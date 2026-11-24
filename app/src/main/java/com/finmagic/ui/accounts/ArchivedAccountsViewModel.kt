package com.finmagic.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.ArchivedAccountWithSms
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ArchivedAccountsUiState(
    val isLoading: Boolean = true,
    val isMatchingSms: Boolean = false,
    val items: List<ArchivedAccountWithSms> = emptyList()
)

@HiltViewModel
class ArchivedAccountsViewModel @Inject constructor(
    application: Application,
    private val accountRepository: AccountRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ArchivedAccountsUiState())
    val uiState: StateFlow<ArchivedAccountsUiState> = _uiState.asStateFlow()

    private val _matchedSmsByAccount = MutableStateFlow<Map<Long, List<SmsRecord>>>(emptyMap())
    private var matchJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                accountRepository.observeArchived(),
                accountRepository.observeSmsCount().distinctUntilChanged()
            ) { archivedAccounts, smsCount ->
                archivedAccounts to smsCount
            }.collect { (archivedAccounts, _) ->
                // Show archived accounts immediately without waiting for SMS regex classification
                val cachedMap = _matchedSmsByAccount.value
                _uiState.value = ArchivedAccountsUiState(
                    isLoading = false,
                    isMatchingSms = archivedAccounts.isNotEmpty(),
                    items = archivedAccounts.map { acct ->
                        ArchivedAccountWithSms(
                            account = acct,
                            matchingSms = cachedMap[acct.id].orEmpty()
                        )
                    }
                )

                matchJob?.cancel()
                if (archivedAccounts.isEmpty()) {
                    _matchedSmsByAccount.value = emptyMap()
                    _uiState.value = ArchivedAccountsUiState(
                        isLoading = false,
                        isMatchingSms = false,
                        items = emptyList()
                    )
                } else {
                    matchJob = launch {
                        val matched = withContext(Dispatchers.Default) {
                            runCatching {
                                accountRepository.matchArchivedAccountSmsOnce(archivedAccounts)
                            }.getOrElse { emptyMap() }
                        }
                        _matchedSmsByAccount.value = matched
                        _uiState.value = ArchivedAccountsUiState(
                            isLoading = false,
                            isMatchingSms = false,
                            items = archivedAccounts.map { acct ->
                                ArchivedAccountWithSms(
                                    account = acct,
                                    matchingSms = matched[acct.id].orEmpty()
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    fun restoreAccount(accountId: Long) {
        viewModelScope.launch { accountRepository.restoreAccount(accountId) }
    }

    fun restoreAccounts(accountIds: List<Long>) {
        viewModelScope.launch { accountRepository.restoreAccounts(accountIds) }
    }

    fun deleteArchivedAccountSms(smsRecordId: Long) {
        viewModelScope.launch { accountRepository.deleteArchivedAccountSms(smsRecordId) }
    }

    fun deleteAllArchivedAccountSms(smsRecordIds: List<Long>) {
        viewModelScope.launch { accountRepository.deleteArchivedAccountSmsBatch(smsRecordIds) }
    }

    fun deleteAccountPermanently(accountId: Long) {
        viewModelScope.launch { accountRepository.deleteAccountPermanently(accountId) }
    }

    fun deleteAccountsPermanentlyWithSms(accountIds: List<Long>) {
        viewModelScope.launch { accountRepository.deleteAccountsPermanentlyWithSms(accountIds) }
    }
}
