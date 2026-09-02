package com.finmagic.ui.smsreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.KeywordJson
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.CardPaymentDao
import com.finmagic.data.local.dao.SmsRecordDao
import com.finmagic.data.local.dao.SmsTeachingRuleDao
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.CardPayment
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.local.entity.SmsTeachingRule
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.BillRepository
import com.finmagic.data.repository.CardRepository
import com.finmagic.data.sms.SmsTeachingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SmsReviewViewModel @Inject constructor(
    private val smsRecordDao: SmsRecordDao,
    private val ruleDao: SmsTeachingRuleDao,
    private val accountDao: AccountDao,
    private val accountRepository: AccountRepository,
    private val smsTeachingEngine: SmsTeachingEngine,
    private val billRepository: BillRepository,
    private val cardRepository: CardRepository,
    private val cardPaymentDao: CardPaymentDao
) : ViewModel() {

    val unparsedSms: StateFlow<List<SmsRecord>> =
        smsRecordDao.getUnparsedSms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allSms: StateFlow<List<SmsRecord>> =
        smsRecordDao.getAllSms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<Account>> =
        accountDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val creditCards: StateFlow<List<CreditCard>> =
        cardRepository.getAllCards()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules: StateFlow<List<SmsTeachingRule>> =
        ruleDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTab = MutableStateFlow(ReviewTab.ALL)
    val selectedTab: StateFlow<ReviewTab> = _selectedTab.asStateFlow()

    private val _selectedRecord = MutableStateFlow<SmsRecord?>(null)
    val selectedRecord: StateFlow<SmsRecord?> = _selectedRecord.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun selectTab(tab: ReviewTab) {
        _selectedTab.value = tab
    }

    fun selectRecord(record: SmsRecord?) {
        _selectedRecord.value = record
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun teachSms(
        record: SmsRecord,
        accountId: Long,
        direction: String,
        amount: Double,
        occurredAt: Long,
        counterparty: String,
        category: String,
        balanceAfter: Double?,
        saveRule: Boolean,
        requiredKeywords: String,
        excludedKeywords: String
    ) {
        viewModelScope.launch {
            if (saveRule && requiredKeywords.isNotBlank()) {
                ruleDao.insert(
                    SmsTeachingRule(
                        name = "Rule for ${record.address ?: "Unknown"}",
                        senderKey = record.address?.trim()?.uppercase(),
                        requiredKeywords = KeywordJson.encodeFromCommaSeparated(requiredKeywords),
                        excludedKeywords = KeywordJson.encodeFromCommaSeparated(excludedKeywords),
                        action = if (direction == AccountTransaction.DIRECTION_IN) {
                            SmsTeachingRule.ACTION_CREDIT
                        } else {
                            SmsTeachingRule.ACTION_DEBIT
                        },
                        accountId = accountId,
                        category = category.ifBlank { null }
                    )
                )
            }
            if (record.isParsed) {
                accountRepository.replaceParsedSmsEntries(record.id)
            }
            if (accountRepository.storeManualToAccount(
                    accountId = accountId,
                    direction = direction,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = category.ifBlank { "Manual" },
                    counterparty = counterparty.ifBlank { "Taught from SMS" },
                    smsBody = record.body,
                    smsRecordId = record.id,
                    balanceAfter = balanceAfter
                ) != null
            ) {
                smsRecordDao.updateParsedStatus(record.id, true)
                _selectedRecord.value = null
                _message.value = "Transaction saved"
            } else {
                _message.value = "Failed to save — duplicate or invalid amount"
            }
        }
    }

    fun teachAtmWithdrawal(
        record: SmsRecord,
        accountId: Long,
        amount: Double,
        occurredAt: Long,
        saveRule: Boolean,
        requiredKeywords: String,
        excludedKeywords: String
    ) {
        viewModelScope.launch {
            if (saveRule && requiredKeywords.isNotBlank()) {
                ruleDao.insert(
                    SmsTeachingRule(
                        name = "ATM rule for ${record.address ?: "Unknown"}",
                        senderKey = record.address?.trim()?.uppercase(),
                        requiredKeywords = KeywordJson.encodeFromCommaSeparated(requiredKeywords),
                        excludedKeywords = KeywordJson.encodeFromCommaSeparated(excludedKeywords),
                        action = SmsTeachingRule.ACTION_ATM_WITHDRAWAL,
                        accountId = accountId
                    )
                )
            }
            if (record.isParsed) {
                accountRepository.replaceParsedSmsEntries(record.id)
            }
            val account = accountDao.findByIdOnce(accountId) ?: return@launch
            val entry = com.finmagic.data.sms.parsers.LedgerEntry(
                institution = account.bankName,
                kind = com.finmagic.data.sms.parsers.AccountKind.valueOf(account.kind),
                accountMask = account.accountMask,
                direction = com.finmagic.data.sms.parsers.LedgerDirection.OUT,
                amount = amount,
                occurredAt = occurredAt
            )
            if (accountRepository.storeAtmWithdraw(entry, record.body, record.id) != null) {
                smsRecordDao.updateParsedStatus(record.id, true)
                _selectedRecord.value = null
                _message.value = "ATM withdrawal recorded"
            } else {
                _message.value = "Failed to save — duplicate or invalid amount"
            }
        }
    }

    fun teachTransfer(
        record: SmsRecord,
        fromAccountId: Long,
        toAccountId: Long,
        amount: Double,
        occurredAt: Long,
        saveRule: Boolean,
        requiredKeywords: String,
        excludedKeywords: String
    ) {
        viewModelScope.launch {
            if (saveRule && requiredKeywords.isNotBlank()) {
                ruleDao.insert(
                    SmsTeachingRule(
                        name = "Transfer rule for ${record.address ?: "Unknown"}",
                        senderKey = record.address?.trim()?.uppercase(),
                        requiredKeywords = KeywordJson.encodeFromCommaSeparated(requiredKeywords),
                        excludedKeywords = KeywordJson.encodeFromCommaSeparated(excludedKeywords),
                        action = SmsTeachingRule.ACTION_TRANSFER,
                        accountId = fromAccountId,
                        category = "Transfer"
                    )
                )
            }
            if (record.isParsed) {
                accountRepository.replaceParsedSmsEntries(record.id)
            }
            val fromAccount = accountDao.findByIdOnce(fromAccountId) ?: return@launch
            val toAccount = accountDao.findByIdOnce(toAccountId) ?: return@launch
            
            val fromEntry = com.finmagic.data.sms.parsers.LedgerEntry(
                institution = fromAccount.bankName,
                kind = com.finmagic.data.sms.parsers.AccountKind.valueOf(fromAccount.kind),
                accountMask = fromAccount.accountMask,
                direction = com.finmagic.data.sms.parsers.LedgerDirection.OUT,
                amount = amount,
                occurredAt = occurredAt
            )
            val toEntry = com.finmagic.data.sms.parsers.LedgerEntry(
                institution = toAccount.bankName,
                kind = com.finmagic.data.sms.parsers.AccountKind.valueOf(toAccount.kind),
                accountMask = toAccount.accountMask,
                direction = com.finmagic.data.sms.parsers.LedgerDirection.IN,
                amount = amount,
                occurredAt = occurredAt
            )
            
            if (accountRepository.storeTransferPair(fromEntry, toEntry, record.body, record.id) != null) {
                smsRecordDao.updateParsedStatus(record.id, true)
                _selectedRecord.value = null
                _message.value = "Transfer recorded"
            } else {
                _message.value = "Failed to save — duplicate or invalid amount"
            }
        }
    }

    fun teachBalanceOnly(
        record: SmsRecord,
        accountId: Long,
        balance: Double,
        occurredAt: Long,
        saveRule: Boolean,
        requiredKeywords: String,
        excludedKeywords: String
    ) {
        viewModelScope.launch {
            if (saveRule && requiredKeywords.isNotBlank()) {
                ruleDao.insert(
                    SmsTeachingRule(
                        name = "Balance rule for ${record.address ?: "Unknown"}",
                        senderKey = record.address?.trim()?.uppercase(),
                        requiredKeywords = KeywordJson.encodeFromCommaSeparated(requiredKeywords),
                        excludedKeywords = KeywordJson.encodeFromCommaSeparated(excludedKeywords),
                        action = SmsTeachingRule.ACTION_BALANCE_ONLY,
                        accountId = accountId
                    )
                )
            }
            // Balance-only doesn't create a transaction, just marks the SMS as parsed
            smsRecordDao.updateParsedStatus(record.id, true)
            _selectedRecord.value = null
            _message.value = "Balance update recorded"
        }
    }

    fun teachCreateBill(
        record: SmsRecord,
        cardId: Long,
        totalDue: Double,
        minDue: Double,
        dueDate: String,
        billPeriod: String,
        occurredAt: Long,
        saveRule: Boolean,
        requiredKeywords: String,
        excludedKeywords: String
    ) {
        viewModelScope.launch {
            if (saveRule && requiredKeywords.isNotBlank()) {
                ruleDao.insert(
                    SmsTeachingRule(
                        name = "Bill rule for ${record.address ?: "Unknown"}",
                        senderKey = record.address?.trim()?.uppercase(),
                        requiredKeywords = KeywordJson.encodeFromCommaSeparated(requiredKeywords),
                        excludedKeywords = KeywordJson.encodeFromCommaSeparated(excludedKeywords),
                        action = SmsTeachingRule.ACTION_DEBIT,
                        accountId = 0
                    )
                )
            }
            val bill = Bill(
                cardId = cardId,
                billPeriod = billPeriod,
                totalDue = totalDue,
                minDue = if (minDue > 0) minDue else totalDue,
                dueDate = dueDate,
                smsBody = record.body,
                currency = "BDT",
                detectedAt = occurredAt
            )
            billRepository.addBill(bill)
            smsRecordDao.updateParsedStatus(record.id, true)
            _selectedRecord.value = null
            _message.value = "Bill created"
        }
    }

    fun teachBillPaid(
        record: SmsRecord,
        cardId: Long,
        amount: Double,
        occurredAt: Long,
        saveRule: Boolean,
        requiredKeywords: String,
        excludedKeywords: String
    ) {
        viewModelScope.launch {
            val card = cardRepository.getCardById(cardId) ?: return@launch
            val unpaid = billRepository.findActiveUnpaidBill(cardId, "BDT")
            if (unpaid != null) {
                billRepository.markAsPaid(unpaid.id, occurredAt)
            }
            cardPaymentDao.insert(
                CardPayment(
                    cardTail = card.cardLast4,
                    amountBdt = amount,
                    amountUsd = 0.0,
                    paidAt = occurredAt,
                    smsBody = record.body
                )
            )
            smsRecordDao.updateParsedStatus(record.id, true)
            _selectedRecord.value = null
            _message.value = "Bill marked as paid"
        }
    }

    fun ignoreSms(record: SmsRecord) {
        viewModelScope.launch {
            smsRecordDao.updateParsedStatus(record.id, true)
            _selectedRecord.value = null
            _message.value = "SMS marked as ignored"
        }
    }

    fun ignoreSimilar(sender: String?) {
        viewModelScope.launch {
            if (sender.isNullOrBlank()) return@launch
            ruleDao.insert(
                SmsTeachingRule(
                    name = "Block $sender",
                    senderKey = sender.trim().uppercase(),
                    requiredKeywords = KeywordJson.encode(emptyList()),
                    excludedKeywords = KeywordJson.encode(emptyList()),
                    action = SmsTeachingRule.ACTION_IGNORE,
                    accountId = 0
                )
            )
            _message.value = "All future messages from $sender will be ignored"
        }
    }

    fun createAccount(
        name: String,
        kind: String,
        bankName: String,
        accountMask: String,
        currency: String,
        includeInTotal: Boolean,
        onCreated: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val account = Account(
                name = name,
                kind = kind,
                bankName = bankName,
                accountMask = accountMask,
                currency = currency,
                includeInTotal = includeInTotal
            )
            accountDao.insert(account)
            val created = accountDao.findByInstitutionAndMask(bankName, accountMask)
            if (created != null) {
                _message.value = "Account \"${created.displayName}\" created"
                onCreated(created.id)
            }
        }
    }

    fun toggleRule(rule: SmsTeachingRule) {
        viewModelScope.launch {
            ruleDao.setEnabled(rule.id, !rule.enabled)
        }
    }

    fun deleteRule(rule: SmsTeachingRule) {
        viewModelScope.launch {
            ruleDao.deleteById(rule.id)
        }
    }

    fun disableAllRules() {
        viewModelScope.launch {
            ruleDao.disableAll()
            _message.value = "All rules disabled"
        }
    }

    fun enableAllRules() {
        viewModelScope.launch {
            ruleDao.enableAll()
            _message.value = "All rules enabled"
        }
    }

    fun applyRuleToHistorical(rule: SmsTeachingRule) {
        viewModelScope.launch {
            val unparsedList = unparsedSms.value
            if (unparsedList.isEmpty()) {
                _message.value = "No unparsed messages available to process"
                return@launch
            }
            val count = smsTeachingEngine.applyRuleToHistorical(rule, unparsedList)
            if (count > 0) {
                unparsedList.forEach { record ->
                    val sender = record.address?.trim()?.uppercase()
                    val normalized = SmsTeachingEngine.normalizeText(record.body)
                    if (SmsTeachingEngine.findBestMatch(listOf(rule), sender, normalized) != null) {
                        smsRecordDao.updateParsedStatus(record.id, true)
                    }
                }
                _message.value = "Rule applied to $count historical SMS messages"
            } else {
                _message.value = "No historical unparsed messages matched this rule"
            }
        }
    }
}

enum class ReviewTab {
    ALL,
    UNPARSED,
    RULES
}
