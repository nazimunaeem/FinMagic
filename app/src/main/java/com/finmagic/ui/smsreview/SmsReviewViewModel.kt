package com.finmagic.ui.smsreview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.SmsRecordDao
import com.finmagic.data.local.dao.SmsTeachingRuleDao
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.local.entity.SmsTeachingRule
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.sms.SmsTeachingEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SmsReviewViewModel @Inject constructor(
    private val smsRecordDao: SmsRecordDao,
    private val ruleDao: SmsTeachingRuleDao,
    private val accountDao: AccountDao,
    private val accountRepository: AccountRepository,
    private val smsTeachingEngine: SmsTeachingEngine
) : ViewModel() {

    val unparsedSms: StateFlow<List<SmsRecord>> =
        smsRecordDao.getUnparsedSms()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<Account>> =
        accountDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rules: StateFlow<List<SmsTeachingRule>> =
        ruleDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedTab = MutableStateFlow(ReviewTab.UNPARSED)
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

    /**
     * Teach the app to recognize this SMS as a credit or debit transaction.
     */
    fun teachSms(
        record: SmsRecord,
        accountId: Long,
        direction: String,
        amount: Double,
        occurredAt: Long,
        counterparty: String,
        category: String,
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
                        requiredKeywords = requiredKeywords.split(',').map { it.trim() }
                            .filter { it.isNotEmpty() }.distinct().joinToString("|"),
                        excludedKeywords = excludedKeywords.split(',').map { it.trim() }
                            .filter { it.isNotEmpty() }.distinct().joinToString("|"),
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
            if (accountRepository.storeManualToAccount(
                    accountId = accountId,
                    direction = direction,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = category.ifBlank { "Manual" },
                    counterparty = counterparty.ifBlank { "Taught from SMS" },
                    smsBody = record.body,
                    smsRecordId = record.id
                )
            ) {
                smsRecordDao.updateParsedStatus(record.id, true)
                _selectedRecord.value = null
                _message.value = "Transaction saved"
            } else {
                _message.value = "Failed to save — duplicate or invalid amount"
            }
        }
    }

    /**
     * Teach as ATM withdrawal (bank → cash pair).
     */
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
                        requiredKeywords = requiredKeywords.split(',').map { it.trim() }
                            .filter { it.isNotEmpty() }.distinct().joinToString("|"),
                        excludedKeywords = excludedKeywords.split(',').map { it.trim() }
                            .filter { it.isNotEmpty() }.distinct().joinToString("|"),
                        action = SmsTeachingRule.ACTION_ATM_WITHDRAWAL,
                        accountId = accountId
                    )
                )
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
            if (accountRepository.storeAtmWithdraw(entry, record.body, record.id)) {
                smsRecordDao.updateParsedStatus(record.id, true)
                _selectedRecord.value = null
                _message.value = "ATM withdrawal recorded"
            } else {
                _message.value = "Failed to save — duplicate or invalid amount"
            }
        }
    }

    /**
     * Mark as ignore — no transaction created.
     */
    fun ignoreSms(record: SmsRecord) {
        viewModelScope.launch {
            smsRecordDao.updateParsedStatus(record.id, true)
            _selectedRecord.value = null
        }
    }

    fun ignoreSimilar(sender: String?) {
        viewModelScope.launch {
            if (sender.isNullOrBlank()) return@launch
            // Insert an ignore rule for this sender so future messages are auto-ignored.
            ruleDao.insert(
                SmsTeachingRule(
                    name = "Ignore ${sender}",
                    senderKey = sender.trim().uppercase(),
                    requiredKeywords = "",
                    excludedKeywords = "",
                    action = SmsTeachingRule.ACTION_IGNORE,
                    accountId = 0
                )
            )
            _message.value = "Future messages from $sender will be ignored"
        }
    }

    // --- Account creation ---

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

    // --- Rule Management ---

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

    fun formatTimestamp(ts: Long): String {
        return SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

enum class ReviewTab {
    UNPARSED,
    RULES
}
