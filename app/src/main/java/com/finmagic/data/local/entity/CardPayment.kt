package com.finmagic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "card_payments",
    indices = [
        Index(value = ["smsBody"], unique = true),
        Index("appliedBillId")
    ]
)
data class CardPayment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val cardTail: String,
    val amountBdt: Double,
    val amountUsd: Double,
    val paidAt: Long,
    val smsBody: String,
    val appliedBillId: Long? = null,
    val detectedAt: Long = System.currentTimeMillis()
)
