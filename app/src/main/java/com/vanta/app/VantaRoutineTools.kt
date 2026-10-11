package com.vanta.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import java.util.Calendar
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local-first reminders and briefings. Uses inexact system alarms instead of unrestricted
 * exact-alarm access. Android Calendar's own editor remains the final save step.
 */
object VantaRoutineTools {
    private const val PREFS = "vanta_routines"
    private const val MORNING_ID = 771041
    private const val MORNING_ACTION = "com.vanta.app.MORNING_BRIEFING"

    fun execute(context: Context, raw: String): LocalCommandResult? {
        val prompt = raw.trim()
        val lower = prompt.lowercase(Locale.ROOT)

        if (lower in setOf("open notification access", "enable notification access", "connect notifications", "connect email notifications", "connect missed call notifications")) {
            NotificationDigestService.openAccessSettings(context)
            return LocalCommandResult("Opening Android notification access settings. Enable VANTA notification digest there, and allow VANTA notifications in App permissions so reminders can appear.")
        }

        if (lower in setOf("morning briefing", "daily briefing", "brief me", "notification summary", "summarize my notifications", "summarize notifications")) {
            return LocalCommandResult(NotificationDigestService.buildBriefing(context))
        }

        if (lower in setOf("how many emails", "how many email notifications", "any new emails", "check email notifications")) {
            if (!NotificationDigestService.hasAccess(context)) {
                return LocalCommandResult("Email notification counting is off. Say “enable notification access” and enable VANTA notification digest. It counts supported notifications, not your exact unread inbox.")
            }
            val count = NotificationDigestService.snapshot(context).emailGroups
            return LocalCommandResult("I recorded " + count + " email notification group(s) today. This is not guaranteed to equal your unread Gmail count.")
        }

        if (lower in setOf("missed calls", "any missed calls", "how many missed calls", "check missed calls")) {
            if (!NotificationDigestService.hasAccess(context)) {
                return LocalCommandResult("Missed-call notification tracking is off. Say “enable notification access” and enable VANTA notification digest.")
            }
            val count = NotificationDigestService.snapshot(context).missedCallAlerts
            return LocalCommandResult("I recorded " + count + " missed-call alert(s) today from supported phone notifications. This is not a full call-log scan.")
        }

        if (lower in setOf("calendar notifications", "how many calendar alerts", "check calendar alerts")) {
            if (!NotificationDigestService.hasAccess(context)) {
                return LocalCommandResult("Calendar notification counting is off. Say “enable notification access” and enable VANTA notification digest.")
            }
            val count = NotificationDigestService.snapshot(context).calendarAlerts
            return LocalCommandResult("I recorded " + count + " calendar alert(s) today. Google Calendar can also manage event-specific alerts in its own settings.")
        }

        if (lower in setOf("enable morning briefing", "schedule morning briefing", "turn on morning briefing")) {
            scheduleMorningBriefing(context, 7, 0)
            return LocalCommandResult("Daily morning briefing is scheduled for 7:00 AM. It will summarise notification counts available to VANTA.")
        }

        if (lower in setOf("disable morning briefing", "cancel morning briefing", "turn off morning briefing")) {
            cancelMorningBriefing(context)
            return LocalCommandResult("Daily morning briefing disabled.")
        }

        val briefingTime = Regex(
            """^(?:set|schedule|enable)\s+(?:my\s+)?morning briefing\s+(?:for|at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$""",
            RegexOption.IGNORE_CASE
        ).find(prompt)
        if (briefingTime != null) {
            val parsed = parseTime(briefingTime.groupValues[1], briefingTime.groupValues[2], briefingTime.groupValues[3])
                ?: return LocalCommandResult("Please give a valid time, such as “set morning briefing at 7:30 AM”.")
            scheduleMorningBriefing(context, parsed.first, parsed.second)
            return LocalCommandResult("Daily morning briefing scheduled for " + formatTime(parsed.first, parsed.second) + ".")
        }

        val daily = Regex(
            """^(?:remind me\s+)?(?:every day|daily) at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s+(?:to\s+)?(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(prompt)
        if (daily != null) {
            val parsed = parseTime(daily.groupValues[1], daily.groupValues[2], daily.groupValues[3])
                ?: return LocalCommandResult("Please give a valid time. Example: “remind me every day at 7 PM to prepare my school bag”.")
            val cleanTask = daily.groupValues[4].trim()
            if (cleanTask.isBlank()) return LocalCommandResult("Tell me what I should remind you to do each day.")
            scheduleDailyReminder(context, parsed.first, parsed.second, cleanTask)
            return LocalCommandResult("Daily reminder set for " + formatTime(parsed.first, parsed.second) + ": " + cleanTask + ".")
        }

        val oneTime = Regex(
            """^(?:remind me|set (?:a )?reminder) in\s+(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\s+(?:to\s+)?(.+)$""",
            RegexOption.IGNORE_CASE
        ).find(prompt)
        if (oneTime != null) {
            val amount = oneTime.groupValues[1].toLongOrNull() ?: 0L
            val unit = oneTime.groupValues[2].lowercase(Locale.ROOT)
            val multiplier = when {
                unit.startsWith("hour") || unit.startsWith("hr") -> 3_600_000L
                unit.startsWith("min") -> 60_000L
                else -> 1_000L
            }
            if (amount !in 1L..100_000L || amount * multiplier > 365L * 24L * 60L * 60L * 1000L) {
                return LocalCommandResult("Please choose a reminder between 1 unit and about 1 year from now.")
            }
            val task = oneTime.groupValues[3].trim()
            if (task.isBlank()) return LocalCommandResult("Tell me what the reminder is for.")
            scheduleOnce(context, amount * multiplier, task)
            return LocalCommandResult("Reminder set for " + amount + " " + unit + " from now: " + task + ". Android may deliver it a little later to save battery.")
        }

        val calendarEvent = Regex(
            """^(?:add|create|schedule)\s+(?:a\s+)?calendar event(?:\s+(?:called|titled|named))?\s*(.*)$""",
            RegexOption.IGNORE_CASE
        ).find(prompt)
        if (calendarEvent != null) {
            val title = calendarEvent.groupValues[1].trim().ifBlank { "New event" }
            val begin = System.currentTimeMillis() + 60L * 60L * 1000L
            return try {
                context.startActivity(
                    Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                        .putExtra(CalendarContract.Events.TITLE, title)
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60L * 60L * 1000L)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                LocalCommandResult("Opening your calendar's event editor for “" + title + "”. Review the time and save it in Calendar.")
            } catch (_: Exception) {
                LocalCommandResult("I couldn't open a calendar app on this phone.")
            }
        }

        return null
    }

    fun restoreScheduledTasks(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean("morning_briefing_enabled", false)) {
            scheduleMorningBriefing(
                context,
                prefs.getInt("morning_hour", 7),
                prefs.getInt("morning_minute", 0),
                persist = false
            )
        }
        val reminders = runCatching { JSONArray(prefs.getString("daily_reminders", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until reminders.length()) {
            val row = runCatching { reminders.getJSONObject(i) }.getOrNull() ?: continue
            val hour = row.optInt("hour", -1)
            val minute = row.optInt("minute", -1)
            val task = row.optString("text").trim()
            if (hour in 0..23 && minute in 0..59 && task.isNotBlank()) {
                scheduleDailyReminder(context, hour, minute, task, persist = false)
            }
        }
    }

    private fun scheduleOnce(context: Context, delayMs: Long, task: String) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val id = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction("com.vanta.app.REMINDER_$id")
            .putExtra("title", "Argon reminder")
            .putExtra("text", task)
        val pending = PendingIntent.getBroadcast(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pending)
    }

    private fun scheduleMorningBriefing(context: Context, hour: Int, minute: Int, persist: Boolean = true) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (persist) {
            prefs.edit().putBoolean("morning_briefing_enabled", true)
                .putInt("morning_hour", hour).putInt("morning_minute", minute).apply()
        }
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(MORNING_ACTION)
            .putExtra("morning_briefing", true)
        val pending = PendingIntent.getBroadcast(
            context, MORNING_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setInexactRepeating(
            AlarmManager.RTC_WAKEUP, nextTrigger(hour, minute), AlarmManager.INTERVAL_DAY, pending
        )
    }

    private fun cancelMorningBriefing(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("morning_briefing_enabled", false).apply()
        val intent = Intent(context, ReminderReceiver::class.java).setAction(MORNING_ACTION)
        val pending = PendingIntent.getBroadcast(
            context, MORNING_ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending)
        pending.cancel()
    }

    private fun scheduleDailyReminder(
        context: Context,
        hour: Int,
        minute: Int,
        task: String,
        persist: Boolean = true
    ) {
        val cleanTask = task.trim().take(240)
        if (cleanTask.isBlank()) return
        val cleanHash = cleanTask.hashCode() and 0x7FFFFFFF
        val id = "daily_${hour}_${minute}_${cleanHash}"
        if (persist) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = runCatching { JSONArray(prefs.getString("daily_reminders", "[]")) }.getOrDefault(JSONArray())
            val next = JSONArray()
            for (i in 0 until existing.length()) {
                val row = runCatching { existing.getJSONObject(i) }.getOrNull() ?: continue
                if (row.optString("id") != id) next.put(row)
            }
            next.put(JSONObject().put("id", id).put("hour", hour).put("minute", minute).put("text", cleanTask))
            prefs.edit().putString("daily_reminders", next.toString()).apply()
        }
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction("com.vanta.app.$id")
            .putExtra("title", "Argon daily reminder")
            .putExtra("text", cleanTask)
        val pending = PendingIntent.getBroadcast(
            context, id.hashCode() and 0x7FFFFFFF, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).setInexactRepeating(
            AlarmManager.RTC_WAKEUP, nextTrigger(hour, minute), AlarmManager.INTERVAL_DAY, pending
        )
    }

    private fun nextTrigger(hour: Int, minute: Int): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    private fun parseTime(hourText: String, minuteText: String, ampmText: String): Pair<Int, Int>? {
        val rawHour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.ifBlank { "0" }.toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        val ampm = ampmText.lowercase(Locale.ROOT)
        val hour = when (ampm) {
            "am" -> if (rawHour in 1..12) rawHour % 12 else return null
            "pm" -> if (rawHour in 1..12) (rawHour % 12) + 12 else return null
            else -> if (rawHour in 0..23) rawHour else return null
        }
        return hour to minute
    }

    private fun formatTime(hour: Int, minute: Int): String {
        val ampm = if (hour < 12) "AM" else "PM"
        val displayHour = when (val value = hour % 12) { 0 -> 12; else -> value }
        return "%d:%02d %s".format(displayHour, minute, ampm)
    }
}
