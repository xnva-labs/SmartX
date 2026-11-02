package com.xnvalabs.smarteyex.data.glasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.ParcelUuid
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.xnvalabs.smarteyex.data.net.ApiClient
import com.xnvalabs.smarteyex.data.net.CloudGate
import com.xnvalabs.smarteyex.data.privacy.PrivacyRepository
import com.xnvalabs.smarteyex.data.security.SecureStore
import com.xnvalabs.smarteyex.data.session.SessionRepository
import com.xnvalabs.smarteyex.data.vision.VisionRepository
import com.xnvalabs.smarteyex.data.xnai.XnaiMessage
import com.xnvalabs.smarteyex.data.xnai.XnaiRepository
import com.xnvalabs.smarteyex.service.GlassesService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-side owner of the SmartEyeX glasses: scanning, pairing, the authenticated BLE session, and the voice-first
 * assistant that runs on top of it.
 *
 * Button gestures on the glasses (physical, so no screen needed):
 *  - hold: push-to-talk. Release sends the speech to the server (transcribe), asks XNAI, and speaks the answer
 *    through the bone-conduction speaker;
 *  - single press: take a photo and describe what is in view;
 *  - double press: take a photo and read the text in it (OCR).
 *
 * Privacy: every capture checks the Camera/Microphone toggles first, every cloud call goes through [CloudGate], and
 * the glasses' own LED lights before the camera sensor is powered. Photos and audio are not stored by the app or the server.
 */
@SuppressLint("MissingPermission")  // the UI requests BLUETOOTH_SCAN / BLUETOOTH_CONNECT before calling scan/connect
object GlassesRepository : GlassesClient.Listener {
    enum class State { IDLE, SCANNING, CONNECTING, AUTHENTICATING, READY }
    data class Found(val name: String, val address: String)
    private class SecretResult(val secret: ByteArray?, val error: String?)

    private const val PREFS = "smarteyex_glasses"
    private const val KEY_LAST = "last_address"
    private const val KEY_SECRET = "secret_"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private var initialized = false

    var state by mutableStateOf(State.IDLE)
        private set
    var status by mutableStateOf<GlassesStatus?>(null)
        private set
    var info by mutableStateOf<GlassesClient.DeviceInfo?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    val found = mutableStateListOf<Found>()
    val log = mutableStateListOf<String>()
    val lastAddress: String? get() = if (initialized) prefs.getString(KEY_LAST, null) else null

    private val devices = HashMap<String, BluetoothDevice>()
    private var scanner: BluetoothLeScanner? = null
    private var scanJob: Job? = null
    private var client: GlassesClient? = null
    private var serverDeviceId: String? = null
    private var lastTelemetry = 0L

    private val busy = AtomicBoolean(false)
    private var voiceBegin: Job? = null
    @Volatile private var voiceStarted = false
    private val history = ArrayList<XnaiMessage>()

