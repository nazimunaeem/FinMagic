# SMS Teaching System - Improvement Suggestions

**Analysis Date**: 2026-08-27  
**Based on**: `app_teaching_plan.md` implementation review

---

## Overview

The SMS teaching feature is fully implemented across all 5 phases. This document identifies gaps between the implementation and the plan specifications, plus opportunities for improvement.

---

## 🔴 High Priority Improvements

### 1. Fix Keyword Storage Format (Plan §3)

**Current Implementation**:
```kotlin
// SmsTeachingEngine.kt
val required = rule.requiredKeywords.split('|').map { it.trim() }
val excluded = rule.excludedKeywords.split('|').map { it.trim() }
```

**Plan Specification**:
> Use JSON adapters or a small serialization helper for keyword lists. Do not encode lists with ad hoc comma splitting because keywords may contain punctuation or spaces.

**Problem**: Keywords like "Rocket|Nagad" or "A/C#123" would break the pipe-delimited format.

**Recommended Fix**:
```kotlin
// Entity
val requiredKeywordsJson: String,  // JSON array: ["keyword1", "keyword2"]
val excludedKeywordsJson: String   // JSON array: ["exclude1"]

// Helper
object KeywordJson {
    private val gson = Gson()
    
    fun encode(keywords: List<String>): String =
        gson.toJson(keywords.distinct().filter { it.isNotBlank() })
    
    fun decode(json: String): List<String> =
        try { gson.fromJson(json, object : TypeToken<List<String>>() {}.type) ?: emptyList() }
        catch (_: Exception) { emptyList() }
}

// Usage in matchesRule()
val required = KeywordJson.decode(rule.requiredKeywordsJson)
val excluded = KeywordJson.decode(rule.excludedKeywordsJson)
```

**Files to Update**:
- `SmsTeachingRule.kt` - rename fields
- `SmsTeachingEngine.kt` - use JSON decoding
- `SmsReviewViewModel.kt` - use JSON encoding when saving
- `AppDatabase.kt` - add migration for column rename

---

### 2. Add Missing Transfer and Balance-Only Actions (Plan §2.3)

**Current Implementation**:
```kotlin
enum class TeachAction(val label: String) {
    CREDIT("Credit"),
    DEBIT("Debit"),
    ATM("ATM"),
    IGNORE("Ignore")
}
```

**Missing Actions**:
- `TRANSFER` - Transfer between accounts
- `BALANCE_ONLY` - Informational balance update

**Recommended Fix**:
```kotlin
enum class TeachAction(val label: String) {
    CREDIT("Credit"),
    DEBIT("Debit"),
    TRANSFER("Transfer"),
    ATM("ATM Withdrawal"),
    BALANCE_ONLY("Balance Update"),
    IGNORE("Ignore")
}

// TeachSmsSheet.kt - add callbacks
onTeachTransfer: (fromAccountId: Long, toAccountId: Long, amount: Double, occurredAt: Long, saveRule: Boolean, req: String, exc: String) -> Unit,
onTeachBalanceOnly: (accountId: Long, balance: Double, saveRule: Boolean, req: String, exc: String) -> Unit,

// ViewModel - add handlers
fun teachTransfer(record: SmsRecord, fromAccountId: Long, toAccountId: Long, amount: Double, occurredAt: Long, saveRule: Boolean, req: String, exc: String) { ... }
fun teachBalanceOnly(record: SmsRecord, accountId: Long, balance: Double, saveRule: Boolean, req: String, exc: String) { ... }
```

**UI Changes Needed**:
- Add "To Account" selector when TRANSFER is selected
- Show only balance field when BALANCE_ONLY is selected
- Update amount validation logic

---

### 3. Implement Pattern-Based Extraction (Plan §4.1)

**Current Implementation**:
```kotlin
// SmsTeachingRule has pattern fields but they're UNUSED
val amountPattern: String? = null,
val datePattern: String? = null,
val balancePattern: String? = null,
val accountMaskPattern: String? = null,
val counterpartyPattern: String? = null,
```

**Plan Specification**:
> When the user selects a value, generate a safe regex with captured groups.

