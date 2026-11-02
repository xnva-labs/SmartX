package com.xnvalabs.smarteyex.ui.screens.org

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.net.ApiClient
import com.xnvalabs.smarteyex.data.org.OrgRepository
import com.xnvalabs.smarteyex.data.session.SessionRepository
import com.xnvalabs.smarteyex.ui.components.ErrorNote
import com.xnvalabs.smarteyex.ui.components.FormCard
import com.xnvalabs.smarteyex.ui.components.FormField
import com.xnvalabs.smarteyex.ui.components.FormHeader
import com.xnvalabs.smarteyex.ui.components.Pill
import com.xnvalabs.smarteyex.ui.components.PrimaryButton
import com.xnvalabs.smarteyex.ui.components.SecondaryButton
import com.xnvalabs.smarteyex.ui.components.SectionLabel
import com.xnvalabs.smarteyex.ui.theme.AccentOrange
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.LightSurface
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Loads data from the server with loading/error state and a manual reload. [key] re-triggers loading when it changes. */
private class Loader<T>(val value: T?, val error: String?, val loading: Boolean, val reload: () -> Unit)

@Composable
private fun <T> rememberLoader(key: Any?, load: suspend () -> T): Loader<T> {
    var value by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick, key) {
        loading = true
        error = null
        try {
            value = load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = ApiClient.describe(e)
        }
        loading = false
    }
    return Loader(value, error, loading) { tick += 1 }
}

/** Enterprise workspace for a signed-in employee. What each tab shows (and allows) follows the role's permissions on the server. */
@Composable
fun OrgScreen(onBack: () -> Unit, onOpenLocalBoard: () -> Unit) {
    val info = SessionRepository.info ?: return
    LaunchedEffect(Unit) { runCatching { SessionRepository.refreshIdentity() } }
    var tab by remember { mutableStateOf("Tugas") }
    val tabs = buildList {
        add("Tugas"); add("Info"); add("Tim"); add("Pengetahuan")
        if (info.can("analytics.read") || info.can("audit.read")) add("Wawasan")
        add("Notifikasi")
    }
    Column(modifier = Modifier.fillMaxSize().background(LightBgWarm).padding(top = 28.dp)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FormHeader(info.orgName ?: "Enterprise", onBack)
            Text("${info.name.ifBlank { info.email }} · ${info.role ?: ""}", fontSize = 12.sp, color = TextMutedLight)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            tabs.forEach { t -> Pill(t, tab == t) { tab = t } }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                "Tugas" -> TasksTab(info)
                "Info" -> AnnouncementsTab(info)
                "Tim" -> PeopleTab(info)
                "Pengetahuan" -> KnowledgeTab(info)
                "Wawasan" -> InsightTab(info)
                else -> NotificationsTab()
            }
            Text("Papan tugas lokal (offline, satu perangkat)", fontSize = 12.sp, color = TextMutedLight, modifier = Modifier.clickable { onOpenLocalBoard() }.padding(vertical = 10.dp))
        }
    }
}

private fun statusLabel(s: String) = when (s) { "todo" -> "Belum"; "doing" -> "Dikerjakan"; "blocked" -> "Terblokir"; "done" -> "Selesai"; else -> s }
private fun nextStatus(s: String) = when (s) { "todo" -> "doing"; "doing" -> "done"; "done" -> "todo"; else -> "doing" }

@Composable
private fun LoadingState(l: Loader<*>) {
    ErrorNote(l.error)
    if (l.loading && l.value == null && l.error == null) Text("Memuat…", fontSize = 12.sp, color = TextMutedLight)
}

