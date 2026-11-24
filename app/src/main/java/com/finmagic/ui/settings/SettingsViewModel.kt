package com.finmagic.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.finmagic.data.backup.BackupManager
import com.finmagic.data.backup.BackupPreviewSummary
import com.finmagic.data.backup.CsvBackupManager
import com.finmagic.data.backup.CsvRestoreResult
import com.finmagic.data.backup.SavedCsvBackup
import com.finmagic.data.local.AppThemeMode
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.BillRepository
import com.finmagic.data.update.GitHubReleaseInfo
import com.finmagic.data.update.GitHubUpdateChecker
import com.finmagic.data.update.UpdateCheckState
import com.finmagic.data.update.UpdateInstallState
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
import kotlinx.coroutines.flow.map
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

sealed class BackupUiState {
    data object Idle : BackupUiState()
    data class Working(val message: String) : BackupUiState()
    data class Message(val text: String, val isError: Boolean = false) : BackupUiState()
}

sealed class CsvRestoreState {
    data object Idle : CsvRestoreState()
    data class Working(val message: String) : CsvRestoreState()
    data class Success(val message: String) : CsvRestoreState()
    data class Error(val message: String) : CsvRestoreState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val billRepository: BillRepository,
    private val accountRepository: AccountRepository,
    private val cardTransactionDao: CardTransactionDao,
    private val preferenceManager: PreferenceManager,
    private val localWebServer: LocalWebServer,
    private val gitHubUpdateChecker: GitHubUpdateChecker,
    private val backupManager: BackupManager,
    private val csvBackupManager: CsvBackupManager
) : AndroidViewModel(application) {

    val webServerState: StateFlow<WebServerState> = localWebServer.state

    val webPortalPort: StateFlow<Int> = preferenceManager.webPortalPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 8080)

    val webPortalAutoStart: StateFlow<Boolean> = preferenceManager.webPortalAutoStart
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val updateCheckState: StateFlow<UpdateCheckState> = gitHubUpdateChecker.state
    val updateInstallState: StateFlow<UpdateInstallState> = gitHubUpdateChecker.installState

    val autoUpdateCheckEnabled: StateFlow<Boolean> = preferenceManager.autoUpdateCheckEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val installedVersionName: String = gitHubUpdateChecker.installedVersionName

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _savedCsvBackups = MutableStateFlow<List<SavedCsvBackup>>(emptyList())
    val savedCsvBackups: StateFlow<List<SavedCsvBackup>> = _savedCsvBackups.asStateFlow()

    private val _csvRestoreState = MutableStateFlow<CsvRestoreState>(CsvRestoreState.Idle)
    val csvRestoreState: StateFlow<CsvRestoreState> = _csvRestoreState.asStateFlow()

    private val _backupUiState = MutableStateFlow<BackupUiState>(BackupUiState.Idle)
    val backupUiState: StateFlow<BackupUiState> = _backupUiState.asStateFlow()

    private val _restorePreview = MutableStateFlow<Pair<Uri, BackupPreviewSummary>?>(null)
    val restorePreview: StateFlow<Pair<Uri, BackupPreviewSummary>?> = _restorePreview.asStateFlow()

    init {
        refreshSavedCsvBackups()
    }

    val persistentBackupEnabled: StateFlow<Boolean> = preferenceManager.persistentBackupEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val persistentBackupUri: StateFlow<String?> = preferenceManager.persistentBackupUri
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val lastBackupAt: StateFlow<Long> = preferenceManager.lastBackupAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val lastBackupLabel: StateFlow<String?> = preferenceManager.lastBackupLabel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val archivedAccountCount: StateFlow<Int> = accountRepository.observeArchived()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

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

    fun startUpdateInstall(release: GitHubReleaseInfo) {
        viewModelScope.launch {
            gitHubUpdateChecker.downloadAndPrepareInstall(release)
        }
    }

    fun resetUpdateInstallState() {
        gitHubUpdateChecker.resetInstallState()
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

    fun refreshSavedCsvBackups() {
        viewModelScope.launch {
            _savedCsvBackups.value = withContext(Dispatchers.IO) {
                csvBackupManager.listSavedBackups()
            }
        }
    }

    fun exportData() {
        viewModelScope.launch {
            _exportState.value = ExportState.Loading
            try {
                val file = csvBackupManager.exportBackup()
                _exportState.value = ExportState.Success(file)
                refreshSavedCsvBackups()
            } catch (e: Exception) {
                _exportState.value = ExportState.Error(e.message ?: "Export failed")
            }
        }
    }

    fun saveCsvToUri(file: File, destination: Uri) {
        viewModelScope.launch {
            val ok = csvBackupManager.copyFileToUri(file, destination)
            _csvRestoreState.value = if (ok) {
                CsvRestoreState.Success("Saved ${file.name} to selected location")
            } else {
                CsvRestoreState.Error("Could not write ${file.name} to selected location")
            }
        }
    }

    fun restoreCsvFile(file: File) {
        viewModelScope.launch {
            _csvRestoreState.value = CsvRestoreState.Working("Restoring ${file.name}...")
            try {
                val result = csvBackupManager.restoreFromFile(file)
                _csvRestoreState.value = CsvRestoreState.Success(formatCsvRestoreMessage(result))
            } catch (e: Exception) {
                _csvRestoreState.value = CsvRestoreState.Error(e.message ?: "CSV restore failed")
            }
        }
    }

    fun restoreCsvFromUri(uri: Uri) {
        viewModelScope.launch {
            _csvRestoreState.value = CsvRestoreState.Working("Importing CSV backup...")
            try {
                val result = csvBackupManager.restoreFromUri(uri)
                _csvRestoreState.value = CsvRestoreState.Success(formatCsvRestoreMessage(result))
            } catch (e: Exception) {
                _csvRestoreState.value = CsvRestoreState.Error(e.message ?: "CSV import failed")
            }
        }
    }

    fun deleteCsvBackupFile(file: File) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { csvBackupManager.deleteBackupFile(file) }
            if (deleted) {
                if ((_exportState.value as? ExportState.Success)?.file == file) {
                    _exportState.value = ExportState.Idle
                }
                refreshSavedCsvBackups()
            }
        }
    }

    fun clearCsvRestoreState() {
        _csvRestoreState.value = CsvRestoreState.Idle
    }

    private fun formatCsvRestoreMessage(result: CsvRestoreResult): String {
        return buildString {
            append("Restored ${result.accountsRestored} accounts, ${result.transactionsRestored} txns, ")
            append("${result.cardTransactionsRestored} card txns, ${result.billsRestored} bills")
            if (result.skippedDuplicates > 0) {
                append(" (${result.skippedDuplicates} duplicates skipped)")
            }
        }
    }

    fun resetExportState() {
        _exportState.value = ExportState.Idle
    }

    // ---- Persistent File & Full Backup / Restore ----

    fun setPersistentBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferenceManager.setPersistentBackupEnabled(enabled)
            if (enabled && backupManager.hasMeaningfulData()) {
                backupManager.writeToPersistentFile()
            }
        }
    }

    fun backupNow() {
        viewModelScope.launch {
            _backupUiState.value = BackupUiState.Working("Saving backup...")
            val result = backupManager.writeToPersistentFile()
            _backupUiState.value = result.fold(
                onSuccess = { label ->
                    BackupUiState.Message("Backup saved to $label")
                },
                onFailure = { err ->
                    BackupUiState.Message(err.message ?: "Backup failed", isError = true)
                }
            )
        }
    }

    fun saveBackupToUri(uri: Uri) {
        viewModelScope.launch {
            _backupUiState.value = BackupUiState.Working("Writing backup file...")
            val result = backupManager.writeBackupToUri(uri, persistAsTarget = true)
            _backupUiState.value = result.fold(
                onSuccess = { label ->
                    BackupUiState.Message("Backup saved to $label (pinned for auto-sync)")
                },
                onFailure = { err ->
                    BackupUiState.Message(err.message ?: "Failed to save backup file", isError = true)
                }
            )
        }
    }

    fun clearCustomBackupUri() {
        viewModelScope.launch {
            preferenceManager.setPersistentBackupUri(null)
            _backupUiState.value = BackupUiState.Message("Custom backup file unlinked")
        }
    }

    fun requestRestorePreview(uri: Uri) {
        viewModelScope.launch {
            _backupUiState.value = BackupUiState.Working("Reading backup file...")
            val result = backupManager.previewBackupFromUri(uri)
            result.fold(
                onSuccess = { summary ->
                    _restorePreview.value = uri to summary
                    _backupUiState.value = BackupUiState.Idle
                },
                onFailure = { err ->
                    _backupUiState.value = BackupUiState.Message(
                        err.message ?: "Could not read backup file",
                        isError = true
                    )
                }
            )
        }
    }

    fun confirmRestoreFromPending() {
        val pending = _restorePreview.value ?: return
        viewModelScope.launch {
            _restorePreview.value = null
            _backupUiState.value = BackupUiState.Working("Restoring accounts, cards & rules...")
            val result = backupManager.restoreFromUri(pending.first)
            _backupUiState.value = result.fold(
                onSuccess = { summary ->
                    BackupUiState.Message(
                        "Restored ${summary.accountCount} accounts, ${summary.cardCount} cards, ${summary.transactionCount} transactions"
                    )
                },
                onFailure = { err ->
                    BackupUiState.Message(err.message ?: "Restore failed", isError = true)
                }
            )
        }
    }

    fun dismissRestorePreview() {
        _restorePreview.value = null
    }

    fun clearBackupMessage() {
        if (_backupUiState.value is BackupUiState.Message) {
            _backupUiState.value = BackupUiState.Idle
        }
    }
}
