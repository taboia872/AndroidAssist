package com.taboia872.androidassist.llm

import com.taboia872.androidassist.net.Net
import com.taboia872.androidassist.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

/**
 * Minimal, provider-agnostic OpenAI-compatible client. Reads endpoint, key
 * and model from AppSettings at call time — switching providers in Settings
 * takes effect on the very next message, no restart, no service reload.
 * Model listing lives in ModelCatalog (rich metadata), not here.
 */
object LlmClient {

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

        // HttpURLConnection (not Https): custom endpoints may be plain http
        // (e.g. Ollama on http://192.168.0.10:11434/v1) and openConnection()
        // returns a plain connection for those.
        val conn = Net.open("${cfg.baseUrl.trimEnd('/')}/chat/completions").apply {
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
