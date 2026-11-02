package com.xnvalabs.smarteyex.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.xnvalabs.smarteyex.data.reminder.ReminderRepository

/**
 * Android clears all AlarmManager alarms on reboot (and on app update),
 * so without this every reminder would silently stop until the user
 * reopened the app. Re-arms every saved reminder on BOOT_COMPLETED and
 * MY_PACKAGE_REPLACED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ReminderRepository.init(context)
            ReminderRepository.rescheduleAll()
        }
    }
}
