package com.xnvalabs.smarteyex.data.net

import com.xnvalabs.smarteyex.data.session.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal JSON-over-HTTPS client for the SmartEyeX platform API (docs/openapi.yaml).
 * Plain HttpURLConnection + org.json (both part of Android), same "no dependency before it is earned"
 * rule as the rest of this app. Adds the bearer token and transparently refreshes it once on 401.
 */
object ApiClient {
    class ApiException(val status: Int, val code: String, message: String) : Exception(message)

    private val refreshLock = Mutex()
    private val REFRESHABLE = setOf("token_expired", "invalid_token")

    suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        query: Map<String, String> = emptyMap(),
        auth: Boolean = true,
        timeoutMs: Int = 90_000,
    ): JSONObject {
        val base = SessionRepository.baseUrl()
            ?: throw ApiException(0, "no_server", "Alamat server SmartEyeX belum diatur. Atur di menu Akun.")
        val token = if (auth) SessionRepository.accessToken() else null
        if (auth && token == null) throw ApiException(401, "not_logged_in", "Belum masuk ke server SmartEyeX.")
        try {
            return withContext(Dispatchers.IO) { once(base, method, path, body, query, token, timeoutMs) }
        } catch (e: ApiException) {
            if (auth && e.status == 401 && e.code in REFRESHABLE) {
                val fresh = refreshAfter(token)
                return withContext(Dispatchers.IO) { once(base, method, path, body, query, fresh, timeoutMs) }
            }
            throw e
        }
    }

    private suspend fun refreshAfter(stale: String?): String = refreshLock.withLock {
        val current = SessionRepository.accessToken()
        if (current != null && current != stale) return current  // another request already refreshed
        if (!SessionRepository.refreshTokens()) throw ApiException(401, "session_expired", "Sesi berakhir, silakan masuk lagi.")
        SessionRepository.accessToken() ?: throw ApiException(401, "session_expired", "Sesi berakhir, silakan masuk lagi.")
    }

    private fun once(
        base: String, method: String, path: String, body: JSONObject?, query: Map<String, String>, token: String?, timeoutMs: Int,
    ): JSONObject {
        val qs = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val conn = URL(base + path + qs).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "SmartEyeX-Android")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code in 200..299) return if (text.isBlank()) JSONObject() else JSONObject(text)
            val err = runCatching { JSONObject(text).getJSONObject("error") }.getOrNull()
            throw ApiException(code, err?.optString("code").orEmpty().ifEmpty { "http_$code" }, err?.optString("message").orEmpty().ifEmpty { "Server mengembalikan HTTP $code" })
        } catch (e: IOException) {
            throw ApiException(0, "network", "Tidak dapat terhubung ke server. Periksa koneksi internet dan alamat server.")
        } catch (e: JSONException) {
            throw ApiException(0, "bad_response", "Respons server tidak valid.")
        } finally {
            conn.disconnect()
        }
    }

    /** Human-readable Indonesian message for any failure from [call]. */
    fun describe(e: Throwable): String {
        if (e is CancellationException) throw e
        if (e !is ApiException) return e.message ?: "Terjadi kesalahan."
        return when (e.code) {
            "cloud_processing_disabled" -> "Cloud Processing belum diizinkan. Aktifkan di Privacy Control."
            "xnai_not_configured" -> "Server ini belum dikonfigurasi dengan penyedia model XNAI. Hubungi operator server."
            "stt_not_configured" -> "Transkripsi suara belum dikonfigurasi di server ini."
            "quota_exceeded" -> "Kuota penggunaan AI sudah habis. Coba lagi nanti."
            "disabled_by_policy" -> "Fitur ini dinonaktifkan oleh kebijakan organisasi."
            "wrong_realm" -> "Fitur ini tidak tersedia untuk jenis akun Anda."
            "invalid_credentials" -> "Email, organisasi, atau password salah."
            "account_locked" -> "Akun dikunci sementara karena terlalu banyak percobaan gagal."
            "email_taken" -> "Email sudah terdaftar."
            "weak_password" -> e.message ?: "Password terlalu lemah."
            "signup_disabled" -> "Pendaftaran dinonaktifkan di server ini."
            "invite_invalid" -> "Kode undangan tidak valid atau sudah kedaluwarsa."
            "rate_limited" -> "Terlalu banyak permintaan. Coba lagi sebentar lagi."
            else -> e.message ?: "Terjadi kesalahan (${e.status})."
        }
    }
}
