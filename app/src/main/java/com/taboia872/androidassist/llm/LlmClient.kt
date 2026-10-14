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
    /** Model requests the web_search tool; carries the query it wants. */
    data class ToolCall(val id: String, val query: String) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

/**
 * Minimal, provider-agnostic OpenAI-compatible client. Reads endpoint, key
 * and model from AppSettings at call time — switching providers in Settings
 * takes effect on the very next message, no restart, no service reload.
 * Model listing lives in ModelCatalog (rich metadata), not here.
 *
 * Messages: triples of (role, content, imageBase64?). Images go out as the
 * OpenAI vision multipart content shape; plain text stays a simple string.
 */
object LlmClient {

    /** OpenAI tools declaration for web_search (function calling). */
    private fun webSearchTool(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", "web_search")
            put("description",
                "Search the web for current information (news, prices, weather, " +
                "recent events). Use when the question needs up-to-date facts.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().apply {
                        put("type", "string")
                        put("description", "The search query, in the user's language")
                    })
                })
                put("required", org.json.JSONArray().put("query"))
            })
        })
    }

    /**
     * Streaming chat completion with FUNCTION CALLING when the server
     * supports it: the web_search tool is declared up front; if the model
     * wants fresh facts it emits a tool_call delta instead of guessing.
     * [toolsEnabled] gates the declaration; [toolResult] injects a
     * role:"tool" message (search results) for the follow-up turn.
     */
    fun chat(
        messages: List<Triple<String, String, String?>>,
        systemPrompt: String,
        toolsEnabled: Boolean = false,
        toolCallId: String? = null,
        toolResult: String? = null
    ): Flow<StreamEvent> = flow {
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
            if (toolsEnabled) {
                put("tools", JSONArray().put(webSearchTool()))
                put("tool_choice", "auto")
            }
            put("messages", JSONArray().apply {
                if (systemPrompt.isNotBlank()) {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                if (toolCallId != null && toolResult != null) {
                    // Follow-up turn: the assistant's tool_call + the result
                    put(JSONObject().apply {
                        put("role", "assistant")
                        put("tool_calls", JSONArray().put(JSONObject().apply {
                            put("id", toolCallId)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", "web_search")
                                put("arguments", "{}")
                            })
                        }))
                    })
                    put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", toolCallId)
                        put("content", toolResult)
                    })
                }
                for ((role, content, image) in messages) {
                    if (image != null) {
                        // OpenAI vision shape: content is an array of typed parts
                        put(JSONObject().apply {
                            put("role", role)
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", content)
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject()
                                        .put("url", "data:image/jpeg;base64,$image"))
                                })
                            })
                        })
                    } else {
                        put(JSONObject().put("role", role).put("content", content))
                    }
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

                    // Tool calls (streamed function-call deltas)
                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (t in 0 until toolCalls.length()) {
                            val tc = toolCalls.optJSONObject(t) ?: continue
                            val fn = tc.optJSONObject("function") ?: continue
                            val args = fn.optJSONObject("arguments") ?: fn.opt("arguments")
                            val query = when (args) {
                                is org.json.JSONObject -> args.optString("query", "")
                                is String -> {
                                    try {
                                        org.json.JSONObject(args).optString("query", "")
                                    } catch (e: Exception) { "" }
                                }
                                else -> ""
                            }
                            val id = tc.optString("id", "call_0")
                            if (query.isNotBlank()) {
                                emit(StreamEvent.ToolCall(id, query))
                            } else if (fn.optString("name", "") == "web_search") {
                                // name arrived, arguments may stream later;
                                // emit with empty query so the caller waits
                                emit(StreamEvent.ToolCall(id, ""))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: e.javaClass.simpleName))
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)
}
