package com.finmagic.data.sms.parsers

import java.util.Locale

/**
 * Pubali Bank ledger-side messages (plan §6 #5).
 *
 * Corpus-grounded (Analysis/sms-20260819151558.xml, sender "PUBALI BANK"):
 *  - The main transaction A/C appears under several spellings of the same
 *    account: "***934", "***0934" (EFT salary credits) and "*******240934"
 *    (CRM cash deposits). Masks are normalized to their zero-padded trailing
 *    four digits so all variants resolve to ONE Account row.
 *  - Distinct sub-accounts seen: TBSD small-deposit a/c ("***463", Motijheel
 *    branch deposits) and a credit-card-linked EMI settlement a/c ("***674").
 *  - Debit-card alerts ("… Card*7557.") and inbound "FUND TRANSFER from …"
 *    alerts carry no A/C reference; they belong to that main account.
 *  - Credit-card POS purchases ("Card*2402") are intentionally NOT handled
 *    here — [com.finmagic.data.sms.parsers.CardPosParser] claims them first as
 *    CardTransactions, so they must never also become account debits.
 *
 * Bills stay in [com.finmagic.data.sms.BankSmsParser] (PBPLC CREDIT CARD).
 */
object PubaliParser {

    /** Account used when an alert references only the card / fund source. */
    private const val PRIMARY_MASK_FALLBACK = "0934"

