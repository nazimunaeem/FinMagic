package com.finmagic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.finmagic.data.local.entity.CardPayment

@Dao
interface CardPaymentDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(payment: CardPayment): Long

    @Query("DELETE FROM card_payments WHERE smsBody = :smsBody")
    suspend fun deleteBySmsBody(smsBody: String)

    @Query("SELECT * FROM card_payments WHERE appliedBillId IS NULL ORDER BY paidAt ASC")
    suspend fun getUnappliedOnce(): List<CardPayment>

    @Query("SELECT * FROM card_payments ORDER BY paidAt DESC")
    fun observeAllPayments(): kotlinx.coroutines.flow.Flow<List<CardPayment>>

    @Query("SELECT * FROM card_payments ORDER BY paidAt ASC")
    suspend fun getAllPaymentsOnce(): List<CardPayment>

    @Query("UPDATE card_payments SET appliedBillId = :billId WHERE id = :paymentId")
    suspend fun markApplied(paymentId: Long, billId: Long)

    @Query("SELECT COUNT(*) FROM card_payments")
    suspend fun countOnce(): Int

    @Query("DELETE FROM card_payments WHERE detectedAt < :beforeTimestamp")
    suspend fun deleteOld(beforeTimestamp: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplace(payments: List<CardPayment>)

    @Query("DELETE FROM card_payments")
    suspend fun clearAll()
}
