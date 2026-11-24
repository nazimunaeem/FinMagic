package com.finmagic.ui.creditcards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.repository.BillRepository
import com.finmagic.data.repository.CardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

@HiltViewModel
class CardDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val cardRepository: CardRepository,
    private val billRepository: BillRepository,
    private val cardPaymentDao: CardPaymentDao,
    private val cardTransactionDao: CardTransactionDao,
    preferenceManager: PreferenceManager
) : ViewModel() {

    val expenseCategories: StateFlow<List<String>> = preferenceManager.cashExpenseCategories
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            listOf("Food", "Shopping", "Transport", "Bill", "Entertainment", "Health", "Other")
        )

    val cardId: Long = savedStateHandle.get<Long>("cardId")
        ?: savedStateHandle.get<String>("cardId")?.toLongOrNull()
        ?: 0L

    private val nowCal = Calendar.getInstance()
    private val _periodMode = MutableStateFlow(CardPeriodMode.ALL)
    val periodMode: StateFlow<CardPeriodMode> = _periodMode.asStateFlow()

    private val _selectedPeriod = MutableStateFlow(
        nowCal.get(Calendar.YEAR) to nowCal.get(Calendar.MONTH)
    )
    val selectedYear: StateFlow<Int> = _selectedPeriod
        .map { it.first }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), nowCal.get(Calendar.YEAR))
    val selectedMonth: StateFlow<Int> = _selectedPeriod
        .map { it.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), nowCal.get(Calendar.MONTH))

    val canStepNext: StateFlow<Boolean> = combine(_periodMode, _selectedPeriod) { mode, (y, m) ->
        val now = Calendar.getInstance()
        val curY = now.get(Calendar.YEAR)
        val curM = now.get(Calendar.MONTH)
        when (mode) {
            CardPeriodMode.ALL -> false
            CardPeriodMode.MONTH -> y < curY || (y == curY && m < curM)
            CardPeriodMode.YEAR -> y < curY
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val card: StateFlow<CreditCard?> = cardRepository.observeCardById(cardId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _bills: StateFlow<List<BillWithCard>> = billRepository.getBillsForCard(cardId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bills: StateFlow<List<BillWithCard>> = _bills

    val totalPaid: StateFlow<Double> = _bills
        .map { list ->
            list.filter { it.isPaid }
                .sumOf { if (it.paidAmount > 0.0) it.paidAmount else it.totalDue }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val paidSoFarTotals: StateFlow<Map<String, Double>> = _bills
        .map { list ->
            list.filter { it.isPaid }
                .groupingBy { it.currency }
                .fold(0.0) { acc, b -> acc + (if (b.paidAmount > 0.0) b.paidAmount else b.totalDue) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val totalUnpaid: StateFlow<Double> = _bills
        .map { list -> list.filter { !it.isPaid && !it.isSuperseded }.sumOf { it.totalDue - it.paidAmount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val paidTotals: StateFlow<Map<String, Double>> = combine(_bills, _periodMode, _selectedPeriod) { list, mode, (y, m) ->
        list.filter { it.isPaid && billMatchesPeriod(it, mode, y, m) }
            .groupingBy { it.currency }
            .fold(0.0) { acc, b -> acc + (if (b.paidAmount > 0.0) b.paidAmount else b.totalDue) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val billedTotals: StateFlow<Map<String, Double>> = combine(_bills, _periodMode, _selectedPeriod) { list, mode, (y, m) ->
        list.filter { !it.isSuperseded && billMatchesPeriod(it, mode, y, m) }
            .groupingBy { it.currency }
            .fold(0.0) { acc, b -> acc + b.totalDue }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val unpaidTotals: StateFlow<Map<String, Double>> = _bills
        .map { list ->
            list.filter { !it.isPaid && !it.isSuperseded }
                .groupingBy { it.currency }
                .fold(0.0) { acc, b -> acc + (b.totalDue - b.paidAmount) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val cardTransactions: StateFlow<List<CardTransaction>> = combine(
        cardTransactionDao.observeAll(),
        card,
        _periodMode,
        _selectedPeriod
    ) { allTxns, currentCard, mode, (y, m) ->
        if (currentCard == null) emptyList()
        else allTxns.filter { tx ->
            tx.belongsTo(currentCard) && matchesPeriod(tx.occurredAt, mode, y, m)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val spentInPeriod: StateFlow<Double> = cardTransactions
        .map { list -> list.sumOf { it.amount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val payments: StateFlow<List<CardPayment>> = combine(
        cardPaymentDao.observeAllPayments(),
        card
    ) { allPayments, currentCard ->
        if (currentCard == null) emptyList()
        else {
            val tail3 = currentCard.cardLast4.takeLast(3)
            allPayments.filter { payment ->
                payment.cardTail.takeLast(3) == tail3
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val outstanding: StateFlow<Double> = combine(
        card,
        _bills,
        cardTransactionDao.observeAll(),
        cardPaymentDao.observeAllPayments()
    ) { currentCard, bills, allTxns, allPayments ->
        if (currentCard == null) 0.0
        else CardRepository.computeCardOutstanding(
            card = currentCard,
            cardBills = bills,
            allCardTxns = allTxns,
            allPayments = allPayments
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

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

    fun updateNickname(card: CreditCard, nickname: String) {
        viewModelScope.launch {
            cardRepository.updateCard(card.copy(cardNickname = nickname.trim()))
        }
    }

    fun updateCardDetails(
        card: CreditCard,
        nickname: String,
        creditLimit: Double,
        currency: String,
        newOutstanding: Double? = null,
        clearOutstandingAnchor: Boolean = false
    ) {
        viewModelScope.launch {
            cardRepository.updateCardDetails(
                card = card,
                nickname = nickname,
                creditLimit = creditLimit,
                currency = currency,
                newOutstanding = newOutstanding,
                clearOutstandingAnchor = clearOutstandingAnchor
            )
        }
    }

    fun deleteCard(card: CreditCard, onSuccess: () -> Unit) {
        viewModelScope.launch {
            billRepository.deleteBillsForCard(card.id)
            cardRepository.deleteCard(card)
            onSuccess()
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

    fun editBill(billId: Long, totalDue: Double, minDue: Double, dueDate: String) {
        viewModelScope.launch { billRepository.updateBillDetails(billId, totalDue, minDue, dueDate) }
    }

    fun addManualTransaction(
        merchant: String,
        amount: Double,
        occurredAt: Long,
        category: String?
    ) {
        val currentCard = card.value ?: return
        val trimmedMerchant = merchant.trim()
        if (trimmedMerchant.isEmpty() || amount <= 0.0) return
        viewModelScope.launch {
            cardRepository.recordManualCardSpend(
                card = currentCard,
                merchant = trimmedMerchant,
                amount = amount,
                occurredAt = occurredAt,
                category = category
            )
        }
    }

    fun deleteManualTransaction(txnId: Long) {
        viewModelScope.launch {
            cardRepository.deleteCardSpend(txnId, manualOnly = true)
        }
    }
}
