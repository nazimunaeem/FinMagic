package com.finmagic.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.AccountTypeConfig
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.local.dao.AccountTransactionWithAccount
import com.finmagic.data.local.dao.CategoryTotal
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountSummary
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.BillRepository
import com.finmagic.data.repository.CardRepository
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

data class CardOutstandingItem(
    val card: CreditCard,
    val unpaidAmount: Double,
    val unpaidBillCount: Int,
    val currency: String = "BDT"
)

data class AccountsUiState(
    val summaries: List<AccountSummary> = emptyList(),
    val monthIn: Double = 0.0,
    val monthOut: Double = 0.0,
    val prevMonthIn: Double = 0.0,
    val prevMonthOut: Double = 0.0,
    val totalBalance: Double = 0.0,
    val assetsTotal: Double = 0.0,
    val accountLiabilitiesTotal: Double = 0.0,
    val cardLiabilityTotal: Double = 0.0,
    val cardLiabilityCount: Int = 0,
    val cardOutstandingItems: List<CardOutstandingItem> = emptyList(),
    val allAccountTypes: List<AccountTypeConfig> = emptyList(),
    val customAccountTypes: List<AccountTypeConfig> = emptyList(),
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
    private val preferenceManager: PreferenceManager,
    private val billRepository: BillRepository,
    private val cardRepository: CardRepository
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

    private data class LedgerBundle(
        val summaries: List<AccountSummary>,
        val expenses: List<CategoryTotal>,
        val income: List<CategoryTotal>,
        val archived: List<Account>,
        val transactions: List<AccountTransactionWithAccount>
    )

    private data class TypesAndCardsBundle(
        val allTypes: List<AccountTypeConfig>,
        val customTypes: List<AccountTypeConfig>,
        val unpaidBills: List<com.finmagic.data.local.entity.BillWithCard>,
        val cards: List<CreditCard>
    )

    val uiState: StateFlow<AccountsUiState> =
        monthRange.flatMapLatest { (ms, me) ->
            val ledgerFlow = combine(
                accountRepository.observeSummaries(ms, me),
                accountRepository.observeMonthlyExpenseByCategory(ms, me),
                accountRepository.observeMonthlyIncomeByCategory(ms, me),
                accountRepository.observeArchived(),
                accountRepository.observeAllTransactionsWithAccount()
            ) { summaries, expenses, income, archived, transactions ->
                LedgerBundle(summaries, expenses, income, archived, transactions)
            }
            val metaFlow = combine(
                preferenceManager.allAccountTypes,
                preferenceManager.customAccountTypes,
                billRepository.getUnpaidBills(),
                cardRepository.getAllCards()
            ) { allTypes, customTypes, unpaidBills, cards ->
                TypesAndCardsBundle(allTypes, customTypes, unpaidBills, cards)
            }
            combine(ledgerFlow, metaFlow) { ledger, meta ->
                val counted = ledger.summaries.filter { it.account.includeInTotal }
                val currentMonthIn = counted.sumOf { it.monthIn }
                val currentMonthOut = counted.sumOf { it.monthOut }

                val prevCal = Calendar.getInstance().apply {
                    timeInMillis = ms
                    add(Calendar.MONTH, -1)
                }
                val prevStart = startOfMonthMillis(prevCal.get(Calendar.YEAR), prevCal.get(Calendar.MONTH))
                val prevEnd = ms

                val prevTransactions = ledger.transactions.filter { it.occurredAt >= prevStart && it.occurredAt < prevEnd }
                val prevMonthIn = prevTransactions.filter { it.direction == AccountTransaction.DIRECTION_IN }.sumOf { it.amount }
                val prevMonthOut = prevTransactions.filter { it.direction == AccountTransaction.DIRECTION_OUT }.sumOf { it.amount }

                val assetsTotal = counted.filter { !it.account.isLiability }.sumOf { it.displayBalance }
                val accountLiabilitiesTotal = counted.filter { it.account.isLiability }.sumOf { kotlin.math.abs(it.displayBalance) }

                val unpaidByCard = meta.unpaidBills.groupBy { it.cardId }
                val cardItems = meta.cards.map { card ->
                    val cardBills = unpaidByCard[card.id].orEmpty()
                    val unpaid = cardBills.sumOf { maxOf(0.0, it.totalDue - it.paidAmount) }
                    CardOutstandingItem(
                        card = card,
                        unpaidAmount = unpaid,
                        unpaidBillCount = cardBills.size,
                        currency = card.creditLimitCurrency.ifBlank { "BDT" }
                    )
                }.sortedByDescending { it.unpaidAmount }

                val cardLiabilityTotal = meta.unpaidBills.sumOf { maxOf(0.0, it.totalDue - it.paidAmount) }
                val netBalance = assetsTotal - accountLiabilitiesTotal - cardLiabilityTotal

                AccountsUiState(
                    summaries = ledger.summaries,
                    monthIn = currentMonthIn,
                    monthOut = currentMonthOut,
                    prevMonthIn = prevMonthIn,
                    prevMonthOut = prevMonthOut,
                    totalBalance = netBalance,
                    assetsTotal = assetsTotal,
                    accountLiabilitiesTotal = accountLiabilitiesTotal,
                    cardLiabilityTotal = cardLiabilityTotal,
                    cardLiabilityCount = meta.cards.size,
                    cardOutstandingItems = cardItems,
                    allAccountTypes = meta.allTypes,
                    customAccountTypes = meta.customTypes,
                    expensesByCategory = ledger.expenses.filter { it.total > 0.0 },
                    incomeByCategory = ledger.income.filter { it.total > 0.0 },
                    archivedAccounts = ledger.archived,
                    recentTransactions = ledger.transactions
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

    fun createAccount(
        name: String,
        accountType: String,
        isLiability: Boolean,
        bankName: String = "",
        accountMask: String = "",
        currency: String = "BDT",
        initialBalance: Double = 0.0,
        includeInTotal: Boolean = true
    ) {
        viewModelScope.launch {
            accountRepository.createManualAccount(
                name = name,
                accountType = accountType,
                isLiability = isLiability,
                bankName = bankName,
                accountMask = accountMask,
                currency = currency,
                initialBalance = initialBalance,
                includeInTotal = includeInTotal
            )
        }
    }

    fun addCustomAccountType(name: String, isLiability: Boolean) {
        viewModelScope.launch { preferenceManager.addCustomAccountType(name, isLiability) }
    }

    fun removeCustomAccountType(name: String) {
        viewModelScope.launch { preferenceManager.removeCustomAccountType(name) }
    }

    fun updateAccountDetails(
        accountId: Long,
        newName: String,
        accountType: String,
        isLiability: Boolean,
        includeInTotal: Boolean
    ) {
        viewModelScope.launch {
            accountRepository.updateAccountDetails(accountId, newName, accountType, isLiability, includeInTotal)
        }
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