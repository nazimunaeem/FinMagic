package com.finmagic.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

sealed class ExportState {
    data object Idle : ExportState()
    data object Loading : ExportState()
    data class Success(val file: File) : ExportState()
    data class Error(val message: String) : ExportState()
}

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val accountRepository: AccountRepository,
    private val preferenceManager: PreferenceManager
) : AndroidViewModel(application) {

    // Route declares NavType.LongType → SavedStateHandle stores a Long; reading it
    // as String throws ClassCastException (crash on opening the detail screen).
    val accountId: Long = savedStateHandle.get<Long>("accountId")
        ?: savedStateHandle.get<String>("accountId")?.toLongOrNull()
        ?: 0L

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _balance = MutableStateFlow(0.0)
    val balance: StateFlow<Double> = _balance.asStateFlow()

    val transactions: StateFlow<List<AccountTransaction>> =
        accountRepository.observeTransactions(accountId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    val categoryNicknames: StateFlow<Map<String, String>> =
        preferenceManager.categoryNicknames
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _allAccounts = MutableStateFlow<List<Account>>(emptyList())
    val allAccounts: StateFlow<List<Account>> = _allAccounts.asStateFlow()

    init {
        viewModelScope.launch {
            _account.value = accountRepository.getAccountOnce(accountId)
            _allAccounts.value = accountRepository.getAllAccountsOnce()
            refreshBalance()
        }
        // Auto-refresh balance whenever transactions change
        viewModelScope.launch {
            transactions.collect { refreshBalance() }
        }
    }

    private suspend fun refreshBalance() {
        val acct = _account.value ?: return
        val latest = accountRepository.latestBalance(accountId)
        if (acct.kind != Account.KIND_CASH && latest != null) {
            _balance.value = latest
        } else {
            val txns = accountRepository.getAllTransactionsOnce(accountId)
            _balance.value = AccountRepository.effectiveBalance(txns)
        }
    }

    fun addCashEntry(
        direction: String,
        amount: Double,
        counterparty: String?,
        category: String? = null,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            accountRepository.addManualCashEntry(
                direction = direction,
                amount = amount,
                category = category?.ifBlank { null },
                counterparty = counterparty?.ifBlank { null },
                occurredAt = occurredAt
            )
            refreshBalance()
        }
    }

    fun addTransfer(otherAccountId: Long, fromCash: Boolean, amount: Double) {
        viewModelScope.launch {
            accountRepository.addManualTransfer(otherAccountId, fromCash, amount, System.currentTimeMillis())
            refreshBalance()
        }
    }

    fun deleteEntry(txnId: Long) {
        viewModelScope.launch { accountRepository.deleteEntry(txnId) }
    }

    fun setExpenseOverride(txnId: Long, value: Int) {
        viewModelScope.launch { accountRepository.setExpenseOverride(txnId, value) }
    }

    fun updateTransactionCategory(txnId: Long, category: String) {
        viewModelScope.launch { accountRepository.updateTransactionCategory(txnId, category) }
    }

    fun updateTransactionDetails(txnId: Long, counterparty: String?, category: String?, amount: Double) {
        viewModelScope.launch { accountRepository.updateTransactionDetails(txnId, counterparty, category, amount) }
    }

    fun correctTransaction(
        txnId: Long,
        direction: String,
        amount: Double,
        accountId: Long,
        category: String,
        counterparty: String,
        balanceAfter: Double? = null
    ) {
        viewModelScope.launch {
            accountRepository.correctTransaction(
                txnId, direction, amount, accountId,
                category.ifBlank { null }, counterparty.ifBlank { null }, balanceAfter
            )
        }
    }

    fun ignoreTransaction(txnId: Long) {
        viewModelScope.launch {
            accountRepository.ignoreParsedTransaction(txnId)
        }
    }

    fun renameAccount(newName: String) {
        viewModelScope.launch {
            if (accountRepository.renameAccount(accountId, newName)) {
                _account.value = accountRepository.getAccountOnce(accountId)
            }
        }
    }

    fun setIncludeInTotal(value: Boolean) {
        viewModelScope.launch {
            accountRepository.setIncludeInTotal(accountId, value)
            _account.value = accountRepository.getAccountOnce(accountId)
        }
    }

    fun deleteAccount(onSuccess: () -> Unit) {
        viewModelScope.launch {
            if (accountRepository.deleteAccount(accountId)) {
                onSuccess()
            }
        }
    }

    fun adjustCashBalance(targetBalance: Double) {
        viewModelScope.launch {
            accountRepository.adjustCashBalance(targetBalance, accountId)
            refreshBalance()
        }
    }

    fun setCashBalanceDirect(targetBalance: Double) {
        viewModelScope.launch {
            accountRepository.setCashBalanceDirect(targetBalance, accountId)
            refreshBalance()
        }
    }

    suspend fun getAllAccountsOnce(): List<Account> = accountRepository.getAllAccountsOnce()

    fun exportCsv(startMillis: Long? = null, endMillis: Long? = null) {
        viewModelScope.launch {
            _exportState.value = ExportState.Loading
            try {
                val context = getApplication<Application>()
                val account = accountRepository.getAccountOnce(accountId)
                val txns = accountRepository.getTransactionsOnce(accountId)
                    .filter { txn ->
                        val afterStart = startMillis == null || txn.occurredAt >= startMillis
                        val beforeEnd = endMillis == null || txn.occurredAt <= endMillis
                        afterStart && beforeEnd
                    }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val safeName = (account?.displayName ?: "account").replace(Regex("""[^A-Za-z0-9_-]"""), "")
                val file = File(context.filesDir, "${safeName}_ledger_$stamp.csv")

                file.bufferedWriter().use { writer ->
                    writer.appendLine("Date,Direction,Amount,Currency,Balance After,Category,Counterparty")
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    for (txn in txns.sortedBy { it.occurredAt }) {
                        writer.appendLine(
                            listOf(
                                df.format(Date(txn.occurredAt)),
                                txn.direction,
                                txn.amount.toString(),
                                txn.currency,
                                txn.balanceAfter?.toString() ?: "",
                                txn.category ?: "",
                                (txn.counterparty ?: "").replace(",", " ")
                            ).joinToString(",")
                        )
                    }
                }
                _exportState.value = ExportState.Success(file)
            } catch (e: Exception) {
                _exportState.value = ExportState.Error(e.message ?: "Export failed")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }
}
