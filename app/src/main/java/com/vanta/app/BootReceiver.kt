package com.vanta.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores scheduled routines only; boot never starts a microphone listener. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        VantaRoutineTools.restoreScheduledTasks(context)
    }
}
