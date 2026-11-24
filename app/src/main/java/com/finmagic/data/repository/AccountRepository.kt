package com.finmagic.data.repository

import androidx.room.withTransaction
import com.finmagic.data.local.AppDatabase
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.AccountTransactionDao
import com.finmagic.data.local.dao.SmsRecordDao
import com.finmagic.data.local.dao.SmsRuleApplicationDao
import com.finmagic.data.local.dao.TransferDao
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.Transfer
import com.finmagic.data.sms.parsers.SmsClassification
import com.finmagic.data.sms.parsers.LedgerDirection
import com.finmagic.data.sms.parsers.LedgerEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists classified ledger SMS into Account/AccountTransaction/Transfer rows
 * (plan §2.2, §4, §5):
 *  - one Account per (institution, accountMask); Cash is a built-in singleton;
 *  - dedup by SMS body so rescans never double-count;
 *  - ATM withdrawals become Bank→Cash transfers;
 *  - manual cash expense/income/transfer entries.
 */
@Singleton
class AccountRepository @Inject constructor(
    private val database: AppDatabase,
    private val accountDao: AccountDao,
    private val txnDao: AccountTransactionDao,
    private val transferDao: TransferDao,
    private val simResolver: com.finmagic.data.sms.SimAccountResolver,
    private val smsRecordDao: SmsRecordDao,
    private val smsRuleApplicationDao: SmsRuleApplicationDao
) {

    companion object {
        /** Natural, visible ledger balance used for account math. Direct-set overrides are hidden
         * and should not be treated as real ledger entries when calculating the current balance.
         */
        fun ledgerBalance(txns: List<AccountTransaction>): Double =
            txns.filterNot { it.category == "DirectSet" }
                .sumOf { if (it.direction == AccountTransaction.DIRECTION_IN) it.amount else -it.amount }

        /** Effective/displayed balance INCLUDING the visible DirectSet override row. */
        fun effectiveBalance(txns: List<AccountTransaction>): Double =
            txns.sumOf { if (it.direction == AccountTransaction.DIRECTION_IN) it.amount else -it.amount }

        fun calculateAdjustmentDelta(targetBalance: Double, txns: List<AccountTransaction>): Double =
            targetBalance - ledgerBalance(txns)

        /** Per-transaction account balance before and after each transaction in chronological order. */
        fun computeBalanceTrails(txns: List<AccountTransaction>): Map<Long, BalanceTrail> {
            if (txns.isEmpty()) return emptyMap()
            val sorted = txns.sortedWith(compareBy({ it.occurredAt }, { it.id }))
            val result = LinkedHashMap<Long, BalanceTrail>(sorted.size)
            val firstKnownIdx = sorted.indexOfFirst { it.balanceAfter != null }
            if (firstKnownIdx == -1) {
                var running = 0.0
                for (txn in sorted) {
                    val delta = if (txn.direction == AccountTransaction.DIRECTION_IN) txn.amount else -txn.amount
                    val before = running
                    val after = running + delta
                    result[txn.id] = BalanceTrail(before = before, after = after)
                    running = after
                }
            } else {
                var cursorAfter = sorted[firstKnownIdx].balanceAfter!!
                for (i in firstKnownIdx downTo 0) {
                    val txn = sorted[i]
                    val delta = if (txn.direction == AccountTransaction.DIRECTION_IN) txn.amount else -txn.amount
                    val after = txn.balanceAfter ?: cursorAfter
                    val before = after - delta
                    result[txn.id] = BalanceTrail(before = before, after = after)
                    cursorAfter = before
                }
                var running = sorted[firstKnownIdx].balanceAfter!!
                for (i in (firstKnownIdx + 1) until sorted.size) {
                    val txn = sorted[i]
                    val delta = if (txn.direction == AccountTransaction.DIRECTION_IN) txn.amount else -txn.amount
                    val after = txn.balanceAfter ?: (running + delta)
                    val before = if (txn.balanceAfter != null) after - delta else running
                    result[txn.id] = BalanceTrail(before = before, after = after)
                    running = after
                }
            }
            return result
        }

        /** Current account balance computed chronologically across balanceAfter anchors and subsequent transactions. */
        fun computeAccountBalance(txns: List<AccountTransaction>): Double {
            if (txns.isEmpty()) return 0.0
            val sorted = txns.sortedWith(compareBy({ it.occurredAt }, { it.id }))
            val trails = computeBalanceTrails(sorted)
            return trails[sorted.last().id]?.after ?: effectiveBalance(sorted)
        }
    }

    data class BalanceTrail(
        val before: Double,
        val after: Double
    )

    // ---------------------------------------------------------------- accounts

    suspend fun ensureCashAccount(): Account {
        accountDao.getCashAccountOnce()?.let { return it }
        accountDao.insert(
            Account(
                name = "Cash",
                kind = Account.KIND_CASH,
                bankName = "",
                accountMask = Account.CASH_MASK
            )
        )
        return accountDao.getCashAccountOnce()!!
    }

    /**
     * Finds or creates the Account for a parsed [LedgerEntry].
     *
     * Wallets (bKash/Nagad/Rocket) are SIM-owned: at most one account per
     * institution per SIM (SIM1/SIM2). The SIM's phone number is used as the
     * account mask when readable, otherwise a "SIM1"/"SIM2" label. Counterparty
     * wallets from funding SMS never reach here.
     */
    suspend fun resolveAccount(entry: LedgerEntry): Account? {
        val simIdentity = if (entry.kind == com.finmagic.data.sms.parsers.AccountKind.WALLET) {
            simResolver.identityFor(entry.subscriptionId)
        } else {
            null
        }
        val mask = when {
            simIdentity?.number != null -> simIdentity.number
            simIdentity != null -> simIdentity.label
            else -> entry.accountMask.ifBlank { "WALLET" }
        }
        accountDao.findByInstitutionAndMask(entry.institution, mask)?.let { account ->
            if (account.isArchived) return null
            return account
        }

        val baseName = com.finmagic.data.sms.parsers.Institutions.accountName(entry.institution)
        val name = when {
            // "bKash 01712…456" / "bKash SIM2"
            simIdentity != null -> "$baseName ${simIdentity.number ?: simIdentity.label}"
            entry.institution == com.finmagic.data.sms.parsers.Institutions.AIBL -> {
                val last4 = entry.accountMask.takeLast(4).filter { it.isDigit() }
                if (last4.isNotEmpty()) "$baseName $last4" else baseName
            }
            // Pubali holds several accounts (main/TBSD/EMI); masks are normalized
            // by PubaliParser so suffixes stay stable across SMS spellings.
            entry.institution == com.finmagic.data.sms.parsers.Institutions.PUBALI ->
                "$baseName •• ${mask.takeLast(4)}"
            entry.institution == com.finmagic.data.sms.parsers.Institutions.UNKNOWN_BANK ->
                "$baseName •• ${entry.accountMask.takeLast(4)}"
            else -> baseName
        }
        accountDao.insert(
            Account(
                name = name,
                kind = entry.kind.name,
                bankName = entry.institution,
                accountMask = mask,
                currency = "BDT"
            )
        )
        val inserted = accountDao.findByInstitutionAndMask(entry.institution, mask)
        if (inserted?.isArchived == true) return null
        return inserted
    }

    /** Finds or recreates an account from a saved [com.finmagic.data.sms.AccountTemplate]. */
    suspend fun resolveOrCreateFromTemplate(template: com.finmagic.data.sms.AccountTemplate): Long? {
        if (template.kind.equals(Account.KIND_CASH, ignoreCase = true)) {
            return ensureCashAccount().id
        }
        val existing = accountDao.findByInstitutionAndMask(template.bankName, template.accountMask)
        if (existing != null) {
            if (existing.isArchived) accountDao.setArchived(existing.id, false)
            return existing.id
        }
        val rowId = accountDao.insert(
            Account(
                name = template.name.trim(),
                kind = template.kind,
                bankName = template.bankName,
                accountMask = template.accountMask,
                currency = template.currency.ifBlank { "BDT" },
                includeInTotal = template.includeInTotal
            )
        )
        return if (rowId > 0L) {
            rowId
        } else {
            accountDao.findByInstitutionAndMask(template.bankName, template.accountMask)?.id
        }
    }

    /** Creates a user-defined manual account (Savings, Deposit, Loan, Cash, or custom type). */
    suspend fun createManualAccount(
        name: String,
        accountType: String,
        isLiability: Boolean,
        bankName: String = "",
        accountMask: String = "",
        currency: String = "BDT",
        initialBalance: Double = 0.0,
        includeInTotal: Boolean = true
    ): Long? {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return null
        val trimmedType = accountType.trim().ifBlank { Account.TYPE_SAVINGS }
        val existingCash = if (trimmedType.equals(Account.TYPE_CASH, ignoreCase = true)) {
            accountDao.getCashAccountOnce()
        } else null
        val kind = if (trimmedType.equals(Account.TYPE_CASH, ignoreCase = true) && existingCash == null) {
            Account.KIND_CASH
        } else {
            Account.KIND_BANK
        }
        val effectiveMask = when {
            kind == Account.KIND_CASH -> Account.CASH_MASK
            accountMask.isNotBlank() -> accountMask.trim()
            else -> "MANUAL-${System.currentTimeMillis()}"
        }
        return database.withTransaction {
            val insertedId = accountDao.insert(
                Account(
                    name = trimmedName,
                    kind = kind,
                    bankName = bankName.trim(),
                    accountMask = effectiveMask,
                    currency = currency.trim().ifBlank { "BDT" },
                    includeInTotal = includeInTotal,
                    accountType = trimmedType,
                    isLiability = isLiability
                )
            )
            val resolvedId = if (insertedId > 0L) {
                insertedId
            } else {
                accountDao.findByInstitutionAndMask(bankName.trim(), effectiveMask)?.id
            } ?: return@withTransaction null

            if (kotlin.math.abs(initialBalance) >= 0.01) {
                val direction = if (initialBalance >= 0) {
                    AccountTransaction.DIRECTION_IN
                } else {
                    AccountTransaction.DIRECTION_OUT
                }
                txnDao.insert(
                    AccountTransaction(
                        accountId = resolvedId,
                        direction = direction,
                        amount = kotlin.math.abs(initialBalance),
                        balanceAfter = if (kind != Account.KIND_CASH) initialBalance else null,
                        occurredAt = System.currentTimeMillis(),
                        category = "DirectSet",
                        counterparty = "Opening Balance",
                        isManual = true
                    )
                )
            }
            resolvedId
        }
    }

    // ---------------------------------------------------------------- storage

    /**
     * Stores any ledger-bearing classification. Returns true when new rows were
     * written. SMS-body dedup keeps repeat scans idempotent.
     */
    suspend fun store(
        classification: SmsClassification,
        smsBody: String,
        smsRecordId: Long?
    ): Boolean = when (classification) {
        is SmsClassification.AtmWithdraw -> storeAtmWithdraw(classification.entry, smsBody, smsRecordId) != null
        is SmsClassification.WalletToCash -> storeTransferPair(classification.from, classification.to, smsBody, smsRecordId) != null
        is SmsClassification.AcctTxn -> storeSingle(classification.entry, smsBody, smsRecordId)
        is SmsClassification.WalletTxn -> storeSingle(classification.entry, smsBody, smsRecordId)
        else -> false
    }

    /**
     * Stores a manual/taught transaction. Returns the new transaction row id when
     * a row was written (i.e. not a duplicate, amount valid); otherwise null.
     */
    suspend fun storeManualToAccount(
        accountId: Long,
        direction: String,
        amount: Double,
        occurredAt: Long,
        category: String,
        counterparty: String?,
        smsBody: String,
        smsRecordId: Long?,
        balanceAfter: Double? = null,
        isExpenseOverride: Int = AccountTransaction.EXPENSE_AUTO
    ): Long? {
        if (amount <= 0.0 || txnDao.existsBySmsBody(smsBody)) return null
        accountDao.findByIdOnce(accountId) ?: return null
        return database.withTransaction {
            txnDao.insert(
                AccountTransaction(
                    accountId = accountId,
                    direction = direction,
                    amount = amount,
                    balanceAfter = balanceAfter,
                    occurredAt = occurredAt,
                    category = category,
                    counterparty = counterparty,
                    smsRecordId = smsRecordId,
                    smsBody = smsBody,
                    isManual = true,
                    isExpenseOverride = isExpenseOverride
                )
            )
        }
    }

    private suspend fun checkAndInsertGapAdjustment(accountId: Long, currentEntry: LedgerEntry) {
        val currentBalanceAfter = currentEntry.balanceAfter ?: return
        val currentOccurredAt = currentEntry.occurredAt ?: System.currentTimeMillis()

        val prevTxn = txnDao.getLatestTxnWithBalanceBefore(accountId, currentOccurredAt) ?: return
        val prevBalance = prevTxn.balanceAfter ?: return

        val middleTxns = txnDao.getTxnsBetweenWithoutBalance(accountId, prevTxn.occurredAt, prevTxn.id, currentOccurredAt)
        val middleDeltas = middleTxns.sumOf {
            if (it.direction == AccountTransaction.DIRECTION_IN) it.amount else -it.amount
        }

        val expectedPriorBalance = if (currentEntry.direction == LedgerDirection.IN) {
            currentBalanceAfter - currentEntry.amount
        } else {
            currentBalanceAfter + currentEntry.amount
        }

        val expectedPriorFromPrev = prevBalance + middleDeltas
        val gap = expectedPriorBalance - expectedPriorFromPrev

        if (kotlin.math.abs(gap) >= 0.01) {
            val gapDirection = if (gap > 0) AccountTransaction.DIRECTION_IN else AccountTransaction.DIRECTION_OUT
            val gapAmount = kotlin.math.abs(gap)
            val adjTime = maxOf(prevTxn.occurredAt + 1, currentOccurredAt - 1)

            txnDao.insert(
                AccountTransaction(
                    accountId = accountId,
                    direction = gapDirection,
                    amount = gapAmount,
                    balanceAfter = expectedPriorBalance,
                    occurredAt = adjTime,
                    category = com.finmagic.data.sms.parsers.TxnCategory.ADJUSTMENT,
                    counterparty = "Missing SMS Adjustment",
                    isManual = true
                )
            )
        }
    }

    suspend fun storeSingle(entry: LedgerEntry, smsBody: String, smsRecordId: Long?): Boolean {
        if (txnDao.existsBySmsBody(smsBody)) return false
        val account = resolveAccount(entry) ?: return false
        database.withTransaction {
            checkAndInsertGapAdjustment(account.id, entry)
            txnDao.insert(entry.toEntity(account.id, smsRecordId, smsBody))
        }
        return true
    }

    /** ATM withdrawal → OUT on the bank + IN on Cash + Transfer row (plan §5). */
    suspend fun storeAtmWithdraw(entry: LedgerEntry, smsBody: String, smsRecordId: Long?): Long? {
        if (txnDao.existsBySmsBody(smsBody)) return null
        val bank = resolveAccount(entry) ?: return null
        val cash = ensureCashAccount()
        return database.withTransaction {
            val transferId = transferDao.insert(
                Transfer(
                    fromAccountId = bank.id,
                    toAccountId = cash.id,
                    amount = entry.amount,
                    occurredAt = entry.occurredAt ?: System.currentTimeMillis()
                )
            )
            checkAndInsertGapAdjustment(bank.id, entry)
            val bankTxnId = txnDao.insert(entry.toEntity(bank.id, smsRecordId, smsBody, transferId))
            txnDao.insert(cashInEntry(entry, cash.id, "${bank.displayName} ATM", transferId))
            bankTxnId
        }
    }

    /** Generic two-account movement (bank→wallet funding, wallet→cash out). */
    suspend fun storeTransferPair(
        from: LedgerEntry,
        to: LedgerEntry,
        smsBody: String,
        smsRecordId: Long?
    ): Long? {
        if (txnDao.existsBySmsBody(smsBody)) return null
        val fromAccount = resolveAccount(from) ?: return null
        val toAccount = if (to.kind == com.finmagic.data.sms.parsers.AccountKind.CASH) {
            ensureCashAccount()
        } else {
            resolveAccount(to) ?: return null
        }
        if (fromAccount.id == toAccount.id) return null
        return database.withTransaction {
            val occurredAt = from.occurredAt ?: System.currentTimeMillis()
            val transferId = transferDao.insert(
                Transfer(
                    fromAccountId = fromAccount.id,
                    toAccountId = toAccount.id,
                    amount = from.amount,
                    occurredAt = occurredAt
                )
            )
            checkAndInsertGapAdjustment(fromAccount.id, from)
            val outTxnId = txnDao.insert(from.toEntity(fromAccount.id, smsRecordId, smsBody, transferId))
            checkAndInsertGapAdjustment(toAccount.id, to)
            txnDao.insert(to.toEntity(toAccount.id, smsRecordId, smsBody, transferId))
            outTxnId
        }
    }

    private fun cashInEntry(
        source: LedgerEntry,
        cashAccountId: Long,
        counterparty: String,
        transferId: Long
    ) = AccountTransaction(
        accountId = cashAccountId,
        direction = AccountTransaction.DIRECTION_IN,
        amount = source.amount,
        occurredAt = source.occurredAt ?: System.currentTimeMillis(),
        category = com.finmagic.data.sms.parsers.TxnCategory.RECEIVED,
        counterparty = counterparty,
        smsRecordId = null,
        transferId = transferId,
        smsBody = null
    )

    private fun LedgerEntry.toEntity(
        accountId: Long,
        smsRecordId: Long?,
        smsBody: String?,
        transferId: Long? = null
    ) = AccountTransaction(
        accountId = accountId,
        direction = if (direction == LedgerDirection.IN) {
            AccountTransaction.DIRECTION_IN
        } else {
            AccountTransaction.DIRECTION_OUT
        },
        amount = amount,
        balanceAfter = balanceAfter,
        occurredAt = occurredAt ?: System.currentTimeMillis(),
        category = category,
        counterparty = counterparty,
        smsRecordId = smsRecordId,
        transferId = transferId,
        smsBody = smsBody
    )

    // ----------------------------------------------------------- manual entries

    /** Removes any transaction(s) previously created from the same parsed SMS record, including
     * transfer mirrors and their transfer links, so re-teaching replaces the original entry.
     */
    suspend fun replaceParsedSmsEntries(smsRecordId: Long) {
        val existing = txnDao.getBySmsRecordIdOnce(smsRecordId)
        if (existing.isEmpty()) return

        val transferIds = existing.mapNotNull { it.transferId }.distinct()
        database.withTransaction {
            transferIds.forEach { transferId ->
                txnDao.deleteByTransferId(transferId)
                transferDao.deleteById(transferId)
            }
            txnDao.deleteBySmsRecordId(smsRecordId)
        }
    }

    /** Manual cash expense/income row (plan §5). */
    suspend fun addManualCashEntry(
        direction: String,
        amount: Double,
        category: String?,
        counterparty: String?,
        occurredAt: Long
    ): Boolean {
        if (amount <= 0.0) return false
        val cash = ensureCashAccount()
        val initialOverride = if (direction == AccountTransaction.DIRECTION_IN) {
            AccountTransaction.EXPENSE_INCLUDED
        } else {
            AccountTransaction.EXPENSE_AUTO
        }
        database.withTransaction {
            txnDao.insert(
                AccountTransaction(
                    accountId = cash.id,
                    direction = direction,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = category ?: com.finmagic.data.sms.parsers.TxnCategory.MANUAL,
                    counterparty = counterparty,
                    isManual = true,
                    isExpenseOverride = initialOverride
                )
            )
        }
        return true
    }

    /** Manual transfer between Cash and another account (plan §5). */
    suspend fun addManualTransfer(
        otherAccountId: Long,
        fromCash: Boolean,
        amount: Double,
        occurredAt: Long
    ): Boolean {
        if (amount <= 0.0) return false
        val other = accountDao.findByIdOnce(otherAccountId) ?: return false
        val cash = ensureCashAccount()
        val from = if (fromCash) cash else other
        val to = if (fromCash) other else cash
        database.withTransaction {
            val transferId = transferDao.insert(
                Transfer(
                    fromAccountId = from.id,
                    toAccountId = to.id,
                    amount = amount,
                    occurredAt = occurredAt
                )
            )
            txnDao.insert(
                AccountTransaction(
                    accountId = from.id,
                    direction = AccountTransaction.DIRECTION_OUT,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = com.finmagic.data.sms.parsers.TxnCategory.FUND_TRANSFER,
                    counterparty = "Transfer to ${to.displayName}",
                    transferId = transferId,
                    isManual = true
                )
            )
            txnDao.insert(
                AccountTransaction(
                    accountId = to.id,
                    direction = AccountTransaction.DIRECTION_IN,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = com.finmagic.data.sms.parsers.TxnCategory.RECEIVED,
                    counterparty = "Transfer from ${from.displayName}",
                    transferId = transferId,
                    isManual = true
                )
            )
        }
        return true
    }

    /** Manual transfer between any two accounts. */
    suspend fun transferBetweenAccounts(
        fromAccountId: Long,
        toAccountId: Long,
        amount: Double,
        occurredAt: Long = System.currentTimeMillis()
    ): Boolean {
        if (amount <= 0.0 || fromAccountId == toAccountId) return false
        val from = accountDao.findByIdOnce(fromAccountId) ?: return false
        val to = accountDao.findByIdOnce(toAccountId) ?: return false
        database.withTransaction {
            val transferId = transferDao.insert(
                Transfer(
                    fromAccountId = from.id,
                    toAccountId = to.id,
                    amount = amount,
                    occurredAt = occurredAt
                )
            )
            txnDao.insert(
                AccountTransaction(
                    accountId = from.id,
                    direction = AccountTransaction.DIRECTION_OUT,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = com.finmagic.data.sms.parsers.TxnCategory.FUND_TRANSFER,
                    counterparty = "Transfer to ${to.displayName}",
                    transferId = transferId,
                    isManual = true
                )
            )
            txnDao.insert(
                AccountTransaction(
                    accountId = to.id,
                    direction = AccountTransaction.DIRECTION_IN,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = com.finmagic.data.sms.parsers.TxnCategory.RECEIVED,
                    counterparty = "Transfer from ${from.displayName}",
                    transferId = transferId,
                    isManual = true
                )
            )
        }
        return true
    }

    suspend fun deleteEntry(txnId: Long): Boolean {
        val txn = txnDao.findByIdOnce(txnId) ?: return false
        database.withTransaction {
            txn.transferId?.let { transferId ->
                txnDao.getByTransferIdOnce(transferId).forEach { txnDao.deleteById(it.id) }
                transferDao.deleteById(transferId)
            }
            txnDao.deleteById(txnId)
        }
        return true
    }

    suspend fun deleteManualEntry(txnId: Long): Boolean {
        val txn = txnDao.findByIdOnce(txnId) ?: return false
        database.withTransaction {
            txnDao.deleteManualById(txnId)
            txn.transferId?.let { transferId ->
                // Remove the mirror row(s) and the transfer link of a manual transfer.
                txnDao.getByTransferIdOnce(transferId)
                    .filter { it.isManual }
                    .forEach { txnDao.deleteManualById(it.id) }
                transferDao.deleteById(transferId)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- manage

    /** Renames an account (My Accounts screen). */
    suspend fun renameAccount(accountId: Long, newName: String): Boolean {
        val name = newName.trim()
        if (name.isEmpty()) return false
        accountDao.findByIdOnce(accountId) ?: return false
        database.withTransaction { accountDao.rename(accountId, name) }
        return true
    }

    /** Toggles whether the account's balance counts toward the aggregated total. */
    suspend fun setIncludeInTotal(accountId: Long, value: Boolean) {
        accountDao.setIncludeInTotal(accountId, value)
    }

    /** Updates an account's type and liability flag. */
    suspend fun updateAccountType(accountId: Long, accountType: String, isLiability: Boolean) {
        accountDao.updateType(accountId, accountType.trim(), isLiability)
    }

    /** Updates name, type, liability flag, and total-inclusion in one call. */
    suspend fun updateAccountDetails(
        accountId: Long,
        newName: String,
        accountType: String,
        isLiability: Boolean,
        includeInTotal: Boolean
    ): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        accountDao.findByIdOnce(accountId) ?: return false
        database.withTransaction {
            accountDao.updateDetails(accountId, trimmed, accountType.trim(), isLiability, includeInTotal)
        }
        return true
    }

    /**
     * Soft-deletes an account and clears its transactions. Soft-deleted accounts
     * stay in the DB (archived) so rescanning old SMS does not recreate them.
     */
    suspend fun deleteAccount(accountId: Long): Boolean {
        val account = accountDao.findByIdOnce(accountId) ?: return false
        if (account.kind == Account.KIND_CASH) return false
        database.withTransaction {
            transferDao.getForAccountOnce(accountId).forEach { transfer ->
                txnDao.deleteByTransferId(transfer.id)
                transferDao.deleteById(transfer.id)
            }
            txnDao.deleteByAccountId(accountId)
            accountDao.setArchived(accountId, true)
        }
        return true
    }

    /**
     * Updates the category of a transaction. If changing from ATM withdrawal to
     * another category, the associated Cash transfer & mirror row are removed.
     */
    /** Expenses-tab membership override (-1 exclude / 0 auto / 1 include). */
    suspend fun setExpenseOverride(txnId: Long, value: Int) {
        txnDao.setExpenseOverride(txnId, value)
    }

    suspend fun updateTransactionCategory(txnId: Long, newCategory: String): Boolean {
        val txn = txnDao.findByIdOnce(txnId) ?: return false
        val oldCategory = txn.category
        val isOldAtm = oldCategory.equals(com.finmagic.data.sms.parsers.TxnCategory.ATM, ignoreCase = true)
        val isNewAtm = newCategory.equals(com.finmagic.data.sms.parsers.TxnCategory.ATM, ignoreCase = true)

        database.withTransaction {
            if (isOldAtm && !isNewAtm && txn.transferId != null) {
                val transferId = txn.transferId
                txnDao.deleteTransferMirror(transferId, txn.accountId)
                transferDao.deleteById(transferId)
                txnDao.updateCategoryAndTransferId(txnId, newCategory, null)
            } else if (!isOldAtm && isNewAtm && txn.direction == AccountTransaction.DIRECTION_OUT && txn.transferId == null) {
                val bank = accountDao.findByIdOnce(txn.accountId)
                if (bank != null && bank.kind != Account.KIND_CASH) {
                    val cash = ensureCashAccount()
                    val transferId = transferDao.insert(
                        Transfer(
                            fromAccountId = bank.id,
                            toAccountId = cash.id,
                            amount = txn.amount,
                            occurredAt = txn.occurredAt
                        )
                    )
                    txnDao.insert(
                        AccountTransaction(
                            accountId = cash.id,
                            direction = AccountTransaction.DIRECTION_IN,
                            amount = txn.amount,
                            occurredAt = txn.occurredAt,
                            category = com.finmagic.data.sms.parsers.TxnCategory.RECEIVED,
                            counterparty = "${bank.displayName} ATM",
                            transferId = transferId
                        )
                    )
                    txnDao.updateCategoryAndTransferId(txnId, newCategory, transferId)
                } else {
                    txnDao.updateCategoryAndTransferId(txnId, newCategory, txn.transferId)
                }
            } else {
                txnDao.updateCategoryAndTransferId(txnId, newCategory, txn.transferId)
            }
        }
        return true
    }

    /** Updates details (counterparty/note, category, amount) of a transaction. */
    suspend fun updateTransactionDetails(id: Long, counterparty: String?, category: String?, amount: Double): Boolean {
        if (amount <= 0.0) return false
        val txn = txnDao.findByIdOnce(id) ?: return false
        database.withTransaction {
            txnDao.updateTransactionDetails(id, counterparty?.ifBlank { null }, category?.ifBlank { null }, amount)
        }
        return true
    }

    /**
     * Corrects a misparsed SMS transaction (plan §2.2).
     * Replaces the existing transaction with corrected values.
     * If the account changed, the old transaction is removed and a new one created.
     * The original smsBody is preserved for audit and dedup.
     */
    suspend fun correctTransaction(
        txnId: Long,
        direction: String,
        amount: Double,
        accountId: Long,
        category: String?,
        counterparty: String?,
        balanceAfter: Double? = null
    ): Boolean {
        if (amount <= 0.0) return false
        val txn = txnDao.findByIdOnce(txnId) ?: return false
        accountDao.findByIdOnce(accountId) ?: return false

        database.withTransaction {
            val sameAccount = txn.accountId == accountId
            val sameDirection = txn.direction == direction
            val effectiveBalance = balanceAfter ?: txn.balanceAfter

            if (sameAccount && sameDirection) {
                // Simple update — no account or direction change.
                txnDao.updateTransactionDetails(
                    txnId,
                    counterparty?.ifBlank { null },
                    category?.ifBlank { null },
                    amount
                )
                if (balanceAfter != null) {
                    txnDao.updateBalanceAfter(txnId, balanceAfter)
                }
            } else {
                // Account or direction changed — remove old, insert new.
                // Handle transfer mirrors if the original was part of a transfer.
                txn.transferId?.let { transferId ->
                    txnDao.deleteTransferMirror(transferId, txn.accountId)
                    transferDao.deleteById(transferId)
                }
                txnDao.deleteById(txnId)

                txnDao.insert(
                    AccountTransaction(
                        accountId = accountId,
                        direction = direction,
                        amount = amount,
                        currency = txn.currency,
                        balanceAfter = effectiveBalance,
                        occurredAt = txn.occurredAt,
                        category = category?.ifBlank { null } ?: txn.category,
                        counterparty = counterparty?.ifBlank { null } ?: txn.counterparty,
                        smsRecordId = txn.smsRecordId,
                        smsBody = txn.smsBody,
                        isManual = false,
                        createdAt = txn.createdAt
                    )
                )
            }
        }
        return true
    }

    /**
     * Reverses and removes an incorrectly parsed SMS transaction (plan §2.2).
     */
    suspend fun ignoreParsedTransaction(txnId: Long): Boolean {
        val txn = txnDao.findByIdOnce(txnId) ?: return false
        database.withTransaction {
            txn.transferId?.let { transferId ->
                txnDao.deleteTransferMirror(transferId, txn.accountId)
                transferDao.deleteById(transferId)
            }
            txnDao.deleteById(txnId)
        }
        return true
    }

    /** Adjusts the balance of the given account (or Cash if null) to the target amount,
     *  recording an adjustment entry for the difference from the current displayed balance. */
    suspend fun adjustCashBalance(targetBalance: Double, accountId: Long? = null): Boolean {
        val targetId = accountId ?: ensureCashAccount().id
        database.withTransaction {
            val account = accountDao.findByIdOnce(targetId)
            val isNonCash = account != null && account.kind != Account.KIND_CASH
            val allTxns = txnDao.getAllByAccountOnce(targetId)
            val currentBalance = if (isNonCash) computeAccountBalance(allTxns) else effectiveBalance(allTxns)
            val diff = targetBalance - currentBalance
            if (kotlin.math.abs(diff) >= 0.01) {
                val direction = if (diff > 0) AccountTransaction.DIRECTION_IN else AccountTransaction.DIRECTION_OUT
                val amount = kotlin.math.abs(diff)
                val label = if (isNonCash) "Balance Adjustment" else "Cash Balance Adjustment"
                txnDao.insert(
                    AccountTransaction(
                        accountId = targetId,
                        direction = direction,
                        amount = amount,
                        balanceAfter = if (isNonCash) targetBalance else null,
                        occurredAt = System.currentTimeMillis(),
                        category = com.finmagic.data.sms.parsers.TxnCategory.ADJUSTMENT,
                        counterparty = label,
                        isManual = true
                    )
                )
            }
        }
        return true
    }

    // ---------------------------------------------------------------- queries

    fun observeSummaries(monthStart: Long, monthEnd: Long = Long.MAX_VALUE): Flow<List<com.finmagic.data.local.entity.AccountSummary>> =
        kotlinx.coroutines.flow.combine(
            accountDao.observeAll(),
            txnDao.observeSummariesRaw(monthStart, monthEnd),
            txnDao.observeAllIncludingDirectSet()
        ) { accounts, rows, allTxns ->
            val byId = rows.associateBy { it.accountId }
            val txnsByAccount = allTxns.groupBy { it.accountId }
            accounts.mapNotNull { account ->
                byId[account.id]?.let { row ->
                    val acctTxns = txnsByAccount[account.id].orEmpty()
                    val currentBal = if (account.kind == Account.KIND_CASH) {
                        row.computedBalance
                    } else {
                        computeAccountBalance(acctTxns)
                    }
                    com.finmagic.data.local.entity.AccountSummary(
                        account = account,
                        latestBalance = row.latestBalance,
                        computedBalance = row.computedBalance,
                        monthIn = row.monthIn,
                        monthOut = row.monthOut,
                        lastActivity = row.lastActivity,
                        currentBalance = currentBal
                    )
                }
            }
        }

    fun observeTransactions(accountId: Long): Flow<List<AccountTransaction>> =
        txnDao.observeByAccount(accountId)

    fun observeAllTransactions(): Flow<List<AccountTransaction>> =
        txnDao.observeAll()

    fun observeAllTransactionsIncludingDirectSet(): Flow<List<AccountTransaction>> =
        txnDao.observeAllIncludingDirectSet()

    fun observeAllTransactionsWithAccount(): Flow<List<com.finmagic.data.local.dao.AccountTransactionWithAccount>> =
        txnDao.observeAllWithAccount()

    /** Monthly expense totals per category across all accounts (plan Phase 5). */
    fun observeMonthlyExpenseByCategory(monthStart: Long, monthEnd: Long = Long.MAX_VALUE): Flow<List<com.finmagic.data.local.dao.CategoryTotal>> =
        txnDao.observeMonthlyExpenseByCategory(monthStart, monthEnd)

    /** Monthly income totals per category across all accounts. */
    fun observeMonthlyIncomeByCategory(monthStart: Long, monthEnd: Long = Long.MAX_VALUE): Flow<List<com.finmagic.data.local.dao.CategoryTotal>> =
        txnDao.observeMonthlyIncomeByCategory(monthStart, monthEnd)

    /** Soft-deleted (archived) accounts that can still be restored by the user. */
    fun observeArchived(): Flow<List<Account>> = accountDao.observeArchived()

    /** Archived accounts paired with their stored SMS messages so users can delete SMS and then permanently remove the account. */
    fun observeArchivedWithSms(): Flow<List<ArchivedAccountWithSms>> =
        kotlinx.coroutines.flow.combine(
            accountDao.observeArchived(),
            smsRecordDao.getAllSms(),
            smsRuleApplicationDao.observeAccountLinkedSmsIds()
        ) { archivedAccounts, allSms, linkedRows ->
            val linkedByAccount = linkedRows.groupBy({ it.accountId }, { it.smsRecordId })
            archivedAccounts.map { account ->
                val linkedIds = linkedByAccount[account.id]?.toSet().orEmpty()
                ArchivedAccountWithSms(
                    account = account,
                    matchingSms = ArchivedAccountSmsMatcher.matchingSms(account, allSms, linkedIds)
                )
            }
        }

    /** Deletes a single stored SMS record (for archived account cleanup). */
    suspend fun deleteArchivedAccountSms(smsRecordId: Long) {
        database.withTransaction {
            smsRuleApplicationDao.deleteBySmsRecordIds(listOf(smsRecordId))
            smsRecordDao.deleteById(smsRecordId)
        }
    }

    /** Deletes all stored SMS records matched to an archived account. */
    suspend fun deleteArchivedAccountSmsBatch(smsRecordIds: List<Long>) {
        if (smsRecordIds.isEmpty()) return
        database.withTransaction {
            smsRuleApplicationDao.deleteBySmsRecordIds(smsRecordIds)
            smsRecordDao.deleteByIds(smsRecordIds)
        }
    }

    /** Permanently deletes an archived non-Cash account only when no stored SMS remain for it. */
    suspend fun deleteAccountPermanently(accountId: Long): Boolean {
        val account = accountDao.findByIdOnce(accountId) ?: return false
        if (!account.isArchived || account.kind == Account.KIND_CASH) return false

        val linkedIds = smsRuleApplicationDao.getSmsRecordIdsForAccount(accountId).toSet()
        val remainingSms = ArchivedAccountSmsMatcher.matchingSms(
            account = account,
            allSms = smsRecordDao.getAllOnce(),
            linkedSmsIds = linkedIds
        )
        if (remainingSms.isNotEmpty()) return false

        database.withTransaction {
            transferDao.getForAccountOnce(accountId).forEach { transfer ->
                txnDao.deleteByTransferId(transfer.id)
                transferDao.deleteById(transfer.id)
            }
            txnDao.deleteByAccountId(accountId)
            accountDao.deleteById(accountId)
        }
        return true
    }

    /** Total absolute displayed balance across active accounts (used by SmsScanWorker to detect zero-balance state). */
    suspend fun getTotalDisplayedBalanceOnce(): Double {
        val accounts = accountDao.getAllOnce()
        return accounts.sumOf { acct ->
            val txns = txnDao.getAllByAccountOnce(acct.id)
            val bal = if (acct.kind == Account.KIND_CASH) {
                effectiveBalance(txns)
            } else {
                computeAccountBalance(txns)
            }
            kotlin.math.abs(bal)
        }
    }

    /** Brings an archived account back into the active list. */
    suspend fun restoreAccount(accountId: Long): Boolean {
        accountDao.findByIdOnce(accountId) ?: return false
        accountDao.setArchived(accountId, false)
        return true
    }

    suspend fun getTransactionsOnce(accountId: Long): List<AccountTransaction> =
        txnDao.getByAccountOnce(accountId)

    /** Gets ALL rows including DirectSet override entries. */
    suspend fun getAllTransactionsOnce(accountId: Long): List<AccountTransaction> =
        txnDao.getAllByAccountOnce(accountId)

    suspend fun getAllAccountsOnce(): List<Account> = accountDao.getAllOnce()

    suspend fun getAccountOnce(accountId: Long): Account? = accountDao.findByIdOnce(accountId)

    suspend fun latestBalance(accountId: Long): Double? = txnDao.getLatestBalanceOnce(accountId)

    /** Finds recent unlinked 'IN' transactions for an account that could be the other side of a cash transfer. */
    suspend fun findPotentialCreditItems(accountId: Long, amount: Double): List<AccountTransaction> {
        val window = 24 * 60 * 60 * 1000L // 24h
        val now = System.currentTimeMillis()
        return txnDao.getUnlinkedInTransactionsOnce(accountId, now - window, amount * 0.95, amount * 1.05)
    }

    /**
     * Completes a Cash -> Account transfer by linking an existing 'credit' transaction
     * in the target account to a new 'debit' transaction in Cash.
     */
    suspend fun linkCashToExistingCredit(targetTxnId: Long, amount: Double): Boolean {
        val creditTxn = txnDao.findByIdOnce(targetTxnId) ?: return false
        val cash = ensureCashAccount()
        database.withTransaction {
            val transferId = transferDao.insert(
                Transfer(
                    fromAccountId = cash.id,
                    toAccountId = creditTxn.accountId,
                    amount = amount,
                    occurredAt = creditTxn.occurredAt
                )
            )
            // Update the existing bank 'IN' transaction to point to this transfer
            txnDao.updateTransferId(targetTxnId, transferId)
            // Insert the mirror 'OUT' transaction in Cash
            txnDao.insert(
                AccountTransaction(
                    accountId = cash.id,
                    direction = AccountTransaction.DIRECTION_OUT,
                    amount = amount,
                    occurredAt = creditTxn.occurredAt,
                    category = com.finmagic.data.sms.parsers.TxnCategory.FUND_TRANSFER,
                    counterparty = "Transfer to ${accountDao.findByIdOnce(creditTxn.accountId)?.displayName}",
                    transferId = transferId,
                    isManual = true
                )
            )
        }
        return true
    }

    /**
     * Directly overrides the account balance to [targetBalance] by inserting a single
     * "DirectSet" entry that makes computed balance equal the target, WITHOUT
     * creating a visible adjustment transaction in the ledger.
     *
     * This deletes any existing "direct-set" entry before inserting a fresh one so there
     * is never more than one such entry at a time.
     */
    suspend fun setCashBalanceDirect(targetBalance: Double, accountId: Long? = null): Boolean {
        val targetId = accountId ?: ensureCashAccount().id
        database.withTransaction {
            val account = accountDao.findByIdOnce(targetId)
            val isNonCash = account != null && account.kind != Account.KIND_CASH
            // Remove old direct-set entries
            txnDao.deleteDirectSetEntries(targetId)
            // Compute natural balance (without the direct-set)
            val txns = txnDao.getByAccountOnce(targetId)
            val diff = calculateAdjustmentDelta(targetBalance, txns)
            // Always create a DirectSet entry, even if diff is small
            val direction = if (diff >= 0) AccountTransaction.DIRECTION_IN else AccountTransaction.DIRECTION_OUT
            txnDao.insert(
                AccountTransaction(
                    accountId = targetId,
                    direction = direction,
                    amount = kotlin.math.abs(diff),
                    balanceAfter = if (isNonCash) targetBalance else null,
                    occurredAt = System.currentTimeMillis(),
                    category = "DirectSet",
                    counterparty = "Balance Set",
                    isManual = true
                )
            )
        }
        return true
    }
}

