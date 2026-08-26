package com.finmagic.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.dao.AccountTransactionWithAccount
import com.finmagic.data.local.dao.CategoryTotal
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountSummary
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class AccountsUiState(
    val summaries: List<AccountSummary> = emptyList(),
    val monthIn: Double = 0.0,
    val monthOut: Double = 0.0,
    val totalBalance: Double = 0.0,
    val expensesByCategory: List<CategoryTotal> = emptyList(),
    val recentTransactions: List<AccountTransactionWithAccount> = emptyList()
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    application: Application,
    private val accountRepository: AccountRepository,
    private val preferenceManager: PreferenceManager
) : AndroidViewModel(application) {

    private val monthStart: Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val uiState: StateFlow<AccountsUiState> =
        combine(
            accountRepository.observeSummaries(monthStart),
            accountRepository.observeMonthlyExpenseByCategory(monthStart),
            accountRepository.observeAllTransactionsWithAccount()
        ) { summaries, expenses, transactions ->
            val counted = summaries.filter { it.account.includeInTotal }
            AccountsUiState(
                summaries = summaries,
                monthIn = counted.sumOf { it.monthIn },
                monthOut = counted.sumOf { it.monthOut },
                totalBalance = counted.sumOf { it.displayBalance },
                expensesByCategory = expenses.filter { it.total > 0.0 },
                recentTransactions = transactions
            )
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsUiState())

    fun ensureCashAccount() {
        viewModelScope.launch { accountRepository.ensureCashAccount() }
    }

    val categoryNicknames: StateFlow<Map<String, String>> =
        preferenceManager.categoryNicknames
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    suspend fun findPotentialCreditItems(accountId: Long, amount: Double): List<AccountTransaction> {
        return accountRepository.findPotentialCreditItems(accountId, amount)
    }

    fun addManualTransfer(targetAccountId: Long, amount: Double, existingCreditId: Long?) {
        viewModelScope.launch {
            if (existingCreditId != null) {
                accountRepository.linkCashToExistingCredit(existingCreditId, amount)
            } else {
                accountRepository.addManualTransfer(
                    otherAccountId = targetAccountId,
                    fromCash = true,
                    amount = amount,
                    occurredAt = System.currentTimeMillis()
                )
            }
        }
    }

    fun addCashExpense(amount: Double, note: String?, category: String? = null) {
        viewModelScope.launch {
            accountRepository.addManualCashEntry(
                direction = AccountTransaction.DIRECTION_OUT,
                amount = amount,
                category = category?.ifBlank { null } ?: "Manual",
                counterparty = note,
                occurredAt = System.currentTimeMillis()
            )
        }
    }

    fun addCashIncome(amount: Double, note: String?, category: String? = null) {
        viewModelScope.launch {
            accountRepository.addManualCashEntry(
                direction = AccountTransaction.DIRECTION_IN,
                amount = amount,
                category = category?.ifBlank { null } ?: "Manual",
                counterparty = note,
                occurredAt = System.currentTimeMillis()
            )
        }
    }

    fun deleteTransaction(txnId: Long) {
        viewModelScope.launch {
            accountRepository.deleteManualEntry(txnId)
        }
    }

    fun setExpenseOverride(txnId: Long, value: Int) {
        viewModelScope.launch {
            accountRepository.setExpenseOverride(txnId, value)
        }
    }

    fun updateTransactionCategory(txnId: Long, category: String) {
        viewModelScope.launch {
            accountRepository.updateTransactionCategory(txnId, category)
        }
    }

    fun updateTransactionDetails(txnId: Long, counterparty: String?, category: String?, amount: Double) {
        viewModelScope.launch {
            accountRepository.updateTransactionDetails(txnId, counterparty, category, amount)
        }
    }

    /** Adjusts cash balance by inserting a visible adjustment transaction (keeps history). */
    fun adjustCashBalance(targetBalance: Double) {
        viewModelScope.launch { accountRepository.adjustCashBalance(targetBalance) }
    }

    /** Sets cash balance directly — replaces any previous DirectSet entry (no adjustment trail). */
    fun setCashBalanceDirect(targetBalance: Double) {
        viewModelScope.launch { accountRepository.setCashBalanceDirect(targetBalance) }
    }
}
