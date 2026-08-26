package com.finmagic.data.sms.parsers

import java.util.Locale

/**
 * DBBL / Dutch-Bangla NexusPay ledger (plan §6 #6): shares the C/B debit-credit
 * format with City Bank; institution is resolved from the 16216/DBBL aliases.
 */
object DbblParser {

    fun matches(sender: String?, body: String): Boolean {
        if (LedgerText.looksLikeNoise(body)) return false
        val s = sender?.uppercase(Locale.US) ?: ""
        val b = body.uppercase(Locale.US)
        return s.contains("DBBL") ||
            b.contains("NEXUSPAY") || b.contains("DBBL") || b.contains("DUTCH-BANGLA") ||
            b.contains("DUTCH BANGLA") ||
            (s == "16216" && b.contains("A/C") && b.contains("C/B"))
    }

    fun classify(sender: String?, body: String, smsTime: Long): SmsClassification? {
        if (!matches(sender, body)) return null
        GenericLedgerParser.parseCbStyle(body, Institutions.DBBL, smsTime)?.let { return it }

        // Balance-query replies carry no movement — refresh the reported balance only.
        val b = body.uppercase(Locale.US)
        if (!b.contains("DEBIT") && !b.contains("CREDIT") && !b.contains("WITHDRAW") &&
            b.contains("BALANCE")
        ) {
            IbblParser.classifyStatement(body, Institutions.DBBL, smsTime)?.let { return it }
        }
        return null
    }
}
