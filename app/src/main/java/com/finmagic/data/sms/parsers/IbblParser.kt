package com.finmagic.data.sms.parsers

import java.util.Locale

/**
 * Islami Bank ledger (plan §6 #7):
 *  - "Dear Customer Tk 1000 has been transferred to A/C777**01530111 Balance Tk.990 Islami Bank"
 *  - periodic balance statements ("Balance Tk X").
 */
object IbblParser {

    private val transfer = Regex(
        """(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+has\s+been\s+(?:transferred|debited|credited)[\s\S]{0,80}?A/?C#?\s*([\d*]+)""",
        RegexOption.IGNORE_CASE
    )

    private val creditedTo = Regex(
        """has\s+been\s+credited\s+to""",
        RegexOption.IGNORE_CASE
    )

    fun matches(sender: String?, body: String): Boolean {
        if (LedgerText.looksLikeNoise(body)) return false
        val s = sender?.uppercase(Locale.US)?.trim() ?: ""
        val b = body.uppercase(Locale.US)
        // Sender arrives as "IBBL", "IBBPLC" or "IBB" (latest).
        return s.startsWith("IBBL") || s.contains("IBBPLC") || s == "IBB" ||
            b.contains("ISLAMI BANK") || b.contains("IBBPLC") || b.contains("IBB ")
    }

    fun classify(sender: String?, body: String, smsTime: Long): SmsClassification? {
        if (!matches(sender, body)) return null

        transfer.find(body)?.let { m ->
            val isIn = creditedTo.containsMatchIn(body)
            val isNpsb = body.contains("NPSB", ignoreCase = true)
            val isAtm = body.contains("ATM", ignoreCase = true)
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            
            return SmsClassification.AcctTxn(
                LedgerEntry(
                    institution = Institutions.IBBL,
                    kind = AccountKind.BANK,
                    accountMask = m.groupValues[2],
                    direction = if (isIn) LedgerDirection.IN else LedgerDirection.OUT,
                    amount = amount,
                    balanceAfter = LedgerText.parseBalance(body),
                    occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                    category = when {
                        isNpsb -> TxnCategory.NPSB
                        isAtm -> TxnCategory.ATM
                        isIn -> TxnCategory.CREDIT
                        else -> TxnCategory.FUND_TRANSFER
                    },
                    counterparty = when {
                        isNpsb -> "NPSB"
                        isAtm -> "ATM " + (if (isIn) "Deposit" else "Withdraw")
                        isIn -> "Credit"
                        else -> "Transfer Out"
                    }
                )
            )
        }

        // Periodic/monthly balance statement: no movement, only refreshes the
        // reported balance (plan §4 — banks without running-balance SMS).
        classifyStatement(body, Institutions.IBBL, smsTime)?.let { return it }
        return null
    }

    /**
     * Zero-amount ledger row carrying a fresh balance for periodic statements like
     * "Dear Customer, your A/C 777**01530111 Balance Tk.990 Islami Bank" or monthly
     * statement notices. Only fires when no debit/credit verb is present.
     */
    fun classifyStatement(body: String, institution: String, smsTime: Long): SmsClassification.AcctTxn? {
        val b = body.uppercase()
        if (b.contains("DEBIT") || b.contains("CREDIT") || b.contains("WITHDRAW") ||
            b.contains("TRANSFER")
        ) return null
        if (!b.contains("BALANCE")) return null
        val mask = LedgerText.parseMask(body) ?: return null
        val balance = LedgerText.parseBalance(body) ?: return null
        return SmsClassification.AcctTxn(
            LedgerEntry(
                institution = institution,
                kind = AccountKind.BANK,
                accountMask = mask,
                direction = LedgerDirection.IN,
                amount = 0.0,
                balanceAfter = balance,
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                category = TxnCategory.STATEMENT,
                counterparty = "Balance Statement"
            )
        )
    }
}