**Recommended Fix**:
```kotlin
// In SmsTeachingEngine.applyRule()
private suspend fun applyRule(rule: SmsTeachingRule, record: SmsRecord): Boolean {
    // Use pattern-based extraction if patterns exist
    val amount = rule.amountPattern?.let { extractByPattern(record.body, it) }
        ?: extractAmount(record.body) ?: return false
    
    val date = rule.datePattern?.let { extractByPattern(record.body, it)?.toLong() }
        ?: record.timestamp
    
    val balance = rule.balancePattern?.let { extractByPattern(record.body, it)?.toDoubleOrNull() }
    
    // ... apply transaction with extracted values
}

private fun extractByPattern(body: String, pattern: String): String? {
    return try {
        Regex(pattern, RegexOption.IGNORE_CASE).find(body)?.value
    } catch (_: Exception) { null }
}

// In TeachSmsSheet - when user selects a value, offer to save pattern
if (saveRule && selectedAmountCandidate != null) {
    val pattern = SmsTeachingEngine.generatePattern(record.body, selectedAmountCandidate, "amount")
    // Store pattern in rule
}
```

---

### 4. Populate Transaction ID in Audit Trail (Plan §5)

**Current Implementation**:
```kotlin
// SmsRuleApplication is created with null transactionId
ruleApplicationDao.insert(
    SmsRuleApplication(
        ruleId = match.id,
        smsRecordId = record.id
        // transactionId is null!
    )
)
```

**Plan Specification**:
> Keep an audit trail of rule ID, SMS record ID, and resulting transaction.

**Recommended Fix**:
```kotlin
// AccountRepository should return transaction ID
suspend fun storeManualToAccount(...): Long? {  // Changed from Boolean
    // ...
    val txnId = txnDao.insert(transaction)
    return txnId
}

// SmsTeachingEngine.applyRule()
private suspend fun applyRule(rule: SmsTeachingRule, record: SmsRecord): Pair<Boolean, Long?> {
    return when (rule.action) {
        ACTION_CREDIT -> {
            val amount = extractAmount(record.body) ?: return false to null
            val txnId = accountRepository.storeManualToAccount(
                accountId = rule.accountId,
                // ...
            )
            (txnId != null) to txnId
        }
        // ...
    }
}

// applyFirstMatch() - capture and store transaction ID
val (applied, txnId) = applyRule(match, record)
if (applied) {
    ruleDao.incrementMatchCount(match.id)
    ruleApplicationDao.insert(
        SmsRuleApplication(
            ruleId = match.id,
            smsRecordId = record.id,
            transactionId = txnId  // Now populated!
        )
    )
}
```

---

## 🟡 Medium Priority Improvements

### 5. Implement Account Template Auto-Creation (Plan §4)

**Current Implementation**:
```kotlin
// Field exists but unused
val accountTemplateJson: String? = null,
```

**Plan Specification**:
> Assign the result to an existing account or create a new account.

**Recommended Fix**:
```kotlin
// Data class for template
@Serializable
data class AccountTemplate(
    val name: String,
    val kind: String,  // BANK, WALLET, CASH
    val bankName: String = "",
    val accountMask: String = "",
    val currency: String = "BDT",
    val includeInTotal: Boolean = true
)

// In applyRule()
val account = accountDao.findByIdOnce(rule.accountId)
    ?: rule.accountTemplateJson?.let { createFromTemplate(it) }

private suspend fun createFromTemplate(templateJson: String): Account? {
    val template = Json.decodeFromString<AccountTemplate>(templateJson)
    val account = Account(
        name = template.name,
        kind = template.kind,
        bankName = template.bankName,
        accountMask = template.accountMask,
        currency = template.currency,
        includeInTotal = template.includeInTotal
    )
    accountDao.insert(account)
    return accountDao.findByInstitutionAndMask(template.bankName, template.accountMask)
}

// In TeachSmsSheet - when creating account during teaching, save as template
if (saveRule && createdAccountId != null) {
    val template = AccountTemplate(name, kind, bankName, mask, currency, includeInTotal)
    rule.accountTemplateJson = Json.encodeToString(template)
}
```

---

### 6. Improve Conflict Detection (Plan §3.2)

**Current Implementation**:
```kotlin
// Only detects exact same score with different action/account
if (score == bestScore && bestMatch != null) {
    if (bestMatch.action != rule.action || bestMatch.accountId != rule.accountId) {
        return null  // Conflict
    }
}
```

**Additional Conflicts to Detect**:
1. Sender-only rule vs sender+keyword rule with same sender
2. Overlapping keyword sets that could match same message differently
3. Rule that would match every SMS from a sender

