package com.finmagic.ui.accounts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.entity.AccountSummary
import com.finmagic.data.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** Backing "My Accounts" management screen: list, rename, delete. */
@HiltViewModel
class MyAccountsViewModel @Inject constructor(
    application: Application,
    private val accountRepository: AccountRepository
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

    fun renameAccount(accountId: Long, newName: String) {
        viewModelScope.launch { accountRepository.renameAccount(accountId, newName) }
    }

    fun setIncludeInTotal(accountId: Long, value: Boolean) {
        viewModelScope.launch { accountRepository.setIncludeInTotal(accountId, value) }
    }

    fun deleteAccount(accountId: Long) {
        viewModelScope.launch { accountRepository.deleteAccount(accountId) }
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

