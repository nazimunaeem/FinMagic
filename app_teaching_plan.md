# FinMagic - In-App SMS Teaching Plan

This plan adds a user-assisted SMS recognizer for messages that do not match the built-in parsers. Users can teach the app whether a message is a credit, debit, transfer, cash withdrawal, or ignored message; which account owns it; how to extract its values; and whether a new account should be created.

The feature must remain local-first. User rules are stored as data and interpreted by the app. Users must never enter executable code or Kotlin expressions.

---

## 0. Goals and non-goals

### Goals

- Detect SMS messages that fall through the built-in classifier.
- Let a user classify the current SMS with a guided form.
- Extract amount, date, balance, account mask, and counterparty values.
- Assign the result to an existing account or create a new account.
- Save a reusable rule for future matching messages.
- Apply a saved rule to matching historical SMS messages after confirmation.
- Make rules editable, disableable, explainable, and reversible.
- Prevent ambiguous rules from silently creating incorrect financial records.

### Non-goals for the first release

- Arbitrary user-written scripts.
- Remote rule downloads or cloud synchronization.
- Automatic deletion of original SMS bodies.
- Fully automatic learning without user confirmation.
- Replacing the existing built-in parsers.

### Parsed but incorrect

An SMS can be syntactically parsed but still produce the wrong result, for example:

- A BRAC card purchase classified as a Rocket wallet transaction.
- A DBBL message assigned to the wrong account because the sender is shared.
- A debit interpreted as a credit.
- The correct amount extracted from the wrong number in the message.
- A transaction assigned to the wrong existing account.

This must be treated as a separate correction workflow, not as an unparsed-message case.

---

## 1. Current architecture integration

The existing pipeline is:

```text
SmsReader keyword filter
    -> SmsClassifier
        -> built-in bill/payment/card/account/wallet parsers
        -> Noise when no parser matches
    -> SmsScanWorker persistence
```

Add the teaching layer after built-in parsers and before the final Noise result:

```text
built-in parsers
    -> user-defined rules
    -> Unparsed SMS review queue
    -> Noise / ignored
```

Recommended classification result additions:

```text
USER_RULE_TXN       -> persist using the same account repository paths
USER_RULE_IGNORE    -> mark handled and do not create a transaction
NEEDS_REVIEW        -> retain locally until the user decides
```

Built-in parsers must keep precedence over user rules. This protects existing behavior and prevents a broad user rule from overriding a precise bank parser.

---

## 2. User workflow

### 2.1 Unparsed message notification

Status: ✅ implemented.

When an SMS is not parsed and is not confidently identified as noise:

- Add it to an unparsed/review queue.
- Show a non-blocking notification or an in-app count.
- Do not create a transaction yet.
- Do not repeatedly prompt for the same SMS.

The review item should show:

- Sender.
- Received time.
- Short redacted preview.
- Reason: `No built-in parser matched`.
- Actions: `Teach`, `Ignore once`, `Ignore similar`.

### 2.2 Incorrectly parsed message

Every automatically created transaction should provide a `Report incorrect` or `Correct` action in its detail view. The correction screen should show:

- Original SMS body and sender.
- Current classification and account.
- Current extracted amount, date, balance, direction, category, and counterparty.
- A replacement action and account.
- Which extracted values should be changed.
- Whether the correction applies once or becomes a reusable rule.

The user must be able to choose:

- `Correct this transaction only`.
- `Correct this and matching future SMS`.
- `Correct this and repair matching historical SMS`.
- `Mark as ignore/noise`.

For a one-time correction, update or replace the existing transaction without creating a duplicate. Preserve the original classification and correction reason in audit metadata.

For a reusable correction, create an override rule with higher precedence than the built-in parser only for the selected sender/body signature. Do not allow a broad override that changes all messages from a sender without an explicit confirmation.

When the correction changes accounts, reverse the original transfer or mirror transaction through the repository transaction boundary before creating the corrected record. When the original transaction affected a credit-card bill, reverse the payment application before applying the corrected result.

The correction flow must show a before/after preview and the number of historical messages that would change. Historical repair requires explicit confirmation.

### 2.3 Teach current SMS

Status: ✅ implemented (credit/debit/ATM/ignore with date selection, balance selection, counterparty, category, reusable rules, and account creation).

The teaching screen should guide the user through a preview-first form:

1. Choose action:
   - Credit / money in.
   - Debit / money out.
   - Transfer between accounts.
   - ATM cash withdrawal.
   - Informational balance update.
   - Ignore.