    @Volatile private var captureWaiter: CompletableDeferred<ByteArray?>? = null
    @Volatile private var assembler: ImageAssembler? = null
    @Volatile private var captureError: String? = null
    private val micChunks = ArrayList<ShortArray>()
    @Volatile private var micStream = -1
    @Volatile private var micNextSeq = 0
    @Volatile private var micEnded: CompletableDeferred<Int>? = null
    private var streamCounter = 0

    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        initialized = true
    }

    fun reportPermissionDenied() {
        message = "Izin Bluetooth ditolak. Izinkan 'Perangkat sekitar' di pengaturan aplikasi."
    }

    // ------------------------------------------------------------------ scanning

    private fun adapter(): BluetoothAdapter? = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun startScan() {
        val a = adapter()
        if (a == null || !a.isEnabled) {
            message = "Bluetooth mati atau tidak tersedia."
            return
        }
        val s = a.bluetoothLeScanner
        if (s == null) {
            message = "Pemindai BLE tidak tersedia."
            return
        }
        stopScan()
        found.clear()
        devices.clear()
        state = State.SCANNING
        message = "Memindai kacamata SmartEyeX…"
        scanner = s
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(ProtocolIds.SERVICE_UUID)).build()
        s.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
        scanJob = scope.launch {
            delay(12_000L)
            stopScan()
            if (found.isEmpty()) message = "Kacamata tidak ditemukan. Pastikan menyala dan berada dekat."
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        try { scanner?.stopScan(scanCallback) } catch (e: Exception) { /* adapter turned off */ }
        scanner = null
        if (state == State.SCANNING) state = State.IDLE
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            if (devices.put(d.address, d) == null) found.add(Found(d.name ?: result.scanRecord?.deviceName ?: "SmartEyeX", d.address))
        }

        override fun onScanFailed(errorCode: Int) {
            message = "Pemindaian gagal (kode $errorCode)."
            state = State.IDLE
        }
    }

    // ------------------------------------------------------------------ connect / pair

    fun connect(address: String) {
        if (state == State.CONNECTING || state == State.AUTHENTICATING || state == State.READY) return
        scope.launch { doConnect(address) }
    }

    fun reconnectLast() {
        val last = lastAddress
        if (last == null) message = "Belum ada kacamata yang pernah tersambung." else connect(last)
    }

    fun disconnect() {
        client?.disconnect()
    }

    private suspend fun doConnect(address: String) {
        val device = devices[address] ?: adapter()?.getRemoteDevice(address)
        if (device == null) {
            fail("Perangkat tidak ditemukan. Pindai ulang.")
            return
        }
        stopScan()
        state = State.CONNECTING
        message = "Menghubungkan… Jika diminta, setujui pairing Bluetooth."
        val c = GlassesClient(appContext, this)
        client = c
        val devInfo = c.connect(device)
        if (devInfo == null) {
            client = null
            fail("Gagal terhubung. Jika kacamata pernah dipasangkan, hapus 'SmartEyeX-…' di Pengaturan Bluetooth lalu coba lagi.")
            return
        }
        info = devInfo
        state = State.AUTHENTICATING
        message = "Mengautentikasi…"
        val sr = obtainSecret(c, devInfo)
        val secret = sr.secret
        if (secret == null) {
            c.disconnect()
            fail(sr.error ?: "Gagal mendapatkan secret kacamata.")
            return
        }
        val err = c.authenticate(secret)
        if (err != null) {
            c.disconnect()
            fail(err)
            return
        }
        prefs.edit().putString(KEY_LAST, address).apply()
        state = State.READY
        message = null
        startService()
        note("Terhubung ke kacamata ${devInfo.hwId} (firmware ${devInfo.firmware}).")
        tone(880, 120)
    }

    private fun fail(msg: String) {
        state = State.IDLE
        message = msg
        stopService()
    }

    private suspend fun obtainSecret(c: GlassesClient, d: GlassesClient.DeviceInfo): SecretResult {
        val pairing = status?.has(ProtocolIds.Flag.PAIRING_MODE) == true
        val signedIn = SessionRepository.info != null
        return try {
            if (pairing) {
                message = "Mode pairing terdeteksi — memasangkan kacamata…"
                val fresh = if (signedIn) registerOnServer(d) else ByteArray(ProtocolIds.K.SECRET_LEN).also { SecureRandom().nextBytes(it) }
                val err = c.provision(fresh)
                if (err != null) return SecretResult(null, err)
                saveSecret(d.hwId, fresh)
                SecretResult(fresh, null)
            } else {
                loadSecret(d.hwId)?.let { return SecretResult(it, null) }
                if (signedIn) {
                    fetchSecretFromServer(d)?.let {
                        saveSecret(d.hwId, it)
                        return SecretResult(it, null)
                    }
                }
                SecretResult(null, "Kacamata ini belum dipasangkan dengan ponsel atau akun ini. Tahan tombol sambil menyalakannya sekitar 3 detik untuk membuka mode pairing, lalu sambungkan lagi.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SecretResult(null, ApiClient.describe(e))
        }
    }

    private fun decodeSecret(b64: String): ByteArray = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private suspend fun findOnServer(hwId: String): JSONObject? {
        val items = ApiClient.call("GET", "/v1/devices").getJSONArray("items")
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            if (o.getString("hw_id") == hwId && !o.getBoolean("revoked")) return o
        }
        return null
    }

    /** Registers the glasses on the account (or rotates the secret of an existing registration) and returns the fresh secret. */
    private suspend fun registerOnServer(d: GlassesClient.DeviceInfo): ByteArray {
        val existing = findOnServer(d.hwId)
        val secret: String
        if (existing != null) {
            serverDeviceId = existing.getString("id")
            secret = ApiClient.call("POST", "/v1/devices/$serverDeviceId/rotate-secret").getString("secret")
        } else {
            val body = JSONObject().put("hw_id", d.hwId).put("label", "Kacamata " + d.hwId.takeLast(4)).put("fw_version", d.firmware)
            val r = ApiClient.call("POST", "/v1/devices", body)
            serverDeviceId = r.getString("id")
            secret = r.getString("secret")
        }
        return decodeSecret(secret)
    }

    private suspend fun fetchSecretFromServer(d: GlassesClient.DeviceInfo): ByteArray? {
        val existing = findOnServer(d.hwId) ?: return null
        serverDeviceId = existing.getString("id")
        return decodeSecret(ApiClient.call("GET", "/v1/devices/$serverDeviceId/secret").getString("secret"))
    }

    private fun saveSecret(hwId: String, secret: ByteArray) {
        prefs.edit().putString(KEY_SECRET + hwId, SecureStore.encrypt(Base64.encodeToString(secret, Base64.NO_WRAP), "glasses:$hwId")).apply()
    }

    private fun loadSecret(hwId: String): ByteArray? {
        val blob = prefs.getString(KEY_SECRET + hwId, null) ?: return null
        val b64 = SecureStore.decrypt(blob, "glasses:$hwId") ?: return null
        return Base64.decode(b64, Base64.NO_WRAP)
    }

    /** Wipes the secret on the glasses, here, and on the server. The Bluetooth bond stays until removed in system settings. */
    fun resetGlasses() {
        val c = client ?: return
        val hw = info?.hwId ?: return
        scope.launch {
            val err = c.forget()
            if (err != null) {
                note(err)
                return@launch
            }
            prefs.edit().remove(KEY_SECRET + hw).apply()
            serverDeviceId?.let { id -> runCatching { ApiClient.call("DELETE", "/v1/devices/$id") } }
            serverDeviceId = null
            note("Kacamata direset. Untuk memasangkan lagi, buka mode pairing dengan menahan tombol saat menyalakannya.")
            c.disconnect()
        }
    }

    // ------------------------------------------------------------------ GlassesClient.Listener

    override fun onDisconnected() {
        val wasReady = state == State.READY
        client = null
        status = null
        state = State.IDLE
        if (wasReady) message = "Terputus dari kacamata."
        stopService()
        captureError = "Kacamata terputus"
        captureWaiter?.complete(null)
        micEnded?.complete(0)
        voiceStarted = false
        busy.set(false)
    }

    override fun onStatus(status: GlassesStatus) {
        this.status = status
        val id = serverDeviceId
        val now = System.currentTimeMillis()
        if (id != null && now - lastTelemetry > 300_000L) {
            lastTelemetry = now
            scope.launch {
                runCatching {
                    val b = JSONObject()
                    if (status.batteryKnown && status.battery <= 100) b.put("battery_pct", status.battery)
                    info?.firmware?.takeIf { it.isNotBlank() }?.let { b.put("fw_version", it) }
                    ApiClient.call("POST", "/v1/devices/$id/telemetry", b)
                }
            }
        }
    }

    override fun onEvent(event: GlassesEvent) {
        val p = event.payload
        val first = if (p.isNotEmpty()) p[0].toInt() and 0xFF else -1
        when (event.type) {
            ProtocolIds.Evt.BUTTON -> onButton(first)
            ProtocolIds.Evt.CAPTURE_READY -> assembler = GlassesProtocol.parseCaptureReady(p)?.let { ImageAssembler(it) }
            ProtocolIds.Evt.CAPTURE_FAILED -> {
                captureError = when (first) {
                    ProtocolIds.Capfail.PRIVACY -> "Penutup privasi kacamata tertutup."
                    ProtocolIds.Capfail.BUSY -> "Kamera kacamata sedang sibuk."
                    ProtocolIds.Capfail.CAMERA_INIT -> "Kamera kacamata gagal dinyalakan."
                    else -> "Kamera kacamata tidak menghasilkan gambar."
                }
                captureWaiter?.complete(null)
            }
            ProtocolIds.Evt.MIC_STARTED -> micStream = first
            ProtocolIds.Evt.MIC_ENDED -> micEnded?.complete(if (p.size > 1) p[1].toInt() and 0xFF else 0)
            ProtocolIds.Evt.LOW_BATTERY -> note("Baterai kacamata rendah ($first%).")
            ProtocolIds.Evt.PRIVACY -> note(if (first == 1) "Penutup privasi kacamata TERTUTUP: kamera dan mikrofon diblokir." else "Penutup privasi kacamata dibuka.")
        }
    }

    override fun onImageFrame(frame: ByteArray) {
        val a = assembler ?: return
        when (a.add(frame)) {
            ImageAssembler.State.DONE -> {
                assembler = null
                captureWaiter?.complete(a.bytes())
            }
            ImageAssembler.State.BAD -> {
                assembler = null
                captureError = "Transfer gambar dari kacamata rusak."
                captureWaiter?.complete(null)
            }
            ImageAssembler.State.MORE -> Unit
        }
    }

    override fun onAudioFrame(frame: ByteArray) {
        val f = GlassesProtocol.parseAudioFrame(frame) ?: return
        if (micStream >= 0 && f.stream != micStream) return
        val missing = ((f.seq - micNextSeq) and 0xFFFF).coerceAtMost(5)
        val pcm = GlassesProtocol.adpcmDecode(f.state, f.data)
        synchronized(micChunks) {
            repeat(missing) { micChunks.add(ShortArray(ProtocolIds.K.AUDIO_FRAME_SAMPLES)) }  // conceal lost frames with silence
            micChunks.add(pcm)
        }
        micNextSeq = (f.seq + 1) and 0xFFFF
    }

    // ------------------------------------------------------------------ gestures

    private fun onButton(kind: Int) {
        when (kind) {
            ProtocolIds.Btn.SHORT -> runExclusive { describeScene("describe") }
            ProtocolIds.Btn.DOUBLE -> runExclusive { describeScene("read") }
            ProtocolIds.Btn.HOLD_START -> {
                if (!busy.compareAndSet(false, true)) return
                voiceStarted = false
                voiceBegin = scope.launch {
                    voiceStarted = startListening()
                    if (voiceStarted) tone(1200, 70) else busy.set(false)
                }
            }
            ProtocolIds.Btn.HOLD_END -> scope.launch {
                voiceBegin?.join()
                if (voiceStarted) {
                    voiceStarted = false
                    try { processVoice() } finally { busy.set(false) }
                }
            }
        }
    }

    private fun runExclusive(block: suspend () -> Unit) {
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try { block() } finally { busy.set(false) }
        }
    }

    /** Manual tests from the Device screen. */
    fun testTone() { scope.launch { tone(660, 250) } }
    fun testDescribe() = runExclusive { describeScene("describe") }
    fun testRead() = runExclusive { describeScene("read") }

    // ------------------------------------------------------------------ capture / listen / speak

    private suspend fun capturePhoto(resolution: Int, quality: Int): ByteArray? {
        val c = client ?: return null
        if (!PrivacyRepository.settings.value.cameraEnabled) {
            note("Camera OFF — aktifkan di Privacy Control.")
            return null
        }
        val waiter = CompletableDeferred<ByteArray?>()
        captureWaiter = waiter
        assembler = null
        captureError = null
        val st = c.command(ProtocolIds.Cmd.CAPTURE, byteArrayOf(resolution.toByte(), quality.toByte()))
        if (st != ProtocolIds.St.OK) {
            captureWaiter = null
            note(statusMessage(st))
            return null
        }
        val bytes = withTimeoutOrNull(25_000L) { waiter.await() }
        captureWaiter = null
        assembler = null
        if (bytes == null) note(captureError ?: "Foto dari kacamata tidak lengkap.")
        return bytes
    }

    private suspend fun describeScene(mode: String) {
        if (!PrivacyRepository.settings.value.cameraEnabled) {
            note("Camera OFF — aktifkan di Privacy Control.")
            errorTone()
            return
        }
        CloudGate.check()?.let { note(it); errorTone(); return }
        val read = mode == "read"
        val jpeg = capturePhoto(if (read) ProtocolIds.Res.SVGA else ProtocolIds.Res.VGA, if (read) 12 else 15)
        if (jpeg == null) {
            errorTone()
            return
        }
        note("Menganalisis gambar…")
        val text = VisionRepository.analyzeFrame(jpeg, mode).getOrElse {
            note(it.message ?: "Analisis gambar gagal.")
            errorTone()
            return
        }
        note((if (read) "Teks: " else "Pemandangan: ") + text)
        speakText(text)
    }

    private suspend fun startListening(): Boolean {
        val c = client ?: return false
        if (!PrivacyRepository.settings.value.microphoneEnabled) {
            note("Microphone OFF — aktifkan di Privacy Control.")
            errorTone()
            return false
        }
        val gate = CloudGate.check()
        if (gate != null) {
            note(gate)
            errorTone()
            return false
        }
        synchronized(micChunks) { micChunks.clear() }
        micStream = -1
        micNextSeq = 0
        micEnded = CompletableDeferred()
        val st = c.command(ProtocolIds.Cmd.MIC_START, GlassesProtocol.u16(20_000))
        if (st != ProtocolIds.St.OK) {
            note(statusMessage(st))
            errorTone()
            return false
        }
        return true
    }

    private suspend fun stopListening(): ShortArray? {
        val c = client ?: return null
        c.command(ProtocolIds.Cmd.MIC_STOP)
        withTimeoutOrNull(3_000L) { micEnded?.await() }
        val chunks = synchronized(micChunks) { micChunks.toList().also { micChunks.clear() } }
        val total = chunks.sumOf { it.size }
        if (total == 0) return null
        val out = ShortArray(total)
        var o = 0
        for (ch in chunks) {
            System.arraycopy(ch, 0, out, o, ch.size)
            o += ch.size
        }
        return out
    }

    private suspend fun processVoice() {
        val pcm = stopListening()
        if (pcm == null || pcm.size < ProtocolIds.K.AUDIO_RATE_HZ / 4) {
            note("Suara terlalu pendek; tahan tombol sambil berbicara.")
            errorTone()
            return
        }
        note("Memproses suara…")
        val text = transcribe(pcm) ?: return
        note("Anda: $text")
        val reply = XnaiRepository.sendMessage(text, history.toList(), "RELAX").getOrElse {
            note(it.message ?: "XNAI gagal menjawab.")
            errorTone()
            return
        }
        history.add(XnaiMessage("user", text))
        history.add(XnaiMessage("assistant", reply))
        while (history.size > 12) history.removeAt(0)
        note("XNAI: $reply")
        speakText(reply)
    }

    private suspend fun transcribe(pcm: ShortArray): String? {
        val gate = CloudGate.check()
        if (gate != null) {
            note(gate)
            errorTone()
            return null
        }
        return try {
            val wav = GlassesProtocol.pcmToWav(pcm)
            val body = JSONObject().put("audioBase64", Base64.encodeToString(wav, Base64.NO_WRAP)).put("language", "id")
            val text = ApiClient.call("POST", "/v1/xnai/transcribe", body).getString("text").trim()
            if (text.isEmpty()) {
                note("Tidak ada ucapan yang terdeteksi.")
                errorTone()
                null
            } else {
                text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            note(ApiClient.describe(e))
            errorTone()
            null
        }
    }

    private suspend fun speakText(text: String) {
        val pcm = withContext(Dispatchers.Main) { PhoneTts.synthesize(appContext, text) }
        if (pcm == null || pcm.isEmpty()) {
            note("Mesin suara (TTS) di ponsel tidak tersedia; jawaban hanya ditampilkan di layar.")
            return
        }
        speak(pcm)
    }

    private suspend fun speak(pcm: ShortArray): Boolean {
        val c = client ?: return false
        val stream = (streamCounter++ and 0x7F) + 1
        val st = c.command(ProtocolIds.Cmd.SPEAK_BEGIN, byteArrayOf(stream.toByte()) + GlassesProtocol.u16(ProtocolIds.K.AUDIO_RATE_HZ))
        if (st != ProtocolIds.St.OK) {
            note(statusMessage(st))
            return false
        }
        val n = ProtocolIds.K.AUDIO_FRAME_SAMPLES
        val frames = (pcm.size + n - 1) / n
        val adpcm = AdpcmState()
        val startNs = System.nanoTime()
        for (i in 0 until frames) {
            val buf = ShortArray(n)
            System.arraycopy(pcm, i * n, buf, 0, minOf(n, pcm.size - i * n))
            val before = adpcm.copy()
            val frame = GlassesProtocol.buildAudioFrame(stream, i, before, GlassesProtocol.adpcmEncode(adpcm, buf, 0, n))
            if (!c.writeAudio(frame)) {
                c.command(ProtocolIds.Cmd.SPEAK_ABORT)
                return false
            }
            if (i >= PREROLL_FRAMES) {  // first 160 ms go out at once to fill the jitter buffer, the rest in real time
                val dueNs = startNs + (i - PREROLL_FRAMES + 1) * 20_000_000L
                val waitMs = (dueNs - System.nanoTime()) / 1_000_000L
                if (waitMs > 0) delay(waitMs)
            }
        }
        c.command(ProtocolIds.Cmd.SPEAK_END, byteArrayOf(stream.toByte()))
        return true
    }

    private const val PREROLL_FRAMES = 8

    private suspend fun tone(freq: Int, ms: Int, volume: Int = 60) {
        client?.command(ProtocolIds.Cmd.TONE, GlassesProtocol.u16(freq) + GlassesProtocol.u16(ms) + byteArrayOf(volume.toByte()), 3_000L)
    }

    private suspend fun errorTone() = tone(300, 300)

    private fun statusMessage(st: Int): String = when (st) {
        ProtocolIds.St.BUSY -> "Kacamata sedang sibuk."
        ProtocolIds.St.NOT_AUTHENTICATED -> "Kacamata belum terautentikasi."
        ProtocolIds.St.UNSUPPORTED -> "Perangkat keras kacamata tidak mendukung fitur ini."
        ProtocolIds.St.PRIVACY_BLOCKED -> "Penutup privasi kacamata tertutup."
        ProtocolIds.St.HW_FAILURE -> "Perangkat keras kacamata gagal."
        ProtocolIds.St.LOW_BATTERY -> "Baterai kacamata terlalu rendah."
        ProtocolIds.St.MTU_TOO_SMALL -> "Ukuran paket Bluetooth terlalu kecil."
        -2 -> "Kacamata tidak merespons."
        -3 -> "Kacamata terputus."
        else -> "Perintah ke kacamata gagal (kode $st)."
    }

    private fun note(msg: String) {
        message = msg
        log.add(0, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + msg)
        while (log.size > 30) log.removeAt(log.size - 1)
    }

    private fun startService() {
        runCatching { ContextCompat.startForegroundService(appContext, Intent(appContext, GlassesService::class.java)) }
    }

    private fun stopService() {
        runCatching { appContext.stopService(Intent(appContext, GlassesService::class.java)) }
    }
}
