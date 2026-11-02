package com.xnvalabs.smarteyex.data.glasses

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** IMA ADPCM decoder/encoder state (predictor and step index). */
class AdpcmState(var predictor: Int = 0, var index: Int = 0) {
    fun copy(): AdpcmState = AdpcmState(predictor, index)
}

class GlassesStatus(val battery: Int, val charge: Int, val flags: Int, val batteryMv: Int, val uptimeS: Long) {
    fun has(flag: Int): Boolean = (flags and flag) != 0
    val batteryKnown: Boolean get() = battery != 255
}

class GlassesEvent(val type: Int, val seq: Int, val payload: ByteArray)
class CaptureReady(val xfer: Int, val size: Int, val crc: Long, val width: Int, val height: Int)
class AudioFrame(val stream: Int, val seq: Int, val state: AdpcmState, val data: ByteArray)

/**
 * Wire format of the SmartEyeX glasses protocol v1 (docs/BLE_PROTOCOL.md). Pure Kotlin so it is unit-tested on the
 * JVM against vectors produced by independent reference implementations (see GlassesProtocolTest).
 */
object GlassesProtocol {
    private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    private val STEP_TABLE = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552,
        1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
        12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )

    // ------------------------------------------------------------------ crypto / integrity

    fun crc32(data: ByteArray): Long {
        val c = CRC32()
        c.update(data)
        return c.value
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun glassesMac(secret: ByteArray, deviceId: ByteArray, nonceC: ByteArray, nonceG: ByteArray): ByteArray =
        hmac(secret, ProtocolIds.MAC_LABEL_GLASSES.toByteArray(Charsets.US_ASCII), deviceId, nonceC, nonceG)

    fun phoneMac(secret: ByteArray, deviceId: ByteArray, nonceG: ByteArray, nonceC: ByteArray): ByteArray =
        hmac(secret, ProtocolIds.MAC_LABEL_PHONE.toByteArray(Charsets.US_ASCII), deviceId, nonceG, nonceC)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = java.security.MessageDigest.isEqual(a, b)

    // ------------------------------------------------------------------ auth messages

    fun u16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v shr 8).toByte())

    fun hello(nonceC: ByteArray, mtu: Int): ByteArray = byteArrayOf(ProtocolIds.Auth.HELLO.toByte()) + nonceC + u16(mtu)
    fun proof(mac: ByteArray): ByteArray = byteArrayOf(ProtocolIds.Auth.PROOF.toByte()) + mac
    fun provision(secret: ByteArray): ByteArray = byteArrayOf(ProtocolIds.Auth.PROVISION.toByte()) + secret
    fun forget(): ByteArray = byteArrayOf(ProtocolIds.Auth.FORGET.toByte())

    // ------------------------------------------------------------------ commands / events

    fun command(cmd: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= 16) { "payload perintah maksimal 16 byte" }
        return byteArrayOf(cmd.toByte(), seq.toByte(), payload.size.toByte()) + payload
    }

    fun parseEvent(b: ByteArray): GlassesEvent? {
        if (b.size < 3 || (b[2].toInt() and 0xFF) != b.size - 3) return null
        return GlassesEvent(b[0].toInt() and 0xFF, b[1].toInt() and 0xFF, b.copyOfRange(3, b.size))
    }

    fun parseStatus(b: ByteArray): GlassesStatus? {
        if (b.size != ProtocolIds.K.STATUS_LEN || (b[0].toInt() and 0xFF) != ProtocolIds.K.PROTO_VERSION) return null
        return GlassesStatus(
            battery = b[1].toInt() and 0xFF,
            charge = b[2].toInt() and 0xFF,
            flags = b[3].toInt() and 0xFF,
            batteryMv = (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8),
            uptimeS = u32(b, 6),
        )
    }

    fun parseCaptureReady(p: ByteArray): CaptureReady? {
        if (p.size != 13) return null
        val size = u32(p, 1)
        if (size <= 0 || size > 2_000_000) return null
        return CaptureReady(
            xfer = p[0].toInt() and 0xFF,
            size = size.toInt(),
            crc = u32(p, 5),
            width = (p[9].toInt() and 0xFF) or ((p[10].toInt() and 0xFF) shl 8),
            height = (p[11].toInt() and 0xFF) or ((p[12].toInt() and 0xFF) shl 8),
        )
    }

    fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    // ------------------------------------------------------------------ audio frames

    fun parseAudioFrame(b: ByteArray): AudioFrame? {
        if (b.size <= ProtocolIds.K.AUDIO_HEADER_LEN) return null
        val idx = b[5].toInt() and 0xFF
        if (idx > 88) return null
        val pred = ((b[3].toInt() and 0xFF) or (b[4].toInt() shl 8)).toShort().toInt()
        return AudioFrame(b[0].toInt() and 0xFF, (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8), AdpcmState(pred, idx),
            b.copyOfRange(ProtocolIds.K.AUDIO_HEADER_LEN, b.size))
    }

    fun buildAudioFrame(stream: Int, seq: Int, before: AdpcmState, adpcm: ByteArray): ByteArray =
        byteArrayOf(stream.toByte()) + u16(seq and 0xFFFF) + u16(before.predictor and 0xFFFF) + byteArrayOf(before.index.toByte()) + adpcm

    /** Encodes [count] (even) samples starting at [offset]; returns count/2 bytes, first sample in the low nibble. */
    fun adpcmEncode(st: AdpcmState, pcm: ShortArray, offset: Int, count: Int): ByteArray {
        val out = ByteArray(count / 2)
        var o = 0
        var i = 0
        while (i + 1 < count) {
            val lo = encodeOne(st, pcm[offset + i].toInt())
            val hi = encodeOne(st, pcm[offset + i + 1].toInt())
            out[o++] = (lo or (hi shl 4)).toByte()
            i += 2
        }
        return out
    }

    fun adpcmDecode(st: AdpcmState, data: ByteArray): ShortArray {
        val out = ShortArray(data.size * 2)
        var o = 0
        for (byte in data) {
            val v = byte.toInt() and 0xFF
            out[o++] = decodeOne(st, v and 0x0F).toShort()
            out[o++] = decodeOne(st, v shr 4).toShort()
        }
        return out
    }

    private fun encodeOne(st: AdpcmState, sample: Int): Int {
        var step = STEP_TABLE[st.index]
        var diff = sample - st.predictor
        var sign = 0
        if (diff < 0) {
            sign = 8
            diff = -diff
        }
        var delta = 0
        var vpdiff = step shr 3
        if (diff >= step) { delta = 4; diff -= step; vpdiff += step }
        step = step shr 1
        if (diff >= step) { delta = delta or 2; diff -= step; vpdiff += step }
        step = step shr 1
        if (diff >= step) { delta = delta or 1; vpdiff += step }
        st.predictor = (st.predictor + (if (sign != 0) -vpdiff else vpdiff)).coerceIn(-32768, 32767)
        delta = delta or sign
        st.index = (st.index + INDEX_TABLE[delta and 7]).coerceIn(0, 88)
        return delta
    }

    private fun decodeOne(st: AdpcmState, code: Int): Int {
        val step = STEP_TABLE[st.index]
        st.index = (st.index + INDEX_TABLE[code and 7]).coerceIn(0, 88)
        var vpdiff = step shr 3
        if ((code and 4) != 0) vpdiff += step
        if ((code and 2) != 0) vpdiff += step shr 1
        if ((code and 1) != 0) vpdiff += step shr 2
        st.predictor = (st.predictor + (if ((code and 8) != 0) -vpdiff else vpdiff)).coerceIn(-32768, 32767)
        return st.predictor
    }

    // ------------------------------------------------------------------ WAV helpers

    fun pcmToWav(pcm: ShortArray, rate: Int = ProtocolIds.K.AUDIO_RATE_HZ): ByteArray {
        val dataLen = pcm.size * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        fun i32(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + dataLen); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(dataLen)
        for (s in pcm) i16(s.toInt())
        return out.toByteArray()
    }

    /** Parses a PCM16 WAV file (any rate/channels), mixes to mono and resamples to 16 kHz. Null if unsupported. */
    fun wavToPcm16k(wav: ByteArray): ShortArray? {
        if (wav.size < 44 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" || String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        var dataStart = -1
        var dataLen = 0
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            val size = u32(wav, pos + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val body = pos + 8
            if (id == "fmt " && body + 16 <= wav.size) {
                val format = (wav[body].toInt() and 0xFF) or ((wav[body + 1].toInt() and 0xFF) shl 8)
                if (format != 1) return null
                channels = (wav[body + 2].toInt() and 0xFF) or ((wav[body + 3].toInt() and 0xFF) shl 8)
                rate = u32(wav, body + 4).toInt()
                bits = (wav[body + 14].toInt() and 0xFF) or ((wav[body + 15].toInt() and 0xFF) shl 8)
            } else if (id == "data") {
                dataStart = body
                dataLen = minOf(size, wav.size - body)
                break
            }
            pos = body + size + (size and 1)
        }
        if (dataStart < 0 || channels < 1 || bits != 16 || rate < 4000) return null
        val frames = dataLen / (2 * channels)
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0
            for (c in 0 until channels) {
                val o = dataStart + (f * channels + c) * 2
                acc += ((wav[o].toInt() and 0xFF) or (wav[o + 1].toInt() shl 8)).toShort().toInt()
            }
            mono[f] = (acc / channels).toShort()
        }
        val target = ProtocolIds.K.AUDIO_RATE_HZ
        if (rate == target) return mono
        val outLen = (mono.size.toLong() * target / rate).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val src = i.toDouble() * rate / target
            val i0 = src.toInt().coerceAtMost(mono.size - 1)
            val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
            val frac = src - i0
            out[i] = (mono[i0] * (1 - frac) + mono[i1] * frac).toInt().toShort()
        }
        return out
    }

    fun hexToBytes(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }
}

/** Reassembles one JPEG from IMAGE notifications ([xfer u8][offset u32 LE][data]); strictly in order. */
class ImageAssembler(private val ready: CaptureReady) {
    enum class State { MORE, DONE, BAD }

    private val buf = ByteArray(ready.size)
    private var received = 0

    fun add(frame: ByteArray): State {
        if (frame.size <= ProtocolIds.K.IMAGE_HEADER_LEN) return State.BAD
        if ((frame[0].toInt() and 0xFF) != ready.xfer) return State.MORE  // stale chunk from an earlier transfer
        val offset = GlassesProtocol.u32(frame, 1)
        val n = frame.size - ProtocolIds.K.IMAGE_HEADER_LEN
        if (offset != received.toLong() || received + n > buf.size) return State.BAD
        System.arraycopy(frame, ProtocolIds.K.IMAGE_HEADER_LEN, buf, received, n)
        received += n
        if (received < buf.size) return State.MORE
        return if (GlassesProtocol.crc32(buf) == ready.crc) State.DONE else State.BAD
    }

    fun bytes(): ByteArray = buf
}
