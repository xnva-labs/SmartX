package com.xnvalabs.smarteyex.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption at rest for data this app keeps locally (session tokens, memory, PIN verifier).
 * Keys are generated inside the Android Keystore and never leave it: an attacker who copies the app's
 * files cannot decrypt them elsewhere. AES-256-GCM with a random IV per message; [aad] binds the
 * ciphertext to its purpose so one blob cannot be substituted for another.
 */
object SecureStore {
    private const val PROVIDER = "AndroidKeyStore"
    private const val AES_ALIAS = "smarteyex_aes_v1"
    private const val HMAC_ALIAS = "smarteyex_hmac_v1"
    const val PREFIX = "v1:"

    private fun keystore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun aesKey(): SecretKey {
        (keystore().getKey(AES_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun hmacKey(): SecretKey {
        (keystore().getKey(HMAC_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        gen.init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    fun encrypt(plain: String, aad: String = ""): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        if (aad.isNotEmpty()) cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(cipher.iv + ct, Base64.NO_WRAP)
    }

    /** Returns null when the blob is not ours, was tampered with, or the key no longer exists. */
    fun decrypt(blob: String, aad: String = ""): String? {
        if (!blob.startsWith(PREFIX)) return null
        return try {
            val raw = Base64.decode(blob.substring(PREFIX.length), Base64.NO_WRAP)
            if (raw.size < 13) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
            if (aad.isNotEmpty()) cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** HMAC-SHA256 with a non-exportable Keystore key (used to make PIN hashes useless off-device). */
    fun hmac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey())
        return mac.doFinal(data)
    }
}
