package com.xnvalabs.smarteyex.data.privacy

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * Single source of truth for privacy settings. Backed by SharedPreferences
 * for persistence — no DataStore/Room dependency needed yet, same
 * "don't add a dependency before it's earned" rule the rest of this
 * project follows (see MainActivity's Navigation Compose note and
 * AndroidManifest's permissions note).
 *
 * Every future repository that reads/writes camera, mic, memory, cloud, or
 * face-recognition data MUST check [settings] here first. This is the
 * enforcement point Tahap 2 (Memory), Tahap 3 (Backend XNAI), and Tahap 4
 * (Camera) all build on top of.
 */
object PrivacyRepository {
    private const val PREFS_NAME = "smarteyex_privacy"
    private const val KEY_CAMERA = "camera_enabled"
    private const val KEY_MIC = "microphone_enabled"
    private const val KEY_MEMORY = "memory_enabled"
    private const val KEY_CLOUD = "cloud_processing_enabled"
    private const val KEY_FACE = "face_recognition_enabled"

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    /** Reactive snapshot — Compose screens read this directly and recompose on change. */
    var settings = mutableStateOf(PrivacySettings.DEFAULT)
        private set

    /** Call once, from MainActivity.onCreate, before any screen reads [settings]. */
    fun init(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        settings.value = PrivacySettings(
            cameraEnabled = prefs.getBoolean(KEY_CAMERA, false),
            microphoneEnabled = prefs.getBoolean(KEY_MIC, false),
            memoryEnabled = prefs.getBoolean(KEY_MEMORY, false),
            cloudProcessingEnabled = prefs.getBoolean(KEY_CLOUD, false),
            faceRecognitionEnabled = prefs.getBoolean(KEY_FACE, false),
        )
        initialized = true
    }

    fun setCameraEnabled(enabled: Boolean) = update { it.copy(cameraEnabled = enabled) }
    fun setMicrophoneEnabled(enabled: Boolean) = update { it.copy(microphoneEnabled = enabled) }
    fun setMemoryEnabled(enabled: Boolean) = update { it.copy(memoryEnabled = enabled) }
    /** Set by MainActivity: mirrors the Cloud Processing toggle to the server, which enforces it too. */
    var onCloudChanged: ((Boolean) -> Unit)? = null

    fun setCloudProcessingEnabled(enabled: Boolean) {
        update { it.copy(cloudProcessingEnabled = enabled) }
        onCloudChanged?.invoke(enabled)
    }
    fun setFaceRecognitionEnabled(enabled: Boolean) = update { it.copy(faceRecognitionEnabled = enabled) }

    /**
     * Wipes stored data and resets the memory toggle. Tahap 2 (Memory)
     * hooks its own row-deletion logic into [onClearMemory] — this
     * function is the single call site so Memory never has to duplicate
     * "check privacy, then clear" logic itself.
     */
    fun clearAllData(onClearMemory: () -> Unit = {}) {
        onClearMemory()
        setMemoryEnabled(false)
    }

    private inline fun update(transform: (PrivacySettings) -> PrivacySettings) {
        check(initialized) { "PrivacyRepository.init(context) must be called before mutating settings" }
        val next = transform(settings.value)
        settings.value = next
        prefs.edit()
            .putBoolean(KEY_CAMERA, next.cameraEnabled)
            .putBoolean(KEY_MIC, next.microphoneEnabled)
            .putBoolean(KEY_MEMORY, next.memoryEnabled)
            .putBoolean(KEY_CLOUD, next.cloudProcessingEnabled)
            .putBoolean(KEY_FACE, next.faceRecognitionEnabled)
            .apply()
    }
}