    /** "A/C ***934", "a/c ***0934", "account ***463", "A/C Number *******240934". */
    private val maskRe = Regex(
        """(?:A/?C(?:\s+Number)?|account)\s*\**\s*([\d*]+)""",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------- card alerts
    // "BDT 20000.00 debited for cash withdrawal at ATM PUBALI BANK PLC on
    //  05/MAR/26 at 02:49:58 PM.A/C Bal:BDT Cr 12076.7.Cust ID:30062088.Card*7557."
    private val cardAtmRe = Regex(
        """(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+debited\s+for\s+cash\s+withdrawal\s+at\s+(.+?)\s+on\s+.+?Card\*\s*(\d{3,4})""",
        RegexOption.IGNORE_CASE
    )

    // ---------------------------------------------------- PI Banking transfers
    // "BDT 5000 has been debited from your a/c ***934 and credited to
    //  MD NAZIM UDDIN, CITY BANK on 04-JAN-26 through PI. A/C Bal:14880"
    private val transferDebitRe = Regex(
        """(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+has\s+been\s+debited\s+from\s+your\s+(?:A/?C|account)\s*\**\s*([\d*]+)\s+and\s+credited\s+to\s+(.+?)\s+on\s+\d""",
        RegexOption.IGNORE_CASE
    )

    // --------------------------------------------------------------- credits
    // PI Banking in: "… credited to your a/c ***934 from MD NAZIM UDDIN on 01-JAN-26 …"
    private val piCreditRe = Regex(
        """(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+has\s+been\s+credited\s+to\s+your\s+(?:A/?C|account)\s*\**\s*([\d*]+)\s+from\s+(.+?)\s+on\s+\d""",
        RegexOption.IGNORE_CASE
    )

    // EFT salary/dividend in: "… credited to your a/c ***0934 through EFT SONALI
    // BANK,LR,NOT APPLICABLE on 08-JAN-26. A/C Bal:12320.7"
    private val eftCreditRe = Regex(
        """credited\s+to\s+your\s+(?:A/?C|account)\s*\**\s*([\d*]+)\s+through\s+EFT\s+(.+?)\s+on\s+\d""",
        RegexOption.IGNORE_CASE
    )

    // Inbound fund transfer without mask: "… credited to your account using FUND
    // TRANSFER from DUTCH-BANGLA BANK LIMITED on 08-FEB-26 A/C Bal:Cr 12076.7"
    private val fundTransferInRe = Regex(
        """credited\s+to\s+your\s+account\s+using\s+FUND\s+TRANSFER\s+from\s+(.+?)\s+on\s+\d""",
        RegexOption.IGNORE_CASE
    )

    // Reversal (all caps): "REVERSE TRANSACTION: BDT 20000 HAS BEEN CREDITED TO
    // YOUR A/C ***934 ON 20-MAY-26 THROUGH PI BANKING."
    private val reversalRe = Regex(
        """REVERSE\s+TRANSACTION:\s*(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+HAS\s+BEEN\s+CREDITED\s+TO\s+YOUR\s+(?:A/?C|ACCOUNT)\s*\**\s*([\d*]+)\s+ON""",
        RegexOption.IGNORE_CASE
    )

    // -------------------------------------------------------- branch counters
    // "BDT 1000 has been credited to your account ***463 through MOTIJHEEL
    //  CORPORATE branch  on 06-01-2026. A/C Bal: Cr 2000"
    private val branchRe = Regex(
        """(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+has\s+been\s+(credited\s+to|debited\s+from)\s+your\s+account\s*\**\s*([\d*]+)\s+through\s+(.+?)\s+branch""",
        RegexOption.IGNORE_CASE
    )

    // CRM cash deposit: "You deposited BDT 20000.00 through CRM to A/C Number
    // *******240934 on 09-APR-26 .A/C Bal:Cr 57886.35."
    private val crmDepositRe = Regex(
        """You\s+deposited\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+through\s+CRM\s+to\s+A/?C\s+Number\s*\**(\d+)""",
        RegexOption.IGNORE_CASE
    )

    fun matches(sender: String?, body: String): Boolean {
        if (LedgerText.looksLikeNoise(body)) return false
        val s = sender?.uppercase(Locale.US)?.replace(Regex("""[\s\u00A0\-._]"""), "") ?: ""
        val b = body.uppercase(Locale.US)
        return s.contains("PUBALI") || s.contains("PBPLC") || s == "PBL" ||
            b.contains("PUBALI") || b.contains("PBPLC")
    }

    /**
     * Zero-padded last four digits of every mask spelling so "***934",
     * "***0934" and "*******240934" all collapse to "0934".
     */
    internal fun normalizeMask(raw: String?): String {
        val digits = raw?.filter { it.isDigit() }.orEmpty()
        if (digits.isEmpty()) return PRIMARY_MASK_FALLBACK
        return digits.takeLast(4).padStart(4, '0')
    }

    fun classify(sender: String?, body: String, smsTime: Long): SmsClassification? {
        if (!matches(sender, body)) return null

        cardWithdrawal(body, smsTime)?.let { return it }
        transferDebit(body, smsTime)?.let { return it }
        credit(body, smsTime)?.let { return it }
        branchMovement(body, smsTime)?.let { return it }
        crmDeposit(body, smsTime)?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ pieces

    private fun cardWithdrawal(body: String, smsTime: Long): SmsClassification.AtmWithdraw? {
        val m = cardAtmRe.find(body) ?: return null
        val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
        val place = m.groupValues[2].trim()
        val tail = m.groupValues[3]
        return SmsClassification.AtmWithdraw(
            LedgerEntry(
                institution = Institutions.PUBALI,
                kind = AccountKind.BANK,
                accountMask = normalizeMask(maskRe.find(body)?.groupValues?.get(1)),
                direction = LedgerDirection.OUT,
                amount = amount,
                balanceAfter = LedgerText.parseBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                category = TxnCategory.ATM,
                counterparty = "$place ••Card$tail"
            )
        )
    }

    private fun transferDebit(body: String, smsTime: Long): SmsClassification.AcctTxn? {
        val m = transferDebitRe.find(body) ?: return null
        val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
        val rawDest = m.groupValues[3].trim()

        val wallet = Regex("""(Nagad|Rocket|bKash)\s+Wallet\s*(\d{6,})""", RegexOption.IGNORE_CASE)
            .find(rawDest)
        val (category, counterparty) = when {
            wallet != null -> {
                val institution = when (wallet.groupValues[1].lowercase(Locale.US)) {
                    "nagad" -> Institutions.NAGAD
                    "rocket" -> Institutions.ROCKET
                    else -> Institutions.BKASH
                }
                TxnCategory.FUND_TRANSFER to "${institution} ${wallet.groupValues[2]} Wallet"
            }
            rawDest.matches(Regex("""(?i)(your\s+)?(FD|MTD)\s+A/?C.*""")) ->
                TxnCategory.FUND_TRANSFER to rawDest.replace(Regex("""(?i)^your\s+"""), "").trim()
            rawDest.contains("MOBILE RECHARGE", ignoreCase = true) ->
                TxnCategory.PAYMENT to "Mobile Recharge"
            rawDest.contains("DPDC", ignoreCase = true) || rawDest.contains("Prepaid", ignoreCase = true) ->
                TxnCategory.BILL_PAY to rawDest
            else -> TxnCategory.FUND_TRANSFER to rawDest.replace(Regex("""(?i)^your\s+"""), "").trim()
        }

        return SmsClassification.AcctTxn(
            LedgerEntry(
                institution = Institutions.PUBALI,
                kind = AccountKind.BANK,
                accountMask = normalizeMask(m.groupValues[2]),
                direction = LedgerDirection.OUT,
                amount = amount,
                balanceAfter = LedgerText.parseBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                category = category,
                counterparty = counterparty
            )
        )
    }

    private fun credit(body: String, smsTime: Long): SmsClassification.AcctTxn? {
        piCreditRe.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            return acctIn(
                mask = normalizeMask(m.groupValues[2]),
                amount = amount,
                balance = LedgerText.parseBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                counterparty = m.groupValues[3].trim()
            )
        }

        eftCreditRe.find(body)?.let { m ->
            val amount = LedgerText.firstAmount(body) ?: return null
            return acctIn(
                mask = normalizeMask(m.groupValues[1]),
                amount = amount,
                balance = LedgerText.parseBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                counterparty = "EFT ${m.groupValues[2].trim().trimEnd(',')}"
            )
        }

        fundTransferInRe.find(body)?.let { m ->
            val amount = LedgerText.firstAmount(body) ?: return null
            return acctIn(
                mask = normalizeMask(null),
                amount = amount,
                balance = LedgerText.parseBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                counterparty = "Fund Transfer ${m.groupValues[1].trim()}"
            )
        }

        reversalRe.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            return acctIn(
                mask = normalizeMask(m.groupValues[2]),
                amount = amount,
                balance = LedgerText.parseBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                counterparty = "Transaction Reversal"
            )
        }

        return null
    }

