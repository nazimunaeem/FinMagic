package com.finmagic.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.AccountTypeConfig
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.local.entity.AccountSummary
import com.finmagic.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** Backing "My Accounts" management screen: list, rename, delete. */
@HiltViewModel
class MyAccountsViewModel @Inject constructor(
    application: Application,
    private val accountRepository: AccountRepository,
    private val preferenceManager: PreferenceManager
) : AndroidViewModel(application) {

    private val monthStart: Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    val summaries: StateFlow<List<AccountSummary>> =
        accountRepository.observeSummaries(monthStart)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedCount: StateFlow<Int> =
        accountRepository.observeArchived()
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val allAccountTypes: StateFlow<List<AccountTypeConfig>> =
        preferenceManager.allAccountTypes
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun renameAccount(accountId: Long, newName: String) {
        viewModelScope.launch { accountRepository.renameAccount(accountId, newName) }
    }

    fun updateAccountDetails(
        accountId: Long,
        newName: String,
        accountType: String,
        isLiability: Boolean,
        includeInTotal: Boolean
    ) {
        viewModelScope.launch {
            accountRepository.updateAccountDetails(accountId, newName, accountType, isLiability, includeInTotal)
        }
    }

    fun setIncludeInTotal(accountId: Long, value: Boolean) {
        viewModelScope.launch { accountRepository.setIncludeInTotal(accountId, value) }
    }

    fun deleteAccount(accountId: Long) {
        viewModelScope.launch { accountRepository.deleteAccount(accountId) }
    }

    fun archiveAccounts(accountIds: List<Long>) {
        viewModelScope.launch { accountRepository.archiveAccounts(accountIds) }
    }

    fun deleteAccountsPermanentlyWithSms(accountIds: List<Long>) {
        viewModelScope.launch { accountRepository.deleteAccountsPermanentlyWithSms(accountIds) }
    }

    fun adjustBalance(targetBalance: Double, accountId: Long) {
        viewModelScope.launch {
            accountRepository.adjustCashBalance(targetBalance, accountId)
        }
    }

    fun setBalanceDirect(targetBalance: Double, accountId: Long) {
        viewModelScope.launch { accountRepository.setCashBalanceDirect(targetBalance, accountId) }
    }
}

