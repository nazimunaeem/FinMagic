package com.finmagic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sms_rule_applications",
    indices = [Index("ruleId"), Index("smsRecordId")]
)
data class SmsRuleApplication(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val smsRecordId: Long,
    val transactionId: Long? = null,
    val appliedAt: Long = System.currentTimeMillis()
)
