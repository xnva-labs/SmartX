package com.xnvalabs.smarteyex.data.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import androidx.compose.runtime.mutableStateOf
import com.xnvalabs.smarteyex.service.SmartEyeXNotificationListener

/** Snapshot of the currently-controlled media session, if any. */
data class MediaState(
    val title: String? = null,
    val artist: String? = null,
    val isPlaying: Boolean = false,
    val hasSession: Boolean = false,
)

/**
 * Feature #28 (Media Assistant) — controls whatever's playing via
 * Android's MediaSessionManager, the same way a Bluetooth headset's
 * play/pause button does. Reuses SmartEyeXNotificationListener's
 * "Notification access" grant from Tahap 5: MediaSessionManager requires
 * an enabled NotificationListenerService component to list active
 * sessions — see [com.xnvalabs.smarteyex.data.notifications.NotificationRepository.isAccessGranted]
 * for the shared check — so there's no separate media permission to request.
 *
 * Deliberately controls the FIRST active session only (whatever's
 * topmost/most recent) rather than letting the user pick among several
 * — matches the feature spec's simple "Play / Pause / Next / Previous"
 * examples, not a full multi-app session picker.
 */
object MediaRepository {
    var state = mutableStateOf(MediaState())
        private set

    private var controller: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(playbackState: PlaybackState?) {
            refreshFromController()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshFromController()
        }
    }

    /** Re-reads the active session list and binds to the first one, if any. Call whenever MediaScreen opens/resumes. */
    fun refresh(context: Context) {
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(context, SmartEyeXNotificationListener::class.java)
        val sessions = runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())

        controller?.unregisterCallback(callback)
        controller = sessions.firstOrNull()
        controller?.registerCallback(callback)
        refreshFromController()
    }

    fun play() = controller?.transportControls?.play()
    fun pause() = controller?.transportControls?.pause()
    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

    private fun refreshFromController() {
        val c = controller
        if (c == null) {
            state.value = MediaState(hasSession = false)
            return
        }
        val metadata = c.metadata
        val playback = c.playbackState
        state.value = MediaState(
            title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
            artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            hasSession = true,
        )
    }
}
