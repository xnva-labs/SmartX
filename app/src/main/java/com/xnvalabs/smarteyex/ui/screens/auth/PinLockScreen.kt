package com.xnvalabs.smarteyex.ui.screens.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.auth.AuthRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

enum class PinMode { UNLOCK, SETUP }

private enum class Stage { UNLOCK, ENTER_NEW, CONFIRM_NEW }

/**
 * PIN entry screen — feature #26 (User Authentication), used two ways:
 * [PinMode.UNLOCK] gates app entry when a PIN is already set (checked
 * once per process start via [AuthRepository.isUnlocked] — see
 * MainActivity — not a per-screen timeout); [PinMode.SETUP] is reached
 * from Privacy Control's "Set PIN" button and walks through
 * enter-then-confirm before calling [AuthRepository.setPin].
 *
 * 4-digit PIN, numeric keypad only — no biometrics, no recovery flow.
 *
 * [onDone] fires once unlocked (UNLOCK) or once the PIN is set (SETUP).
 * [onCancel] fires from "Batal" — only shown in SETUP mode; UNLOCK has
 * no way out except a correct PIN, by design.
 */
@Composable
fun PinLockScreen(mode: PinMode, onDone: () -> Unit, onCancel: (() -> Unit)? = null) {
    var stage by remember { mutableStateOf(if (mode == PinMode.SETUP) Stage.ENTER_NEW else Stage.UNLOCK) }
    var firstPin by remember { mutableStateOf("") }
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val title = when (stage) {
        Stage.UNLOCK -> "Masukkan PIN"
        Stage.ENTER_NEW -> "Buat PIN baru"
        Stage.CONFIRM_NEW -> "Ulangi PIN"
    }

    fun onDigit(d: String) {
        if (entered.length >= 4) return
        entered += d
        error = null
        if (entered.length == 4) {
            when (stage) {
                Stage.UNLOCK -> {
                    if (AuthRepository.verify(entered)) {
                        onDone()
                    } else {
                        val wait = AuthRepository.lockRemainingSeconds()
                        error = if (wait > 0) "Terlalu banyak percobaan. Coba lagi dalam $wait detik" else "PIN salah, coba lagi"
                        entered = ""
                    }
                }
                Stage.ENTER_NEW -> {
                    firstPin = entered
                    entered = ""
                    stage = Stage.CONFIRM_NEW
                }
                Stage.CONFIRM_NEW -> {
                    if (entered == firstPin) {
                        AuthRepository.setPin(entered)
                        onDone()
                    } else {
                        error = "PIN gak cocok, ulangi dari awal"
                        entered = ""
                        firstPin = ""
                        stage = Stage.ENTER_NEW
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(60.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                error ?: "4 digit angka",
                fontSize = 13.sp,
                color = if (error != null) AccentOrange else TextMutedLight,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(4) { i ->
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(
                                if (i < entered.length) AccentOrange else LightSurface,
                                CircleShape,
                            ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("", "0", "⌫"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(
                                        if (key.isNotEmpty()) LightSurface else Color.Transparent,
                                        CircleShape,
                                    )
                                    .then(
                                        if (key.isNotEmpty()) {
                                            Modifier.clickable {
                                                if (key == "⌫") {
                                                    if (entered.isNotEmpty()) entered = entered.dropLast(1)
                                                    error = null
                                                } else {
                                                    onDigit(key)
                                                }
                                            }
                                        } else {
                                            Modifier
                                        },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (key.isNotEmpty()) {
                                    Text(key, fontSize = 22.sp, color = TextPrimaryLight)
                                }
                            }
                        }
                    }
                }
            }

            if (onCancel != null) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Batal",
                    fontSize = 13.sp,
                    color = TextMutedLight,
                    modifier = Modifier.clickable { onCancel() },
                )
            }
        }
    }
}
