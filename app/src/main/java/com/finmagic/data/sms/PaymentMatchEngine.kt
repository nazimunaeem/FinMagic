package com.finmagic.data.sms

import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CreditCard
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matches stored card-payment SMS against bills and applies them.
 *
 * Matching rules:
 * 1. A payment belongs to the card whose visible number ends with the same
 *    tail (e.g. "376***945" and "376948*****2945" both resolve to tail "945").
 * 2. Payments apply chronologically: the oldest unpaid / partially-paid bill is paid first.
 * 3. When a payment covers the remaining balance of a previous month's bill,
 *    that previous bill is auto-flagged as PAID (isPaid = true, isSuperseded = false).
 * 4. Since a new month's bill statement includes any unpaid balance carried over from
 *    previous months, paying off the previous bill also reduces ("flags down")
 *    this month's bill by that payment amount (paidAmount increases, remaining balance
 *    decreases).
 * 5. If this month's bill still has remaining dues (for new purchases this cycle),
 *    it remains unpaid ("not paid but otherwise") with the reduced balance shown.
 *    If the payment covers the full due of this month's bill as well, this month's
 *    bill is also marked as PAID.
 */
@Singleton
class PaymentMatchEngine @Inject constructor(
    private val cardDao: CreditCardDao,
    private val billDao: BillDao,
    private val cardPaymentDao: CardPaymentDao
) {

    suspend fun applyUnappliedPayments(): Int {
        val allPayments = cardPaymentDao.getAllPaymentsOnce()
        if (allPayments.isEmpty()) return 0

        val cards = cardDao.getAllCardsOnce()
        if (cards.isEmpty()) return 0

        var totalAppliedCount = 0

        for (card in cards) {
            val cardPayments = allPayments.filter { payment ->
                isTailMatch(payment.cardTail, card.cardLast4)
            }
            if (cardPayments.isEmpty()) continue

            val bdtApplied = applyPaymentsForCardAndCurrency(card, "BDT", cardPayments.filter { it.amountBdt > 0.0 })
            val usdApplied = applyPaymentsForCardAndCurrency(card, "USD", cardPayments.filter { it.amountUsd > 0.0 })

            totalAppliedCount += (bdtApplied + usdApplied)
        }

        return totalAppliedCount
    }

    fun isTailMatch(paymentTail: String, cardLast4: String): Boolean {
        val cleanPayment = paymentTail.trim()
        val cleanCard = cardLast4.trim()
        if (cleanPayment.isEmpty() || cleanCard.isEmpty()) return false
        return if (cleanPayment.length >= 4 && cleanCard.length >= 4) {
            cleanCard == cleanPayment || cleanCard.takeLast(3) == cleanPayment.takeLast(3)
        } else {
            cleanCard.takeLast(3) == cleanPayment.takeLast(3)
        }
    }

    private suspend fun applyPaymentsForCardAndCurrency(
        card: CreditCard,
        currency: String,
        payments: List<CardPayment>
    ): Int {
        if (payments.isEmpty()) return 0

        val rawBills = billDao.getBillsForCardAndCurrency(card.id, currency)
        if (rawBills.isEmpty()) return 0

        // Sort bills chronologically: oldest due date first, then detectedAt, then id
        val sortedBills = rawBills.sortedWith(
            compareBy(
                { parseDueDateToEpoch(it.dueDate) },
                { it.detectedAt },
                { it.id }
            )
        )

        // Sort payments chronologically by paidAt
        val sortedPayments = payments.sortedBy { it.paidAt }

        class MutableBill(
            val bill: Bill,
            var totalDue: Double = bill.totalDue,
            var paidAmount: Double = if (bill.paidViaSms) 0.0 else bill.paidAmount,
            var isPaid: Boolean = if (bill.paidViaSms) false else bill.isPaid,
            var paidAt: Long? = if (bill.paidViaSms) null else bill.paidAt,
            var paidViaSms: Boolean = false,
            var isSuperseded: Boolean = bill.isSuperseded
        ) {
            val remaining: Double
                get() = maxOf(0.0, totalDue - paidAmount)
        }

        val billStates = sortedBills.map { MutableBill(it) }
        var appliedCount = 0

        for (payment in sortedPayments) {
            val amount = if (currency.equals("USD", ignoreCase = true)) payment.amountUsd else payment.amountBdt
            if (amount <= 0.0) continue

            val paidAt = payment.paidAt

            // Find the oldest unpaid bill
            val targetBillIndex = billStates.indexOfFirst { !it.isPaid && it.remaining > 0.01 }
            if (targetBillIndex == -1) {
                val latest = billStates.lastOrNull()
                if (latest != null) {
                    cardPaymentDao.markApplied(payment.id, latest.bill.id)
                }
                continue
            }

            val targetBill = billStates[targetBillIndex]
            val remainingOnTarget = targetBill.remaining

            // 1. Pay off or reduce target bill (previous bill)
            if (amount >= remainingOnTarget - 0.01) {
                targetBill.paidAmount = targetBill.totalDue
                targetBill.isPaid = true
                targetBill.paidAt = paidAt
                targetBill.paidViaSms = true
                targetBill.isSuperseded = false
            } else {
                targetBill.paidAmount += amount
                targetBill.isPaid = false
                targetBill.paidAt = paidAt
                targetBill.paidViaSms = true
            }

            // 2. Since newer bills issued on or before payment date incorporated
            // previous months' dues, credit this payment amount to those newer bills too
            // ("flag down" this month's bill).
            for (i in (targetBillIndex + 1) until billStates.size) {
                val newerBill = billStates[i]
                val newerDueDateEpoch = parseDueDateToEpoch(newerBill.bill.dueDate)
                val estimatedStatementDate = if (newerBill.bill.detectedAt > 0) {
                    newerBill.bill.detectedAt
                } else {
                    newerDueDateEpoch - (25L * 24 * 60 * 60 * 1000L)
                }

                // A newer bill's statement incorporates the previous debt only if that statement
                // was generated on or before the payment occurred.
                val wasIssuedAtPaymentTime = paidAt >= (estimatedStatementDate - (24L * 60 * 60 * 1000L))

                if (wasIssuedAtPaymentTime) {
                    val newPaid = newerBill.paidAmount + amount
                    if (newPaid >= newerBill.totalDue - 0.01) {
                        newerBill.paidAmount = newerBill.totalDue
                        newerBill.isPaid = true
                        newerBill.paidAt = paidAt
                        newerBill.paidViaSms = true
                        newerBill.isSuperseded = false
                    } else {
                        newerBill.paidAmount = newPaid
                        newerBill.isPaid = false
                        newerBill.paidViaSms = true
                    }
                }
            }

            cardPaymentDao.markApplied(payment.id, targetBill.bill.id)
            appliedCount++
        }

        // Maintain supersede logic:
        // For any bill that is STILL unpaid (!isPaid):
        // If there is a newer unpaid bill after it, mark older unpaid bill isSuperseded = true
        // If it is the latest unpaid bill, isSuperseded = false
        val unpaidIndices = billStates.indices.filter { !billStates[it].isPaid }
        for (idx in billStates.indices) {
            val state = billStates[idx]
            if (state.isPaid) {
                state.isSuperseded = false
            } else if (unpaidIndices.isNotEmpty() && idx < unpaidIndices.last()) {
                state.isSuperseded = true
            } else {
                state.isSuperseded = false
            }
        }

        val updatedBills = billStates.mapNotNull { state ->
            val orig = state.bill
            if (orig.paidAmount != state.paidAmount ||
                orig.isPaid != state.isPaid ||
                orig.paidAt != state.paidAt ||
                orig.paidViaSms != state.paidViaSms ||
                orig.isSuperseded != state.isSuperseded
            ) {
                orig.copy(
                    paidAmount = state.paidAmount,
                    isPaid = state.isPaid,
                    paidAt = state.paidAt,
                    paidViaSms = state.paidViaSms,
                    isSuperseded = state.isSuperseded
                )
            } else null
        }

        if (updatedBills.isNotEmpty()) {
            billDao.updateBills(updatedBills)
        }

        return appliedCount
    }

    fun parseDueDateToEpoch(dueDate: String): Long {
        return try {
            val normalized = BankSmsParser.normalizeDate(dueDate)
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            sdf.isLenient = false
            sdf.parse(normalized)?.time ?: Long.MAX_VALUE
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
    }
}
