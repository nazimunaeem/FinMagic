package com.finmagic.data.repository

import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

@Singleton
class CardRepository @Inject constructor(
    private val cardDao: CreditCardDao,
    private val cardTransactionDao: CardTransactionDao
) {
    fun getActiveCards(): Flow<List<CreditCard>> = cardDao.getActiveCards()

    fun getAllCards(): Flow<List<CreditCard>> = cardDao.getAllCards()

    fun observeCardById(cardId: Long): Flow<CreditCard?> =
        cardDao.getAllCards().map { list -> list.firstOrNull { it.id == cardId } }

    suspend fun getCardById(id: Long): CreditCard? = cardDao.getCardById(id)

    suspend fun findCard(bankName: String, last4: String): CreditCard? =
        cardDao.findCard(bankName, last4)

    suspend fun addCard(card: CreditCard): Long = cardDao.insertCard(card)

    suspend fun updateCard(card: CreditCard) = cardDao.updateCard(card)

    suspend fun updateCardDetails(
        card: CreditCard,
        nickname: String,
        creditLimit: Double,
        currency: String,
        newOutstanding: Double? = null,
        clearOutstandingAnchor: Boolean = false
    ): CreditCard {
        val updated = when {
            clearOutstandingAnchor -> card.copy(
                cardNickname = nickname.trim(),
                creditLimit = max(0.0, creditLimit),
                creditLimitCurrency = currency.ifBlank { "BDT" },
                outstandingAnchor = null,
                outstandingAnchorAt = 0L
            )
            newOutstanding != null -> card.copy(
                cardNickname = nickname.trim(),
                creditLimit = max(0.0, creditLimit),
                creditLimitCurrency = currency.ifBlank { "BDT" },
                outstandingAnchor = max(0.0, newOutstanding),
                outstandingAnchorAt = System.currentTimeMillis()
            )
            else -> card.copy(
                cardNickname = nickname.trim(),
                creditLimit = max(0.0, creditLimit),
                creditLimitCurrency = currency.ifBlank { "BDT" }
            )
        }
        cardDao.updateCard(updated)
        return updated
    }

    suspend fun recordManualCardSpend(
        card: CreditCard,
        merchant: String,
        amount: Double,
        occurredAt: Long,
        category: String?
    ): Long {
        val trimmedMerchant = merchant.trim().ifBlank { "Card Spend" }
        if (amount <= 0.0) return -1L
        val insertedId = cardTransactionDao.insert(
            CardTransaction(
                cardTail = card.cardLast4,
                bankName = card.bankName,
                merchant = trimmedMerchant,
                amount = amount,
                occurredAt = occurredAt,
                category = category?.trim()?.ifBlank { null },
                smsBody = "manual:${UUID.randomUUID()}"
            )
        )
        if (insertedId > 0L && card.outstandingAnchor != null && occurredAt <= card.outstandingAnchorAt) {
            cardDao.updateCard(
                card.copy(outstandingAnchor = max(0.0, card.outstandingAnchor + amount))
            )
        }
        return insertedId
    }

    suspend fun deleteCardSpend(txnId: Long, manualOnly: Boolean) {
        val txn = cardTransactionDao.findByIdOnce(txnId) ?: return
        if (manualOnly && !txn.smsBody.startsWith("manual:")) return
        if (manualOnly) {
            cardTransactionDao.deleteManualById(txnId)
        } else {
            cardTransactionDao.deleteById(txnId)
        }
        val cards = cardDao.getAllCardsOnce()
        val matchingCard = cards.firstOrNull { cardTransactionBelongsTo(txn, it) }
        if (matchingCard != null &&
            matchingCard.outstandingAnchor != null &&
            txn.occurredAt <= matchingCard.outstandingAnchorAt
        ) {
            cardDao.updateCard(
                matchingCard.copy(
                    outstandingAnchor = max(0.0, matchingCard.outstandingAnchor - txn.amount)
                )
            )
        }
    }

    suspend fun deleteCard(card: CreditCard) = cardDao.deleteCard(card)

    suspend fun deactivateCard(cardId: Long) = cardDao.deactivateCard(cardId)

    companion object {
        fun normalizeCardBank(name: String?): String {
            val n = name?.trim()?.lowercase(Locale.US) ?: return ""
            return when {
                n.contains("eastern") || n.contains("ebl") -> "ebl"
                n.contains("city") -> "city"
                n.contains("brac") -> "brac"
                n.contains("pubali") -> "pubali"
                n.contains("prime") -> "prime"
                n.contains("dbbl") || n.contains("dutch") -> "dbbl"
                n.contains("aibl") || n.contains("arafah") -> "aibl"
                n.contains("ibbl") || n.contains("islami") -> "ibbl"
                n == "bank" || n == "card" || n == "unknown bank" -> ""
                else -> n.replace("bank", "").trim()
            }
        }

        fun cardTransactionBelongsTo(tx: CardTransaction, card: CreditCard): Boolean {
            val txTail = tx.cardTail.filter { it.isDigit() }
            val cardDigits = card.cardLast4.filter { it.isDigit() }
            if (txTail.isEmpty() || cardDigits.isEmpty()) return false
            val tailMatches = if (txTail.length >= 4 && cardDigits.length >= 4) {
                txTail.takeLast(4) == cardDigits.takeLast(4)
            } else {
                txTail.takeLast(3) == cardDigits.takeLast(3)
            }
            if (!tailMatches) return false
            val txBank = normalizeCardBank(tx.bankName)
            val cardBank = normalizeCardBank(card.bankName)
            return txBank.isEmpty() || cardBank.isEmpty() || txBank == cardBank
        }

        fun cardPaymentBelongsTo(payment: CardPayment, card: CreditCard): Boolean {
            val pDigits = payment.cardTail.filter { it.isDigit() }
            val cDigits = card.cardLast4.filter { it.isDigit() }
            if (pDigits.isEmpty() || cDigits.isEmpty()) return false
            return if (pDigits.length >= 4 && cDigits.length >= 4) {
                pDigits.takeLast(4) == cDigits.takeLast(4)
            } else {
                pDigits.takeLast(3) == cDigits.takeLast(3)
            }
        }

        /**
         * Computes live outstanding debt for a credit card (distinct from Bills):
         * - When [CreditCard.outstandingAnchor] is set, starts from that anchor at
         *   [CreditCard.outstandingAnchorAt], adds card purchases after that timestamp,
         *   and subtracts card payments after that timestamp.
         * - Otherwise, derives outstanding from unpaid active bills + card purchases after
         *   the latest statement minus any excess card payments after that statement.
         */
        fun computeCardOutstanding(
            card: CreditCard,
            cardBills: List<BillWithCard>,
            allCardTxns: List<CardTransaction>,
            allPayments: List<CardPayment>,
            asOfMillis: Long = Long.MAX_VALUE,
            nowMillis: Long = System.currentTimeMillis()
        ): Double {
            val cardTxns = allCardTxns.filter { it.occurredAt <= asOfMillis && cardTransactionBelongsTo(it, card) }
            val cardPayments = allPayments.filter { it.paidAt <= asOfMillis && cardPaymentBelongsTo(it, card) }

            val anchor = card.outstandingAnchor
            if (anchor != null && card.outstandingAnchorAt <= asOfMillis) {
                val spendSinceAnchor = cardTxns
                    .filter { it.occurredAt > card.outstandingAnchorAt }
                    .sumOf { it.amount }
                val smsPaidSinceAnchor = cardPayments
                    .filter { it.paidAt > card.outstandingAnchorAt }
                    .sumOf { it.amountBdt }
                val limitCurrency = card.creditLimitCurrency.ifBlank { "BDT" }
                val manualBillPaidSinceAnchor = cardBills
                    .filter { bill ->
                        !bill.paidViaSms &&
                            bill.paidAt != null &&
                            bill.paidAt > card.outstandingAnchorAt &&
                            bill.paidAt <= asOfMillis &&
                            bill.paidAmount > 0.0 &&
                            (bill.currency.equals(limitCurrency, ignoreCase = true) ||
                                bill.currency.equals("BDT", ignoreCase = true))
                    }
                    .groupBy { it.paidAt!! }
                    .entries
                    .sumOf { (paidAt, billsAtTimestamp) ->
                        // Credit carryover sets the same paidAt on newer bills; earliest-detected
                        // bill holds the primary payment amount. Also avoid double-counting if
                        // a CardPayment row was inserted at the same time (e.g. taught SMS).
                        val primaryAmount = billsAtTimestamp
                            .minByOrNull { it.detectedAt }
                            ?.paidAmount ?: 0.0
                        val alreadyInCardPayments = cardPayments.any { p ->
                            kotlin.math.abs(p.paidAt - paidAt) <= 60_000L &&
                                kotlin.math.abs(p.amountBdt - primaryAmount) <= 1.0
                        }
                        if (alreadyInCardPayments) 0.0 else primaryAmount
                    }
                return max(0.0, anchor + spendSinceAnchor - smsPaidSinceAnchor - manualBillPaidSinceAnchor)
            }

            val todayStart = Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

            val activeBillsAtCutoff = if (asOfMillis >= todayStart) {
                cardBills.filter { !it.isSuperseded }
            } else {
                // Historical statements carry forward unpaid balances into the next statement,
                // so keep at most the latest non-superseded bill per currency as of cutoff.
                cardBills
                    .filter { !it.isSuperseded && it.detectedAt <= asOfMillis }
                    .groupBy { it.currency.ifBlank { "BDT" }.uppercase(Locale.US) }
                    .values
                    .mapNotNull { group ->
                        group.maxWithOrNull(compareBy<BillWithCard>({ it.detectedAt }, { it.billId }))
                    }
            }

            val unpaidBillRemaining = if (asOfMillis >= todayStart) {
                activeBillsAtCutoff.filter { !it.isPaid }
                    .sumOf { max(0.0, it.totalDue - it.paidAmount) }
            } else {
                activeBillsAtCutoff.sumOf { bill ->
                    val paidAt = bill.paidAt
                    when {
                        !bill.isPaid -> {
                            val effectivePaid = if (paidAt != null && paidAt <= asOfMillis) bill.paidAmount else 0.0
                            max(0.0, bill.totalDue - effectivePaid)
                        }
                        paidAt != null && paidAt > asOfMillis -> bill.totalDue
                        else -> 0.0
                    }
                }
            }

            val latestStatementAt = activeBillsAtCutoff.maxOfOrNull { it.detectedAt } ?: 0L
            if (latestStatementAt <= 0L) {
                if (anchor != null && asOfMillis < card.outstandingAnchorAt) {
                    val spendBetween = allCardTxns
                        .filter {
                            it.occurredAt > asOfMillis &&
                                it.occurredAt <= card.outstandingAnchorAt &&
                                cardTransactionBelongsTo(it, card)
                        }
                        .sumOf { it.amount }
                    val smsPaidBetween = allPayments
                        .filter {
                            it.paidAt > asOfMillis &&
                                it.paidAt <= card.outstandingAnchorAt &&
                                cardPaymentBelongsTo(it, card)
                        }
                        .sumOf { it.amountBdt }
                    return max(0.0, anchor - spendBetween + smsPaidBetween)
                }
                val totalSpend = cardTxns.sumOf { it.amount }
                val totalPaid = cardPayments.sumOf { it.amountBdt }
                return max(0.0, totalSpend - totalPaid)
            }

            val postStatementSpend = cardTxns
                .filter { it.occurredAt > latestStatementAt }
                .sumOf { it.amount }

            val postStatementPayments = cardPayments
                .filter { it.paidAt > latestStatementAt }
                .sumOf { it.amountBdt }

            val smsAbsorbedAfterStmt = activeBillsAtCutoff
                .filter {
                    it.paidViaSms &&
                        it.currency.equals("BDT", ignoreCase = true) &&
                        it.paidAt != null &&
                        it.paidAt > latestStatementAt &&
                        it.paidAt <= asOfMillis
                }
                .maxOfOrNull { it.paidAmount } ?: 0.0

            val unabsorbedPostStmtPayments = max(0.0, postStatementPayments - smsAbsorbedAfterStmt)

            return max(0.0, unpaidBillRemaining + postStatementSpend - unabsorbedPostStmtPayments)
        }
    }
}
