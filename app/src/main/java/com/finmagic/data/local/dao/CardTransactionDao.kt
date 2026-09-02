package com.finmagic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.finmagic.data.local.entity.CardTransaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CardTransactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(txn: CardTransaction): Long

    @Query("SELECT * FROM card_transactions WHERE occurredAt BETWEEN :start AND :end ORDER BY occurredAt DESC")
    suspend fun getBetweenOnce(start: Long, end: Long): List<CardTransaction>

    @Query("SELECT COALESCE(SUM(amount), 0.0) FROM card_transactions WHERE occurredAt BETWEEN :start AND :end")
    suspend fun sumBetweenOnce(start: Long, end: Long): Double

    @Query("SELECT COUNT(*) FROM card_transactions WHERE occurredAt BETWEEN :start AND :end")
    suspend fun countBetweenOnce(start: Long, end: Long): Int

    @Query("SELECT * FROM card_transactions ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<CardTransaction>>

    @Query("SELECT MIN(occurredAt) FROM card_transactions")
    suspend fun getEarliestOccurredAt(): Long?
}
