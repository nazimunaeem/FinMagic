package com.finmagic.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class RecurringTemplate(
    val kind: String,
    val title: String,
    val amount: Double,
    val category: String? = null,
    val note: String? = null
) {
    val id: String
        get() = "${kind.lowercase()}|${title.trim()}"
}

enum class AppThemeMode(val label: String) {
    LIGHT("Light"),
    DARK("Dark"),
    SYSTEM("System")
}

data class AccountTypeConfig(
    val name: String,
    val isLiability: Boolean = false
)

data class BackupPreferencesPayload(
    val notificationsEnabled: Boolean = true,
    val alertRules: Set<String> = setOf("DAILY"),
    val alertTime: String = "09:00",
    val largeDebitAlerts: Boolean = true,
    val largeDebitThreshold: Double = 10_000.0,
    val biometricLockEnabled: Boolean = false,
    val themeMode: String = AppThemeMode.LIGHT.name,
    val categoryNicknames: Map<String, String> = emptyMap(),
    val cashExpenseCategories: List<String> = emptyList(),
    val cashIncomeCategories: List<String> = emptyList(),
    val recurringTemplates: List<RecurringTemplate> = emptyList(),
    val customAccountTypes: List<AccountTypeConfig> = emptyList(),
    val webPortalPort: Int = 8080,
    val webPortalAutoStart: Boolean = false,
    val autoUpdateCheckEnabled: Boolean = true,
    val firstScanComplete: Boolean = false
)

