package com.finmagic.data.sms.parsers


/**
 * BBKSRSL-style "Debited/Credited … Current Balance" messages plus the shared
 * NexusPay "debited (…)/credited (…) … C/B Tk…" ledger format and generic
 * bank→wallet funding lines. Used directly for unknown banks, and reused by the
 * City/DBBL parsers via [parseCbStyle] (plan §6 #3).
 */
object GenericLedgerParser {

    private val bbksrslDebit = Regex(
        """BBKSRSL\s+Account\s+[\d*]+\s+has\s+been\s+Debited\s+by\s+(?:BDT|Tk|TK)\s*([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    private val bbksrslCredit = Regex(
        """BBKSRSL\s+Account\s+[\d*]+\s+has\s+been\s+Credited\s+(?:with|by)?\s*(?:BDT|Tk|TK)?\s*([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    /** Shared NexusPay C/B format: "your A/C ***3657 debited (ATM Cash Withdrawal) by Tk20,000.00 on … C/B Tk500.95". */
    private val cbLedger = Regex(
        """A/?C#?\s*([\d*]+)\s+(debited|credited)\s*\(([^)]+)\)\s+by\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    /** Card spend alert: "TK 200.00 transacted at ROCKET APP OFFUS >DHAKA BD on … using card#***8044. Balance TK …". */
    private val transactedAt = Regex(
        """(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+transacted\s+at\s+(.+?)\s+on\s+.+?using\s+card#?\s*[:#]?\s*([*Xx\d\-]+)""",
        RegexOption.IGNORE_CASE
    )

    fun classifyCardTxn(body: String, smsTime: Long): SmsClassification.CardTxn? {
        val m = transactedAt.find(body) ?: return null
        val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
        if (amount <= 0.0) return null
        val tail = Regex("""(\d{3,4})$""").find(m.groupValues[3])?.groupValues?.get(1)
        return SmsClassification.CardTxn(
            cardTail = tail ?: "",
            amount = amount,
            merchant = m.groupValues[2].trim().take(60),
            occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime
        )
    }

    private fun categoryFor(reason: String, body: String? = null): String {
        val r = reason.uppercase()
        val b = body?.uppercase() ?: ""
        return when {
            r.contains("ATM") || b.contains("ATM") -> TxnCategory.ATM
            r.contains("INTEREST") -> TxnCategory.INTEREST
            r.contains("NPSB") || b.contains("NPSB") -> TxnCategory.NPSB
            r.contains("FUND TRANSFER") || r.contains("FUND-TRANSFER") -> TxnCategory.FUND_TRANSFER
            else -> reason.trim()
        }
    }

    /**
     * Parses the shared C/B ledger line. [institution] decides which Account owns it.
     * ATM-cash debits surface as [SmsClassification.AtmWithdraw] so they become
     * Bank→Cash transfers (plan §5); other movements as [SmsClassification.AcctTxn].
     * Returns null when the body doesn't match.
     */
    fun parseCbStyle(body: String, institution: String, smsTime: Long): SmsClassification? {
        val m = cbLedger.find(body) ?: return null
        val mask = m.groupValues[1]
        val isDebit = m.groupValues[2].equals("debited", ignoreCase = true)
        val amount = LedgerText.parseAmount(m.groupValues[4]) ?: return null
        if (amount <= 0.0) return null
        
        val rawCategory = m.groupValues[3]
        var category = categoryFor(rawCategory, body)
        
        // Flag generic non-NPSB credits as "Credit"; keep recognized kinds
        // (e.g. Interest Credit) intact.
        if (!isDebit && category != TxnCategory.NPSB && category != TxnCategory.INTEREST) {
            category = TxnCategory.CREDIT
        }

        val entry = LedgerEntry(
            institution = institution,
            kind = AccountKind.BANK,
            accountMask = mask,
            direction = if (isDebit) LedgerDirection.OUT else LedgerDirection.IN,
            amount = amount,
            balanceAfter = LedgerText.parseBalance(body),
            occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
            category = category,
            counterparty = if (category == TxnCategory.NPSB) "NPSB" else rawCategory.trim()
        )
        return if (isDebit && entry.category == TxnCategory.ATM) {
            SmsClassification.AtmWithdraw(entry)
        } else {
            SmsClassification.AcctTxn(entry)
        }
    }

    /**
     * Generic bank→wallet funding: "… debited from your A/C ***934 and credited to Nagad Wallet 0131… A/C Bal:12711.7".
     * Emits a single debit on the bank account; the destination wallet stays a
     * counterparty so recipient wallets never become tracked accounts.
     */
    fun parseAcctToWallet(
        body: String,
        institution: String,
        smsTime: Long
    ): SmsClassification.AcctTxn? {
        if (!body.contains("Wallet", ignoreCase = true)) return null
        val m = Regex(
            """(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+has\s+been\s+debited\s+from\s+your\s+A/?C#?\s*([\d*]+)\s+and\s+credited\s+to\s+(\S+)\s+Wallet""",
            RegexOption.IGNORE_CASE
        ).find(body) ?: return null

        val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
        val mask = m.groupValues[2]
        val walletWord = m.groupValues[3]
        val walletInstitution = when {
            walletWord.contains("Nagad", ignoreCase = true) -> Institutions.NAGAD
            walletWord.contains("Rocket", ignoreCase = true) -> Institutions.ROCKET
            walletWord.contains("bKash", ignoreCase = true) || walletWord.contains("bkash", ignoreCase = true) -> Institutions.BKASH
            else -> walletWord.replace(Regex("""[^A-Za-z]"""), "")
        }
        val walletNumber = Regex("""Wallet\s*#?\s*([\d+]{6,})""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)

        return SmsClassification.AcctTxn(
            LedgerEntry(
                institution = institution,
                kind = AccountKind.BANK,
                accountMask = mask,
                direction = LedgerDirection.OUT,
                amount = amount,
                balanceAfter = LedgerText.parseBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                category = TxnCategory.FUND_TRANSFER,
                counterparty = listOfNotNull(walletInstitution.ifBlank { "Mobile" }, walletNumber)
                    .joinToString(" ") + " Wallet"
            )
        )
    }

    fun matches(sender: String?, body: String): Boolean {
        if (LedgerText.looksLikeNoise(body)) return false
        val b = body.uppercase()
        return b.contains("BBKSRSL") ||
            b.contains("C/B ") ||
            (b.contains("WALLET") && b.contains("DEBITED") && b.contains("CREDITED")) ||
            (b.contains("TRANSACTED AT") && b.contains("USING CARD"))
    }

    fun classify(sender: String?, body: String, smsTime: Long): SmsClassification? {
        if (!matches(sender, body)) return null

        // 1. Bank→wallet funding first (most specific).
        parseAcctToWallet(body, Institutions.UNKNOWN_BANK, smsTime)?.let { return it }

        // 2. Shared NexusPay C/B ledger — institution unknown here; caller (classifier)
        //    resolves via sender aliases before falling through to this parser.
        parseCbStyle(body, Institutions.UNKNOWN_BANK, smsTime)?.let { return it }

        // 3. Card spend alerts ("transacted at … using card#…").
        classifyCardTxn(body, smsTime)?.let { return it }

        // 3. BBKSRSL savings ledger with running balance.
        val debit = bbksrslDebit.find(body)
        val credit = bbksrslCredit.find(body)
        val m = debit ?: credit ?: return null
        val amount = LedgerText.parseAmount(m.groupValues[1])
        if (amount == null || amount <= 0.0) return null
        val mask = LedgerText.bbksrslMask.find(body)?.groupValues?.get(1) ?: return null

        val isDebit = debit != null
        val isNpsb = body.contains("NPSB", ignoreCase = true)
        val entry = LedgerEntry(
            institution = Institutions.BBKSRSL,
            kind = AccountKind.BANK,
            accountMask = mask,
            direction = if (isDebit) LedgerDirection.OUT else LedgerDirection.IN,
            amount = amount,
            balanceAfter = LedgerText.parseBalance(body),
            occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
            category = when {
                isDebit -> TxnCategory.ATM
                isNpsb -> TxnCategory.NPSB
                else -> TxnCategory.CREDIT
            },
            counterparty = if (isDebit) "BBKSRSL ATM" else if (isNpsb) "NPSB" else "Credit"
        )

        return if (isDebit) {
            SmsClassification.AtmWithdraw(entry)
        } else {
            SmsClassification.AcctTxn(entry)
        }
    }
}
