package com.finmagic.data.sms.parsers

enum class AccountKind { BANK, WALLET, CASH }

enum class LedgerDirection { IN, OUT }

/** Auto-assigned categories for ledger rows (plan §2.2). */
object TxnCategory {
    const val ATM = "ATM"
    const val CASH_DEPOSIT = "Cash-Deposit"
    const val INTEREST = "Interest"
    const val DPS = "DPS"
    const val FUND_TRANSFER = "Fund-Transfer"
    const val CARD_PAYMENT = "Card-Payment"
    const val CASH_IN = "Cash-In"
    const val PAYMENT = "Payment"
    const val RECEIVED = "Received"
    const val BILL_PAY = "Bill-Pay"
    const val MANUAL = "Manual"
    const val ADJUSTMENT = "Adjustment"
    const val CREDIT = "Credit"
    const val NPSB = "NPSB"
    /** Zero-amount row that only refreshes the account's reported balance (plan §4). */
    const val STATEMENT = "Statement"
    /** Hidden balance-override row ("Set balance directly") — never a real spend. */
    const val DIRECT_SET = "DirectSet"

    /**
     * Categories that are money movement, not spending. Keep in sync with the
     * NOT IN list in AccountTransactionDao.getExpensesBetweenOnce.
     */
    val NON_SPENDING = setOf(FUND_TRANSFER, ATM, ADJUSTMENT, NPSB, "RD-Installment", DIRECT_SET)

    /** Auto rule: an OUT row in a spending category counts as an expense. */
    fun isAutoExpense(direction: String?, category: String?): Boolean =
        direction == "OUT" && (category == null || category !in NON_SPENDING)

    /** Does this row currently appear in the Expenses tab (override-aware)? */
    fun countsAsExpense(override: Int, direction: String?, category: String?): Boolean =
        override == 1 || (override != -1 && isAutoExpense(direction, category))

    /** Target override value when the user toggles the Expense checkbox to [include]. */
    fun targetOverride(include: Boolean, direction: String?, category: String?): Int =
        when {
            include -> 1
            isAutoExpense(direction, category) -> -1 // force-exclude an auto expense
            else -> 0                                // back to auto
        }
}

/**
 * One money movement on one account, extracted from an SMS.
 * Account identity = (institution, accountMask) per plan §8.
 * [subscriptionId] identifies the SIM that received the SMS; wallet entries use
 * it so one institution maps to at most one account per SIM (SIM1/SIM2).
 */
data class LedgerEntry(
    val institution: String,
    val kind: AccountKind,
    val accountMask: String,
    val direction: LedgerDirection,
    val amount: Double,
    val balanceAfter: Double? = null,
    val occurredAt: Long? = null,
    val category: String? = null,
    val counterparty: String? = null,
    val subscriptionId: Int? = null
)

/** Classification results produced by [com.finmagic.data.sms.SmsClassifier]. */
sealed class SmsClassification {
    data class BillStatement(val bankSms: com.finmagic.data.sms.ParsedBill) : SmsClassification()
    data class CardPayment(val payment: com.finmagic.data.sms.ParsedPayment) : SmsClassification()

    /** Credit/debit card POS purchase (stored as a CardTransaction). */
    data class CardTxn(
        val cardTail: String,
        val amount: Double,
        val merchant: String?,
        val occurredAt: Long?,
        val balanceAfter: Double? = null,
        val bankName: String? = null
    ) : SmsClassification()

    /** Plain credit/debit on a savings/current account. */
    data class AcctTxn(val entry: LedgerEntry) : SmsClassification()

    /** ATM cash withdrawal → becomes Bank→Cash transfer (Feature C). */
    data class AtmWithdraw(val entry: LedgerEntry) : SmsClassification()

    /** bKash/Nagad/Rocket wallet movement. */
    data class WalletTxn(val entry: LedgerEntry) : SmsClassification()

    /** Wallet cash-out → wallet→Cash transfer. */
    data class WalletToCash(val from: LedgerEntry, val to: LedgerEntry) : SmsClassification()

    /** Financial-looking SMS that needs user teaching before it is persisted. */
    data object Unparsed : SmsClassification()

    data object Noise : SmsClassification()
}
