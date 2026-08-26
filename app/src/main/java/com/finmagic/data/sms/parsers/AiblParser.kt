package com.finmagic.data.sms.parsers

import java.util.Locale

/**
 * Al-Arafah Bank ledger parser (corpus-verified). Every AIBL SMS arrives from the
 * shared gateway +8801944400032, so matching is body-driven. Known styles:
 *  - "…has been Credited by amount 100000 on Account  002131****868 at date : 19-OCT-25 . Thanks for banking with AIBL."
 *  - "profit of BDT 2521 has been credited to your account 002131****868. …-AIBPLC."
 *  - "Your A/C 002131****868 has been Debited by BDT 252.20 on 16-APR-26, 09:39:18 PM.Ref: …-AIBLPLC"
 * Senders appear as AIB / AIBL / AIBPLC / AIBLPLC. Non-ledger notices
 * ("MTDR account … opened") are claimed as [SmsClassification.Noise].
 */
object AiblParser {

    private val debitCredit = Regex(
        """(?:debited|credited)[\s\S]{0,60}?(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    // "Credited by amount 100000" — no currency symbol.
    private val byAmount = Regex(
        """(?:debited|credited)[\s\S]{0,60}?by\s+amount\s+([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    // "profit of BDT 2521 has been credited to your account".
    private val profitAmount = Regex(
        """profit\s+of\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    // Masked account without an A/C prefix: "on Account  002131****868".
    private val bareMask = Regex("""\b(\d{1,6}\*{2,}\d{1,6})\b""")

    // Senders arrive as "AIB", "AIBL", "AIBPLC" or "AIBLPLC" in the wild.
    private val senderAlias = Regex("""^AIB(?:L(?:PLC)?)?(?:PLC)?$""")

    private val bodyAlias = Regex("""\bAIB(?:LPLC|PLC|L)?\b|AL[- ]?ARAFAH|ASTHA""")

    fun matches(sender: String?, body: String): Boolean {
        if (LedgerText.looksLikeNoise(body)) return false
        val s = sender?.uppercase(Locale.US)?.trim() ?: ""
        if (senderAlias.containsMatchIn(s)) return true
        return bodyAlias.containsMatchIn(body.uppercase(Locale.US))
    }

    fun classify(sender: String?, body: String, smsTime: Long): SmsClassification? {
        if (!matches(sender, body)) return null
        val m = debitCredit.find(body)
            ?: byAmount.find(body)
            ?: profitAmount.find(body)
            ?: return SmsClassification.Noise
        val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return SmsClassification.Noise
        val mask = LedgerText.parseMask(body)
            ?: bareMask.find(body)?.groupValues?.get(1)
            ?: return SmsClassification.Noise
        val isDebit = body.contains("debited", ignoreCase = true)
        val isNpsb = body.contains("NPSB", ignoreCase = true)

        return SmsClassification.AcctTxn(
            LedgerEntry(
                institution = Institutions.AIBL,
                kind = AccountKind.BANK,
                accountMask = mask,
                direction = if (isDebit) LedgerDirection.OUT else LedgerDirection.IN,
                amount = amount,
                balanceAfter = LedgerText.parseBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                category = when {
                    body.contains("profit", ignoreCase = true) -> TxnCategory.INTEREST
                    isNpsb -> TxnCategory.NPSB
                    !isDebit -> TxnCategory.CREDIT
                    else -> TxnCategory.FUND_TRANSFER
                },
                counterparty = if (isNpsb) "NPSB" else null
            )
        )
    }
}
