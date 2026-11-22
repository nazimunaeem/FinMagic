package com.finmagic.ui.creditcards

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.local.entity.DueStatus
import com.finmagic.data.repository.BillRepository
import com.finmagic.data.repository.CardRepository
import com.finmagic.notification.SmsScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

enum class CardPeriodMode {
    ALL,
    MONTH,
    YEAR
}

internal fun normalizeCardBank(name: String?): String {
    val n = name?.trim()?.lowercase(Locale.US) ?: return ""
    return when {
        n.contains("eastern") || n.contains("ebl") -> "ebl"
        n.contains("city") -> "city"
        n.contains("brac") -> "brac"
        n.contains("pubali") -> "pubali"
        n.contains("prime") -> "prime"
        n.contains("dbbl") || n.contains("dutch") -> "dbbl"
        n.contains("aibl") || n.contains("arafah") -> "aibl"
        n.contains("ibbl") || n.contains("islami") -> "ibbl"
        n == "bank" || n == "card" || n == "unknown bank" -> ""
        else -> n.replace("bank", "").trim()
    }
}

internal fun CardTransaction.belongsTo(card: CreditCard): Boolean {
    val txTail = cardTail.filter { it.isDigit() }
    val cardDigits = card.cardLast4.filter { it.isDigit() }
    if (txTail.isEmpty() || cardDigits.isEmpty()) return false
    val tailMatches = if (txTail.length >= 4 && cardDigits.length >= 4) {
        txTail.takeLast(4) == cardDigits.takeLast(4)
    } else {
        txTail.takeLast(3) == cardDigits.takeLast(3)
    }
    if (!tailMatches) return false
    val txBank = normalizeCardBank(bankName)
    val cardBank = normalizeCardBank(card.bankName)
    return txBank.isEmpty() || cardBank.isEmpty() || txBank == cardBank
}

internal fun billYearMonth(bill: BillWithCard): Pair<Int, Int> {
    val periodFormats = listOf(
        "MMM-yyyy", "MMM yyyy", "MMM-yy", "MMM yy", "MMMM yyyy", "MMMM-yyyy"
    )
    for (fmt in periodFormats) {
        try {
            val sdfP = SimpleDateFormat(fmt, Locale.US).apply { isLenient = false }
            val periodDate = sdfP.parse(bill.billPeriod)
            if (periodDate != null) {
                val cal = Calendar.getInstance().apply { time = periodDate }
                return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
            }
        } catch (_: Exception) {}
    }
    try {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        val normalizedDueDate = com.finmagic.data.sms.BankSmsParser.normalizeDate(bill.dueDate)
        val date = sdf.parse(normalizedDueDate)
        if (date != null) {
            val cal = Calendar.getInstance().apply { time = date }
            return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH)
        }
    } catch (_: Exception) {}
    val fallback = Calendar.getInstance().apply { timeInMillis = bill.detectedAt }
    return fallback.get(Calendar.YEAR) to fallback.get(Calendar.MONTH)
}

internal fun matchesPeriod(
    occurredAt: Long,
    mode: CardPeriodMode,
    year: Int,
    month: Int
): Boolean {
    if (mode == CardPeriodMode.ALL) return true
    val cal = Calendar.getInstance().apply { timeInMillis = occurredAt }
    return when (mode) {
        CardPeriodMode.ALL -> true
        CardPeriodMode.MONTH -> cal.get(Calendar.YEAR) == year && cal.get(Calendar.MONTH) == month
        CardPeriodMode.YEAR -> cal.get(Calendar.YEAR) == year
    }
}

internal fun billMatchesPeriod(
    bill: BillWithCard,
    mode: CardPeriodMode,
    year: Int,
    month: Int
): Boolean {
    if (mode == CardPeriodMode.ALL) return true
    val (bYear, bMonth) = billYearMonth(bill)
    return when (mode) {
        CardPeriodMode.ALL -> true
        CardPeriodMode.MONTH -> bYear == year && bMonth == month
        CardPeriodMode.YEAR -> bYear == year
    }
}

