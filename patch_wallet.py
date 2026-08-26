import re

with open('app/src/main/java/com/finmagic/data/sms/parsers/WalletParser.kt', 'r') as f:
    content = f.read()

# Update classify to pass sender
content = content.replace(
    'classifyBkash(body, smsTime, subscriptionId)?.let { return it }',
    'classifyBkash(sender, body, smsTime, subscriptionId)?.let { return it }'
)

content = content.replace(
    'classifyNagad(body, subscriptionId)?.let { return it }',
    'classifyNagad(sender, body, subscriptionId)?.let { return it }'
)

# Update classifyBkash definition
content = content.replace(
    'private fun classifyBkash(body: String, smsTime: Long, subscriptionId: Int?): SmsClassification? {',
    'private fun classifyBkash(sender: String?, body: String, smsTime: Long, subscriptionId: Int?): SmsClassification? {'
)
content = content.replace(
    'if (!body.contains("bKash", ignoreCase = true)) return null',
    'val s = sender?.trim()?.lowercase(java.util.Locale.US) ?: ""\n        if (!s.contains("bkash") && !body.contains("bKash", ignoreCase = true) && !body.contains("bKa.sh", ignoreCase = true)) return null'
)

# Update classifyNagad definition
content = content.replace(
    'private fun classifyNagad(body: String, subscriptionId: Int?): SmsClassification? {',
    'private fun classifyNagad(sender: String?, body: String, subscriptionId: Int?): SmsClassification? {'
)
content = content.replace(
    'if (!body.contains("Nagad", ignoreCase = true)) return null',
    'val s = sender?.trim()?.lowercase(java.util.Locale.US) ?: ""\n        if (!s.contains("nagad") && !body.contains("Nagad", ignoreCase = true)) return null'
)

# Fix receivedMoney regex for bKash deposit: "You have received deposit from iBanking of Tk 494.00 from BRAC Bank Internet Banking."
# Old: """(?:You\s+have\s+)?received\s+(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+from\s+(.+?)\."""
# New: """(?:You\s+have\s+)?received\s+(?:deposit\s+from\s+\S+\s+of\s+)?(?:Tk|TK|BDT)\s*([\d,]+(?:\.\d+)?)\s+from\s+(.+?)\."""
content = content.replace(
    '"""(?:You\\s+have\\s+)?received\\s+(?:Tk|TK|BDT)\\s*([\\d,]+(?:\\.\\d+)?)\\s+from\\s+(.+?)\\."""',
    '"""(?:You\\s+have\\s+)?received\\s+(?:deposit\\s+from\\s+\\S+\\s+of\\s+)?(?:Tk|TK|BDT)\\s*([\\d,]+(?:\\.\\d+)?)\\s+from\\s+(.+?)\\."""'
)

with open('app/src/main/java/com/finmagic/data/sms/parsers/WalletParser.kt', 'w') as f:
    f.write(content)
