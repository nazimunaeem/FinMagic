package com.finmagic.data.sms

import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CreditCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class PaymentMatchEngineTest {

    private lateinit var cardDao: FakeCreditCardDao
    private lateinit var billDao: FakeBillDao
    private lateinit var cardPaymentDao: FakeCardPaymentDao
    private lateinit var engine: PaymentMatchEngine

    private val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)

    private fun dateToEpoch(dateStr: String): Long {
        return sdf.parse(dateStr)!!.time
    }

    @Before
    fun setup() {
        cardDao = FakeCreditCardDao()
        billDao = FakeBillDao()
        cardPaymentDao = FakeCardPaymentDao()
        engine = PaymentMatchEngine(cardDao, billDao, cardPaymentDao)
    }

    @Test
    fun paymentReceived_autoFlagsPaidToPreviousBill_andFlagsDownThisMonthBill() = runBlocking {
        // Setup Card: City Bank Amex 2945 (tail 945)
        cardDao.cards.add(
            CreditCard(id = 1, bankName = "City Bank (Amex)", cardLast4 = "2945")
        )

        // July Bill: Total Due 10,000, statement date July 20, due Aug 15
        billDao.bills.add(
            Bill(
                id = 101,
                cardId = 1,
                billPeriod = "July 2026",
                totalDue = 10000.0,
                minDue = 1000.0,
                dueDate = "15/08/2026",
                detectedAt = dateToEpoch("20/07/2026"),
                currency = "BDT"
            )
        )

        // August Bill: Total Due 15,000 (10,000 old + 5,000 new), statement date Aug 20, due Sept 15
        billDao.bills.add(
            Bill(
                id = 102,
                cardId = 1,
                billPeriod = "August 2026",
                totalDue = 15000.0,
                minDue = 1500.0,
                dueDate = "15/09/2026",
                detectedAt = dateToEpoch("20/08/2026"),
                currency = "BDT"
            )
        )

        // Payment: 10,000 BDT received on August 25
        cardPaymentDao.payments.add(
            CardPayment(
                id = 1,
                cardTail = "945",
                amountBdt = 10000.0,
                amountUsd = 0.0,
                paidAt = dateToEpoch("25/08/2026"),
                smsBody = "25-AUG-26 Tk. 10,000.00 payment received CARD NO: 376***945"
            )
        )

        val applied = engine.applyUnappliedPayments()
        assertEquals(1, applied)

        val julyBill = billDao.bills.first { it.id == 101L }
        val augustBill = billDao.bills.first { it.id == 102L }

        // Previous bill (July) must be auto-flagged PAID
        assertTrue("July bill should be marked as paid", julyBill.isPaid)
        assertEquals(10000.0, julyBill.paidAmount, 0.01)
        assertTrue(julyBill.paidViaSms)
        assertFalse(julyBill.isSuperseded)

        // Current month bill (August) must be flagged down (reduced by 10,000, remaining = 5,000)
        assertFalse("August bill should NOT be marked as fully paid", augustBill.isPaid)
        assertEquals(10000.0, augustBill.paidAmount, 0.01)
        assertEquals(5000.0, augustBill.totalDue - augustBill.paidAmount, 0.01)
        assertTrue(augustBill.paidViaSms)
        assertFalse(augustBill.isSuperseded)
    }

    @Test
    fun subsequentPayment_clearsRemainingOnThisMonthBill() = runBlocking {
        cardDao.cards.add(
            CreditCard(id = 1, bankName = "City Bank (Amex)", cardLast4 = "2945")
        )

        billDao.bills.add(
            Bill(
                id = 101,
                cardId = 1,
                billPeriod = "July 2026",
                totalDue = 10000.0,
                minDue = 1000.0,
                dueDate = "15/08/2026",
                detectedAt = dateToEpoch("20/07/2026"),
                currency = "BDT"
            )
        )

        billDao.bills.add(
            Bill(
                id = 102,
                cardId = 1,
                billPeriod = "August 2026",
                totalDue = 15000.0,
                minDue = 1500.0,
                dueDate = "15/09/2026",
                detectedAt = dateToEpoch("20/08/2026"),
                currency = "BDT"
            )
        )

        // Payment 1: 10,000 on Aug 25
        cardPaymentDao.payments.add(
            CardPayment(
                id = 1,
                cardTail = "945",
                amountBdt = 10000.0,
                amountUsd = 0.0,
                paidAt = dateToEpoch("25/08/2026"),
                smsBody = "25-AUG-26 Tk. 10,000.00 payment received"
            )
        )

        // Payment 2: 5,000 on Sept 5
        cardPaymentDao.payments.add(
            CardPayment(
                id = 2,
                cardTail = "945",
                amountBdt = 5000.0,
                amountUsd = 0.0,
                paidAt = dateToEpoch("05/09/2026"),
                smsBody = "05-SEP-26 Tk. 5,000.00 payment received"
            )
        )

        engine.applyUnappliedPayments()

        val julyBill = billDao.bills.first { it.id == 101L }
        val augustBill = billDao.bills.first { it.id == 102L }

        assertTrue(julyBill.isPaid)
        assertEquals(10000.0, julyBill.paidAmount, 0.01)

        assertTrue("August bill should now be fully paid", augustBill.isPaid)
        assertEquals(15000.0, augustBill.paidAmount, 0.01)
        assertEquals(0.0, augustBill.totalDue - augustBill.paidAmount, 0.01)
    }

    @Test
    fun partialPaymentBeforeStatement_andPaymentAfterStatement() = runBlocking {
        cardDao.cards.add(
            CreditCard(id = 1, bankName = "City Bank (Amex)", cardLast4 = "2945")
        )

        billDao.bills.add(
            Bill(
                id = 101,
                cardId = 1,
                billPeriod = "July 2026",
                totalDue = 10000.0,
                minDue = 1000.0,
                dueDate = "15/08/2026",
                detectedAt = dateToEpoch("20/07/2026"),
                currency = "BDT"
            )
        )

        // Payment 1: 4,000 on Aug 5 (before August statement cut on Aug 20)
        cardPaymentDao.payments.add(
            CardPayment(
                id = 1,
                cardTail = "945",
                amountBdt = 4000.0,
                amountUsd = 0.0,
                paidAt = dateToEpoch("05/08/2026"),
                smsBody = "05-AUG-26 Tk. 4,000.00 payment received"
            )
        )

        // August Statement cut on Aug 20: 11,000 (6,000 remaining + 5,000 new)
        billDao.bills.add(
            Bill(
                id = 102,
                cardId = 1,
                billPeriod = "August 2026",
                totalDue = 11000.0,
                minDue = 1100.0,
                dueDate = "15/09/2026",
                detectedAt = dateToEpoch("20/08/2026"),
                currency = "BDT"
            )
        )

        // Payment 2: 6,000 on Aug 25 (clears remaining July balance)
        cardPaymentDao.payments.add(
            CardPayment(
                id = 2,
                cardTail = "945",
                amountBdt = 6000.0,
                amountUsd = 0.0,
                paidAt = dateToEpoch("25/08/2026"),
                smsBody = "25-AUG-26 Tk. 6,000.00 payment received"
            )
        )

        engine.applyUnappliedPayments()

        val julyBill = billDao.bills.first { it.id == 101L }
        val augustBill = billDao.bills.first { it.id == 102L }

        // July bill was partially paid (4,000), then cleared by 6,000 -> full 10,000 paid!
        assertTrue(julyBill.isPaid)
        assertEquals(10000.0, julyBill.paidAmount, 0.01)

        // August bill: received 6,000 credit from Payment 2 (Payment 1 was already in bank statement)
        assertFalse(augustBill.isPaid)
        assertEquals(6000.0, augustBill.paidAmount, 0.01)
        assertEquals(5000.0, augustBill.totalDue - augustBill.paidAmount, 0.01)
    }

    @Test
    fun singleLargePayment_clearsBothBills() = runBlocking {
        cardDao.cards.add(
            CreditCard(id = 1, bankName = "City Bank (Amex)", cardLast4 = "2945")
        )

        billDao.bills.add(
            Bill(
                id = 101,
                cardId = 1,
                billPeriod = "July 2026",
                totalDue = 10000.0,
                minDue = 1000.0,
                dueDate = "15/08/2026",
                detectedAt = dateToEpoch("20/07/2026"),
                currency = "BDT"
            )
        )

        billDao.bills.add(
            Bill(
                id = 102,
                cardId = 1,
                billPeriod = "August 2026",
                totalDue = 15000.0,
                minDue = 1500.0,
                dueDate = "15/09/2026",
                detectedAt = dateToEpoch("20/08/2026"),
                currency = "BDT"
            )
        )

        // Payment of full 15,000 on Aug 25
        cardPaymentDao.payments.add(
            CardPayment(
                id = 1,
                cardTail = "945",
                amountBdt = 15000.0,
                amountUsd = 0.0,
                paidAt = dateToEpoch("25/08/2026"),
                smsBody = "25-AUG-26 Tk. 15,000.00 payment received"
            )
        )

        engine.applyUnappliedPayments()

        val julyBill = billDao.bills.first { it.id == 101L }
        val augustBill = billDao.bills.first { it.id == 102L }

        assertTrue(julyBill.isPaid)
        assertEquals(10000.0, julyBill.paidAmount, 0.01)

        assertTrue(augustBill.isPaid)
        assertEquals(15000.0, augustBill.paidAmount, 0.01)
    }

    @Test
    fun multiCurrency_independentLedgers() = runBlocking {
        cardDao.cards.add(
            CreditCard(id = 1, bankName = "EBL", cardLast4 = "5428")
        )

        billDao.bills.add(
            Bill(
                id = 101,
                cardId = 1,
                billPeriod = "July 2026",
                totalDue = 5000.0,
                minDue = 500.0,
                dueDate = "15/08/2026",
                detectedAt = dateToEpoch("20/07/2026"),
                currency = "BDT"
            )
        )

        billDao.bills.add(
            Bill(
                id = 102,
                cardId = 1,
                billPeriod = "July 2026",
                totalDue = 100.0,
                minDue = 10.0,
                dueDate = "15/08/2026",
                detectedAt = dateToEpoch("20/07/2026"),
                currency = "USD"
            )
        )

        // Payment: 5,000 BDT only
        cardPaymentDao.payments.add(
            CardPayment(
                id = 1,
                cardTail = "428",
                amountBdt = 5000.0,
                amountUsd = 0.0,
                paidAt = dateToEpoch("05/08/2026"),
                smsBody = "05-AUG-26 Tk. 5,000.00 payment received"
            )
        )

        engine.applyUnappliedPayments()

        val bdtBill = billDao.bills.first { it.id == 101L }
        val usdBill = billDao.bills.first { it.id == 102L }

        assertTrue(bdtBill.isPaid)
        assertEquals(5000.0, bdtBill.paidAmount, 0.01)

        assertFalse(usdBill.isPaid)
        assertEquals(0.0, usdBill.paidAmount, 0.01)
    }
}

