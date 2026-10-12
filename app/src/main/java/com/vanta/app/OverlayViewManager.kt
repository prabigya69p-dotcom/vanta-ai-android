package com.vanta.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Small, non-focusable status pill shown when the local wake word is detected. */
class OverlayViewManager(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val hideRunnable = Runnable { removeViewNow() }

    fun show(status: String = "Argon heard you") {
        mainHandler.post {
            if (!Settings.canDrawOverlays(appContext)) return@post
            var label = view
            if (label == null) {
                label = createView()
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = 20
                    y = 120
                }
                try {
                    windowManager.addView(label, params)
                    view = label
                } catch (_: SecurityException) {
                    return@post
                } catch (_: WindowManager.BadTokenException) {
                    return@post
                }
            }
            label.text = status
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, 2800L)
        }
    }

    fun updateStatus(status: String) {
        mainHandler.post { view?.text = status }
    }

    fun hide() {
        mainHandler.post {
            mainHandler.removeCallbacks(hideRunnable)
            removeViewNow()
        }
    }

    private fun createView() = TextView(appContext).apply {
        text = "Argon heard you"
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(24, 15, 24, 15)
        background = GradientDrawable().apply {
            setColor(Color.rgb(13, 31, 53))
            cornerRadius = 48f
            setStroke(2, Color.rgb(70, 166, 255))
        }
        elevation = 12f
    }

    private fun removeViewNow() {
        val old = view ?: return
        view = null
        runCatching { windowManager.removeView(old) }
    }
}
