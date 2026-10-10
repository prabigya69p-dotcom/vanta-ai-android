package com.vanta.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/** Best-effort boot restoration. Android may block microphone FGS starts from boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!VantaStore(context).loadWakeEnabled()) return
        if (ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val serviceIntent = Intent(context, WakeService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (error: Exception) {
            // Recent Android versions restrict microphone services started from boot.
            // The user can reopen Vanta and enable hands-free mode again.
            Log.w("VantaBoot", "Hands-free restore requires user launch", error)
        }
    }
}