// -----------------------------------------------------------------------------
// Test Fakes
// -----------------------------------------------------------------------------

class FakeCreditCardDao : CreditCardDao {
    val cards = mutableListOf<CreditCard>()

    override fun getActiveCards(): Flow<List<CreditCard>> = flowOf(cards.filter { it.isActive })
    override fun getAllCards(): Flow<List<CreditCard>> = flowOf(cards)
    override suspend fun getAllCardsOnce(): List<CreditCard> = cards.toList()
    override suspend fun getCardById(cardId: Long): CreditCard? = cards.firstOrNull { it.id == cardId }
    override suspend fun findCard(bankName: String, last4: String): CreditCard? =
        cards.firstOrNull { it.bankName.equals(bankName, ignoreCase = true) && it.cardLast4 == last4 }

    override suspend fun findCardFlexible(bankName: String, last4: String): CreditCard? =
        cards.firstOrNull {
            it.cardLast4 == last4 && (
                it.bankName.equals(bankName, ignoreCase = true) ||
                it.bankName.uppercase().contains(bankName.uppercase()) ||
                bankName.uppercase().contains(it.bankName.uppercase())
            )
        }

    override suspend fun insertCard(card: CreditCard): Long {
        cards.add(card)
        return card.id
    }

    override suspend fun updateCard(card: CreditCard) {
        val idx = cards.indexOfFirst { it.id == card.id }
        if (idx >= 0) cards[idx] = card
    }

