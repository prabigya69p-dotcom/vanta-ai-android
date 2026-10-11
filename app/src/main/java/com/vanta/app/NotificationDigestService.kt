package com.vanta.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NotificationDigestSnapshot(
    val emailGroups: Int = 0,
    val missedCallAlerts: Int = 0,
    val calendarAlerts: Int = 0,
    val totalNotifications: Int = 0
)

/**
 * Optional, user-enabled digest. It stores aggregate counters and notification keys only;
 * it deliberately does not persist notification text, message bodies, or caller names.
 */
class NotificationDigestService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        runCatching { record(this, sbn) }
    }

    companion object {
        private const val PREFS = "vanta_notification_digest"
        private const val KEY_DAY = "day"
        private const val KEY_SEEN = "seen_notification_keys"

        private fun today(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        private fun prepareToday(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_DAY, null) != today()) {
                prefs.edit().clear().putString(KEY_DAY, today()).commit()
            }
        }

        private fun record(context: Context, sbn: StatusBarNotification) {
            prepareToday(context)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet(KEY_SEEN, emptySet())?.toMutableSet() ?: mutableSetOf()
            val uniqueKey = sbn.packageName + ":" + sbn.key
            if (!seen.add(uniqueKey)) return
            if (seen.size > 800) {
                val iterator = seen.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }

            val pkg = sbn.packageName.lowercase(Locale.ROOT)
            val notification = sbn.notification
            val extras = notification.extras
            val title = extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            val combined = (title + " " + text).lowercase(Locale.ROOT)
            val isEmail = pkg in setOf(
                "com.google.android.gm",
                "com.microsoft.office.outlook",
                "com.samsung.android.email",
                "com.yahoo.mobile.client.android.mail",
                "ch.protonmail.android"
            ) || pkg.contains("email") || pkg.contains("mail")
            val isCalendar = pkg.contains("calendar") ||
                notification.category == android.app.Notification.CATEGORY_EVENT
            val phonePackage = pkg.contains("dialer") || pkg.contains("phone") ||
                pkg.contains("incallui") || pkg.contains("telecom")
            val missedCall = notification.category == "missed_call" ||
                (phonePackage && Regex("""\bmissed\s+calls?\b|\bcall\s+missed\b""").containsMatchIn(combined))

            val editor = prefs.edit()
                .putStringSet(KEY_SEEN, seen)
                .putInt("total", prefs.getInt("total", 0) + 1)
            if (isEmail) editor.putInt("email", prefs.getInt("email", 0) + 1)
            if (isCalendar) editor.putInt("calendar", prefs.getInt("calendar", 0) + 1)
            if (missedCall) editor.putInt("missed", prefs.getInt("missed", 0) + 1)
            editor.apply()
        }

        fun hasAccess(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS
            ).orEmpty()
            val expected = ComponentName(context, NotificationDigestService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun openAccessSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }

        fun snapshot(context: Context): NotificationDigestSnapshot {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_DAY, null) != today()) return NotificationDigestSnapshot()
            return NotificationDigestSnapshot(
                emailGroups = prefs.getInt("email", 0),
                missedCallAlerts = prefs.getInt("missed", 0),
                calendarAlerts = prefs.getInt("calendar", 0),
                totalNotifications = prefs.getInt("total", 0)
            )
        }

        fun buildBriefing(context: Context): String {
            if (!hasAccess(context)) {
                return "Notification access is off. Open Vanta Settings and choose Connect email / calendar / missed-call notifications, then enable VANTA notification digest in Android settings."
            }
            val stats = snapshot(context)
            return "Morning briefing: I recorded " + stats.emailGroups + " email notification group(s), " +
                stats.missedCallAlerts + " missed-call alert(s), and " + stats.calendarAlerts +
                " calendar alert(s) today. " + stats.totalNotifications +
                " unique notification(s) have been seen since midnight. Email notification groups are not an exact unread-inbox count."
        }
    }
}
