package com.vanta.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val isMorningBriefing = intent.getBooleanExtra("morning_briefing", false)
        val title = if (isMorningBriefing) {
            "Argon morning briefing"
        } else {
            intent.getStringExtra("title") ?: "VANTA reminder"
        }
        val text = if (isMorningBriefing) {
            NotificationDigestService.buildBriefing(context)
        } else {
            intent.getStringExtra("text") ?: "You asked VANTA to remind you."
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel("vanta_reminders", "VANTA reminders", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        manager.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            NotificationCompat.Builder(context, "vanta_reminders")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .build()
        )
    }
}