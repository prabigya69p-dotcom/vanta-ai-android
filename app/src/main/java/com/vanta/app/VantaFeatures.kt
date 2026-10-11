package com.vanta.app

import android.provider.AlarmClock
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.net.URLEncoder
import org.json.JSONArray

enum class VantaMode(val label: String, val instruction: String) {
    SMART("Smart", "Decide the best response strategy yourself. Stay concise unless the task needs depth."),
    RESEARCH("Research", "Research carefully, prefer current information, separate facts from uncertainty, and cite sources when available."),
    STUDY("Study", "Teach step by step at the user's level. Explain why, show worked examples, and end with a compact recap."),
    CREATE("Create", "Be highly creative and practical. Produce polished copy, plans, designs, or generated-media prompts."),
    DEVELOPER("Developer", "Act like a senior software engineer. Give implementation-ready solutions, diagnose errors, and preserve existing architecture."),
    MISSION("Mission", "Treat the request as a goal. Break it into milestones, identify blockers, execute concrete steps, and report progress."),
    FOCUS("Focus", "Keep responses distraction-free and action-oriented. Favor one next step and a compact plan."),
    FACT_CHECK("Fact-check", "Challenge assumptions, verify claims when research is enabled, and explicitly distinguish confirmed facts from uncertainty."),
    COMPARE("Compare", "Compare options using clear criteria, trade-offs, and a decisive recommendation when evidence supports one.")
}

data class LocalCommandResult(
    val reply: String,
    val shouldOpenSettings: Boolean = false
)

object VantaSmart {
    fun modeFor(prompt: String, selected: VantaMode): VantaMode {
        if (selected != VantaMode.SMART) return selected
        val p = prompt.lowercase(Locale.getDefault())
        return when {
            listOf("code", "kotlin", "android", "github", "bug", "error", "gradle", "api").any(p::contains) -> VantaMode.DEVELOPER
            listOf("homework", "study", "explain", "math", "exam", "chapter", "learn").any(p::contains) -> VantaMode.STUDY
            listOf("compare", "versus", " vs ", "difference between").any(p::contains) -> VantaMode.COMPARE
            listOf("fact check", "is it true", "verify", "real or fake").any(p::contains) -> VantaMode.FACT_CHECK
            listOf("plan", "build", "make an app", "project", "goal", "mission").any(p::contains) -> VantaMode.MISSION
            listOf("write", "design", "logo", "story", "creative", "image", "wallpaper").any(p::contains) -> VantaMode.CREATE
            listOf("focus", "study with me", "no distractions").any(p::contains) -> VantaMode.FOCUS
            else -> VantaMode.SMART
        }
    }

    fun shouldResearch(prompt: String, selected: VantaMode): Boolean {
        if (selected == VantaMode.RESEARCH || selected == VantaMode.FACT_CHECK || selected == VantaMode.COMPARE) return true
        val p = prompt.lowercase(Locale.getDefault())
        return listOf("latest", "today", "current", "news", "search", "look up", "on the internet", "price", "weather", "score").any(p::contains)
    }

    fun instructions(mode: VantaMode, personality: String, project: String, memories: List<String>): String {
        val memoryText = memories.joinToString("\n") { "- " + it }.ifBlank { "- No saved memories yet." }
        return (
            "You are Argon, the AI assistant inside the Vanta Android app. The app is named Vanta; your assistant name is Argon.\n" +
            "Personality: " + personality.ifBlank { "direct, warm, futuristic, honest" } + ".\n" +
            "Active project/space: " + project.ifBlank { "General" } + ".\n" +
            "Mode: " + mode.label + ".\n" +
            "Mode guidance: " + mode.instruction + "\n" +
            "Saved user memories are context, not facts to invent:\n" +
            memoryText +
            "\nNever claim an action happened when you could not perform it. Be direct and transparent."
        )
    }
}

class VantaMemoryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("vanta_memory", Context.MODE_PRIVATE)

    fun list(): List<String> {
        val raw = prefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun remember(value: String) {
        val clean = value.trim().take(240)
        if (clean.isBlank()) return
        val next = (list().filterNot { it.equals(clean, ignoreCase = true) } + clean).takeLast(30)
        val arr = JSONArray()
        next.forEach(arr::put)
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun forget(value: String) {
        val clean = value.trim()
        val next = list().filterNot { it.contains(clean, ignoreCase = true) }
        val arr = JSONArray()
        next.forEach(arr::put)
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun clear() = prefs.edit().remove("items").apply()
}

object VantaLocalCommandRouter {
    fun execute(context: Context, prompt: String, memory: VantaMemoryStore): LocalCommandResult? {
        val p = prompt.trim()
        val lower = p.lowercase(Locale.getDefault())

        VantaRoutineTools.execute(context, p)?.let { return it }

        if (lower == "open settings" || lower == "open vanta settings") {
            return LocalCommandResult("Opening Vanta settings.", shouldOpenSettings = true)
        }

        if (lower.startsWith("remember ")) {
            val value = p.substringAfter("remember ").trim()
            memory.remember(value)
            return LocalCommandResult("Saved to memory: " + value)
        }

        if (lower.startsWith("forget ")) {
            memory.forget(p.substringAfter("forget ").trim())
            return LocalCommandResult("Removed matching memory entries.")
        }

        if (lower.contains("what do you remember") || lower == "show memory" || lower == "my memories") {
            val items = memory.list()
            return LocalCommandResult(
                if (items.isEmpty()) "I don't have any saved memories yet."
                else "Here's what I remember:\n" + items.joinToString("\n") { "• " + it }
            )
        }

        if (lower == "clear memory" || lower == "forget everything") {
            memory.clear()
            return LocalCommandResult("Cleared Argon's saved memory.")
        }

        val timer = Regex("""(?:set|start)?\s*(?:a\s*)?timer\s+(?:for\s+)?(\d+)\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)""", RegexOption.IGNORE_CASE).find(p)
        if (timer != null) {
            val amount = timer.groupValues[1].toInt()
            val unit = timer.groupValues[2].lowercase(Locale.getDefault())
            val seconds = when {
                unit.startsWith("hour") || unit.startsWith("hr") -> amount * 3600
                unit.startsWith("min") -> amount * 60
                else -> amount
            }
            return try {
                context.startActivity(
                    Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Argon timer")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                LocalCommandResult("Timer ready for " + amount + " " + timer.groupValues[2] + ".")
            } catch (_: Throwable) {
                LocalCommandResult("I couldn't open the system timer on this phone.")
            }
        }

        val alarm = Regex("""(?:set|create)?\s*(?:an?\s*)?alarm\s+(?:for\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""", RegexOption.IGNORE_CASE).find(p)
        if (alarm != null) {
            val hourRaw = alarm.groupValues[1].toInt()
            val minute = alarm.groupValues[2].ifBlank { "0" }.toInt()
            val ampm = alarm.groupValues[3]
            val hour = if (ampm.isBlank()) hourRaw else {
                when {
                    ampm.equals("am", true) -> if (hourRaw == 12) 0 else hourRaw
                    else -> if (hourRaw == 12) 12 else hourRaw + 12
                }
            }
            return try {
                context.startActivity(
                    Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                        .putExtra(AlarmClock.EXTRA_MESSAGE, "Argon alarm")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                LocalCommandResult("Alarm setup opened for %02d:%02d.".format(hour, minute))
            } catch (_: Throwable) {
                LocalCommandResult("I couldn't open the system alarm app.")
            }
        }

        val searchQuery = Regex("""^(?:please\s+)?(?:search(?:\s+for)?|google)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(p)?.groupValues?.get(1)?.trim()
        if (!searchQuery.isNullOrBlank()) {
            return try {
                val encoded = URLEncoder.encode(searchQuery, "UTF-8")
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + encoded))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                LocalCommandResult("Searching the web for " + searchQuery + ".")
            } catch (_: Throwable) {
                LocalCommandResult("I couldn't open a browser for that search.")
            }
        }

        val openTarget = Regex("""^(?:please\s+)?(?:open|launch|start)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(p)?.groupValues?.get(1)?.trim()
        if (openTarget != null) {
            val target = openTarget.lowercase(Locale.getDefault()).removeSuffix(" app").trim()
            val aliases = mapOf(
                "chrome" to listOf("com.android.chrome"), "browser" to listOf("com.android.chrome", "com.sec.android.app.sbrowser"),
                "google" to listOf("com.google.android.googlequicksearchbox", "com.android.chrome"),
                "youtube" to listOf("com.google.android.youtube"), "maps" to listOf("com.google.android.apps.maps"),
                "calendar" to listOf("com.google.android.calendar", "com.samsung.android.calendar"),
                "camera" to listOf("com.android.camera", "com.sec.android.app.camera"),
                "settings" to listOf("com.android.settings"), "gmail" to listOf("com.google.android.gm"),
                "whatsapp" to listOf("com.whatsapp"), "instagram" to listOf("com.instagram.android"),
                "facebook" to listOf("com.facebook.katana"), "tiktok" to listOf("com.zhiliaoapp.musically"),
                "spotify" to listOf("com.spotify.music"), "roblox" to listOf("com.roblox.client"),
                "notion" to listOf("notion.id"),
                "play store" to listOf("com.android.vending"), "phone" to listOf("com.google.android.dialer", "com.samsung.android.dialer"),
                "dialer" to listOf("com.google.android.dialer", "com.samsung.android.dialer"),
                "messages" to listOf("com.google.android.apps.messaging", "com.samsung.android.messaging"),
                "contacts" to listOf("com.google.android.contacts", "com.samsung.android.app.contacts"),
                "clock" to listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage"),
                "calculator" to listOf("com.google.android.calculator", "com.sec.android.app.popupcalculator"),
                "files" to listOf("com.google.android.documentsui", "com.sec.android.app.myfiles"),
                "gallery" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
                "photos" to listOf("com.google.android.apps.photos"), "music" to listOf("com.spotify.music")
            )
            val packageManager = context.packageManager
            val candidatePackages = aliases[target].orEmpty()
            for (packageName in candidatePackages) {
                val launch = packageManager.getLaunchIntentForPackage(packageName)
                if (launch != null) {
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return LocalCommandResult("Opening " + openTarget + ".")
                }
            }
            val installedMatch = runCatching {
                packageManager.getInstalledApplications(0).firstOrNull { app ->
                    packageManager.getApplicationLabel(app).toString().equals(target, ignoreCase = true) ||
                        packageManager.getApplicationLabel(app).toString().contains(target, ignoreCase = true)
                }
            }.getOrNull()
            if (installedMatch != null) {
                val launch = packageManager.getLaunchIntentForPackage(installedMatch.packageName)
                if (launch != null) {
                    context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return LocalCommandResult("Opening " + packageManager.getApplicationLabel(installedMatch) + ".")
                }
            }
            return LocalCommandResult("I couldn't find an installed app called " + openTarget + ".")
        }

        if (lower == "what time is it" || lower == "current time" || lower == "time") {
            val zone = TimeZone.getDefault()
            val fmt = SimpleDateFormat("EEEE, h:mm a", Locale.getDefault()).apply { timeZone = zone }
            return LocalCommandResult("It's " + fmt.format(Date()) + ".")
        }

        val calc = Regex("""^\s*calculate\s+([0-9.]+)\s*([+\-*/])\s*([0-9.]+)\s*$""").find(p)
        if (calc != null) {
            val a = calc.groupValues[1].toDouble()
            val b = calc.groupValues[3].toDouble()
            val result = when (calc.groupValues[2]) {
                "+" -> a + b
                "-" -> a - b
                "*" -> a * b
                "/" -> if (b == 0.0) null else a / b
                else -> null
            }
            if (result != null) return LocalCommandResult("Result: " + result)
        }

        if (lower == "play music" || lower == "pause music" || lower == "next song") {
            return LocalCommandResult("Music control requested. Your active media app may handle it.")
        }

        return null
    }
}