**Recommended Fix**:
```kotlin
fun findBestMatch(
    rules: List<SmsTeachingRule>,
    sender: String?,
    normalizedBody: String
): SmsTeachingRule? {
    val candidates = rules.filter { matchesRule(it, sender, normalizedBody) }
    
    // Check for dangerous broad rules (Plan §3.2)
    val broadRules = candidates.filter { 
        it.senderKey.isNullOrBlank() && it.requiredKeywordsJson == "[]"
    }
    if (broadRules.isNotEmpty()) {
        // Require explicit confirmation before applying body-only rules
        return null  // Or return special marker
    }
    
    // Score and find best
    val scored = candidates.map { it to scoreRule(it, sender) }
        .sortedByDescending { it.second }
    
    if (scored.isEmpty()) return null
    
    val best = scored.first()
    val sameScoreCount = scored.count { it.second == best.second }
    
    if (sameScoreCount > 1) {
        // Multiple rules with same top score - check for conflict
        val conflicting = scored.filter { 
            it.second == best.second && 
            (it.first.action != best.first.action || it.first.accountId != best.first.accountId)
        }
        if (conflicting.isNotEmpty()) {
            return null  // NEEDS_REVIEW
        }
    }
    
    return best.first
}
```

---

### 7. Add Rule Testing from SMS List

**Current Implementation**:
Rule testing only available from Rules tab via search icon.

**Recommended Addition**:
Long-press on SMS in "All" or "Unparsed" tab should show:
- "Test Rules" - opens RuleTestScreen with this SMS pre-filled
- "Copy Body" - for debugging

**Implementation**:
```kotlin
// SmsReviewScreen.kt - add context menu
var showContextMenu by remember { mutableStateOf<SmsRecord?>(null) }

AllSmsItem(
    record = record,
    onTap = { viewModel.selectRecord(record) },
    onLongPress = { showContextMenu = record },
    onIgnore = { viewModel.ignoreSms(record) }
)

// Context menu
showContextMenu?.let { record ->
    DropdownMenu(
        expanded = true,
        onDismissRequest = { showContextMenu = null }
    ) {
        DropdownMenuItem(
            text = { Text("Test Rules") },
            onClick = {
                showRuleTest = true
                testSmsBody = record.body
                testSmsSender = record.address ?: ""
                showContextMenu = null
            }
        )
        DropdownMenuItem(
            text = { Text("Copy Body") },
            onClick = {
                clipboardManager.setText(AnnotatedString(record.body))
                showContextMenu = null
            }
        )
    }
}
```

---

### 8. Fix Historical Application Deduplication Safety

**Current Implementation**:
```kotlin
// SmsReviewViewModel.applyRuleToHistorical()
val count = smsTeachingEngine.applyRuleToHistorical(rule, unparsedList)
if (count > 0) {
    unparsedList.forEach { record ->
        // ... check if matches
        smsRecordDao.updateParsedStatus(record.id, true)  // Always marks parsed!
    }
}
```

**Problem**: If `applyRuleToHistorical` partially succeeds (some transactions created, some fail due to duplicates), all matching SMS are still marked as parsed.

**Recommended Fix**:
```kotlin
// SmsTeachingEngine.applyRuleToHistorical() should return list of processed IDs
suspend fun applyRuleToHistorical(
    rule: SmsTeachingRule, 
    unparsedRecords: List<SmsRecord>
): List<Long> {  // Return SMS IDs that were actually processed
    val processedIds = mutableListOf<Long>()
    
    for (record in unparsedRecords) {
        val normalizedBody = normalizeText(record.body)
        val sender = record.address?.trim()?.uppercase()
        
        if (matchesRule(rule, sender, normalizedBody)) {
            val success = applyRule(rule, record)
            if (success) {
                ruleDao.incrementMatchCount(rule.id)
                ruleApplicationDao.insert(...)
                processedIds.add(record.id)  // Only add if successful
            }
        }
    }
    
    return processedIds
}

// ViewModel
fun applyRuleToHistorical(rule: SmsTeachingRule) {
    viewModelScope.launch {
        val processedIds = smsTeachingEngine.applyRuleToHistorical(rule, unparsedSms.value)
        
        // Only mark actually processed SMS as parsed
        processedIds.forEach { smsId ->
            smsRecordDao.updateParsedStatus(smsId, true)
        }
        
        _message.value = "Rule applied to ${processedIds.size} SMS"
    }
}
```

