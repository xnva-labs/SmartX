package com.xnvalabs.smarteyex.ui.screens.emergency

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.emergency.EmergencyRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

/**
 * Emergency Assistance screen — feature #30. Lets the user save ONE
 * emergency contact, then reach them fast via a big button. See
 * [EmergencyRepository]'s doc comment for why this opens the dialer
 * (ACTION_DIAL) instead of calling directly — that manual tap-to-call
 * step is the feature spec's required "confirmation logic", not a
 * missing shortcut.
 *
 * Location-sharing ("mengirim lokasi" in the feature spec) isn't built
 * yet — it needs ACCESS_FINE_LOCATION, a permission this MVP hasn't
 * taken on for any feature so far, and deserves its own deliberate pass
 * rather than being bolted onto this one.
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun EmergencyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val contact = EmergencyRepository.contact.value
    var nameDraft by remember { mutableStateOf(contact?.name ?: "") }
    var phoneDraft by remember { mutableStateOf(contact?.phone ?: "") }
    var editing by remember { mutableStateOf(contact == null) }

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Emergency Assistance", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (contact != null && !editing) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LightSurface, RoundedCornerShape(16.dp))
                        .padding(20.dp),
                ) {
                    Text("KONTAK DARURAT", fontSize = 10.sp, color = TextMutedLight, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(contact.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                    Text(contact.phone, fontSize = 14.sp, color = TextMutedLight)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Ubah kontak",
                        fontSize = 12.sp,
                        color = AccentOrange,
                        modifier = Modifier.clickable { editing = true },
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(Color(0xFFE24A3B), RoundedCornerShape(16.dp))
                        .clickable {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phone}"))
                            context.startActivity(intent)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🆘 HUBUNGI ${contact.name.uppercase()}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Ketuk untuk buka dialer — kamu tetap yang menekan panggil, biar gak kepencet gak sengaja.",
                    fontSize = 11.sp,
                    color = TextMutedLight,
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LightSurface, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                ) {
                    Text("Nama kontak", fontSize = 11.sp, color = TextMutedLight)
                    BasicTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        textStyle = TextStyle(color = TextPrimaryLight, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                    )
                    Text("Nomor telepon", fontSize = 11.sp, color = TextMutedLight)
                    BasicTextField(
                        value = phoneDraft,
                        onValueChange = { phoneDraft = it },
                        textStyle = TextStyle(color = TextPrimaryLight, fontSize = 15.sp),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LightSurface, RoundedCornerShape(14.dp))
                        .clickable(enabled = nameDraft.isNotBlank() && phoneDraft.isNotBlank()) {
                            EmergencyRepository.save(nameDraft.trim(), phoneDraft.trim())
                            editing = false
                        }
                        .padding(16.dp),
                ) {
                    Text("Simpan Kontak", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = AccentOrange)
                }
            }
        }
    }
}
