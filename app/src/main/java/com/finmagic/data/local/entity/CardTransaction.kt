package com.finmagic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A credit/debit card POS purchase parsed from an SMS (plan §2.1 Phase 4). */
@Entity(
    tableName = "card_transactions",
    indices = [Index(value = ["smsBody"], unique = true), Index("occurredAt")]
)
data class CardTransaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cardTail: String,
    val bankName: String,
    val merchant: String,
    val amount: Double,
    val occurredAt: Long,
    val balanceAfter: Double? = null,
    val category: String? = null,
    val smsBody: String,
    val detectedAt: Long = System.currentTimeMillis()
)