---

## 🟢 Low Priority / Polish

### 9. Add Comprehensive Test Coverage (Plan §9)

**Missing Tests**:

#### Unit Tests
```kotlin
// SmsTeachingEngineTest.kt
@Test fun testScoreRule_senderOnlyVsKeywords()
@Test fun testScoreRule_priorityBonus()
@Test fun testFindBestMatch_conflictDetection()
@Test fun testGeneratePattern_escapesLiterals()
@Test fun testNormalizeText_handlesNonBreakingSpace()
@Test fun testMatchesRule_caseInsensitive()
@Test fun testMatchesRule_excludedKeywords()

// KeywordJsonTest.kt (new)
@Test fun testEncodeDecode_handlesPunctuation()
@Test fun testEncodeDecode_handlesEmptyList()
@Test fun testDecode_handlesMalformedJson()
```

#### Corpus Tests
```kotlin
// SmsTeachingCorpusTest.kt
@Test fun testSharedSender_16216_dbblVsRocket()
@Test fun testRocket_withoutRocketKeyword()
@Test fun testNewAccount_maskedNumber()
@Test fun testBalanceOnly_message()
```

#### Repository Tests
```kotlin
// SmsTeachingRepositoryTest.kt
@Test fun testUserRuleCredit_createsOneTransaction()
@Test fun testUserRuleDebit_createsOneTransaction()
@Test fun testUserRuleAtm_createsTransferPair()
@Test fun testApplyRuleTwice_isIdempotent()
@Test fun testHistoricalApplication_onlyAffectsMatching()
```

---

### 10. Improve Date Parsing

**Current Implementation**:
```kotlin
// TeachSmsSheet uses limited formats
val fmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
```

**Recommendation**: Reuse existing `SmsDateParser` which handles more formats:
```kotlin
// TeachSmsSheet.kt
private fun extractCandidates(smsTimestamp: Long, smsBody: String): SmsCandidates {
    // ...
    // Use SmsDateParser instead of manual parsing
    val parsedDate = SmsDateParser.parse(body)?.time
    if (parsedDate != null) dates.add("Detected" to parsedDate)
    dates.add("SMS time" to smsTimestamp)
    // ...
}
```

---

### 11. Implement Rule Import/Export (Plan §12)

**Plan Question**:
> Should user rules be exportable as a local JSON backup?

**Recommended Implementation**:
```kotlin
// RuleExportImport.kt
object RuleExportImport {
    fun exportRules(rules: List<SmsTeachingRule>): String {
        return Json { prettyPrint = true }.encodeToString(rules)
    }
    
    fun importRules(json: String): List<SmsTeachingRule> {
        return try {
            Json.decodeFromString<List<SmsTeachingRule>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// UI - in RulesList
Row {
    TextButton(onClick = { viewModel.exportRules() }) {
        Text("Export")
    }
    TextButton(onClick = { showImportDialog = true }) {
        Text("Import")
    }
}
```

---

### 12. Notification for Unparsed SMS (Plan §2.1)

**Plan Specification**:
> Show a non-blocking notification or an in-app count.

**Current**: Only visible when user opens SMS Review screen.

**Recommended Addition**:
```kotlin
// SmsScanWorker - after scan completes
val unparsedCount = smsRecordDao.getUnparsedSmsCount()
if (unparsedCount > 0) {
    NotificationHelper.showUnparsedSmsNotification(
        context = applicationContext,
        count = unparsedCount
    )
}

// NotificationHelper.kt
fun showUnparsedSmsNotification(context: Context, count: Int) {
    val intent = Intent(context, MainActivity::class.java).apply {
        putExtra("open_sms_review", true)
    }
    
    NotificationCompat.Builder(context, CHANNEL_UNPARSED)
        .setSmallIcon(R.drawable.ic_message)
        .setContentTitle("$count unparsed SMS")
        .setContentText("Tap to teach the app how to handle these messages")
        .setContentIntent(PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        .setAutoCancel(true)
        .build()
        .let { NotificationManagerCompat.from(context).notify(NOTIF_UNPARSED, it) }
}
```

---

### 13. Balance Field Validation

**Current**: Balance is optional with no validation.

