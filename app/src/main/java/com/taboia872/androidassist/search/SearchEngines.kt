package com.taboia872.androidassist.search

import com.taboia872.androidassist.net.Net
import com.taboia872.androidassist.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Search engine abstraction. All engines return the common SearchResult
 * list. Brave/Exa/LangSearch need their API keys (per-provider slots in
 * AppSettings); DuckDuckGo is keyless.
 */
object SearchEngines {

    data class EngineInfo(
        val id: String,
        val name: String,
        val keySlot: String?, // null = keyless
        val privacy: String,
        val hint: String
    )

    val ALL = listOf(
        EngineInfo("duckduckgo", "DuckDuckGo", null, "High — privacy-first, no query profiling", "Free, no API key"),
        EngineInfo("brave", "Brave Search", "brave", "High — independent index, clear policy", "2,000 free/month (card required) • brave.com/search/api"),
        EngineInfo("exa", "Exa AI", "exa", "Medium — US service, pay-as-you-go", "$20 free credits • dashboard.exa.ai"),
        EngineInfo("langsearch", "LangSearch", "langsearch", "Unknown — no published privacy policy found", "Free tier, daily tokens • langsearch.com")
    )

    fun byId(id: String): EngineInfo = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun isConfigured(id: String): Boolean {
        val e = byId(id)
        if (e.keySlot == null) return true
        return (AppSettings.config.value.apiKeys[e.keySlot] ?: "").isNotBlank()
    }

    suspend fun search(query: String): List<SearchResult> {
        return when (AppSettings.config.value.searchEngine) {
            "brave" -> braveSearch(query)
            "exa" -> exaSearch(query)
            "langsearch" -> langSearch(query)
            else -> DuckDuckGo.search(query)
        }
    }

    // ------------------------------------------------------------- Brave
    // GET api.search.brave.com/res/v1/web/search, X-Subscription-Token header
    private suspend fun braveSearch(query: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val key = AppSettings.config.value.apiKeys["brave"] ?: ""
            if (key.isBlank()) return@withContext emptyList()
            try {
                val url = "https://api.search.brave.com/res/v1/web/search?q=" +
                    URLEncoder.encode(query, "UTF-8") + "&count=5"
                val conn = Net.open(url).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Subscription-Token", key)
                }
                try {
                    if (conn.responseCode != 200) return@withContext emptyList()
                    val root = JSONObject(conn.inputStream.bufferedReader().readText())
                    val results = root.optJSONObject("web")?.optJSONArray("results")
                        ?: return@withContext emptyList()
                    (0 until results.length()).mapNotNull { i ->
                        val r = results.optJSONObject(i) ?: return@mapNotNull null
                        val urlOut = r.optString("url")
                        if (urlOut.isBlank()) return@mapNotNull null
                        SearchResult(
                            title = r.optString("title"),
                            url = urlOut,
                            snippet = r.optString("description")
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    // ------------------------------------------------------------- Exa
    // POST api.exa.ai/search, Bearer key; contents.highlights for excerpts
    private suspend fun exaSearch(query: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val key = AppSettings.config.value.apiKeys["exa"] ?: ""
            if (key.isBlank()) return@withContext emptyList()
            try {
                val body = JSONObject().apply {
                    put("query", query)
                    put("type", "auto")
                    put("numResults", 5)
                    put("contents", JSONObject().put("highlights", true))
                }
                val conn = Net.open("https://api.exa.ai/search").apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    conn.outputStream.use {
                        it.write(body.toString().toByteArray(Charsets.UTF_8))
                    }
                    if (conn.responseCode != 200) return@withContext emptyList()
                    val results = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optJSONArray("results") ?: return@withContext emptyList()
                    (0 until results.length()).mapNotNull { i ->
                        val r = results.optJSONObject(i) ?: return@mapNotNull null
                        val highlights = r.optJSONArray("highlights")
                        val snippet = if (highlights != null && highlights.length() > 0)
                            highlights.optString(0) else r.optString("summary", "")
                        SearchResult(
                            title = r.optString("title"),
                            url = r.optString("url"),
                            snippet = snippet
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

    // ------------------------------------------------------------- LangSearch
    // POST api.langsearch.com/v1/web-search, Bearer key; contents.text
    private suspend fun langSearch(query: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val key = AppSettings.config.value.apiKeys["langsearch"] ?: ""
            if (key.isBlank()) return@withContext emptyList()
            try {
                val body = JSONObject().apply {
                    put("query", query)
                    put("count", 5)
                    put("contents", JSONObject().put("text", true))
                }
                val conn = Net.open("https://api.langsearch.com/v1/web-search").apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Content-Type", "application/json")
                }
                try {
                    conn.outputStream.use {
                        it.write(body.toString().toByteArray(Charsets.UTF_8))
                    }
                    if (conn.responseCode != 200) return@withContext emptyList()
                    val results = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optJSONArray("results") ?: return@withContext emptyList()
                    (0 until results.length()).mapNotNull { i ->
                        val r = results.optJSONObject(i) ?: return@mapNotNull null
                        val url = r.optString("url")
                        if (url.isBlank()) return@mapNotNull null
                        SearchResult(
                            title = r.optString("title", "").ifEmpty { url },
                            url = url,
                            snippet = r.optString("text", r.optString("snippet", ""))
                        )
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
}
