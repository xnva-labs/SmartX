package com.xnvalabs.smarteyex.ui.screens.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.education.EducationRepository
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

/**
 * Progress screen — read-only summary derived from the same list the
 * Perpustakaan screen edits: overall completion plus a bar per subject.
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun ProgressScreen(onBack: () -> Unit) {
    val all = EducationRepository.items.value
    val doneCount = all.count { it.done }
    val overall = if (all.isEmpty()) 0f else doneCount.toFloat() / all.size
    val bySubject = all.groupBy { it.subject }.toSortedMap().toList()

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Progress", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(16.dp))
                    .padding(20.dp),
            ) {
                Text("TOTAL", fontSize = 10.sp, color = TextMutedLight, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${(overall * 100).toInt()}%",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentOrange,
                )
                Text("$doneCount dari ${all.size} materi selesai", fontSize = 13.sp, color = TextMutedLight)
                Spacer(modifier = Modifier.height(12.dp))
                Bar(overall)
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (all.isEmpty()) {
                Text("Belum ada materi — tambah dulu di Perpustakaan.", fontSize = 13.sp, color = TextMutedLight)
            } else {
                Text("PER MATA PELAJARAN", fontSize = 11.sp, color = TextMutedLight, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(bySubject, key = { it.first }) { (subject, list) ->
                        val done = list.count { it.done }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LightSurface, RoundedCornerShape(14.dp))
                                .padding(16.dp),
                        ) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(subject, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                                Text("$done/${list.size}", fontSize = 13.sp, color = TextMutedLight)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Bar(done.toFloat() / list.size)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bar(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(LightBgWarm, RoundedCornerShape(4.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(AccentOrange, RoundedCornerShape(4.dp)),
        )
    }
}
