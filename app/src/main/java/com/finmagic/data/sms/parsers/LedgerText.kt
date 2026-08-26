package com.finmagic.data.sms.parsers

import com.finmagic.data.sms.SmsDateParser

/** Shared regex helpers for extracting amounts/masks/balances from ledger SMS. */
internal object LedgerText {

    val amount = Regex("""(?:Tk|TK|BDT|US\$|\$)\.?\s*([\d,]+(?:\.\d+)?)""")
    val bareAmount = Regex("""\b([\d,]+(?:\.\d+)?)\b""")
    // Handles "A/C Bal:Cr 24526.7", "A/C Bal:BDT Cr 12076.7.", "A/C Bal:-23539.4"
    // and plain "Current Balance is BDT29,126.60". DR / a leading minus → negative.
    val balanceAfter = Regex(
        """(?:Current\s+Balance|A/?C\s*Bal(?:ance)?|C/?B|(?:Account\s+)?Balance|Bal)\s*(?:is)?\s*:?\s*(?:Tk|TK|BDT)?\.?\s*(?:CR|DR)?\.?\s*([-—–−]?)\s*([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )
    val accountMask = Regex("""A/?C#?\s*[:#]?\s*([*\d][\d*]{2,})""")
    val bbksrslMask = Regex("""BBKSRSL\s+Account\s+([\d*]+)""", RegexOption.IGNORE_CASE)

    fun parseAmount(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        return text.replace(",", "").trim().toDoubleOrNull()
    }

    /** First "… Tk X" style amount in [body]. */
    fun firstAmount(body: String): Double? =
        parseAmount(amount.find(body)?.groupValues?.get(1))

    /** Bank-reported running balance ("Current Balance is BDT29,126.60", "C/B Tk500.95", "A/C Bal:Cr 24526.7"). */
    fun parseBalance(body: String): Double? {
        val m = balanceAfter.find(body) ?: return null
        val value = parseAmount(m.groupValues[2]) ?: return null
        val negative = Regex("""\bDR\b""", RegexOption.IGNORE_CASE).containsMatchIn(m.value) ||
            m.groupValues[1].isNotEmpty()
        return if (negative) -value else value
    }

    /** First account mask ("10551**0001", "***3657", "1**88", "777**01530111"). */
    fun parseMask(body: String): String? {
        bbksrslMask.find(body)?.let { return it.groupValues[1] }
        return accountMask.find(body)?.groupValues?.get(1)
    }

    fun parseOccurredAt(body: String): Long? = SmsDateParser.parseFirst(body)

    /** True when body looks like OTP/marketing/noise we must never ledger (plan §8). */
    private val noiseHints = listOf(
        Regex("""one[\s-]?time\s+password""", RegexOption.IGNORE_CASE),
        Regex("""\bOTP\b"""),
        Regex("""verification code""", RegexOption.IGNORE_CASE),
        Regex("""unsubscribe""", RegexOption.IGNORE_CASE),
        Regex("""discount|offer|cashback|win|congratulations|quiz|result""", RegexOption.IGNORE_CASE),
        // EMI conversion notices -- these reference a past purchase amount, not a new debit.
        Regex("""converted\s+to\s+Instant\s*EMI""", RegexOption.IGNORE_CASE),
        Regex("""has\s+been\s+converted\s+to\s+(?:Instant\s*)?EMI""", RegexOption.IGNORE_CASE),
        Regex("""Instant\s*EMI""", RegexOption.IGNORE_CASE)
    )

    fun looksLikeNoise(body: String): Boolean = noiseHints.any { it.containsMatchIn(body) }
}
