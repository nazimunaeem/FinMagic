package com.finmagic.ui.creditcards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.entity.BillWithCard
import com.finmagic.data.local.entity.CardPayment
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
import javax.inject.Inject

@HiltViewModel
class CardDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val cardRepository: CardRepository,
    private val billRepository: BillRepository,
    private val cardPaymentDao: CardPaymentDao
) : ViewModel() {

    val cardId: Long = savedStateHandle.get<Long>("cardId")
        ?: savedStateHandle.get<String>("cardId")?.toLongOrNull()
        ?: 0L

    private val _card = MutableStateFlow<CreditCard?>(null)
    val card: StateFlow<CreditCard?> = _card.asStateFlow()

    private val _bills: StateFlow<List<BillWithCard>> = billRepository.getBillsForCard(cardId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bills: StateFlow<List<BillWithCard>> = _bills

    val totalPaid: StateFlow<Double> = _bills
        .map { list -> list.filter { it.isPaid }.sumOf { it.paidAmount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalUnpaid: StateFlow<Double> = _bills
        .map { list -> list.filter { !it.isPaid && !it.isSuperseded }.sumOf { it.totalDue - it.paidAmount } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val payments: StateFlow<List<CardPayment>> = combine(
        cardPaymentDao.observeAllPayments(),
        _card
    ) { allPayments, currentCard ->
        if (currentCard == null) emptyList()
        else {
            val tail3 = currentCard.cardLast4.takeLast(3)
            allPayments.filter { payment ->
                payment.cardTail.takeLast(3) == tail3
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadCard()
    }

    private fun loadCard() {
        viewModelScope.launch {
            _card.value = cardRepository.getCardById(cardId)
        }
    }

    fun updateNickname(card: CreditCard, nickname: String) {
        viewModelScope.launch {
            cardRepository.updateCard(card.copy(cardNickname = nickname.trim()))
            _card.value = cardRepository.getCardById(cardId)
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
}
