package com.vanta.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** OAuth-backed Google Workspace and Notion actions. Run execute() on Dispatchers.IO. */
object VantaIntegrations {
    private val googleTaskCreate = Regex(
        """^(?:add|create|make)\s+(?:a\s+)?(?:google\s+task|task\s+to\s+google tasks)\s+(?:called\s+|named\s+)?(.+)$""",
        RegexOption.IGNORE_CASE
    )
    private val calendarCreate = Regex(
        """^(?:create|schedule|add)\s+(?:a\s+)?(?:google\s+)?calendar event\s+(.+?)\s+on\s+(\d{4}-\d{2}-\d{2})\s+from\s+(\d{1,2}:\d{2})\s+to\s+(\d{1,2}:\d{2})$""",
        RegexOption.IGNORE_CASE
    )
    private val inboxPhrases = listOf(
        "check my email", "check email", "check inbox", "unread emails", "unread email",
        "how many emails", "latest emails", "recent emails"
    )
    private val calendarPhrases = listOf(
        "today's calendar", "today calendar", "what's on my calendar",
        "what is on my calendar", "my schedule today", "upcoming events"
    )
    private val taskPhrases = listOf(
        "check my google tasks", "show my google tasks", "list google tasks",
        "list my google tasks", "what are my google tasks", "upcoming google tasks",
        "google tasks", "check google tasks"
    )

    fun isDataCommand(raw: String): Boolean {
        val p = raw.trim().lowercase(Locale.ROOT)
        return inboxPhrases.any { p == it || p.startsWith(it + " ") } ||
            calendarPhrases.any { p == it || p.startsWith(it + " ") } ||
            taskPhrases.any { p == it || p.startsWith(it + " ") } ||
            googleTaskCreate.matches(raw.trim()) ||
            calendarCreate.matches(raw.trim()) ||
            p.startsWith("create calendar event ") ||
            p.startsWith("schedule calendar event ") ||
            p.startsWith("add calendar event ") ||
            p.startsWith("send email to ") ||
            p.startsWith("reply to latest email") ||
            p.startsWith("reply to the latest email") ||
            p.startsWith("reply to last email") ||
            p.startsWith("reply to the last email") ||
            p.startsWith("check my notion") ||
            p.startsWith("notion tasks") ||
            p.startsWith("my notion tasks") ||
            p.startsWith("check notion tasks")
    }

    fun execute(context: Context, raw: String): String {
        val p = raw.trim().lowercase(Locale.ROOT)
        val google = GoogleWorkspaceClient(context)
        return when {
            googleTaskCreate.matches(raw.trim()) ->
                google.createTask(googleTaskCreate.matchEntire(raw.trim())!!.groupValues[1].trim())
            calendarCreate.matches(raw.trim()) -> google.createCalendarEvent(raw.trim())
            p.startsWith("create calendar event ") || p.startsWith("schedule calendar event ") || p.startsWith("add calendar event ") ->
                "Use: schedule calendar event Project meeting on 2026-10-12 from 15:00 to 16:00. Use 24-hour times."
            p.startsWith("send email to ") -> google.sendEmailCommand(raw.trim())
            p.startsWith("reply to latest email") || p.startsWith("reply to the latest email") ||
                p.startsWith("reply to last email") || p.startsWith("reply to the last email") ->
                google.replyToLatestCommand(raw.trim())
            inboxPhrases.any { p == it || p.startsWith(it + " ") } -> google.inboxSummary()
            calendarPhrases.any { p == it || p.startsWith(it + " ") } -> google.calendarSummary()
            taskPhrases.any { p == it || p.startsWith(it + " ") } -> google.tasksSummary()
            else -> NotionClient(context).tasksSummary()
        }
    }

    fun createGoogleTask(context: Context, title: String): String =
        GoogleWorkspaceClient(context).createTask(title)