2. Choose or create the account.
3. Select the amount from detected candidates, or enter it manually.
4. Select the date and time from detected candidates, or use SMS time.
5. Select the reported balance if present.
6. Confirm counterparty and category.
7. Choose rule scope:
   - Apply once.
   - Save for future messages from this sender.
   - Save for future messages matching selected keywords.
8. Preview the resulting transaction and confirm.

The form should display the original text with matched portions highlighted. Sensitive values should be masked in surrounding UI where possible, while keeping enough text for the user to verify the match.

### 2.4 Create an account during teaching

The account selector should include `Create account`.

Required fields:

- Display name.
- Account type: bank, wallet, or cash.
- Institution/name.
- Account mask or identifier when available.
- Currency.
- Include in total balance toggle.

The new account should be created only when the final transaction is confirmed. The rule should reference the created account identity, not only its display name.

### 2.5 Historical application

After saving a rule, show the number of historical SMS messages that match it. The user can choose:

- Current SMS only.
- Current SMS plus selected historical messages.
- All matching historical messages after preview.

Every historical application must use the normal deduplication and repository transaction paths.

---

## 3. Rule model

Add a Room table, for example `sms_teaching_rules`:

```kotlin
@Entity(
    tableName = "sms_teaching_rules",
    indices = [
        Index("enabled"),
        Index("senderKey"),
        Index("createdAt")
    ]
)
data class SmsTeachingRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val senderKey: String?,
    val requiredKeywordsJson: String,
    val excludedKeywordsJson: String,
    val amountPattern: String?,
    val datePattern: String?,
    val balancePattern: String?,
    val accountMaskPattern: String?,
    val counterpartyPattern: String?,
    val action: String,
    val accountId: Long?,
    val accountTemplateJson: String?,
    val category: String?,
    val enabled: Boolean = true,
    val applyToHistorical: Boolean = false,
    val priority: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
```

Use canonical constants for actions:

```text
CREDIT
DEBIT
TRANSFER
ATM_WITHDRAWAL
BALANCE_ONLY
IGNORE
```

Use JSON adapters or a small serialization helper for keyword lists. Do not encode lists with ad hoc comma splitting because keywords may contain punctuation or spaces.

### 3.1 Rule matching

A rule should match only when all required conditions pass:

- Sender matches, when configured.
- Every required keyword is present, case-insensitively.
- No excluded keyword is present.
- All configured extraction patterns match.
- The resulting amount is valid and greater than zero for movement actions.
- The selected account exists, or the account template is valid for creation.

Normalize text before matching:

- Replace non-breaking spaces.
- Collapse repeated whitespace.
- Use case-insensitive matching.
- Keep the original body for persistence and audit details.

### 3.2 Rule priority and conflicts

Evaluate rules in this order:

1. Enabled correction overrides with exact sender and the most specific body signature.
2. Enabled teaching rules with exact sender and the most required keywords.
3. Enabled rules with exact sender.
4. Enabled body-only rules.
5. Built-in parser fallback is not re-run here.

If two rules have the same effective specificity and produce different actions/accounts:

- Return `NEEDS_REVIEW`.
- Show the conflicting rule names.
- Do not post a transaction automatically.

A rule should not be allowed to match every SMS from a sender unless the user explicitly confirms a sender-only rule.

---

## 4. Extraction model

The teaching UI should identify candidate values from the SMS using generic helpers before asking the user to select one.

### Amount candidates

Recognize common currency prefixes and separators already supported by `LedgerText`:

```text
Tk 1,250.00
BDT2,000.00
TK. 200.00
Amount: 500
```

Exclude obvious non-transaction values such as OTPs, dates, card tails, and account masks.

### Date and time candidates

Reuse `SmsDateParser` for known formats and allow the user to select the SMS received time as fallback.

### Balance candidates

Recognize nearby labels such as:

```text
Balance
Available balance
Current Balance
C/B
A/C Bal
```

### Account mask candidates

Recognize masked account forms without treating card tails as bank accounts:

```text
A/C ***3657
A/C#10551**0001
Account 1**88
```

### Counterparty candidates

Use the text between known action words and delimiters as a suggestion only. The user must be able to edit it.

### Pattern generation

When the user selects a value, generate a safe regex with captured groups. Escape all literal text and replace only the selected span with a named capture:

```text
Tk 1,250.00 debited from A/C ***1234
```

becomes conceptually:

