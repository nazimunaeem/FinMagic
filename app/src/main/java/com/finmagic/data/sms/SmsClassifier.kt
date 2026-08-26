package com.finmagic.data.sms

import com.finmagic.data.sms.parsers.AiblParser
import com.finmagic.data.sms.parsers.BracParser
import com.finmagic.data.sms.parsers.CardPosParser
import com.finmagic.data.sms.parsers.CityBankParser
import com.finmagic.data.sms.parsers.DbblParser
import com.finmagic.data.sms.parsers.GenericLedgerParser
import com.finmagic.data.sms.parsers.IbblParser
import com.finmagic.data.sms.parsers.LedgerText
import com.finmagic.data.sms.parsers.PubaliParser
import com.finmagic.data.sms.parsers.SmsClassification
import com.finmagic.data.sms.parsers.WalletParser

/**
 * Routes every candidate SMS to exactly one [SmsClassification] (plan §2.1).
 *
 * Order matters: cheap noise/bill/payment checks first, then institution-specific
 * parsers, and the generic ledger parser last so it only sees leftovers.
 */
object SmsClassifier {

    /** Bangla-script service notices are noise even when they mention ATM/EBL (plan §8). */
    private val banglaChars = Regex("""[\u0980-\u09FF]""")

    fun classify(
        sender: String?,
        body: String,
        smsTime: Long,
        subscriptionId: Int? = null
    ): SmsClassification {
        // 1. Noise first (cheapest): OTP/marketing/results, Bangla notices.
        if (LedgerText.looksLikeNoise(body) || banglaChars.containsMatchIn(body)) {
            return SmsClassification.Noise
        }

        // 2. Bill statements → existing bill insert path.
        BankSmsParser.parse(body, sender)?.let { return SmsClassification.BillStatement(it) }

        // 3. Payment received / transfer-to-card → auto-paid engine.
        PaymentSmsParser.parse(body)?.let { return SmsClassification.CardPayment(it) }

        // 3.5 Card POS purchases (City/BRAC/PUBLI/EBL) → stored as CardTransaction.
        CardPosParser.classify(sender, body, smsTime)?.let { return it }

        // 4. Institution-specific ledgers.
        WalletParser.classify(sender, body, smsTime, subscriptionId)?.let { return it }
        CityBankParser.classify(sender, body, smsTime)?.let { return it }
        BracParser.classify(sender, body, smsTime)?.let { return it }
        PubaliParser.classify(sender, body, smsTime)?.let { return it }
        DbblParser.classify(sender, body, smsTime)?.let { return it }
        IbblParser.classify(sender, body, smsTime)?.let { return it }
        AiblParser.classify(sender, body, smsTime)?.let { return it }

        // 5. Generic ledger last (BBKSRSL-style, shared C/B format, bank→wallet).
        GenericLedgerParser.classify(sender, body, smsTime)?.let { return it }

        return SmsClassification.Unparsed
    }
}
