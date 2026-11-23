package com.finmagic.ui.creditcards

import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class CreditCardsMatchingTest {

    private fun makeCard(
        id: Long = 1L,
        bankName: String = "City Bank",
        last4: String = "1234"
    ) = CreditCard(
        id = id,
        bankName = bankName,
        cardLast4 = last4
    )

    private fun makeTxn(
        cardTail: String = "1234",
        bankName: String = "City Bank",
        occurredAt: Long = System.currentTimeMillis()
    ) = CardTransaction(
        cardTail = cardTail,
        bankName = bankName,
        merchant = "Test Merchant",
        amount = 500.0,
        occurredAt = occurredAt,
        smsBody = "test-$cardTail-$bankName-$occurredAt"
    )

    private fun makeBill(
        billPeriod: String = "Nov-2026",
        dueDate: String = "25/11/2026",
        detectedAt: Long = System.currentTimeMillis()
    ) = BillWithCard(
        billId = 1L,
        cardId = 1L,
        bankName = "City Bank",
        cardLast4 = "1234",
        cardNickname = "",
        billPeriod = billPeriod,
        totalDue = 10000.0,
        minDue = 500.0,
        dueDate = dueDate,
        isPaid = false,
        paidAmount = 0.0,
        paidAt = null,
        smsBody = null,
        currency = "BDT",
        detectedAt = detectedAt
    )

    @Test
    fun normalizeCardBank_mapsKnownVariants() {
        assertEquals("ebl", normalizeCardBank("Eastern Bank PLC"))
        assertEquals("ebl", normalizeCardBank("EBL"))
        assertEquals("city", normalizeCardBank("City Bank"))
        assertEquals("brac", normalizeCardBank("BRAC Bank"))
        assertEquals("dbbl", normalizeCardBank("Dutch-Bangla Bank"))
        assertEquals("aibl", normalizeCardBank("Al-Arafah Islami Bank"))
        assertEquals("ibbl", normalizeCardBank("IBBL"))
        assertEquals("", normalizeCardBank("Unknown Bank"))
        assertEquals("", normalizeCardBank(null))
    }

    @Test
    fun belongsTo_matchesFourDigitTailAndSameBank() {
        val card = makeCard(bankName = "City Bank", last4 = "4567")
        val matchingTx = makeTxn(cardTail = "4567", bankName = "City")
        val wrongTailTx = makeTxn(cardTail = "9999", bankName = "City")

        assertTrue(matchingTx.belongsTo(card))
        assertFalse(wrongTailTx.belongsTo(card))
    }

    @Test
    fun belongsTo_matchesThreeDigitAmexTail() {
        val amexCard = makeCard(bankName = "City Bank", last4 = "2891")
        val amex3DigitTx = makeTxn(cardTail = "891", bankName = "City Bank")
        val wrong3DigitTx = makeTxn(cardTail = "892", bankName = "City Bank")

        assertTrue(amex3DigitTx.belongsTo(amexCard))
        assertFalse(wrong3DigitTx.belongsTo(amexCard))
    }

    @Test
    fun belongsTo_avoidsCrossBankTailCollision() {
        val eblCard = makeCard(id = 1L, bankName = "Eastern Bank", last4 = "4321")
        val bracCard = makeCard(id = 2L, bankName = "BRAC Bank", last4 = "4321")
        val eblTx = makeTxn(cardTail = "4321", bankName = "EBL")

        assertTrue(eblTx.belongsTo(eblCard))
        assertFalse(eblTx.belongsTo(bracCard))
    }

    @Test
    fun belongsTo_allowsUnknownBankWhenTailMatches() {
        val card = makeCard(bankName = "Prime Bank", last4 = "7788")
        val genericBankTx = makeTxn(cardTail = "7788", bankName = "Bank")

        assertTrue(genericBankTx.belongsTo(card))
    }

    @Test
    fun matchesPeriod_filtersMonthYearAndAll() {
        val cal = Calendar.getInstance().apply {
            clear()
            set(2026, Calendar.OCTOBER, 14, 15, 30)
        }
        val ts = cal.timeInMillis

        assertTrue(matchesPeriod(ts, CardPeriodMode.ALL, 2020, Calendar.JANUARY))
        assertTrue(matchesPeriod(ts, CardPeriodMode.MONTH, 2026, Calendar.OCTOBER))
        assertFalse(matchesPeriod(ts, CardPeriodMode.MONTH, 2026, Calendar.NOVEMBER))
        assertFalse(matchesPeriod(ts, CardPeriodMode.MONTH, 2025, Calendar.OCTOBER))
        assertTrue(matchesPeriod(ts, CardPeriodMode.YEAR, 2026, Calendar.JANUARY))
        assertFalse(matchesPeriod(ts, CardPeriodMode.YEAR, 2025, Calendar.OCTOBER))
    }

    @Test
    fun billMatchesPeriod_parsesBillPeriodAndFallsBackToDueDate() {
        val novBill = makeBill(billPeriod = "Nov-2026", dueDate = "05/12/2026")
        assertTrue(billMatchesPeriod(novBill, CardPeriodMode.MONTH, 2026, Calendar.NOVEMBER))
        assertFalse(billMatchesPeriod(novBill, CardPeriodMode.MONTH, 2026, Calendar.DECEMBER))
        assertTrue(billMatchesPeriod(novBill, CardPeriodMode.YEAR, 2026, Calendar.JANUARY))

        val unparsedPeriodBill = makeBill(billPeriod = "Statement", dueDate = "18/08/2025")
        assertTrue(billMatchesPeriod(unparsedPeriodBill, CardPeriodMode.MONTH, 2025, Calendar.AUGUST))
        assertTrue(billMatchesPeriod(unparsedPeriodBill, CardPeriodMode.YEAR, 2025, Calendar.JANUARY))
        assertFalse(billMatchesPeriod(unparsedPeriodBill, CardPeriodMode.YEAR, 2026, Calendar.JANUARY))
    }

    @Test
    fun buildPaidTimeline_sortsNewerOnTopAndOlderAtBottom() {
        val scanToday = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 23, 10, 0, 0)
        }.timeInMillis

        // Older bill (Jan 2026) marked paid/detected today should still sort BEFORE-in-time (bottom)
        val janBill = BillWithCard(
            billId = 1L, cardId = 1L, bankName = "City Bank", cardLast4 = "1234", cardNickname = "",
            billPeriod = "Jan-2026", totalDue = 5000.0, minDue = 500.0, dueDate = "20/01/2026",
            isPaid = true, paidAmount = 5000.0, paidAt = scanToday, smsBody = null, currency = "BDT",
            detectedAt = scanToday
        )
        val octBill = BillWithCard(
            billId = 2L, cardId = 1L, bankName = "City Bank", cardLast4 = "1234", cardNickname = "",
            billPeriod = "Oct-2026", totalDue = 8000.0, minDue = 500.0, dueDate = "20/10/2026",
            isPaid = true, paidAmount = 8000.0, paidAt = scanToday - 10_000_000L, smsBody = null, currency = "BDT",
            detectedAt = scanToday - 10_000_000L
        )
        val augPayment = CardPayment(
            id = 10L,
            cardTail = "1234",
            amountBdt = 3000.0,
            amountUsd = 0.0,
            paidAt = Calendar.getInstance().apply { set(2026, Calendar.AUGUST, 10, 12, 0, 0) }.timeInMillis,
            smsBody = "aug pay"
        )
        val novPayment = CardPayment(
            id = 11L,
            cardTail = "1234",
            amountBdt = 4000.0,
            amountUsd = 0.0,
            paidAt = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 5, 12, 0, 0) }.timeInMillis,
            smsBody = "nov pay"
        )

        val timeline = buildPaidTimeline(listOf(janBill, octBill), listOf(augPayment, novPayment))
        assertEquals(
            listOf("pay_11", "history_2", "pay_10", "history_1"),
            timeline.map { it.key }
        )
    }
}
