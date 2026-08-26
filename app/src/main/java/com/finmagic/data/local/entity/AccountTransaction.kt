package com.finmagic.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "account_transactions",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("accountId"), Index("occurredAt"), Index("smsBody"), Index("transferId")]
)
data class AccountTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val accountId: Long,
    /** IN | OUT */
    val direction: String,
    val amount: Double,
    val currency: String = "BDT",
    /** Balance reported by the bank after this txn; enables self-heal display. */
    val balanceAfter: Double? = null,
    val occurredAt: Long,
    val category: String? = null,
    val counterparty: String? = null,
    val smsRecordId: Long? = null,
    val transferId: Long? = null,
    /** Full SMS body for dedup (null for manual rows). */
    val smsBody: String? = null,
    val isManual: Boolean = false,
    /**
     * Expenses-tab membership override (stored as INTEGER; no schema change):
     * -1 = force-exclude, 0 = auto, 1 = force-include.
     */
    val isExpenseOverride: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val DIRECTION_IN = "IN"
        const val DIRECTION_OUT = "OUT"
        const val EXPENSE_EXCLUDED = -1
        const val EXPENSE_AUTO = 0
        const val EXPENSE_INCLUDED = 1
    }
}
