package com.finmagic.data.repository

import androidx.room.withTransaction
import com.finmagic.data.local.AppDatabase
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.AccountTransactionDao
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
    private val simResolver: com.finmagic.data.sms.SimAccountResolver
) {

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
        is SmsClassification.AtmWithdraw -> storeAtmWithdraw(classification.entry, smsBody, smsRecordId)
        is SmsClassification.WalletToCash -> storeTransferPair(classification.from, classification.to, smsBody, smsRecordId)
        is SmsClassification.AcctTxn -> storeSingle(classification.entry, smsBody, smsRecordId)
        is SmsClassification.WalletTxn -> storeSingle(classification.entry, smsBody, smsRecordId)
        else -> false
    }

    suspend fun storeManualToAccount(
        accountId: Long,
        direction: String,
        amount: Double,
        occurredAt: Long,
        category: String,
        counterparty: String?,
        smsBody: String,
        smsRecordId: Long?
    ): Boolean {
        if (amount <= 0.0 || txnDao.existsBySmsBody(smsBody)) return false
        accountDao.findByIdOnce(accountId) ?: return false
        database.withTransaction {
            txnDao.insert(
                AccountTransaction(
                    accountId = accountId,
                    direction = direction,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = category,
                    counterparty = counterparty,
                    smsRecordId = smsRecordId,
                    smsBody = smsBody,
                    isManual = true
                )
            )
        }
        return true
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
    suspend fun storeAtmWithdraw(entry: LedgerEntry, smsBody: String, smsRecordId: Long?): Boolean {
        if (txnDao.existsBySmsBody(smsBody)) return false
        val bank = resolveAccount(entry) ?: return false
        val cash = ensureCashAccount()
        database.withTransaction {
            val transferId = transferDao.insert(
                Transfer(
                    fromAccountId = bank.id,
                    toAccountId = cash.id,
                    amount = entry.amount,
                    occurredAt = entry.occurredAt ?: System.currentTimeMillis()
                )
            )
            checkAndInsertGapAdjustment(bank.id, entry)
            txnDao.insert(entry.toEntity(bank.id, smsRecordId, smsBody, transferId))
            txnDao.insert(cashInEntry(entry, cash.id, "${bank.displayName} ATM", transferId))
        }
        return true
    }

    /** Generic two-account movement (bank→wallet funding, wallet→cash out). */
    suspend fun storeTransferPair(
        from: LedgerEntry,
        to: LedgerEntry,
        smsBody: String,
        smsRecordId: Long?
    ): Boolean {
        if (txnDao.existsBySmsBody(smsBody)) return false
        val fromAccount = resolveAccount(from) ?: return false
        val toAccount = if (to.kind == com.finmagic.data.sms.parsers.AccountKind.CASH) {
            ensureCashAccount()
        } else {
            resolveAccount(to) ?: return false
        }
        if (fromAccount.id == toAccount.id) return false
        database.withTransaction {
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
            txnDao.insert(from.toEntity(fromAccount.id, smsRecordId, smsBody, transferId))
            checkAndInsertGapAdjustment(toAccount.id, to)
            txnDao.insert(to.toEntity(toAccount.id, smsRecordId, smsBody, transferId))
        }
        return true
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
        database.withTransaction {
            txnDao.insert(
                AccountTransaction(
                    accountId = cash.id,
                    direction = direction,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = category ?: com.finmagic.data.sms.parsers.TxnCategory.MANUAL,
                    counterparty = counterparty,
                    isManual = true
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
        counterparty: String?
    ): Boolean {
        if (amount <= 0.0) return false
        val txn = txnDao.findByIdOnce(txnId) ?: return false
        accountDao.findByIdOnce(accountId) ?: return false

        database.withTransaction {
            val sameAccount = txn.accountId == accountId
            val sameDirection = txn.direction == direction

            if (sameAccount && sameDirection) {
                // Simple update — no account or direction change.
                txnDao.updateTransactionDetails(
                    txnId,
                    counterparty?.ifBlank { null },
                    category?.ifBlank { null },
                    amount
                )
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
                        balanceAfter = txn.balanceAfter,
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

    /** Adjusts current Cash balance to the target amount by inserting a balance adjustment entry. */
    suspend fun adjustCashBalance(targetBalance: Double): Boolean {
        val cash = ensureCashAccount()
        database.withTransaction {
            val txns = txnDao.getAllByAccountOnce(cash.id)
            val currentBalance = txns.sumOf {
                if (it.direction == AccountTransaction.DIRECTION_IN) it.amount else -it.amount
            }
            val diff = targetBalance - currentBalance
            if (kotlin.math.abs(diff) >= 0.01) {
                val direction = if (diff > 0) AccountTransaction.DIRECTION_IN else AccountTransaction.DIRECTION_OUT
                val amount = kotlin.math.abs(diff)
                txnDao.insert(
                    AccountTransaction(
                        accountId = cash.id,
                        direction = direction,
                        amount = amount,
                        occurredAt = System.currentTimeMillis(),
                        category = com.finmagic.data.sms.parsers.TxnCategory.ADJUSTMENT,
                        counterparty = "Cash Balance Adjustment",
                        isManual = true
                    )
                )
            }
        }
        return true
    }

    // ---------------------------------------------------------------- queries

    fun observeSummaries(monthStart: Long): Flow<List<com.finmagic.data.local.entity.AccountSummary>> =
        kotlinx.coroutines.flow.combine(accountDao.observeAll(), txnDao.observeSummariesRaw(monthStart)) { accounts, rows ->
            val byId = rows.associateBy { it.accountId }
            accounts.mapNotNull { account ->
                byId[account.id]?.let { row ->
                    com.finmagic.data.local.entity.AccountSummary(
                        account = account,
                        latestBalance = row.latestBalance,
                        computedBalance = row.computedBalance,
                        monthIn = row.monthIn,
                        monthOut = row.monthOut
                    )
                }
            }
        }

    fun observeTransactions(accountId: Long): Flow<List<AccountTransaction>> =
        txnDao.observeByAccount(accountId)

    fun observeAllTransactions(): Flow<List<AccountTransaction>> =
        txnDao.observeAll()

    fun observeAllTransactionsWithAccount(): Flow<List<com.finmagic.data.local.dao.AccountTransactionWithAccount>> =
        txnDao.observeAllWithAccount()

    /** Monthly expense totals per category across all accounts (plan Phase 5). */
    fun observeMonthlyExpenseByCategory(monthStart: Long): Flow<List<com.finmagic.data.local.dao.CategoryTotal>> =
        txnDao.observeMonthlyExpenseByCategory(monthStart)

    suspend fun getTransactionsOnce(accountId: Long): List<AccountTransaction> =
        txnDao.getByAccountOnce(accountId)

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
            // Remove old direct-set entries
            txnDao.deleteDirectSetEntries(targetId)
            // Compute natural balance (without the direct-set)
            val txns = txnDao.getByAccountOnce(targetId)
            val naturalBalance = txns.sumOf {
                if (it.direction == AccountTransaction.DIRECTION_IN) it.amount else -it.amount
            }
            val diff = targetBalance - naturalBalance
            if (kotlin.math.abs(diff) >= 0.01) {
                val direction = if (diff > 0) AccountTransaction.DIRECTION_IN else AccountTransaction.DIRECTION_OUT
                txnDao.insert(
                    AccountTransaction(
                        accountId = targetId,
                        direction = direction,
                        amount = kotlin.math.abs(diff),
                        occurredAt = System.currentTimeMillis(),
                        category = "DirectSet",
                        counterparty = "Balance Set",
                        isManual = true
                    )
                )
            }
        }
        return true
    }
}