```text
Tk\\s+(?<amount>[\\d,]+(?:\\.\\d+)?)\\s+debited\\s+from\\s+A/C\\s+\\*+1234
```

Generated expressions must be validated before saving. Limit pattern length and reject unsafe constructs such as lookbehind loops, backreferences, or nested quantifier patterns that could cause excessive matching time.

---

## 5. Persistence behavior

User-rule transactions must use existing domain paths:

- Bank/account credit or debit -> `AccountRepository.storeSingle`.
- ATM withdrawal -> `AccountRepository.storeAtmWithdraw`.
- Wallet-to-cash movement -> `AccountRepository.storeTransferPair`.
- Ignore -> store only a handled/review state.

Add metadata to the persisted transaction where useful:

- `sourceType = BUILTIN | USER_RULE | MANUAL`.
- `teachingRuleId` nullable.
- `ruleVersion` or rule updated timestamp.

If changing the schema is too costly for the first increment, keep rule provenance in a separate `sms_rule_applications` table keyed by SMS record and transaction ID.

Existing deduplication must remain authoritative. Applying a rule twice must not duplicate a transaction.

---

## 6. Review and rule management UI

Add a Settings section named `SMS Recognition` with three views:

### Unparsed

- Pending messages.
- Sender and date.
- Match preview.
- Teach, ignore once, ignore similar.

### Learned rules

- Enabled/disabled switch.
- Rule name.
- Sender and keyword summary.
- Action and account.
- Last matched time.
- Match count.
- Edit, test, disable, delete.

### Rule test

Allow users to paste or select a local SMS and show:

- Matching rule.
- Extracted amount/date/balance.
- Target account.
- Proposed transaction.
- Conflict warnings.

Do not display instructional feature text inside normal transaction screens. Keep explanations in the teaching and rule-management surfaces where the user is actively configuring behavior.

---

## 7. Database and migration plan

1. Add `SmsTeachingRule` and DAO.
2. Add an unparsed/review entity and DAO, or extend `SmsRecord` if it already represents the required lifecycle.
3. Add rule provenance storage.
4. Add a Room migration from the current schema version.
5. Keep destructive migration disabled for this feature.
6. On downgrade or malformed rule data, disable the rule and preserve the original SMS for review.

Migration must not modify existing bills, account transactions, or SMS records except for adding nullable metadata columns.

---

## 8. Security, privacy, and safety

- Store rules and SMS data locally only.
- Never execute user-provided code.
- Do not log full SMS bodies or extracted account numbers in normal logs.
- Mask account/card values in notifications and rule lists.
- Require explicit confirmation before historical bulk application.
- Show a preview before every first-time rule application.
- Keep an audit trail of rule ID, SMS record ID, and resulting transaction.
- Add a `Disable all learned rules` action in Settings.
- Allow deletion of learned rules without deleting already-created transactions.

---

## 9. Testing strategy

### Unit tests

- Required and excluded keyword matching.
- Sender normalization.
- Whitespace and non-breaking-space normalization.
- Amount/date/balance extraction.
- Invalid or missing amount behavior.
- Rule priority.
- Parsed-but-wrong correction and override precedence.
- Reversing an incorrectly posted transaction without duplication.
- Account changes for transfer pairs.
- Reversing and reapplying credit-card bill payments.
- Conflict detection.
- Regex validation and limits.
- Account-template validation.
- Ignore rules.
- Duplicate application behavior.

### Corpus tests

Add anonymized samples for messages that currently fall through, especially:

- Shared sender IDs such as `16216`.
- Rocket wallet messages without the word `Rocket`.
- Bank messages with inconsistent currency prefixes.
- New account creation from a masked account number.
- Balance-only messages.

### Repository tests

- A user-rule credit creates one account transaction.
- A user-rule debit creates one account transaction.
- ATM withdrawal creates the bank-to-cash pair.
- Transfer rules create both sides exactly once.
- Applying a rule again is idempotent.
- Historical application does not affect non-matching SMS.

### UI tests

- Teach screen shows extracted candidates.
- Date and amount can be edited.
- Existing account selection works.
- New account creation works.
- Conflicting rules require review.
- Disabled rules do not classify messages.

---

## 10. Delivery phases

### Phase 1 - Manual teaching, apply once

Status: ✅ implemented.

