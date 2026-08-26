package com.finmagic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sms_teaching_rules",
    indices = [
        Index("senderKey"),
        Index("enabled"),
        Index("createdAt")
    ]
)
data class SmsTeachingRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val senderKey: String?,
    val requiredKeywords: String,
    val excludedKeywords: String = "",
    
    // Field extraction patterns (Phase 3)
    val amountPattern: String? = null,
    val datePattern: String? = null,
    val balancePattern: String? = null,
    val accountMaskPattern: String? = null,
    val counterpartyPattern: String? = null,
    
    val action: String,
    val accountId: Long,
    val category: String? = null,
    
    // Account creation support (Phase 4)
    val accountTemplateJson: String? = null,
    
    val enabled: Boolean = true,
    val priority: Int = 0,
    val matchCount: Int = 0,
    val lastMatchedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val ACTION_CREDIT = "CREDIT"
        const val ACTION_DEBIT = "DEBIT"
        const val ACTION_TRANSFER = "TRANSFER"
        const val ACTION_ATM_WITHDRAWAL = "ATM_WITHDRAWAL"
        const val ACTION_BALANCE_ONLY = "BALANCE_ONLY"
        const val ACTION_IGNORE = "IGNORE"
    }

    val actionLabel: String
        get() = when (action) {
            ACTION_CREDIT -> "Credit"
            ACTION_DEBIT -> "Debit"
            ACTION_TRANSFER -> "Transfer"
            ACTION_ATM_WITHDRAWAL -> "ATM Withdrawal"
            ACTION_BALANCE_ONLY -> "Balance Only"
            ACTION_IGNORE -> "Ignore"
            else -> action
        }

    val summaryKeywords: String
        get() {
            val parts = mutableListOf<String>()
            senderKey?.let { parts.add("From: $it") }
            val req = requiredKeywords.split('|').filter { it.isNotBlank() }
            if (req.isNotEmpty()) parts.add("Keywords: ${req.joinToString(", ")}")
            val exc = excludedKeywords.split('|').filter { it.isNotBlank() }
            if (exc.isNotEmpty()) parts.add("Exclude: ${exc.joinToString(", ")}")
            return parts.joinToString(" · ")
        }
}
