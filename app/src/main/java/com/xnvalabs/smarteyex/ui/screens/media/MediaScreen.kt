package com.xnvalabs.smarteyex.ui.screens.media

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.xnvalabs.smarteyex.data.media.MediaRepository
import com.xnvalabs.smarteyex.data.notifications.NotificationRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

/**
 * Media Assistant screen — feature #28. Controls whatever's currently
 * playing (music/podcast) via [MediaRepository], which reuses the
 * Notification access grant from Tahap 5 rather than a separate
 * permission — see MediaRepository's and
 * [NotificationRepository.isAccessGranted]'s doc comments.
 *
 * If Notification access isn't granted, this shows the same kind of
 * banner NotificationListenerScreen does, pointing at the same Settings
 * screen — granting it once covers both features. Session state is
 * refreshed on open and on every resume (e.g. coming back from
 * Settings, or after switching which app is playing).
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun MediaScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var hasAccess by remember { mutableStateOf(NotificationRepository.isAccessGranted(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasAccess = NotificationRepository.isAccessGranted(context)
                if (hasAccess) MediaRepository.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasAccess) {
        if (hasAccess) MediaRepository.refresh(context)
    }

    val state = MediaRepository.state.value

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Media Assistant", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(16.dp))

            when {
                !hasAccess -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LightSurface, RoundedCornerShape(14.dp))
                            .clickable {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }
                            .padding(14.dp),
                    ) {
                        Text(
                            "Izin notifikasi belum aktif — ketuk buat buka Settings (sama kayak Notification Listener)",
                            fontSize = 12.sp,
                            color = AccentOrange,
                        )
                    }
                }
                !state.hasSession -> {
                    Text("Gak ada musik/podcast yang lagi diputar.", fontSize = 13.sp, color = TextMutedLight)
                }
                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(LightSurface, RoundedCornerShape(16.dp))
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            state.title ?: "Tidak diketahui",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryLight,
                        )
                        state.artist?.let {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(it, fontSize = 13.sp, color = TextMutedLight)
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                            ControlButton("⏮") { MediaRepository.previous() }
                            ControlButton(if (state.isPlaying) "⏸" else "▶") {
                                if (state.isPlaying) MediaRepository.pause() else MediaRepository.play()
                            }
                            ControlButton("⏭") { MediaRepository.next() }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .background(LightBgWarm, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 20.sp, color = AccentOrange)
    }
}
