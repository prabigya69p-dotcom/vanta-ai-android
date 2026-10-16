package com.vanta.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Keeps the floating orb independent from the chat activity and microphone service. */
class FloatingOrbService : Service() {
    private var overlay: OverlayViewManager? = null
    override fun onCreate() { super.onCreate(); overlay = OverlayViewManager(this) { stopSelf() } }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) { overlay?.hide(); stopSelf(); return START_NOT_STICKY }
        overlay?.showOrb()
        return START_NOT_STICKY
    }
    override fun onDestroy() { overlay?.hide(); overlay = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object { const val ACTION_SHOW = "com.vanta.app.orb.SHOW"; const val ACTION_HIDE = "com.vanta.app.orb.HIDE" }
}
