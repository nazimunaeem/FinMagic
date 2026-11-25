package com.finmagic.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.finmagic.FinMagicApp
import com.finmagic.MainActivity
import com.finmagic.R
import com.finmagic.data.local.entity.BillWithCard

object NotificationHelper {

    private const val ACTION_MARK_PAID = "com.finmagic.ACTION_MARK_PAID"
    const val EXTRA_BILL_ID = "bill_id"

    fun showDueDateReminder(context: Context, bill: BillWithCard, daysLeft: Int) {
        val title = if (daysLeft == 0) "Due Today!" else "Due in $daysLeft day(s)"
        val text = "${bill.displayName}: ${bill.formattedTotal} due ${bill.formattedTotal}"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, bill.billId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val paidIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ACTION_MARK_PAID
            putExtra(EXTRA_BILL_ID, bill.billId)
        }
        val paidPendingIntent = PendingIntent.getBroadcast(
            context, bill.billId.toInt() + 10000, paidIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, FinMagicApp.DUE_DATE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_notification_overlay)
            .setContentTitle(title)
            .setContentText("${bill.displayName} • ${bill.formattedTotal}")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${bill.displayName}\nTotal Due: ${bill.formattedTotal}\nDue: ${bill.formattedTotal}\n${daysLeft.coerceAtLeast(0)} day(s) remaining"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "Mark Paid", paidPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(bill.billId.toInt(), notification)
    }

    fun showOverdueAlert(context: Context, bill: BillWithCard) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, bill.billId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val paidIntent = Intent(context, ReminderActionReceiver::class.java).apply {
            action = ACTION_MARK_PAID
            putExtra(EXTRA_BILL_ID, bill.billId)
        }
        val paidPendingIntent = PendingIntent.getBroadcast(
            context, bill.billId.toInt() + 20000, paidIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, FinMagicApp.OVERDUE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("OVERDUE!")
            .setContentText("${bill.displayName}: ${bill.formattedTotal}")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${bill.displayName}\nTotal Due: ${bill.formattedTotal}\nThis bill is overdue! Please pay immediately."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "Mark Paid", paidPendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(bill.billId.toInt() + 50000, notification)
    }

    /** Large account debit alert (plan Phase 5). */
    fun showLargeDebitAlert(
        context: Context,
        institution: String,
        accountMask: String,
        amount: Double,
        counterparty: String?
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, (institution + accountMask).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val formatted = "৳%,.2f".format(amount)
        val title = "Large debit: $formatted"
        val text = "$institution ••$accountMask" + (counterparty?.let { " — $it" } ?: "")

        val notification = NotificationCompat.Builder(context, FinMagicApp.ALERTS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify((institution + accountMask + amount).hashCode(), notification)
    }
}
