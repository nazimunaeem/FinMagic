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
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val KIND_BANK = "BANK"
        const val KIND_WALLET = "WALLET"
        const val KIND_CASH = "CASH"
        const val CASH_MASK = "CASH"
    }

    val displayName: String
        get() = when (kind) {
            KIND_CASH -> "Cash"
            else -> name
        }
}
