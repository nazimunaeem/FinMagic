package com.finmagic.data.repository

import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class CardOutstandingTest {

    private fun ts(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun card(
        id: Long = 1L,
        anchor: Double? = null,
        anchorAt: Long = 0L
    ) = CreditCard(
        id = id,
        bankName = "City Bank",
        cardLast4 = "4321",
        cardNickname = "City Visa",
        creditLimit = 200_000.0,
        outstandingAnchor = anchor,
        outstandingAnchorAt = anchorAt
    )

    private fun bill(
        id: Long = 10L,
        totalDue: Double,
        paidAmount: Double = 0.0,
        isPaid: Boolean = false,
        paidViaSms: Boolean = false,
        detectedAt: Long,
        paidAt: Long? = null
    ) = BillWithCard(
        billId = id,
        cardId = 1L,
        bankName = "City Bank",
        cardLast4 = "4321",
        cardNickname = "City Visa",
        billPeriod = "Mar 2026",
        totalDue = totalDue,
        minDue = 1_000.0,
        dueDate = "25/03/2026",
        isPaid = isPaid,
        isSuperseded = false,
        paidAmount = paidAmount,
        paidAt = paidAt,
        paidViaSms = paidViaSms,
        smsBody = null,
        currency = "BDT",
        detectedAt = detectedAt
    )

    private fun spend(id: Long, amount: Double, occurredAt: Long) = CardTransaction(
        id = id,
        cardTail = "4321",
        bankName = "City Bank",
        merchant = "Store $id",
        amount = amount,
        occurredAt = occurredAt,
        smsBody = "manual:$id"
    )

    private fun payment(id: Long, amount: Double, paidAt: Long) = CardPayment(
        id = id,
        cardTail = "4321",
        amountBdt = amount,
        amountUsd = 0.0,
        paidAt = paidAt,
        smsBody = "payment:$id"
    )

    @Test
    fun deriveOutstandingFromBillsAndPostStatementActivity() {
        val stmtTime = ts(2026, Calendar.MARCH, 5)
        val afterStmtSpend = ts(2026, Calendar.MARCH, 10)
        val afterStmtPay = ts(2026, Calendar.MARCH, 15)
        val now = ts(2026, Calendar.MARCH, 20)

        val c = card()
        val bills = listOf(bill(totalDue = 25_000.0, paidAmount = 10_000.0, paidViaSms = true, detectedAt = stmtTime, paidAt = afterStmtPay))
        val txns = listOf(spend(1L, 4_500.0, afterStmtSpend))
        val payments = listOf(payment(1L, 10_000.0, afterStmtPay))

        // Unpaid bill remaining = 15,000 + post-statement spend 4,500 = 19,500
        // (the 10,000 SMS payment was already absorbed into paidAmount)
        val outstanding = CardRepository.computeCardOutstanding(
            card = c,
            cardBills = bills,
            allCardTxns = txns,
            allPayments = payments,
            nowMillis = now
        )
        assertEquals(19_500.0, outstanding, 0.01)
    }

    @Test
    fun outstandingAnchorOverridesDerivedAndTracksSubsequentSpendAndPayments() {
        val anchorTime = ts(2026, Calendar.MARCH, 10)
        val beforeAnchorSpend = ts(2026, Calendar.MARCH, 4)
        val afterAnchorSpend = ts(2026, Calendar.MARCH, 12)
        val afterAnchorPayment = ts(2026, Calendar.MARCH, 14)

        val c = card(anchor = 30_000.0, anchorAt = anchorTime)
        val bills = listOf(bill(totalDue = 50_000.0, detectedAt = ts(2026, Calendar.MARCH, 1)))
        val txns = listOf(
            spend(1L, 5_000.0, beforeAnchorSpend),
            spend(2L, 3_500.0, afterAnchorSpend)
        )
        val payments = listOf(
            payment(1L, 8_000.0, afterAnchorPayment)
        )

        val live = CardRepository.computeCardOutstanding(c, bills, txns, payments)
        // 30,000 + 3,500 - 8,000 = 25,500 (pre-anchor spend and bill are absorbed by anchor)
        assertEquals(25_500.0, live, 0.01)

        // Historical check before anchorTime falls back to derived bills/txns before that date
        val beforeAnchor = CardRepository.computeCardOutstanding(
            card = c,
            cardBills = bills,
            allCardTxns = txns,
            allPayments = payments,
            asOfMillis = ts(2026, Calendar.MARCH, 6),
            nowMillis = ts(2026, Calendar.MARCH, 20)
        )
        assertEquals(55_000.0, beforeAnchor, 0.01)

        // Clearing anchor reverts to derived model: 50,000 + 5,000 + 3,500 - 8,000 = 50,500
        val cleared = CardRepository.computeCardOutstanding(
            card = c.copy(outstandingAnchor = null, outstandingAnchorAt = 0L),
            cardBills = bills,
            allCardTxns = txns,
            allPayments = payments
        )
        assertEquals(50_500.0, cleared, 0.01)
    }

    @Test
    fun manualBillPaymentDeductsFromOutstandingAnchorWithoutDoubleCounting() {
        val stmtTime = ts(2026, Calendar.MARCH, 5)
        val anchorTime = ts(2026, Calendar.MARCH, 10)
        val manualPayTime = ts(2026, Calendar.MARCH, 15)
        val smsPayTime = ts(2026, Calendar.MARCH, 18)

        // Outstanding set manually to 40,000 even though active bill is 15,000
        val c = card(anchor = 40_000.0, anchorAt = anchorTime)

        // Bill 1 (15,000) manually marked paid after anchorTime; newer bill (20,000) got carryover at same timestamp
        val bills = listOf(
            bill(
                id = 10L,
                totalDue = 15_000.0,
                paidAmount = 15_000.0,
                isPaid = true,
                paidViaSms = false,
                detectedAt = stmtTime,
                paidAt = manualPayTime
            ),
            bill(
                id = 11L,
                totalDue = 20_000.0,
                paidAmount = 15_000.0,
                isPaid = false,
                paidViaSms = false,
                detectedAt = ts(2026, Calendar.MARCH, 9),
                paidAt = manualPayTime
            )
        )

        val afterManualBillPay = CardRepository.computeCardOutstanding(
            card = c,
            cardBills = bills,
            allCardTxns = emptyList(),
            allPayments = emptyList()
        )
        // 40,000 - 15,000 = 25,000 (carryover duplicate at same paidAt is NOT double-deducted)
        assertEquals(25_000.0, afterManualBillPay, 0.01)

        // Adding a CardPayment SMS of 5,000 later deducts an additional 5,000 -> 20,000
        val afterSmsPay = CardRepository.computeCardOutstanding(
            card = c,
            cardBills = bills,
            allCardTxns = emptyList(),
            allPayments = listOf(payment(1L, 5_000.0, smsPayTime))
        )
        assertEquals(20_000.0, afterSmsPay, 0.01)
    }
}