data class CardWithStats(
    val card: CreditCard,
    val billedByCurrency: Map<String, Double> = emptyMap(),
    val paidByCurrency: Map<String, Double> = emptyMap(),
    val unpaidByCurrency: Map<String, Double> = emptyMap(),
    val spentAmount: Double = 0.0,
    val transactionCount: Int = 0,
    val unpaidBillCount: Int = 0,
    val overdueBillCount: Int = 0,
    val totalPaid: Double = 0.0,
    val totalUnpaid: Double = 0.0
)

data class CardSummaryRow(
    val cardId: Long,
    val displayName: String,
    val maskedCard: String,
    val unpaidByCurrency: Map<String, Double>,
    val unpaidBillCount: Int,
    val overdueBillCount: Int
)

data class TrendPoint(
    val label: String,
    val amount: Double
)

data class CreditCardsState(
    val unpaidCount: Int = 0,
    val unpaidCardCount: Int = 0,
    val overdueCount: Int = 0,
    val currentMonthTotals: Map<String, Double> = emptyMap(),
    val currentMonthMinTotals: Map<String, Double> = emptyMap(),
    val previousMonthsTotals: Map<String, Double> = emptyMap(),
    val nextDue: BillWithCard? = null,
    val overdueBills: List<BillWithCard> = emptyList(),
    val dueSoonBills: List<BillWithCard> = emptyList(),
    val upcomingBills: List<BillWithCard> = emptyList(),
    val safeBills: List<BillWithCard> = emptyList(),
    val previousMonthsBills: List<BillWithCard> = emptyList(),
    val paidHistory: List<BillWithCard> = emptyList(),
    val recentPayments: List<com.finmagic.data.local.entity.CardPayment> = emptyList(),
    val cards: List<CardWithStats> = emptyList(),
    val perCardSummary: List<CardSummaryRow> = emptyList(),
    val trend: List<TrendPoint> = emptyList(),
    val periodMode: CardPeriodMode = CardPeriodMode.ALL,
    val selectedYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    val selectedMonth: Int = Calendar.getInstance().get(Calendar.MONTH),
    val canStepNext: Boolean = false,
    val isLoading: Boolean = true
) {
    fun formatTotals(totals: Map<String, Double>): String {
        if (totals.isEmpty()) return "৳0.00"
        return totals.entries.joinToString(" + ") { (currency, amount) ->
            val symbol = when (currency.uppercase()) {
                "USD" -> "$"
                "BDT", "TK" -> "৳"
                else -> currency
            }
            "$symbol${String.format(Locale.US, "%,.2f", amount)}"
        }
    }
}

