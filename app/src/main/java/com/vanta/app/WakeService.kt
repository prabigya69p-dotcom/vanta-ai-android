package com.vanta.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/** Compatibility facade for existing VANTA UI and boot code. */
object WakeService {
    private const val TAG = "VantaWakeService"

    fun start(context: Context) = dispatch(context, AssistantForegroundService.ACTION_START)

    fun stop(context: Context) {
        val app = context.applicationContext
        app.stopService(Intent(app, AssistantForegroundService::class.java))
    }

    fun pause(context: Context) {
        if (!VantaStore(context).loadWakeEnabled()) return
        val current = AssistantForegroundService.instance
        if (current != null) current.pauseImmediately(AudioStateManager.SPEAKING)
        else dispatch(context, AssistantForegroundService.ACTION_PAUSE)
    }

    fun beginUserRecording(context: Context) {
        if (!VantaStore(context).loadWakeEnabled()) return
        val current = AssistantForegroundService.instance
        if (current != null) current.pauseImmediately(AudioStateManager.RECORDING_USER)
        else dispatch(context, AssistantForegroundService.ACTION_RECORDING)
    }

    fun processing(context: Context) {
        if (!VantaStore(context).loadWakeEnabled()) return
        val current = AssistantForegroundService.instance
        if (current != null) current.processingImmediately()
        else dispatch(context, AssistantForegroundService.ACTION_PROCESSING)
    }

    fun resume(context: Context) {
        if (!VantaStore(context).loadWakeEnabled()) return
        val current = AssistantForegroundService.instance
        if (current != null) current.resumeImmediately()
        else dispatch(context, AssistantForegroundService.ACTION_RESUME)
    }

    private fun dispatch(context: Context, action: String) {
        val app = context.applicationContext
        val intent = Intent(app, AssistantForegroundService::class.java).setAction(action)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(app, intent)
            } else {
                app.startService(intent)
            }
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not control hands-free service: " + (failure.message ?: "unknown error"))
        }
    }
}
