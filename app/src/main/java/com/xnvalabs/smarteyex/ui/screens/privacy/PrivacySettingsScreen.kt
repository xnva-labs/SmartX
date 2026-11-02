package com.xnvalabs.smarteyex.ui.screens.privacy

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.auth.AuthRepository
import com.xnvalabs.smarteyex.data.memory.MemoryRepository
import com.xnvalabs.smarteyex.data.privacy.PrivacyRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

private data class ToggleRow(
    val label: String,
    val description: String,
    val checked: Boolean,
    val onToggle: (Boolean) -> Unit,
)

/**
 * Privacy Control Screen — Tahap 1 of the MVP build order (see
 * "Ringkasan Prioritas" sheet: Privacy → Memory → Backend XNAI → Camera).
 * Every toggle here maps 1:1 to a [PrivacyRepository] field; nothing else
 * in the app may touch camera/mic/memory/cloud/face-recognition data
 * without checking these first.
 *
 * "Hapus semua memori & reset izin" wipes [MemoryRepository] too, via
 * the onClearMemory hook (Tahap 2).
 *
 * User Authentication section (feature #26) added afterward: PIN is
 * fully optional (this feature is non-MVP) — [onSetPin] routes to
 * MainActivity's PinLockScreen in SETUP mode; removing an existing PIN
 * happens right here, same pattern as the memory wipe button, since it
 * needs no multi-step flow.
 *
 * [onBack] fires from the "‹" button. [onSetPin] fires from "Set PIN".
 */
@Composable
fun PrivacySettingsScreen(onBack: () -> Unit, onSetPin: () -> Unit, onOpenAccount: () -> Unit = {}) {
    val settings = PrivacyRepository.settings.value
    val pinSet = AuthRepository.isPinSet()

    val rows = listOf(
        ToggleRow(
            label = "Camera",
            description = "Izinkan SmartEyeX mengakses kamera untuk computer vision & OCR.",
            checked = settings.cameraEnabled,
            onToggle = PrivacyRepository::setCameraEnabled,
        ),
        ToggleRow(
            label = "Microphone",
            description = "Izinkan SmartEyeX mendengar voice command & percakapan.",
            checked = settings.microphoneEnabled,
            onToggle = PrivacyRepository::setMicrophoneEnabled,
        ),
        ToggleRow(
            label = "Memory",
            description = "Izinkan SmartEyeX menyimpan memori pribadi (orang, catatan, jadwal).",
            checked = settings.memoryEnabled,
            onToggle = PrivacyRepository::setMemoryEnabled,
        ),
        ToggleRow(
            label = "Cloud Processing",
            description = "Izinkan pertanyaan kompleks diproses lewat cloud AI, bukan cuma on-device.",
            checked = settings.cloudProcessingEnabled,
            onToggle = PrivacyRepository::setCloudProcessingEnabled,
        ),
        ToggleRow(
            label = "Face Recognition",
            description = "Izinkan SmartEyeX mengenali wajah orang yang sudah kamu daftarkan.",
            checked = settings.faceRecognitionEnabled,
            onToggle = PrivacyRepository::setFaceRecognitionEnabled,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LightBgWarm),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier
                        .clickable { onBack() }
                        .padding(end = 12.dp),
                )
                Text("Privacy Control", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Semua OFF secara default. Kamu yang tentuin apa yang boleh SmartEyeX akses.",
                fontSize = 13.sp,
                color = TextMutedLight,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LightSurface, RoundedCornerShape(14.dp))
                            .padding(16.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(row.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(row.description, fontSize = 12.sp, color = TextMutedLight)
                        }
                        Switch(
                            checked = row.checked,
                            onCheckedChange = row.onToggle,
                            colors = SwitchDefaults.colors(checkedTrackColor = AccentOrange),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("USER AUTHENTICATION", fontSize = 11.sp, color = TextMutedLight, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("PIN Lock", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                    Text(
                        if (pinSet) "Aktif — app terkunci tiap dibuka" else "Belum diaktifkan",
                        fontSize = 12.sp,
                        color = TextMutedLight,
                    )
                }
                Text(
                    if (pinSet) "Hapus PIN" else "Set PIN",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentOrange,
                    modifier = Modifier.clickable {
                        if (pinSet) AuthRepository.clearPin() else onSetPin()
                    },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .clickable { onOpenAccount() }
                    .padding(16.dp),
            ) {
                Text(
                    "Akun & Server (masuk, keluar, hapus akun)",
                    fontSize = 14.sp,
                    color = AccentOrange,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .clickable {
                        PrivacyRepository.clearAllData(onClearMemory = MemoryRepository::deleteAll)
                    }
                    .padding(16.dp),
            ) {
                Text(
                    "Hapus semua memori & reset izin",
                    fontSize = 14.sp,
                    color = AccentOrange,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