@HiltViewModel
class CreditCardsViewModel @Inject constructor(
    application: Application,
    private val billRepository: BillRepository,
    private val cardRepository: CardRepository,
    private val cardTransactionDao: CardTransactionDao
) : AndroidViewModel(application) {

    private val nowCal = Calendar.getInstance()
    private val _periodMode = MutableStateFlow(CardPeriodMode.ALL)
    private val _selectedPeriod = MutableStateFlow(
        nowCal.get(Calendar.YEAR) to nowCal.get(Calendar.MONTH)
    )

    private val _state = MutableStateFlow(CreditCardsState())
    val state: StateFlow<CreditCardsState> = _state.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        loadData()
    }

    fun setPeriodMode(mode: CardPeriodMode) {
        _periodMode.value = mode
    }

    fun stepPreviousPeriod() {
        val (y, m) = _selectedPeriod.value
        when (_periodMode.value) {
            CardPeriodMode.MONTH -> {
                _selectedPeriod.value = if (m == 0) (y - 1 to 11) else (y to m - 1)
            }
            CardPeriodMode.YEAR -> {
                _selectedPeriod.value = (y - 1 to m)
            }
            CardPeriodMode.ALL -> Unit
        }
    }

    fun stepNextPeriod() {
        val now = Calendar.getInstance()
        val curY = now.get(Calendar.YEAR)
        val curM = now.get(Calendar.MONTH)
        val (y, m) = _selectedPeriod.value
        when (_periodMode.value) {
            CardPeriodMode.MONTH -> {
                if (y < curY || (y == curY && m < curM)) {
                    _selectedPeriod.value = if (m == 11) (y + 1 to 0) else (y to m + 1)
                }
            }
            CardPeriodMode.YEAR -> {
                if (y < curY) {
                    _selectedPeriod.value = (y + 1 to m)
                }
            }
            CardPeriodMode.ALL -> Unit
        }
    }

    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val baseDataFlow = combine(
                billRepository.getAllBills(),
                billRepository.getUnpaidCount(),
                cardRepository.getAllCards(),
                billRepository.getAllPayments(),
                cardTransactionDao.observeAll()
            ) { allBills, count, allCards, allPayments, allCardTxns ->
                QuadData(allBills, count, allCards, allPayments, allCardTxns)
            }

            combine(
                baseDataFlow,
                _periodMode,
                _selectedPeriod
            ) { data, mode, (selYear, selMonth) ->
                withContext(Dispatchers.Default) {
                    val allBills = data.allBills
                    val count = data.unpaidCount
                    val allCards = data.allCards
                    val allPayments = data.allPayments
                    val allCardTxns = data.allCardTxns

                    val unpaidBills = allBills.filter { !it.isPaid && !it.isSuperseded }
                    val paidBills = allBills.filter { it.isPaid }
                        .sortedByDescending { it.paidAt ?: it.detectedAt }

                    val now = Calendar.getInstance()
                    val currentMonth = now.get(Calendar.MONTH)
                    val currentYear = now.get(Calendar.YEAR)

                    val canStepNext = when (mode) {
                        CardPeriodMode.ALL -> false
                        CardPeriodMode.MONTH -> selYear < currentYear || (selYear == currentYear && selMonth < currentMonth)
                        CardPeriodMode.YEAR -> selYear < currentYear
                    }

                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
                    val periodFormats = listOf(
                        "MMM-yyyy", "MMM yyyy", "MMM-yy", "MMM yy", "MMMM yyyy", "MMMM-yyyy"
                    )

                    val thisMonthTotals = mutableMapOf<String, Double>()
                    val thisMonthMinTotals = mutableMapOf<String, Double>()
                    val prevMonthsTotals = mutableMapOf<String, Double>()

                    val thisMonthBillsList = mutableListOf<BillWithCard>()
                    val prevMonthBillsList = mutableListOf<BillWithCard>()

                    unpaidBills.forEach { bill ->
                        var isCurrentMonth = false
                        try {
                            val normalizedDueDate = com.finmagic.data.sms.BankSmsParser.normalizeDate(bill.dueDate)
                            val date = sdf.parse(normalizedDueDate)
                            if (date != null) {
                                val billCal = Calendar.getInstance().apply { time = date }
                                if (billCal.get(Calendar.YEAR) == currentYear && billCal.get(Calendar.MONTH) == currentMonth) {
                                    isCurrentMonth = true
                                }
                            }
                        } catch (_: Exception) {}

                        if (!isCurrentMonth) {
                            for (fmt in periodFormats) {
                                try {
                                    val sdfP = SimpleDateFormat(fmt, Locale.US)
                                    sdfP.isLenient = false
                                    val periodDate = sdfP.parse(bill.billPeriod)
                                    if (periodDate != null) {
                                        val periodCal = Calendar.getInstance().apply { time = periodDate }
                                        if (periodCal.get(Calendar.YEAR) == currentYear && periodCal.get(Calendar.MONTH) == currentMonth) {
                                            isCurrentMonth = true
                                            break
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }

                        if (isCurrentMonth) {
                            val remaining = bill.totalDue - bill.paidAmount
                            thisMonthTotals[bill.currency] = (thisMonthTotals[bill.currency] ?: 0.0) + remaining
                            val remainingMin = maxOf(0.0, bill.minDue - bill.paidAmount)
                            thisMonthMinTotals[bill.currency] = (thisMonthMinTotals[bill.currency] ?: 0.0) + remainingMin
                            thisMonthBillsList.add(bill)
                        } else {
                            val remaining = bill.totalDue - bill.paidAmount
                            if (remaining > 0.0) {
                                prevMonthsTotals[bill.currency] = (prevMonthsTotals[bill.currency] ?: 0.0) + remaining
                            }
                            prevMonthBillsList.add(bill)
                        }
                    }

                    // Sort unpaid lists: oldest first (ASC due date)
                    fun sortBills(list: List<BillWithCard>) = list.sortedBy { bill ->
                        try {
                            val normalized = com.finmagic.data.sms.BankSmsParser.normalizeDate(bill.dueDate)
                            sdf.parse(normalized)?.time ?: Long.MAX_VALUE
                        } catch (_: Exception) { Long.MAX_VALUE }
                    }

                    val sortedUnpaid = sortBills(unpaidBills)
                    val nextDue = sortedUnpaid.firstOrNull()

                    // Per-card stats (outstanding, spent, billed, paid, txn count) for My Cards tab.
                    val cardsStats: List<CardWithStats> = allCards.map { card ->
                        val cardBills = allBills.filter { it.cardId == card.id }
                        val activeNonSuperseded = cardBills.filter { !it.isSuperseded }
                        val periodBills = activeNonSuperseded.filter {
                            billMatchesPeriod(it, mode, selYear, selMonth)
                        }
                        val unpaid = activeNonSuperseded.filter { !it.isPaid }
                        val matchingTxns = allCardTxns.filter { tx ->
                            tx.belongsTo(card) && matchesPeriod(tx.occurredAt, mode, selYear, selMonth)
                        }

                        CardWithStats(
                            card = card,
                            billedByCurrency = periodBills
                                .groupingBy { it.currency }
                                .fold(0.0) { acc, b -> acc + b.totalDue },
                            paidByCurrency = periodBills
                                .filter { it.paidAmount > 0.0 }
                                .groupingBy { it.currency }
                                .fold(0.0) { acc, b -> acc + b.paidAmount },
                            unpaidByCurrency = unpaid
                                .groupingBy { it.currency }
                                .fold(0.0) { acc, b -> acc + (b.totalDue - b.paidAmount) },
                            spentAmount = matchingTxns.sumOf { it.amount },
                            transactionCount = matchingTxns.size,
                            unpaidBillCount = unpaid.size,
                            overdueBillCount = unpaid.count { it.dueStatus == DueStatus.OVERDUE },
                            totalPaid = periodBills.sumOf { it.paidAmount },
                            totalUnpaid = unpaid.sumOf { it.totalDue - it.paidAmount }
                        )
                    }.sortedWith(
                        compareByDescending<CardWithStats> { it.overdueBillCount > 0 }
                            .thenByDescending { it.totalUnpaid }
                            .thenByDescending { it.transactionCount }
                    )

                    val perCardSummary = cardsStats
                        .filter { it.unpaidBillCount > 0 }
                        .map {
                            CardSummaryRow(
                                cardId = it.card.id,
                                displayName = it.card.displayName,
                                maskedCard = it.card.maskedCard,
                                unpaidByCurrency = it.unpaidByCurrency,
                                unpaidBillCount = it.unpaidBillCount,
                                overdueBillCount = it.overdueBillCount
                            )
                        }

                    // Statement total trend (dominant currency only, avoids mixing BDT+USD).
                    val dominantCurrency = allBills
                        .groupingBy { it.currency }
                        .eachCount()
                        .maxByOrNull { it.value }?.key ?: "BDT"

                    val trend = buildTrend(allBills.filter { it.currency == dominantCurrency })

                    CreditCardsState(
                        unpaidCount = count,
                        unpaidCardCount = unpaidBills.map { it.cardId }.distinct().size,
                        overdueCount = unpaidBills.count { it.dueStatus == DueStatus.OVERDUE },
                        currentMonthTotals = thisMonthTotals,
                        currentMonthMinTotals = thisMonthMinTotals,
                        previousMonthsTotals = prevMonthsTotals,
                        nextDue = nextDue,
                        overdueBills = sortBills(thisMonthBillsList.filter { it.dueStatus == DueStatus.OVERDUE }),
                        dueSoonBills = sortBills(thisMonthBillsList.filter { it.dueStatus == DueStatus.DUE_SOON }),
                        upcomingBills = sortBills(thisMonthBillsList.filter { it.dueStatus == DueStatus.UPCOMING }),
                        safeBills = sortBills(thisMonthBillsList.filter { it.dueStatus == DueStatus.SAFE }),
                        previousMonthsBills = sortBills(prevMonthBillsList),
                        paidHistory = paidBills,
                        recentPayments = allPayments.take(20),
                        cards = cardsStats,
                        perCardSummary = perCardSummary,
                        trend = trend,
                        periodMode = mode,
                        selectedYear = selYear,
                        selectedMonth = selMonth,
                        canStepNext = canStepNext,
                        isLoading = false
                    )
                }
            }
            .distinctUntilChanged()
            .conflate()
            .collect { _state.value = it }
        }
    }

    private data class QuadData(
        val allBills: List<BillWithCard>,
        val unpaidCount: Int,
        val allCards: List<CreditCard>,
        val allPayments: List<com.finmagic.data.local.entity.CardPayment>,
        val allCardTxns: List<CardTransaction>
    )

    private fun buildTrend(bills: List<BillWithCard>): List<TrendPoint> {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        val monthAccum = mutableMapOf<Int, Double>()
        val periodFormats = listOf(
            "MMM-yyyy", "MMM yyyy", "MMM-yy", "MMM yy", "MMMM yyyy", "MMMM-yyyy"
        )

        for (bill in bills) {
            var monthKey: Int? = null
            for (fmt in periodFormats) {
                try {
                    val sdfP = SimpleDateFormat(fmt, Locale.US)
                    sdfP.isLenient = false
                    val d = sdfP.parse(bill.billPeriod) ?: continue
                    val c = Calendar.getInstance().apply { time = d }
                    monthKey = c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
                    break
                } catch (_: Exception) {}
            }
            if (monthKey == null) {
                try {
                    val d = sdf.parse(com.finmagic.data.sms.BankSmsParser.normalizeDate(bill.dueDate))
                    if (d != null) {
                        val c = Calendar.getInstance().apply { time = d }
                        monthKey = c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
                    }
                } catch (_: Exception) {}
            }
            if (monthKey != null) {
                monthAccum[monthKey] = (monthAccum[monthKey] ?: 0.0) + bill.totalDue
            }
        }

        val labelFmt = SimpleDateFormat("MMM", Locale.US)
        return monthAccum.entries
            .sortedBy { it.key }
            .takeLast(6)
            .map { (key, amount) ->
                val month = key % 12
                val year = key / 12
                val cal = Calendar.getInstance().apply {
                    clear()
                    set(Calendar.MONTH, month)
                    set(Calendar.YEAR, year)
                }
                TrendPoint(label = labelFmt.format(cal.time), amount = amount)
            }
    }

    fun markBillAsPaid(billId: Long) {
        viewModelScope.launch { billRepository.markAsPaid(billId) }
    }

    fun updatePartialPayment(billId: Long, amount: Double) {
        viewModelScope.launch { billRepository.updatePaidAmount(billId, amount) }
    }

    fun markBillAsUnpaid(billId: Long) {
        viewModelScope.launch { billRepository.markAsUnpaid(billId) }
    }

    fun editBill(billId: Long, total: Double, min: Double, due: String) {
        viewModelScope.launch { billRepository.updateBillDetails(billId, total, min, due) }
    }

    fun updateNickname(card: CreditCard, nickname: String) {
        viewModelScope.launch {
            cardRepository.updateCard(card.copy(cardNickname = nickname.trim()))
        }
    }

    fun updateCardDetails(card: CreditCard, nickname: String, creditLimit: Double, currency: String) {
        viewModelScope.launch {
            cardRepository.updateCard(
                card.copy(
                    cardNickname = nickname.trim(),
                    creditLimit = creditLimit,
                    creditLimitCurrency = currency.ifBlank { "BDT" }
                )
            )
        }
    }

    fun addManualCard(
        bankName: String,
        last4: String,
        nickname: String,
        creditLimit: Double,
        currency: String
    ) {
        viewModelScope.launch {
            val bank = bankName.trim()
            val tail = last4.trim().takeLast(4)
            if (bank.isEmpty() || tail.length != 4) return@launch
            cardRepository.addCard(
                CreditCard(
                    bankName = bank,
                    cardLast4 = tail,
                    cardNickname = nickname.trim(),
                    creditLimit = creditLimit,
                    creditLimitCurrency = currency.ifBlank { "BDT" }
                )
            )
        }
    }

    fun deleteCard(card: CreditCard) {
        viewModelScope.launch {
            cardRepository.deleteCard(card)
        }
    }

    fun getBillsForCard(cardId: Long): kotlinx.coroutines.flow.Flow<List<BillWithCard>> {
        return billRepository.getBillsForCard(cardId)
    }

    fun refresh() {
        _state.value = _state.value.copy(isLoading = true)
        SmsScheduler.scanNow(getApplication())
        loadData()
    }
}