**Recommended**: Validate balance makes sense relative to transaction:
```kotlin
// TeachSmsSheet validation
val balanceValid = when {
    balanceText.isBlank() -> true
    amount == null -> true
    // For debit, balance should decrease; for credit, increase
    selectedAction == TeachAction.DEBIT -> (balanceAfter ?: 0) < (previousBalance ?: Double.MAX_VALUE)
    selectedAction == TeachAction.CREDIT -> (balanceAfter ?: 0) > (previousBalance ?: 0)
    else -> true
}

// Show warning if balance seems inconsistent
if (!balanceValid) {
    Text(
        "⚠️ Balance doesn't match expected change",
        color = MaterialTheme.colorScheme.error
    )
}
```

---

### 14. Transfer Account Selection UI

**Current**: Only one account can be selected.

**For TRANSFER Action**:
```kotlin
// TeachSmsSheet - when TRANSFER selected
if (selectedAction == TeachAction.TRANSFER) {
    Text("From Account", style = MaterialTheme.typography.labelLarge)
    // Account selector for fromAccountId
    
    Text("To Account", style = MaterialTheme.typography.labelLarge)
    // Account selector for toAccountId
    
    // Common transfer pairs as quick-select
    Text("Common Transfers:", style = MaterialTheme.typography.bodySmall)
    Row {
        AssistChip(onClick = { fromAccount = bank; toAccount = cash }, label = { Text("Bank → Cash") })
        AssistChip(onClick = { fromAccount = wallet; toAccount = bank }, label = { Text("Wallet → Bank") })
    }
}
```

---

## Summary Table

| # | Improvement | Priority | Effort | Impact |
|---|-------------|----------|--------|--------|
| 1 | Fix keyword storage to use JSON | High | 2h | High |
| 2 | Add Transfer and Balance actions | High | 3h | High |
| 3 | Implement pattern-based extraction | High | 4h | High |
| 4 | Populate transaction ID in audit | High | 1h | Medium |
| 5 | Account template auto-creation | Medium | 3h | High |
| 6 | Improve conflict detection | Medium | 2h | Medium |
| 7 | Rule testing from SMS list | Medium | 1h | Low |
| 8 | Fix historical app dedup safety | Medium | 1h | Medium |
| 9 | Add comprehensive tests | Low | 8h | High |
| 10 | Improve date parsing | Low | 30m | Low |
| 11 | Rule import/export | Low | 2h | Medium |
| 12 | Unparsed SMS notification | Low | 1h | Medium |
| 13 | Balance field validation | Low | 30m | Low |
| 14 | Transfer account selection UI | Low | 1h | Medium |

---

## Quick Wins (< 1 hour each)

1. ✅ Populate `transactionId` in `SmsRuleApplication`
2. ✅ Use `SmsDateParser` in `extractAllFields`
3. ✅ Fix historical application to only mark parsed on success
4. ✅ Add balance field validation warning

---

## Recommended Implementation Order

1. **Week 1**: Items 1-4 (High Priority fixes)
2. **Week 2**: Items 5-8 (Medium Priority)
3. **Week 3**: Item 9 (Tests) + Items 11-12 (Export/Notifications)
4. **Week 4**: Items 10, 13-14 (Polish)

---

## Files Requiring Changes

```
app/src/main/java/com/finmagic/
├── data/
│   ├── local/
│   │   ├── entity/SmsTeachingRule.kt          # Rename keyword fields
│   │   ├── dao/SmsTeachingRuleDao.kt          # Add JSON helpers
│   │   └── AppDatabase.kt                      # Migration
│   ├── repository/AccountRepository.kt         # Return transaction ID
│   └── sms/
│       ├── SmsTeachingEngine.kt               # Pattern matching, JSON keywords
│       └── SmsScanWorker.kt                    # Unparsed notification
└── ui/
    ├── smsreview/
    │   ├── TeachSmsSheet.kt                   # Transfer/Balance actions
    │   ├── SmsReviewViewModel.kt              # Transfer/Balance handlers
    │   ├── SmsReviewScreen.kt                 # Context menu, test integration
    │   └── RuleTestScreen.kt                  # Pre-fill from context menu
    └── notification/NotificationHelper.kt      # Unparsed SMS notification
```

---

## Notes

- All changes should maintain backward compatibility with existing rules
- Migration should convert pipe-delimited keywords to JSON format
- Consider adding telemetry to track which improvements are most used
- Document new actions in user-facing help text
