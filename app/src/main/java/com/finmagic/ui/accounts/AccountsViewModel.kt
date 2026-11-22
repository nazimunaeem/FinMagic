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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

internal fun monthTrendPercent(current: Double, previous: Double): Double {
    if (previous <= 0.0) return 0.0
    return ((current - previous) / previous) * 100.0
}

data class AccountsUiState(
    val summaries: List<AccountSummary> = emptyList(),
    val monthIn: Double = 0.0,
    val monthOut: Double = 0.0,
    val prevMonthIn: Double = 0.0,
    val prevMonthOut: Double = 0.0,
    val totalBalance: Double = 0.0,
    val expensesByCategory: List<CategoryTotal> = emptyList(),
    val incomeByCategory: List<CategoryTotal> = emptyList(),
    val archivedAccounts: List<Account> = emptyList(),
    val recentTransactions: List<AccountTransactionWithAccount> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AccountsViewModel @Inject constructor(
    application: Application,
    private val accountRepository: AccountRepository,
    private val preferenceManager: PreferenceManager
) : AndroidViewModel(application) {

    private val now = Calendar.getInstance()
    private val _selectedYear = MutableStateFlow(now.get(Calendar.YEAR))
    private val _selectedMonth = MutableStateFlow(now.get(Calendar.MONTH))

    val selectedYear: StateFlow<Int> = _selectedYear.asStateFlow()
    val selectedMonth: StateFlow<Int> = _selectedMonth.asStateFlow()

    private val monthRange: Flow<Pair<Long, Long>> =
        combine(_selectedYear, _selectedMonth) { year, month ->
            val start = startOfMonthMillis(year, month)
            val end = endOfMonthMillis(year, month)
            start to end
        }

    val uiState: StateFlow<AccountsUiState> =
        monthRange.flatMapLatest { (ms, me) ->
            combine(
                accountRepository.observeSummaries(ms, me),
                accountRepository.observeMonthlyExpenseByCategory(ms, me),
                accountRepository.observeMonthlyIncomeByCategory(ms, me),
                accountRepository.observeArchived(),
                accountRepository.observeAllTransactionsWithAccount()
            ) { summaries, expenses, income, archived, transactions ->
                val counted = summaries.filter { it.account.includeInTotal }
                val currentMonthIn = counted.sumOf { it.monthIn }
                val currentMonthOut = counted.sumOf { it.monthOut }

                val prevCal = Calendar.getInstance().apply {
                    timeInMillis = ms
                    add(Calendar.MONTH, -1)
                }
                val prevStart = startOfMonthMillis(prevCal.get(Calendar.YEAR), prevCal.get(Calendar.MONTH))
                val prevEnd = ms

                val prevTransactions = transactions.filter { it.occurredAt >= prevStart && it.occurredAt < prevEnd }
                val prevMonthIn = prevTransactions.filter { it.direction == AccountTransaction.DIRECTION_IN }.sumOf { it.amount }
                val prevMonthOut = prevTransactions.filter { it.direction == AccountTransaction.DIRECTION_OUT }.sumOf { it.amount }

                AccountsUiState(
                    summaries = summaries,
                    monthIn = currentMonthIn,
                    monthOut = currentMonthOut,
                    prevMonthIn = prevMonthIn,
                    prevMonthOut = prevMonthOut,
                    totalBalance = counted.sumOf { it.displayBalance },
                    expensesByCategory = expenses.filter { it.total > 0.0 },
                    incomeByCategory = income.filter { it.total > 0.0 },
                    archivedAccounts = archived,
                    recentTransactions = transactions
                )
            }
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccountsUiState())

    fun setSelectedMonth(year: Int, month: Int) {
        _selectedYear.value = year
        _selectedMonth.value = month
    }

    fun stepPreviousMonth() {
        var m = _selectedMonth.value - 1
        var y = _selectedYear.value
        if (m < 0) {
            m = 11
            y -= 1
        }
        setSelectedMonth(y, m)
    }

    fun stepNextMonth() {
        val cur = Calendar.getInstance()
        val curY = cur.get(Calendar.YEAR)
        val curM = cur.get(Calendar.MONTH)
        var m = _selectedMonth.value + 1
        var y = _selectedYear.value
        if (m > 11) {
            m = 0
            y += 1
        }
        if (y > curY || (y == curY && m > curM)) return
        setSelectedMonth(y, m)
    }

    fun restoreAccount(accountId: Long) {
        viewModelScope.launch { accountRepository.restoreAccount(accountId) }
    }

    fun ensureCashAccount() {
        viewModelScope.launch { accountRepository.ensureCashAccount() }
    }

    val categoryNicknames: StateFlow<Map<String, String>> =
        preferenceManager.categoryNicknames
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    suspend fun findPotentialCreditItems(accountId: Long, amount: Double): List<AccountTransaction> {
        return accountRepository.findPotentialCreditItems(accountId, amount)
    }

    fun addManualTransfer(
        targetAccountId: Long,
        amount: Double,
        existingCreditId: Long?,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            if (existingCreditId != null) {
                accountRepository.linkCashToExistingCredit(existingCreditId, amount)
            } else {
                accountRepository.addManualTransfer(
                    otherAccountId = targetAccountId,
                    fromCash = true,
                    amount = amount,
                    occurredAt = occurredAt
                )
            }
        }
    }

    fun addCashExpense(
        amount: Double,
        note: String?,
        category: String? = null,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            accountRepository.addManualCashEntry(
                direction = AccountTransaction.DIRECTION_OUT,
                amount = amount,
                category = category?.ifBlank { null } ?: "Manual",
                counterparty = note,
                occurredAt = occurredAt
            )
        }
    }

    fun addCashIncome(
        amount: Double,
        note: String?,
        category: String? = null,
        occurredAt: Long = System.currentTimeMillis()
    ) {
        viewModelScope.launch {
            accountRepository.addManualCashEntry(
                direction = AccountTransaction.DIRECTION_IN,
                amount = amount,
                category = category?.ifBlank { null } ?: "Manual",
                counterparty = note,
                occurredAt = occurredAt
            )
        }
    }

    fun deleteTransaction(txnId: Long) {
        viewModelScope.launch {
            accountRepository.deleteEntry(txnId)
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

    private fun startOfMonthMillis(year: Int, month: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun endOfMonthMillis(year: Int, month: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MONTH, 1)
        }.timeInMillis
}