package com.finmagic.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class PreferenceManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    private val ALERT_RULES = stringSetPreferencesKey("alert_rules")
    private val ALERT_TIME = stringPreferencesKey("alert_time")
    private val FIRST_SCAN_COMPLETE = booleanPreferencesKey("first_scan_complete")
    private val LARGE_DEBIT_ALERTS = booleanPreferencesKey("large_debit_alerts")
    private val LARGE_DEBIT_THRESHOLD = doublePreferencesKey("large_debit_threshold")
    private val CATEGORY_NICKNAMES = stringSetPreferencesKey("category_nicknames")
    private val CASH_EXPENSE_CATEGORIES = stringPreferencesKey("cash_expense_categories")
    private val CASH_INCOME_CATEGORIES = stringPreferencesKey("cash_income_categories")

    val notificationsEnabled: Flow<Boolean> = context.dataStore.data.map { it[NOTIFICATIONS_ENABLED] ?: true }
    val alertRules: Flow<Set<String>> = context.dataStore.data.map {
        it[ALERT_RULES] ?: setOf("DAILY")
    }
    val alertTime: Flow<String> = context.dataStore.data.map { it[ALERT_TIME] ?: "09:00" }
    val isFirstScanComplete: Flow<Boolean> = context.dataStore.data.map { it[FIRST_SCAN_COMPLETE] ?: false }

    /** Notify on large account debits (plan Phase 5). */
    val largeDebitAlerts: Flow<Boolean> = context.dataStore.data.map { it[LARGE_DEBIT_ALERTS] ?: true }
    val largeDebitThreshold: Flow<Double> = context.dataStore.data.map { it[LARGE_DEBIT_THRESHOLD] ?: 10_000.0 }

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
}
