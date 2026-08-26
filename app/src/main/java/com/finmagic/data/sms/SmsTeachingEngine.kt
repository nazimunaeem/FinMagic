package com.finmagic.data.sms

import com.finmagic.data.local.dao.SmsRuleApplicationDao
import com.finmagic.data.local.dao.SmsTeachingRuleDao
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.local.entity.SmsRuleApplication
import com.finmagic.data.local.entity.SmsTeachingRule
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.sms.parsers.LedgerDirection
import com.finmagic.data.sms.parsers.LedgerEntry
import com.finmagic.data.sms.parsers.AccountKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matches user-defined teaching rules against unparsed SMS and applies them.
 *
 * Rule priority (plan §3.2):
 * 1. Enabled correction overrides with exact sender and most required keywords.
 * 2. Enabled teaching rules with exact sender and most required keywords.
 * 3. Enabled rules with exact sender only.
 * 4. Enabled body-only rules.
 *
 * Built-in parsers keep precedence (they run before this engine in SmsScanWorker).
 */
@Singleton
class SmsTeachingEngine @Inject constructor(
    private val ruleDao: SmsTeachingRuleDao,
    private val ruleApplicationDao: SmsRuleApplicationDao,
    private val accountRepository: AccountRepository
) {
    /**
     * Attempts to match and apply the first matching rule for [record].
     * Returns true if a rule was applied (transaction created or SMS ignored).
     */
    suspend fun applyFirstMatch(record: SmsRecord): Boolean {
        val normalizedBody = normalizeText(record.body)
        val sender = record.address?.trim()?.uppercase()

        val rules = ruleDao.getEnabledOnce()
        val match = findBestMatch(rules, sender, normalizedBody) ?: return false

        val applied = applyRule(match, record)
        if (applied) {
            ruleDao.incrementMatchCount(match.id)
            ruleApplicationDao.insert(
                SmsRuleApplication(
                    ruleId = match.id,
                    smsRecordId = record.id
                )
            )
        }
        return applied
    }

    /**
     * Finds the best matching rule using specificity scoring (plan §3.2).
     * Returns null if no rule matches or if two rules conflict.
     */
    fun findBestMatch(
        rules: List<SmsTeachingRule>,
        sender: String?,
        normalizedBody: String
    ): SmsTeachingRule? {
        // Delegate to the static companion method for testability.
        var bestMatch: SmsTeachingRule? = null
        var bestScore = -1
        for (rule in rules) {
            if (!matchesRule(rule, sender, normalizedBody)) continue
            val score = scoreRule(rule, sender)
            if (score > bestScore) {
                bestScore = score
                bestMatch = rule
            } else if (score == bestScore && bestMatch != null) {
                if (bestMatch.action != rule.action || bestMatch.accountId != rule.accountId) {
                    return null
                }
            }
        }
        return bestMatch
    }

    private suspend fun applyRule(rule: SmsTeachingRule, record: SmsRecord): Boolean {
        return when (rule.action) {
            SmsTeachingRule.ACTION_IGNORE -> true

            SmsTeachingRule.ACTION_CREDIT -> {
                val amount = extractAmount(record.body) ?: return false
                accountRepository.storeManualToAccount(
                    accountId = rule.accountId,
                    direction = AccountTransaction.DIRECTION_IN,
                    amount = amount,
                    occurredAt = record.timestamp,
                    category = rule.category ?: "Manual",
                    counterparty = "Taught SMS",
                    smsBody = record.body,
                    smsRecordId = record.id
                )
            }

            SmsTeachingRule.ACTION_DEBIT -> {
                val amount = extractAmount(record.body) ?: return false
                accountRepository.storeManualToAccount(
                    accountId = rule.accountId,
                    direction = AccountTransaction.DIRECTION_OUT,
                    amount = amount,
                    occurredAt = record.timestamp,
                    category = rule.category ?: "Manual",
                    counterparty = "Taught SMS",
                    smsBody = record.body,
                    smsRecordId = record.id
                )
            }

            SmsTeachingRule.ACTION_ATM_WITHDRAWAL -> {
                val amount = extractAmount(record.body) ?: return false
                val entry = LedgerEntry(
                    institution = "",
                    kind = AccountKind.BANK,
                    accountMask = "",
                    direction = LedgerDirection.OUT,
                    amount = amount,
                    occurredAt = record.timestamp
                )
                accountRepository.storeAtmWithdraw(entry, record.body, record.id)
            }

            SmsTeachingRule.ACTION_TRANSFER -> {
                val amount = extractAmount(record.body) ?: return false
                val fromEntry = LedgerEntry(
                    institution = "",
                    kind = AccountKind.BANK,
                    accountMask = "",
                    direction = LedgerDirection.OUT,
                    amount = amount,
                    occurredAt = record.timestamp
                )
                val toEntry = LedgerEntry(
                    institution = "",
                    kind = AccountKind.CASH,
                    accountMask = "CASH",
                    direction = LedgerDirection.IN,
                    amount = amount,
                    occurredAt = record.timestamp
                )
                accountRepository.storeTransferPair(fromEntry, toEntry, record.body, record.id)
            }

            SmsTeachingRule.ACTION_BALANCE_ONLY -> {
                true
            }

            else -> false
        }
    }

    companion object {
        /** Generic amount extraction from common Bangladeshi SMS formats (plan §4). */
        private val amountRegex = Regex(
            """(?:Tk|BDT|Tk\.|BDT\.)\s*([\d,]+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )
        
        /** Date extraction patterns */
        private val dateRegex = Regex(
            """(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""",
            RegexOption.IGNORE_CASE
        )
        
        /** Balance extraction patterns */
        private val balanceRegex = Regex(
            """(?:Balance|Available balance|Current Balance|C/B|A/C\s*Bal)[:\s]*(?:Tk|BDT)?\.?\s*([\d,]+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )
        
        /** Account mask extraction */
        private val accountMaskRegex = Regex(
            """(?:A/C|A/C#|Account|A/C\s*#?)\s*\*+[\d*]+""",
            RegexOption.IGNORE_CASE
        )
        
        /** Counterparty patterns */
        private val counterpartyRegex = Regex(
            """(?:from|to|at)\s+([A-Za-z\s]{3,40})(?:\s+[Aa]ccount)?""",
            RegexOption.IGNORE_CASE
        )

        /** Field extraction data class */
        data class ExtractedFields(
            val amount: Double? = null,
            val date: Long? = null,
            val balance: Double? = null,
            val accountMask: String? = null,
            val counterparty: String? = null
        )

        fun extractAmount(body: String): Double? {
            return amountRegex.find(body)?.groupValues?.get(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()
        }
        
        fun extractAllFields(body: String): ExtractedFields {
            val amount = amountRegex.find(body)?.groupValues?.get(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()
                
            val date = dateRegex.find(body)?.value?.let { parseDate(it) }
            
            val balance = balanceRegex.find(body)?.groupValues?.get(1)
                ?.replace(",", "")
                ?.toDoubleOrNull()
                
            val accountMask = accountMaskRegex.find(body)?.value
            
            val counterparty = counterpartyRegex.find(body)?.groupValues?.get(1)?.trim()
            
            return ExtractedFields(amount, date, balance, accountMask, counterparty)
        }
        
        private fun parseDate(dateStr: String): Long? {
            return try {
                // Try multiple date formats
                val formats = listOf(
                    "dd/MM/yyyy",
                    "dd-MM-yyyy", 
                    "dd/MM/yy",
                    "dd-MM-yy"
                )
                
                for (format in formats) {
                    try {
                        val sdf = java.text.SimpleDateFormat(format, java.util.Locale.getDefault())
                        sdf.parse(dateStr)?.time
                    } catch (e: Exception) {
                        null
                    }
                } ?: null
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Generates a regex pattern for a selected field (plan §4.1).
         * Safely escapes literal text and replaces selected span with named capture.
         */
        fun generatePattern(fullText: String, selectedSpan: String, fieldName: String): String? {
            try {
                val escaped = Regex.escapeReplacement(fullText)
                val startIndex = fullText.indexOf(selectedSpan)
                if (startIndex == -1) return null
                
                val before = escaped.substring(0, startIndex)
                val after = escaped.substring(startIndex + selectedSpan.length)
                val patternContent = Regex.escape(selectedSpan)
                
                // Validate the pattern isn't too complex
                if (patternContent.length > 100 || before.length > 200 || after.length > 200) {
                    return null
                }
                
                return "$before(?<$fieldName>$patternContent)$after"
            } catch (e: Exception) {
                return null
            }
        }

        /** Normalize text for matching (plan §3.1). */
        fun normalizeText(text: String): String {
            return text
                .replace('\u00A0', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /**
         * Finds the best matching rule using specificity scoring (plan §3.2).
         * Returns null if no rule matches or if two rules conflict.
         * This is a pure function — safe to call from unit tests without injection.
         */
        fun findBestMatch(
            rules: List<SmsTeachingRule>,
            sender: String?,
            normalizedBody: String
        ): SmsTeachingRule? {
            var bestMatch: SmsTeachingRule? = null
            var bestScore = -1

            for (rule in rules) {
                if (!matchesRule(rule, sender, normalizedBody)) continue

                val score = scoreRule(rule, sender)
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = rule
                } else if (score == bestScore && bestMatch != null) {
                    if (bestMatch.action != rule.action || bestMatch.accountId != rule.accountId) {
                        return null
                    }
                }
            }
            return bestMatch
        }

        private fun matchesRule(rule: SmsTeachingRule, sender: String?, normalizedBody: String): Boolean {
            if (rule.senderKey?.isNotBlank() == true) {
                if (sender == null || !sender.contains(rule.senderKey.uppercase())) return false
            }
            val required = rule.requiredKeywords.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (required.any { !normalizedBody.contains(it, ignoreCase = true) }) return false
            val excluded = rule.excludedKeywords.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (excluded.any { normalizedBody.contains(it, ignoreCase = true) }) return false
            return true
        }

        private fun scoreRule(rule: SmsTeachingRule, sender: String?): Int {
            var score = 0
            if (rule.senderKey?.isNotBlank() == true && sender != null) score += 100
            val required = rule.requiredKeywords.split('|').map { it.trim() }.filter { it.isNotEmpty() }
            score += required.size * 10
            score += rule.priority
            return score
        }
    }
}