    fun openGoogleConnect(context: Context) {
        context.startActivity(
            Intent(context, GoogleWorkspaceAuthActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun openNotionHelp(context: Context) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.notion.so/profile/integrations"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private class GoogleWorkspaceClient(context: Context) {
    private val store = VantaStore(context)
    private val token = store.loadGoogleAccessToken()
    private val apiKey = store.loadGoogleApiKey()
    private val formatter = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())

    private fun keyedUrl(url: String): String {
        if (apiKey.isBlank()) return url
        val separator = if (url.contains("?")) "&" else "?"
        return url + separator + "key=" + URLEncoder.encode(apiKey.trim(), "UTF-8")
    }

    private fun request(url: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        if (token.isBlank()) {
            throw IllegalStateException("Google isn't connected. Open Vanta Settings → Google Workspace and connect your Google account.")
        }
        val connection = URL(keyedUrl(url)).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 12000
        connection.readTimeout = 20000
        connection.setRequestProperty("Authorization", "Bearer " + token)
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status == 401) throw IllegalStateException("Google access token expired. Reconnect Google in Vanta Settings.")
            if (status == 403) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message").orEmpty() }
                    .getOrDefault("").take(160)
                throw IllegalStateException("Google denied this action. Reconnect and approve the required permission." +
                    if (detail.isBlank()) "" else " " + detail)
            }
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(response).optJSONObject("error")?.optString("message").orEmpty() }
                    .getOrDefault("").take(180)
                throw IllegalStateException("Google API HTTP " + status + if (detail.isBlank()) "." else ": " + detail)
            }
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }

    fun inboxSummary(): String {
        val list = request("https://gmail.googleapis.com/gmail/v1/users/me/messages?q=is%3Aunread&maxResults=10")
        val estimate = list.optInt("resultSizeEstimate", -1)
        if (estimate == 0) return "Gmail reports no unread messages."
        val messages = list.optJSONArray("messages") ?: JSONArray()
        val subjects = (0 until minOf(3, messages.length())).mapNotNull { i ->
            val id = messages.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val message = request(
                "https://gmail.googleapis.com/gmail/v1/users/me/messages/" + id +
                    "?format=metadata&metadataHeaders=Subject"
            )
            val headers = message.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
            (0 until headers.length()).mapNotNull { j ->
                headers.optJSONObject(j)?.takeIf { it.optString("name").equals("Subject", true) }
                    ?.optString("value")
            }.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "• " + it.take(100) }
        }
        return buildString {
            append("Gmail reports ").append(if (estimate >= 0) estimate else "at least " + messages.length())
                .append(" unread message(s).")
            if (subjects.isNotEmpty()) append("\nLatest subjects:\n").append(subjects.joinToString("\n"))
        }
    }

    fun calendarSummary(): String {
        val now = Instant.now()
        val end = now.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events?singleEvents=true" +
            "&orderBy=startTime&maxResults=15&timeMin=" + URLEncoder.encode(now.toString(), "UTF-8") +
            "&timeMax=" + URLEncoder.encode(end.toString(), "UTF-8")
        val events = request(url).optJSONArray("items") ?: JSONArray()
        if (events.length() == 0) return "No upcoming events were found in your primary Google Calendar today."
        return buildString {
            append("Your upcoming Google Calendar events:\n")
            for (i in 0 until events.length()) {
                val event = events.optJSONObject(i) ?: continue
                val start = event.optJSONObject("start")?.optString("dateTime").orEmpty()
                    .ifBlank { event.optJSONObject("start")?.optString("date").orEmpty() }
                val time = runCatching { formatter.format(Instant.parse(start)) }.getOrElse { start }
                append("• ").append(time).append(" — ").append(event.optString("summary", "Untitled event")).append('\n')
            }
        }.trim()
    }

    fun tasksSummary(): String {
        val tasks = request(
            "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks?showCompleted=false&maxResults=20"
        ).optJSONArray("items") ?: JSONArray()
        if (tasks.length() == 0) return "You have no open tasks in Google Tasks."
        val lines = (0 until tasks.length()).mapNotNull { i ->
            val task = tasks.optJSONObject(i) ?: return@mapNotNull null
            val title = task.optString("title").trim()
            if (title.isBlank()) return@mapNotNull null
            val due = task.optString("due").takeIf { it.isNotBlank() }?.let { " (due " + it.take(10) + ")" }.orEmpty()
            "• " + title + due
        }
        return if (lines.isEmpty()) "You have no open tasks in Google Tasks."
        else "Your Google Tasks:\n" + lines.joinToString("\n")
    }

    fun createTask(rawTitle: String): String {
        val title = rawTitle.trim().take(250)
        if (title.isBlank()) return "Tell me the title of the Google Task to add."
        request(
            "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks",
            "POST",
            JSONObject().put("title", title)
        )
        return "Added to Google Tasks: " + title
    }

    fun createCalendarEvent(raw: String): String {
        val pattern = Regex(
            """^(?:create|schedule|add)\s+(?:a\s+)?(?:google\s+)?calendar event\s+(.+?)\s+on\s+(\d{4}-\d{2}-\d{2})\s+from\s+(\d{1,2}:\d{2})\s+to\s+(\d{1,2}:\d{2})$""",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.matchEntire(raw) ?: return
            "Use: schedule calendar event Project meeting on 2026-10-12 from 15:00 to 16:00. Use 24-hour times."
        val title = match.groupValues[1].trim().take(180)
        return try {
            val date = LocalDate.parse(match.groupValues[2])
            val timeFormat = DateTimeFormatter.ofPattern("H:mm")
            val startTime = LocalTime.parse(match.groupValues[3], timeFormat)
            val endTime = LocalTime.parse(match.groupValues[4], timeFormat)
            if (!endTime.isAfter(startTime)) return "The event end time must be later than the start time on the same date."
            val zone = ZoneId.systemDefault()
            val start = ZonedDateTime.of(date, startTime, zone)
            val end = ZonedDateTime.of(date, endTime, zone)
            val event = JSONObject()
                .put("summary", title)
                .put("start", JSONObject().put("dateTime", start.toOffsetDateTime().toString()).put("timeZone", zone.id))
                .put("end", JSONObject().put("dateTime", end.toOffsetDateTime().toString()).put("timeZone", zone.id))
            request("https://www.googleapis.com/calendar/v3/calendars/primary/events", "POST", event)
            "Created Google Calendar event: " + title + " on " + date + " from " +
                match.groupValues[3] + " to " + match.groupValues[4] + "."
        } catch (error: Throwable) {
            "Could not create the event. Check the date and 24-hour times. " + (error.message ?: "")
        }
    }

    fun sendEmailCommand(raw: String): String {
        val match = VantaIntegrationsEmailParser.send(raw) ?: return
            "Use: send email to person@example.com subject Subject text body Message text."
        val to = match.first
        if (!Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""").matches(to)) {
            return "That email address does not look valid. Try the send command again with a valid address."
        }
        return sendMessage(to, match.second, match.third, null, null)
    }

    fun replyToLatestCommand(raw: String): String {
        val body = VantaIntegrationsEmailParser.reply(raw) ?: return
            "Use: reply to latest email with your message."
        val inbox = request("https://gmail.googleapis.com/gmail/v1/users/me/messages?q=in%3Ainbox&maxResults=1")
        val row = inbox.optJSONArray("messages")?.optJSONObject(0)
            ?: return "I could not find an email in your inbox to reply to."
        val id = row.optString("id").takeIf { it.isNotBlank() }
            ?: return "I could not identify the latest email to reply to."
        val message = request("https://gmail.googleapis.com/gmail/v1/users/me/messages/" + id + "?format=full")
        val headers = message.optJSONObject("payload")?.optJSONArray("headers") ?: JSONArray()
        fun header(name: String): String = (0 until headers.length()).mapNotNull { i ->
            headers.optJSONObject(i)?.takeIf { it.optString("name").equals(name, true) }?.optString("value")
        }.firstOrNull().orEmpty()
        val destinationHeader = header("Reply-To").ifBlank { header("From") }
        val destination = Regex("""<([^>]+)>""").find(destinationHeader)?.groupValues?.get(1)
            ?: destinationHeader.trim()
        val subject = header("Subject").replace("\r", " ").replace("\n", " ").take(180)
        val messageId = header("Message-ID").replace("\r", "").replace("\n", "").trim()
        val references = header("References").replace("\r", "").replace("\n", "").trim()
        val threadId = message.optString("threadId").takeIf { it.isNotBlank() }
        if (!Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""").matches(destination)) {
            return "The latest email did not contain a reply address Vanta could use."
        }
        val replySubject = if (subject.startsWith("re:", true)) subject else "Re: " + subject
        return sendMessage(destination, replySubject, body, threadId, Pair(messageId, references))
    }

    private fun sendMessage(
        to: String,
        subject: String,
        body: String,
        threadId: String?,
        replyHeaders: Pair<String, String>?
    ): String {
        val cleanTo = to.replace("\r", "").replace("\n", "").trim()
        val cleanSubject = subject.replace("\r", " ").replace("\n", " ").take(180)
        val mime = StringBuilder()
            .append("To: ").append(cleanTo).append("\r\n")
            .append("Subject: ").append(cleanSubject).append("\r\n")
        if (replyHeaders != null && replyHeaders.first.isNotBlank()) {
            mime.append("In-Reply-To: ").append(replyHeaders.first).append("\r\n")
            val refs = (replyHeaders.second + " " + replyHeaders.first).trim()
            mime.append("References: ").append(refs).append("\r\n")
        }
        mime.append("MIME-Version: 1.0\r\n")
            .append("Content-Type: text/plain; charset=UTF-8\r\n")
            .append("Content-Transfer-Encoding: 8bit\r\n\r\n")
            .append(body.trim())
        val encoded = Base64.encodeToString(
            mime.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val payload = JSONObject().put("raw", encoded)
        if (!threadId.isNullOrBlank()) payload.put("threadId", threadId)
        request("https://gmail.googleapis.com/gmail/v1/users/me/messages/send", "POST", payload)
        return if (replyHeaders == null) "Email sent to " + cleanTo + "."
        else "Reply sent to " + cleanTo + "."
    }
}

