package com.finmagic.data.repository

import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.BillWithCard
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillRepository @Inject constructor(
    private val billDao: com.finmagic.data.local.dao.BillDao,
    private val cardPaymentDao: com.finmagic.data.local.dao.CardPaymentDao
) {
    fun getUnpaidBills(): Flow<List<BillWithCard>> = billDao.getUnpaidBillsWithCards()

    fun getAllBills(): Flow<List<BillWithCard>> = billDao.getAllBillsWithCards()

    fun getBillsForCard(cardId: Long): Flow<List<BillWithCard>> = billDao.getBillsForCard(cardId)

    fun getAllPayments(): Flow<List<com.finmagic.data.local.entity.CardPayment>> = 
        cardPaymentDao.observeAllPayments()

    fun getTotalUnpaid(): Flow<Double?> = billDao.getTotalUnpaid()

    fun getUnpaidCount(): Flow<Int> = billDao.getUnpaidCount()

    fun getOverdueCount(today: String): Flow<Int> = billDao.getOverdueCount(today)

    fun getUnpaidTotalForCard(cardId: Long): Flow<Double?> = billDao.getUnpaidTotalForCard(cardId)

    fun getPaidTotalForCard(cardId: Long): Flow<Double?> = billDao.getPaidTotalForCard(cardId)

    suspend fun addBill(bill: Bill): Long = billDao.insertBill(bill)

    suspend fun markAsPaid(billId: Long, paidAt: Long = System.currentTimeMillis()) {
        val bill = billDao.getBillById(billId)
        if (bill != null) {
            val remainingToPay = maxOf(0.0, bill.totalDue - bill.paidAmount)
            billDao.markAsPaid(billId, paidAt)
            if (remainingToPay > 0.0) {
                creditNewerBills(bill.cardId, bill.currency, bill.dueDate, remainingToPay, paidAt)
            }
        } else {
            billDao.markAsPaid(billId, paidAt)
        }
    }

    suspend fun updatePaidAmount(billId: Long, amount: Double, paidAt: Long = System.currentTimeMillis()) {
        val bill = billDao.getBillById(billId)
        if (bill != null) {
            val delta = amount - bill.paidAmount
            billDao.updatePaidAmount(billId, amount, paidAt)
            if (delta > 0.0) {
                creditNewerBills(bill.cardId, bill.currency, bill.dueDate, delta, paidAt)
            }
        } else {
            billDao.updatePaidAmount(billId, amount, paidAt)
        }
    }

    private suspend fun creditNewerBills(
        cardId: Long,
        currency: String,
        olderDueDate: String,
        amount: Double,
        paidAt: Long
    ) {
        val bills = billDao.getBillsForCardAndCurrency(cardId, currency)
        val olderEpoch = com.finmagic.data.sms.PaymentSmsParser.paymentDateToEpoch(
            com.finmagic.data.sms.BankSmsParser.normalizeDate(olderDueDate)
        ) ?: 0L

        val newerBills = bills.filter {
            val dueEpoch = com.finmagic.data.sms.PaymentSmsParser.paymentDateToEpoch(
                com.finmagic.data.sms.BankSmsParser.normalizeDate(it.dueDate)
            ) ?: Long.MAX_VALUE
            dueEpoch > olderEpoch && !it.isPaid
        }

        for (newer in newerBills) {
            val newPaid = newer.paidAmount + amount
            if (newPaid >= newer.totalDue - 0.01) {
                billDao.markAsPaid(newer.id, paidAt)
            } else {
                billDao.updatePaidAmount(newer.id, newPaid, paidAt)
            }
        }
    }

    suspend fun markAsUnpaid(billId: Long) = billDao.markAsUnpaid(billId)

    suspend fun updateBillDetails(billId: Long, totalDue: Double, minDue: Double, dueDate: String) =
        billDao.updateBillDetails(billId, totalDue, minDue, dueDate)

    suspend fun findExistingBill(cardId: Long, period: String): Bill? =
        billDao.findExistingBill(cardId, period)

    suspend fun findBillBySms(smsBody: String): Bill? = billDao.findBillBySms(smsBody)

    suspend fun deleteBillsByIds(ids: List<Long>) = billDao.deleteBillsByIds(ids)

    suspend fun deleteBillsForCard(cardId: Long) {
        val bills = billDao.getBillsForCardOnce(cardId)
        billDao.deleteBillsByIds(bills.map { it.id })
    }
}