    override suspend fun deleteCard(card: CreditCard) {
        cards.removeAll { it.id == card.id }
    }

    override suspend fun deactivateCard(cardId: Long) {
        val idx = cards.indexOfFirst { it.id == cardId }
        if (idx >= 0) cards[idx] = cards[idx].copy(isActive = false)
    }
}

class FakeBillDao : BillDao {
    val bills = mutableListOf<Bill>()

    override fun getUnpaidBillsWithCards(): Flow<List<BillWithCard>> = flowOf(emptyList())
    override fun getAllBillsWithCards(): Flow<List<BillWithCard>> = flowOf(emptyList())
    override fun getBillsForCard(cardId: Long): Flow<List<BillWithCard>> = flowOf(emptyList())
    override fun getAllBillsWithCardsForHistory(): Flow<List<BillWithCard>> = flowOf(emptyList())
    override fun getTotalUnpaid(): Flow<Double?> = flowOf(bills.filter { !it.isPaid && !it.isSuperseded }.sumOf { it.totalDue - it.paidAmount })
    override fun getUnpaidCount(): Flow<Int> = flowOf(bills.count { !it.isPaid && !it.isSuperseded })
    override fun getOverdueCount(today: String): Flow<Int> = flowOf(0)
    override fun getUnpaidTotalForCard(cardId: Long): Flow<Double?> = flowOf(0.0)
    override fun getPaidTotalForCard(cardId: Long): Flow<Double?> = flowOf(bills.filter { it.cardId == cardId }.sumOf { it.paidAmount })

