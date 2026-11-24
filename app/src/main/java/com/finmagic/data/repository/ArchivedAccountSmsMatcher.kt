package com.finmagic.data.repository

import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.sms.SmsClassifier
import com.finmagic.data.sms.parsers.AccountKind
import com.finmagic.data.sms.parsers.Institutions
import com.finmagic.data.sms.parsers.LedgerEntry
import com.finmagic.data.sms.parsers.SmsClassification

data class ArchivedAccountWithSms(
    val account: Account,
    val matchingSms: List<SmsRecord>
)

/**
 * Matches stored [SmsRecord] rows to an [Account] so archived accounts can
 * surface their stored SMS for inspection/deletion before permanent removal.
 */
object ArchivedAccountSmsMatcher {

    fun matchingSms(
        account: Account,
        allSms: List<SmsRecord>,
        linkedSmsIds: Set<Long> = emptySet()
    ): List<SmsRecord> {
        if (account.kind == Account.KIND_CASH) return emptyList()
        return allSms.filter { record -> matches(account, record, linkedSmsIds) }
    }

    fun matches(
        account: Account,
        record: SmsRecord,
        linkedSmsIds: Set<Long> = emptySet()
    ): Boolean {
        if (account.kind == Account.KIND_CASH) return false
        if (record.id > 0L && record.id in linkedSmsIds) return true

        return when (val result = SmsClassifier.classify(record.address, record.body, record.timestamp)) {
            is SmsClassification.AcctTxn -> entryMatchesAccount(result.entry, account)
            is SmsClassification.AtmWithdraw -> entryMatchesAccount(result.entry, account)
            is SmsClassification.WalletTxn -> entryMatchesAccount(result.entry, account)
            is SmsClassification.WalletToCash -> entryMatchesAccount(result.from, account)
            is SmsClassification.BillStatement,
            is SmsClassification.CardPayment,
            is SmsClassification.CardTxn,
            is SmsClassification.Noise -> false
            is SmsClassification.Unparsed -> fallbackTextMatch(account, record)
        }
    }

    private fun entryMatchesAccount(entry: LedgerEntry, account: Account): Boolean {
        if (!institutionMatches(account.bankName, entry.institution)) return false

        if (account.kind == Account.KIND_WALLET && entry.kind == AccountKind.WALLET) {
            if (entry.accountMask.isBlank() || entry.accountMask.equals("WALLET", ignoreCase = true)) {
                return true
            }
        }
        return maskMatches(account, entry.accountMask)
    }

    private fun institutionMatches(accountBankName: String, entryInstitution: String): Boolean {
        if (accountBankName.isBlank() || entryInstitution.isBlank()) return false
        if (accountBankName.equals(entryInstitution, ignoreCase = true)) return true
        val accountAlias = Institutions.accountName(accountBankName)
        val entryAlias = Institutions.accountName(entryInstitution)
        return accountAlias.equals(entryAlias, ignoreCase = true)
    }

    private fun maskMatches(account: Account, entryMask: String): Boolean {
        val accountMask = account.accountMask.trim()
        val parsedMask = entryMask.trim()
        if (accountMask.equals(parsedMask, ignoreCase = true)) return true
        if (accountMask.isBlank() && parsedMask.isBlank()) return true

        val acctDigits = accountMask.filter { it.isDigit() }
        val entryDigits = parsedMask.filter { it.isDigit() }

        if (account.bankName.equals(Institutions.PUBALI, ignoreCase = true) &&
            acctDigits.isNotEmpty() && entryDigits.isNotEmpty()
        ) {
            val normalizedAcct = acctDigits.takeLast(4).padStart(4, '0')
            val normalizedEntry = entryDigits.takeLast(4).padStart(4, '0')
            return normalizedAcct == normalizedEntry
        }

        if (acctDigits.length >= 3 && entryDigits.length >= 3) {
            if (acctDigits == entryDigits) return true
            if (acctDigits.length != entryDigits.length) {
                return acctDigits.endsWith(entryDigits) || entryDigits.endsWith(acctDigits)
            }
        }
        return false
    }

    private fun fallbackTextMatch(account: Account, record: SmsRecord): Boolean {
        val bank = account.bankName.trim()
        if (bank.isBlank()) return false

        val alias = Institutions.accountName(bank)
        val sender = record.address.orEmpty()
        val body = record.body
        val mentionsInstitution = sender.contains(bank, ignoreCase = true) ||
            body.contains(bank, ignoreCase = true) ||
            (alias.length >= 3 && (sender.contains(alias, ignoreCase = true) || body.contains(alias, ignoreCase = true)))
        if (!mentionsInstitution) return false

        val mask = account.accountMask.trim()
        if (mask.isNotEmpty() && body.contains(mask, ignoreCase = true)) return true

        val digits = mask.filter { it.isDigit() }
        if (digits.length < 3) return false
        val tail = digits.takeLast(minOf(4, digits.length))
        val pattern = Regex("""(?<![\d.])\**${Regex.escape(tail)}(?!\d)""")
        return pattern.containsMatchIn(body)
    }
}
