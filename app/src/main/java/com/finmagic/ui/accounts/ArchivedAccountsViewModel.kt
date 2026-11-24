package com.finmagic.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.ArchivedAccountWithSms
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ArchivedAccountsViewModel @Inject constructor(
    application: Application,
    private val accountRepository: AccountRepository
) : AndroidViewModel(application) {

    val archivedAccountsWithSms: StateFlow<List<ArchivedAccountWithSms>> =
        accountRepository.observeArchivedWithSms()
            .conflate()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
