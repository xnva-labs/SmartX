package com.xnvalabs.smarteyex.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.xnvalabs.smarteyex.data.notifications.NotificationRepository

private val KNOWN_APP_LABELS = mapOf(
    "com.whatsapp" to "WhatsApp",
    "org.telegram.messenger" to "Telegram",
    "com.instagram.android" to "Instagram",
    "com.google.android.gm" to "Gmail",
    "com.google.android.youtube" to "YouTube",
)

/**
 * Real Android NotificationListenerService — Tahap 5 (Communication
 * Assistant lanjutan). Requires the user to manually grant "Notification
 * access" in system Settings; NotificationListenerScreen shows a banner
 * with a button that opens that Settings screen when access isn't
 * granted yet — this permission can't be requested via a normal runtime
 * dialog like CAMERA in Tahap 4, Android only exposes it through Settings.
 *
 * Deliberately minimal: extracts app label + title/text and hands them
 * straight to NotificationRepository. No filtering, no reply-sending
 * logic here — feature #4 Voice Reply (actually sending a reply back
 * into WhatsApp/Telegram/etc.) is a separate, later piece, since it
 * needs per-app RemoteInput/action-intent handling that several apps
 * don't expose the same way.
 */
class SmartEyeXNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val message = listOf(title, text).filter { it.isNotBlank() }.joinToString(" — ")
        if (message.isBlank()) return

        val appLabel = KNOWN_APP_LABELS[sbn.packageName] ?: runCatching {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)

        NotificationRepository.push(appLabel, message)
    }
}
