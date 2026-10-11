package com.example.nkill

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Text-based rules (and a fallback for app-level rules when Shizuku isn't running).
 * Fires right after a notification is posted, so a brief flash is possible.
 */
class BlockerService : NotificationListenerService() {

    override fun onListenerConnected() {
        runCatching { activeNotifications?.forEach { check(it) } }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        check(sbn)
    }

    private fun check(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val rules = RuleStore.load(this).filter { it.pkg == sbn.packageName }
        if (rules.isEmpty()) return

        val e = sbn.notification.extras
        val haystack = listOf(
            Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
            Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT
        ).mapNotNull { e.getCharSequence(it)?.toString() }.joinToString("\n")

        if (rules.any { it.text.isEmpty() || haystack.contains(it.text, ignoreCase = true) }) kill(sbn)
    }

    private fun kill(sbn: StatusBarNotification) {
        // Ongoing/foreground-service notifications usually refuse cancel(); snooze often works on them.
        runCatching { cancelNotification(sbn.key) }
        if (sbn.isOngoing) runCatching { snoozeNotification(sbn.key, 12 * 60 * 60 * 1000L) }
    }
}
