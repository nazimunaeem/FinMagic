package com.finmagic.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.finmagic.data.local.AppDatabase
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.AccountTransactionDao
import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.repository.AccountRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class SavedCsvBackup(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long
)

data class CsvRestoreResult(
    val accountsRestored: Int,
    val transactionsRestored: Int,
    val cardTransactionsRestored: Int,
    val billsRestored: Int,
    val skippedDuplicates: Int
) {
    val totalRestored: Int
        get() = accountsRestored + transactionsRestored + cardTransactionsRestored + billsRestored
}

@Singleton
class CsvBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val accountDao: AccountDao,
    private val txnDao: AccountTransactionDao,
    private val creditCardDao: CreditCardDao,
    private val cardTransactionDao: CardTransactionDao,
    private val billDao: BillDao,
    private val accountRepository: AccountRepository
) {

    fun listSavedBackups(): List<SavedCsvBackup> {
        return context.filesDir
            .listFiles { file -> file.isFile && file.extension.equals("csv", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                SavedCsvBackup(
                    file = it,
                    name = it.name,
                    sizeBytes = it.length(),
                    lastModified = it.lastModified()
                )
            }
            .orEmpty()
    }

    fun deleteBackupFile(file: File): Boolean {
        return if (file.exists() && file.parentFile == context.filesDir) file.delete() else false
    }

    suspend fun exportBackup(): File = withContext(Dispatchers.IO) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val outFile = File(context.filesDir, "finmagic_export_$timestamp.csv")

        val accounts = accountDao.getAllIncludingArchivedOnce()
        val accountsById = accounts.associateBy { it.id }
        val allTxns = accounts.flatMap { acct ->
            txnDao.getAllByAccountOnce(acct.id)
                .filter { it.category != "DirectSet" }
                .map { it to (accountsById[it.accountId]?.displayName ?: "Account") }
        }.sortedByDescending { it.first.occurredAt }
        val cardTxns = cardTransactionDao.getAllOnce()
        val bills = billDao.getAllBillsWithCards().first()

        outFile.bufferedWriter().use { writer ->
            writer.appendLine(
                csvRow(
                    "Record Type",
                    "Date",
                    "Account / Card",
                    "Category / Period",
                    "Direction / Status",
                    "Amount",
                    "Currency",
                    "Balance / Min Due",
                    "Counterparty / Merchant",
                    "Source / Due Date",
                    "Extra1",
                    "Extra2"
                )
            )

            for (acct in accounts) {
                val txns = txnDao.getAllByAccountOnce(acct.id)
                val balance = if (acct.kind == Account.KIND_CASH) {
                    AccountRepository.effectiveBalance(txns)
                } else {
                    AccountRepository.computeAccountBalance(txns)
                }
                writer.appendLine(
                    csvRow(
                        "ACCOUNT",
                        "",
                        "${acct.displayName} (${acct.accountMask})",
                        acct.kind,
                        if (acct.includeInTotal) "INCLUDED" else "EXCLUDED",
                        String.format(Locale.US, "%.2f", balance),
                        acct.currency,
                        acct.accountType,
                        acct.bankName,
                        if (acct.isArchived) "Archived" else "Active account",
                        if (acct.isLiability) "LIABILITY" else "ASSET",
                        acct.id.toString()
                    )
                )
            }

            for ((txn, accountName) in allTxns) {
                writer.appendLine(
                    csvRow(
                        "ACCOUNT_TXN",
                        dateTimeFmt.format(Date(txn.occurredAt)),
                        accountName,
                        txn.category ?: "Uncategorized",
                        txn.direction,
                        String.format(Locale.US, "%.2f", txn.amount),
                        txn.currency,
                        txn.balanceAfter?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                        txn.counterparty ?: "",
                        if (txn.isManual) "MANUAL" else "SMS",
                        txn.occurredAt.toString(),
                        txn.isExpenseOverride.toString()
                    )
                )
            }

            for (cardTx in cardTxns) {
                val cardLabel = listOfNotNull(
                    cardTx.bankName.takeIf { it.isNotBlank() },
                    "•••• ${cardTx.cardTail}"
                ).joinToString(" ")
                writer.appendLine(
                    csvRow(
                        "CARD_TXN",
                        dateTimeFmt.format(Date(cardTx.occurredAt)),
                        cardLabel,
                        cardTx.category ?: "Card Spend",
                        "OUT",
                        String.format(Locale.US, "%.2f", cardTx.amount),
                        "BDT",
                        cardTx.balanceAfter?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                        cardTx.merchant,
                        if (cardTx.smsBody.startsWith("manual:")) "MANUAL" else "SMS",
                        cardTx.occurredAt.toString(),
                        cardTx.cardTail
                    )
                )
            }

            for (bill in bills) {
                val status = when {
                    bill.isPaid -> "PAID"
                    bill.paidAmount > 0 -> "PARTIAL (paid ${String.format(Locale.US, "%.2f", bill.paidAmount)})"
                    else -> "UNPAID"
                }
                writer.appendLine(
                    csvRow(
                        "CARD_BILL",
                        dateTimeFmt.format(Date(bill.detectedAt)),
                        "${bill.bankName} (•••• ${bill.cardLast4})",
                        bill.billPeriod,
                        status,
                        String.format(Locale.US, "%.2f", bill.totalDue),
                        bill.currency,
                        String.format(Locale.US, "%.2f", bill.minDue),
                        bill.cardNickname,
                        "Due: ${bill.dueDate}",
                        String.format(Locale.US, "%.2f", bill.paidAmount),
                        if (bill.isSuperseded) "SUPERSEDED" else "ACTIVE"
                    )
                )
            }
        }
        outFile
    }

    suspend fun copyFileToUri(source: File, destination: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(destination)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: return@withContext false
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun restoreFromFile(file: File): CsvRestoreResult = withContext(Dispatchers.IO) {
        val text = file.readText(Charsets.UTF_8)
        restoreCsvText(text)
    }

    suspend fun restoreFromUri(uri: Uri): CsvRestoreResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
            it.readText()
        } ?: throw IllegalStateException("Could not open selected CSV file")
        restoreCsvText(text)
    }

    suspend fun restoreCsvText(rawCsv: String): CsvRestoreResult {
        val cleaned = rawCsv.removePrefix("\uFEFF")
        val rows = parseCsvRecords(cleaned)
        if (rows.isEmpty()) throw IllegalArgumentException("CSV file is empty")

        val header = rows.first().map { it.trim().lowercase(Locale.US) }
        val isFullBackup = header.firstOrNull()?.contains("record type") == true
        val isSingleLedger = header.firstOrNull() == "date" && header.any { it == "direction" }

        if (!isFullBackup && !isSingleLedger) {
            throw IllegalArgumentException("Unrecognized CSV header. Select a FinMagic backup or ledger CSV.")
        }

        if (isSingleLedger) {
            return restoreSingleLedgerRows(rows.drop(1))
        }
        return restoreFullBackupRows(rows.drop(1))
    }

    private suspend fun restoreSingleLedgerRows(dataRows: List<List<String>>): CsvRestoreResult {
        var txnsAdded = 0
        var skipped = 0
        database.withTransaction {
            val cash = accountRepository.ensureCashAccount()
            val existingTxns = txnDao.getAllByAccountOnce(cash.id)
            for (cols in dataRows) {
                if (cols.size < 3) continue
                val dateMillis = parseDateTime(cols.getOrNull(0)) ?: System.currentTimeMillis()
                val direction = if (cols.getOrNull(1).equals("IN", ignoreCase = true)) {
                    AccountTransaction.DIRECTION_IN
                } else {
                    AccountTransaction.DIRECTION_OUT
                }
                val amount = cols.getOrNull(2)?.replace(",", "")?.toDoubleOrNull() ?: continue
                if (amount <= 0.0) continue
                val currency = cols.getOrNull(3)?.ifBlank { "BDT" } ?: "BDT"
                val balAfter = cols.getOrNull(4)?.replace(",", "")?.toDoubleOrNull()
                val category = cols.getOrNull(5)?.ifBlank { null }
                val counterparty = cols.getOrNull(6)?.ifBlank { null }

                val dup = existingTxns.any {
                    abs(it.occurredAt - dateMillis) < 120_000L &&
                        it.direction == direction &&
                        abs(it.amount - amount) < 0.01 &&
                        (it.counterparty ?: "") == (counterparty ?: "")
                }
                if (dup) {
                    skipped++
                    continue
                }
                txnDao.insert(
                    AccountTransaction(
                        accountId = cash.id,
                        direction = direction,
                        amount = amount,
                        currency = currency,
                        balanceAfter = balAfter,
                        occurredAt = dateMillis,
                        category = category,
                        counterparty = counterparty,
                        isManual = true
                    )
                )
                txnsAdded++
            }
        }
        return CsvRestoreResult(0, txnsAdded, 0, 0, skipped)
    }

    private suspend fun restoreFullBackupRows(dataRows: List<List<String>>): CsvRestoreResult {
        var accountsRestored = 0
        var txnsRestored = 0
        var cardTxnsRestored = 0
        var billsRestored = 0
        var skipped = 0

        val accountRows = dataRows.filter { it.getOrNull(0)?.trim().equals("ACCOUNT", ignoreCase = true) }
        val txnRows = dataRows.filter { it.getOrNull(0)?.trim().equals("ACCOUNT_TXN", ignoreCase = true) }
        val cardTxnRows = dataRows.filter { it.getOrNull(0)?.trim().equals("CARD_TXN", ignoreCase = true) }
        val billRows = dataRows.filter { it.getOrNull(0)?.trim().equals("CARD_BILL", ignoreCase = true) }

        val accountBalancesToSeed = mutableListOf<Pair<Long, Double>>()

        database.withTransaction {
            val allExistingAccounts = accountDao.getAllIncludingArchivedOnce().toMutableList()

            suspend fun findOrCreateAccountByLabel(
                rawLabel: String,
                kindHint: String = Account.KIND_BANK,
                bankNameHint: String = "",
                currencyHint: String = "BDT",
                includeInTotal: Boolean = true,
                accountTypeHint: String? = null,
                isLiabilityHint: Boolean = false
            ): Account {
                val (parsedName, parsedMask) = parseAccountNameAndMask(rawLabel)
                if (kindHint.equals(Account.KIND_CASH, ignoreCase = true) ||
                    parsedName.equals("Cash", ignoreCase = true) ||
                    parsedMask.equals(Account.CASH_MASK, ignoreCase = true)
                ) {
                    val cash = accountRepository.ensureCashAccount()
                    if (allExistingAccounts.none { it.id == cash.id }) allExistingAccounts.add(cash)
                    return cash
                }

                val match = allExistingAccounts.firstOrNull { acct ->
                    (parsedMask.isNotBlank() && acct.accountMask.equals(parsedMask, ignoreCase = true)) ||
                        acct.name.equals(parsedName, ignoreCase = true) ||
                        acct.displayName.equals(parsedName, ignoreCase = true)
                }
                if (match != null) {
                    if (match.isArchived) accountDao.setArchived(match.id, false)
                    if (!accountTypeHint.isNullOrBlank()) {
                        accountDao.updateType(match.id, accountTypeHint, isLiabilityHint)
                    }
                    accountDao.setIncludeInTotal(match.id, includeInTotal)
                    return accountDao.findByIdOnce(match.id) ?: match
                }

                val finalMask = parsedMask.ifBlank { "RESTORED-${parsedName.hashCode().toUInt()}" }
                val finalType = accountTypeHint?.takeIf { it.isNotBlank() }
                    ?: if (kindHint.equals(Account.KIND_CASH, true)) Account.TYPE_CASH else Account.TYPE_SAVINGS
                val id = accountDao.insert(
                    Account(
                        name = parsedName.ifBlank { "Restored Account" },
                        kind = kindHint.ifBlank { Account.KIND_BANK },
                        bankName = bankNameHint.ifBlank { parsedName },
                        accountMask = finalMask,
                        currency = currencyHint.ifBlank { "BDT" },
                        includeInTotal = includeInTotal,
                        accountType = finalType,
                        isLiability = isLiabilityHint
                    )
                )
                val inserted = if (id > 0L) {
                    accountDao.findByIdOnce(id)
                } else {
                    accountDao.findByInstitutionAndMask(bankNameHint.ifBlank { parsedName }, finalMask)
                }
                val resolved = inserted ?: allExistingAccounts.first()
                if (allExistingAccounts.none { it.id == resolved.id }) {
                    allExistingAccounts.add(resolved)
                }
                accountsRestored++
                return resolved
            }

            // 1. Restore accounts
            for (cols in accountRows) {
                val label = cols.getOrNull(2).orEmpty()
                val kind = cols.getOrNull(3)?.ifBlank { Account.KIND_BANK } ?: Account.KIND_BANK
                val includeInTotal = !cols.getOrNull(4).equals("EXCLUDED", ignoreCase = true)
                val balance = cols.getOrNull(5)?.replace(",", "")?.toDoubleOrNull()
                val currency = cols.getOrNull(6)?.ifBlank { "BDT" } ?: "BDT"
                val accountType = cols.getOrNull(7)?.takeIf { it.isNotBlank() }
                val bankName = cols.getOrNull(8).orEmpty()
                val isArchived = cols.getOrNull(9).equals("Archived", ignoreCase = true)
                val isLiability = cols.getOrNull(10).equals("LIABILITY", ignoreCase = true) ||
                    accountType.equals(Account.TYPE_LOAN, ignoreCase = true)

                val account = findOrCreateAccountByLabel(
                    rawLabel = label,
                    kindHint = kind,
                    bankNameHint = bankName,
                    currencyHint = currency,
                    includeInTotal = includeInTotal,
                    accountTypeHint = accountType,
                    isLiabilityHint = isLiability
                )
                if (isArchived && account.kind != Account.KIND_CASH) {
                    accountDao.setArchived(account.id, true)
                }
                if (balance != null) {
                    accountBalancesToSeed.add(account.id to balance)
                }
            }

            // 2. Restore account transactions
            val existingTxnsCache = mutableMapOf<Long, MutableList<AccountTransaction>>()
            for (cols in txnRows) {
                val occurredAt = cols.getOrNull(10)?.toLongOrNull()
                    ?: parseDateTime(cols.getOrNull(1))
                    ?: System.currentTimeMillis()
                val accountLabel = cols.getOrNull(2).orEmpty().ifBlank { "Cash" }
                val category = cols.getOrNull(3)?.ifBlank { null }
                val direction = if (cols.getOrNull(4).equals("IN", ignoreCase = true)) {
                    AccountTransaction.DIRECTION_IN
                } else {
                    AccountTransaction.DIRECTION_OUT
                }
                val amount = cols.getOrNull(5)?.replace(",", "")?.toDoubleOrNull() ?: continue
                if (amount <= 0.0) continue
                val currency = cols.getOrNull(6)?.ifBlank { "BDT" } ?: "BDT"
                val balanceAfter = cols.getOrNull(7)?.replace(",", "")?.toDoubleOrNull()
                val counterparty = cols.getOrNull(8)?.ifBlank { null }
                val isManual = !cols.getOrNull(9).equals("SMS", ignoreCase = true)
                val expenseOverride = cols.getOrNull(11)?.toIntOrNull() ?: 0

                val account = findOrCreateAccountByLabel(accountLabel)
                val list = existingTxnsCache.getOrPut(account.id) {
                    txnDao.getAllByAccountOnce(account.id).toMutableList()
                }
                val duplicate = list.any {
                    abs(it.occurredAt - occurredAt) < 120_000L &&
                        it.direction == direction &&
                        abs(it.amount - amount) < 0.01 &&
                        (it.counterparty ?: "") == (counterparty ?: "") &&
                        (it.category ?: "") == (category ?: "")
                }
                if (duplicate) {
                    skipped++
                    continue
                }
                val entity = AccountTransaction(
                    accountId = account.id,
                    direction = direction,
                    amount = amount,
                    currency = currency,
                    balanceAfter = balanceAfter,
                    occurredAt = occurredAt,
                    category = category,
                    counterparty = counterparty,
                    isManual = isManual,
                    isExpenseOverride = expenseOverride
                )
                val id = txnDao.insert(entity)
                if (id > 0L) {
                    list.add(entity.copy(id = id))
                    txnsRestored++
                }
            }

            // Seed balances for accounts that have no transactions yet (so restoring an empty/older backup preserves balances)
            for ((accountId, targetBal) in accountBalancesToSeed) {
                val existing = txnDao.getAllByAccountOnce(accountId)
                if (existing.isEmpty() && abs(targetBal) >= 0.01) {
                    val acct = accountDao.findByIdOnce(accountId)
                    txnDao.insert(
                        AccountTransaction(
                            accountId = accountId,
                            direction = if (targetBal >= 0) AccountTransaction.DIRECTION_IN else AccountTransaction.DIRECTION_OUT,
                            amount = abs(targetBal),
                            balanceAfter = if (acct?.kind != Account.KIND_CASH) targetBal else null,
                            occurredAt = System.currentTimeMillis(),
                            category = "Restored Balance",
                            counterparty = "CSV Backup Restore",
                            isManual = true
                        )
                    )
                    txnsRestored++
                }
            }

            // 3. Restore card transactions
            val existingCardTxns = cardTransactionDao.getAllOnce().toMutableList()
            for (cols in cardTxnRows) {
                val occurredAt = cols.getOrNull(10)?.toLongOrNull()
                    ?: parseDateTime(cols.getOrNull(1))
                    ?: System.currentTimeMillis()
                val cardLabel = cols.getOrNull(2).orEmpty()
                val (bankName, tail) = parseCardBankAndTail(cardLabel, cols.getOrNull(11))
                val category = cols.getOrNull(3)?.ifBlank { null }
                val amount = cols.getOrNull(5)?.replace(",", "")?.toDoubleOrNull() ?: continue
                if (amount <= 0.0) continue
                val balAfter = cols.getOrNull(7)?.replace(",", "")?.toDoubleOrNull()
                val merchant = cols.getOrNull(8)?.ifBlank { "Card Spend" } ?: "Card Spend"

                val duplicate = existingCardTxns.any {
                    it.cardTail == tail &&
                        abs(it.occurredAt - occurredAt) < 120_000L &&
                        abs(it.amount - amount) < 0.01 &&
                        it.merchant == merchant
                }
                if (duplicate) {
                    skipped++
                    continue
                }
                val smsBodyKey = "backup:${bankName}_${tail}_${occurredAt}_${amount}_${merchant.hashCode()}"
                val entity = CardTransaction(
                    cardTail = tail,
                    bankName = bankName,
                    merchant = merchant,
                    amount = amount,
                    occurredAt = occurredAt,
                    balanceAfter = balAfter,
                    category = category,
                    smsBody = smsBodyKey
                )
                val insertedId = cardTransactionDao.insert(entity)
                if (insertedId > 0L) {
                    existingCardTxns.add(entity.copy(id = insertedId))
                    cardTxnsRestored++
                }
            }

            // 4. Restore credit cards & bills
            for (cols in billRows) {
                val detectedAt = parseDateTime(cols.getOrNull(1)) ?: System.currentTimeMillis()
                val cardLabel = cols.getOrNull(2).orEmpty()
                val (bankName, tail) = parseCardBankAndTail(cardLabel, null)
                if (tail.isBlank()) continue
                val period = cols.getOrNull(3)?.ifBlank { "Statement" } ?: "Statement"
                val statusStr = cols.getOrNull(4).orEmpty()
                val totalDue = cols.getOrNull(5)?.replace(",", "")?.toDoubleOrNull() ?: continue
                val currency = cols.getOrNull(6)?.ifBlank { "BDT" } ?: "BDT"
                val minDue = cols.getOrNull(7)?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                val nickname = cols.getOrNull(8).orEmpty()
                val dueDate = cols.getOrNull(9).orEmpty().removePrefix("Due:").trim().ifBlank { "01/01/2026" }
                val extraPaid = cols.getOrNull(10)?.replace(",", "")?.toDoubleOrNull()
                val isSuperseded = cols.getOrNull(11).equals("SUPERSEDED", ignoreCase = true)

                val isPaid = statusStr.equals("PAID", ignoreCase = true)
                val paidAmount = when {
                    extraPaid != null -> extraPaid
                    isPaid -> totalDue
                    statusStr.startsWith("PARTIAL", ignoreCase = true) -> {
                        Regex("""([\d,.]+)""").find(statusStr)?.groupValues?.get(1)
                            ?.replace(",", "")?.toDoubleOrNull() ?: 0.0
                    }
                    else -> 0.0
                }

                var card = creditCardDao.findCard(bankName, tail)
                if (card == null) {
                    creditCardDao.insertCard(
                        CreditCard(
                            bankName = bankName,
                            cardLast4 = tail,
                            cardNickname = nickname
                        )
                    )
                    card = creditCardDao.findCard(bankName, tail) ?: continue
                }

                val existingBills = billDao.getBillsForCardOnce(card.id)
                val dupBill = existingBills.any {
                    it.billPeriod.equals(period, ignoreCase = true) &&
                        it.currency.equals(currency, ignoreCase = true)
                }
                if (dupBill) {
                    skipped++
                    continue
                }
                billDao.insertBill(
                    Bill(
                        cardId = card.id,
                        billPeriod = period,
                        totalDue = totalDue,
                        minDue = minDue,
                        dueDate = dueDate,
                        isPaid = isPaid,
                        isSuperseded = isSuperseded,
                        paidAmount = paidAmount,
                        paidAt = if (isPaid || paidAmount > 0) detectedAt else null,
                        currency = currency,
                        detectedAt = detectedAt
                    )
                )
                billsRestored++
            }
        }

        return CsvRestoreResult(
            accountsRestored = accountsRestored,
            transactionsRestored = txnsRestored,
            cardTransactionsRestored = cardTxnsRestored,
            billsRestored = billsRestored,
            skippedDuplicates = skipped
        )
    }

    companion object {
        private fun escapeCsv(value: String?): String {
            val v = value ?: ""
            return if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
                "\"${v.replace("\"", "\"\"")}\""
            } else {
                v
            }
        }

        private fun csvRow(vararg cols: String?): String = cols.joinToString(",") { escapeCsv(it) }

        /** Pure RFC-4180 CSV parser handling quoted commas, doubled quotes, and newlines. */
        fun parseCsvRecords(input: String): List<List<String>> {
            val records = mutableListOf<List<String>>()
            val row = mutableListOf<String>()
            val field = StringBuilder()
            var inQuotes = false
            var i = 0
            while (i < input.length) {
                val c = input[i]
                if (inQuotes) {
                    if (c == '"') {
                        if (i + 1 < input.length && input[i + 1] == '"') {
                            field.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        field.append(c)
                    }
                } else {
                    when (c) {
                        '"' -> inQuotes = true
                        ',' -> {
                            row.add(field.toString())
                            field.clear()
                        }
                        '\r' -> {
                            // handle \r\n cleanly
                        }
                        '\n' -> {
                            row.add(field.toString())
                            field.clear()
                            if (row.any { it.isNotBlank() }) {
                                records.add(row.toList())
                            }
                            row.clear()
                        }
                        else -> field.append(c)
                    }
                }
                i++
            }
            if (field.isNotEmpty() || row.isNotEmpty()) {
                row.add(field.toString())
                if (row.any { it.isNotBlank() }) {
                    records.add(row.toList())
                }
            }
            return records
        }

        private fun parseDateTime(raw: String?): Long? {
            val text = raw?.trim().orEmpty()
            if (text.isBlank()) return null
            val patterns = listOf(
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd",
                "dd/MM/yyyy HH:mm",
                "dd/MM/yyyy"
            )
            for (pattern in patterns) {
                try {
                    val parsed = SimpleDateFormat(pattern, Locale.US).parse(text)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {
                }
            }
            return null
        }

        private fun parseAccountNameAndMask(label: String): Pair<String, String> {
            val trimmed = label.trim()
            val match = Regex("""^(.+?)\s*\(([^)]+)\)\s*$""").find(trimmed)
            return if (match != null) {
                match.groupValues[1].trim() to match.groupValues[2].trim()
            } else {
                trimmed to ""
            }
        }

        private fun parseCardBankAndTail(label: String, tailHint: String?): Pair<String, String> {
            val tailMatch = Regex("""(\d{4})\s*\)?\s*$""").find(label.trim())
            val tail = tailHint?.takeLast(4)?.ifBlank { null } ?: tailMatch?.groupValues?.get(1).orEmpty()
            val bank = label
                .replace(Regex("""\(.*\)"""), "")
                .replace(Regex("""[•*]+\s*\d{4}$"""), "")
                .trim()
                .ifBlank { "Card" }
            return bank to tail
        }
    }
}
