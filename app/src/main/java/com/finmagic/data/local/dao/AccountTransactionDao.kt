package com.finmagic.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.finmagic.data.local.entity.AccountTransaction
import kotlinx.coroutines.flow.Flow

/** A flattened expense row used to feed the Expenses tab. */
data class ExpenseRow(
    val id: Long,
    val occurredAt: Long,
    val amount: Double,
    val title: String,
    val category: String,
    val direction: String?,
    val isExpenseOverride: Int,
    val source: String,
    val accountLabel: String?
)

@Dao
interface AccountTransactionDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(txn: AccountTransaction): Long

    /** Expenses-tab membership: -1 force-exclude, 0 auto, 1 force-include. */
    @Query("UPDATE account_transactions SET isExpenseOverride = :value WHERE id = :id")
    suspend fun setExpenseOverride(id: Long, value: Int)

    /** Account-driven expenses for a month window (OUT, excluding non-spending categories).
     *  Keep the NOT IN list in sync with TxnCategory.NON_SPENDING. */
    @Query("""
        SELECT id AS id, occurredAt AS occurredAt, amount AS amount,
               COALESCE(category, 'Expense') AS title,
               COALESCE(category, '') AS category,
               direction AS direction,
               isExpenseOverride AS isExpenseOverride,
               'account' AS source,
               counterparty AS accountLabel
        FROM account_transactions
        WHERE direction = 'OUT'
          AND (
            ((category IS NULL OR category NOT IN (
                'Fund-Transfer', 'ATM', 'Adjustment', 'NPSB', 'RD-Installment', 'DirectSet'))
             AND isExpenseOverride != -1)
            OR isExpenseOverride = 1
          )
          AND occurredAt BETWEEN :start AND :end
        ORDER BY occurredAt DESC, id DESC
    """)
    suspend fun getExpensesBetweenOnce(start: Long, end: Long): List<ExpenseRow>

    @Query("SELECT MIN(occurredAt) FROM account_transactions")
    suspend fun getEarliestOccurredAt(): Long?

    @Query("SELECT * FROM account_transactions WHERE accountId = :accountId ORDER BY occurredAt ASC, id ASC")
    fun observeByAccount(accountId: Long): Flow<List<AccountTransaction>>

    @Query("SELECT * FROM account_transactions WHERE category != 'DirectSet' ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<AccountTransaction>>

    @Query("SELECT * FROM account_transactions ORDER BY occurredAt ASC, id ASC")
    fun observeAllIncludingDirectSet(): Flow<List<AccountTransaction>>

    @Query("""
        SELECT t.*, a.name AS accountName, a.kind AS accountKind
        FROM account_transactions t
        JOIN accounts a ON t.accountId = a.id
        WHERE t.category != 'DirectSet'
        ORDER BY t.occurredAt DESC, t.id DESC
    """)
    fun observeAllWithAccount(): Flow<List<AccountTransactionWithAccount>>


    @Query("SELECT * FROM account_transactions WHERE accountId = :accountId AND category != 'DirectSet' ORDER BY occurredAt DESC, id DESC")
    suspend fun getByAccountOnce(accountId: Long): List<AccountTransaction>

    @Query("SELECT * FROM account_transactions WHERE accountId = :accountId ORDER BY occurredAt ASC, id ASC")
    suspend fun getAllByAccountOnce(accountId: Long): List<AccountTransaction>

    @Query("SELECT * FROM account_transactions WHERE id = :id")
    suspend fun findByIdOnce(id: Long): AccountTransaction?

    @Query("SELECT * FROM account_transactions ORDER BY occurredAt ASC, id ASC")
    suspend fun getAllOnce(): List<AccountTransaction>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplace(txns: List<AccountTransaction>)

    @Query("DELETE FROM account_transactions")
    suspend fun clearAll()

    @Query(
        "SELECT balanceAfter FROM account_transactions " +
            "WHERE accountId = :accountId AND balanceAfter IS NOT NULL " +
            "ORDER BY occurredAt DESC, id DESC LIMIT 1"
    )
    suspend fun getLatestBalanceOnce(accountId: Long): Double?

    @Query("SELECT EXISTS(SELECT 1 FROM account_transactions WHERE smsBody = :smsBody LIMIT 1)")
    suspend fun existsBySmsBody(smsBody: String): Boolean

    @Query("DELETE FROM account_transactions WHERE id = :id AND isManual = 1")
    suspend fun deleteManualById(id: Long)

    @Query("SELECT * FROM account_transactions WHERE smsRecordId = :smsRecordId")
    suspend fun getBySmsRecordIdOnce(smsRecordId: Long): List<AccountTransaction>

    @Query("DELETE FROM account_transactions WHERE smsRecordId = :smsRecordId")
    suspend fun deleteBySmsRecordId(smsRecordId: Long)

    @Query("DELETE FROM account_transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM account_transactions WHERE accountId = :accountId AND category = 'DirectSet'")
    suspend fun deleteDirectSetEntries(accountId: Long)


    @Query("SELECT * FROM account_transactions WHERE transferId = :transferId")
    suspend fun getByTransferIdOnce(transferId: Long): List<AccountTransaction>

    @Query("DELETE FROM account_transactions WHERE transferId = :transferId")
    suspend fun deleteByTransferId(transferId: Long)

    @Query("DELETE FROM account_transactions WHERE transferId = :transferId AND accountId != :keepAccountId")
    suspend fun deleteTransferMirror(transferId: Long, keepAccountId: Long)

    @Query("DELETE FROM account_transactions WHERE accountId = :accountId")
    suspend fun deleteByAccountId(accountId: Long)

    @Query("UPDATE account_transactions SET category = :category, transferId = :transferId WHERE id = :id")
    suspend fun updateCategoryAndTransferId(id: Long, category: String?, transferId: Long?)

    @Query("UPDATE account_transactions SET counterparty = :counterparty, category = :category, amount = :amount WHERE id = :id")
    suspend fun updateTransactionDetails(id: Long, counterparty: String?, category: String?, amount: Double)

    @Query("UPDATE account_transactions SET balanceAfter = :balanceAfter WHERE id = :id")
    suspend fun updateBalanceAfter(id: Long, balanceAfter: Double?)

    @Query("UPDATE account_transactions SET transferId = :transferId WHERE id = :id")
    suspend fun updateTransferId(id: Long, transferId: Long?)

    @Query("""
        SELECT * FROM account_transactions
        WHERE accountId = :accountId
          AND direction = 'IN'
          AND transferId IS NULL
          AND occurredAt >= :since
          AND amount BETWEEN :minAmount AND :maxAmount
        ORDER BY occurredAt DESC
    """)
    suspend fun getUnlinkedInTransactionsOnce(accountId: Long, since: Long, minAmount: Double, maxAmount: Double): List<AccountTransaction>

    @Query("""
        SELECT * FROM account_transactions
        WHERE accountId = :accountId AND balanceAfter IS NOT NULL AND occurredAt <= :time
        ORDER BY occurredAt DESC, id DESC LIMIT 1
    """)
    suspend fun getLatestTxnWithBalanceBefore(accountId: Long, time: Long): AccountTransaction?

    @Query("""
        SELECT * FROM account_transactions
        WHERE accountId = :accountId
          AND balanceAfter IS NULL
          AND (occurredAt > :startTime OR (occurredAt = :startTime AND id > :startId))
          AND occurredAt < :endTime
        ORDER BY occurredAt ASC, id ASC
    """)
    suspend fun getTxnsBetweenWithoutBalance(accountId: Long, startTime: Long, startId: Long, endTime: Long): List<AccountTransaction>

    @Query("""
        SELECT a.id AS accountId,
               (SELECT balanceAfter FROM account_transactions t
                WHERE t.accountId = a.id AND t.balanceAfter IS NOT NULL
                ORDER BY t.occurredAt DESC, t.id DESC LIMIT 1) AS latestBalance,
               COALESCE((SELECT SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END)
                FROM account_transactions t WHERE t.accountId = a.id
                  ), 0) AS computedBalance,
               COALESCE((SELECT SUM(CASE WHEN direction = 'IN' AND isExpenseOverride = 1 THEN amount ELSE 0 END)
                FROM account_transactions t
                WHERE t.accountId = a.id AND t.occurredAt >= :monthStart AND t.occurredAt < :monthEnd
                  AND t.category != 'DirectSet'), 0) AS monthIn,
               COALESCE((SELECT SUM(CASE WHEN direction = 'OUT' THEN amount ELSE 0 END)
                FROM account_transactions t
                WHERE t.accountId = a.id AND t.occurredAt >= :monthStart AND t.occurredAt < :monthEnd
                  AND t.transferId IS NULL AND t.category != 'DirectSet'), 0) AS monthOut,
               COALESCE((SELECT MAX(occurredAt) FROM account_transactions t
                WHERE t.accountId = a.id AND t.category != 'DirectSet'), 0) AS lastActivity
        FROM accounts a
        WHERE a.isArchived = 0
        ORDER BY lastActivity DESC, a.name
    """)
    fun observeSummariesRaw(monthStart: Long, monthEnd: Long): Flow<List<AccountSummaryRow>>

    /** Monthly outflow per category; internal transfers excluded (plan Phase 5). */
    @Query("""
        SELECT 
            CASE 
                WHEN category LIKE 'Fund Transfer%' OR category LIKE 'Fund-Transfer%' THEN 'Fund Transfer'
                ELSE COALESCE(NULLIF(category, ''), 'Uncategorized')
            END AS category,
            SUM(amount) AS total
        FROM account_transactions
        WHERE occurredAt >= :monthStart
          AND occurredAt < :monthEnd
          AND direction = 'OUT'
          AND transferId IS NULL
          AND category != 'DirectSet'
        GROUP BY 
            CASE 
                WHEN category LIKE 'Fund Transfer%' OR category LIKE 'Fund-Transfer%' THEN 'Fund Transfer'
                ELSE COALESCE(NULLIF(category, ''), 'Uncategorized')
            END
        ORDER BY total DESC
    """)
    fun observeMonthlyExpenseByCategory(monthStart: Long, monthEnd: Long): Flow<List<CategoryTotal>>

    /** Monthly inflow per category — only explicitly marked income rows (isExpenseOverride = 1). */
    @Query("""
        SELECT 
            CASE 
                WHEN category LIKE 'Fund Transfer%' OR category LIKE 'Fund-Transfer%' THEN 'Fund Transfer'
                ELSE COALESCE(NULLIF(category, ''), 'Uncategorized')
            END AS category,
            SUM(amount) AS total
        FROM account_transactions
        WHERE occurredAt >= :monthStart
          AND occurredAt < :monthEnd
          AND direction = 'IN'
          AND category != 'DirectSet'
          AND isExpenseOverride = 1
        GROUP BY 
            CASE 
                WHEN category LIKE 'Fund Transfer%' OR category LIKE 'Fund-Transfer%' THEN 'Fund Transfer'
                ELSE COALESCE(NULLIF(category, ''), 'Uncategorized')
            END
        ORDER BY total DESC
    """)
    fun observeMonthlyIncomeByCategory(monthStart: Long, monthEnd: Long): Flow<List<CategoryTotal>>
}

