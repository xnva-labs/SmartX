package com.xnvalabs.smarteyex.data.privacy

/**
 * Snapshot of every privacy toggle a user controls. Any subsystem that
 * touches sensitive data — camera, mic, memory storage, cloud AI calls,
 * face recognition — must check the relevant flag here before doing
 * anything. See [PrivacyRepository] for how these are read and written.
 */
data class PrivacySettings(
    val cameraEnabled: Boolean = false,
    val microphoneEnabled: Boolean = false,
    val memoryEnabled: Boolean = false,
    val cloudProcessingEnabled: Boolean = false,
    val faceRecognitionEnabled: Boolean = false,
) {
    companion object {
        /** Everything OFF by default — the user opts in, never opts out. */
        val DEFAULT = PrivacySettings()
    }
}
