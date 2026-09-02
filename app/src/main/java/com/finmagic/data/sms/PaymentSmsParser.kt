package com.finmagic.data.sms

import java.text.SimpleDateFormat
import java.util.Locale

data class ParsedPayment(
    val cardTail: String,
    val amountBdt: Double,
    val amountUsd: Double,
    val paymentDate: String?,
    val bankName: String? = null
)

/**
 * Detects "payment received" / transfer-to-card SMS and extracts the data needed
 * to auto-mark the matching credit-card bill as paid.
 *
 * Supported formats (from real corpus):
 *  City Bank:  "05-DEC-24 \nTk. 3,713.00 payment received \nUS$. 0.00 payment received
 *               \nCARD NO: 376***945 \nClient ID: 2233552"
 *  City Bank (long mask, blank lines): "02-SEP-25 ¶ ¶ Tk. 5,000.00 payment received ¶ …
 *               ¶ CARD NO: 376948*****2945"
 *  BRAC Bank:  "Tk 7,304.00 has been transferred from your BBL A/C: 1**0001 to
 *               Card: 3769-48XX-XXX2-945. Available balance is Tk. 3,787.78."
 */
object PaymentSmsParser {

    private val bdtReceivedPattern = Regex(
        """Tk\.?\s*([\d,]+(?:\.\d+)?)\s*payment\s+received""",
        RegexOption.IGNORE_CASE
    )

    private val usdReceivedPattern = Regex(
        """US\$\.?\s*([\d,]+(?:\.\d+)?)\s*payment\s+received""",
        RegexOption.IGNORE_CASE
    )

    private val cardNoPattern = Regex(
        """CARD[\s\u00A0]*NO[\s\u00A0]*:?[\s\u00A0]*([0-9Xx*\-\s]+)""",
        RegexOption.IGNORE_CASE
    )

    private val bracTransferPattern = Regex(
        """Tk\.?\s*([\d,]+(?:\.\d+)?)\s+has\s+been\s+transferred\s+from\s+your\s+[\s\S]{0,40}?A/?C[\s\S]{0,20}?to\s+Card\s*:?\s*([0-9Xx*\-]+)""",
        RegexOption.IGNORE_CASE
    )

    private val eblCardPaymentPattern = Regex(
        """EBL[\s\u00A0]+CARDS?:[\s\u00A0]+(?:Payment of )?(?:BDT|Tk|TK|USD)?\.?\s*([\d,]+\.?\d*)\s+(?:is )?credited to Card[\s\u00A0]+[\d*]+\*{1,4}(\d{3,4})""",
        RegexOption.IGNORE_CASE
    )

    private val bkashCardPaymentPattern = Regex(
        """Bill Payment of (?:Tk|TK)?\.?\s*([\d,]+\.?\d*) for ([A-Z]+) Credit Card is successful""",
        RegexOption.IGNORE_CASE
    )

    private val datePattern = Regex("""\b(\d{1,2}[-/.][A-Za-z]{3}[-/.]\d{2,4}|\d{1,2}[-/.][0-9]{1,2}[-/.]\d{2,4})\b""")

    fun parse(body: String): ParsedPayment? {
        // bKash to Card Payment
        bkashCardPaymentPattern.find(body)?.let { match ->
            val bankName = when {
                match.groupValues[2].contains("CITY", ignoreCase = true) -> "City Bank"
                match.groupValues[2].contains("EBL", ignoreCase = true) -> "EBL"
                match.groupValues[2].contains("BRAC", ignoreCase = true) -> "BRAC Bank"
                else -> match.groupValues[2]
            }
            return ParsedPayment(
                cardTail = "", // bKash doesn't always show card tail in this message
                amountBdt = parseAmount(match.groupValues[1]),
                amountUsd = 0.0,
                paymentDate = datePattern.find(body)?.groupValues?.get(1)?.let { BankSmsParser.normalizeDate(it) },
                bankName = bankName
            )
        }

        // EBL card credit ("EBL CARDS: BDT 100 is credited to Card 423800**5428 ...").
        eblCardPaymentPattern.find(body)?.let { match ->
            return ParsedPayment(
                cardTail = match.groupValues[2],
                amountBdt = parseAmount(match.groupValues[1]),
                amountUsd = 0.0,
                paymentDate = datePattern.find(body)?.groupValues?.get(1)?.let { BankSmsParser.normalizeDate(it) },
                bankName = "EBL"
            )
        }

        bracTransferPattern.find(body)?.let { match ->
            val tail = extractCardTail(match.groupValues[2]) ?: return@let
            return ParsedPayment(
                cardTail = tail,
                amountBdt = parseAmount(match.groupValues[1]),
                amountUsd = 0.0,
                paymentDate = null,
                bankName = "BRAC Bank"
            )
        }

        val bdt = bdtReceivedPattern.find(body)?.groupValues?.get(1)
        val usd = usdReceivedPattern.find(body)?.groupValues?.get(1)
        if (bdt == null && usd == null) return null

        val cardMask = cardNoPattern.find(body)?.groupValues?.get(1)
        val tail = cardMask?.let { extractCardTail(it) } ?: return null

        return ParsedPayment(
            cardTail = tail,
            amountBdt = bdt?.let { parseAmount(it) } ?: 0.0,
            amountUsd = usd?.let { parseAmount(it) } ?: 0.0,
            paymentDate = datePattern.find(body)?.groupValues?.get(1)?.let { BankSmsParser.normalizeDate(it) },
            bankName = "City Bank"
        )
    }

    /** Last 3-4 digits of a masked card number: "376***945"→"945", "376948*****2945"→"2945". */
    private fun extractCardTail(mask: String): String? {
        val cleaned = mask.replace(Regex("""[^0-9*]"""), "")
        // Try to get digits after stars first
        Regex("""\*+(\d+)$""").find(cleaned)?.groupValues?.get(1)?.let { return it }
        // Fallback to last digits
        return Regex("""(\d+)$""").find(cleaned)?.groupValues?.get(1)?.let { 
            if (it.length > 4) it.takeLast(4) else it
        }
    }

    private fun parseAmount(amount: String): Double {
        return amount.replace(",", "").trim().toDoubleOrNull() ?: 0.0
    }

    fun paymentDateToEpoch(normalizedDate: String?): Long? {
        if (normalizedDate.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            sdf.isLenient = false
            sdf.parse(normalizedDate)?.time
        } catch (e: Exception) {
            null
        }
    }
}
