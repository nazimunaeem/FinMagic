package com.finmagic.ui.creditcards

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.entity.BillWithCard
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

data class CardWithStats(
    val card: CreditCard,
    val paidByCurrency: Map<String, Double> = emptyMap(),
    val unpaidByCurrency: Map<String, Double> = emptyMap(),
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
    private val cardRepository: CardRepository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(CreditCardsState())
    val state: StateFlow<CreditCardsState> = _state.asStateFlow()
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        loadData()
    }

    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            combine(
                billRepository.getAllBills(),
                billRepository.getUnpaidCount(),
                cardRepository.getAllCards(),
                billRepository.getAllPayments()
            ) { allBills, count, allCards, allPayments ->
                withContext(Dispatchers.Default) {
                    val unpaidBills = allBills.filter { !it.isPaid && !it.isSuperseded }
                    val paidBills = allBills.filter { it.isPaid }
                        .sortedByDescending { it.paidAt ?: it.detectedAt }

                    val now = Calendar.getInstance()
                    val currentMonth = now.get(Calendar.MONTH)
                    val currentYear = now.get(Calendar.YEAR)

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

                    // Per-card stats (paid + unpaid) for the My Cards tab.
                    val cardsStats: List<CardWithStats> = allCards.map { card ->
                        val cardBills = allBills.filter { it.cardId == card.id }
                        val paid = cardBills.filter { it.isPaid }
                        val unpaid = cardBills.filter { !it.isPaid && !it.isSuperseded }
                        CardWithStats(
                            card = card,
                            paidByCurrency = paid
                                .groupingBy { it.currency }
                                .fold(0.0) { acc, b -> acc + b.paidAmount },
                            unpaidByCurrency = unpaid
                                .groupingBy { it.currency }
                                .fold(0.0) { acc, b -> acc + (b.totalDue - b.paidAmount) },
                            unpaidBillCount = unpaid.size,
                            overdueBillCount = unpaid.count { it.dueStatus == DueStatus.OVERDUE },
                            totalPaid = paid.sumOf { it.paidAmount },
                            totalUnpaid = unpaid.sumOf { it.totalDue - it.paidAmount }
                        )
                    }.sortedWith(
                        compareByDescending<CardWithStats> { it.overdueBillCount > 0 }
                            .thenByDescending { it.totalUnpaid }
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
                        isLoading = false
                    )
                }
            }
            .distinctUntilChanged()
            .conflate()
            .collect { _state.value = it }
        }
    }

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