@Composable
private fun TasksTab(info: SessionRepository.Info) {
    val scope = rememberCoroutineScope()
    var mine by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("normal") }
    var assignee by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val tasks = rememberLoader(mine) { OrgRepository.tasks(mine) }
    val people = rememberLoader(info.can("tasks.assign")) { if (info.can("tasks.assign")) OrgRepository.people().filter { it.status == "active" } else emptyList() }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Semua", !mine) { mine = false }
        Pill("Milik saya", mine) { mine = true }
        if (info.can("tasks.create")) Pill(if (creating) "Tutup" else "+ Tugas", creating) { creating = !creating }
    }
    if (creating) {
        FormCard {
            FormField("Judul tugas", title, { title = it })
            FormField("Deskripsi (opsional)", desc, { desc = it }, minLines = 2)
            FormField("Tenggat YYYY-MM-DD (opsional)", due, { due = it })
            SectionLabel("PRIORITAS")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("low", "normal", "high", "urgent").forEach { p -> Pill(p, priority == p) { priority = p } } }
            val list = people.value
            if (info.can("tasks.assign") && list != null && list.isNotEmpty()) {
                SectionLabel("DITUGASKAN KE")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("Saya", assignee == null) { assignee = null }
                    list.forEach { p -> Pill(p.name.ifBlank { p.email }, assignee == p.id) { assignee = p.id } }
                }
            }
            ErrorNote(error)
            PrimaryButton("Simpan tugas", enabled = title.isNotBlank()) {
                error = null
                scope.launch {
                    runCatching { OrgRepository.createTask(title.trim(), desc.trim(), assignee ?: info.id, priority, due.trim().ifEmpty { null }) }
                        .onSuccess { title = ""; desc = ""; due = ""; creating = false; tasks.reload() }
                        .onFailure { error = ApiClient.describe(it) }
                }
            }
        }
    }
    LoadingState(tasks)
    ErrorNote(error.takeIf { !creating })
    tasks.value?.let { list ->
        if (list.isEmpty()) Text("Belum ada tugas.", fontSize = 13.sp, color = TextMutedLight)
        list.forEach { t ->
            FormCard {
                Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                if (t.description.isNotBlank()) Text(t.description, fontSize = 12.sp, color = TextMutedLight)
                Text(
                    "Prioritas ${t.priority}" + (t.dueDate?.let { " · tenggat $it" } ?: "") + if (t.overdue) " · TERLAMBAT" else "",
                    fontSize = 11.sp, color = if (t.overdue) AccentOrange else TextMutedLight,
                )
                Pill("Status: ${statusLabel(t.status)}  ›  ${statusLabel(nextStatus(t.status))}", false) {
                    scope.launch {
                        runCatching { OrgRepository.setTaskStatus(t.id, nextStatus(t.status)) }.onFailure { error = ApiClient.describe(it) }
                        tasks.reload()
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnouncementsTab(info: SessionRepository.Info) {
    val scope = rememberCoroutineScope()
    val list = rememberLoader(Unit) { OrgRepository.announcements() }
    var posting by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (info.can("announcements.manage")) SecondaryButton(if (posting) "Tutup" else "Buat pengumuman") { posting = !posting }
    if (posting) {
        FormCard {
            FormField("Judul", title, { title = it })
            FormField("Isi pengumuman", body, { body = it }, minLines = 3)
            Pill(if (pinned) "Disematkan" else "Sematkan", pinned) { pinned = !pinned }
            ErrorNote(error)
            PrimaryButton("Kirim ke seluruh organisasi", enabled = title.isNotBlank() && body.isNotBlank()) {
                scope.launch {
                    runCatching { OrgRepository.postAnnouncement(title.trim(), body.trim(), pinned) }
                        .onSuccess { title = ""; body = ""; pinned = false; posting = false; list.reload() }
                        .onFailure { error = ApiClient.describe(it) }
                }
            }
        }
    }
    LoadingState(list)
    list.value?.let { items ->
        if (items.isEmpty()) Text("Belum ada pengumuman.", fontSize = 13.sp, color = TextMutedLight)
        items.forEach { a ->
            FormCard {
                Text((if (a.pinned) "📌 " else "") + a.title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                Text(a.body, fontSize = 13.sp, color = TextPrimaryLight)
            }
        }
    }
}

@Composable
private fun PeopleTab(info: SessionRepository.Info) {
    val scope = rememberCoroutineScope()
    val people = rememberLoader(Unit) { OrgRepository.people() }
    var inviting by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("member") }
    var code by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val manage = info.can("employees.manage")
    if (manage) SecondaryButton(if (inviting) "Tutup" else "Undang karyawan") { inviting = !inviting; code = null }
    if (inviting) {
        FormCard {
            FormField("Email kerja", email, { email = it })
            FormField("Nama", name, { name = it })
            SectionLabel("PERAN")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val roles = if (info.role == "owner") listOf("member", "manager", "auditor", "admin") else listOf("member", "manager", "auditor")
                roles.forEach { r -> Pill(r, role == r) { role = r } }
            }
            ErrorNote(error)
            PrimaryButton("Buat undangan", enabled = email.isNotBlank() && name.isNotBlank()) {
                error = null
                scope.launch {
                    runCatching { OrgRepository.invite(email, name, role) }
                        .onSuccess { code = it; email = ""; name = ""; people.reload() }
                        .onFailure { error = ApiClient.describe(it) }
                }
            }
            code?.let {
                Text("Kode undangan (berlaku 7 hari, hanya ditampilkan sekali). Bagikan ke karyawan lewat saluran aman:", fontSize = 12.sp, color = TextMutedLight)
                SelectionContainer { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight) }
            }
        }
    }
    LoadingState(people)
    people.value?.forEach { p ->
        FormCard {
            Text(p.name.ifBlank { p.email }, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            Text("${p.role}${if (p.title.isNotBlank()) " · " + p.title else ""} · ${p.email} · ${p.status}", fontSize = 12.sp, color = TextMutedLight)
            if (manage && p.id != info.id && p.status != "invited") {
                val disable = p.status == "active"
                SecondaryButton(if (disable) "Nonaktifkan akun" else "Aktifkan kembali") {
                    scope.launch {
                        runCatching { OrgRepository.setPersonStatus(p.id, if (disable) "disabled" else "active") }.onFailure { error = ApiClient.describe(it) }
                        people.reload()
                    }
                }
            }
        }
    }
    ErrorNote(error.takeIf { !inviting })
}

@Composable
private fun KnowledgeTab(info: SessionRepository.Info) {
    val scope = rememberCoroutineScope()
    val docs = rememberLoader(Unit) { OrgRepository.documents() }
    var question by remember { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<OrgRepository.Answer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("org") }
    var error by remember { mutableStateOf<String?>(null) }
    FormCard {
        Text("Tanya knowledge base", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
        FormField("Pertanyaan (mis. prosedur pemeriksaan mesin)", question, { question = it }, minLines = 2)
        ErrorNote(error)
        PrimaryButton(if (asking) "Mencari jawaban…" else "Tanya XNAI", enabled = !asking && question.trim().length >= 3 && info.can("ai.use")) {
            asking = true
            error = null
            scope.launch {
                runCatching { OrgRepository.ask(question.trim()) }.onSuccess { answer = it }.onFailure { error = ApiClient.describe(it) }
                asking = false
            }
        }
        answer?.let { a ->
            SelectionContainer { Text(a.text, fontSize = 13.sp, color = TextPrimaryLight) }
            if (a.sources.isNotEmpty()) Text("Sumber: " + a.sources.joinToString(", "), fontSize = 11.sp, color = TextMutedLight)
            Text("Jawaban AI adalah alat bantu; keputusan keselamatan dan teknis tetap pada pihak berwenang.", fontSize = 11.sp, color = TextMutedLight)
        }
    }
    if (info.can("kb.manage")) {
        SecondaryButton(if (adding) "Tutup" else "Tambah dokumen") { adding = !adding }
        if (adding) {
            FormCard {
                FormField("Judul dokumen", title, { title = it })
                FormField("Isi dokumen (teks atau markdown)", text, { text = it }, minLines = 6)
                SectionLabel("YANG BOLEH MEMBACA")
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("org" to "Semua", "division" to "Divisi saya", "managers" to "Manajer+", "admins" to "Admin").forEach { (v, l) -> Pill(l, visibility == v) { visibility = v } }
                }
                PrimaryButton("Simpan dan indeks", enabled = title.isNotBlank() && text.trim().length >= 10) {
                    scope.launch {
                        runCatching { OrgRepository.addDocument(title.trim(), text.trim(), visibility) }
                            .onSuccess { title = ""; text = ""; adding = false; docs.reload() }
                            .onFailure { error = ApiClient.describe(it) }
                    }
                }
            }
        }
    }
    SectionLabel("DOKUMEN")
    LoadingState(docs)
    docs.value?.let { list ->
        if (list.isEmpty()) Text("Belum ada dokumen.", fontSize = 13.sp, color = TextMutedLight)
        list.forEach { d ->
            Row(modifier = Modifier.fillMaxWidth().background(LightSurface, RoundedCornerShape(12.dp)).padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(d.title, fontSize = 13.sp, color = TextPrimaryLight)
                Text(d.visibility, fontSize = 11.sp, color = TextMutedLight)
            }
        }
    }
}

@Composable
private fun InsightTab(info: SessionRepository.Info) {
    val scope = rememberCoroutineScope()
    var verify by remember { mutableStateOf<String?>(null) }
    if (info.can("analytics.read")) {
        val overview = rememberLoader(Unit) { OrgRepository.overview() }
        SectionLabel("RINGKASAN 30 HARI")
        LoadingState(overview)
        overview.value?.let { rows ->
            FormCard { rows.forEach { (k, v) -> Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(k, fontSize = 13.sp, color = TextMutedLight); Text(v, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight) } } }
        }
    }
    SectionLabel("TRANSPARANSI DATA")
    val transparency = rememberLoader(Unit) { OrgRepository.transparency() }
    LoadingState(transparency)
    transparency.value?.let { lines -> FormCard { lines.forEach { Text(it, fontSize = if (it.startsWith("•")) 12.sp else 11.sp, fontWeight = if (it.startsWith("•")) FontWeight.Normal else FontWeight.Bold, color = if (it.startsWith("•")) TextPrimaryLight else TextMutedLight) } } }
    if (info.can("audit.read")) {
        SectionLabel("AUDIT LOG")
        val audit = rememberLoader(Unit) { OrgRepository.audit() }
        SecondaryButton("Verifikasi rantai hash audit") {
            scope.launch { verify = runCatching { OrgRepository.verifyAudit() }.getOrElse { ApiClient.describe(it) } }
        }
        verify?.let { Text(it, fontSize = 12.sp, color = TextPrimaryLight) }
        LoadingState(audit)
        audit.value?.let { lines -> FormCard { lines.forEach { Text(it, fontSize = 11.sp, color = TextPrimaryLight) } } }
    }
}

@Composable
private fun NotificationsTab() {
    val scope = rememberCoroutineScope()
    val list = rememberLoader(Unit) { OrgRepository.notifications() }
    SecondaryButton("Tandai semua sudah dibaca") { scope.launch { runCatching { OrgRepository.markAllRead() }; list.reload() } }
    LoadingState(list)
    list.value?.let { items ->
        if (items.isEmpty()) Text("Tidak ada notifikasi.", fontSize = 13.sp, color = TextMutedLight)
        items.forEach { n ->
            FormCard {
                Text((if (n.read) "" else "● ") + n.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                Text(n.body, fontSize = 12.sp, color = TextMutedLight)
            }
        }
    }
}

/** Shown in place of the workspace when nobody is signed in to an organization. */
@Composable
fun OrgGate(onBack: () -> Unit, onSignIn: () -> Unit, onOpenLocalBoard: () -> Unit) {
    val info = SessionRepository.info
    Column(
        modifier = Modifier.fillMaxSize().background(LightBgWarm).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FormHeader("Enterprise", onBack)
        FormCard {
            Text("SmartEyeX Enterprise", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            Text(
                if (info != null) "Anda sedang masuk sebagai akun Personal. Enterprise memakai akun organisasi yang terpisah; keluar dulu lalu masuk dengan akun organisasi."
                else "Masuk dengan akun organisasi atau kode undangan dari administrator Anda untuk memakai tugas tim, pengumuman, knowledge base, dan laporan.",
                fontSize = 12.sp, color = TextMutedLight,
            )
            PrimaryButton(if (info != null) "Buka Akun & Server" else "Masuk akun organisasi", onClick = onSignIn)
        }
        FormCard {
            Text("Papan tugas lokal", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
            Text("Papan sederhana yang tersimpan hanya di perangkat ini, tanpa akun dan tanpa sinkronisasi tim.", fontSize = 12.sp, color = TextMutedLight)
            SecondaryButton("Buka papan lokal", onOpenLocalBoard)
        }
    }
}
