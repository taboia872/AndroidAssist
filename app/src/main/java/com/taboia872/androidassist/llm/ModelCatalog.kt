package com.taboia872.androidassist.llm

import com.taboia872.androidassist.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * One model with STRUCTURED capabilities — the UI renders a compact single
 * line ("128K · vision · audio-in") and sorts by context or capability.
 */
data class ModelInfo(
    val id: String,
    val name: String,
    /** Short one-line capability summary, provider-agnostic. */
    val desc: String,
    val supportsVision: Boolean,
    val isFree: Boolean,
    val contextLength: Int = 0,
    val audioIn: Boolean = false,
    val audioOut: Boolean = false,
    val imageGen: Boolean = false,
    /** Ranking hint: more capabilities = higher score. */
    val capabilityScore: Int = 0
)

/**
 * Live model catalog from any OpenAI-compatible GET /models endpoint.
 * 5-minute in-memory cache per (baseUrl, apiKey).
 *
 * UNIFORM RENDERING across providers (user request): every model shows
 * context size and capability tags (vision / audio-in / audio-out /
 * image-gen / omni) parsed from whatever shape the server gives —
 * OpenRouter nests under architecture/top_provider, Groq exposes
 * input_modalities/output_modalities, Ollama usually only ids.
 * Pricing shows ONLY on OpenRouter (the single provider where free/paid
 * coexist meaningfully for the user).
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
                val models = parse(conn.inputStream.bufferedReader().readText(), url)
                if (models.isEmpty()) throw RuntimeException("Server returned no models")
                cache = Cached(url, apiKey, models, System.currentTimeMillis())
                models
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }

    private fun JSONObject?.strList(vararg keys: String): List<String> {
        if (this == null) return emptyList()
        for (k in keys) {
            val arr = optJSONArray(k) ?: continue
            return arr.strings()
        }
        return emptyList()
    }

    /**
     * Map a /models JSON body onto ModelInfo. Field shapes differ per
     * provider — everything is normalized into the same compact line.
     */
    fun parse(body: String, baseUrl: String = ""): List<ModelInfo> {
        // Provider identity comes from the BASE URL — never from the model id:
        // Groq ids now look like "openai/gpt-oss-120b" and were being
        // mistaken for OpenRouter (pricing leaked back into the list).
        val isOpenRouter = baseUrl.contains("openrouter", ignoreCase = true)

        val data: JSONArray = JSONObject(body).optJSONArray("data") ?: JSONArray()
        val out = ArrayList<ModelInfo>(data.length())
        for (i in 0 until data.length()) {
            val m = data.optJSONObject(i) ?: continue
            val id = m.optString("id", "")
            if (id.isEmpty()) continue

            val name = m.optString("name", "").ifEmpty { id }

            // --- modalities across provider shapes ---
            val arch = m.optJSONObject("architecture")
            val inMods = m.strList("input_modalities").ifEmpty { arch.strList("input_modalities") }
            val outMods = m.strList("output_modalities").ifEmpty { arch.strList("output_modalities") }
            val capsObj = m.optJSONObject("capabilities")
            val supportsVision = inMods.any { it.equals("image", true) } ||
                (capsObj != null && (capsObj.optString("vision") == "true" || capsObj.has("input_image")))

            val audioIn = inMods.any { it.equals("audio", true) }
            val audioOut = outMods.any { it.equals("audio", true) }
            val imageGen = outMods.any { it.equals("image", true) }
            val omni = supportsVision && audioIn && audioOut

            val contextLength = m.optJSONObject("top_provider")?.optInt("context_length", 0)
                ?: m.optInt("context_window", 0)

            // --- pricing: OpenRouter ONLY ---
            val pricing = m.optJSONObject("pricing")
            val promptPrice = pricing?.optString("prompt", "0")?.toDoubleOrNull() ?: 0.0
            val isFree = !isOpenRouter || pricing == null ||
                id.endsWith(":free") || promptPrice == 0.0

            // --- compact uniform line ---
            val desc = buildString {
                if (isOpenRouter && pricing != null) {
                    if (isFree) append("Free")
                    else append(String.format("$%.2f/M", promptPrice * 1_000_000))
                    append(" · ")
                }
                if (contextLength > 0) {
                    append(if (contextLength >= 1000) "${contextLength / 1000}K" else "$contextLength")
                    append(" ctx")
                }
                val tags = buildList {
                    if (omni) add("omni") else {
                        if (supportsVision) add("vision")
                        if (audioIn) add("audio-in")
                        if (audioOut) add("audio-out")
                    }
                    if (imageGen) add("image-gen")
                }
                tags.forEach { t ->
                    if (isNotEmpty()) append(" · ")
                    append(t)
                }
            }

            val score = (if (supportsVision) 2 else 0) + (if (audioIn) 1 else 0) +
                (if (audioOut) 1 else 0) + (if (imageGen) 2 else 0) +
                (if (omni) 1 else 0) + (if (contextLength >= 128_000) 1 else 0)

            out.add(ModelInfo(
                id = id, name = name, desc = desc,
                supportsVision = supportsVision, isFree = isFree,
                contextLength = contextLength,
                audioIn = audioIn, audioOut = audioOut, imageGen = imageGen,
                capabilityScore = score
            ))
        }
        return out
    }
}
