package com.xnvalabs.smarteyex.data.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import com.xnvalabs.smarteyex.service.ReminderReceiver
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Feature #12 (Reminder & Alarm) — schedules real system alarms via
 * AlarmManager, one per reminder, keyed by [ReminderEntry.id] as the
 * PendingIntent request code. Persists reminders the same way
 * Memory/Privacy do (SharedPreferences + hand-rolled JSON — still no
 * Room, still a flat list).
 *
 * Reminders are daily-repeating: [schedule] always computes the NEXT
 * occurrence of hour:minute (today if it hasn't passed yet, else
 * tomorrow), and ReminderReceiver calls [rescheduleNextDay] right after
 * firing, which lands on tomorrow since today's time has now passed.
 * There's no one-off or custom-weekday reminder yet.
 *
 * Exact alarms need SCHEDULE_EXACT_ALARM on Android 12+, which — like
 * notification access — can't be requested via a runtime dialog;
 * ReminderScreen checks AlarmManager.canScheduleExactAlarms() and links
 * to Settings if it's off. If exact scheduling isn't available, this
 * still schedules inexactly (setAndAllowWhileIdle) rather than silently
 * failing — the reminder just might fire a little late.
 */
object ReminderRepository {
    private const val PREFS_NAME = "smarteyex_reminders"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_NEXT_ID = "next_id"

    private lateinit var prefs: SharedPreferences
    private lateinit var appContext: Context
    private var initialized = false

    var reminders = mutableStateOf(emptyList<ReminderEntry>())
        private set

    /** Call once, from MainActivity.onCreate, before any screen reads [reminders]. */
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        reminders.value = load()
        initialized = true
    }

    fun add(title: String, hour: Int, minute: Int) {
        val id = prefs.getInt(KEY_NEXT_ID, 1)
        val entry = ReminderEntry(id, title, hour, minute)
        persist(reminders.value + entry)
        prefs.edit().putInt(KEY_NEXT_ID, id + 1).apply()
        schedule(entry)
    }

    fun remove(id: Int) {
        cancelAlarm(id)
        persist(reminders.value.filterNot { it.id == id })
    }

    /** Called by ReminderReceiver right after firing, to re-arm for the same time tomorrow. */
    fun rescheduleNextDay(id: Int) {
        val entry = reminders.value.firstOrNull { it.id == id } ?: return
        schedule(entry)
    }

    /** Re-arms every saved reminder. Called after a reboot or app update, when the system has dropped our alarms. */
    fun rescheduleAll() {
        reminders.value.forEach { schedule(it) }
    }

    private fun schedule(entry: ReminderEntry) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = nextTriggerMillis(entry.hour, entry.minute)
        val pendingIntent = pendingIntentFor(entry)
        runCatching {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }
    }

    private fun cancelAlarm(id: Int) {
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntentFor(ReminderEntry(id, "", 0, 0)))
    }

    private fun pendingIntentFor(entry: ReminderEntry): PendingIntent {
        val intent = Intent(appContext, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, entry.title)
            putExtra(ReminderReceiver.EXTRA_ID, entry.id)
        }
        return PendingIntent.getBroadcast(
            appContext,
            entry.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun nextTriggerMillis(hour: Int, minute: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(now)) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    private fun persist(next: List<ReminderEntry>) {
        reminders.value = next
        val array = JSONArray()
        next.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("id", r.id)
                    put("title", r.title)
                    put("hour", r.hour)
                    put("minute", r.minute)
                },
            )
        }
        prefs.edit().putString(KEY_ENTRIES, array.toString()).apply()
    }

    private fun load(): List<ReminderEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ReminderEntry(
                    id = obj.getInt("id"),
                    title = obj.getString("title"),
                    hour = obj.getInt("hour"),
                    minute = obj.getInt("minute"),
                )
            }
        }.getOrDefault(emptyList())
    }
}
