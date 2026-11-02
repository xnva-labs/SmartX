package com.xnvalabs.smarteyex.data.glasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One BLE connection to a pair of SmartEyeX glasses (docs/BLE_PROTOCOL.md). Handles bonding, MTU, service discovery,
 * notifications, a strictly serialized GATT operation queue (Android allows one outstanding operation), the mutual
 * HMAC authentication and request/ack commands. Pure plumbing: no privacy or AI decisions live here.
 */
@SuppressLint("MissingPermission")  // callers verify BLUETOOTH_SCAN/CONNECT before using this class
class GlassesClient(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onDisconnected()
        fun onStatus(status: GlassesStatus)
        fun onEvent(event: GlassesEvent)
        fun onImageFrame(frame: ByteArray)
        fun onAudioFrame(frame: ByteArray)
    }

    class DeviceInfo(val hwId: String, val firmware: String, val hardware: String, val caps: List<String>)

    private var gatt: BluetoothGatt? = null
    @Volatile private var mtu = 23
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var lastRead: ByteArray? = null
    @Volatile private var connectedSignal: CompletableDeferred<Boolean>? = null
    @Volatile private var mtuSignal: CompletableDeferred<Int>? = null
    @Volatile private var servicesSignal: CompletableDeferred<Boolean>? = null
    private val authInbox = Channel<ByteArray>(Channel.UNLIMITED)
    private val acks = ConcurrentHashMap<Int, CompletableDeferred<Int>>()
    private val seq = AtomicInteger(0)
    private val chars = HashMap<String, BluetoothGattCharacteristic>()

    var deviceId: ByteArray = ByteArray(0)
        private set
    @Volatile var authenticated: Boolean = false
        private set
    val negotiatedMtu: Int get() = mtu

    // ------------------------------------------------------------------ GATT callbacks

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedSignal?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedSignal?.complete(false)
                pending?.complete(-3)
                acks.values.forEach { it.complete(-3) }
                authenticated = false
                try { g.close() } catch (e: Exception) { /* already closed */ }
                val wasCurrent = gatt === g
                if (wasCurrent) gatt = null
                if (wasCurrent) listener.onDisconnected()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuSignal?.complete(mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesSignal?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            lastRead = c.value
            pending?.complete(status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            lastRead = value
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        @Suppress("DEPRECATION")
        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            route(c.uuid, v)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            route(c.uuid, value)
        }
    }

    private fun route(uuid: UUID, v: ByteArray) {
        when (uuid.toString().lowercase()) {
            ProtocolIds.UUID_STATUS -> GlassesProtocol.parseStatus(v)?.let { listener.onStatus(it) }
            ProtocolIds.UUID_AUTH -> if (v.isNotEmpty()) authInbox.trySend(v)
            ProtocolIds.UUID_EVENT -> GlassesProtocol.parseEvent(v)?.let { handleEvent(it) }
            ProtocolIds.UUID_IMAGE -> listener.onImageFrame(v)
            ProtocolIds.UUID_AUDIO_IN -> listener.onAudioFrame(v)
        }
    }

    private fun handleEvent(e: GlassesEvent) {
        if (e.type == ProtocolIds.Evt.CMD_ACK && e.payload.size == 3) {
            acks.remove(e.payload[1].toInt() and 0xFF)?.complete(e.payload[2].toInt() and 0xFF)
        } else {
            listener.onEvent(e)
        }
    }

    // ------------------------------------------------------------------ operation queue

    /** Runs one GATT operation and waits for its callback. Returns the GATT status, -1 if it could not start, -2 on timeout, -3 if disconnected. */
    private suspend fun runOp(start: () -> Boolean): Int = opLock.withLock {
        val d = CompletableDeferred<Int>()
        pending = d
        if (!start()) {
            pending = null
            return@withLock -1
        }
        val r = withTimeoutOrNull(10_000L) { d.await() }
        pending = null
        r ?: -2
    }

    @Suppress("DEPRECATION")
    private fun writeChar(c: BluetoothGattCharacteristic, value: ByteArray, type: Int): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(d)
        }

    private suspend fun readChar(id: String): ByteArray? {
        val c = chars[id] ?: return null
        val st = runOp { gatt?.readCharacteristic(c) == true }
        return if (st == BluetoothGatt.GATT_SUCCESS) lastRead else null
    }

    private suspend fun writeAwait(id: String, value: ByteArray, type: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT): Int {
        val c = chars[id] ?: return -1
        return runOp { writeChar(c, value, type) }
    }

    private suspend fun enableNotify(id: String): Boolean {
        val c = chars[id] ?: return false
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(CCCD) ?: return false
        return runOp { writeDescriptor(g, cccd) } == BluetoothGatt.GATT_SUCCESS
    }

    // ------------------------------------------------------------------ connect

    /** Bonds (system pairing dialog on first use), connects, negotiates MTU, discovers services, subscribes, reads device info. Null on failure. */
    suspend fun connect(device: BluetoothDevice): DeviceInfo? {
        if (!ensureBonded(device)) return null
        connectedSignal = CompletableDeferred()
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: return null
        gatt = g
        if (withTimeoutOrNull(20_000L) { connectedSignal?.await() } != true) {
            close()
            return null
        }
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        mtuSignal = CompletableDeferred()
        if (g.requestMtu(ProtocolIds.K.MAX_MTU)) withTimeoutOrNull(6_000L) { mtuSignal?.await() }
        servicesSignal = CompletableDeferred()
        if (!g.discoverServices() || withTimeoutOrNull(10_000L) { servicesSignal?.await() } != true) {
            close()
            return null
        }
        val service = g.getService(UUID.fromString(ProtocolIds.SERVICE_UUID))
        if (service == null) {
            close()
            return null
        }
        chars.clear()
        for (id in ALL_CHARS) service.getCharacteristic(UUID.fromString(id))?.let { chars[id] = it }
        if (chars.size != ALL_CHARS.size) {
            close()
            return null
        }
        for (id in NOTIFY_CHARS) {
            if (!enableNotify(id)) {
                close()
                return null
            }
        }
        val info = readChar(ProtocolIds.UUID_DEVICE_INFO)?.let { parseInfo(it) }
        if (info == null) {
            close()
            return null
        }
        deviceId = GlassesProtocol.hexToBytes(info.hwId)
        readChar(ProtocolIds.UUID_STATUS)?.let { GlassesProtocol.parseStatus(it) }?.let { listener.onStatus(it) }
        return info
    }

    private fun parseInfo(b: ByteArray): DeviceInfo? = try {
        val o = JSONObject(String(b, Charsets.UTF_8))
        val id = o.getString("id")
        if (o.getInt("proto") != ProtocolIds.K.PROTO_VERSION || id.length != 12) {
            null
        } else {
            val caps = o.optJSONArray("caps")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            DeviceInfo(id.uppercase(), o.optString("fw"), o.optString("hw"), caps)
        }
    } catch (e: Exception) {
        null
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private suspend fun ensureBonded(device: BluetoothDevice): Boolean {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return true
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.bluetoothDevice()?.address != device.address) return
                when (i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> done.complete(true)
                    BluetoothDevice.BOND_NONE -> done.complete(false)
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        try {
            if (!device.createBond()) return false
            return withTimeoutOrNull(60_000L) { done.await() } == true
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    fun disconnect() {
        authenticated = false
        gatt?.disconnect()
    }

    private fun close() {
        authenticated = false
        try { gatt?.disconnect() } catch (e: Exception) { /* ignore */ }
        try { gatt?.close() } catch (e: Exception) { /* ignore */ }
        gatt = null
    }

    // ------------------------------------------------------------------ authentication

    /** Mutual HMAC authentication with the per-device secret. Returns null on success or an Indonesian error message. */
    suspend fun authenticate(secret: ByteArray): String? {
        val nonceC = ByteArray(ProtocolIds.K.NONCE_LEN).also { SecureRandom().nextBytes(it) }
        while (authInbox.tryReceive().isSuccess) { /* drop stale replies */ }
        if (writeAwait(ProtocolIds.UUID_AUTH, GlassesProtocol.hello(nonceC, mtu)) != BluetoothGatt.GATT_SUCCESS) return "Gagal mengirim permintaan autentikasi."
        val challenge = withTimeoutOrNull(5_000L) { authInbox.receive() } ?: return "Kacamata tidak merespons autentikasi."
        if ((challenge[0].toInt() and 0xFF) == ProtocolIds.Auth.AUTH_FAIL) return authFailMessage(challenge)
        val expectLen = 1 + ProtocolIds.K.NONCE_LEN + ProtocolIds.K.MAC_LEN
        if ((challenge[0].toInt() and 0xFF) != ProtocolIds.Auth.CHALLENGE || challenge.size != expectLen) return "Respons autentikasi dari kacamata tidak valid."
        val nonceG = challenge.copyOfRange(1, 1 + ProtocolIds.K.NONCE_LEN)
        val macG = challenge.copyOfRange(1 + ProtocolIds.K.NONCE_LEN, expectLen)
        if (!GlassesProtocol.constantTimeEquals(macG, GlassesProtocol.glassesMac(secret, deviceId, nonceC, nonceG))) {
            return "Kacamata gagal membuktikan identitasnya (secret tidak cocok). Buka mode pairing lalu pasangkan ulang."
        }
        val proof = GlassesProtocol.proof(GlassesProtocol.phoneMac(secret, deviceId, nonceG, nonceC))
        if (writeAwait(ProtocolIds.UUID_AUTH, proof) != BluetoothGatt.GATT_SUCCESS) return "Gagal mengirim bukti autentikasi."
        val result = withTimeoutOrNull(5_000L) { authInbox.receive() } ?: return "Kacamata tidak menjawab bukti autentikasi."
        if ((result[0].toInt() and 0xFF) == ProtocolIds.Auth.AUTH_OK) {
            authenticated = true
            return null
        }
        return authFailMessage(result)
    }

    /** Writes a new secret; only accepted while the glasses' physical pairing window is open. Null on success. */
    suspend fun provision(secret: ByteArray): String? {
        while (authInbox.tryReceive().isSuccess) { /* drop stale replies */ }
        if (writeAwait(ProtocolIds.UUID_AUTH, GlassesProtocol.provision(secret)) != BluetoothGatt.GATT_SUCCESS) return "Gagal mengirim secret ke kacamata."
        val r = withTimeoutOrNull(5_000L) { authInbox.receive() } ?: return "Kacamata tidak merespons proses pairing."
        return when (r[0].toInt() and 0xFF) {
            ProtocolIds.Auth.PROVISION_OK -> null
            ProtocolIds.Auth.PROVISION_FAIL ->
                if (r.size > 1 && (r[1].toInt() and 0xFF) == ProtocolIds.Provfail.NOT_PAIRING) {
                    "Kacamata tidak dalam mode pairing. Tahan tombol sambil menyalakan kacamata sekitar 3 detik, lalu coba lagi."
                } else {
                    "Kacamata gagal menyimpan secret."
                }
            else -> "Respons pairing tidak dikenal."
        }
    }

    /** Erases the secret on the glasses (requires an authenticated session). Null on success. */
    suspend fun forget(): String? {
        while (authInbox.tryReceive().isSuccess) { /* drop stale replies */ }
        if (writeAwait(ProtocolIds.UUID_AUTH, GlassesProtocol.forget()) != BluetoothGatt.GATT_SUCCESS) return "Gagal mengirim perintah reset."
        val r = withTimeoutOrNull(5_000L) { authInbox.receive() } ?: return "Kacamata tidak merespons."
        return if ((r[0].toInt() and 0xFF) == ProtocolIds.Auth.FORGET_OK) null else "Reset ditolak kacamata."
    }

    private fun authFailMessage(b: ByteArray): String = when (if (b.size > 1) b[1].toInt() and 0xFF else 0) {
        ProtocolIds.Authfail.NO_SECRET -> "Kacamata belum dipasangkan (belum memiliki secret)."
        ProtocolIds.Authfail.BAD_MAC -> "Secret tidak cocok dengan kacamata. Pasangkan ulang."
        ProtocolIds.Authfail.LOCKED_OUT -> "Kacamata mengunci autentikasi sementara karena percobaan gagal. Tunggu 1 menit."
        ProtocolIds.Authfail.MTU_TOO_SMALL -> "Ukuran paket Bluetooth terlalu kecil; sambungkan ulang."
        else -> "Autentikasi ditolak kacamata."
    }

    // ------------------------------------------------------------------ commands

    /** Sends a command and waits for the glasses' ack. Returns the protocol status (0 = OK), -1 not sent, -2 no ack, -3 disconnected. */
    suspend fun command(cmd: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 6_000L): Int {
        val s = seq.getAndIncrement() and 0xFF
        val ack = CompletableDeferred<Int>()
        acks[s] = ack
        val st = writeAwait(ProtocolIds.UUID_CONTROL, GlassesProtocol.command(cmd, s, payload))
        if (st != BluetoothGatt.GATT_SUCCESS) {
            acks.remove(s)
            return if (st == -3) -3 else -1
        }
        val r = withTimeoutOrNull(timeoutMs) { ack.await() }
        acks.remove(s)
        return r ?: -2
    }

    /** One speaker frame (write without response, paced by the GATT queue). */
    suspend fun writeAudio(frame: ByteArray): Boolean {
        repeat(20) {
            val st = writeAwait(ProtocolIds.UUID_AUDIO_OUT, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (st == BluetoothGatt.GATT_SUCCESS) return true
            if (st == -3) return false
            delay(5)
        }
        return false
    }

    companion object {
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val ALL_CHARS = listOf(
            ProtocolIds.UUID_DEVICE_INFO, ProtocolIds.UUID_STATUS, ProtocolIds.UUID_AUTH, ProtocolIds.UUID_CONTROL,
            ProtocolIds.UUID_EVENT, ProtocolIds.UUID_IMAGE, ProtocolIds.UUID_AUDIO_IN, ProtocolIds.UUID_AUDIO_OUT,
        )
        private val NOTIFY_CHARS = listOf(
            ProtocolIds.UUID_STATUS, ProtocolIds.UUID_AUTH, ProtocolIds.UUID_EVENT, ProtocolIds.UUID_IMAGE, ProtocolIds.UUID_AUDIO_IN,
        )
    }
}
