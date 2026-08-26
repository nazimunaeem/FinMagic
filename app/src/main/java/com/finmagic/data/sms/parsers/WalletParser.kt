package com.finmagic.data.sms.parsers

import java.util.Locale

/**
 * Mobile-wallet ledger (plan §6 #4): bKash (all verbs), Nagad Money Received /
 * Send Money / Payment, Rocket. Wallet "Cash Out" maps to a wallet→Cash transfer.
 *
 *  bKash:  "Cash In Tk 1,540.00 from 018… successful. Fee Tk 0.00. Balance Tk 1,540.06. TrxID … at 26/12/2024 11:51"
 *  bKash:  "Payment of Tk 406.00 to Sonali Bank Limited-RM4513 is successful. Balance Tk 79.06. TrxID …"
 *  Nagad:  "Money Received. | Amount: Tk 2500.00 | Sender: 013… | Ref: N/A | TxnID: 750TFZM0 | Balance: Tk 2500.69 | 05/03/2026 11:52"
 */
object WalletParser {

    private val bKashBalance = Regex("""Balance\s+(?:is\s+)?(?:Tk|TK|BDT)\.?\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val bKashDate = Regex("""at\s+(\d{1,2}/\d{1,2}/\d{2,4})\s+(\d{1,2}:\d{2})""")

    private val cashIn = Regex(
        """Cash\s+In\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+from\s+(.+?)\s+successful""",
        RegexOption.IGNORE_CASE
    )
    private val payment = Regex(
        """Payment\s+of\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+to\s+(.+?)\s+is\s+successful""",
        RegexOption.IGNORE_CASE
    )
    private val sendMoney = Regex(
        """Send\s+Money\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+to\s+(.+?)\.\s*(?:Fee|Balance)""",
        RegexOption.IGNORE_CASE
    )
    private val receivedMoney = Regex(
        """(?:You\s+have\s+)?received\s+(?:deposit\s+from\s+\S+\s+of\s+)?(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+from\s+(.+?)\.""",
        RegexOption.IGNORE_CASE
    )
    private val billPay = Regex(
        """Bill\s+Pay\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+to\s+(.+?)\.\s*(?:Fee|Balance)""",
        RegexOption.IGNORE_CASE
    )
    private val cashOut = Regex(
        """Cash\s+Out\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+(?:to|from)\s+(.+?)\.\s*(?:Fee|Balance)""",
        RegexOption.IGNORE_CASE
    )

    /** Nagad pipe format: "Nagad: Money Received. | Amount: Tk 2500.00 | … | Balance: Tk 2500.69 | 05/03/2026 11:52". */
    private val nagadVerb = Regex("""^(?:\s*\w+\s*:)?\s*([A-Za-z ]{3,30}?)\.\s*(?:\||\n)""")
    private val nagadAmount = Regex("""Amount:\s*(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val nagadBalance = Regex("""Balance:\s*(?:Tk|TK|BDT)?\.?\s*:?\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val nagadBankAddMoney = Regex(
        """Add\s+Money\s+from\s+Bank\s+is\s+Successful.*?Amount:\s*(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?).*?Balance:\s*(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )

    fun matches(sender: String?, body: String): Boolean {
        if (LedgerText.looksLikeNoise(body)) return false
        val s = sender?.trim()?.lowercase(Locale.US) ?: ""
        val b = body.lowercase(Locale.US)
        val namesAnotherInstitution = b.contains("brac bank") || b.contains("bracbank") ||
            s.contains("brac") || b.contains("ebl") || b.contains("pubali") || b.contains("city bank")
        val hasRocketWalletTemplate = rocketWalletTemplate.containsMatchIn(body) && b.contains("balance")
        val isSharedRocket = s == "16216" && rocketSharedBalance.containsMatchIn(body) &&
            rocketSharedTransaction.containsMatchIn(body)
        return s == "bkash" || s.contains("bkash") ||
            s.contains("nagad") || s.contains("rocket") ||
            isSharedRocket ||
            (!namesAnotherInstitution && (b.contains("rocket") || hasRocketWalletTemplate) && b.contains("balance"))
    }

    fun classify(sender: String?, body: String, smsTime: Long, subscriptionId: Int? = null): SmsClassification? {
        if (!matches(sender, body)) return null
        classifyBkash(sender, body, smsTime, subscriptionId)?.let { return it }
        classifyNagad(sender, body, subscriptionId)?.let { return it }
        classifyRocket(sender, body, subscriptionId)?.let { return it }
        return null
    }

    private fun bkashOccurredAt(body: String, fallback: Long): Long {
        bKashDate.find(body)?.let { m ->
            val normalized = com.finmagic.data.sms.BankSmsParser.normalizeDate(m.groupValues[1])
            com.finmagic.data.sms.SmsDateParser.dayMillisFromNormalized(normalized)?.let { day ->
                val parts = m.groupValues[2].split(":")
                val hour = parts.getOrNull(0)?.toIntOrNull() ?: return day
                val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = day
                cal.set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
                cal.set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
        }
        return fallback
    }

    private fun walletBalance(body: String): Double? =
        (bKashBalance.find(body) ?: nagadBalance.find(body))?.groupValues?.get(1)
            ?.let { LedgerText.parseAmount(it) }

    private fun classifyBkash(sender: String?, body: String, smsTime: Long, subscriptionId: Int?): SmsClassification? {
        val s = sender?.trim()?.lowercase(java.util.Locale.US) ?: ""
        if (!s.contains("bkash") && !body.contains("bKash", ignoreCase = true) && !body.contains("bKa.sh", ignoreCase = true)) return null

        data class Verb(val regex: Regex, val direction: LedgerDirection?, val category: String)

        // direction==null marks Cash Out → wallet→Cash transfer.
        val verbs = listOf(
            Verb(cashOut, null, TxnCategory.CASH_IN),
            Verb(cashIn, LedgerDirection.IN, TxnCategory.CREDIT),
            Verb(receivedMoney, LedgerDirection.IN, TxnCategory.CREDIT),
            Verb(payment, LedgerDirection.OUT, TxnCategory.PAYMENT),
            Verb(sendMoney, LedgerDirection.OUT, TxnCategory.FUND_TRANSFER),
            Verb(billPay, LedgerDirection.OUT, TxnCategory.BILL_PAY)
        )

        for (verb in verbs) {
            val m = verb.regex.find(body) ?: continue
            val amount = LedgerText.parseAmount(m.groupValues[1]) ?: continue
            val counterparty = m.groupValues[2].trim().take(60)
            val occurredAt = bkashOccurredAt(body, smsTime)

            if (verb.direction == null) {
                // bKash Cash Out → wallet OUT + Cash IN transfer.
                val from = LedgerEntry(
                    institution = Institutions.BKASH,
                    kind = AccountKind.WALLET,
                    accountMask = "WALLET",
                    direction = LedgerDirection.OUT,
                    amount = amount,
                    balanceAfter = walletBalance(body),
                    occurredAt = occurredAt,
                    category = TxnCategory.FUND_TRANSFER,
                    counterparty = "Cash Out: $counterparty",
                    subscriptionId = subscriptionId
                )
                val to = LedgerEntry(
                    institution = "CASH",
                    kind = AccountKind.CASH,
                    accountMask = "CASH",
                    direction = LedgerDirection.IN,
                    amount = amount,
                    balanceAfter = null,
                    occurredAt = occurredAt,
                    category = TxnCategory.RECEIVED,
                    counterparty = "${Institutions.BKASH} Cash Out"
                )
                return SmsClassification.WalletToCash(from, to)
            }

            return SmsClassification.WalletTxn(
                LedgerEntry(
                    institution = Institutions.BKASH,
                    kind = AccountKind.WALLET,
                    accountMask = "WALLET",
                    direction = verb.direction,
                    amount = amount,
                    balanceAfter = walletBalance(body),
                    occurredAt = occurredAt,
                    category = verb.category,
                    counterparty = counterparty,
                    subscriptionId = subscriptionId
                )
            )
        }
        return null
    }

    private fun classifyNagad(sender: String?, body: String, subscriptionId: Int?): SmsClassification? {
        val s = sender?.trim()?.lowercase(java.util.Locale.US) ?: ""
        if (!s.contains("nagad") && !body.contains("Nagad", ignoreCase = true)) return null

        nagadBankAddMoney.find(body)?.let { match ->
            return SmsClassification.WalletTxn(
                LedgerEntry(
                    institution = Institutions.NAGAD,
                    kind = AccountKind.WALLET,
                    accountMask = "WALLET",
                    direction = LedgerDirection.IN,
                    amount = LedgerText.parseAmount(match.groupValues[1]) ?: return null,
                    balanceAfter = LedgerText.parseAmount(match.groupValues[2]),
                    occurredAt = LedgerText.parseOccurredAt(body),
                    category = TxnCategory.CREDIT,
                    counterparty = Regex("""From:\s*(.+?)\s+Amount:""", RegexOption.IGNORE_CASE)
                        .find(body)?.groupValues?.get(1)?.trim(),
                    subscriptionId = subscriptionId
                )
            )
        }

        val verbMatch = nagadVerb.find(body) ?: return null
        val amount = nagadAmount.find(body)?.groupValues?.get(1)
            ?.let { LedgerText.parseAmount(it) } ?: return null

        val verb = verbMatch.groupValues[1].lowercase(Locale.US)
        val (direction, category) = when {
            verb.contains("received") -> LedgerDirection.IN to TxnCategory.CREDIT
            else -> LedgerDirection.OUT to TxnCategory.FUND_TRANSFER
        }

        return SmsClassification.WalletTxn(
            LedgerEntry(
                institution = Institutions.NAGAD,
                kind = AccountKind.WALLET,
                accountMask = "WALLET",
                direction = direction,
                amount = amount,
                balanceAfter = walletBalance(body),
                occurredAt = LedgerText.parseOccurredAt(body),
                category = category,
                counterparty = Regex("""Sender:\s*(\S+)""", RegexOption.IGNORE_CASE)
                    .find(body)?.groupValues?.get(1),
                subscriptionId = subscriptionId
            )
        )
    }
}


private val rocketWalletTemplate = Regex(
    "cash\\s+(?:in|out)|send\\s+money|bill\\s+pay|payment\\s+of|received\\s+(?:deposit|money)",
    RegexOption.IGNORE_CASE
)

private val rocketSharedBalance = Regex("""NetBal\s*:\s*(?:Tk|TK|BDT)\.?\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
private val rocketSharedTransaction = Regex("""TxnId\s*:\s*\S+\s+Date\s*:""", RegexOption.IGNORE_CASE)

    private val rocketBalance = Regex("""Balance\s+(?:Tk|TK|BDT)?\.?\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val rocketAmount = Regex("""(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

    private fun classifyRocket(sender: String?, body: String, subscriptionId: Int?): SmsClassification? {
        val s = sender?.trim()?.lowercase(java.util.Locale.US) ?: ""
        val b = body.lowercase(java.util.Locale.US)
        if (!s.contains("rocket") && !b.contains("rocket") &&
            !(rocketWalletTemplate.containsMatchIn(body) && b.contains("balance")) &&
            !(s == "16216" && rocketSharedBalance.containsMatchIn(body) && rocketSharedTransaction.containsMatchIn(body))) return null

        val amount = rocketAmount.find(body)?.groupValues?.get(1)?.let { LedgerText.parseAmount(it) } ?: return null
        val balance = (rocketBalance.find(body)?.groupValues?.get(1)
            ?: rocketSharedBalance.find(body)?.groupValues?.get(1))?.let { LedgerText.parseAmount(it) }

        val direction = if (body.contains("received", ignoreCase = true) || body.contains("credited", ignoreCase = true)) {
            LedgerDirection.IN
        } else {
            LedgerDirection.OUT
        }

        val category = if (direction == LedgerDirection.IN) TxnCategory.CREDIT else TxnCategory.FUND_TRANSFER

        return SmsClassification.WalletTxn(
            LedgerEntry(
                institution = Institutions.ROCKET,
                kind = AccountKind.WALLET,
                accountMask = "WALLET",
                direction = direction,
                amount = amount,
                balanceAfter = balance,
                occurredAt = LedgerText.parseOccurredAt(body),
                category = category,
                counterparty = null,
                subscriptionId = subscriptionId
            )
        )
    }
