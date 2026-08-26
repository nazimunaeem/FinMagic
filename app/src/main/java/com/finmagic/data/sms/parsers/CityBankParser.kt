package com.finmagic.data.sms.parsers

import java.util.Locale

/**
 * City Bank ledger-side messages (plan §6 #1): NexusPay-format C/B account
 * debits/credits and credit-card purchase alerts. Bills + payment-received are
 * handled by [com.finmagic.data.sms.BankSmsParser] / [com.finmagic.data.sms.PaymentSmsParser].
 */
object CityBankParser {

    private val cardPurchase = Regex(
        """(?:Tk|TK|BDT)\.?\s*([\d,]+(?:\.\d+)?)\s+purchased\s+at\s+(\S+)""",
        RegexOption.IGNORE_CASE
    )

    private val cardTailPattern = Regex("""CARD(?:[\s\u00A0]+NO)?\s*:?\s*([\dXx*\-]+)""")

    fun matches(sender: String?, body: String): Boolean {
        if (LedgerText.looksLikeNoise(body)) return false
        val s = sender?.uppercase(Locale.US)?.replace(Regex("""[\s\u00A0\-]"""), "") ?: ""
        val b = body.uppercase(Locale.US)
        return s.contains("CITY") || s.contains("AMEX") || s.contains("+8801944400032") ||
            b.contains("CITY BANK")
    }

    fun classify(sender: String?, body: String, smsTime: Long): SmsClassification? {
        if (!matches(sender, body)) return null

        // Card spend alert → recognized but stored only in the optional card ledger.
        cardPurchase.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            val tail = cardTailPattern.find(body)?.groupValues?.get(1)
                ?.let { Regex("""(\d{3,4})$""").find(it)?.groupValues?.get(1) }
            return SmsClassification.CardTxn(
                cardTail = tail ?: "",
                amount = amount,
                merchant = m.groupValues[2],
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                balanceAfter = LedgerText.parseBalance(body),
                bankName = Institutions.CITY
            )
        }

        return GenericLedgerParser.parseCbStyle(body, Institutions.CITY, smsTime)
    }
}
