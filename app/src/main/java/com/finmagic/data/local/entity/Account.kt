package com.finmagic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A tracked money account: bank savings/current, mobile wallet or the built-in
 * Cash singleton (plan §2.2). Identity = (bankName/institution, accountMask).
 */
@Entity(
    tableName = "accounts",
    indices = [Index(value = ["bankName", "accountMask"], unique = true)]
)
data class Account(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** BANK | WALLET | CASH */
    val kind: String,
    /** Institution key ("BRAC Bank", "bKash"); empty for Cash. */
    val bankName: String = "",
    /** Masked number as it appears in SMS ("10551**0001", "1**88", "CASH"). */
    val accountMask: String,
    val currency: String = "BDT",
    val isArchived: Boolean = false,
    /** When false, this account's balance is excluded from the aggregated total. */
    val includeInTotal: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    /** User-visible account type: Cash, Savings, Deposit, Loan, or custom type name. */
    val accountType: String = "",
    /** True when this account represents a liability (e.g. Loan) subtracted from net balance. */
    val isLiability: Boolean = false
) {
    companion object {
        const val KIND_BANK = "BANK"
        const val KIND_WALLET = "WALLET"
        const val KIND_CASH = "CASH"
        const val CASH_MASK = "CASH"

        const val TYPE_CASH = "Cash"
        const val TYPE_SAVINGS = "Savings"
        const val TYPE_DEPOSIT = "Deposit"
        const val TYPE_LOAN = "Loan"

        val DEFAULT_TYPES: List<Pair<String, Boolean>> = listOf(
            TYPE_CASH to false,
            TYPE_SAVINGS to false,
            TYPE_DEPOSIT to false,
            TYPE_LOAN to true
        )
    }

    val effectiveType: String
        get() = accountType.ifBlank {
            when (kind) {
                KIND_CASH -> TYPE_CASH
                else -> TYPE_SAVINGS
            }
        }

    val displayName: String
        get() = when (kind) {
            KIND_CASH -> "Cash"
            else -> name
        }
}
