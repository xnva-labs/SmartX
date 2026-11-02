package com.xnvalabs.smarteyex.ui.screens.enterprise

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.enterprise.EnterpriseRepository
import com.xnvalabs.smarteyex.data.enterprise.TaskStatus
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight

/**
 * Enterprise Mode screen — a local task board plus a summary row
 * (Todo / Dikerjakan / Selesai counts). Tap a task's status chip to
 * advance it. Single-device only; see [EnterpriseRepository] for why
 * there's no team sync.
 *
 * [onBack] fires from the "‹" button.
 */
@Composable
fun EnterpriseScreen(onBack: () -> Unit) {
    val tasks = EnterpriseRepository.tasks.value
    var titleDraft by remember { mutableStateOf("") }
    var assigneeDraft by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize().background(LightBgWarm)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "‹",
                    fontSize = 26.sp,
                    color = TextPrimaryLight,
                    modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
                )
                Text("Enterprise", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TaskStatus.entries.forEach { status ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .background(LightSurface, RoundedCornerShape(14.dp))
                            .padding(vertical = 14.dp),
                    ) {
                        Text(
                            tasks.count { it.status == status }.toString(),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentOrange,
                        )
                        Text(status.label, fontSize = 11.sp, color = TextMutedLight)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LightSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
            ) {
                Box {
                    if (titleDraft.isEmpty()) Text("Tugas baru", fontSize = 14.sp, color = TextMutedLight)
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
                        if (assigneeDraft.isEmpty()) Text("Penanggung jawab (opsional)", fontSize = 14.sp, color = TextMutedLight)
                        BasicTextField(
                            value = assigneeDraft,
                            onValueChange = { assigneeDraft = it },
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
                                EnterpriseRepository.add(titleDraft.trim(), assigneeDraft.trim())
                                titleDraft = ""
                                assigneeDraft = ""
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (tasks.isEmpty()) {
                Text("Belum ada tugas.", fontSize = 13.sp, color = TextMutedLight)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(tasks, key = { it.id }) { task ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(LightSurface, RoundedCornerShape(14.dp))
                                .padding(14.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(task.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                                if (task.assignee.isNotBlank()) {
                                    Text(task.assignee, fontSize = 12.sp, color = TextMutedLight)
                                }
                            }
                            Text(
                                task.status.label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (task.status == TaskStatus.DONE) LightBgWarm else AccentOrange,
                                modifier = Modifier
                                    .background(
                                        if (task.status == TaskStatus.DONE) AccentOrange else LightBgWarm,
                                        RoundedCornerShape(10.dp),
                                    )
                                    .clickable { EnterpriseRepository.advance(task.id) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                            Text(
                                "✕",
                                fontSize = 13.sp,
                                color = TextMutedLight,
                                modifier = Modifier
                                    .clickable { EnterpriseRepository.remove(task.id) }
                                    .padding(start = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
