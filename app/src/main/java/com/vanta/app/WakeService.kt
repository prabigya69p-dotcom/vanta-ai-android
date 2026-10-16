package com.vanta.app

import android.content.Context
import android.util.Log

/**
 * Hands-free wake-word listening has been retired. This compatibility facade
 * remains so existing UI and audio-call sites compile, but it never starts a
 * background microphone service. Tap-to-talk remains handled by the UI.
 */
object WakeService {
    private const val TAG = "VantaWakeService"

    fun start(context: Context) {
        Log.i(TAG, "Hands-free wake-word mode is disabled; use tap-to-talk")
    }

    fun stop(context: Context) = Unit
    fun pause(context: Context) = Unit
    fun beginUserRecording(context: Context) = Unit
    fun processing(context: Context) = Unit
    fun resume(context: Context) = Unit
}
