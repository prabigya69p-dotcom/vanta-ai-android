package com.vanta.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Floating assistant UI. The small orb stays above other apps; its panel can be collapsed for call/chat mode. */
class OverlayViewManager(context: Context, private val onClose: (() -> Unit)? = null) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null
    private var orbRoot: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var expanded = false
    private val hideRunnable = Runnable { removeStatusView() }

    fun show(status: String = "Argon heard you", onVisible: ((Boolean) -> Unit)? = null) {
        mainHandler.post {
            if (!Settings.canDrawOverlays(appContext)) { onVisible?.invoke(false); return@post }
            var label = statusView
            if (label == null) {
                label = TextView(appContext).apply { textSize = 14f; setTextColor(Color.WHITE); setPadding(24, 15, 24, 15); background = pill(Color.rgb(13, 31, 53)); elevation = 12f }
                val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.END; x = 20; y = 120 }
                try { windowManager.addView(label, params); statusView = label } catch (_: RuntimeException) { onVisible?.invoke(false); return@post }
            }
            label.text = status
            onVisible?.invoke(true)
            mainHandler.removeCallbacks(hideRunnable)
            mainHandler.postDelayed(hideRunnable, 2800L)
        }
    }

    fun showOrb() {
        mainHandler.post {
            if (!Settings.canDrawOverlays(appContext)) return@post
            if (orbRoot != null) { setExpanded(false); return@post }
            val root = LinearLayout(appContext).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val actions = LinearLayout(appContext).apply {
                orientation = LinearLayout.VERTICAL; setPadding(14, 10, 14, 10); background = pill(Color.rgb(12, 27, 45)); visibility = View.GONE
            }
            panel = actions
            actions.addView(label("VANTA is ready", 14f, true))
            actions.addView(action("Open VANTA chat") { openChat() })
            actions.addView(action("Minimize · call mode") { setExpanded(false) })
            actions.addView(action("Close orb") { hide(); onClose?.invoke() })
            val orb = TextView(appContext).apply {
                text = "V"; textSize = 25f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(18, 68, 112)); setStroke(3, Color.rgb(83, 184, 255)) }
                elevation = 18f; contentDescription = "VANTA floating orb. Tap to expand controls"
                setOnClickListener { setExpanded(!expanded) }
            }
            val size = (56 * appContext.resources.displayMetrics.density).toInt()
            root.addView(actions, LinearLayout.LayoutParams(190.dp(), LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8.dp() })
            root.addView(orb, LinearLayout.LayoutParams(size, size))
            val params = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.END; x = 12.dp(); y = 150.dp() }
            try { windowManager.addView(root, params); orbRoot = root; setExpanded(false) } catch (_: RuntimeException) { orbRoot = null; panel = null }
        }
    }

    fun updateStatus(status: String) { mainHandler.post { statusView?.text = status } }
    fun hide() { mainHandler.post { mainHandler.removeCallbacks(hideRunnable); removeStatusView(); removeOrb() } }

    private fun setExpanded(value: Boolean) {
        expanded = value
        panel?.visibility = if (value) View.VISIBLE else View.GONE
    }
    private fun openChat() {
        val intent = Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        appContext.startActivity(intent)
        setExpanded(false)
    }
    private fun action(text: String, click: () -> Unit) = label(text, 13f, false).apply {
        setPadding(8.dp(), 10.dp(), 8.dp(), 10.dp())
        setOnClickListener { click() }
        isClickable = true
    }
    private fun label(text: String, size: Float, bold: Boolean) = TextView(appContext).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE); setPadding(6.dp(), 6.dp(), 6.dp(), 6.dp())
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = 22.dp().toFloat(); setStroke(2, Color.rgb(70, 166, 255)) }
    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
    private fun Int.dp(): Int = (this * appContext.resources.displayMetrics.density).toInt()
    private fun removeStatusView() { val old = statusView ?: return; statusView = null; runCatching { windowManager.removeView(old) } }
    private fun removeOrb() { val old = orbRoot ?: return; orbRoot = null; panel = null; runCatching { windowManager.removeView(old) } }
}
