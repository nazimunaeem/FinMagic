package com.finmagic.data.sms

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.dao.SmsRecordDao
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.log.ActivityLogger
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.sms.parsers.LedgerDirection
import com.finmagic.data.sms.parsers.SmsClassification
import com.finmagic.notification.NotificationHelper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock

@HiltWorker
class SmsScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val smsReader: SmsReader,
    private val cardDao: CreditCardDao,
    private val billDao: BillDao,
    private val smsRecordDao: SmsRecordDao,
    private val cardPaymentDao: CardPaymentDao,
    private val cardTransactionDao: CardTransactionDao,
    private val paymentMatchEngine: PaymentMatchEngine,
    private val accountRepository: AccountRepository,
    private val activityLogger: ActivityLogger,
    private val preferenceManager: PreferenceManager
    , private val smsTeachingEngine: SmsTeachingEngine
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SmsScanWorker"

        // Process-wide lock: the periodic scan ("sms_scan") and on-demand scans
        // ("sms_scan_now") use different WorkManager unique names and could otherwise
        // run at the same time, parsing the same SMS twice.
        private val scanMutex = kotlinx.coroutines.sync.Mutex()

        /** Card activation SMS: create a CreditCard but treat as noise (no financial data). */
        private val activationPattern = Regex(
            """(?:your\s+)?(?:credit\s+)?card\s+(?:no\.?\s*)?[\d*]+\s+(?:has\s+been\s+)?activat""",
            RegexOption.IGNORE_CASE
        )

        private val cardTailFromActivation = Regex("""\*{2,}(\d{4})""")

        private fun parseActivation(body: String): Pair<String, String>? {
            val m = activationPattern.find(body) ?: return null
            val tail = cardTailFromActivation.find(body)?.groupValues?.get(1) ?: return null
            val bankName = when {
                body.uppercase().contains("EBL") -> "EBL"
                body.uppercase().contains("CITY") || body.uppercase().contains("AMEX") -> "City Bank"
                body.uppercase().contains("BRAC") -> "BRAC Bank"
                body.uppercase().contains("PUBALI") -> "Pubali Bank"
                body.uppercase().contains("PRIME") -> "Prime Bank"
                else -> return null
            }
            return bankName to tail
        }
    }

    override suspend fun doWork(): Result {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            scanMutex.withLock {
                activityLogger.info(TAG, "Starting SMS scan...")
                try {
                    val isFirstScan = !preferenceManager.isFirstScanComplete.first()
                    val afterTimestamp = if (isFirstScan) 0L else System.currentTimeMillis() - (60L * 24 * 60 * 60 * 1000)

                    activityLogger.info(TAG, "Scan mode: ${if (isFirstScan) "FIRST (all SMS)" else "incremental (60 days)"}")

                    // Per-scan settings (Phase 5 debit alerts).
                    val debitAlertsEnabled = preferenceManager.largeDebitAlerts.first() &&
                        preferenceManager.notificationsEnabled.first()
                    val debitThreshold = preferenceManager.largeDebitThreshold.first()

                    var totalRead = 0
                    var parsedCount = 0
                    var errorCount = 0

                    smsReader.readAllSmsStream(afterTimestamp = afterTimestamp).collect { batch ->
                        for (message in batch) {
                            try {
                                totalRead++
                                val parsed = processMessage(
                                    message,
                                    debitAlertsEnabled,
                                    debitThreshold
                                )
                                if (parsed != null) {
                                    parsedCount++
                                }
                            } catch (e: Exception) {
                                errorCount++
                                activityLogger.error(TAG, "Error processing SMS #$totalRead: ${e.message}")
                            }
                        }

                        activityLogger.info(TAG, "Batch done: $totalRead total, $parsedCount parsed, $errorCount errors")
                    }

                    // Remove legacy duplicate rows (same card + month + currency) created
                    // before period normalization existed, then keep only the latest unpaid
                    // bill per card+currency active — older unpaid bills are superseded
                    // (their dues are included in the newer bill).
                    removeDuplicateBills()
                    billDao.markAllButLatestSuperseded()

                    // Apply stored payment SMS to unpaid bills — including payments that
                    // were recorded before their bill statement arrived in this scan.
                    val appliedPayments = paymentMatchEngine.applyUnappliedPayments()
                    if (appliedPayments > 0) {
                        activityLogger.info(TAG, "Auto-applied $appliedPayments card payment(s) to bills")
                    }

                    activityLogger.info(TAG, "SMS scan completed. Total: $totalRead, Parsed: $parsedCount, Errors: $errorCount")

                    val now = System.currentTimeMillis()
                    val ninetyDaysAgo = now - (90L * 24 * 60 * 60 * 1000)
                    smsRecordDao.deleteOldParsedSms(ninetyDaysAgo)

                    // Clean up SMS rows duplicated by the old concurrent-scan race:
                    // keep one row per body, carrying over the parsed flag.
                    smsRecordDao.mergeParsedStatusOfDuplicates()
                    smsRecordDao.deleteDuplicateSmsRecords()

                    val thirtyDaysAgo = now - (30L * 24 * 60 * 60 * 1000)
                    billDao.clearSmsBodyForOldPaidBills(thirtyDaysAgo)

                    preferenceManager.setFirstScanComplete(true)

                    Result.success()
                } catch (e: Exception) {
                activityLogger.error(TAG, "Fatal error during SMS scan: ${e.message}")
                Result.retry()
                }
            }
        }
    }

    private suspend fun processMessage(
        message: SmsMessage,
        debitAlertsEnabled: Boolean,
        debitThreshold: Double
    ): ParsedBill? {
        val existingRecord = smsRecordDao.findByBody(message.body)
        var smsId = existingRecord?.id ?: 0L

        if (existingRecord == null) {
            smsId = smsRecordDao.insert(
                SmsRecord(
                    address = message.address,
                    body = message.body,
                    timestamp = message.date
                )
            )
        }

        when (val result = SmsClassifier.classify(message.address, message.body, message.date, message.subscriptionId)) {
            is SmsClassification.BillStatement -> {
                markParsed(smsId)
                return storeBill(result.bankSms, message)
            }

            is SmsClassification.CardPayment -> {
                markParsed(smsId)
                storeCardPayment(result.payment, message)
            }

            is SmsClassification.AcctTxn -> {
                markParsed(smsId)
                if (accountRepository.store(result, message.body, smsId.takeIf { it > 0 })) {
                    maybeNotifyLargeDebit(result.entry, debitAlertsEnabled, debitThreshold)
                    activityLogger.info(
                        TAG,
                        "Account txn: ${result.entry.institution} ${result.entry.direction} ${result.entry.amount}"
                    )
                }
            }

            is SmsClassification.AtmWithdraw -> {
                markParsed(smsId)
                if (accountRepository.store(result, message.body, smsId.takeIf { it > 0 })) {
                    maybeNotifyLargeDebit(result.entry, debitAlertsEnabled, debitThreshold)
                    activityLogger.info(TAG, "ATM withdrawal → Cash: ${result.entry.amount}")
                }
            }

            is SmsClassification.WalletTxn -> {
                markParsed(smsId)
                if (accountRepository.store(result, message.body, smsId.takeIf { it > 0 })) {
                    activityLogger.info(
                        TAG,
                        "Wallet txn: ${result.entry.institution} ${result.entry.direction} ${result.entry.amount}"
                    )
                }
            }

            is SmsClassification.WalletToCash -> {
                markParsed(smsId)
                accountRepository.store(result, message.body, smsId.takeIf { it > 0 })
            }

            // Card POS purchases → persisted so they appear in the Expenses tab.
            is SmsClassification.CardTxn -> {
                markParsed(smsId)
                storeCardTransaction(result, message)
            }

            is SmsClassification.Unparsed -> {
                if (smsTeachingEngine.applyFirstMatch(SmsRecord(smsId, message.address, message.body, message.date))) {
                    markParsed(smsId)
                }
            }
            is SmsClassification.Noise -> {
                // Card activation notices are noise but should auto-create the credit card.
                parseActivation(message.body)?.let { (bankName, tail) ->
                    ensureCreditCardExists(bankName, tail)
                }
            }
        }

        return null
    }

    private suspend fun markParsed(smsId: Long) {
        if (smsId > 0) {
            smsRecordDao.updateParsedStatus(smsId, true)
        }
    }

    private suspend fun maybeNotifyLargeDebit(
        entry: com.finmagic.data.sms.parsers.LedgerEntry,
        enabled: Boolean,
        threshold: Double
    ) {
        if (!enabled) return
        if (entry.direction != LedgerDirection.OUT) return
        if (entry.amount < threshold) return
        NotificationHelper.showLargeDebitAlert(
            context = applicationContext,
            institution = entry.institution,
            accountMask = entry.accountMask,
            amount = entry.amount,
            counterparty = entry.counterparty
        )
    }

    private suspend fun storeCardPayment(payment: ParsedPayment, message: SmsMessage) {
        ensureCreditCardExists(payment.bankName, payment.cardTail)
        val inserted = cardPaymentDao.insert(
            CardPayment(
                cardTail = payment.cardTail,
                amountBdt = payment.amountBdt,
                amountUsd = payment.amountUsd,
                paidAt = PaymentSmsParser.paymentDateToEpoch(payment.paymentDate) ?: message.date,
                smsBody = message.body,
                detectedAt = message.date
            )
        )
        if (inserted != -1L) {
            activityLogger.info(
                TAG,
                "Card payment recorded: •••${payment.cardTail} BDT ${payment.amountBdt} USD ${payment.amountUsd}"
            )
        }
    }

    private suspend fun storeCardTransaction(txn: SmsClassification.CardTxn, message: SmsMessage) {
        ensureCreditCardExists(txn.bankName, txn.cardTail)
        val inserted = cardTransactionDao.insert(
            CardTransaction(
                cardTail = txn.cardTail,
                bankName = txn.bankName ?: "Card",
                merchant = txn.merchant ?: "Purchase",
                amount = txn.amount,
                occurredAt = txn.occurredAt ?: message.date,
                balanceAfter = txn.balanceAfter,
                smsBody = message.body,
                detectedAt = message.date
            )
        )
        if (inserted != -1L) {
            activityLogger.info(
                TAG,
                "Card POS recorded: ${txn.bankName} •••${txn.cardTail} ${
                    txn.merchant
                } ${txn.amount}"
            )
        }
    }

    /** Auto-create a CreditCard record if one doesn't exist for the given bank+tail. */
    private suspend fun ensureCreditCardExists(bankName: String?, cardTail: String) {
        if (bankName.isNullOrBlank() || cardTail.isBlank()) return
        val existing = cardDao.findCardFlexible(bankName, cardTail)
        if (existing == null) {
            activityLogger.info(TAG, "Auto-discovered card: $bankName •••• $cardTail")
            cardDao.insertCard(
                CreditCard(
                    bankName = bankName,
                    cardLast4 = cardTail
                )
            )
        }
    }

    private suspend fun storeBill(parsed: ParsedBill, message: SmsMessage): ParsedBill? {
        var card = cardDao.findCard(parsed.bankName, parsed.cardLast4)
        if (card == null) {
            activityLogger.info(TAG, "New card: ${parsed.bankName} •••• ${parsed.cardLast4}")
            cardDao.insertCard(
                CreditCard(
                    bankName = parsed.bankName,
                    cardLast4 = parsed.cardLast4,
                    clientId = parsed.clientId
                )
            )
            card = cardDao.findCard(parsed.bankName, parsed.cardLast4)
        }

        if (card != null) {
            // Compare periods in canonical form so format changes ("Aug-2026" vs
            // "AUG-26") don't create duplicate bills for the same month. Currency is
            // part of the identity: an EBL card can have a BDT and a USD bill for the
            // same statement month.
            val normalizedPeriod = BankSmsParser.normalizeBillPeriod(parsed.billPeriod)
            val isDuplicate = billDao.getBillsForCardOnce(card.id).any {
                BankSmsParser.normalizeBillPeriod(it.billPeriod) == normalizedPeriod &&
                    it.currency.equals(parsed.currency, ignoreCase = true)
            }

            if (!isDuplicate) {
                billDao.insertBill(
                    Bill(
                        cardId = card.id,
                        billPeriod = normalizedPeriod,
                        totalDue = parsed.totalDue,
                        minDue = parsed.minDue,
                        dueDate = parsed.dueDate,
                        smsBody = message.body,
                        currency = parsed.currency,
                        detectedAt = message.date
                    )
                )
            }
        }

        return parsed
    }

    private suspend fun removeDuplicateBills() {
        val allBills = billDao.getAllBillsOnce()
        val duplicateIds = allBills
            .groupBy { Triple(it.cardId, BankSmsParser.normalizeBillPeriod(it.billPeriod), it.currency.uppercase()) }
            .values
            .flatMap { group ->
                if (group.size <= 1) emptyList()
                else group.sortedBy { it.id }.dropLast(1).map { it.id }
            }
        if (duplicateIds.isNotEmpty()) {
            activityLogger.info(TAG, "Removing ${duplicateIds.size} duplicate bill row(s)")
            billDao.deleteBillsByIds(duplicateIds)
        }
    }
}
