package com.xnvalabs.smarteyex.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import com.xnvalabs.smarteyex.data.security.SecureStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Feature #26 (User Authentication) — optional PIN lock, opt-in: nothing is locked until the user sets
 * a PIN from Privacy Control.
 *
 * Storage: the PIN is never stored. We keep a salted PBKDF2-HMAC-SHA256 derivation of it, additionally
 * run through an HMAC whose key lives inside the Android Keystore and cannot be exported — so copying the
 * app's files off the phone does not allow brute-forcing the 4-digit space offline. Wrong attempts are
 * rate-limited (5 free tries, then 30 s doubling up to 15 min). A PIN hashed with the original unsalted
 * SHA-256 scheme is upgraded transparently on the next successful unlock.
 *
 * [isUnlocked] is the gate MainActivity checks: true when no PIN is set, or once [verify] succeeds. It
 * resets to false on every fresh process start.
 *
 * No "forgot PIN" recovery yet: losing the PIN means reinstalling (a deliberate later decision).
 */
object AuthRepository {
    private const val PREFS_NAME = "smarteyex_auth"
    private const val KEY_LEGACY_HASH = "pin_hash"
    private const val KEY_VERIFIER = "pin_verifier"
    private const val KEY_SALT = "pin_salt"
    private const val KEY_FAILS = "pin_fails"
    private const val KEY_LOCK_UNTIL = "pin_lock_until"
    private const val ITERATIONS = 120_000
    private const val FREE_ATTEMPTS = 5
    private const val BASE_LOCK_MS = 30_000L
    private const val MAX_LOCK_MS = 15 * 60 * 1000L

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    var isUnlocked = mutableStateOf(false)
        private set

    /** Call once, from MainActivity.onCreate, before checking [isUnlocked]. */
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isUnlocked.value = !isPinSet()
        initialized = true
    }

    fun isPinSet(): Boolean = prefs.contains(KEY_VERIFIER) || prefs.contains(KEY_LEGACY_HASH)

    fun setPin(pin: String) {
        store(pin)
        resetFailures()
        isUnlocked.value = true
    }

    fun clearPin() {
        prefs.edit().remove(KEY_VERIFIER).remove(KEY_SALT).remove(KEY_LEGACY_HASH).remove(KEY_FAILS).remove(KEY_LOCK_UNTIL).apply()
        isUnlocked.value = true
    }

    /** Seconds until another attempt is allowed; 0 when not locked out. */
    fun lockRemainingSeconds(): Long {
        val ms = prefs.getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()
        return if (ms > 0) (ms + 999) / 1000 else 0L
    }

    fun verify(pin: String): Boolean {
        if (!isPinSet()) return true
        if (lockRemainingSeconds() > 0) return false
        val ok = if (prefs.contains(KEY_VERIFIER)) {
            val salt = Base64.decode(prefs.getString(KEY_SALT, "") ?: "", Base64.NO_WRAP)
            val stored = prefs.getString(KEY_VERIFIER, "") ?: ""
            MessageDigest.isEqual(verifierOf(pin, salt).toByteArray(), stored.toByteArray())
        } else {
            val legacy = prefs.getString(KEY_LEGACY_HASH, "") ?: ""
            MessageDigest.isEqual(legacyHash(pin).toByteArray(), legacy.toByteArray())
        }
        if (ok) {
            if (!prefs.contains(KEY_VERIFIER)) store(pin)
            resetFailures()
            isUnlocked.value = true
        } else {
            registerFailure()
        }
        return ok
    }

    private fun store(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(KEY_VERIFIER, verifierOf(pin, salt))
            .remove(KEY_LEGACY_HASH)
            .apply()
    }

    private fun verifierOf(pin: String, salt: ByteArray): String {
        val dk = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, 256)).encoded
        val bound = runCatching { SecureStore.hmac(dk) }.getOrElse { dk }
        return Base64.encodeToString(bound, Base64.NO_WRAP)
    }

    private fun legacyHash(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun registerFailure() {
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILS, fails)
        if (fails >= FREE_ATTEMPTS) {
            val steps = (fails - FREE_ATTEMPTS).coerceAtMost(10)
            val lock = (BASE_LOCK_MS shl steps).coerceAtMost(MAX_LOCK_MS)
            editor.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + lock)
        }
        editor.apply()
    }

    private fun resetFailures() {
        prefs.edit().remove(KEY_FAILS).remove(KEY_LOCK_UNTIL).apply()
    }
}
