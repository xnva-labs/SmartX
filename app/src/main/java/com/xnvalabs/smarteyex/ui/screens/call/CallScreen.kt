package com.xnvalabs.smarteyex.ui.screens.call

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.call.CallRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

/**
 * Call Assistant screen — quick-dial list. Tapping a contact opens the
 * phone dialer pre-filled (ACTION_DIAL), so the user's own tap on the
 * call button is the confirmation — same safe pattern as
 * EmergencyScreen. Contacts are saved by hand here (name + number); it
 * doesn't read the phone's address book (see [CallRepository]).
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun CallScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val contacts = CallRepository.contacts.value
    var nameDraft by remember { mutableStateOf("") }
    var phoneDraft by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Call Assistant", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("Ketuk kontak buat buka dialer — kamu yang mencet tombol panggil.", fontSize = 13.sp, color = TextMutedLight)
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Box {
                    if (nameDraft.isEmpty()) Text("Nama", fontSize = 14.sp, color = TextMutedLight)
                    BasicTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        textStyle = TextStyle(color = TextPrimaryLight, fontSize = 14.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (phoneDraft.isEmpty()) Text("Nomor telepon", fontSize = 14.sp, color = TextMutedLight)
                        BasicTextField(
                            value = phoneDraft,
                            onValueChange = { phoneDraft = it },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            textStyle = TextStyle(color = TextPrimaryLight, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "+ Simpan",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange,
                        modifier = Modifier.clickable {
                            if (nameDraft.isNotBlank() && phoneDraft.isNotBlank()) {
                                CallRepository.add(nameDraft.trim(), phoneDraft.trim())
                                nameDraft = ""
                                phoneDraft = ""
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (contacts.isEmpty()) {
                Text("Belum ada kontak.", fontSize = 13.sp, color = TextMutedLight)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(contacts, key = { it.name + it.phone }) { c ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LightSurface, RoundedCornerShape(14.dp))
                                .clickable { openDialer(context, c.phone) }
                                .padding(16.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(c.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                                Text(c.phone, fontSize = 12.sp, color = TextMutedLight)
                            }
                            Text(
                                "Hapus",
                                fontSize = 12.sp,
                                color = AccentOrange,
                                modifier = Modifier.clickable { CallRepository.remove(c) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun openDialer(context: Context, phone: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(phone)}")))
    } catch (_: ActivityNotFoundException) {
        // No dialer app — nothing sensible to fall back to.
    }
}
