package com.finmagic.data.local.entity

import androidx.room.Embedded

/** Aggregated row for the Accounts tab: account + computed balances/flows. */
data class AccountSummary(
    @Embedded val account: Account,
    /** Newest bank-reported balance (self-heal: latest wins), null if never reported. */
    val latestBalance: Double?,
    /** SUM(IN) - SUM(OUT); authoritative for Cash, fallback otherwise. */
    val computedBalance: Double,
    val monthIn: Double,
    val monthOut: Double,
    /** Millis of the newest non-direct-set transaction; used for activity ordering. */
    val lastActivity: Long = 0L
) {
    /** Balance shown in UI: bank-reported when available, else computed. */
    val displayBalance: Double
        get() = if (account.kind == Account.KIND_CASH) computedBalance else (latestBalance ?: computedBalance)
}
