package com.xnvalabs.smarteyex.ui.screens.device

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.glasses.GlassesRepository
import com.xnvalabs.smarteyex.data.glasses.ProtocolIds
import com.xnvalabs.smarteyex.ui.components.FormCard
import com.xnvalabs.smarteyex.ui.components.PrimaryButton
import com.xnvalabs.smarteyex.ui.components.SecondaryButton
import com.xnvalabs.smarteyex.ui.components.SectionLabel
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

/**
 * SmartEyeX glasses card for the Device screen: scan, pair (first time: hold the glasses' button while powering them on),
 * connect, live status, and quick tests. Everything real-time runs in GlassesRepository.
 */
@Composable
fun GlassesSection() {
    val context = LocalContext.current
    val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    var pendingScan by remember { mutableStateOf(true) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted[Manifest.permission.BLUETOOTH_SCAN] == true && granted[Manifest.permission.BLUETOOTH_CONNECT] == true
        if (!ok) GlassesRepository.reportPermissionDenied()
        else if (pendingScan) GlassesRepository.startScan() else GlassesRepository.reconnectLast()
    }
    val state = GlassesRepository.state
    val status = GlassesRepository.status
    val info = GlassesRepository.info

    SectionLabel("KACAMATA SMARTEYEX")
    FormCard {
        Text(
            when (state) {
                GlassesRepository.State.IDLE -> "Tidak terhubung"
                GlassesRepository.State.SCANNING -> "Memindai…"
                GlassesRepository.State.CONNECTING -> "Menghubungkan…"
                GlassesRepository.State.AUTHENTICATING -> "Mengautentikasi…"
                GlassesRepository.State.READY -> "Terhubung · ${info?.hwId ?: ""}"
            },
            fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight,
        )
        GlassesRepository.message?.let { Text(it, fontSize = 12.sp, color = TextMutedLight) }

        if (state == GlassesRepository.State.READY) {
            if (status != null) {
                val battery = if (status.batteryKnown) "${status.battery}%" else "tidak terukur"
                val charge = when (status.charge) { ProtocolIds.Charge.CHARGING -> " · mengisi"; ProtocolIds.Charge.FULL -> " · penuh"; else -> "" }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Baterai", fontSize = 13.sp, color = TextMutedLight)
                    Text(battery + charge, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Penutup privasi", fontSize = 13.sp, color = TextMutedLight)
                    Text(if (status.has(ProtocolIds.Flag.PRIVACY_CLOSED)) "TERTUTUP (kamera & mikrofon mati)" else "terbuka", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                }
            }
            if (info != null) Text("Firmware ${info.firmware} · ${info.hardware}", fontSize = 11.sp, color = TextMutedLight)
            Text("Tahan tombol: bicara ke XNAI · Tekan: jelaskan yang terlihat · Tekan dua kali: bacakan teks", fontSize = 11.sp, color = TextMutedLight)
            PrimaryButton("Tes nada di speaker kacamata") { GlassesRepository.testTone() }
            SecondaryButton("Tes: ambil foto dan jelaskan") { GlassesRepository.testDescribe() }
            SecondaryButton("Tes: ambil foto dan bacakan teks") { GlassesRepository.testRead() }
            SecondaryButton("Putuskan") { GlassesRepository.disconnect() }
            SecondaryButton("Reset kacamata (hapus pairing)") { GlassesRepository.resetGlasses() }
        } else if (state == GlassesRepository.State.IDLE || state == GlassesRepository.State.SCANNING) {
            PrimaryButton(if (state == GlassesRepository.State.SCANNING) "Memindai…" else "Cari kacamata", enabled = state == GlassesRepository.State.IDLE) {
                pendingScan = true
                launcher.launch(permissions)
            }
            if (GlassesRepository.lastAddress != null && state == GlassesRepository.State.IDLE) {
                SecondaryButton("Sambungkan kacamata terakhir") {
                    pendingScan = false
                    launcher.launch(permissions)
                }
            }
            if (GlassesRepository.found.isNotEmpty()) {
                SectionLabel("DITEMUKAN")
                GlassesRepository.found.forEach { f ->
                    SecondaryButton("${f.name}  ·  ${f.address}") { GlassesRepository.connect(f.address) }
                }
            }
            Text("Pertama kali: tahan tombol kacamata sambil menyalakannya sekitar 3 detik (LED berkedip cepat), lalu pilih kacamata di atas.", fontSize = 11.sp, color = TextMutedLight)
        }
    }
    if (GlassesRepository.log.isNotEmpty()) {
        SectionLabel("AKTIVITAS ASISTEN")
        FormCard {
            GlassesRepository.log.take(8).forEach { Text(it, fontSize = 11.sp, color = TextPrimaryLight) }
        }
    }
}
