package com.finmagic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.finmagic.data.local.entity.Account
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: Account): Long

    @Query("SELECT * FROM accounts WHERE bankName = :bankName AND accountMask = :mask LIMIT 1")
    suspend fun findByInstitutionAndMask(bankName: String, mask: String): Account?

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun findByIdOnce(id: Long): Account?

    @Query("SELECT * FROM accounts WHERE kind = 'CASH' LIMIT 1")
    suspend fun getCashAccountOnce(): Account?

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY CASE kind WHEN 'CASH' THEN 0 WHEN 'BANK' THEN 1 WHEN 'WALLET' THEN 2 ELSE 3 END, name")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY CASE kind WHEN 'CASH' THEN 0 WHEN 'BANK' THEN 1 WHEN 'WALLET' THEN 2 ELSE 3 END, name")
    suspend fun getAllOnce(): List<Account>

    @Query("SELECT * FROM accounts WHERE isArchived = 1 ORDER BY name")
    fun observeArchived(): Flow<List<Account>>

    @Query("UPDATE accounts SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("UPDATE accounts SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE accounts SET includeInTotal = :value WHERE id = :id")
    suspend fun setIncludeInTotal(id: Long, value: Boolean)

    /** Cascades to account_transactions (FK ON DELETE CASCADE). */
    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
