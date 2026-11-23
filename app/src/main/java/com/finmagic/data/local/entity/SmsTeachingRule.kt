package com.finmagic.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.finmagic.data.local.KeywordJson

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
    /** JSON array of required keywords (Plan §3). Use KeywordJson.encode/decode. */
    val requiredKeywords: String,
    /** JSON array of excluded keywords (Plan §3). Use KeywordJson.encode/decode. */
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
    val updatedAt: Long = System.currentTimeMillis(),
    val cardId: Long? = null
) {
    companion object {
        const val ACTION_CREDIT = "CREDIT"
        const val ACTION_DEBIT = "DEBIT"
        const val ACTION_TRANSFER = "TRANSFER"
        const val ACTION_ATM_WITHDRAWAL = "ATM_WITHDRAWAL"
        const val ACTION_BALANCE_ONLY = "BALANCE_ONLY"
        const val ACTION_IGNORE = "IGNORE"
        const val ACTION_CARD_DEBIT = "CARD_DEBIT"
        const val ACTION_CARD_CREDIT = "CARD_CREDIT"
    }

    val actionLabel: String
        get() = when (action) {
            ACTION_CREDIT -> "Credit"
            ACTION_DEBIT -> "Debit"
            ACTION_TRANSFER -> "Transfer"
            ACTION_ATM_WITHDRAWAL -> "ATM Withdrawal"
            ACTION_BALANCE_ONLY -> "Balance Only"
            ACTION_IGNORE -> "Ignore"
            ACTION_CARD_DEBIT -> "Card Debit"
            ACTION_CARD_CREDIT -> "Card Credit"
            else -> action
        }

    /** Parsed list of required keywords (decoded from JSON). */
    val requiredKeywordsList: List<String>
        get() = KeywordJson.decode(requiredKeywords)
    
    /** Parsed list of excluded keywords (decoded from JSON). */
    val excludedKeywordsList: List<String>
        get() = KeywordJson.decode(excludedKeywords)

    val summaryKeywords: String
        get() {
            val parts = mutableListOf<String>()
            senderKey?.let { parts.add("From: $it") }
            val req = requiredKeywordsList
            if (req.isNotEmpty()) parts.add("Keywords: ${req.joinToString(", ")}")
            val exc = excludedKeywordsList
            if (exc.isNotEmpty()) parts.add("Exclude: ${exc.joinToString(", ")}")
            return parts.joinToString(" · ")
        }
}
