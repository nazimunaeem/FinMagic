package com.finmagic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.finmagic.data.local.entity.Transfer
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {

    @Insert
    suspend fun insert(transfer: Transfer): Long

    @Query("SELECT * FROM transfers ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<Transfer>>

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** All transfers touching an account — needed to clean both sides on delete. */
    @Query("SELECT * FROM transfers WHERE fromAccountId = :accountId OR toAccountId = :accountId")
    suspend fun getForAccountOnce(accountId: Long): List<Transfer>
}
