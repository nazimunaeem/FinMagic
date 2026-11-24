package com.finmagic.notification

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.finmagic.data.sms.SmsScanWorker
import java.util.concurrent.TimeUnit

object SmsScheduler {

    const val KEY_SCAN_MODE = "scan_mode"
    const val MODE_STANDARD = "standard"
    const val MODE_FULL = "full"
    const val MODE_STARTUP_RECENT_50 = "startup_recent_50"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SmsScanWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "sms_scan",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Fast startup scan: checks last 50 SMS, then automatically runs full scan if balances are zero. */
    fun scanStartup(context: Context) {
        val input = Data.Builder()
            .putString(KEY_SCAN_MODE, MODE_STARTUP_RECENT_50)
            .build()
        val request = OneTimeWorkRequestBuilder<SmsScanWorker>()
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sms_scan_now",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun scanNow(context: Context, force: Boolean = false, fullScan: Boolean = force) {
        val mode = if (fullScan) MODE_FULL else MODE_STANDARD
        val input = Data.Builder()
            .putString(KEY_SCAN_MODE, mode)
            .build()
        val request = OneTimeWorkRequestBuilder<SmsScanWorker>()
            .setInputData(input)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sms_scan_now",
            if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("sms_scan")
    }
}
