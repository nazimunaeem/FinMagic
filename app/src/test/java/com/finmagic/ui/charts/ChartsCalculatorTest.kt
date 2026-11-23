package com.finmagic.ui.charts

import com.finmagic.data.local.AccountTypeConfig
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.sms.parsers.TxnCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class ChartsCalculatorTest {

    private fun ts(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            set(year, month, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun bankAccount(
        id: Long,
        kind: String = Account.KIND_BANK,
        accountType: String = "Savings",
        isLiability: Boolean = false
    ) = Account(
        id = id,
        name = "Account $id",
        kind = kind,
        bankName = if (kind == Account.KIND_CASH) "" else "Bank",
        accountMask = if (kind == Account.KIND_CASH) Account.CASH_MASK else "1234-$id",
        accountType = accountType,
        isLiability = isLiability
    )

    private fun txn(
        id: Long,
        accountId: Long,
        direction: String,
        amount: Double,
        occurredAt: Long,
        category: String? = null,
        balanceAfter: Double? = null,
        transferId: Long? = null,
        isExpenseOverride: Int = AccountTransaction.EXPENSE_AUTO
    ) = AccountTransaction(
        id = id,
        accountId = accountId,
        direction = direction,
        amount = amount,
        balanceAfter = balanceAfter,
        occurredAt = occurredAt,
        category = category,
        transferId = transferId,
        isExpenseOverride = isExpenseOverride
    )

    private fun bill(
        id: Long,
        totalDue: Double,
        paidAmount: Double = 0.0,
        isPaid: Boolean = false,
        detectedAt: Long,
        paidAt: Long? = null
    ) = BillWithCard(
        billId = id,
        cardId = 1L,
        bankName = "Bank",
        cardLast4 = "1111",
        cardNickname = "Visa 1111",
        billPeriod = "Mar 2026",
        totalDue = totalDue,
        minDue = 500.0,
        dueDate = "20/03/2026",
        isPaid = isPaid,
        isSuperseded = false,
        paidAmount = paidAmount,
        paidAt = paidAt,
        smsBody = null,
        currency = "BDT",
        detectedAt = detectedAt
    )

    @Test
    fun computeNetAssetAt_accountsLiabilitiesAndBillsOverTime() {
        val savings = bankAccount(1L)
        val loan = bankAccount(2L, accountType = "Loan", isLiability = true)
        val accounts = listOf(savings, loan)

        val d1 = ts(2026, Calendar.MARCH, 5)
        val d2 = ts(2026, Calendar.MARCH, 10)
        val d3 = ts(2026, Calendar.MARCH, 15)
        val d4 = ts(2026, Calendar.MARCH, 20)
        val now = ts(2026, Calendar.MARCH, 25)

        val txnsByAccount = mapOf(
            1L to listOf(
                txn(1, 1L, AccountTransaction.DIRECTION_IN, 50_000.0, d1, category = "Salary"),
                txn(2, 1L, AccountTransaction.DIRECTION_OUT, 5_000.0, d3, category = "Groceries")
            ),
            2L to listOf(
                txn(3, 2L, AccountTransaction.DIRECTION_OUT, 10_000.0, d2, category = "Loan")
            )
        )
        val bills = listOf(
            bill(id = 10L, totalDue = 4_000.0, paidAmount = 4_000.0, isPaid = true, detectedAt = d2, paidAt = d4)
        )

        // On March 6: savings 50k, no loan yet, no bill yet -> net 50k
        val (net6, assets6, liab6) = ChartsCalculator.computeNetAssetAt(
            ChartsCalculator.endOfDay(2026, Calendar.MARCH, 6),
            accounts, txnsByAccount, bills, now
        )
        assertEquals(50_000.0, assets6, 0.01)
        assertEquals(0.0, liab6, 0.01)
        assertEquals(50_000.0, net6, 0.01)

        // On March 12: savings 50k, loan 10k, unpaid bill 4k -> net 36k
        val (net12, assets12, liab12) = ChartsCalculator.computeNetAssetAt(
            ChartsCalculator.endOfDay(2026, Calendar.MARCH, 12),
            accounts, txnsByAccount, bills, now
        )
        assertEquals(50_000.0, assets12, 0.01)
        assertEquals(14_000.0, liab12, 0.01)
        assertEquals(36_000.0, net12, 0.01)

        // On March 25 (now): savings 45k, loan 10k, bill paid on Mar 20 -> net 35k
        val (net25, assets25, liab25) = ChartsCalculator.computeNetAssetAt(
            now, accounts, txnsByAccount, bills, now
        )
        assertEquals(45_000.0, assets25, 0.01)
        assertEquals(10_000.0, liab25, 0.01)
        assertEquals(35_000.0, net25, 0.01)
    }

    @Test
    fun buildDailyMonthlyYearlyNetAssetSeries_produceExpectedPointCountsAndValues() {
        val cash = bankAccount(1L, kind = Account.KIND_CASH, accountType = "Cash")
        val accounts = listOf(cash)
        val now = ts(2026, Calendar.MARCH, 5, hour = 18)

        val txns = listOf(
            txn(1, 1L, AccountTransaction.DIRECTION_IN, 1_000.0, ts(2025, Calendar.DECEMBER, 10)),
            txn(2, 1L, AccountTransaction.DIRECTION_IN, 2_000.0, ts(2026, Calendar.FEBRUARY, 15)),
            txn(3, 1L, AccountTransaction.DIRECTION_OUT, 500.0, ts(2026, Calendar.MARCH, 3))
        )
        val txnsByAccount = mapOf(1L to txns)

        val daily = ChartsCalculator.buildDailyNetAssetSeries(
            2026, Calendar.MARCH, accounts, txnsByAccount, emptyList(), now
        )
        assertEquals(5, daily.size) // Mar 1..Mar 5
        assertEquals(3_000.0, daily[0].netAsset, 0.01)
        assertEquals(2_500.0, daily[4].netAsset, 0.01)

        val monthly = ChartsCalculator.buildMonthlyNetAssetSeries(
            2026, accounts, txnsByAccount, emptyList(), now
        )
        assertEquals(3, monthly.size) // Jan, Feb, Mar
        assertEquals(1_000.0, monthly[0].netAsset, 0.01)
        assertEquals(3_000.0, monthly[1].netAsset, 0.01)
        assertEquals(2_500.0, monthly[2].netAsset, 0.01)

        val yearly = ChartsCalculator.buildYearlyNetAssetSeries(
            accounts, txnsByAccount, emptyList(), now
        )
        assertEquals(2, yearly.size) // 2025, 2026
        assertEquals(1_000.0, yearly[0].netAsset, 0.01)
        assertEquals(2_500.0, yearly[1].netAsset, 0.01)
    }

    @Test
    fun buildCashflowAndCategoryBreakdown_respectsTransfersAndPrevPeriodComparison() {
        val bank = bankAccount(1L)
        val cash = bankAccount(2L, kind = Account.KIND_CASH, accountType = "Cash")
        val accounts = listOf(bank, cash)
        val now = ts(2026, Calendar.MARCH, 20)

        val txns = listOf(
            // Feb 2026: marked income 40k, expense Food 1k
            txn(
                1, 1L, AccountTransaction.DIRECTION_IN, 40_000.0, ts(2026, Calendar.FEBRUARY, 2),
                category = "Salary", isExpenseOverride = AccountTransaction.EXPENSE_INCLUDED
            ),
            txn(2, 2L, AccountTransaction.DIRECTION_OUT, 1_000.0, ts(2026, Calendar.FEBRUARY, 8), category = "Food"),
            // Mar 2026: marked income 50k, unmarked bank credit 15k (must NOT count as income),
            // internal transfer 5k (excluded from income/expense), Food 2k + Card Shopping 3k
            txn(
                3, 1L, AccountTransaction.DIRECTION_IN, 50_000.0, ts(2026, Calendar.MARCH, 1),
                category = "Salary", isExpenseOverride = AccountTransaction.EXPENSE_INCLUDED
            ),
            txn(
                7, 1L, AccountTransaction.DIRECTION_IN, 15_000.0, ts(2026, Calendar.MARCH, 2),
                category = "Refund", isExpenseOverride = AccountTransaction.EXPENSE_AUTO
            ),
            txn(4, 1L, AccountTransaction.DIRECTION_OUT, 5_000.0, ts(2026, Calendar.MARCH, 4), category = "Transfer", transferId = 99L),
            txn(5, 2L, AccountTransaction.DIRECTION_IN, 5_000.0, ts(2026, Calendar.MARCH, 4), category = "Transfer", transferId = 99L),
            txn(6, 2L, AccountTransaction.DIRECTION_OUT, 2_000.0, ts(2026, Calendar.MARCH, 10), category = "Food")
        )
        val cardTxns = listOf(
            CardTransaction(
                id = 100L,
                cardTail = "1111",
                bankName = "Bank",
                merchant = "Store",
                amount = 3_000.0,
                occurredAt = ts(2026, Calendar.MARCH, 12),
                category = "Shopping",
                smsBody = "POS BDT 3000 at Store"
            )
        )

        val monthlySeries = ChartsCalculator.buildCashflowSeries(
            ChartGranularity.MONTHLY, 2026, Calendar.MARCH, accounts, txns, cardTxns, now
        )
        assertEquals(3, monthlySeries.size)
        assertEquals(40_000.0, monthlySeries[1].income, 0.01)
        assertEquals(1_000.0, monthlySeries[1].expense, 0.01)
        assertEquals(50_000.0, monthlySeries[2].income, 0.01)
        assertEquals(5_000.0, monthlySeries[2].expense, 0.01)

        val incomeSlices = ChartsCalculator.buildCategoryBreakdown(
            startMillis = ChartsCalculator.startOfMonth(2026, Calendar.MARCH),
            endMillis = ChartsCalculator.endOfMonth(2026, Calendar.MARCH),
            prevStartMillis = ChartsCalculator.startOfMonth(2026, Calendar.FEBRUARY),
            prevEndMillis = ChartsCalculator.endOfMonth(2026, Calendar.FEBRUARY),
            countedAccountIds = setOf(1L, 2L),
            allAccountTxns = txns,
            allCardTxns = cardTxns,
            showIncome = true
        )
        assertEquals(1, incomeSlices.size)
        assertEquals("Salary", incomeSlices[0].category)
        assertEquals(50_000.0, incomeSlices[0].amount, 0.01)
        assertEquals(40_000.0, incomeSlices[0].prevAmount, 0.01)

        val slices = ChartsCalculator.buildCategoryBreakdown(
            startMillis = ChartsCalculator.startOfMonth(2026, Calendar.MARCH),
            endMillis = ChartsCalculator.endOfMonth(2026, Calendar.MARCH),
            prevStartMillis = ChartsCalculator.startOfMonth(2026, Calendar.FEBRUARY),
            prevEndMillis = ChartsCalculator.endOfMonth(2026, Calendar.FEBRUARY),
            countedAccountIds = setOf(1L, 2L),
            allAccountTxns = txns,
            allCardTxns = cardTxns,
            showIncome = false
        )
        assertEquals(2, slices.size)
        assertEquals("Shopping", slices[0].category)
        assertEquals(3_000.0, slices[0].amount, 0.01)
        assertEquals(0.0, slices[0].prevAmount, 0.01)
        assertEquals("Food", slices[1].category)
        assertEquals(2_000.0, slices[1].amount, 0.01)
        assertEquals(1_000.0, slices[1].prevAmount, 0.01)

        val snapshotMar = ChartsCalculator.buildPeriodSnapshot(
            isYearly = false,
            year = 2026,
            month = Calendar.MARCH,
            accounts = accounts,
            txnsByAccount = txns.groupBy { it.accountId },
            bills = emptyList(),
            allAccountTxns = txns,
            allCardTxns = cardTxns,
            nowMillis = now
        )
        assertEquals(50_000.0, snapshotMar.income, 0.01)
        assertEquals(5_000.0, snapshotMar.expense, 0.01)
        assertEquals(45_000.0, snapshotMar.netCashflow, 0.01)
        assertTrue(snapshotMar.topCategories.isNotEmpty())
    }

    @Test
    fun countsAsIncomeAndCountsAsExpense_doNotCollide() {
        // Marked IN transaction must count as income and NEVER leak into expense
        assertTrue(TxnCategory.countsAsIncome(AccountTransaction.EXPENSE_INCLUDED, AccountTransaction.DIRECTION_IN))
        assertFalse(TxnCategory.countsAsExpense(AccountTransaction.EXPENSE_INCLUDED, AccountTransaction.DIRECTION_IN, "Salary"))

        // Unmarked IN transaction counts as neither income nor expense
        assertFalse(TxnCategory.countsAsIncome(AccountTransaction.EXPENSE_AUTO, AccountTransaction.DIRECTION_IN))
        assertFalse(TxnCategory.countsAsExpense(AccountTransaction.EXPENSE_AUTO, AccountTransaction.DIRECTION_IN, "Salary"))

        // OUT transaction with EXPENSE_INCLUDED counts as expense and NEVER as income
        assertFalse(TxnCategory.countsAsIncome(AccountTransaction.EXPENSE_INCLUDED, AccountTransaction.DIRECTION_OUT))
        assertTrue(TxnCategory.countsAsExpense(AccountTransaction.EXPENSE_INCLUDED, AccountTransaction.DIRECTION_OUT, "Food"))
    }

    @Test
    fun buildAllocationBreakdown_groupsAssetsAndLiabilities() {
        val savings = bankAccount(1L, accountType = "Savings", isLiability = false)
        val loan = bankAccount(2L, accountType = "Loan", isLiability = true)
        val txns = mapOf(
            1L to listOf(txn(1, 1L, AccountTransaction.DIRECTION_IN, 25_000.0, ts(2026, Calendar.MARCH, 1))),
            2L to listOf(txn(2, 2L, AccountTransaction.DIRECTION_OUT, 8_000.0, ts(2026, Calendar.MARCH, 2)))
        )
        val bills = listOf(bill(id = 1L, totalDue = 3_000.0, detectedAt = ts(2026, Calendar.MARCH, 5)))
        val types = listOf(
            AccountTypeConfig(name = "Savings", isLiability = false),
            AccountTypeConfig(name = "Loan", isLiability = true)
        )

        val allocation = ChartsCalculator.buildAllocationBreakdown(listOf(savings, loan), txns, bills, types)
        assertEquals(3, allocation.size)
        assertEquals("Savings", allocation[0].label)
        assertEquals(false, allocation[0].isLiability)
        assertEquals(25_000.0, allocation[0].amount, 0.01)
        assertEquals("Loan", allocation[1].label)
        assertEquals(true, allocation[1].isLiability)
        assertEquals(8_000.0, allocation[1].amount, 0.01)
        assertEquals("Credit Cards", allocation[2].label)
        assertEquals(true, allocation[2].isLiability)
        assertEquals(3_000.0, allocation[2].amount, 0.01)
    }
}
