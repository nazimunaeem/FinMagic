package com.finmagic.data.repository

import com.finmagic.data.local.dao.AccountTransactionDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.entity.CardTransaction
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
    val subtitle: String?
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
    val categories: List<CategoryTotal>,
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
        val prevCardTotal = cardTxnDao.sumBetweenOnce(prevStart, prevEnd)

        val items = buildList {
            for (r in accountRows) {
                add(
                    ExpenseItem(
                        uid = "a:${r.id}",
                        occurredAt = r.occurredAt,
                        amount = r.amount,
                        title = r.title,
                        category = r.category.ifBlank { "Expense" },
                        source = "account",
                        subtitle = r.accountLabel
                    )
                )
            }
            for (c in cardRows) {
                add(
                    ExpenseItem(
                        uid = "c:${c.id}",
                        occurredAt = c.occurredAt,

                        amount = c.amount,
                        title = c.merchant,
                        category = "Card",
                        source = "card",
                        subtitle = "${c.bankName} •••${c.cardTail}"
                    )
                )
            }
        }.sortedByDescending { it.occurredAt }

        val total = items.sumOf { it.amount }
        val count = items.size
        val days = ((end - start) / 86_400_000L).coerceAtLeast(1)
        val categories = items
            .groupBy { it.category }
            .map { (cat, list) -> CategoryTotal(cat, list.sumOf { it.amount }, list.size) }
            .sortedByDescending { it.total }
        val topCategory = categories.firstOrNull()
        // Previous period total = previous account expenses + previous card purchases.
        val accountPrev = accountTxnDao.getExpensesBetweenOnce(prevStart, prevEnd).sumOf { it.amount }
        val prevTotal = accountPrev + prevCardTotal

        val stats = ExpenseStats(
            total = total,
            count = count,
            dailyAverage = total / days,
            categories = categories,
            topCategory = topCategory,
            prevTotal = prevTotal
        )
        return items to stats
    }

    suspend fun setExpenseOverride(accountTxnId: Long, value: Int) {
        accountTxnDao.setExpenseOverride(accountTxnId, value)
    }
}
