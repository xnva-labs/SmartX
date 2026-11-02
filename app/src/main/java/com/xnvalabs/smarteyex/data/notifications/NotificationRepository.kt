package com.xnvalabs.smarteyex.data.notifications

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationManagerCompat

/** One real notification captured by SmartEyeXNotificationListener. */
data class RawNotification(val app: String, val msg: String, val timestamp: Long)

/**
 * Bridge between SmartEyeXNotificationListener (a system service, with no
 * Activity/Compose lifecycle of its own) and NotificationListenerScreen.
 * The service calls [push] whenever Android delivers a real notification;
 * the screen just observes [notifications] and re-ranks/displays them —
 * same reactive-state pattern as PrivacyRepository/MemoryRepository.
 *
 * Deliberately no privacy gating here: notification access is already
 * gated by Android's own system permission (the service simply never
 * receives callbacks until the user grants "Notification access" in
 * Settings). There's no separate Privacy Control toggle for this yet —
 * unlike Camera/Mic/Memory/Cloud, this category wasn't part of the
 * PrivacySettings schema from Tahap 1, and extending that schema is a
 * separate decision, not bundled into this piece.
 */
object NotificationRepository {
    private const val MAX_KEPT = 30

    var notifications = mutableStateOf(emptyList<RawNotification>())
        private set

    fun push(app: String, msg: String) {
        notifications.value = (listOf(RawNotification(app, msg, System.currentTimeMillis())) + notifications.value)
            .take(MAX_KEPT)
    }

    /**
     * Whether the user has granted system "Notification access" to this
     * app. Shared by NotificationListenerScreen (Tahap 5) and
     * MediaRepository (feature #28 Media Assistant) — both ride the same
     * NotificationListenerService grant, Android just uses it for two
     * different APIs (notification capture vs. media session access).
     * One place to check it, so both screens stay in sync.
     */
    fun isAccessGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
}
