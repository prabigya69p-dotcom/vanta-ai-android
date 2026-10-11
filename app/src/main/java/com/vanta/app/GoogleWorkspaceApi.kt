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

object GoogleWorkspaceApi {
    private fun request(url: String, token: String): JSONObject {
        if (token.isBlank()) throw IOException("Connect Google Workspace in VANTA Settings first.")
        val c=URL(url).openConnection() as HttpURLConnection
        c.requestMethod="GET"; c.connectTimeout=15000; c.readTimeout=20000
        c.setRequestProperty("Authorization","Bearer "+token); c.setRequestProperty("Accept","application/json")
        try {
            val status=c.responseCode
            val body=(if(status in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(status==401||status==403) throw IOException("Google access expired or permission is missing. Reconnect Google Workspace in VANTA Settings.")
            if(status !in 200..299) throw IOException("Google API HTTP "+status+". "+body.take(160))
            return JSONObject(body)
        } finally { c.disconnect() }
    }
    fun unreadSummary(token:String):String {
        val unread=request("https://gmail.googleapis.com/gmail/v1/users/me/labels/INBOX",token).optInt("messagesUnread",-1)
        if(unread<0) throw IOException("Gmail did not return an unread count.")
        return "Your Gmail inbox has "+unread+" unread message(s)."
    }
    fun calendarSummary(token:String):String {
        val fmt=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",Locale.US).apply{timeZone=TimeZone.getTimeZone("UTC")}
        val now=URLEncoder.encode(fmt.format(Date()),"UTF-8")
        val json=request("https://www.googleapis.com/calendar/v3/calendars/primary/events?timeMin="+now+"&maxResults=8&singleEvents=true&orderBy=startTime",token)
        val events=json.optJSONArray("items")
        if(events==null||events.length()==0) return "You have no upcoming events in your primary Google Calendar."
        val lines=mutableListOf<String>()
        for(i in 0 until events.length()){
            val e=events.optJSONObject(i)?:continue
            val start=e.optJSONObject("start")
            val whenText=start?.optString("dateTime")?.takeIf{it.isNotBlank()}?:start?.optString("date")?.takeIf{it.isNotBlank()}?:"time not specified"
            lines += "• "+e.optString("summary","Untitled event")+" — "+whenText
        }
        return if(lines.isEmpty()) "No upcoming events found." else "Your upcoming Google Calendar events:\n"+lines.joinToString("\n")
    }
    fun summary(token:String)=unreadSummary(token)+"\n\n"+calendarSummary(token)
}