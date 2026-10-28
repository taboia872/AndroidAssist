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
import java.net.HttpURLConnection
import java.io.InputStreamReader

/**
 * One completed tool exchange to replay in the next request: the assistant's
 * tool_call (with any partial answer it produced before calling) and the
 * tool result. Multiple exchanges = the model searched more than once.
 */
data class ToolExchange(
    val callId: String,
    val callName: String,
    val assistantContent: String,
    val result: String
)

sealed class StreamEvent {
    data class Content(val text: String) : StreamEvent()
    data class Reasoning(val text: String) : StreamEvent()
    /** Model requests a tool; carries the raw arguments JSON. */
    data class ToolCall(val id: String, val name: String, val args: String) : StreamEvent()
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

    /**
     * The connection of the stream being read RIGHT NOW. stop() disconnects
     * it so a blocked read() aborts immediately — without this the local
     * server keeps generating after the user pressed STOP (llama.cpp report).
     */
    @Volatile private var activeConn: HttpURLConnection? = null

    /** Kill the in-flight stream (called by ChatSession.stop()). */
    fun abortActive() {
        try {
            activeConn?.disconnect()
        } catch (e: Exception) {
        }
        activeConn = null
    }

    // ----------------------------------------------------------- tools

    /** Simple tool spec builder. */
    private fun tool(
        name: String,
        description: String,
        properties: Pair<String, String>, // (paramName, description)
        required: List<String> = emptyList(),
        extraProps: Map<String, Pair<String, String>> = emptyMap() // param -> (type, desc)
    ): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put(properties.first, JSONObject().apply {
                        put("type", "string")
                        put("description", properties.second)
                    })
                    extraProps.forEach { (param, typeDesc) ->
                        put(param, JSONObject().apply {
                            put("type", typeDesc.first)
                            put("description", typeDesc.second)
                        })
                    }
                })
                if (required.isNotEmpty()) put("required", org.json.JSONArray(required))
            })
        })
    }

    /**
     * Device tool specs (Tier 1+2): local actions executed on the phone via
     * Android Intents. Declared whenever deviceToolsEnabled is on.
     */
    private fun deviceToolSpecs(): List<JSONObject> = listOf(
        tool(
            "set_alarm",
            "Set an alarm on the user's phone. Use for wake-up alarms and reminders at a specific TIME of day.",
            "label" to "Short alarm label/message",
            required = listOf("label"),
            extraProps = mapOf(
                "hour" to ("integer" to "Hour 0-23 (24h format)"),
                "minute" to ("integer" to "Minute 0-59"),
                "days" to ("array" to "Repeat days as strings: mon,tue,wed,thu,fri,sat,sun. Empty = one-time alarm")
            )
        ),
        tool(
            "set_timer",
            "Start a countdown timer on the user's phone (e.g. 'remind me in 10 minutes', '5 minute egg timer').",
            "label" to "Short timer label",
            extraProps = mapOf(
                "seconds" to ("integer" to "Total duration in seconds")
            )
        ),
        tool(
            "add_calendar_event",
            "Open a new calendar event pre-filled on the user's calendar app. The user reviews and saves it there.",
            "title" to "Event title",
            required = listOf("title"),
            extraProps = mapOf(
                "begin_time" to ("string" to "Start time ISO 8601 local, e.g. 2026-10-22T15:30 (resolve relative dates like 'tomorrow 10am' to a real date first)"),
                "duration_minutes" to ("integer" to "Event duration in minutes (default 60)"),
                "all_day" to ("boolean" to "True for an all-day event"),
                "description" to ("string" to "Optional event description/location")
            )
        ),
        tool(
            "open_app",
            "Open an installed app on the user's phone by name (e.g. WhatsApp, Spotify, Camera).",
            "app_name" to "App name as the user said it",
            required = listOf("app_name")
        ),
        tool(
            "read_clipboard",
            "Read the current clipboard text (e.g. when the user says 'translate this' or 'what does this say' with content copied).",
            "_" to "No parameters needed"
        ),
        tool(
            "add_to_clipboard",
            "Copy text to the clipboard so the user can paste it anywhere.",
            "text" to "The exact text to copy",
            required = listOf("text")
        ),
        tool(
            "share_text",
            "Open the Android share sheet with the given text so the user can send it to any app (WhatsApp, Telegram, email...).",
            "text" to "The text to share",
            required = listOf("text")
        ),
        tool(
            "get_location",
            "Get the user's approximate current location (latitude/longitude, ~city block accuracy). Use it for local questions: transit (bus/metro stops and schedules), nearby places, weather. Coordinates are approximate and stay private.",
            "_" to "No parameters needed"
        ),
        tool(
            "open_map",
            "Open a point on the map in the user's preferred map app (a chooser appears if several are installed). Use after get_location when the user wants to SEE a place.",
            "latitude" to "Latitude of the point",
            required = listOf("latitude"),
            extraProps = mapOf(
                "longitude" to ("number" to "Longitude of the point"),
                "label" to ("string" to "Short place label shown on the map pin")
            )
        ),
        tool(
            "save_memory",
            "Save a lasting memory about the user. ONLY for durable information: standing instructions (kind=core: how you must behave with this user), explicit tastes/preferences expressed as SHORT phrases (kind=preference), or stable world facts (kind=fact: numbers, names, schedules, addresses). NEVER save task content, transient state or anything the user asked you to just do once. Check the wording with the user's own phrasing. At most ONE save per turn.",
            "text" to "The memory content, short and self-contained",
            required = listOf("text"),
            extraProps = mapOf(
                "kind" to ("string" to "core | preference | fact — core=standing instruction, preference=short user taste, fact=stable world fact")
            )
        ),
        tool(
            "search_memories",
            "Search the user's saved memories (facts and skills you cannot see in the prompt). ALWAYS call this when the question involves personal facts (bus lines, contacts, schedules, addresses) or when the user mentions doing a routine you may have stored.",
            "query" to "What to look for (keywords or a short question)"
        ),
        tool(
            "forget_memory",
            "Delete a memory that is wrong or outdated (user said to forget something, or you found a contradiction). Provide the id or the exact/recognizable text.",
            "id_or_text" to "Memory id or the text (or distinctive part) to delete"
        )
    )

    /**
     * Streaming chat completion with FUNCTION CALLING when the server
     * supports it: the web_search tool is declared up front; if the model
     * wants fresh facts it emits a tool_call delta instead of guessing.
     * [toolsEnabled] gates the declaration; [toolExchanges] replays every
     * completed tool exchange (assistant tool_call + result) so the model
     * can search MULTIPLE times per turn.
     */
    fun chat(
        messages: List<Triple<String, String, String?>>,
        systemPrompt: String,
        toolsEnabled: Boolean = false,
        toolExchanges: List<ToolExchange> = emptyList()
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
            // No-think mode (local thinking models): llama-server and
            // compatible servers accept chat_template_kwargs; others ignore
            // the extra field harmlessly.
            if (AppSettings.config.value.noThink) {
                put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            }
            if (toolsEnabled) {
                val specs = JSONArray()
                if (AppSettings.config.value.webSearchEnabled) {
                    specs.put(tool(
                        "web_search",
                        "Search the web for current information (news, prices, weather, " +
                            "recent events). Use when the question needs up-to-date facts.",
                        "query" to "The search query, in the user's language",
                        required = listOf("query")
                    ))
                }
                if (AppSettings.config.value.deviceToolsEnabled) {
                    deviceToolSpecs().forEach { specs.put(it) }
                }
                if (specs.length() > 0) {
                    put("tools", specs)
                    put("tool_choice", "auto")
                }
            }
            put("messages", JSONArray().apply {
                if (systemPrompt.isNotBlank()) {
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                }
                toolExchanges.forEach { ex ->
                    // The assistant message that made the call (+ any partial
                    // answer it wrote before calling), then the tool result.
                    put(JSONObject().apply {
                        put("role", "assistant")
                        if (ex.assistantContent.isNotBlank()) {
                            put("content", ex.assistantContent)
                        }
                        put("tool_calls", JSONArray().put(JSONObject().apply {
                            put("id", ex.callId)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", ex.callName)
                                put("arguments", "{}")
                            })
                        }))
                    })
                    put(JSONObject().apply {
                        put("role", "tool")
                        put("tool_call_id", ex.callId)
                        put("content", ex.result)
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

        // Legacy text-format tool calls (some cloud models): buffered while a
        // <function=...> block is open.
        var toolCallTextBuffer: String? = null

        // Streamed tool-call accumulators (arguments arrive in fragments)
        val pendingToolIds = mutableMapOf<Int, String>()
        val pendingToolNames = mutableMapOf<Int, String>()
        val pendingToolArgs = mutableMapOf<Int, String>()

        activeConn = conn
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
                    if (content is String && content.isNotEmpty()) {
                        // Some cloud models (Ollama Cloud, Groq) emit tool calls
                        // as TEXT in the legacy format on the follow-up round
                        // (tools are not re-declared there):
                        //   <function=open_app><parameter=app_name>...</parameter></function>
                        // Buffer it while the pattern is open; emit a REAL
                        // ToolCall when it closes instead of leaking the syntax
                        // into the chat bubble (user report).
                        if (toolCallTextBuffer != null || content.contains("<function=")) {
                            toolCallTextBuffer = (toolCallTextBuffer ?: "") + content
                            if (toolCallTextBuffer!!.contains("</function>")) {
                                parseLegacyToolCall(toolCallTextBuffer!!)?.let { emit(it) }
                                toolCallTextBuffer = null
                            }
                        } else {
                            emit(StreamEvent.Content(content))
                        }
                    }

                    // Reasoning tokens (DeepSeek-R1 style), also common as "reasoning"
                    val reasoning = delta.opt("reasoning_content") ?: delta.opt("reasoning")
                    if (reasoning is String && reasoning.isNotEmpty()) emit(StreamEvent.Reasoning(reasoning))

                    // Tool calls (streamed function-call deltas). Arguments
                    // arrive FRAGMENTED across deltas — accumulate them and
                    // emit ONE ToolCall with the complete query at the end.
                    val toolCalls = delta.optJSONArray("tool_calls")
                    if (toolCalls != null) {
                        for (t in 0 until toolCalls.length()) {
                            val tc = toolCalls.optJSONObject(t) ?: continue
                            val idx = tc.optInt("index", 0)
                            val id = tc.optJSONObject("id")
                            val callId = if (id is String && id.isNotBlank()) id
                            else pendingToolIds.getOrElse(idx) { "call_$idx" }
                            pendingToolIds[idx] = callId
                            val fn = tc.optJSONObject("function") ?: continue
                            if (fn.optString("name", "").isNotBlank()) {
                                pendingToolNames[idx] = fn.optString("name")
                            }
                            val args = fn.opt("arguments")
                            when (args) {
                                is String -> pendingToolArgs[idx] = (pendingToolArgs[idx] ?: "") + args
                                is org.json.JSONObject -> pendingToolArgs[idx] = args.toString()
                            }
                        }
                    }
                }
            }
            // Unterminated legacy block: the model truncated mid-call — drop it
            toolCallTextBuffer = null

            // Stream ended: assemble and emit any complete tool calls
            for ((idx, callId) in pendingToolIds) {
                val name = pendingToolNames[idx] ?: continue
                val rawArgs = pendingToolArgs[idx] ?: ""
                emit(StreamEvent.ToolCall(callId, name, rawArgs))
            }
        } catch (e: Exception) {
            emit(StreamEvent.Error(e.message ?: e.javaClass.simpleName))
        } finally {
            activeConn = null
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Parse the legacy TEXT tool-call format emitted by some models even on
     * cloud providers (Ollama Cloud, Groq — user report):
     *   <function=open_app><parameter=app_name>WhatsApp</parameter></function>
     * Returns null when malformed.
     */
    private fun parseLegacyToolCall(text: String): StreamEvent? {
        val nameMatch = Regex("<function=([a-zA-Z_][a-zA-Z0-9_]*)>").find(text)
            ?: return null
        val name = nameMatch.groupValues[1]
        val body = text.substring(nameMatch.range.last + 1)
            .substringBefore("</function>")
        val args = JSONObject()
        Regex("<parameter=([a-zA-Z_][a-zA-Z0-9_]*)>(.*?)</parameter>", RegexOption.DOT_MATCHES_ALL)
            .findAll(body).forEach { m ->
                args.put(m.groupValues[1], m.groupValues[2].trim())
            }
        if (args.length() == 0) {
            val rest = body.trim().removePrefix("(").removeSuffix(")")
            if (rest.isNotBlank()) args.put("query", rest)
        }
        return StreamEvent.ToolCall(
            "call_legacy_${System.currentTimeMillis()}", name, args.toString()
        )
    }
}