@Singleton
class PreferenceManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    private val ALERT_RULES = stringSetPreferencesKey("alert_rules")
    private val ALERT_TIME = stringPreferencesKey("alert_time")
    private val FIRST_SCAN_COMPLETE = booleanPreferencesKey("first_scan_complete")
    private val LARGE_DEBIT_ALERTS = booleanPreferencesKey("large_debit_alerts")
    private val LARGE_DEBIT_THRESHOLD = doublePreferencesKey("large_debit_threshold")
    private val BIOMETRIC_LOCK = booleanPreferencesKey("biometric_lock")
    private val CATEGORY_NICKNAMES = stringSetPreferencesKey("category_nicknames")
    private val CASH_EXPENSE_CATEGORIES = stringPreferencesKey("cash_expense_categories")
    private val CASH_INCOME_CATEGORIES = stringPreferencesKey("cash_income_categories")
    private val RECURRING_TEMPLATES = stringSetPreferencesKey("recurring_templates")
    private val THEME_MODE = stringPreferencesKey("theme_mode")
    private val CUSTOM_ACCOUNT_TYPES = stringSetPreferencesKey("custom_account_types")
    private val WEB_PORTAL_PORT = intPreferencesKey("web_portal_port")
    private val WEB_PORTAL_AUTO_START = booleanPreferencesKey("web_portal_auto_start")
    private val AUTO_UPDATE_CHECK = booleanPreferencesKey("auto_update_check")
    private val SKIPPED_UPDATE_TAG = stringPreferencesKey("skipped_update_tag")
    private val PERSISTENT_BACKUP_ENABLED = booleanPreferencesKey("persistent_backup_enabled")
    private val PERSISTENT_BACKUP_URI = stringPreferencesKey("persistent_backup_uri")
    private val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    private val LAST_BACKUP_LABEL = stringPreferencesKey("last_backup_label")

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { it[NOTIFICATIONS_ENABLED] ?: true }
    val alertRules: Flow<Set<String>> = context.dataStore.data.map {
        it[ALERT_RULES] ?: setOf("DAILY")
    }
    val alertTime: Flow<String> = context.dataStore.data.map { it[ALERT_TIME] ?: "09:00" }
    val isFirstScanComplete: Flow<Boolean> = context.dataStore.data.map { it[FIRST_SCAN_COMPLETE] ?: false }

    /** Notify on large account debits (plan Phase 5). */
    val largeDebitAlerts: Flow<Boolean> = context.dataStore.data.map { it[LARGE_DEBIT_ALERTS] ?: true }
    val largeDebitThreshold: Flow<Double> = context.dataStore.data.map { it[LARGE_DEBIT_THRESHOLD] ?: 10_000.0 }
    val biometricLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[BIOMETRIC_LOCK] ?: false }

    val themeMode: Flow<AppThemeMode> = context.dataStore.data.map { prefs ->
        val stored = prefs[THEME_MODE]
        AppThemeMode.entries.firstOrNull { it.name == stored } ?: AppThemeMode.LIGHT
    }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.dataStore.edit { it[THEME_MODE] = mode.name }
    }

    /**
     * User-defined display nicknames for auto-detected transaction categories.
     * Stored as a set of "category\u0000nickname" entries (no JSON dependency).
     */
    val categoryNicknames: Flow<Map<String, String>> = context.dataStore.data.map { prefs ->
        prefs[CATEGORY_NICKNAMES].orEmpty().associate { entry ->
            val sep = entry.indexOf('\u0000')
            if (sep >= 0) entry.substring(0, sep) to entry.substring(sep + 1) else entry to entry
        }.filterValues { it.isNotBlank() }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[NOTIFICATIONS_ENABLED] = enabled }
    }

    suspend fun setAlertRules(rules: Set<String>) {
        context.dataStore.edit { it[ALERT_RULES] = rules }
    }

    suspend fun toggleAlertRule(rule: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[ALERT_RULES] ?: setOf("DAILY")
            val newSet = if (current.contains(rule)) {
                current - rule
            } else {
                current + rule
            }
            preferences[ALERT_RULES] = newSet
        }
    }

    suspend fun setAlertTime(time: String) {
        context.dataStore.edit { it[ALERT_TIME] = time }
    }

    suspend fun setFirstScanComplete(complete: Boolean) {
        context.dataStore.edit { it[FIRST_SCAN_COMPLETE] = complete }
    }

    suspend fun setLargeDebitAlerts(enabled: Boolean) {
        context.dataStore.edit { it[LARGE_DEBIT_ALERTS] = enabled }
    }

    suspend fun setLargeDebitThreshold(threshold: Double) {
        context.dataStore.edit { it[LARGE_DEBIT_THRESHOLD] = threshold }
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.dataStore.edit { it[BIOMETRIC_LOCK] = enabled }
    }

    suspend fun setCategoryNickname(category: String, nickname: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[CATEGORY_NICKNAMES].orEmpty().toMutableSet()
            current.removeAll { it.startsWith("$category\u0000") }
            val trimmed = nickname.trim()
            if (trimmed.isNotEmpty()) {
                current.add("$category\u0000$trimmed")
            }
            prefs[CATEGORY_NICKNAMES] = current
        }
    }

    /** Editable list of categories offered when logging a manual Cash EXPENSE. */
    val cashExpenseCategories: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[CASH_EXPENSE_CATEGORIES]?.split('\u0000')?.filter { it.isNotBlank() }
            ?: listOf("Food", "Shopping", "Transport", "Bill", "Entertainment",
                "Health", "Education", "Subscription", "Gift", "Recharge", "Other")
    }

    /** Editable list of categories offered when logging a manual Cash INCOME. */
    val cashIncomeCategories: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[CASH_INCOME_CATEGORIES]?.split('\u0000')?.filter { it.isNotBlank() }
            ?: listOf("Salary", "Received", "Interest", "Refund", "Other")
    }

    suspend fun setCashExpenseCategories(categories: List<String>) {
        context.dataStore.edit { it[CASH_EXPENSE_CATEGORIES] = categories.joinToString("\u0000") }
    }

    suspend fun setCashIncomeCategories(categories: List<String>) {
        context.dataStore.edit { it[CASH_INCOME_CATEGORIES] = categories.joinToString("\u0000") }
    }

    val recurringTemplates: Flow<List<RecurringTemplate>> = context.dataStore.data.map { prefs ->
        (prefs[RECURRING_TEMPLATES] ?: emptySet()).mapNotNull { encoded ->
            val parts = encoded.split("\u0001")
            if (parts.size < 5) return@mapNotNull null
            val kind = parts[0]
            val title = parts[1]
            val amount = parts[2].toDoubleOrNull() ?: return@mapNotNull null
            val category = parts[3].ifBlank { null }
            val note = parts[4].ifBlank { null }
            RecurringTemplate(kind = kind, title = title, amount = amount, category = category, note = note)
        }
    }

    suspend fun addRecurringTemplate(template: RecurringTemplate) {
        context.dataStore.edit { prefs ->
            val current = prefs[RECURRING_TEMPLATES].orEmpty().toMutableSet()
            current.removeAll { it.startsWith("${template.kind.lowercase()}|${template.title.trim()}" ) }
            current.add(listOf(template.kind, template.title.trim(), template.amount.toString(), template.category ?: "", template.note ?: "").joinToString("\u0001"))
            prefs[RECURRING_TEMPLATES] = current
        }
    }

    suspend fun removeRecurringTemplate(template: RecurringTemplate) {
        context.dataStore.edit { prefs ->
            val current = prefs[RECURRING_TEMPLATES].orEmpty().toMutableSet()
            current.removeIf { it.startsWith("${template.kind.lowercase()}|${template.title.trim()}") }
            prefs[RECURRING_TEMPLATES] = current
        }
    }

    // ---- Custom Account Types ----

    val customAccountTypes: Flow<List<AccountTypeConfig>> = context.dataStore.data.map { prefs ->
        prefs[CUSTOM_ACCOUNT_TYPES].orEmpty().mapNotNull { entry ->
            val sep = entry.indexOf('\u0000')
            val name = if (sep >= 0) entry.substring(0, sep).trim() else entry.trim()
            if (name.isBlank()) return@mapNotNull null
            val isLiability = if (sep >= 0) entry.substring(sep + 1) == "1" else false
            AccountTypeConfig(name = name, isLiability = isLiability)
        }.sortedBy { it.name.lowercase() }
    }

    val allAccountTypes: Flow<List<AccountTypeConfig>> = customAccountTypes.map { custom ->
        val builtIn = com.finmagic.data.local.entity.Account.DEFAULT_TYPES.map { (name, liab) ->
            AccountTypeConfig(name = name, isLiability = liab)
        }
        val builtInNames = builtIn.map { it.name.lowercase() }.toSet()
        builtIn + custom.filter { it.name.lowercase() !in builtInNames }
    }

    suspend fun addCustomAccountType(name: String, isLiability: Boolean) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        val builtInNames = com.finmagic.data.local.entity.Account.DEFAULT_TYPES.map { it.first.lowercase() }.toSet()
        if (trimmed.lowercase() in builtInNames) return
        context.dataStore.edit { prefs ->
            val current = prefs[CUSTOM_ACCOUNT_TYPES].orEmpty().toMutableSet()
            current.removeAll {
                val n = it.substringBefore('\u0000').trim()
                n.equals(trimmed, ignoreCase = true)
            }
            current.add("$trimmed\u0000${if (isLiability) "1" else "0"}")
            prefs[CUSTOM_ACCOUNT_TYPES] = current
        }
    }

    suspend fun removeCustomAccountType(name: String) {
        val trimmed = name.trim()
        context.dataStore.edit { prefs ->
            val current = prefs[CUSTOM_ACCOUNT_TYPES].orEmpty().toMutableSet()
            current.removeAll { it.substringBefore('\u0000').trim().equals(trimmed, ignoreCase = true) }
            prefs[CUSTOM_ACCOUNT_TYPES] = current
        }
    }

    // ---- Wi-Fi / Hotspot Web Portal ----

    val webPortalPort: Flow<Int> = context.dataStore.data.map { it[WEB_PORTAL_PORT] ?: 8080 }
    val webPortalAutoStart: Flow<Boolean> = context.dataStore.data.map { it[WEB_PORTAL_AUTO_START] ?: false }

    suspend fun setWebPortalPort(port: Int) {
        val safePort = port.coerceIn(1024, 65535)
        context.dataStore.edit { it[WEB_PORTAL_PORT] = safePort }
    }

    suspend fun setWebPortalAutoStart(enabled: Boolean) {
        context.dataStore.edit { it[WEB_PORTAL_AUTO_START] = enabled }
    }

    // ---- GitHub Auto Update ----

    val autoUpdateCheckEnabled: Flow<Boolean> = context.dataStore.data.map { it[AUTO_UPDATE_CHECK] ?: true }
    val skippedUpdateTag: Flow<String?> = context.dataStore.data.map { it[SKIPPED_UPDATE_TAG] }

    suspend fun setAutoUpdateCheckEnabled(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_UPDATE_CHECK] = enabled }
    }

    suspend fun setSkippedUpdateTag(tag: String?) {
        context.dataStore.edit { prefs ->
            if (tag.isNullOrBlank()) prefs.remove(SKIPPED_UPDATE_TAG)
            else prefs[SKIPPED_UPDATE_TAG] = tag
        }
    }

    // ---- Persistent File & Full Backup ----

    val persistentBackupEnabled: Flow<Boolean> = context.dataStore.data.map { it[PERSISTENT_BACKUP_ENABLED] ?: true }
    val persistentBackupUri: Flow<String?> = context.dataStore.data.map { it[PERSISTENT_BACKUP_URI] }
    val lastBackupAt: Flow<Long> = context.dataStore.data.map { it[LAST_BACKUP_AT] ?: 0L }
    val lastBackupLabel: Flow<String?> = context.dataStore.data.map { it[LAST_BACKUP_LABEL] }

    suspend fun setPersistentBackupEnabled(enabled: Boolean) {
        context.dataStore.edit { it[PERSISTENT_BACKUP_ENABLED] = enabled }
    }

    suspend fun setPersistentBackupUri(uri: String?) {
        context.dataStore.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(PERSISTENT_BACKUP_URI)
            else prefs[PERSISTENT_BACKUP_URI] = uri
        }
    }

    suspend fun recordBackupSuccess(atMillis: Long, label: String) {
        context.dataStore.edit { prefs ->
            prefs[LAST_BACKUP_AT] = atMillis
            prefs[LAST_BACKUP_LABEL] = label
        }
    }

    suspend fun exportSnapshot(): BackupPreferencesPayload {
        return BackupPreferencesPayload(
            notificationsEnabled = notificationsEnabled.first(),
            alertRules = alertRules.first(),
            alertTime = alertTime.first(),
            largeDebitAlerts = largeDebitAlerts.first(),
            largeDebitThreshold = largeDebitThreshold.first(),
            biometricLockEnabled = biometricLockEnabled.first(),
            themeMode = themeMode.first().name,
            categoryNicknames = categoryNicknames.first(),
            cashExpenseCategories = cashExpenseCategories.first(),
            cashIncomeCategories = cashIncomeCategories.first(),
            recurringTemplates = recurringTemplates.first(),
            customAccountTypes = customAccountTypes.first(),
            webPortalPort = webPortalPort.first(),
            webPortalAutoStart = webPortalAutoStart.first(),
            autoUpdateCheckEnabled = autoUpdateCheckEnabled.first(),
            firstScanComplete = isFirstScanComplete.first()
        )
    }

    suspend fun restoreFromBackup(payload: BackupPreferencesPayload) {
        context.dataStore.edit { prefs ->
            prefs[NOTIFICATIONS_ENABLED] = payload.notificationsEnabled
            prefs[ALERT_RULES] = payload.alertRules
            prefs[ALERT_TIME] = payload.alertTime.ifBlank { "09:00" }
            prefs[LARGE_DEBIT_ALERTS] = payload.largeDebitAlerts
            prefs[LARGE_DEBIT_THRESHOLD] = payload.largeDebitThreshold
            prefs[BIOMETRIC_LOCK] = payload.biometricLockEnabled
            val resolvedTheme = AppThemeMode.entries.firstOrNull { it.name == payload.themeMode } ?: AppThemeMode.LIGHT
            prefs[THEME_MODE] = resolvedTheme.name
            prefs[CATEGORY_NICKNAMES] = payload.categoryNicknames
                .filterKeys { it.isNotBlank() }
                .filterValues { it.isNotBlank() }
                .map { (k, v) -> "$k\u0000$v" }
                .toSet()
            if (payload.cashExpenseCategories.isNotEmpty()) {
                prefs[CASH_EXPENSE_CATEGORIES] = payload.cashExpenseCategories.filter { it.isNotBlank() }.joinToString("\u0000")
            }
            if (payload.cashIncomeCategories.isNotEmpty()) {
                prefs[CASH_INCOME_CATEGORIES] = payload.cashIncomeCategories.filter { it.isNotBlank() }.joinToString("\u0000")
            }
            prefs[RECURRING_TEMPLATES] = payload.recurringTemplates.map { t ->
                listOf(t.kind, t.title.trim(), t.amount.toString(), t.category ?: "", t.note ?: "").joinToString("\u0001")
            }.toSet()
            val builtInNames = com.finmagic.data.local.entity.Account.DEFAULT_TYPES.map { it.first.lowercase() }.toSet()
            prefs[CUSTOM_ACCOUNT_TYPES] = payload.customAccountTypes
                .filter { it.name.isNotBlank() && it.name.trim().lowercase() !in builtInNames }
                .map { "${it.name.trim()}\u0000${if (it.isLiability) "1" else "0"}" }
                .toSet()
            prefs[WEB_PORTAL_PORT] = payload.webPortalPort.coerceIn(1024, 65535)
            prefs[WEB_PORTAL_AUTO_START] = payload.webPortalAutoStart
            prefs[AUTO_UPDATE_CHECK] = payload.autoUpdateCheckEnabled
            prefs[FIRST_SCAN_COMPLETE] = payload.firstScanComplete
        }
    }
}
