package com.finmagic.data.repository

import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountSummary
import com.finmagic.data.local.entity.AccountTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountBalanceTrailTest {

    @Test
    fun balanceTrails_cashAccountWithoutBalanceAfter_startsAtZero() {
        val txns = listOf(
            AccountTransaction(id = 1L, accountId = 1L, direction = AccountTransaction.DIRECTION_IN, amount = 5000.0, occurredAt = 100L),
            AccountTransaction(id = 2L, accountId = 1L, direction = AccountTransaction.DIRECTION_OUT, amount = 1250.50, occurredAt = 200L),
            AccountTransaction(id = 3L, accountId = 1L, direction = AccountTransaction.DIRECTION_IN, amount = 300.0, occurredAt = 300L)
        )

        val trails = AccountRepository.computeBalanceTrails(txns)
        assertEquals(0.0, trails[1L]!!.before, 0.001)
        assertEquals(5000.0, trails[1L]!!.after, 0.001)
        assertEquals(5000.0, trails[2L]!!.before, 0.001)
        assertEquals(3749.50, trails[2L]!!.after, 0.001)
        assertEquals(3749.50, trails[3L]!!.before, 0.001)
        assertEquals(4049.50, trails[3L]!!.after, 0.001)
        assertEquals(4049.50, AccountRepository.computeAccountBalance(txns), 0.001)
    }

    @Test
    fun balanceTrails_bankAccountWithSmsAnchorAndLaterTransfer_computesExactTrailAndBalance() {
        val txns = listOf(
            AccountTransaction(id = 10L, accountId = 2L, direction = AccountTransaction.DIRECTION_OUT, amount = 500.0, balanceAfter = 9500.0, occurredAt = 100L),
            AccountTransaction(id = 11L, accountId = 2L, direction = AccountTransaction.DIRECTION_IN, amount = 2000.0, balanceAfter = 11500.0, occurredAt = 200L),
            AccountTransaction(id = 12L, accountId = 2L, direction = AccountTransaction.DIRECTION_OUT, amount = 1500.0, balanceAfter = null, occurredAt = 300L)
        )

        val trails = AccountRepository.computeBalanceTrails(txns)
        assertEquals(10000.0, trails[10L]!!.before, 0.001)
        assertEquals(9500.0, trails[10L]!!.after, 0.001)
        assertEquals(9500.0, trails[11L]!!.before, 0.001)
        assertEquals(11500.0, trails[11L]!!.after, 0.001)
        assertEquals(11500.0, trails[12L]!!.before, 0.001)
        assertEquals(10000.0, trails[12L]!!.after, 0.001)
        assertEquals(10000.0, AccountRepository.computeAccountBalance(txns), 0.001)
    }

    @Test
    fun balanceTrails_backfillsPriorTransactionsBeforeFirstBalanceAnchor() {
        val txns = listOf(
            AccountTransaction(id = 20L, accountId = 3L, direction = AccountTransaction.DIRECTION_OUT, amount = 400.0, balanceAfter = null, occurredAt = 100L),
            AccountTransaction(id = 21L, accountId = 3L, direction = AccountTransaction.DIRECTION_IN, amount = 1000.0, balanceAfter = 5600.0, occurredAt = 200L)
        )

        val trails = AccountRepository.computeBalanceTrails(txns)
        assertEquals(5000.0, trails[20L]!!.before, 0.001)
        assertEquals(4600.0, trails[20L]!!.after, 0.001)
        assertEquals(4600.0, trails[21L]!!.before, 0.001)
        assertEquals(5600.0, trails[21L]!!.after, 0.001)
    }

    @Test
    fun balanceTrails_manualSavingsWithDirectSetOpeningAndSubsequentTxn_updatesBalance() {
        val txns = listOf(
            AccountTransaction(
                id = 30L,
                accountId = 4L,
                direction = AccountTransaction.DIRECTION_IN,
                amount = 25000.0,
                balanceAfter = 25000.0,
                occurredAt = 100L,
                category = "DirectSet"
            ),
            AccountTransaction(
                id = 31L,
                accountId = 4L,
                direction = AccountTransaction.DIRECTION_IN,
                amount = 3500.0,
                balanceAfter = null,
                occurredAt = 200L
            )
        )

        val current = AccountRepository.computeAccountBalance(txns)
        assertEquals(28500.0, current, 0.001)

        val summary = AccountSummary(
            account = Account(id = 4L, name = "BRAC Savings", kind = Account.KIND_BANK, accountMask = "1234", accountType = Account.TYPE_SAVINGS),
            latestBalance = 25000.0,
            computedBalance = 28500.0,
            monthIn = 3500.0,
            monthOut = 0.0,
            currentBalance = current
        )
        assertEquals(28500.0, summary.displayBalance, 0.001)
    }
}
