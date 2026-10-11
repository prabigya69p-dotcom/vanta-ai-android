package com.vanta.app
import java.util.Locale
object WorkspaceCommandRouter {
    fun isCommand(raw:String):Boolean {
        val t=raw.trim().lowercase(Locale.ROOT)
        return t in setOf("check my unread emails","how many unread emails","check my inbox","check gmail","what's on my calendar","what is on my calendar","upcoming calendar events","check my calendar","show my notion tasks","check notion tasks","what are my notion tasks","list notion tasks") || t.startsWith("add task to notion ")
    }
    fun execute(store:VantaStore,raw:String):String {
        val text=raw.trim();val t=text.lowercase(Locale.ROOT)
        return when {
            t in setOf("check my unread emails","how many unread emails","check my inbox","check gmail")->GoogleWorkspaceApi.unreadSummary(store.loadGoogleAccessToken())
            t in setOf("what's on my calendar","what is on my calendar","upcoming calendar events","check my calendar")->GoogleWorkspaceApi.calendarSummary(store.loadGoogleAccessToken())
            t in setOf("show my notion tasks","check notion tasks","what are my notion tasks","list notion tasks")->NotionApi.listTasks(store.loadNotionToken(),store.loadNotionDatabaseId())
            t.startsWith("add task to notion ")->NotionApi.createTask(store.loadNotionToken(),store.loadNotionDatabaseId(),text.substringAfter("add task to notion ","").trim())
            else->"I couldn't identify that workspace command."
        }
    }
}