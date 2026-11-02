package com.xnvalabs.smarteyex.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.xnvalabs.smarteyex.MainActivity
import com.xnvalabs.smarteyex.R
import com.xnvalabs.smarteyex.data.reminder.ReminderRepository

/**
 * Fires when a scheduled reminder's AlarmManager alarm goes off. Shows a
 * system notification, then reschedules itself 24h later via
 * [ReminderRepository.rescheduleNextDay] — reminders in SmartEyeX are
 * daily-repeating by default, matching the feature spec's "Ingetin gue
 * jam 7 buat belajar" style examples (a recurring habit reminder, not a
 * one-off).
 *
 * The receiver can fire while the app process isn't running, so it
 * initializes [ReminderRepository] itself before touching it — without
 * that the saved reminders would be empty and the next day's alarm would
 * never be scheduled.
 *
 * Notification posting is wrapped in runCatching: on Android 13+,
 * POST_NOTIFICATIONS might not be granted (ReminderScreen requests it,
 * but the user can still deny it) — if so, the alarm still fires and
 * still reschedules, it just can't show anything.
 */
class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ID = "extra_id"
        private const val CHANNEL_ID = "smarteyex_reminders"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
        val id = intent.getIntExtra(EXTRA_ID, 0)

        ReminderRepository.init(context)
        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("SmartEyeX")
            .setContentText(title)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(id, notification)
        }

        ReminderRepository.rescheduleNextDay(id)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_HIGH),
                )
            }
        }
    }
}
