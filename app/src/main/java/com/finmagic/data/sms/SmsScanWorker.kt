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
import com.finmagic.notification.SmsScheduler
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
    private val preferenceManager: PreferenceManager,
    private val smsTeachingEngine: SmsTeachingEngine,
    private val scanStatusManager: SmsScanStatusManager
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "SmsScanWorker"

        // Process-wide lock: the periodic scan ("sms_scan") and on-demand scans
        // ("sms_scan_now") use different WorkManager unique names and could otherwise
        // run at the same time, parsing the same SMS twice.
        private val scanMutex = kotlinx.coroutines.sync.Mutex()
    }

    override suspend fun doWork(): Result {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            scanMutex.withLock {
                val scanMode = inputData.getString(SmsScheduler.KEY_SCAN_MODE) ?: SmsScheduler.MODE_STANDARD
                activityLogger.info(TAG, "Starting SMS scan (mode=$scanMode)...")
                var parsedCount = 0
                try {
                    val debitAlertsEnabled = preferenceManager.largeDebitAlerts.first() &&
                        preferenceManager.notificationsEnabled.first()
                    val debitThreshold = preferenceManager.largeDebitThreshold.first()
                    val isFirstScan = !preferenceManager.isFirstScanComplete.first()

                    if (scanMode == SmsScheduler.MODE_STARTUP_RECENT_50) {
                        scanStatusManager.startScan(
                            phase = ScanPhase.RECENT_50,
                            message = "Scanning last 50 SMS...",
                            total = 50
                        )
                        val recent = smsReader.readRecentSms(count = 50).reversed()
                        val filteredRecent = recent.filter { smsReader.matchesFinancialKeywords(it.address, it.body) }
                        var processedRecent = 0
                        for (message in filteredRecent) {
                            processedRecent++
                            try {
                                val parsed = processMessage(message, debitAlertsEnabled, debitThreshold)
                                if (parsed != null) parsedCount++
                            } catch (e: Exception) {
                                activityLogger.error(TAG, "Error processing recent SMS #$processedRecent: ${e.message}")
                            }
                            scanStatusManager.updateProgress(
                                phase = ScanPhase.RECENT_50,
                                processed = processedRecent,
                                total = filteredRecent.size.coerceAtLeast(1),
                                parsed = parsedCount,
                                message = "Scanning last 50 SMS ($processedRecent/${filteredRecent.size})..."
                            )
                        }
                        finalizeScanPass()

                        val totalAccountBal = accountRepository.getTotalDisplayedBalanceOnce()
                        val totalUnpaidBills = billDao.getTotalUnpaidOnce()
                        val balancesZero = totalAccountBal < 0.01 && totalUnpaidBills < 0.01

                        if (balancesZero || isFirstScan) {
                            activityLogger.info(TAG, "Balances are zero or first scan pending; starting auto full scan")
                            scanStatusManager.startScan(
                                phase = ScanPhase.FULL_AUTO,
                                message = if (balancesZero) {
                                    "Balances are zero — auto full SMS scan..."
                                } else {
                                    "Running initial full SMS scan..."
                                }
                            )
                            parsedCount += runStreamScan(
                                afterTimestamp = 0L,
                                phase = ScanPhase.FULL_AUTO,
                                debitAlertsEnabled = debitAlertsEnabled,
                                debitThreshold = debitThreshold,
                                label = "Auto full scan"
                            )
                            finalizeScanPass()
                        }
                    } else {
                        val afterTimestamp = if (isFirstScan || scanMode == SmsScheduler.MODE_FULL) {
                            0L
                        } else {
                            System.currentTimeMillis() - (60L * 24 * 60 * 60 * 1000)
                        }
                        val phase = if (afterTimestamp == 0L) ScanPhase.FULL_AUTO else ScanPhase.MANUAL_OR_PERIODIC
                        val label = if (afterTimestamp == 0L) "Full SMS scan" else "Scanning recent SMS"
                        scanStatusManager.startScan(phase = phase, message = "$label...")

                        parsedCount += runStreamScan(
                            afterTimestamp = afterTimestamp,
                            phase = phase,
                            debitAlertsEnabled = debitAlertsEnabled,
                            debitThreshold = debitThreshold,
                            label = label
                        )
                        finalizeScanPass()

                        // If balances remain zero after an incremental scan, fall back to a full scan automatically.
                        if (afterTimestamp > 0L) {
                            val totalAccountBal = accountRepository.getTotalDisplayedBalanceOnce()
                            val totalUnpaidBills = billDao.getTotalUnpaidOnce()
                            if (totalAccountBal < 0.01 && totalUnpaidBills < 0.01) {
                                activityLogger.info(TAG, "Balances are zero after incremental scan; running auto full scan")
                                scanStatusManager.startScan(
                                    phase = ScanPhase.FULL_AUTO,
                                    message = "Balances are zero — auto full SMS scan..."
                                )
                                parsedCount += runStreamScan(
                                    afterTimestamp = 0L,
                                    phase = ScanPhase.FULL_AUTO,
                                    debitAlertsEnabled = debitAlertsEnabled,
                                    debitThreshold = debitThreshold,
                                    label = "Auto full scan"
                                )
                                finalizeScanPass()
                            }
                        }
                    }

                    val now = System.currentTimeMillis()
                    val ninetyDaysAgo = now - (90L * 24 * 60 * 60 * 1000)
                    smsRecordDao.deleteOldParsedSms(ninetyDaysAgo)

                    smsRecordDao.mergeParsedStatusOfDuplicates()
                    smsRecordDao.deleteDuplicateSmsRecords()

                    val thirtyDaysAgo = now - (30L * 24 * 60 * 60 * 1000)
                    billDao.clearSmsBodyForOldPaidBills(thirtyDaysAgo)

                    preferenceManager.setFirstScanComplete(true)
                    scanStatusManager.finishScan(parsedCount)
                    Result.success()
                } catch (e: Exception) {
                    activityLogger.error(TAG, "Fatal error during SMS scan: ${e.message}")
                    scanStatusManager.finishScan(parsedCount, "Scan paused")
                    Result.retry()
                }
            }
        }
    }

    private suspend fun runStreamScan(
        afterTimestamp: Long,
        phase: ScanPhase,
        debitAlertsEnabled: Boolean,
        debitThreshold: Double,
        label: String
    ): Int {
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
            scanStatusManager.updateProgress(
                phase = phase,
                processed = totalRead,
                total = 0,
                parsed = parsedCount,
                message = "$label ($totalRead SMS checked)..."
            )
            activityLogger.info(TAG, "Batch done: $totalRead total, $parsedCount parsed, $errorCount errors")
        }
        activityLogger.info(TAG, "$label completed. Total: $totalRead, Parsed: $parsedCount, Errors: $errorCount")
        return parsedCount
    }

    private suspend fun finalizeScanPass() {
        removeDuplicateBills()
        billDao.markAllButLatestSuperseded()
        val appliedPayments = paymentMatchEngine.applyUnappliedPayments()
        if (appliedPayments > 0) {
            activityLogger.info(TAG, "Auto-applied $appliedPayments card payment(s) to bills")
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
                // Card activation notices are noise - don't auto-create credit card
                // to avoid creating cards without bill history.
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
        // Don't auto-create CreditCard from payment alone - only from bill statements.
        // The payment will still be recorded and linked when a bill arrives.
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
        // Don't auto-create CreditCard from POS transaction alone - only from bill statements.
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

    private suspend fun storeBill(parsed: ParsedBill, message: SmsMessage): ParsedBill? {
        if (parsed.totalDue <= 0.0 && parsed.minDue <= 0.0) {
            activityLogger.info(TAG, "Skipping zero-balance bill for ${parsed.bankName} •••• ${parsed.cardLast4}")
            return parsed
        }

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
