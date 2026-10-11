package com.vanta.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Google Workspace API configuration. Add the project API key here when it is supplied.
 * Private Gmail/Calendar/Tasks data still requires user-consented OAuth authorization.
 */
object GoogleWorkspaceApi {
    const val API_KEY: String = ""
    private fun keyedUrl(url: String, apiKey: String): String {
        if (apiKey.isBlank()) return url
        val separator = if (url.contains("?")) "&" else "?"
        return url + separator + "key=" + URLEncoder.encode(apiKey.trim(), "UTF-8")
    }

    private fun request(url: String, token: String, apiKey: String): JSONObject {
        if (token.isBlank()) throw IOException("Connect Google Workspace in VANTA Settings first.")
        val c = URL(keyedUrl(url, apiKey)).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Bearer " + token)
        c.setRequestProperty("Accept", "application/json")
        try {
            val status = c.responseCode
            val body = (if (status in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status == 401 || status == 403) {
                throw IOException("Google access expired or permission is missing. Reconnect Google Workspace in VANTA Settings.")
            }
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }
                    .getOrDefault("").take(180)
                throw IOException("Google API HTTP " + status + if (detail.isBlank()) "." else ": " + detail)
            }
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    fun unreadSummary(token: String, apiKey: String = ""): String {
        val unread = request("https://gmail.googleapis.com/gmail/v1/users/me/labels/INBOX", token, apiKey)
            .optInt("messagesUnread", -1)
        if (unread < 0) throw IOException("Gmail did not return an unread count.")
        return "Your Gmail inbox has " + unread + " unread message(s)."
    }

    fun calendarSummary(token: String, apiKey: String = ""): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val now = URLEncoder.encode(fmt.format(Date()), "UTF-8")
        val json = request(
            "https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin=" + now +
                "&maxResults=8&singleEvents=true&orderBy=startTime",
            token, apiKey
        )
        val events = json.optJSONArray("items")
        if (events == null || events.length() == 0) return "You have no upcoming events in your primary Google Calendar."
        val lines = mutableListOf<String>()
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val start = event.optJSONObject("start")
            val whenText = start?.optString("dateTime")?.takeIf { it.isNotBlank() }
                ?: start?.optString("date")?.takeIf { it.isNotBlank() }
                ?: "time not specified"
            lines += "• " + event.optString("summary", "Untitled event") + " — " + whenText
        }
        return if (lines.isEmpty()) "No upcoming events found."
        else "Your upcoming Google Calendar events:\n" + lines.joinToString("\n")
    }

    fun tasksSummary(token: String, apiKey: String = ""): String {
        val json = request(
            "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks?showCompleted=false&maxResults=20",
            token, apiKey
        )
        val tasks = json.optJSONArray("items")
        if (tasks == null || tasks.length() == 0) return "You have no open tasks in Google Tasks."
        val lines = mutableListOf<String>()
        for (i in 0 until tasks.length()) {
            val task = tasks.optJSONObject(i) ?: continue
            val title = task.optString("title").trim()
            if (title.isNotBlank()) {
                val due = task.optString("due").takeIf { it.isNotBlank() }
                    ?.let { " (due " + it.take(10) + ")" }.orEmpty()
                lines += "• " + title + due
            }
        }
        return if (lines.isEmpty()) "You have no open tasks in Google Tasks."
        else "Your Google Tasks:\n" + lines.joinToString("\n")
    }

    fun summary(token: String, apiKey: String = ""): String =
        unreadSummary(token, apiKey) + "\n\n" +
            calendarSummary(token, apiKey) + "\n\n" +
            tasksSummary(token, apiKey)
}
