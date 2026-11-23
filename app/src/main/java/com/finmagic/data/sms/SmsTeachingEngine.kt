package com.finmagic.data.sms

import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.dao.SmsRuleApplicationDao
import com.finmagic.data.local.dao.SmsTeachingRuleDao
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.local.entity.SmsRuleApplication
import com.finmagic.data.local.entity.SmsTeachingRule
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.BillRepository
import com.finmagic.data.sms.parsers.LedgerDirection
import com.finmagic.data.sms.parsers.LedgerEntry
import com.finmagic.data.sms.parsers.AccountKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
/** Field extraction data class */
data class ExtractedFields(
    val amount: Double? = null,
    val date: Long? = null,
    val balance: Double? = null,
    val accountMask: String? = null,
    val counterparty: String? = null
)

data class AccountTemplate(
    val name: String,
    val kind: String,
    val bankName: String = "",
    val accountMask: String = "",
    val currency: String = "BDT",
    val includeInTotal: Boolean = true
)

class SmsTeachingEngine @Inject constructor(
    private val ruleDao: SmsTeachingRuleDao,
    private val ruleApplicationDao: SmsRuleApplicationDao,
    private val accountRepository: AccountRepository,
    private val billRepository: BillRepository,
    private val creditCardDao: CreditCardDao,
    private val cardTransactionDao: CardTransactionDao,
    private val cardPaymentDao: CardPaymentDao
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

        val (applied, txnId) = applyRule(match, record)
        if (applied) {
            ruleDao.incrementMatchCount(match.id)
            ruleApplicationDao.insert(
                SmsRuleApplication(
                    ruleId = match.id,
                    smsRecordId = record.id,
                    transactionId = txnId
                )
            )
        }
        return applied
    }

    /**
     * Applies a specific [rule] to all matching unparsed [unparsedRecords].
     * Returns the list of SMS record IDs that were successfully processed and applied.
     */
    suspend fun applyRuleToHistorical(
        rule: SmsTeachingRule,
        unparsedRecords: List<SmsRecord>,
        excludeRecordId: Long? = null
    ): List<Long> {
        val appliedIds = mutableListOf<Long>()
        for (record in unparsedRecords) {
            if (excludeRecordId != null && record.id == excludeRecordId) continue
            val normalizedBody = normalizeText(record.body)
            val sender = record.address?.trim()?.uppercase()
            if (matchesRule(rule, sender, normalizedBody)) {
                val (applied, txnId) = applyRule(rule, record)
                if (applied) {
                    if (rule.id > 0L) {
                        ruleDao.incrementMatchCount(rule.id)
                        ruleApplicationDao.insert(
                            SmsRuleApplication(
                                ruleId = rule.id,
                                smsRecordId = record.id,
                                transactionId = txnId
                            )
                        )
                    }
                    appliedIds.add(record.id)
                }
            }
        }
        return appliedIds
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
                if (bestMatch.action != rule.action || bestMatch.accountId != rule.accountId || bestMatch.cardId != rule.cardId) {
                    return null
                }
            }
        }
        return bestMatch
    }

    private suspend fun resolveRuleAccountId(rule: SmsTeachingRule): Long {
        if (rule.accountId > 0L && accountRepository.getAccountOnce(rule.accountId) != null) {
            return rule.accountId
        }
        val template = TemplateJson.decode(rule.accountTemplateJson) ?: return rule.accountId
        return accountRepository.resolveOrCreateFromTemplate(template) ?: rule.accountId
    }

    private suspend fun applyRule(rule: SmsTeachingRule, record: SmsRecord): Pair<Boolean, Long?> {
        // Extract fields using rule patterns if available, fall back to generic extraction
        val extracted = extractFieldsWithPatterns(rule, record.body)

        return when (rule.action) {
            SmsTeachingRule.ACTION_IGNORE -> true to null

            SmsTeachingRule.ACTION_CREDIT -> {
                val amount = extracted.amount ?: return false to null
                val targetAccountId = resolveRuleAccountId(rule)
                val txnId = accountRepository.storeManualToAccount(
                    accountId = targetAccountId,
                    direction = AccountTransaction.DIRECTION_IN,
                    amount = amount,
                    occurredAt = extracted.date ?: record.timestamp,
                    category = rule.category ?: "Manual",
                    counterparty = extracted.counterparty ?: "Taught SMS",
                    smsBody = record.body,
                    smsRecordId = record.id,
                    balanceAfter = extracted.balance
                )
                (txnId != null) to txnId
            }

            SmsTeachingRule.ACTION_DEBIT -> {
                val amount = extracted.amount ?: return false to null
                val targetAccountId = resolveRuleAccountId(rule)
                val txnId = accountRepository.storeManualToAccount(
                    accountId = targetAccountId,
                    direction = AccountTransaction.DIRECTION_OUT,
                    amount = amount,
                    occurredAt = extracted.date ?: record.timestamp,
                    category = rule.category ?: "Manual",
                    counterparty = extracted.counterparty ?: "Taught SMS",
                    smsBody = record.body,
                    smsRecordId = record.id,
                    balanceAfter = extracted.balance
                )
                (txnId != null) to txnId
            }

            SmsTeachingRule.ACTION_ATM_WITHDRAWAL -> {
                val amount = extracted.amount ?: return false to null
                val targetAccountId = resolveRuleAccountId(rule)
                val acct = if (targetAccountId > 0L) accountRepository.getAccountOnce(targetAccountId) else null
                val acctKind = runCatching { AccountKind.valueOf(acct?.kind ?: "") }.getOrElse { AccountKind.BANK }
                val entry = LedgerEntry(
                    institution = acct?.bankName.orEmpty(),
                    kind = acctKind,
                    accountMask = acct?.accountMask.orEmpty(),
                    direction = LedgerDirection.OUT,
                    amount = amount,
                    occurredAt = extracted.date ?: record.timestamp,
                    balanceAfter = extracted.balance
                )
                val txnId = accountRepository.storeAtmWithdraw(entry, record.body, record.id)
                (txnId != null) to txnId
            }

            SmsTeachingRule.ACTION_TRANSFER -> {
                val amount = extracted.amount ?: return false to null
                val targetAccountId = resolveRuleAccountId(rule)
                val acct = if (targetAccountId > 0L) accountRepository.getAccountOnce(targetAccountId) else null
                val acctKind = runCatching { AccountKind.valueOf(acct?.kind ?: "") }.getOrElse { AccountKind.BANK }
                val fromEntry = LedgerEntry(
                    institution = acct?.bankName.orEmpty(),
                    kind = acctKind,
                    accountMask = acct?.accountMask.orEmpty(),
                    direction = LedgerDirection.OUT,
                    amount = amount,
                    occurredAt = extracted.date ?: record.timestamp,
                    balanceAfter = extracted.balance
                )
                val toEntry = LedgerEntry(
                    institution = "",
                    kind = AccountKind.CASH,
                    accountMask = "CASH",
                    direction = LedgerDirection.IN,
                    amount = amount,
                    occurredAt = extracted.date ?: record.timestamp,
                    balanceAfter = extracted.balance
                )
                val txnId = accountRepository.storeTransferPair(fromEntry, toEntry, record.body, record.id)
                (txnId != null) to txnId
            }

            SmsTeachingRule.ACTION_CARD_DEBIT -> {
                val amount = extracted.amount ?: return false to null
                val cardId = rule.cardId ?: return false to null
                val card = creditCardDao.getCardById(cardId) ?: return false to null
                cardTransactionDao.deleteBySmsBody(record.body)
                val insertedId = cardTransactionDao.insert(
                    CardTransaction(
                        cardTail = card.cardLast4,
                        bankName = card.bankName,
                        merchant = extracted.counterparty?.takeIf { it.isNotBlank() } ?: "Card Spend",
                        amount = amount,
                        occurredAt = extracted.date ?: record.timestamp,
                        balanceAfter = extracted.balance,
                        category = rule.category?.takeIf { it.isNotBlank() },
                        smsBody = record.body
                    )
                )
                (insertedId > 0L) to (if (insertedId > 0L) insertedId else null)
            }

            SmsTeachingRule.ACTION_CARD_CREDIT -> {
                val amount = extracted.amount ?: return false to null
                val cardId = rule.cardId ?: return false to null
                val card = creditCardDao.getCardById(cardId) ?: return false to null
                cardPaymentDao.deleteBySmsBody(record.body)
                val insertedId = cardPaymentDao.insert(
                    CardPayment(
                        cardTail = card.cardLast4,
                        amountBdt = amount,
                        amountUsd = 0.0,
                        paidAt = extracted.date ?: record.timestamp,
                        smsBody = record.body
                    )
                )
                (insertedId > 0L) to (if (insertedId > 0L) insertedId else null)
            }

            SmsTeachingRule.ACTION_BILL -> {
                val amount = extracted.amount ?: return false to null
                val cardId = rule.cardId ?: return false to null
                creditCardDao.getCardById(cardId) ?: return false to null
                val whenMs = extracted.date ?: record.timestamp
                val dueDateStr = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(whenMs))
                val periodStr = SimpleDateFormat("MMMM yyyy", Locale.US).format(Date(whenMs))
                val billId = billRepository.addBill(
                    Bill(
                        cardId = cardId,
                        billPeriod = periodStr,
                        totalDue = amount,
                        minDue = amount,
                        dueDate = dueDateStr,
                        smsBody = record.body,
                        currency = "BDT",
                        detectedAt = record.timestamp
                    )
                )
                (billId > 0L) to (if (billId > 0L) billId else null)
            }

            SmsTeachingRule.ACTION_BILL_PAID -> {
                val amount = extracted.amount ?: return false to null
                val cardId = rule.cardId ?: return false to null
                val card = creditCardDao.getCardById(cardId) ?: return false to null
                val whenMs = extracted.date ?: record.timestamp
                val unpaid = billRepository.findActiveUnpaidBill(cardId, "BDT")
                if (unpaid != null) {
                    billRepository.markAsPaid(unpaid.id, whenMs)
                }
                cardPaymentDao.deleteBySmsBody(record.body)
                val insertedId = cardPaymentDao.insert(
                    CardPayment(
                        cardTail = card.cardLast4,
                        amountBdt = amount,
                        amountUsd = 0.0,
                        paidAt = whenMs,
                        smsBody = record.body
                    )
                )
                (insertedId > 0L) to (if (insertedId > 0L) insertedId else null)
            }

            SmsTeachingRule.ACTION_BALANCE_ONLY -> {
                true to null
            }

            else -> false to null
        }
    }

    companion object {
        object TemplateJson {
            private val gson = com.google.gson.Gson()

            fun encode(template: AccountTemplate): String = gson.toJson(template)

            fun decode(json: String?): AccountTemplate? {
                if (json.isNullOrBlank()) return null
                return try {
                    val parsed = gson.fromJson(json, AccountTemplate::class.java) ?: return null
                    if (parsed.name.isBlank()) null else parsed
                } catch (_: Exception) {
                    null
                }
            }
        }

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
            val formats = listOf(
                "dd/MM/yyyy",
                "dd-MM-yyyy",
                "dd/MM/yy",
                "dd-MM-yy",
                "yyyy-MM-dd"
            )

            return formats.firstNotNullOfOrNull { format ->
                try {
                    val sdf = java.text.SimpleDateFormat(format, java.util.Locale.getDefault())
                    sdf.parse(dateStr)?.time
                } catch (e: Exception) {
                    null
                }
            }
        }
        
        /**
         * Extracts a value from body using a custom regex pattern.
         * Expects a named capture group matching the field name, or falls back to first capture group.
         */
        private fun extractByPattern(body: String, pattern: String, fieldName: String): String? {
            return try {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                regex.find(body)?.let { match ->
                    // Try named capture group first
                    match.groups[fieldName]?.value ?: match.groupValues.getOrNull(1)
                }
            } catch (_: Exception) { null }
        }
        
        /**
         * Extracts fields from SMS body using rule-specific patterns if available,
         * otherwise falls back to generic extraction patterns.
         */
        fun extractFieldsWithPatterns(rule: SmsTeachingRule, body: String): ExtractedFields {
            // Try rule-specific patterns first
            val amount = rule.amountPattern?.let { extractByPattern(body, it, "amount")?.replace(",", "")?.toDoubleOrNull() }
                ?: extractAmount(body)
            
            val date = rule.datePattern?.let { extractByPattern(body, it, "date")?.let { parseDate(it) } }
                ?: extractAllFields(body).date
            
            val balance = rule.balancePattern?.let { extractByPattern(body, it, "balance")?.replace(",", "")?.toDoubleOrNull() }
                ?: extractAllFields(body).balance
            
            val accountMask = rule.accountMaskPattern?.let { extractByPattern(body, it, "accountMask") }
                ?: extractAllFields(body).accountMask
            
            val counterparty = rule.counterpartyPattern?.let { extractByPattern(body, it, "counterparty") }
                ?: extractAllFields(body).counterparty
            
            return ExtractedFields(amount, date, balance, accountMask, counterparty)
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
                    if (bestMatch.action != rule.action || bestMatch.accountId != rule.accountId || bestMatch.cardId != rule.cardId) {
                        return null
                    }
                }
            }
            return bestMatch
        }

        fun matchesRule(rule: SmsTeachingRule, sender: String?, normalizedBody: String): Boolean {
            val senderKey = rule.senderKey?.takeIf { it.isNotBlank() }
            val required = rule.requiredKeywordsList
            if (senderKey == null && required.isEmpty()) return false
            if (senderKey != null) {
                if (sender == null || !sender.contains(senderKey.uppercase())) return false
            }
            if (required.any { !normalizedBody.contains(it, ignoreCase = true) }) return false
            val excluded = rule.excludedKeywordsList
            if (excluded.any { normalizedBody.contains(it, ignoreCase = true) }) return false
            return true
        }

        private fun scoreRule(rule: SmsTeachingRule, sender: String?): Int {
            var score = 0
            if (rule.senderKey?.isNotBlank() == true && sender != null) score += 100
            // Use JSON-decoded keyword lists (Plan §3)
            val required = rule.requiredKeywordsList
            score += required.size * 10
            score += rule.priority
            return score
        }
    }
}
