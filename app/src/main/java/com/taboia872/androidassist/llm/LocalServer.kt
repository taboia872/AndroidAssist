package com.taboia872.androidassist.llm

import com.taboia872.androidassist.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The llama.cpp server running ON THIS PHONE (the router in Termux).
 *
 * It speaks the OpenAI-compatible API, so the app treats it like any
 * other provider — the only thing this object adds is a tiny health
 * probe, so the Settings can say whether the server is up (the user
 * starts it with ./router.sh).
 */
object LocalServer {

    /**
     * Is the local server answering?
     *
     * Any HTTP response means the server is UP (even 503: the socket
     * answered); only a connection failure means it is down.
     */
    suspend fun isUp(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        val root = baseUrl.trimEnd('/').removeSuffix("/v1")
        if (root.isBlank()) return@withContext false
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = Net.open("$root/health").apply {
                requestMethod = "GET"
                connectTimeout = 2_500
                readTimeout = 2_500
            }
            conn.responseCode
            true
        } catch (e: Exception) {
            false
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { }
        }
    }
}
