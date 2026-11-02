package com.xnvalabs.smarteyex.ui.screens.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnvalabs.smarteyex.data.net.ApiClient
import com.xnvalabs.smarteyex.data.session.SessionRepository
import com.xnvalabs.smarteyex.ui.components.ErrorNote
import com.xnvalabs.smarteyex.ui.components.FormCard
import com.xnvalabs.smarteyex.ui.components.FormField
import com.xnvalabs.smarteyex.ui.components.FormHeader
import com.xnvalabs.smarteyex.ui.components.PrimaryButton
import com.xnvalabs.smarteyex.ui.components.SecondaryButton
import com.xnvalabs.smarteyex.ui.theme.LightBgWarm
import com.xnvalabs.smarteyex.ui.theme.TextMutedLight
import com.xnvalabs.smarteyex.ui.theme.TextPrimaryLight
import kotlinx.coroutines.launch

private enum class Step { CHOOSE, PERSONAL_LOGIN, PERSONAL_REGISTER, ENT_LOGIN, ENT_INVITE }

/**
 * First-run environment choice (Personal or Enterprise) and sign-in. Personal works without any account
 * ("Lanjut tanpa akun"): only XNAI, vision, translation and Enterprise need the server.
 */
@Composable
fun WelcomeScreen(onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    var step by remember { mutableStateOf(Step.CHOOSE) }
    Column(
        modifier = Modifier.fillMaxSize().background(LightBgWarm).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (step) {
            Step.CHOOSE -> ChooseStep(onPick = { step = it }, onSkip = { SessionRepository.markWelcomeSeen(); onDone() }, onBack = onBack)
            Step.PERSONAL_LOGIN -> PersonalForm(register = false, onBack = { step = Step.CHOOSE }, onDone = onDone)
            Step.PERSONAL_REGISTER -> PersonalForm(register = true, onBack = { step = Step.CHOOSE }, onDone = onDone)
            Step.ENT_LOGIN -> EnterpriseForm(invite = false, onBack = { step = Step.CHOOSE }, onDone = onDone)
            Step.ENT_INVITE -> EnterpriseForm(invite = true, onBack = { step = Step.CHOOSE }, onDone = onDone)
        }
    }
}

