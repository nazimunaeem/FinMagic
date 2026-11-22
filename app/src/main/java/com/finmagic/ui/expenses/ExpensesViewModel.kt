package com.finmagic.ui.expenses

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.repository.ExpenseItem
import com.finmagic.data.repository.ExpenseRepository
import com.finmagic.data.repository.ExpenseStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

data class ExpensesUiState(
    val year: Int,
    val month: Int, // 1-12
    val items: List<ExpenseItem> = emptyList(),
    val stats: ExpenseStats? = null,
    val loading: Boolean = false
)

@HiltViewModel
class ExpensesViewModel @Inject constructor(
    private val repository: ExpenseRepository,
    private val preferenceManager: PreferenceManager
) : ViewModel() {

    private val calendar = Calendar.getInstance()
    private val defaultYears = (calendar.get(Calendar.YEAR) downTo 2020).toList()

    private val _uiState = MutableStateFlow(
        ExpensesUiState(
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH) + 1
        )
    )
    val uiState: StateFlow<ExpensesUiState> = _uiState

    /** Years selectable in the picker, most recent first. Seeded lazily from real data. */
    private val _availableYears = MutableStateFlow(defaultYears)
    val availableYears: StateFlow<List<Int>> = _availableYears

    val categoryNicknames: StateFlow<Map<String, String>> =
        preferenceManager.categoryNicknames
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    init {
        load()
        viewModelScope.launch {
            repository.earliestYear()?.let { earliest ->
                val years = (calendar.get(Calendar.YEAR) downTo earliest).toList()
                if (years != _availableYears.value) {
                    _availableYears.value = years
                }
            }
        }
    }

    fun setMonth(year: Int, month: Int) {
        _uiState.update { it.copy(year = year, month = month) }
        load()
    }

    fun stepPrevious() {
        val (y, m) = with(_uiState.value) {
            if (month == 1) year - 1 to 12 else year to month - 1
        }
        setMonth(y, m)
    }

    fun stepNext() {
        val now = Calendar.getInstance()
        val (y, m) = with(_uiState.value) {
            if (month == 12) year + 1 to 1 else year to month + 1
        }
        if (y > now.get(Calendar.YEAR) || (y == now.get(Calendar.YEAR) && m > now.get(Calendar.MONTH) + 1)) return
        setMonth(y, m)
    }

    fun setExpenseOverride(accountTxnId: Long, value: Int) {
        viewModelScope.launch {
            repository.setExpenseOverride(accountTxnId, value)
            load()
        }
    }

    fun refresh() {
        load()
    }

    private fun monthRange(year: Int, month: Int): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        val end = cal.timeInMillis - 1
        return start to end
    }

    private fun load() {
        val (year, month) = _uiState.value
        val (start, end) = monthRange(year, month)
        val prev = if (month == 1) monthRange(year - 1, 12) else monthRange(year, month - 1)
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            val (items, stats) = repository.getExpenses(start, end, prev.first, prev.second)
            _uiState.update { it.copy(items = items, stats = stats, loading = false) }
        }
    }
}
