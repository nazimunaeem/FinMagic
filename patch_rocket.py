import re

with open('app/src/main/java/com/finmagic/data/sms/parsers/WalletParser.kt', 'r') as f:
    content = f.read()

# Add classifyRocket logic
rocket_code = """
    private val rocketBalance = Regex(\"\"\"Balance\\s+(?:Tk|TK|BDT)?\\.?\\s*([\\d,]+(?:\\.\\d+)?)\"\"\", RegexOption.IGNORE_CASE)
    private val rocketAmount = Regex(\"\"\"(?:Tk|TK|BDT)\\s*([\\d,]+(?:\\.\\d+)?)\"\"\", RegexOption.IGNORE_CASE)

    private fun classifyRocket(sender: String?, body: String, subscriptionId: Int?): SmsClassification? {
        val s = sender?.trim()?.lowercase(java.util.Locale.US) ?: ""
        val b = body.lowercase(java.util.Locale.US)
        if (!s.contains("rocket") && !(b.contains("rocket") && b.contains("balance"))) return null

        val amount = rocketAmount.find(body)?.groupValues?.get(1)?.let { LedgerText.parseAmount(it) } ?: return null
        val balance = rocketBalance.find(body)?.groupValues?.get(1)?.let { LedgerText.parseAmount(it) }

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
"""

if 'fun classifyRocket' not in content:
    content = content.replace(
        'classifyNagad(sender, body, subscriptionId)?.let { return it }',
        'classifyNagad(sender, body, subscriptionId)?.let { return it }\n        classifyRocket(sender, body, subscriptionId)?.let { return it }'
    )
    content = content + "\n" + rocket_code

with open('app/src/main/java/com/finmagic/data/sms/parsers/WalletParser.kt', 'w') as f:
    f.write(content)