@Composable
private fun ChooseStep(onPick: (Step) -> Unit, onSkip: () -> Unit, onBack: (() -> Unit)?) {
    FormHeader("SmartEyeX", onBack)
    Text("Pilih lingkungan Anda. Keduanya terpisah: data, identitas, dan izin tidak pernah bercampur.", fontSize = 13.sp, color = TextMutedLight)
    FormCard {
        Text("Personal", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
        Text("Pendamping AI untuk kehidupan sehari-hari. Memori dan pengaturan tersimpan di ponsel; akun hanya dibutuhkan untuk XNAI, penglihatan, dan terjemahan.", fontSize = 12.sp, color = TextMutedLight)
        PrimaryButton("Masuk") { onPick(Step.PERSONAL_LOGIN) }
        SecondaryButton("Daftar akun Personal") { onPick(Step.PERSONAL_REGISTER) }
        SecondaryButton("Lanjut tanpa akun", onSkip)
    }
    FormCard {
        Text("Enterprise", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
        Text("Asisten operasional untuk organisasi: tugas tim, pengumuman, knowledge base, dan kebijakan akses berbasis peran. Akun dibuat oleh administrator organisasi Anda.", fontSize = 12.sp, color = TextMutedLight)
        PrimaryButton("Masuk akun organisasi") { onPick(Step.ENT_LOGIN) }
        SecondaryButton("Saya punya kode undangan") { onPick(Step.ENT_INVITE) }
    }
    ServerCard()
}

@Composable
private fun ServerCard() {
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(SessionRepository.serverUrl) }
    var error by remember { mutableStateOf<String?>(null) }
    FormCard {
        Text("Server", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMutedLight)
        if (!editing) {
            Text(SessionRepository.serverUrl.ifBlank { "Belum diatur" }, fontSize = 13.sp, color = TextPrimaryLight)
            SecondaryButton(if (SessionRepository.serverUrl.isBlank()) "Atur alamat server" else "Ubah alamat server") { editing = true }
        } else {
            FormField("Alamat server (https://…)", text, { text = it }, keyboard = KeyboardType.Uri)
            ErrorNote(error)
            PrimaryButton("Simpan") {
                if (SessionRepository.setServerUrl(text)) {
                    error = null
                    editing = false
                } else {
                    error = if (SessionRepository.info != null) "Keluar dulu sebelum mengganti server." else "Alamat harus berawalan https:// dan berupa URL yang valid."
                }
            }
            SecondaryButton("Batal") { editing = false; error = null }
        }
    }
}

@Composable
private fun PersonalForm(register: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    FormHeader(if (register) "Daftar akun Personal" else "Masuk akun Personal", onBack)
    FormCard {
        if (register) FormField("Nama", name, { name = it })
        FormField("Email", email, { email = it }, keyboard = KeyboardType.Email)
        FormField(if (register) "Password (minimal 10 karakter)" else "Password", password, { password = it }, password = true)
        ErrorNote(error)
        PrimaryButton(if (busy) "Memproses…" else if (register) "Daftar" else "Masuk", enabled = !busy && email.isNotBlank() && password.isNotEmpty()) {
            if (SessionRepository.baseUrl() == null) {
                error = "Atur alamat server dulu di halaman sebelumnya."
                return@PrimaryButton
            }
            busy = true
            error = null
            scope.launch {
                val r = if (register) SessionRepository.registerPersonal(email, password, name) else SessionRepository.loginPersonal(email, password)
                busy = false
                r.onSuccess { onDone() }.onFailure { error = ApiClient.describe(it) }
            }
        }
    }
    if (register) Text("Dengan mendaftar Anda menyetujui Ketentuan Layanan dan Kebijakan Privasi (docs/TERMS.md dan docs/PRIVACY_POLICY.md di repositori proyek).", fontSize = 11.sp, color = TextMutedLight)
}

@Composable
private fun EnterpriseForm(invite: Boolean, onBack: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var org by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    FormHeader(if (invite) "Terima undangan" else "Masuk akun organisasi", onBack)
    FormCard {
        if (invite) {
            FormField("Kode undangan", token, { token = it })
            FormField("Nama Anda", name, { name = it })
        } else {
            FormField("Kode organisasi (slug)", org, { org = it })
            FormField("Email kerja", email, { email = it }, keyboard = KeyboardType.Email)
        }
        FormField(if (invite) "Buat password (minimal 10 karakter)" else "Password", password, { password = it }, password = true)
        ErrorNote(error)
        val ready = if (invite) token.isNotBlank() && password.isNotEmpty() else org.isNotBlank() && email.isNotBlank() && password.isNotEmpty()
        PrimaryButton(if (busy) "Memproses…" else if (invite) "Aktifkan akun" else "Masuk", enabled = !busy && ready) {
            if (SessionRepository.baseUrl() == null) {
                error = "Atur alamat server dulu di halaman sebelumnya."
                return@PrimaryButton
            }
            busy = true
            error = null
            scope.launch {
                val r = if (invite) SessionRepository.acceptInvite(token, password, name) else SessionRepository.loginEnterprise(org, email, password)
                busy = false
                r.onSuccess { onDone() }.onFailure { error = ApiClient.describe(it) }
            }
        }
    }
}

/** Account details, sign out, and (Personal) permanent deletion of the server-side account. */
@Composable
fun AccountScreen(onBack: () -> Unit, onOpenWelcome: () -> Unit) {
    val scope = rememberCoroutineScope()
    val info = SessionRepository.info
    var confirmDelete by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().background(LightBgWarm).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        FormHeader("Akun & Server", onBack)
        FormCard {
            Text("Server", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextMutedLight)
            Text(SessionRepository.serverUrl.ifBlank { "Belum diatur" }, fontSize = 13.sp, color = TextPrimaryLight)
            if (info == null) {
                Text("Belum masuk. Fitur lokal tetap berfungsi; XNAI, penglihatan, terjemahan, dan Enterprise memerlukan akun.", fontSize = 12.sp, color = TextMutedLight)
                PrimaryButton("Masuk / Daftar", onClick = onOpenWelcome)
            } else {
                Text(if (info.realm == SessionRepository.Realm.ENTERPRISE) "Enterprise · ${info.orgName ?: info.orgSlug}" else "Personal", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                Text(info.name.ifBlank { info.email } + "  ·  " + info.email, fontSize = 12.sp, color = TextMutedLight)
                if (info.role != null) Text("Peran: ${info.role}", fontSize = 12.sp, color = TextMutedLight)
                SecondaryButton("Keluar") { scope.launch { SessionRepository.logout() } }
            }
        }
        if (info?.realm == SessionRepository.Realm.PERSONAL) {
            FormCard {
                Text("Hapus akun", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimaryLight)
                Text("Menghapus akun dan seluruh data Anda di server secara permanen (kunci enkripsinya ikut dimusnahkan). Data lokal di ponsel tidak ikut terhapus; gunakan 'Hapus semua memori' di Privacy Control untuk itu.", fontSize = 12.sp, color = TextMutedLight)
                if (!confirmDelete) {
                    SecondaryButton("Hapus akun saya…") { confirmDelete = true }
                } else {
                    FormField("Masukkan password untuk konfirmasi", password, { password = it }, password = true)
                    ErrorNote(error)
                    PrimaryButton(if (busy) "Menghapus…" else "Hapus permanen", enabled = !busy && password.isNotEmpty()) {
                        busy = true
                        error = null
                        scope.launch {
                            val r = SessionRepository.deletePersonalAccount(password)
                            busy = false
                            r.onSuccess { confirmDelete = false; password = "" }.onFailure { error = ApiClient.describe(it) }
                        }
                    }
                    SecondaryButton("Batal") { confirmDelete = false; password = ""; error = null }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