- ✅ Review queue for unparsed SMS.
- ✅ Guided action/account/amount form with date selection, transfer, ATM withdrawal, and ignore.
- ✅ Dedicated SMS Recognition screen (Settings → SMS Review).
- ✅ Before/after preview via modal bottom sheet.
- ✅ Persist the current transaction through the existing account repository.
- ✅ Date picker integration.
- ✅ Counterparty and category fields in teaching form.
- ✅ "Ignore Similar" action to auto-ignore future SMS from a sender.
- ✅ Account creation during teaching (Phase 4).

Also include correction of an existing parsed transaction for direction, amount, account, category, and ignore/noise. Corrections should be one-time only in this phase. (✅ implemented via `CorrectTransactionDialog`).

### Phase 2 - Reusable keyword rules

Status: ✅ implemented.

- ✅ Room rule entity and DAO (extended with name, category, priority, matchCount, lastMatchedAt, updatedAt).
- ✅ Sender, required keywords, excluded keywords, action, account.
- ✅ Apply rules before returning Noise (integrated into SmsTeachingEngine).
- ✅ Rule enable/disable controls (toggle in Rules tab).
- ✅ Rule delete with confirmation.
- ✅ Disable all rules action.
- ✅ Priority-based rule matching with specificity scoring.
- ✅ Conflict detection (returns NEEDS_REVIEW when two rules have same specificity but different actions).
- ✅ DB migration 13→14 with sms_rule_applications audit table.
- ✅ Unit tests for rule matching, amount extraction, and text normalization.

### Phase 3 - Field extraction

Status: ✅ implemented.

- ✅ Generated amount/date/balance/account/counterparty patterns (`SmsTeachingEngine.extractAllFields`, `generatePattern`).
- ✅ Highlighted match preview (`TeachSmsSheet` extracted fields banner).
- ✅ Rule test screen (`RuleTestScreen`).
- ✅ Invalid-pattern safeguards (length and pattern validation in `generatePattern`).

### Phase 4 - Account creation and historical application

Status: ✅ implemented.

- ✅ Create account from teaching flow (`CreateAccountDialog` with callback selecting new account ID).
- ✅ Historical match count and preview (`RuleItem` match count & status).
- ✅ Bulk application to historical unparsed SMS (`SmsTeachingEngine.applyRuleToHistorical`, `onApplyHistorical`).
- ✅ Provenance and audit records (`SmsRuleApplication` table & DAO).

### Phase 5 - Conflict handling and polish

Status: ✅ implemented.

- ✅ Specificity scoring (`SmsTeachingEngine.scoreRule`).
- ✅ Conflicting-rule review (returns `NEEDS_REVIEW` / null on tied scores with differing actions/accounts).
- ✅ Rule analytics and last-match display (`matchCount`, `lastMatchedAt`).
- ✅ Privacy controls and disable-all action.
- ✅ Scrollable dialogs and large-font testing.

---

## 11. Recommended first implementation slice

Start with Phase 1 and one narrow Phase 2 slice:

1. ✅ Add an `Unparsed SMS` review state.
2. ✅ Add `Teach` from the SMS review screen (dedicated screen, not buried in Debug).
3. ✅ Add `Correct` from an automatically created transaction.
4. ✅ Support credit/debit/ignore and account correction; current implementation supports credit/debit/ATM/ignore for unparsed SMS.
5. ✅ Support amount selection, date selection, account selection, and account creation.
6. ✅ Apply once through `AccountRepository` without duplication.
7. ✅ Save only sender plus required/excluded keywords for future use (with priority-based matching).
8. ✅ Add regression coverage for a shared-sender Rocket message and DBBL NexusPay message.

This gives users immediate control without introducing generated regexes, historical bulk operations, or account creation until the core review workflow is proven.

---

## 12. Open decisions

- Should unparsed messages be retained indefinitely, or follow the existing SMS retention period?
- Should user rules be exportable as a local JSON backup?
- Should rules be shared between app reinstalls through Android backup?
- Should account creation from a rule require an account mask, or permit a wallet with no mask?
- Should a rule that matches a card purchase create an account transaction, a card transaction, or let the user choose?
- How should user rules behave when a built-in parser is added later and becomes more specific?
- Should rules created on one SIM be scoped to that subscription ID?

---

## 13. Success criteria

The feature is ready for an initial release when:

- A user can teach an unparsed SMS in under one minute.
- The preview matches the intended amount, date, direction, and account.
- Reapplying a rule never duplicates a transaction.
- Ambiguous rules never silently post financial data.
- A user can disable or delete a learned rule.
- Current built-in parser tests remain green.
- Corpus tests cover at least one shared-sender DBBL/Rocket pair.