    override suspend fun insertBill(bill: Bill): Long {
        bills.add(bill)
        return bill.id
    }

    override suspend fun updateBill(bill: Bill) {
        val idx = bills.indexOfFirst { it.id == bill.id }
        if (idx >= 0) bills[idx] = bill
    }

    override suspend fun updateBills(bills: List<Bill>) {
        for (bill in bills) {
            updateBill(bill)
        }
    }

    override suspend fun getBillById(billId: Long): Bill? = bills.firstOrNull { it.id == billId }

    override suspend fun getBillsForCardAndCurrency(cardId: Long, currency: String): List<Bill> =
        bills.filter { it.cardId == cardId && it.currency.equals(currency, ignoreCase = true) }

    override suspend fun deleteOldPaidBills(beforeTimestamp: Long) {
        bills.removeAll { it.isPaid && (it.paidAt ?: 0L) < beforeTimestamp }
    }

    override suspend fun clearSmsBodyForOldPaidBills(beforeTimestamp: Long) {}

    override suspend fun markAsPaid(billId: Long, paidAt: Long) {
        val idx = bills.indexOfFirst { it.id == billId }
        if (idx >= 0) {
            bills[idx] = bills[idx].copy(isPaid = true, isSuperseded = false, paidAmount = bills[idx].totalDue, paidAt = paidAt)
        }
    }

    override suspend fun updatePaidAmount(billId: Long, amount: Double, paidAt: Long) {
        val idx = bills.indexOfFirst { it.id == billId }
        if (idx >= 0) {
            val total = bills[idx].totalDue
            bills[idx] = bills[idx].copy(
                paidAmount = amount,
                isPaid = amount >= total - 0.01,
                paidAt = paidAt
            )
        }
    }

    override suspend fun markAsUnpaid(billId: Long) {
        val idx = bills.indexOfFirst { it.id == billId }
        if (idx >= 0) {
            bills[idx] = bills[idx].copy(isPaid = false, isSuperseded = false, paidAmount = 0.0, paidAt = null, paidViaSms = false)
        }
    }

    override suspend fun applySmsPayment(billId: Long, amount: Double, paidAt: Long) {
        updatePaidAmount(billId, amount, paidAt)
    }

    override suspend fun findActiveUnpaidBill(cardId: Long, currency: String): Bill? =
        bills.firstOrNull { it.cardId == cardId && it.currency.equals(currency, ignoreCase = true) && !it.isPaid && !it.isSuperseded }

    override suspend fun updateBillDetails(billId: Long, totalDue: Double, minDue: Double, dueDate: String) {
        val idx = bills.indexOfFirst { it.id == billId }
        if (idx >= 0) {
            bills[idx] = bills[idx].copy(totalDue = totalDue, minDue = minDue, dueDate = dueDate)
        }
    }

    override suspend fun getBillsForCardOnce(cardId: Long): List<Bill> =
        bills.filter { it.cardId == cardId }

    override suspend fun getAllBillsOnce(): List<Bill> = bills.toList()

    override suspend fun deleteBillsByIds(ids: List<Long>) {
        bills.removeAll { it.id in ids }
    }

    override suspend fun findExistingBill(cardId: Long, period: String): Bill? =
        bills.firstOrNull { it.cardId == cardId && it.billPeriod == period }

    override suspend fun findBillBySms(smsBody: String): Bill? =
        bills.firstOrNull { it.smsBody == smsBody }

    override suspend fun markOlderBillsSuperseded(cardId: Long, currency: String, newBillDueDate: String) {}
    override suspend fun markAllButLatestSuperseded() {}
}

class FakeCardPaymentDao : CardPaymentDao {
    val payments = mutableListOf<CardPayment>()

    override suspend fun insert(payment: CardPayment): Long {
        payments.add(payment)
        return payment.id
    }

    override suspend fun getUnappliedOnce(): List<CardPayment> =
        payments.filter { it.appliedBillId == null }

    override fun observeAllPayments(): Flow<List<CardPayment>> = flowOf(payments.toList())

    override suspend fun getAllPaymentsOnce(): List<CardPayment> = payments.toList()

    override suspend fun markApplied(paymentId: Long, billId: Long) {
        val idx = payments.indexOfFirst { it.id == paymentId }
        if (idx >= 0) {
            payments[idx] = payments[idx].copy(appliedBillId = billId)
        }
    }

    override suspend fun deleteBySmsBody(smsBody: String) {
        payments.removeAll { it.smsBody == smsBody }
    }


    override suspend fun countOnce(): Int = payments.size

    override suspend fun deleteOld(beforeTimestamp: Long) {
        payments.removeAll { it.detectedAt < beforeTimestamp }
    }
}
