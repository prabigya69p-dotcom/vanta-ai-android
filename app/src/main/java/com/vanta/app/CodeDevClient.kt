package com.vanta.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Sends only the task text and selected ref to a repository-scoped GitHub Actions workflow. */
object CodeDevClient {
    private const val OWNER = "prabigya69p-dotcom"
    private const val REPO = "vanta-ai-android"
    private const val BASE_REF = "fix/hands-free-wake-word"

    fun dispatch(token: String, task: String) {
        require(token.isNotBlank()) { "GitHub connection is missing. Open VANTA Settings → Code Dev Mode." }
        val trimmedTask = task.trim()
        require(trimmedTask.isNotBlank()) { "Describe the code change first." }
        require(trimmedTask.length <= 4000) { "Code tasks are limited to 4,000 characters." }

        val endpoint = URL("https://api.github.com/repos/$OWNER/$REPO/actions/workflows/code-dev.yml/dispatches")
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Vanta-Android-Code-Dev")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        val payload = JSONObject()
            .put("ref", BASE_REF)
            .put("inputs", JSONObject().put("task", trimmedTask))
            .toString()
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status != 204) {
                val detail = runCatching {
                    (connection.errorStream ?: connection.inputStream).bufferedReader().use { it.readText() }
                }.getOrDefault("")
                val message = runCatching { JSONObject(detail).optString("message") }.getOrDefault("")
                throw IllegalStateException(when (status) {
                    401, 403 -> "GitHub rejected the token. Check that it is valid and has Actions read/write permission for this repository."
                    404 -> "The Code Dev workflow is not available on the repository's default branch yet. Merge the validated Code Dev workflow before using this button."
                    422 -> "GitHub rejected the workflow dispatch. Check its input names and the selected branch."
                    else -> "GitHub workflow dispatch failed (HTTP $status). " + message.take(180)
                })
            }
        } finally {
            connection.disconnect()
        }
    }
}
