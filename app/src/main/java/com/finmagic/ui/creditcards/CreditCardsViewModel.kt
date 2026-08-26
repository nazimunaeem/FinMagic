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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

data class CardWithStats(
    val card: CreditCard,
    val totalPaid: Double
)

data class CreditCardsState(
    val unpaidCount: Int = 0,
    val currentMonthTotals: Map<String, Double> = emptyMap(),
    val currentMonthMinTotals: Map<String, Double> = emptyMap(),
    val previousMonthsTotals: Map<String, Double> = emptyMap(),
    val overdueCount: Int = 0,
    val overdueBills: List<BillWithCard> = emptyList(),
    val dueSoonBills: List<BillWithCard> = emptyList(),
    val upcomingBills: List<BillWithCard> = emptyList(),
    val safeBills: List<BillWithCard> = emptyList(),
    val previousMonthsBills: List<BillWithCard> = emptyList(),
    val paidHistory: List<BillWithCard> = emptyList(),
    val recentPayments: List<com.finmagic.data.local.entity.CardPayment> = emptyList(),
    val cards: List<CardWithStats> = emptyList(),
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

@OptIn(ExperimentalCoroutinesApi::class)
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
            val cardsFlow = cardRepository.getAllCards().flatMapLatest { cardList ->
                if (cardList.isEmpty()) kotlinx.coroutines.flow.flowOf(emptyList<CardWithStats>())
                else combine(cardList.map { card ->
                    billRepository.getPaidTotalForCard(card.id).map { CardWithStats(card, it ?: 0.0) }
                }) { it.toList() }
            }

            combine(
                billRepository.getAllBills(),
                billRepository.getUnpaidCount(),
                cardsFlow,
                billRepository.getAllPayments()
            ) { allBills, count, cardsList, allPayments ->
                withContext(Dispatchers.Default) {
                    val unpaidBills = allBills.filter { !it.isPaid }
                    val paidBills = allBills.filter { it.isPaid }.sortedByDescending { it.detectedAt }

                    val now = Calendar.getInstance()
                    val currentMonth = now.get(Calendar.MONTH)
                    val currentYear = now.get(Calendar.YEAR)

                    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
                    val periodFormats = listOf("MMM-yyyy", "MMM yyyy", "MMM-yy", "MMM yy")

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
                            if (!bill.isSuperseded) {
                                val remaining = bill.totalDue - bill.paidAmount
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

                    CreditCardsState(
                        unpaidCount = count,
                        currentMonthTotals = thisMonthTotals,
                        currentMonthMinTotals = thisMonthMinTotals,
                        previousMonthsTotals = prevMonthsTotals,
                        overdueCount = unpaidBills.count { it.dueStatus == DueStatus.OVERDUE && !it.isSuperseded },
                        overdueBills = sortBills(thisMonthBillsList.filter { it.dueStatus == DueStatus.OVERDUE }),
                        dueSoonBills = sortBills(thisMonthBillsList.filter { it.dueStatus == DueStatus.DUE_SOON }),
                        upcomingBills = sortBills(thisMonthBillsList.filter { it.dueStatus == DueStatus.UPCOMING }),
                        safeBills = sortBills(thisMonthBillsList.filter { it.dueStatus == DueStatus.SAFE }),
                        previousMonthsBills = sortBills(prevMonthBillsList),
                        paidHistory = paidBills,
                        recentPayments = allPayments.take(20),
                        cards = cardsList,
                        isLoading = false
                    )
                }
            }
            .distinctUntilChanged()
            .conflate()
            .collect { _state.value = it }
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
            cardRepository.updateCard(card.copy(cardNickname = nickname))
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
