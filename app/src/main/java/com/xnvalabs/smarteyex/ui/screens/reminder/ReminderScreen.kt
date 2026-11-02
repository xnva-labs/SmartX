package com.xnvalabs.smarteyex.ui.screens.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xnvalabs.smarteyex.data.reminder.ReminderRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight
import java.util.Calendar

/**
 * Reminder & Alarm screen — feature #12, reached via the System screen's
 * "schedule" node. Reminders repeat daily (see ReminderReceiver's doc
 * comment) — this MVP doesn't yet support one-off or custom-day-of-week
 * reminders.
 *
 * On Android 12+, exact alarm scheduling needs SCHEDULE_EXACT_ALARM,
 * which — like Notification access in Tahap 5 — is granted through
 * Settings, not a runtime dialog; the banner below links there when it's
 * off, and re-checks on every resume (e.g. coming back from Settings).
 * Notifications themselves need POST_NOTIFICATIONS on Android 13+, which
 * IS a normal runtime dialog, requested automatically on first open.
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun ReminderScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val reminders = ReminderRepository.reminders.value
    var titleDraft by remember { mutableStateOf("") }
    var pickedHour by remember { mutableStateOf<Int?>(null) }
    var pickedMinute by remember { mutableStateOf<Int?>(null) }

    var hasExactAlarm by remember { mutableStateOf(canScheduleExact(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasExactAlarm = canScheduleExact(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var hasNotifPermission by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotifPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Reminder & Alarm", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("Reminder harian — nyala tiap hari di jam yang sama.", fontSize = 13.sp, color = TextMutedLight)

            if (!hasExactAlarm) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LightSurface, RoundedCornerShape(14.dp))
                        .clickable {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                        }
                        .padding(14.dp),
                ) {
                    Text("Izin alarm presisi belum aktif — ketuk buat buka Settings", fontSize = 12.sp, color = AccentOrange)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Box {
                    if (titleDraft.isEmpty()) {
                        Text("Judul reminder (mis. Belajar)", fontSize = 14.sp, color = TextMutedLight)
                    }
                    BasicTextField(
                        value = titleDraft,
                        onValueChange = { titleDraft = it },
                        textStyle = TextStyle(color = TextPrimaryLight, fontSize = 14.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (pickedHour != null) "%02d:%02d".format(pickedHour, pickedMinute) else "Pilih jam",
                        fontSize = 14.sp,
                        color = if (pickedHour != null) TextPrimaryLight else TextMutedLight,
                        modifier = Modifier
                            .background(LightBgWarm, RoundedCornerShape(10.dp))
                            .clickable {
                                val now = Calendar.getInstance()
                                TimePickerDialog(
                                    context,
                                    { _, h, m -> pickedHour = h; pickedMinute = m },
                                    now.get(Calendar.HOUR_OF_DAY),
                                    now.get(Calendar.MINUTE),
                                    true,
                                ).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "+ Tambah",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange,
                        modifier = Modifier.clickable {
                            val h = pickedHour
                            val m = pickedMinute
                            if (titleDraft.isNotBlank() && h != null && m != null) {
                                ReminderRepository.add(titleDraft.trim(), h, m)
                                titleDraft = ""
                                pickedHour = null
                                pickedMinute = null
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (reminders.isEmpty()) {
                Text("Belum ada reminder.", fontSize = 13.sp, color = TextMutedLight)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(reminders.sortedBy { it.hour * 60 + it.minute }, key = { it.id }) { r ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LightSurface, RoundedCornerShape(14.dp))
                                .padding(16.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(r.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                                Text("%02d:%02d setiap hari".format(r.hour, r.minute), fontSize = 12.sp, color = TextMutedLight)
                            }
                            Text(
                                "Hapus",
                                fontSize = 12.sp,
                                color = AccentOrange,
                                modifier = Modifier.clickable { ReminderRepository.remove(r.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
}
