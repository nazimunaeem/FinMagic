# FinMagic

**FinMagic** is an Android app that automatically reads your bank/credit-card SMS, builds a personal finance ledger, tracks credit card bills, and keeps you on top of due dates — all locally on your device.

> Parses SMS from Bangladeshi banks/wallets (bKash, Nagad, Rocket, Pubali, Prime Bank, City Bank, BRAC, EBL, Eastern Bank, and more) to give you an instant overview of your money without manual data entry.

---

## Features

### 📊 Accounts & Ledger
- **Automatic transactions** — SMS from supported banks/wallets is parsed into ledger entries (income, expense, transfer, ATM withdrawal).
- **Multi-account support** — Bank, wallet, and cash accounts with a combined total balance.
- **Manual entries** — Add cash expense/income with category and date directly.
- **Cash balance adjustments** — Correct your cash balance with or without a visible ledger entry.
- **Transfers** — Move money between accounts with automatic mirror entries.

### 💳 Credit Cards & Bills
- **Automatic bill creation** — Card statements from supported banks auto-generate bills with total due, min due, and due date.
- **Bill status tracking** — See unpaid/overdue/due-soon/upcoming/safe bills at a glance.
- **Partial payments** — Record partial payments and mark bills as paid.
- **Statement trend** — Monthly statement charts to spot spending patterns.

### ✉️ SMS Review & Teaching
- **Review queue** — Review unparsed or uncertain SMS.
- **Teach mode** — Tell the app what an SMS means (credit/debit/transfer/ATM/bill/ignore).
- **Smart suggestions** — Auto-suggests action based on SMS keywords with confidence scoring.
- **Learning rules** — Save rules so future SMS from the same sender are parsed automatically.

### 🧭 Navigation & Screens
- **Home** — Quick overview with history, accounts, and card dashboards.
- **Accounts** — Account list with balances + month history.
- **Credit Cards** — Dashboard + per-card view with bill status.
- **Expenses** — Month-by-month expense breakdown by category.
- **Stats** — Income/expense/net/savings with month navigation.
- **Settings** — Permissions, notifications, alerts, categories, data export.

### 🔔 Notifications
- **Due date reminders** — Daily notification for upcoming bill due dates.
- **Large debit alerts** — Alert when an account sees a debit over a threshold.
- **Rescan inbox** — Manually re-scan SMS inbox to catch missed transactions.

---

## Tech Stack
- **Kotlin** + **Jetpack Compose** (Material 3)
- **Room** database (local persistence)
- **Hilt** dependency injection
- **WorkManager** (background SMS scanning)
- **DataStore** Preferences (settings)
- **Android Gradle Plugin**

---

## Project Structure
```
FinMagic/
├── app/
│   ├── src/main/java/com/finmagic/
│   │   ├── data/          # Repositories, Room entities/DAOs, SMS parsing
│   │   ├── ui/            # Compose screens (home, accounts, cards, stats, settings, smsreview)
│   │   ├── notification/  # Reminder & rescan workers
│   │   └── MainActivity.kt
│   ├── src/test/          # Unit tests
│   └── build.gradle.kts
├── Analysis/              # Local analysis and sample SMS data (ignored by git)
├── gradle/                # Gradle wrapper
└── README.md
```

---

## Local Setup

1. **Open the project** in Android Studio.
2. Ensure the Android SDK is installed (see `local.properties`).
3. Build & run:

```bash
./gradlew assembleDebug
```

Or install directly to a connected device:

```bash
./gradlew :app:installDebug
```

---

## Supported Banks & Wallets

**Ledger accounts (transactions/balance):**
- bKash, Nagad, Rocket (wallet)
- Pubali Bank, Prime Bank, City Bank, BRAC Bank, Eastern Bank, EBL + more

**Credit card bills:**
- Prime Bank, Pubali Bank, City Bank (Amex), BRAC Bank, EBL, Eastern Bank

> SMS sender IDs and body keywords are used to identify the institution. See `app/src/main/java/com/finmagic/data/sms/` for the parsers.

---

## Testing

Run the unit test suite:

```bash
./gradlew testDebugUnitTest
```

Tests cover:
- Ledger/adjustment math (`AccountAdjustmentMathTest`)
- SMS classification (`SmsClassifierTest`)
- Trend calculations (`TrendHelperTest`)
- Bank parsers & bill logic

---

## Contributing: Adding a New Bank Parser

Only a subset of banks are supported so far. Adding support for another is welcome and touches only a few files.

Parsing lives under:

```
app/src/main/java/com/finmagic/data/sms/BankSmsParser.kt
```

Each bank is identified by its SMS sender ID (e.g. `PRIMEBANK`) or by keywords inside the body.

### Steps to add a bank

1. **Collect real sample SMS** from the target bank (anonymized — remove card numbers, names, and amounts).
2. **Add a bank identifier** enum in `BankSmsParser.kt`.
3. **Add a regex pattern** for the SMS format.
4. **Register sender IDs** in the sender-mapping table.
5. **Add a dispatch branch** in the `parse()` when-block.
6. **Write the parse function** following existing patterns.
7. **Add the bank to `FILTER_KEYWORDS`** in `SmsReader.kt` so its SMS isn't skipped.
8. **Add unit tests** in `app/src/test/java/com/finmagic/data/sms/`.

### Conventions

- Use `[\s\u00A0]*` in regexes for whitespace tolerance and always `RegexOption.IGNORE_CASE`.
- Route captured values through shared helpers:
  - `parseAmount(...)` — strips commas/currency → `Double`
  - `normalizeCurrency(...)` — canonical currency code
  - `normalizeDate(...)` — canonical `dd/MM/yyyy`
  - `normalizeBillPeriod(...)` — canonical `August 2026`
- Return `null` on no-match — never throw.
- Run tests before committing:

```bash
./gradlew testDebugUnitTest
```

---

## Security

This project must not be published with:
- Local machine paths from `local.properties`
- SMS exports containing OTPs, account numbers, or passwords
- API keys, tokens, or secret credentials
- Generated build directories

The repository already ignores `Analysis/`, `app/build/`, and `.gradle/`.

## ⚠️ Important
Do not commit or push personal SMS data, bank details, OTPs, credentials, or anything that could identify a real user.
