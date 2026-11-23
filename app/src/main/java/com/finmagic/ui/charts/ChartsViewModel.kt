package com.finmagic.ui.charts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.AccountTypeConfig
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.AccountTransactionDao
import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

data class ChartsUiState(
    val granularity: ChartGranularity = ChartGranularity.DAILY,
    val selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    val selectedMonth: Int = Calendar.getInstance().get(Calendar.MONTH),
    val periodLabel: String = "",
    val canStepNext: Boolean = false,
    val compareOverlayEnabled: Boolean = true,
    val comparisonOverlayLabel: String = "",
    val currentNetAsset: Double = 0.0,
    val currentAssets: Double = 0.0,
    val currentLiabilities: Double = 0.0,
    val periodChangeAmount: Double = 0.0,
    val periodChangePercent: Double? = null,
    val netAssetSeries: List<NetAssetPoint> = emptyList(),
    val netAssetComparisonSeries: List<NetAssetPoint> = emptyList(),
    val cashflowSeries: List<CashflowPoint> = emptyList(),
    val totalIncome: Double = 0.0,
    val totalExpense: Double = 0.0,
    val categoryShowIncome: Boolean = false,
    val categorySlices: List<CategoryChartSlice> = emptyList(),
    val allocationSlices: List<AllocationSlice> = emptyList(),
    val categoryNicknames: Map<String, String> = emptyMap(),
    val availableYears: List<Int> = emptyList(),
    val compareYearlyMode: Boolean = false,
    val periodAYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    val periodAMonth: Int = Calendar.getInstance().get(Calendar.MONTH),
    val periodBYear: Int = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.get(Calendar.YEAR),
    val periodBMonth: Int = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.get(Calendar.MONTH),
    val snapshotA: PeriodComparisonSnapshot? = null,
    val snapshotB: PeriodComparisonSnapshot? = null
)

