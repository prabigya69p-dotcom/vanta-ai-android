package com.vanta.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Locale

/** User-enabled, opt-in voice navigation. It never auto-enables itself. */
class ArgonAccessibilityService : AccessibilityService() {
    private var speech: TextToSpeech? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
        speech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) speech?.language = Locale.getDefault()
        }
    }

    private fun speakResult(message: String) {
        val engine = speech
        if (engine != null) {
            engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "argon_accessibility_action")
        } else {
            @Suppress("DEPRECATION")
            announceForAccessibility(message)
        }
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (active === this) active = null
        speech?.stop()
        speech?.shutdown()
        speech = null
        super.onDestroy()
    }

    private fun performVoiceCommand(raw: String): String {
        val command = raw.trim()
        val normalized = command.lowercase(Locale.ROOT)
        return when {
            normalized in setOf("go back", "press back", "back") ->
                if (performGlobalAction(GLOBAL_ACTION_BACK)) "Going back." else "I couldn't go back on this screen."
            normalized in setOf("go home", "home screen", "go to home screen") ->
                if (performGlobalAction(GLOBAL_ACTION_HOME)) "Going to the home screen." else "I couldn't open the home screen."
            normalized in setOf("open notifications", "show notifications", "notifications") ->
                if (performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)) "Opening notifications." else "I couldn't open notifications."
            normalized in setOf("open quick settings", "show quick settings", "quick settings") ->
                if (performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) "Opening quick settings." else "I couldn't open quick settings."
            normalized in setOf("scroll down", "scroll up") -> scroll(normalized == "scroll down")
            normalized.startsWith("tap ") || normalized.startsWith("click ") -> {
                val target = command.substringAfter(' ').trim()
                clickVisibleLabel(target)
            }
            normalized.startsWith("type ") || normalized.startsWith("enter text ") -> {
                val text = if (normalized.startsWith("type ")) command.substring(5).trim() else command.substring(11).trim()
                typeIntoFocusedField(text)
            }
            else -> ""
        }
    }

    private fun scroll(down: Boolean): String {
        val root = rootInActiveWindow ?: return "I can't access the current screen."
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val node = findScrollable(root)
        return if (node?.performAction(action) == true) {
            if (down) "Scrolling down." else "Scrolling up."
        } else "I couldn't find a scrollable area."
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser && node.isEnabled) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val found = findScrollable(child)
            if (found != null) return found
        }
        return null
    }

    private fun clickVisibleLabel(target: String): String {
        if (target.isBlank()) return "Say tap followed by the visible button or label."
        val unsafe = listOf("delete", "send", "pay", "purchase", "buy", "transfer", "submit", "confirm", "remove", "unsubscribe", "place order", "log out", "logout")
        if (unsafe.any { target.lowercase(Locale.ROOT).contains(it) }) {
            return "For safety, I won't activate a potentially destructive or consequential button by voice. Please tap it yourself."
        }
        val root = rootInActiveWindow ?: return "I can't access the current screen."
        val matches = root.findAccessibilityNodeInfosByText(target).orEmpty()
        for (match in matches) {
            if (!match.isVisibleToUser || !match.isEnabled) continue
            var candidate: AccessibilityNodeInfo? = match
            while (candidate != null) {
                if (candidate.isVisibleToUser && candidate.isEnabled && candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return "Tapped $target."
                }
                candidate = candidate.parent
            }
        }
        return "I couldn't find a clickable item labelled $target."
    }

    private fun typeIntoFocusedField(text: String): String {
        if (text.isBlank()) return "Say type followed by the text you want entered."
        if (text.length > 500) return "For safety, voice entry is limited to 500 characters at a time."
        val root = rootInActiveWindow ?: return "I can't access the current screen."
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "Tap a text field first, then say type followed by your text."
        if (!focused.isEditable || focused.isPassword) return "I can only type into a focused, non-password text field."
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            "Text entered. I did not submit it."
        } else "I couldn't enter text in that field."
    }

    companion object {
        @Volatile private var active: ArgonAccessibilityService? = null

        /** Run in-place from WakeService before opening Vanta, preserving the target app window. */
        fun handleWakeCommand(context: Context, raw: String): Boolean {
            val service = active ?: return false
            if (!isAccessibilityCommand(raw)) return false
            val message = runCatching { service.performVoiceCommand(raw) }
                .getOrElse { "That accessibility action failed. You can still use the screen normally." }
                .ifBlank { "I couldn't perform that screen action." }
            service.speakResult(message)
            return true
        }

        private fun isAccessibilityCommand(raw: String): Boolean {
            val normalized = raw.trim().lowercase(Locale.ROOT)
            return normalized in setOf(
                "go back", "press back", "back", "go home", "home screen", "go to home screen",
                "open notifications", "show notifications", "notifications", "open quick settings",
                "show quick settings", "quick settings", "scroll down", "scroll up"
            ) || normalized.startsWith("tap ") || normalized.startsWith("click ") ||
                normalized.startsWith("type ") || normalized.startsWith("enter text ")
        }

        /** Returns null only when the spoken phrase is not an accessibility command. */
        fun executeVoiceCommand(context: Context, raw: String): String? {
            val recognized = isAccessibilityCommand(raw)
            if (!recognized) return null
            val service = active
            if (service == null) {
                return "To use Argon screen controls, open Vanta Settings, choose Accessibility controls, then enable Argon in Android Accessibility settings."
            }
            return runCatching { service.performVoiceCommand(raw) }
                .getOrElse { "That accessibility action failed. You can still use the screen normally." }
                .ifBlank { "I couldn't perform that screen action." }
        }

        fun openAccessibilitySettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
