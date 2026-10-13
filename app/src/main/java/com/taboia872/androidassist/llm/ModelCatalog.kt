package com.taboia872.androidassist.llm

import com.taboia872.androidassist.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val supportsVision: Boolean,
    val isFree: Boolean
)

/**
 * Live model catalog from any OpenAI-compatible GET /models endpoint.
 * 5-minute in-memory cache per (baseUrl, apiKey). Metadata (context, vision,
 * pricing) is parsed when the server provides it — OpenRouter is rich, Groq
 * gives context, self-hosted Ollama usually only ids.
 */
object ModelCatalog {

    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    private class Cached(
        val baseUrl: String,
        val apiKey: String,
        val models: List<ModelInfo>,
        val fetchedAt: Long
    )

    @Volatile
    private var cache: Cached? = null

    fun cached(baseUrl: String, apiKey: String): List<ModelInfo>? =
        cache?.takeIf {
            it.baseUrl == baseUrl.trimEnd('/') && it.apiKey == apiKey &&
                System.currentTimeMillis() - it.fetchedAt < CACHE_TTL_MS
        }?.models

    /** Fetch the catalog. Errors surface as Result.failure — caller decides. */
    suspend fun fetch(
        baseUrl: String,
        apiKey: String,
        forceRefresh: Boolean = false
    ): Result<List<ModelInfo>> = withContext(Dispatchers.IO) {
        val url = baseUrl.trimEnd('/')
        if (url.isBlank()) return@withContext Result.failure(IllegalArgumentException("Base URL is empty"))
        if (!forceRefresh) {
            cached(url, apiKey)?.let { return@withContext Result.success(it) }
        }
        runCatching {
            // HttpURLConnection (not Https): custom endpoints may be plain
            // http, e.g. Ollama on http://192.168.0.10:11434/v1
            val conn = Net.open("$url/models").apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                if (conn.responseCode != 200) {
                    throw RuntimeException("HTTP ${conn.responseCode}")
                }
                val models = parse(conn.inputStream.bufferedReader().readText())
                if (models.isEmpty()) throw RuntimeException("Server returned no models")
                cache = Cached(url, apiKey, models, System.currentTimeMillis())
                models
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * Map a /models JSON body onto ModelInfo. Field shapes differ per
     * provider: OpenRouter nests context under top_provider and has pricing;
     * Groq uses a top-level context_window; plain Ollama/LM Studio only ids.
     */
    fun parse(body: String): List<ModelInfo> {
        val data: JSONArray = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<ModelInfo>(data.length())
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isEmpty()) continue

            val name = m.optString("name", "").ifEmpty { id }
            val modality = m.optJSONObject("architecture")?.optString("modality") ?: ""
            val supportsVision = modality.contains("image")

            val contextLength = m.optJSONObject("top_provider")?.optInt("context_length", 0)
                ?: m.optInt("context_window", 0)
            val pricing = m.optJSONObject("pricing")
            val promptPrice = pricing?.optString("prompt", "0")?.toDoubleOrNull() ?: 0.0
            val isFree = pricing == null || id.endsWith(":free") || promptPrice == 0.0

            val desc = buildString {
                if (pricing == null) append("Hosted model")
                else if (isFree) append("Free")
                else append(String.format("$%.2f/M tokens", promptPrice * 1_000_000))
                if (contextLength > 0) {
                    append(" · ")
                    append(if (contextLength >= 1000) "${contextLength / 1000}K ctx" else "$contextLength ctx")
                }
                if (supportsVision) append(" · vision")
            }

            out.add(ModelInfo(id, name, desc, supportsVision, isFree))
        }
        return out
    }
}
