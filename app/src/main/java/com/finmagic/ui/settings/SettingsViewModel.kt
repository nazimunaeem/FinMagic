package com.finmagic.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.local.AppThemeMode
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.BillRepository
import com.finmagic.data.update.GitHubUpdateChecker
import com.finmagic.data.update.UpdateCheckState
import com.finmagic.data.web.LocalWebServer
import com.finmagic.data.web.WebServerState
import com.finmagic.notification.DueDateReminderScheduler
import com.finmagic.notification.SmsScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

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
    private val accountRepository: AccountRepository,
    private val cardTransactionDao: CardTransactionDao,
    private val preferenceManager: PreferenceManager,
    private val localWebServer: LocalWebServer,
    private val gitHubUpdateChecker: GitHubUpdateChecker
) : AndroidViewModel(application) {

    val webServerState: StateFlow<WebServerState> = localWebServer.state

    val webPortalPort: StateFlow<Int> = preferenceManager.webPortalPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8080)

    val webPortalAutoStart: StateFlow<Boolean> = preferenceManager.webPortalAutoStart
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val updateCheckState: StateFlow<UpdateCheckState> = gitHubUpdateChecker.state

    val autoUpdateCheckEnabled: StateFlow<Boolean> = preferenceManager.autoUpdateCheckEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val installedVersionName: String = gitHubUpdateChecker.installedVersionName

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

    // ---- Wi-Fi / Hotspot Web Portal ----

    fun toggleWebServer(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val port = preferenceManager.webPortalPort.first()
                localWebServer.start(port)
            } else {
                localWebServer.stop()
            }
        }
    }

    fun setWebPortalPort(port: Int) {
        val safePort = port.coerceIn(1024, 65535)
        viewModelScope.launch {
            preferenceManager.setWebPortalPort(safePort)
            if (localWebServer.state.value.isRunning) {
                localWebServer.start(safePort)
            }
        }
    }

    fun setWebPortalAutoStart(enabled: Boolean) {
        viewModelScope.launch { preferenceManager.setWebPortalAutoStart(enabled) }
    }

    fun refreshWebPortalUrls() {
        localWebServer.refreshUrls()
    }

    // ---- GitHub Auto Update ----

    fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        viewModelScope.launch { preferenceManager.setAutoUpdateCheckEnabled(enabled) }
    }

    fun checkForUpdatesManual() {
        viewModelScope.launch {
            preferenceManager.setSkippedUpdateTag(null)
            gitHubUpdateChecker.checkForUpdates(manual = true)
        }
    }

    fun skipUpdateVersion(tag: String) {
        viewModelScope.launch {
            preferenceManager.setSkippedUpdateTag(tag)
            gitHubUpdateChecker.dismissUpdate()
        }
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

    private fun escapeCsv(value: String?): String {
        val v = value ?: ""
        return if (v.contains(',') || v.contains('"') || v.contains('\n') || v.contains('\r')) {
            "\"${v.replace("\"", "\"\"")}\""
        } else {
            v
        }
    }

    private fun csvRow(vararg cols: String?): String = cols.joinToString(",") { escapeCsv(it) }

    fun exportData() {
        viewModelScope.launch {
            _exportState.value = ExportState.Loading
            try {
                val context = getApplication<Application>()
                val file = withContext(Dispatchers.IO) {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val dateTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    val outFile = File(context.filesDir, "finmagic_export_$timestamp.csv")

                    val accounts = accountRepository.getAllAccountsOnce()
                    val accountTxns = accountRepository.observeAllTransactionsWithAccount().first()
                    val cardTxns = cardTransactionDao.observeAll().first()
                    val bills = billRepository.getAllBills().first()

                    outFile.bufferedWriter().use { writer ->
                        writer.appendLine(
                            csvRow(
                                "Record Type",
                                "Date",
                                "Account / Card",
                                "Category / Period",
                                "Direction / Status",
                                "Amount",
                                "Currency",
                                "Balance / Min Due",
                                "Counterparty / Merchant",
                                "Source / Due Date"
                            )
                        )

                        for (acct in accounts) {
                            val txns = accountRepository.getAllTransactionsOnce(acct.id)
                            val balance = AccountRepository.effectiveBalance(txns)
                            writer.appendLine(
                                csvRow(
                                    "ACCOUNT",
                                    "",
                                    "${acct.displayName} (${acct.accountMask})",
                                    acct.kind,
                                    if (acct.includeInTotal) "INCLUDED" else "EXCLUDED",
                                    String.format(Locale.US, "%.2f", balance),
                                    acct.currency,
                                    "",
                                    acct.bankName,
                                    "Active account"
                                )
                            )
                        }

                        for (txn in accountTxns) {
                            writer.appendLine(
                                csvRow(
                                    "ACCOUNT_TXN",
                                    dateTimeFmt.format(Date(txn.occurredAt)),
                                    txn.accountName,
                                    txn.category ?: "Uncategorized",
                                    txn.direction,
                                    String.format(Locale.US, "%.2f", txn.amount),
                                    txn.currency,
                                    txn.balanceAfter?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                                    txn.counterparty ?: "",
                                    if (txn.isManual) "MANUAL" else "SMS"
                                )
                            )
                        }

                        for (cardTx in cardTxns) {
                            val cardLabel = listOfNotNull(
                                cardTx.bankName.takeIf { it.isNotBlank() },
                                "•••• ${cardTx.cardTail}"
                            ).joinToString(" ")
                            writer.appendLine(
                                csvRow(
                                    "CARD_TXN",
                                    dateTimeFmt.format(Date(cardTx.occurredAt)),
                                    cardLabel,
                                    cardTx.category ?: "Card Spend",
                                    "OUT",
                                    String.format(Locale.US, "%.2f", cardTx.amount),
                                    "BDT",
                                    "",
                                    cardTx.merchant,
                                    if (cardTx.smsBody.startsWith("manual:")) "MANUAL" else "SMS"
                                )
                            )
                        }

                        for (bill in bills) {
                            val status = when {
                                bill.isPaid -> "PAID"
                                bill.paidAmount > 0 -> "PARTIAL (paid ${String.format(Locale.US, "%.2f", bill.paidAmount)})"
                                else -> "UNPAID"
                            }
                            writer.appendLine(
                                csvRow(
                                    "CARD_BILL",
                                    dateTimeFmt.format(Date(bill.detectedAt)),
                                    "${bill.displayName} (•••• ${bill.cardLast4})",
                                    bill.billPeriod,
                                    status,
                                    String.format(Locale.US, "%.2f", bill.totalDue),
                                    bill.currency,
                                    String.format(Locale.US, "%.2f", bill.minDue),
                                    "",
                                    "Due: ${bill.dueDate}"
                                )
                            )
                        }
                    }
                    outFile
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
