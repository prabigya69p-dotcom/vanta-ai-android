package com.vanta.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** User-consented Google Workspace and Notion API integrations. Run execute() on Dispatchers.IO. */
object VantaIntegrations {
    fun isDataCommand(raw: String): Boolean {
        val p=raw.trim().lowercase(Locale.ROOT)
        return listOf("check my email","check email","check inbox","unread emails","unread email","how many emails","latest emails","recent emails","today's calendar","today calendar","what's on my calendar","what is on my calendar","my schedule today","upcoming events","check my notion","notion tasks","my notion tasks","check notion tasks").any { p==it || p.startsWith("$it ") }
    }
    fun execute(context: Context, raw: String): String {
        val p=raw.trim().lowercase(Locale.ROOT)
        return when {
            listOf("check my email","check email","check inbox","unread emails","unread email","how many emails","latest emails","recent emails").any { p==it || p.startsWith("$it ") } -> GoogleWorkspaceClient(context).inboxSummary()
            listOf("today's calendar","today calendar","what's on my calendar","what is on my calendar","my schedule today","upcoming events").any { p==it || p.startsWith("$it ") } -> GoogleWorkspaceClient(context).calendarSummary()
            else -> NotionClient(context).tasksSummary()
        }
    }
    fun openGoogleConnect(context: Context) {
        context.startActivity(Intent(context,GoogleWorkspaceAuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun openNotionHelp(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.notion.so/profile/integrations")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private class GoogleWorkspaceClient(context: Context) {
    private val token=VantaStore(context).loadGoogleAccessToken()
    private val formatter=DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
    fun inboxSummary(): String {
        if(token.isBlank()) return "Google isn't connected. Say 'connect Google account' or open Vanta Settings → Integrations."
        val result=get("https://gmail.googleapis.com/gmail/v1/users/me/messages?q=is%3Aunread&maxResults=10")
        val estimate=result.optInt("resultSizeEstimate",-1)
        if(estimate==0) return "Gmail reports no unread messages."
        val messages=result.optJSONArray("messages")?:JSONArray()
        val subjects=(0 until minOf(3,messages.length())).mapNotNull { i ->
            val id=messages.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?:return@mapNotNull null
            val msg=get("https://gmail.googleapis.com/gmail/v1/users/me/messages/$id?format=metadata&metadataHeaders=Subject")
            val headers=msg.optJSONObject("payload")?.optJSONArray("headers")?:JSONArray()
            (0 until headers.length()).mapNotNull { j -> headers.optJSONObject(j)?.takeIf { it.optString("name").equals("Subject",true) }?.optString("value") }.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "• "+it.take(100) }
        }
        return buildString {
            append("Gmail reports ").append(if(estimate>=0) estimate else "at least ${messages.length()}").append(" unread message(s).")
            if(subjects.isNotEmpty()) append("\nLatest subjects:\n").append(subjects.joinToString("\n"))
        }
    }
    fun calendarSummary(): String {
        if(token.isBlank()) return "Google isn't connected. Say 'connect Google account' or open Vanta Settings → Integrations."
        val now=Instant.now()
        val end=now.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val url="https://www.googleapis.com/calendar/v3/calendars/primary/events?singleEvents=true&orderBy=startTime&maxResults=15&timeMin="+URLEncoder.encode(now.toString(),"UTF-8")+"&timeMax="+URLEncoder.encode(end.toString(),"UTF-8")
        val events=get(url).optJSONArray("items")?:JSONArray()
        if(events.length()==0) return "No upcoming events were found in your primary Google Calendar today."
        return buildString {
            append("Your upcoming Google Calendar events:\n")
            for(i in 0 until events.length()) {
                val e=events.optJSONObject(i)?:continue
                val start=e.optJSONObject("start")?.optString("dateTime").orEmpty().ifBlank { e.optJSONObject("start")?.optString("date").orEmpty() }
                val time=runCatching { formatter.format(Instant.parse(start)) }.getOrElse { start }
                append("• ").append(time).append(" — ").append(e.optString("summary","Untitled event")).append('\n')
            }
        }.trim()
    }
    private fun get(url:String):JSONObject {
        val c=(URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod="GET"; connectTimeout=12000; readTimeout=20000
            setRequestProperty("Authorization","Bearer $token"); setRequestProperty("Accept","application/json")
        }
        try {
            val code=c.responseCode
            val body=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if(code==401) error("Google access token expired. Reconnect Google in Vanta Settings.")
            if(code !in 200..299) error("Google API returned HTTP $code: "+runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty().take(180))
            return JSONObject(body)
        } finally { c.disconnect() }
    }
}

private class NotionClient(context:Context) {
    private val store=VantaStore(context)
    fun tasksSummary():String {
        val token=store.loadNotionToken()
        val databaseId=store.loadNotionDatabaseId().replace("-","")
        if(token.isBlank()||databaseId.isBlank()) return "Notion isn't configured. Open Vanta Settings → Integrations, save a Notion integration token and database ID, and share that database with your integration."
        val c=(URL("https://api.notion.com/v1/databases/$databaseId/query").openConnection() as HttpURLConnection).apply {
            requestMethod="POST"; connectTimeout=12000; readTimeout=20000; doOutput=true
            setRequestProperty("Authorization","Bearer $token"); setRequestProperty("Notion-Version","2022-06-28"); setRequestProperty("Content-Type","application/json")
        }
        try {
            c.outputStream.use { it.write(JSONObject().put("page_size",20).toString().toByteArray(Charsets.UTF_8)) }
            val code=c.responseCode
            val raw=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if(code==401||code==403) error("Notion denied access. Check the token and share the database with your integration.")
            if(code !in 200..299) error("Notion API returned HTTP $code: "+runCatching { JSONObject(raw).optString("message") }.getOrDefault("").take(160))
            val pages=JSONObject(raw).optJSONArray("results")?:JSONArray()
            if(pages.length()==0) return "Your connected Notion database has no pages to show."
            val lines=(0 until pages.length()).mapNotNull { i ->
                val props=pages.optJSONObject(i)?.optJSONObject("properties")?:return@mapNotNull null
                val keys=props.keys()
                while(keys.hasNext()) {
                    val prop=props.optJSONObject(keys.next())?:continue
                    val title=prop.optJSONArray("title")?:continue
                    val text=(0 until title.length()).mapNotNull { title.optJSONObject(it)?.optString("plain_text") }.joinToString("")
                    if(text.isNotBlank()) return@mapNotNull "• "+text.take(120)
                }
                null
            }
            return if(lines.isEmpty()) "I reached your Notion database, but could not find page title fields." else "Notion database pages (up to 20):\n"+lines.joinToString("\n")
        } finally { c.disconnect() }
    }
}
