package com.finmagic

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.finmagic.data.backup.BackupManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FinMagicApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var backupManager: BackupManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .apply {
                if (::workerFactory.isInitialized) {
                    setWorkerFactory(workerFactory)
                }
            }
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startAutoBackupObserver()
    }

    @OptIn(FlowPreview::class)
    private fun startAutoBackupObserver() {
        if (!::backupManager.isInitialized) return
        appScope.launch {
            backupManager.observeAutoBackupTriggers()
                .drop(1)
                .debounce(4_000L)
                .collect {
                    runCatching { backupManager.writeToPersistentFile() }
                }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val dueDateChannel = NotificationChannel(
            DUE_DATE_CHANNEL,
            "Due Date Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Reminders for upcoming credit card due dates"
        }

        val overdueChannel = NotificationChannel(
            OVERDUE_CHANNEL,
            "Overdue Bills",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for overdue credit card bills"
        }

        val scanChannel = NotificationChannel(
            SCAN_CHANNEL,
            "SMS Scan",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background SMS scanning status"
        }

        val alertsChannel = NotificationChannel(
            ALERTS_CHANNEL,
            "Account Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Large debit and account activity alerts"
        }

        manager.createNotificationChannel(dueDateChannel)
        manager.createNotificationChannel(overdueChannel)
        manager.createNotificationChannel(scanChannel)
        manager.createNotificationChannel(alertsChannel)
    }

    companion object {
        const val DUE_DATE_CHANNEL = "due_date_reminders"
        const val OVERDUE_CHANNEL = "overdue_bills"
        const val SCAN_CHANNEL = "sms_scan"
        const val ALERTS_CHANNEL = "account_alerts"
    }
}
