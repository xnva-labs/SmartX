package com.xnvalabs.smarteyex.ui.screens.library

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.education.EducationRepository
import com.xnvalabs.smarteyex.data.education.StudyPlanner
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight
import kotlinx.coroutines.launch

/**
 * Perpustakaan (personal library) — the user's own study list, grouped
 * by subject. Tap an item to mark it done / not done; the Progress
 * screen reads the same list. Backed by [EducationRepository].
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val all = EducationRepository.items.value
    val grouped = all.groupBy { it.subject }.toSortedMap()
    var titleDraft by remember { mutableStateOf("") }
    var subjectDraft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var topicDraft by remember { mutableStateOf("") }
    var isPlanning by remember { mutableStateOf(false) }
    var planStatus by remember { mutableStateOf<String?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Perpustakaan", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text("Daftar materi belajar kamu. Ketuk buat tandai selesai.", fontSize = 13.sp, color = TextMutedLight)
            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Box {
                    if (titleDraft.isEmpty()) Text("Materi (mis. Termodinamika Bab 2)", fontSize = 14.sp, color = TextMutedLight)
                    BasicTextField(
                        value = titleDraft,
                        onValueChange = { titleDraft = it },
                        textStyle = TextStyle(color = TextPrimaryLight, fontSize = 14.sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (subjectDraft.isEmpty()) Text("Mata pelajaran (opsional)", fontSize = 14.sp, color = TextMutedLight)
                        BasicTextField(
                            value = subjectDraft,
                            onValueChange = { subjectDraft = it },
                            textStyle = TextStyle(color = TextPrimaryLight, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        "+ Tambah",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange,
                        modifier = Modifier.clickable {
                            if (titleDraft.isNotBlank()) {
                                EducationRepository.add(titleDraft.trim(), subjectDraft.trim())
                                titleDraft = ""
                                subjectDraft = ""
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Text("RENCANA DARI XNAI", fontSize = 10.sp, color = TextMutedLight, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (topicDraft.isEmpty()) Text("Topik (mis. Aerodinamika dasar)", fontSize = 14.sp, color = TextMutedLight)
                        BasicTextField(
                            value = topicDraft,
                            onValueChange = { topicDraft = it },
                            textStyle = TextStyle(color = TextPrimaryLight, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Text(
                        if (isPlanning) "Mikir..." else "Buat",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentOrange,
                        modifier = Modifier.clickable(enabled = !isPlanning && topicDraft.isNotBlank()) {
                            isPlanning = true
                            planStatus = null
                            val topic = topicDraft.trim()
                            scope.launch {
                                val result = StudyPlanner.generate(topic)
                                isPlanning = false
                                planStatus = result.fold(
                                    onSuccess = { n -> topicDraft = ""; "$n materi ditambahin ke \"$topic\"." },
                                    onFailure = { e -> e.message ?: "Gagal bikin rencana." },
                                )
                            }
                        },
                    )
                }
                planStatus?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = TextMutedLight)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (all.isEmpty()) {
                Text("Belum ada materi.", fontSize = 13.sp, color = TextMutedLight)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    grouped.forEach { (subject, list) ->
                        item(key = "h-$subject") {
                            Text(
                                subject.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextMutedLight,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        items(list, key = { it.id }) { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(LightSurface, RoundedCornerShape(12.dp))
                                    .clickable { EducationRepository.toggleDone(item.id) }
                                    .padding(14.dp),
                            ) {
                                Text(
                                    if (item.done) "✓" else "○",
                                    fontSize = 16.sp,
                                    color = if (item.done) AccentOrange else TextMutedLight,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                                Text(
                                    item.title,
                                    fontSize = 14.sp,
                                    color = if (item.done) TextMutedLight else TextPrimaryLight,
                                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "✕",
                                    fontSize = 13.sp,
                                    color = TextMutedLight,
                                    modifier = Modifier
                                        .clickable { EducationRepository.remove(item.id) }
                                        .padding(start = 10.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
