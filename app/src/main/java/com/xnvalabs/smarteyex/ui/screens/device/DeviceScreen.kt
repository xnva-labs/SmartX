package com.xnvalabs.smarteyex.ui.screens.device

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.auth.AuthRepository
import com.xnvalabs.smarteyex.data.device.DeviceStatusRepository
import com.xnvalabs.smarteyex.data.notifications.NotificationRepository
import com.xnvalabs.smarteyex.data.privacy.PrivacyRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

/**
 * Device Management screen — feature #37. A pull-based status dashboard
 * (see [DeviceStatusRepository]'s doc comment for why not a live
 * receiver) plus a recap of every permission/toggle this app tracks
 * across Tahap 1-6 — Camera/Mic/Memory/Cloud/Face from
 * [PrivacyRepository], PIN lock from [AuthRepository], Notification
 * access from [NotificationRepository], exact-alarm straight from
 * AlarmManager. Nothing here is new state; this screen just reads what
 * already exists elsewhere — exactly what feature #37 asks for
 * ("Aplikasi dapat menunjukkan battery, connection... camera status,
 * microphone status").
 *
 * [onBack] fires from the "‹" button. "↻" forces a fresh read (battery %
 * and connection type can change while the screen is open).
 */
@Composable
fun DeviceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val status = remember(refreshTick) { DeviceStatusRepository.snapshot(context) }
    val privacy = PrivacyRepository.settings.value

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Device Management", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "↻",
                    fontSize = 18.sp,
                    color = AccentOrange,
                    modifier = Modifier.clickable { refreshTick++ },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            StatusCard {
                StatusRow("Baterai", "${status.batteryPercent}%" + if (status.isCharging) " (charging)" else "")
                StatusRow("Koneksi", status.networkType)
                StatusRow(
                    "Storage",
                    "%.1f GB bebas dari %.1f GB".format(status.storageFreeGb, status.storageTotalGb),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            GlassesSection()

            Spacer(modifier = Modifier.height(16.dp))
            Text("PRIVACY & PERMISSIONS", fontSize = 11.sp, color = TextMutedLight, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

            StatusCard {
                StatusRow("Camera", if (privacy.cameraEnabled) "ON" else "OFF")
                StatusRow("Microphone", if (privacy.microphoneEnabled) "ON" else "OFF")
                StatusRow("Memory", if (privacy.memoryEnabled) "ON" else "OFF")
                StatusRow("Cloud Processing", if (privacy.cloudProcessingEnabled) "ON" else "OFF")
                StatusRow("Face Recognition", if (privacy.faceRecognitionEnabled) "ON" else "OFF")
                StatusRow("PIN Lock", if (AuthRepository.isPinSet()) "Aktif" else "Tidak aktif")
                StatusRow(
                    "Notification Access",
                    if (NotificationRepository.isAccessGranted(context)) "Diizinkan" else "Belum diizinkan",
                )
                StatusRow("Alarm Presisi", if (canScheduleExact(context)) "Diizinkan" else "Belum diizinkan")
            }
        }
    }
}

@Composable
private fun StatusCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(LightSurface, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = TextMutedLight)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
    }
}

private fun canScheduleExact(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    return alarmManager.canScheduleExactAlarms()
}