@HiltViewModel
class ChartsViewModel @Inject constructor(
    application: Application,
    accountDao: AccountDao,
    accountTxnDao: AccountTransactionDao,
    billDao: BillDao,
    cardTxnDao: CardTransactionDao,
    preferenceManager: PreferenceManager
) : AndroidViewModel(application) {

    private val nowCal = Calendar.getInstance()
    private val initialYear = nowCal.get(Calendar.YEAR)
    private val initialMonth = nowCal.get(Calendar.MONTH)
    private val prevCal = (nowCal.clone() as Calendar).apply { add(Calendar.MONTH, -1) }

    private val _granularity = MutableStateFlow(ChartGranularity.DAILY)
    private val _selectedYear = MutableStateFlow(initialYear)
    private val _selectedMonth = MutableStateFlow(initialMonth)
    private val _compareOverlay = MutableStateFlow(true)
    private val _categoryShowIncome = MutableStateFlow(false)
    private val _compareYearlyMode = MutableStateFlow(false)
    private val _periodA = MutableStateFlow(initialYear to initialMonth)
    private val _periodB = MutableStateFlow(prevCal.get(Calendar.YEAR) to prevCal.get(Calendar.MONTH))

    private data class DbSnapshot(
        val accounts: List<Account>,
        val accountTxns: List<AccountTransaction>,
        val bills: List<BillWithCard>,
        val cardTxns: List<CardTransaction>,
        val accountTypes: List<AccountTypeConfig>,
        val nicknames: Map<String, String>
    )

    private data class SelectionState(
        val granularity: ChartGranularity,
        val year: Int,
        val month: Int,
        val compareOverlay: Boolean,
        val categoryShowIncome: Boolean,
        val compareYearlyMode: Boolean,
        val periodA: Pair<Int, Int>,
        val periodB: Pair<Int, Int>
    )

    private val dbFlow = combine(
        accountDao.observeAll(),
        accountTxnDao.observeAllIncludingDirectSet(),
        billDao.getAllBillsWithCards(),
        cardTxnDao.observeAll(),
        preferenceManager.allAccountTypes
    ) { accounts, accountTxns, bills, cardTxns, types ->
        DbSnapshot(accounts, accountTxns, bills, cardTxns, types, emptyMap())
    }.combine(preferenceManager.categoryNicknames) { snapshot, nicknames ->
        snapshot.copy(nicknames = nicknames)
    }

    private val mainControlsFlow = combine(
        _granularity,
        _selectedYear,
        _selectedMonth,
        _compareOverlay,
        _categoryShowIncome
    ) { g, y, m, cmp, catInc ->
        SelectionState(g, y, m, cmp, catInc, false, initialYear to initialMonth, initialYear to initialMonth)
    }

    private val compareControlsFlow = combine(
        _compareYearlyMode,
        _periodA,
        _periodB
    ) { yearly, pA, pB -> Triple(yearly, pA, pB) }

    private val controlsFlow = combine(mainControlsFlow, compareControlsFlow) { main, cmp ->
        main.copy(
            compareYearlyMode = cmp.first,
            periodA = cmp.second,
            periodB = cmp.third
        )
    }

    val uiState: StateFlow<ChartsUiState> = combine(dbFlow, controlsFlow) { db, sel ->
        val nowMillis = System.currentTimeMillis()
        val curCal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val curY = curCal.get(Calendar.YEAR)
        val curM = curCal.get(Calendar.MONTH)

        val txnsByAccount = db.accountTxns.groupBy { it.accountId }
        val countedIds = db.accounts.filter { !it.isArchived && it.includeInTotal }.map { it.id }.toSet()

        val primarySeries = when (sel.granularity) {
            ChartGranularity.DAILY -> ChartsCalculator.buildDailyNetAssetSeries(
                sel.year, sel.month, db.accounts, txnsByAccount, db.bills, nowMillis
            )
            ChartGranularity.MONTHLY -> ChartsCalculator.buildMonthlyNetAssetSeries(
                sel.year, db.accounts, txnsByAccount, db.bills, nowMillis
            )
            ChartGranularity.YEARLY -> ChartsCalculator.buildYearlyNetAssetSeries(
                db.accounts, txnsByAccount, db.bills, nowMillis
            )
        }

        val (prevY, prevM) = previousMonth(sel.year, sel.month)
        val comparisonSeries = if (!sel.compareOverlay) {
            emptyList()
        } else when (sel.granularity) {
            ChartGranularity.DAILY -> ChartsCalculator.buildDailyNetAssetSeries(
                prevY, prevM, db.accounts, txnsByAccount, db.bills, nowMillis
            )
            ChartGranularity.MONTHLY -> ChartsCalculator.buildMonthlyNetAssetSeries(
                sel.year - 1, db.accounts, txnsByAccount, db.bills, nowMillis
            )
            ChartGranularity.YEARLY -> emptyList()
        }

        val comparisonLabel = when (sel.granularity) {
            ChartGranularity.DAILY -> monthYearText(prevY, prevM)
            ChartGranularity.MONTHLY -> (sel.year - 1).toString()
            ChartGranularity.YEARLY -> ""
        }

        val periodLabel = when (sel.granularity) {
            ChartGranularity.DAILY -> monthYearText(sel.year, sel.month)
            ChartGranularity.MONTHLY -> sel.year.toString()
            ChartGranularity.YEARLY -> {
                val firstY = primarySeries.firstOrNull()?.label ?: curY.toString()
                val lastY = primarySeries.lastOrNull()?.label ?: curY.toString()
                if (firstY == lastY) firstY else "$firstY – $lastY"
            }
        }

        val canStepNext = when (sel.granularity) {
            ChartGranularity.DAILY -> sel.year < curY || (sel.year == curY && sel.month < curM)
            ChartGranularity.MONTHLY -> sel.year < curY
            ChartGranularity.YEARLY -> false
        }

        val (liveNet, liveAssets, liveLiabilities) = ChartsCalculator.computeNetAssetAt(
            nowMillis, db.accounts, txnsByAccount, db.bills, nowMillis
        )
        val displayPoint = primarySeries.lastOrNull()
        val headlineNet = displayPoint?.netAsset ?: liveNet
        val headlineAssets = displayPoint?.assets ?: liveAssets
        val headlineLiabilities = displayPoint?.liabilities ?: liveLiabilities

        val previousAnchorNet = when (sel.granularity) {
            ChartGranularity.DAILY -> {
                val endPrevMonth = ChartsCalculator.endOfMonth(prevY, prevM)
                ChartsCalculator.computeNetAssetAt(endPrevMonth, db.accounts, txnsByAccount, db.bills, nowMillis).first
            }
            ChartGranularity.MONTHLY -> {
                val endPrevYear = ChartsCalculator.endOfYear(sel.year - 1)
                ChartsCalculator.computeNetAssetAt(endPrevYear, db.accounts, txnsByAccount, db.bills, nowMillis).first
            }
            ChartGranularity.YEARLY -> {
                if (primarySeries.size >= 2) primarySeries[primarySeries.size - 2].netAsset else 0.0
            }
        }
        val periodChange = headlineNet - previousAnchorNet
        val periodChangePct = if (kotlin.math.abs(previousAnchorNet) >= 1.0) {
            (periodChange / kotlin.math.abs(previousAnchorNet)) * 100.0
        } else null

        val cashflowSeries = ChartsCalculator.buildCashflowSeries(
            sel.granularity, sel.year, sel.month, db.accounts, db.accountTxns, db.cardTxns, nowMillis
        )
        val totalIncome = cashflowSeries.sumOf { it.income }
        val totalExpense = cashflowSeries.sumOf { it.expense }

        val (catStart, catEnd, prevCatStart, prevCatEnd) = when (sel.granularity) {
            ChartGranularity.DAILY -> {
                listOf(
                    ChartsCalculator.startOfMonth(sel.year, sel.month),
                    ChartsCalculator.endOfMonth(sel.year, sel.month),
                    ChartsCalculator.startOfMonth(prevY, prevM),
                    ChartsCalculator.endOfMonth(prevY, prevM)
                )
            }
            ChartGranularity.MONTHLY -> {
                listOf(
                    ChartsCalculator.startOfYear(sel.year),
                    ChartsCalculator.endOfYear(sel.year),
                    ChartsCalculator.startOfYear(sel.year - 1),
                    ChartsCalculator.endOfYear(sel.year - 1)
                )
            }
            ChartGranularity.YEARLY -> {
                listOf(
                    ChartsCalculator.startOfYear(curY),
                    ChartsCalculator.endOfYear(curY),
                    ChartsCalculator.startOfYear(curY - 1),
                    ChartsCalculator.endOfYear(curY - 1)
                )
            }
        }

        val categorySlices = ChartsCalculator.buildCategoryBreakdown(
            startMillis = catStart,
            endMillis = catEnd,
            prevStartMillis = prevCatStart,
            prevEndMillis = prevCatEnd,
            countedAccountIds = countedIds,
            allAccountTxns = db.accountTxns,
            allCardTxns = db.cardTxns,
            showIncome = sel.categoryShowIncome
        )

        val allocationSlices = ChartsCalculator.buildAllocationBreakdown(
            accounts = db.accounts,
            txnsByAccount = txnsByAccount,
            bills = db.bills,
            accountTypes = db.accountTypes
        )

        val earliestMillis = listOfNotNull(
            db.accountTxns.minOfOrNull { it.occurredAt },
            db.cardTxns.minOfOrNull { it.occurredAt },
            db.bills.minOfOrNull { it.detectedAt }
        ).minOrNull() ?: nowMillis
        val firstYear = Calendar.getInstance().apply { timeInMillis = earliestMillis }
            .get(Calendar.YEAR).coerceAtMost(curY)
        val availableYears = (firstYear..curY).toList().reversed()

        val snapshotA = ChartsCalculator.buildPeriodSnapshot(
            isYearly = sel.compareYearlyMode,
            year = sel.periodA.first,
            month = sel.periodA.second,
            accounts = db.accounts,
            txnsByAccount = txnsByAccount,
            bills = db.bills,
            allAccountTxns = db.accountTxns,
            allCardTxns = db.cardTxns,
            nowMillis = nowMillis
        )
        val snapshotB = ChartsCalculator.buildPeriodSnapshot(
            isYearly = sel.compareYearlyMode,
            year = sel.periodB.first,
            month = sel.periodB.second,
            accounts = db.accounts,
            txnsByAccount = txnsByAccount,
            bills = db.bills,
            allAccountTxns = db.accountTxns,
            allCardTxns = db.cardTxns,
            nowMillis = nowMillis
        )

        ChartsUiState(
            granularity = sel.granularity,
            selectedYear = sel.year,
            selectedMonth = sel.month,
            periodLabel = periodLabel,
            canStepNext = canStepNext,
            compareOverlayEnabled = sel.compareOverlay,
            comparisonOverlayLabel = comparisonLabel,
            currentNetAsset = headlineNet,
            currentAssets = headlineAssets,
            currentLiabilities = headlineLiabilities,
            periodChangeAmount = periodChange,
            periodChangePercent = periodChangePct,
            netAssetSeries = primarySeries,
            netAssetComparisonSeries = comparisonSeries,
            cashflowSeries = cashflowSeries,
            totalIncome = totalIncome,
            totalExpense = totalExpense,
            categoryShowIncome = sel.categoryShowIncome,
            categorySlices = categorySlices,
            allocationSlices = allocationSlices,
            categoryNicknames = db.nicknames,
            availableYears = availableYears,
            compareYearlyMode = sel.compareYearlyMode,
            periodAYear = sel.periodA.first,
            periodAMonth = sel.periodA.second,
            periodBYear = sel.periodB.first,
            periodBMonth = sel.periodB.second,
            snapshotA = snapshotA,
            snapshotB = snapshotB
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChartsUiState())

    fun setGranularity(granularity: ChartGranularity) {
        _granularity.value = granularity
    }

    fun stepPrevious() {
        when (_granularity.value) {
            ChartGranularity.DAILY -> {
                val (py, pm) = previousMonth(_selectedYear.value, _selectedMonth.value)
                _selectedYear.value = py
                _selectedMonth.value = pm
            }
            ChartGranularity.MONTHLY -> {
                _selectedYear.value -= 1
            }
            ChartGranularity.YEARLY -> Unit
        }
    }

    fun stepNext() {
        val cur = Calendar.getInstance()
        val curY = cur.get(Calendar.YEAR)
        val curM = cur.get(Calendar.MONTH)
        when (_granularity.value) {
            ChartGranularity.DAILY -> {
                var m = _selectedMonth.value + 1
                var y = _selectedYear.value
                if (m > 11) { m = 0; y += 1 }
                if (y < curY || (y == curY && m <= curM)) {
                    _selectedYear.value = y
                    _selectedMonth.value = m
                }
            }
            ChartGranularity.MONTHLY -> {
                if (_selectedYear.value < curY) {
                    _selectedYear.value += 1
                }
            }
            ChartGranularity.YEARLY -> Unit
        }
    }

    fun setCompareOverlay(enabled: Boolean) {
        _compareOverlay.value = enabled
    }

    fun setCategoryShowIncome(showIncome: Boolean) {
        _categoryShowIncome.value = showIncome
    }

    fun setCompareYearlyMode(yearly: Boolean) {
        _compareYearlyMode.value = yearly
        if (yearly) {
            val curY = Calendar.getInstance().get(Calendar.YEAR)
            _periodA.value = curY to _periodA.value.second
            _periodB.value = (curY - 1) to _periodB.value.second
        }
    }

    fun stepPeriodA(delta: Int) {
        _periodA.value = stepPair(_periodA.value, _compareYearlyMode.value, delta)
    }

    fun stepPeriodB(delta: Int) {
        _periodB.value = stepPair(_periodB.value, _compareYearlyMode.value, delta)
    }

    fun swapComparisonPeriods() {
        val a = _periodA.value
        _periodA.value = _periodB.value
        _periodB.value = a
    }

    private fun stepPair(pair: Pair<Int, Int>, yearly: Boolean, delta: Int): Pair<Int, Int> {
        val cur = Calendar.getInstance()
        val curY = cur.get(Calendar.YEAR)
        val curM = cur.get(Calendar.MONTH)
        return if (yearly) {
            val nextY = (pair.first + delta).coerceAtMost(curY)
            nextY to pair.second
        } else {
            val cal = Calendar.getInstance().apply {
                set(pair.first, pair.second, 1)
                add(Calendar.MONTH, delta)
            }
            val ny = cal.get(Calendar.YEAR)
            val nm = cal.get(Calendar.MONTH)
            if (ny > curY || (ny == curY && nm > curM)) pair else ny to nm
        }
    }

    private fun previousMonth(year: Int, month: Int): Pair<Int, Int> =
        if (month == 0) (year - 1) to 11 else year to (month - 1)

    private fun monthYearText(year: Int, month: Int): String =
        SimpleDateFormat("MMM yyyy", Locale.US).format(
            Calendar.getInstance().apply { set(year, month, 1) }.time
        )
}