/** Raw projection joined onto [com.finmagic.data.local.entity.Account] in the repository. */
data class AccountSummaryRow(
    val accountId: Long,
    val latestBalance: Double?,
    val computedBalance: Double,
    val monthIn: Double,
    val monthOut: Double,
    val lastActivity: Long
)

/** Monthly outflow aggregated per category (plan Phase 5 expense breakdown). */
data class CategoryTotal(
    val category: String,
    val total: Double
)

/** AccountTransaction row joined with the owning account's display name and kind. */
data class AccountTransactionWithAccount(
    val id: Long,
    val accountId: Long,
    val direction: String,
    val amount: Double,
    val currency: String,
    val balanceAfter: Double?,
    val occurredAt: Long,
    val category: String?,
    val counterparty: String?,
    val smsRecordId: Long?,
    val transferId: Long?,
    val smsBody: String?,
    val isManual: Boolean,
    val isExpenseOverride: Int,
    val createdAt: Long,
    /** Human-readable account name from the accounts table. */
    val accountName: String,
    /** Account kind from the accounts table (e.g. "BANK", "CASH"). */
    val accountKind: String
) {
    fun toTransaction() = com.finmagic.data.local.entity.AccountTransaction(
        id = id, accountId = accountId, direction = direction, amount = amount,
        currency = currency, balanceAfter = balanceAfter, occurredAt = occurredAt,
        category = category, counterparty = counterparty, smsRecordId = smsRecordId,
        transferId = transferId, smsBody = smsBody, isManual = isManual,
        isExpenseOverride = isExpenseOverride, createdAt = createdAt
    )
}

