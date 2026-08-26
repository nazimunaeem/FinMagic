package com.finmagic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Links money movement between two accounts (bank→cash ATM, bank→wallet funding). */
@Entity(tableName = "transfers", indices = [Index("fromAccountId"), Index("toAccountId"), Index("occurredAt")])
data class Transfer(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fromAccountId: Long,
    val toAccountId: Long,
    val amount: Double,
    val occurredAt: Long
)
