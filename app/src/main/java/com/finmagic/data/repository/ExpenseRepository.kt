package com.finmagic.data.repository

import com.finmagic.data.local.dao.AccountTransactionDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.entity.CardTransaction
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** A single expense row shown in the Expenses tab (from an account or a card). */
data class ExpenseItem(
    val uid: String,
    val occurredAt: Long,
    val amount: Double,
    val title: String,
    val category: String,
    val source: String, // "account" or "card"
    val subtitle: String?,
    /** Set for account-sourced rows; used to toggle expense membership. */
    val accountTxnId: Long? = null,
    val direction: String? = null,
    val rawCategory: String? = null,
    val isExpenseOverride: Int = 0
)

data class CategoryTotal(
    val category: String,
    val total: Double,
    val count: Int
)

data class ExpenseStats(
    val total: Double,
    val count: Int,
    val dailyAverage: Double,
    val daysInMonth: Int,
    val daysElapsed: Int,
    val categories: List<CategoryTotal>,
    val prevCategories: List<CategoryTotal>,
    val topCategory: CategoryTotal?,
    val prevTotal: Double
)

@Singleton
class ExpenseRepository @Inject constructor(
    private val accountTxnDao: AccountTransactionDao,
    private val cardTxnDao: CardTransactionDao
) {
    suspend fun getExpenses(start: Long, end: Long, prevStart: Long, prevEnd: Long): Pair<List<ExpenseItem>, ExpenseStats> {
        val accountRows = accountTxnDao.getExpensesBetweenOnce(start, end)
        val cardRows: List<CardTransaction> = cardTxnDao.getBetweenOnce(start, end)
        val prevAccountRows = accountTxnDao.getExpensesBetweenOnce(prevStart, prevEnd)
        val prevCardRows: List<CardTransaction> = cardTxnDao.getBetweenOnce(prevStart, prevEnd)

        val items = buildList {
            for (r in accountRows) {
                add(
                    ExpenseItem(
                        uid = "a:${r.id}",
                        occurredAt = r.occurredAt,
                        amount = r.amount,
                        title = r.title,
                        category = r.category.ifBlank { "Expense" },
                        source = r.source,
                        subtitle = r.accountLabel,
                        accountTxnId = r.id,
                        direction = r.direction,
                        rawCategory = r.category.ifBlank { null },
                        isExpenseOverride = r.isExpenseOverride
                    )
                )
            }
            for (c in cardRows) {
                add(
                    ExpenseItem(
                        uid = "c:${c.id}",
                        occurredAt = c.occurredAt,
                        amount = c.amount,
                        title = c.merchant.ifBlank { "Card Purchase" },
                        category = c.category?.ifBlank { null } ?: "Card",
                        source = "card",
                        subtitle = "${c.bankName} •••${c.cardTail}"
                    )
                )
            }
        }.sortedByDescending { it.occurredAt }

        val total = items.sumOf { it.amount }
        val count = items.size

        val daysInMonth = Calendar.getInstance().apply { timeInMillis = start }
            .getActualMaximum(Calendar.DAY_OF_MONTH)
        val nowCal = Calendar.getInstance()
        val isCurrentMonth = run {
            val c = nowCal.clone() as Calendar
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.set(Calendar.HOUR_OF_DAY, 0)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            start == c.timeInMillis
        }
        val daysElapsed = if (isCurrentMonth) nowCal.get(Calendar.DAY_OF_MONTH) else daysInMonth

        val categories = items
            .groupBy { it.category }
            .map { (cat, list) -> CategoryTotal(cat, list.sumOf { it.amount }, list.size) }
            .sortedByDescending { it.total }

        val prevCategories = buildList {
            prevAccountRows.forEach { r ->
                add(r.category.ifBlank { "Expense" } to r.amount)
            }
            prevCardRows.forEach { c ->
                add((c.category?.ifBlank { null } ?: "Card") to c.amount)
            }
        }
            .groupBy({ it.first }, { it.second })
            .map { (cat, amounts) -> CategoryTotal(cat, amounts.sum(), amounts.size) }
            .sortedByDescending { it.total }

        // Previous period total = previous account expenses + previous card purchases.
        val prevTotal = prevCategories.sumOf { it.total }

        val stats = ExpenseStats(
            total = total,
            count = count,
            dailyAverage = total / daysInMonth,
            daysInMonth = daysInMonth,
            daysElapsed = daysElapsed,
            categories = categories,
            prevCategories = prevCategories,
            topCategory = categories.firstOrNull(),
            prevTotal = prevTotal
        )
        return items to stats
    }

    suspend fun setExpenseOverride(accountTxnId: Long, value: Int) {
        accountTxnDao.setExpenseOverride(accountTxnId, value)
    }

    /** Earliest year with any ledger or card activity; null when the DB is empty. */
    suspend fun earliestYear(): Int? {
        val min = listOf(
            accountTxnDao.getEarliestOccurredAt(),
            cardTxnDao.getEarliestOccurredAt()
        ).filterNotNull().minOrNull() ?: return null
        return Calendar.getInstance().apply { timeInMillis = min }.get(Calendar.YEAR)
    }
}