package com.finmagic.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.dao.ActivityLogDao
import com.finmagic.data.local.dao.SmsRecordDao
import com.finmagic.data.local.entity.ActivityLog
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.local.dao.SmsTeachingRuleDao
import com.finmagic.data.local.entity.SmsTeachingRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val activityLogDao: ActivityLogDao,
    private val smsRecordDao: SmsRecordDao,
    private val smsReader: com.finmagic.data.sms.SmsReader,
    private val accountRepository: AccountRepository,
    private val accountDao: com.finmagic.data.local.dao.AccountDao,
    private val ruleDao: SmsTeachingRuleDao
) : ViewModel() {

    private val _logs = MutableStateFlow<List<ActivityLog>>(emptyList())
    val logs: StateFlow<List<ActivityLog>> = _logs.asStateFlow()

    private val _smsRecords = MutableStateFlow<List<com.finmagic.data.local.entity.SmsRecord>>(emptyList())
    val smsRecords: StateFlow<List<com.finmagic.data.local.entity.SmsRecord>> = _smsRecords.asStateFlow()

    val unparsedSms: StateFlow<List<com.finmagic.data.local.entity.SmsRecord>> =
        smsRecordDao.getUnparsedSms().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    val accounts: StateFlow<List<Account>> =
        accountDao.observeAll().stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    private val _smsCount = MutableStateFlow(0)
    val smsCount: StateFlow<Int> = _smsCount.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    companion object {
        private const val PAGE_SIZE = 50
    }

    init {
        refreshLogs()
        loadSmsPage()
        observeSmsCount()
    }

    private fun observeSmsCount() {
        viewModelScope.launch {
            smsRecordDao.getSmsCount().collect { _smsCount.value = it }
        }
    }

    private fun loadSmsPage(offset: Int = 0) {
        viewModelScope.launch {
            if (offset == 0) {
                _smsRecords.value = emptyList()
            }
            _isLoadingMore.value = true
            smsRecordDao.getSmsPage(PAGE_SIZE, offset).collect { page ->
                _smsRecords.value = if (offset == 0) page else _smsRecords.value + page
                _isLoadingMore.value = false
            }
        }
    }

    fun loadMoreSms() {
        if (_isLoadingMore.value) return
        val currentSize = _smsRecords.value.size
        if (currentSize < _smsCount.value) {
            loadSmsPage(currentSize)
        }
    }

    fun refreshLogs() {
        viewModelScope.launch {
            _logs.value = activityLogDao.recent(100)
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            activityLogDao.clearAll()
            refreshLogs()
        }
    }

    fun clearSmsDatabase() {
        viewModelScope.launch {
            smsRecordDao.clearAll()
            _smsRecords.value = emptyList()
            _smsCount.value = 0
        }
    }

    fun scanAllSmsDebug() {
        viewModelScope.launch {
            val allSms = smsReader.readRecentSms(100)
            for (sms in allSms) {
                if (smsRecordDao.findByBody(sms.body) == null) {
                    smsRecordDao.insert(
                        com.finmagic.data.local.entity.SmsRecord(
                            address = sms.address,
                            body = sms.body,
                            timestamp = sms.date,
                            isParsed = com.finmagic.data.sms.BankSmsParser.identifyBank(sms.address, sms.body) != com.finmagic.data.sms.BankSmsParser.BankType.UNKNOWN
                        )
                    )
                }
            }
            loadSmsPage(0)
        }
    }

    fun teachOnce(
        record: com.finmagic.data.local.entity.SmsRecord,
        accountId: Long,
        direction: String,
        amount: Double,
        saveRule: Boolean,
        requiredKeywords: String,
        excludedKeywords: String
    ) {
        viewModelScope.launch {
            if (saveRule && requiredKeywords.isNotBlank()) {
                ruleDao.insert(
                    SmsTeachingRule(
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
                        accountId = accountId
                    )
                )
            }
            if (accountRepository.storeManualToAccount(
                    accountId = accountId,
                    direction = direction,
                    amount = amount,
                    occurredAt = record.timestamp,
                    category = com.finmagic.data.sms.parsers.TxnCategory.MANUAL,
                    counterparty = "Taught from SMS",
                    smsBody = record.body,
                    smsRecordId = record.id
                )
            ) {
                smsRecordDao.updateParsedStatus(record.id, true)
                loadSmsPage(0)
            }
        }
    }

    fun ignoreSms(record: com.finmagic.data.local.entity.SmsRecord) {
        viewModelScope.launch {
            smsRecordDao.updateParsedStatus(record.id, true)
            loadSmsPage(0)
        }
    }
}
