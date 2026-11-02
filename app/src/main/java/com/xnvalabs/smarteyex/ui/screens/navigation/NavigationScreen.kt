package com.xnvalabs.smarteyex.ui.screens.navigation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

private data class TravelMode(val label: String, val code: String)

private val TRAVEL_MODES = listOf(
    TravelMode("Jalan kaki", "w"),
    TravelMode("Motor", "l"),
    TravelMode("Mobil", "d"),
    TravelMode("Sepeda", "b"),
)

/**
 * Navigation screen — hands the destination off to Google Maps'
 * turn-by-turn navigation via the `google.navigation:` intent, same
 * "let the system app do the heavy lifting" approach as
 * EmergencyScreen's ACTION_DIAL. No location permission needed: Maps
 * itself knows where the user is. Falls back to a generic `geo:` search
 * intent (any installed maps app) if Google Maps isn't installed.
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun NavigationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var destination by remember { mutableStateOf("") }
    var modeIndex by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Navigation", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("Ketik tujuan, navigasi jalan lewat Google Maps.", fontSize = 13.sp, color = TextMutedLight)
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Box {
                    if (destination.isEmpty()) {
                        Text("Mau ke mana? (alamat / nama tempat)", fontSize = 14.sp, color = TextMutedLight)
                    }
                    BasicTextField(
                        value = destination,
                        onValueChange = { destination = it; error = null },
                        textStyle = TextStyle(color = TextPrimaryLight, fontSize = 14.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TRAVEL_MODES.forEachIndexed { i, mode ->
                    Text(
                        mode.label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (i == modeIndex) LightBgWarm else TextPrimaryLight,
                        modifier = Modifier
                            .background(
                                if (i == modeIndex) AccentOrange else LightSurface,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { modeIndex = i }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .clickable(enabled = destination.isNotBlank()) {
                        val ok = startNavigation(context, destination.trim(), TRAVEL_MODES[modeIndex].code)
                        if (!ok) error = "Gak ada aplikasi peta yang bisa dibuka."
                    }
                    .padding(16.dp),
            ) {
                Text("Mulai Navigasi", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentOrange)
            }

            error?.let {
                Spacer(modifier = Modifier.height(10.dp))
                Text(it, fontSize = 12.sp, color = AccentOrange)
            }
        }
    }
}

private fun startNavigation(context: Context, destination: String, modeCode: String): Boolean {
    val encoded = Uri.encode(destination)
    val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$encoded&mode=$modeCode"))
        .setPackage("com.google.android.apps.maps")
    return try {
        context.startActivity(navIntent)
        true
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
