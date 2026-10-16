package com.vanta.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.net.Uri

/** Separate launcher icon so Niagara can assign its double-tap action directly to orb mode. */
class OrbLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            startService(Intent(this, FloatingOrbService::class.java).setAction(FloatingOrbService.ACTION_SHOW))
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        finish()
    }
}
