package com.finmagic.data.repository

import com.finmagic.data.local.entity.AccountTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountAdjustmentMathTest {

    @Test
    fun directSetEntriesAreIgnoredWhenComputingBalance() {
        val txns = listOf(
            AccountTransaction(
                accountId = 1L,
                direction = AccountTransaction.DIRECTION_IN,
                amount = 1000.0,
                occurredAt = 1L
            ),
            AccountTransaction(
                accountId = 1L,
                direction = AccountTransaction.DIRECTION_OUT,
                amount = 250.0,
                occurredAt = 2L
            ),
            AccountTransaction(
                accountId = 1L,
                direction = AccountTransaction.DIRECTION_IN,
                amount = 75.0,
                occurredAt = 3L,
                category = "DirectSet"
            ),
            AccountTransaction(
                accountId = 1L,
                direction = AccountTransaction.DIRECTION_OUT,
                amount = 50.0,
                occurredAt = 4L,
                category = "DirectSet"
            )
        )

        assertEquals(750.0, AccountRepository.ledgerBalance(txns), 0.01)
    }

    @Test
    fun adjustmentDeltaUsesNaturalBalanceInsteadOfPreviousOverride() {
        val txns = listOf(
            AccountTransaction(
                accountId = 1L,
                direction = AccountTransaction.DIRECTION_IN,
                amount = 1200.0,
                occurredAt = 1L
            ),
            AccountTransaction(
                accountId = 1L,
                direction = AccountTransaction.DIRECTION_OUT,
                amount = 400.0,
                occurredAt = 2L
            ),
            AccountTransaction(
                accountId = 1L,
                direction = AccountTransaction.DIRECTION_IN,
                amount = 150.0,
                occurredAt = 3L,
                category = "DirectSet"
            )
        )

        assertEquals(550.0, AccountRepository.ledgerBalance(txns), 0.01)
        assertEquals(150.0, AccountRepository.calculateAdjustmentDelta(700.0, txns), 0.01)
    }
}
