package com.finmagic.data.sms.parsers

import java.util.Locale

/**
 * Credit/debit card POS purchase alerts across banks (plan §2.1 Phase 4):
 *  - City:  "Tk. 450.00 purchased at GRAMEENPHONE.COM\nCARD NO: 376***945\nBal Tk. ..."
 *  - BRAC:  "BDT 340 transacted at ALAM S BABY WEAR on 09/08/26 [06:42:12 PM BST] using Card#***8043. ..."
 *  - PUBALI/EBL: "BDT 1554.00 debited for purchase at AARONG-DHANMOND on 11/APR/26 at 12:08:00 PM.A/C Bal:...Card*2402."
 *  - EBL:   "EBL CARDS: Purchase txn BDT 50 from SKYBANKING ... Card 423800**5428 ..."
 *
 * These were previously recognized but discarded; now they are stored as
 * [com.finmagic.data.local.entity.CardTransaction] and surfaced in the Expenses tab.
 *
 * Must run AFTER the bill-statement and card-payment parsers (those take
 * precedence) but before the generic ledger parser.
 */
object CardPosParser {

    private val city = Regex(
        """(?:Tk|TK|BDT)\.?\s*([\d,]+(?:\.\d+)?)\s+purchased\s+at\s+(\S+)""",
        RegexOption.IGNORE_CASE
    )
    private val cityTail = Regex("""CARD\s*(?:NO)?\s*:?\s*([\dXx*\-]+)""", RegexOption.IGNORE_CASE)

    private val brac = Regex(
        """(?:BDT|aTK)\s*([\d,]+(?:\.\d+)?)\s+transacted\s+at\s+(.+?)\s+on\s+.+?using\s+Card#([\d*]+)""",
        RegexOption.IGNORE_CASE
    )

    private val pubaliEbl = Regex(
        """BDT\s*([\d,]+(?:\.\d+)?)\s+debited\s+for\s+purchase\s+at\s+(.+?)\s+on\s+.+?(?:\.|\s)Card\*?(\d+)""",
        RegexOption.IGNORE_CASE
    )

    private val eblCard = Regex(
        """EBL[\s\u00A0]+CARDS?:[\s\u00A0]+Purchase[\s\u00A0]+txn[\s\u00A0]+(?:BDT|Tk|TK)?\.?\s*([\d,]+(?:\.\d+)?)\s+from\s+(.+?)(?:\s+on|\s+Card|\s+Date)""",
        RegexOption.IGNORE_CASE
    )
    private val eblCardTail = Regex("""Card[\s\u00A0]*(\d{4})""", RegexOption.IGNORE_CASE)

    private val tailDigits = Regex("""(\d{3,4})$""")

    private fun last4(raw: String?): String =
        raw?.let { tailDigits.find(it)?.groupValues?.get(1) } ?: ""

    fun classify(sender: String?, body: String, smsTime: Long): SmsClassification.CardTxn? {
        if (LedgerText.looksLikeNoise(body)) return null

        // EBL card purchase ("EBL CARDS: Purchase txn BDT ...").
        eblCard.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            val tail = last4(eblCardTail.find(body)?.groupValues?.get(1))
            return SmsClassification.CardTxn(
                cardTail = tail,
                amount = amount,
                merchant = m.groupValues[2].trim(),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                balanceAfter = LedgerText.parseBalance(body),
                bankName = Institutions.EBL
            )
        }

        // City Bank card purchase.
        city.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            val tail = last4(cityTail.find(body)?.groupValues?.get(1))
            return SmsClassification.CardTxn(
                cardTail = tail,
                amount = amount,
                merchant = m.groupValues[2],
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                balanceAfter = LedgerText.parseBalance(body),
                bankName = detectCityBankVariant(body, sender)
            )
        }

        // BRAC Bank card purchase.
        brac.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            return SmsClassification.CardTxn(
                cardTail = last4(m.groupValues[3]),
                amount = amount,
                merchant = m.groupValues[2].trim(),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                balanceAfter = LedgerText.parseBalance(body),
                bankName = Institutions.BRAC
            )
        }

        // PUBALI / EBL card purchase (same format).
        pubaliEbl.find(body)?.let { m ->
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: return null
            return SmsClassification.CardTxn(
                cardTail = last4(m.groupValues[3]),
                amount = amount,
                merchant = m.groupValues[2].trim(),
                occurredAt = LedgerText.parseOccurredAt(body) ?: smsTime,
                balanceAfter = LedgerText.parseBalance(body),
                bankName = detectBank(sender)
            )
        }

        return null
    }

    private fun detectCityBankVariant(body: String, sender: String?): String {
        val b = body.uppercase(Locale.US)
        val s = sender?.uppercase(Locale.US)?.replace(Regex("""[\s\u00A0\-]"""), "") ?: ""
        // 376 prefix = American Express, 474 prefix = Visa
        if (b.contains("376") || s.contains("AMEX") || s.contains("CITYTOUCH")) {
            return "City Bank (Amex)"
        }
        if (b.contains("474") || s.contains("VISA")) {
            return "City Bank (Visa)"
        }
        return Institutions.CITY
    }

    private fun detectBank(sender: String?): String {
        val s = sender?.uppercase(Locale.US)?.replace(Regex("""[\s\u00A0\-]"""), "") ?: ""
        return when {
            s.contains("EBL") || s.contains("SKYBANKING") || s.contains("16230") -> Institutions.EBL
            s.contains("PUBALI") || s.contains("PBPLC") -> Institutions.PUBALI
            else -> Institutions.UNKNOWN_BANK
        }
    }
}
