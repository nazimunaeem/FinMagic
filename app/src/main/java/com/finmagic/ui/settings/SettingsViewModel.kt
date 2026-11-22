package com.finmagic.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.repository.BillRepository
import com.finmagic.data.local.AppThemeMode
import com.finmagic.data.local.PreferenceManager
import com.finmagic.notification.DueDateReminderScheduler
import com.finmagic.notification.SmsScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

import kotlinx.coroutines.flow.first

sealed class ExportState {
    data object Idle : ExportState()
    data object Loading : ExportState()
    data class Success(val file: File) : ExportState()
    data class Error(val message: String) : ExportState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val billRepository: BillRepository,
    private val preferenceManager: PreferenceManager
) : AndroidViewModel(application) {

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    val notificationsEnabled: StateFlow<Boolean> = preferenceManager.notificationsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val alertRules: StateFlow<Set<String>> = preferenceManager.alertRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), setOf("DAILY"))

    val alertTime: StateFlow<String> = preferenceManager.alertTime
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "09:00")

    val largeDebitAlerts: StateFlow<Boolean> = preferenceManager.largeDebitAlerts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val largeDebitThreshold: StateFlow<Double> = preferenceManager.largeDebitThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 10_000.0)

    val biometricLockEnabled: StateFlow<Boolean> = preferenceManager.biometricLockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val themeMode: StateFlow<AppThemeMode> = preferenceManager.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppThemeMode.LIGHT)

    val categoryNicknames: StateFlow<Map<String, String>> = preferenceManager.categoryNicknames
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val cashExpenseCategories: StateFlow<List<String>> = preferenceManager.cashExpenseCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cashIncomeCategories: StateFlow<List<String>> = preferenceManager.cashIncomeCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun toggleLargeDebitAlerts(enabled: Boolean) {
        viewModelScope.launch { preferenceManager.setLargeDebitAlerts(enabled) }
    }

    fun setLargeDebitThreshold(threshold: Double) {
        viewModelScope.launch { preferenceManager.setLargeDebitThreshold(threshold) }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch { preferenceManager.setBiometricLockEnabled(enabled) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch { preferenceManager.setThemeMode(mode) }
    }

    fun setCategoryNickname(category: String, nickname: String) {
        viewModelScope.launch { preferenceManager.setCategoryNickname(category, nickname) }
    }

    // ---- Cash entry categories (expense / income) ----
    fun addCashExpenseCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val current = preferenceManager.cashExpenseCategories.first().toMutableList()
            if (current.none { it.equals(trimmed, ignoreCase = true) }) {
                current.add(trimmed)
                preferenceManager.setCashExpenseCategories(current)
            }
        }
    }

    fun renameCashExpenseCategory(old: String, new: String) {
        val newTrimmed = new.trim()
        if (newTrimmed.isEmpty()) return
        viewModelScope.launch {
            val current = preferenceManager.cashExpenseCategories.first().toMutableList()
            val idx = current.indexOfFirst { it.equals(old, ignoreCase = true) }
            if (idx >= 0) {
                current[idx] = newTrimmed
                preferenceManager.setCashExpenseCategories(current)
            }
        }
    }

    fun removeCashExpenseCategory(name: String) {
        viewModelScope.launch {
            val current = preferenceManager.cashExpenseCategories.first().toMutableList()
            current.removeAll { it.equals(name, ignoreCase = true) }
            preferenceManager.setCashExpenseCategories(current)
        }
    }

    fun addCashIncomeCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val current = preferenceManager.cashIncomeCategories.first().toMutableList()
            if (current.none { it.equals(trimmed, ignoreCase = true) }) {
                current.add(trimmed)
                preferenceManager.setCashIncomeCategories(current)
            }
        }
    }

    fun renameCashIncomeCategory(old: String, new: String) {
        val newTrimmed = new.trim()
        if (newTrimmed.isEmpty()) return
        viewModelScope.launch {
            val current = preferenceManager.cashIncomeCategories.first().toMutableList()
            val idx = current.indexOfFirst { it.equals(old, ignoreCase = true) }
            if (idx >= 0) {
                current[idx] = newTrimmed
                preferenceManager.setCashIncomeCategories(current)
            }
        }
    }

    fun removeCashIncomeCategory(name: String) {
        viewModelScope.launch {
            val current = preferenceManager.cashIncomeCategories.first().toMutableList()
            current.removeAll { it.equals(name, ignoreCase = true) }
            preferenceManager.setCashIncomeCategories(current)
        }
    }

    fun triggerRescan() {
        viewModelScope.launch {
            com.finmagic.data.log.ActivityLogger(getApplication()).info("SettingsViewModel", "Manual full rescan triggered")
            // Force a full inbox scan so bills missed by earlier scans are re-detected
            preferenceManager.setFirstScanComplete(false)
            SmsScheduler.scanNow(getApplication(), force = true)
        }
    }

    fun toggleNotifications(enabled: Boolean) {
        viewModelScope.launch {
            preferenceManager.setNotificationsEnabled(enabled)
            if (enabled) {
                SmsScheduler.schedule(getApplication())
                DueDateReminderScheduler.schedule(getApplication(), preferenceManager)
            } else {
                SmsScheduler.cancel(getApplication())
                DueDateReminderScheduler.cancel(getApplication())
            }
        }
    }

    fun toggleAlertRule(rule: String) {
        viewModelScope.launch {
            preferenceManager.toggleAlertRule(rule)
            DueDateReminderScheduler.schedule(getApplication(), preferenceManager)
        }
    }

    fun setAlertTime(time: String) {
        viewModelScope.launch {
            preferenceManager.setAlertTime(time)
            DueDateReminderScheduler.schedule(getApplication(), preferenceManager)
        }
    }

    fun exportData() {
        viewModelScope.launch {
            _exportState.value = ExportState.Loading
            try {
                val context = getApplication<Application>()
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = File(context.filesDir, "cardbill_export_$timestamp.csv")

                val bills = billRepository.getAllBills().first()
                file.bufferedWriter().use { writer ->
                    writer.appendLine("Bank,Card Last4,Period,Currency,Total Due,Min Due,Due Date,Paid")
                    for (bill in bills) {
                        writer.appendLine(
                            "${bill.displayName},${bill.billPeriod},${bill.currency},${bill.totalDue}," +
                            "${bill.minDue},${bill.dueDate},${bill.isPaid}"
                        )
                    }
                }
                _exportState.value = ExportState.Success(file)
            } catch (e: Exception) {
                _exportState.value = ExportState.Error(e.message ?: "Export failed")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }
}
