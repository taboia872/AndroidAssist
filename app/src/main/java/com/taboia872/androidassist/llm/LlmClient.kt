package com.taboia872.androidassist.llm

import com.taboia872.androidassist.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

/**
 * Minimal, provider-agnostic OpenAI-compatible client. Reads endpoint, key
 * and model from AppSettings at call time — switching providers in Settings
 * takes effect on the very next message, no restart, no service reload.
 */
object LlmClient {

    suspend fun models(baseUrl: String, apiKey: String): Result<List<String>> {
        if (baseUrl.isBlank()) return Result.failure(IllegalArgumentException("Base URL is empty"))
        return fetchModels(baseUrl.trimEnd('/'), apiKey)
    }

    private suspend fun fetchModels(baseUrl: String, apiKey: String): Result<List<String>> =
        runCatching {
            val conn = (URL("$baseUrl/models").openConnection() as HttpsURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            }
            try {
                if (conn.responseCode != 200) {
                    throw RuntimeException("models: HTTP ${conn.responseCode}")
                }
                val data = JSONObject(conn.inputStream.bufferedReader().readText())
                    .optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
            } finally {
                conn.disconnect()
            }
        }

    /**
     * Streaming chat completion. Messages: [{role, content}].
     * Emits Content/Reasoning deltas, or one Error event on failure.
     */
    fun chat(messages: List<Pair<String, String>>): Flow<StreamEvent> = flow {
        val cfg = AppSettings.config.value
        if (cfg.baseUrl.isBlank()) {
            emit(StreamEvent.Error("No base URL configured — open Settings."))
            return@flow
        }
        if (cfg.model.isBlank()) {
            emit(StreamEvent.Error("No model selected — open Settings."))
            return@flow
        }
        if (LlmProviders.byId(cfg.providerId).requiresKey && cfg.apiKey.isBlank()) {
            emit(StreamEvent.Error("No API key configured — open Settings."))
            return@flow
        }

        val body = JSONObject().apply {
            put("model", cfg.model)
            put("stream", true)
            put("messages", JSONArray().apply {
                for ((role, content) in messages) {
                    put(JSONObject().put("role", role).put("content", content))
                }
            })
        }

        val conn = (URL("${cfg.baseUrl.trimEnd('/')}/chat/completions").openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (cfg.apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
        }

        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: "no body"
                emit(StreamEvent.Error("HTTP ${conn.responseCode}: $err"))
                return@flow
            }

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.startsWith("data: ")) continue
                    val data = l.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue

                    val delta = JSONObject(data)
                        .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        ?: continue

                    val content = delta.opt("content")
                    if (content is String && content.isNotEmpty()) emit(StreamEvent.Content(content))

                    // Reasoning tokens (DeepSeek-R1 style), also common as "reasoning"
                    val reasoning = delta.opt("reasoning_content") ?: delta.opt("reasoning")
                    if (reasoning is String && reasoning.isNotEmpty()) emit(StreamEvent.Reasoning(reasoning))
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: e.javaClass.simpleName))
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
