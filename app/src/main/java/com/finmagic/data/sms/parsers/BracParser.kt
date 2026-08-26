package com.finmagic.data.sms.parsers

import java.util.Locale

/**
 * BRAC Bank savings-account ledger (plan §6 #2):
 *  - "TK 11,091.00 credited to A/C#10551**0001 on 30-04-25 @11:55 PM from OTHER BANK. Balance TK 11,091.78."
 *  - "TK 6,000.00 withdrawn from A/C#10551**0001 on 18-05-25 @03:16 PM from OTHER BANK ATM. Balance TK 3,772.78."
 *  - "Your DPS installment of TK 1,000.00 has been successfully paid from A/C# 10551**0001. Your A/C balance is TK 48,199.30."
 * Card bills and "transferred … to Card" payments are handled upstream.
 */
object BracParser {

    private val credit = Regex(
        """(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+credited\s+to\s+A/?C#?\s*([\d*]+)""",
        RegexOption.IGNORE_CASE
    )

    private val withdrawn = Regex(
        """(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+withdrawn\s+from\s+A/?C#?\s*([\d*]+)""",
        RegexOption.IGNORE_CASE
    )

    private val dps = Regex(
        """DPS\s+installment\s+of\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+has\s+been\s+successfully\s+paid\s+from\s+A/?C#?\s*([\d*]+)""",
        RegexOption.IGNORE_CASE
    )

    private val counterparty = Regex("""from\s+(?:OTHER\s+)?([A-Z][A-Za-z0-9 .&/-]{2,40}?)(?:\s*ATM)?\s*\.\s*(?:Balance|$)""")

    fun matches(sender: String?, body: String): Boolean {
        if (LedgerText.looksLikeNoise(body)) return false
        val s = sender?.uppercase(Locale.US) ?: ""
        val b = body.uppercase(Locale.US)
        return s.contains("BRAC") || b.contains("BRACBANK") || b.contains("BRAC BANK") ||
            b.contains("BBL A/C")
    }

    fun classify(sender: String?, body: String, smsTime: Long): SmsClassification? {
        if (!matches(sender, body)) return null

        // DPS first (it also contains no date → falls back to SMS time).
        dps.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            val mask = m.groupValues[2]
            return SmsClassification.AcctTxn(
                LedgerEntry(
                    institution = Institutions.BRAC,
                    kind = AccountKind.BANK,
                    accountMask = mask,
                    direction = LedgerDirection.OUT,
                    amount = amount,
                    balanceAfter = LedgerText.parseBalance(body),
                    occurredAt = smsTime,
                    category = TxnCategory.DPS,
                    counterparty = "DPS Installment"
                )
            )
        }

        // ATM withdrawal → Bank→Cash transfer (Feature C).
        withdrawn.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            val mask = m.groupValues[2]
            val isAtm = body.contains("ATM", ignoreCase = true)
            return if (isAtm) {
                SmsClassification.AtmWithdraw(
                    LedgerEntry(
                        institution = Institutions.BRAC,
                        kind = AccountKind.BANK,
                        accountMask = mask,
                        direction = LedgerDirection.OUT,
                        amount = amount,
                        balanceAfter = LedgerText.parseBalance(body),
                        occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                        category = TxnCategory.ATM,
                        counterparty = "ATM Withdrawal"
                    )
                )
            } else {
                SmsClassification.AcctTxn(
                    LedgerEntry(
                        institution = Institutions.BRAC,
                        kind = AccountKind.BANK,
                        accountMask = mask,
                        direction = LedgerDirection.OUT,
                        amount = amount,
                        balanceAfter = LedgerText.parseBalance(body),
                        occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                        category = TxnCategory.FUND_TRANSFER,
                        counterparty = counterparty.find(body)?.groupValues?.get(1)?.trim()
                    )
                )
            }
        }

        // Plain credit into the account.
        credit.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            val mask = m.groupValues[2]
            val isNpsb = body.contains("NPSB", ignoreCase = true)
            return SmsClassification.AcctTxn(
                LedgerEntry(
                    institution = Institutions.BRAC,
                    kind = AccountKind.BANK,
                    accountMask = mask,
                    direction = LedgerDirection.IN,
                    amount = amount,
                    balanceAfter = LedgerText.parseBalance(body),
                    occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                    category = if (isNpsb) TxnCategory.NPSB else TxnCategory.CREDIT,
                    counterparty = if (isNpsb) "NPSB" else (counterparty.find(body)?.groupValues?.get(1)?.trim() ?: "Credit")
                )
            )
        }

        return null
    }
}