    private fun branchMovement(body: String, smsTime: Long): SmsClassification? {
        val m = branchRe.find(body) ?: return null
        val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
        val isDebit = m.groupValues[2].startsWith("debited", ignoreCase = true)
        val branch = "${m.groupValues[4].trim()} Branch"
        val entry = LedgerEntry(
            institution = Institutions.PUBALI,
            kind = AccountKind.BANK,
            accountMask = normalizeMask(m.groupValues[3]),
            direction = if (isDebit) LedgerDirection.OUT else LedgerDirection.IN,
            amount = amount,
            balanceAfter = LedgerText.parseBalance(body),
            occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
            category = if (isDebit) TxnCategory.ATM else TxnCategory.CASH_DEPOSIT,
            counterparty = branch
        )
        return if (isDebit) SmsClassification.AtmWithdraw(entry) else SmsClassification.AcctTxn(entry)
    }

    private fun crmDeposit(body: String, smsTime: Long): SmsClassification.AcctTxn? {
        val m = crmDepositRe.find(body) ?: return null
        val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
        return SmsClassification.AcctTxn(
            LedgerEntry(
                institution = Institutions.PUBALI,
                kind = AccountKind.BANK,
                accountMask = normalizeMask(m.groupValues[2]),
                direction = LedgerDirection.IN,
                amount = amount,
                balanceAfter = LedgerText.parseBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                category = TxnCategory.CASH_DEPOSIT,
                counterparty = "CRM Cash Deposit"
            )
        )
    }

    private fun acctIn(
        mask: String,
        amount: Double,
        balance: Double?,
        occurredAt: Long?,
        counterparty: String?
    ): SmsClassification.AcctTxn =
        SmsClassification.AcctTxn(
            LedgerEntry(
                institution = Institutions.PUBALI,
                kind = AccountKind.BANK,
                accountMask = mask,
                direction = LedgerDirection.IN,
                amount = amount,
                balanceAfter = balance,
                occurredAt = occurredAt,
                category = TxnCategory.RECEIVED,
                counterparty = counterparty
            )
        )
}