private object VantaIntegrationsEmailParser {
    private val sendPattern = Regex(
        """^send email to\s+(\S+)\s+subject\s+(.+?)\s+body\s+([\s\S]+)$""",
        RegexOption.IGNORE_CASE
    )
    private val replyPattern = Regex(
        """^reply to (?:the )?(?:latest|last) email with\s+([\s\S]+)$""",
        RegexOption.IGNORE_CASE
    )
    fun send(raw: String): Triple<String, String, String>? {
        val match = sendPattern.matchEntire(raw.trim()) ?: return null
        return Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }
    fun reply(raw: String): String? = replyPattern.matchEntire(raw.trim())?.groupValues?.get(1)?.trim()
}

private class NotionClient(context: Context) {
    private val store = VantaStore(context)

    fun tasksSummary(): String {
        val token = store.loadNotionToken()
        val databaseId = store.loadNotionDatabaseId().replace("-", "")
        if (token.isBlank() || databaseId.isBlank()) {
            return "Notion isn't configured. Open Vanta Settings → Integrations, save a Notion integration token and database ID, and share that database with your integration."
        }
        val c = (URL("https://api.notion.com/v1/databases/" + databaseId + "/query").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Authorization", "Bearer " + token)
            setRequestProperty("Notion-Version", "2022-06-28")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(JSONObject().put("page_size", 20).toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val raw = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) error("Notion denied access. Check the token and share the database with your integration.")
            if (code !in 200..299) {
                error("Notion API returned HTTP " + code + ": " +
                    runCatching { JSONObject(raw).optString("message") }.getOrDefault("").take(160))
            }
            val pages = JSONObject(raw).optJSONArray("results") ?: JSONArray()
            if (pages.length() == 0) return "Your connected Notion database has no pages to show."
            val lines = (0 until pages.length()).mapNotNull { i ->
                val props = pages.optJSONObject(i)?.optJSONObject("properties") ?: return@mapNotNull null
                val keys = props.keys()
                while (keys.hasNext()) {
                    val prop = props.optJSONObject(keys.next()) ?: continue
                    val title = prop.optJSONArray("title") ?: continue
                    val text = (0 until title.length()).mapNotNull { title.optJSONObject(it)?.optString("plain_text") }.joinToString("")
                    if (text.isNotBlank()) return@mapNotNull "• " + text.take(120)
                }
                null
            }
            return if (lines.isEmpty()) "I reached your Notion database, but could not find page title fields."
            else "Notion database pages (up to 20):\n" + lines.joinToString("\n")
        } finally {
            c.disconnect()
        }
    }
}
