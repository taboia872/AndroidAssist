package com.taboia872.androidassist.search

import com.taboia872.androidassist.llm.KeyRotation
import com.taboia872.androidassist.net.Net
import com.taboia872.androidassist.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
        EngineInfo("parallel", "Parallel Search (MCP)", null, "Medium — service queries: all queries + IP; no account", "Free, no API key • search.parallel.ai"),
        EngineInfo("duckduckgo", "DuckDuckGo", null, "High — privacy-first, no query profiling", "Free, no API key (IP-filtered — VPN/mobile IPs often blocked)"),
        EngineInfo("brave", "Brave Search", "brave", "High — independent index, clear policy", "2,000 free/month (card required) • brave.com/search/api"),
        EngineInfo("exa", "Exa AI", "exa", "Medium — US service, pay-as-you-go", "$20 free credits • dashboard.exa.ai"),
        EngineInfo("langsearch", "LangSearch", "langsearch", "Unknown — no published privacy policy found", "Free tier, daily tokens • langsearch.com"),
        EngineInfo("jina", "Jina Reader (MCP)", "jina", "Medium — service queries: queries + IP; with key, tied to account", "Keyless with limits • jina.ai reader (free key lifts limits)")
    )

    fun byId(id: String): EngineInfo = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun isConfigured(id: String): Boolean {
        val e = byId(id)
        if (e.keySlot == null) return true
        return AppSettings.keysFor(e.keySlot).isNotEmpty()
    }

    /**
     * Run a search call with the rotation candidates of [slot]. A key the
     * server REJECTS (401/403/429) is not fatal — the next candidate is
     * tried and only when EVERY key fails the error surfaces.
     */
    private suspend fun <T> withKeyRotation(slot: String, block: suspend (String) -> T): T {
        val keys = AppSettings.keysFor(slot)
        if (keys.isEmpty()) throw IllegalStateException("no API key saved")
        val strategy = AppSettings.config.value.keyRotationStrategy
        val order = KeyRotation.candidates(slot, keys, strategy)
        var lastErr: Exception? = null
        for (idx in order) {
            try {
                val out = block(keys[idx])
                KeyRotation.markUsed(slot, idx, strategy)
                return out
            } catch (e: KeyRotation.KeyRejected) {
                lastErr = e
                KeyRotation.advanceOnFailure(slot, idx, strategy, keys.size)
            }
        }
        throw lastErr ?: IllegalStateException("no API key saved")
    }

    suspend fun search(query: String): SearchOutcome {
        val cfg = AppSettings.config.value
        val count = cfg.searchResultCount.coerceIn(1, 10)
        return try {
            when (cfg.searchEngine) {
                "parallel" -> parallelSearch(query, count)
                "brave" -> withKeyRotation("brave") { braveSearch(query, count, it) }
                "exa" -> withKeyRotation("exa") { exaSearch(query, count, it) }
                "langsearch" -> withKeyRotation("langsearch") { langSearch(query, count, it) }
                "jina" -> withKeyRotation("jina") { jinaSearch(query, count, it) }
                else -> DuckDuckGo.search(query, count)
            }
        } catch (e: Exception) {
            SearchOutcome.Failed(e.message ?: "search failed")
        }
    }

    // ------------------------------------------------------------- Brave
    // GET api.search.brave.com/res/v1/web/search, X-Subscription-Token header
    private suspend fun braveSearch(query: String, count: Int, key: String): SearchOutcome =
        withContext(Dispatchers.IO) {
            val cfg = AppSettings.config.value
            if (key.isBlank()) return@withContext SearchOutcome.Failed("no Brave API key saved")
            try {
                val region = if (cfg.searchRegion.isNotBlank()) "&country=${cfg.searchRegion}" else ""
                val fresh = when (cfg.searchFreshness) {
                    "day" -> "&freshness=pd"
                    "week" -> "&freshness=pw"
                    "month" -> "&freshness=pm"
                    else -> ""
                }
                val url = "https://api.search.brave.com/res/v1/web/search?q=" +
                    URLEncoder.encode(query, "UTF-8") + "&count=$count$region$fresh"
                val conn = Net.open(url).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Subscription-Token", key)
                }
                try {
                    if (conn.responseCode != 200) {
                        val code = conn.responseCode
                        if (code == 401 || code == 403 || code == 429) {
                            throw KeyRotation.KeyRejected(code, "Brave HTTP $code")
                        }
                        return@withContext SearchOutcome.Failed("Brave HTTP ${conn.responseCode}")
                    }
                    val root = JSONObject(conn.inputStream.bufferedReader().readText())
                    val results = root.optJSONObject("web")?.optJSONArray("results")
                    if (results == null) {
                        return@withContext SearchOutcome.Failed("Brave returned no web results")
                    }
                    val out = (0 until results.length()).mapNotNull { i ->
                        val r = results.optJSONObject(i) ?: return@mapNotNull null
                        val urlOut = r.optString("url")
                        if (urlOut.isBlank()) return@mapNotNull null
                        SearchResult(
                            title = r.optString("title"),
                            url = urlOut,
                            snippet = r.optString("description")
                        )
                    }
                    SearchOutcome.Ok(out)
                } finally {
                    conn.disconnect()
                }
            } catch (e: KeyRotation.KeyRejected) {
                throw e
            } catch (e: Exception) {
                SearchOutcome.Failed("Brave network: ${e.message}")
            }
        }

    // ------------------------------------------------------------- Exa
    // POST api.exa.ai/search, Bearer key; contents.highlights for excerpts
    private suspend fun exaSearch(query: String, count: Int, key: String): SearchOutcome =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext SearchOutcome.Failed("no Exa API key saved")
            try {
                val body = JSONObject().apply {
                    put("query", query)
                    put("type", "auto")
                    put("numResults", count)
                    put("category", "news")
                    put("startPublishedDate", when (AppSettings.config.value.searchFreshness) {
                        "day" -> java.time.Instant.now().minusSeconds(86400).toString()
                        "week" -> java.time.Instant.now().minusSeconds(7 * 86400).toString()
                        "month" -> java.time.Instant.now().minusSeconds(30 * 86400).toString()
                        else -> ""
                    })
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
                    if (conn.responseCode != 200) {
                        val code = conn.responseCode
                        if (code == 401 || code == 403 || code == 429) {
                            throw KeyRotation.KeyRejected(code, "Exa HTTP $code")
                        }
                        return@withContext SearchOutcome.Failed("Exa HTTP ${conn.responseCode}")
                    }
                    val results = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optJSONArray("results")
                    if (results == null) {
                        return@withContext SearchOutcome.Failed("Exa returned no results")
                    }
                    val out = (0 until results.length()).mapNotNull { i ->
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
                    SearchOutcome.Ok(out)
                } finally {
                    conn.disconnect()
                }
            } catch (e: KeyRotation.KeyRejected) {
                throw e
            } catch (e: Exception) {
                SearchOutcome.Failed("Exa network: ${e.message}")
            }
        }

    // ------------------------------------------------------------- LangSearch
    // POST api.langsearch.com/v1/web-search, Bearer key; contents.text
    private suspend fun langSearch(query: String, count: Int, key: String): SearchOutcome =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) return@withContext SearchOutcome.Failed("no LangSearch API key saved")
            try {
                val body = JSONObject().apply {
                    put("query", query)
                    put("count", count)
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
                    if (conn.responseCode != 200) {
                        val code = conn.responseCode
                        if (code == 401 || code == 403 || code == 429) {
                            throw KeyRotation.KeyRejected(code, "LangSearch HTTP $code")
                        }
                        return@withContext SearchOutcome.Failed("LangSearch HTTP ${conn.responseCode}")
                    }
                    val results = JSONObject(conn.inputStream.bufferedReader().readText())
                        .optJSONArray("results")
                    if (results == null) {
                        return@withContext SearchOutcome.Failed("LangSearch returned no results")
                    }
                    val out = (0 until results.length()).mapNotNull { i ->
                        val r = results.optJSONObject(i) ?: return@mapNotNull null
                        val url = r.optString("url")
                        if (url.isBlank()) return@mapNotNull null
                        SearchResult(
                            title = r.optString("title", "").ifEmpty { url },
                            url = url,
                            snippet = r.optString("text", r.optString("snippet", ""))
                        )
                    }
                    SearchOutcome.Ok(out)
                } finally {
                    conn.disconnect()
                }
            } catch (e: KeyRotation.KeyRejected) {
                throw e
            } catch (e: Exception) {
                SearchOutcome.Failed("LangSearch network: ${e.message}")
            }
        }
    // ------------------------------------------------------------- Parallel (MCP)
    // POST search.parallel.ai/mcp JSON-RPC, keyless. LLM-oriented results
    // with excerpts. The best keyless option for VPN/mobile-IP users: it is a
    // real API service, not a scraped frontend, so there is no bot filter.
    private suspend fun parallelSearch(query: String, count: Int): SearchOutcome =
        withContext(Dispatchers.IO) {
            try {
                val params = JSONObject().apply {
                    put("name", "web_search")
                    put("arguments", JSONObject().apply {
                        put("objective", query)
                        put("search_queries", JSONArray().put(query))
                    })
                }
                val req = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "tools/call")
                    put("params", params)
                }
                val text = postJsonRpc("https://search.parallel.ai/mcp", req, null)
                // Response: result.content[0].text = JSON string with results[]
                val root = JSONObject(text)
                val results = root.optJSONArray("results")
                    ?: return@withContext SearchOutcome.Failed("Parallel returned no results")
                val out = ArrayList<SearchResult>()
                for (i in 0 until results.length()) {
                    if (out.size >= count) break
                    val r = results.optJSONObject(i) ?: continue
                    val url = r.optString("url")
                    if (url.isBlank()) continue
                    val excerpts = r.optJSONArray("excerpts")
                    val snippet = if (excerpts != null && excerpts.length() > 0)
                        excerpts.optString(0) else r.optString("snippet", "")
                    out.add(SearchResult(
                        title = r.optString("title").ifBlank { url },
                        url = url,
                        snippet = snippet
                    ))
                }
                SearchOutcome.Ok(out)
            } catch (e: Exception) {
                SearchOutcome.Failed("Parallel: ${e.message}")
            }
        }

    // ------------------------------------------------------------- Jina (MCP)
    // POST mcp.jina.ai/v1 JSON-RPC. Keyless with limits; a key (jina slot)
    // lifts them. search_web returns web results; also exposes read_url.
    private suspend fun jinaSearch(query: String, count: Int, key: String): SearchOutcome =
        withContext(Dispatchers.IO) {
            try {
                val params = JSONObject().apply {
                    put("name", "search_web")
                    put("arguments", JSONObject().apply {
                        put("query", query)
                        put("num", count)
                    })
                }
                val req = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "tools/call")
                    put("params", params)
                }
                val text = postJsonRpc("https://mcp.jina.ai/v1", req, key.ifBlank { null })
                val root = JSONObject(text)
                val results = root.optJSONArray("results")
                    ?: return@withContext SearchOutcome.Failed("Jina returned no results")
                val out = ArrayList<SearchResult>()
                for (i in 0 until results.length()) {
                    if (out.size >= count) break
                    val r = results.optJSONObject(i) ?: continue
                    val url = r.optString("url")
                    if (url.isBlank()) continue
                    out.add(SearchResult(
                        title = r.optString("title").ifBlank { url },
                        url = url,
                        snippet = r.optString("snippet", r.optString("content", ""))
                    ))
                }
                SearchOutcome.Ok(out)
            } catch (e: KeyRotation.KeyRejected) {
                throw e
            } catch (e: Exception) {
                SearchOutcome.Failed("Jina: ${e.message}")
            }
        }

    /** Minimal JSON-RPC POST; returns the "text" content of the tool result. */
    private fun postJsonRpc(url: String, req: JSONObject, apiKey: String?): String {
        val conn = Net.open(url).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json, text/event-stream")
            if (!apiKey.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(req.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) {
                val code = conn.responseCode
                if (code == 401 || code == 403 || code == 429) {
                    throw KeyRotation.KeyRejected(code, "HTTP $code")
                }
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            var body = conn.inputStream.bufferedReader().readText()
            // SSE envelope: "event: messagen\ndata: {...}" — take the data: line
            if (body.contains("data: ")) {
                body = body.substringAfter("data: ").substringBefore("\n\n").trim()
            }
            val root = JSONObject(body)
            // tools/call result.content[0].text
            val result = root.optJSONObject("result") ?: throw RuntimeException("no result in response")
            val content = result.optJSONArray("content") ?: throw RuntimeException("no content")
            val first = content.optJSONObject(0) ?: throw RuntimeException("empty content")
            return first.optString("text")
        } finally {
            conn.disconnect()
        }
    }
}
