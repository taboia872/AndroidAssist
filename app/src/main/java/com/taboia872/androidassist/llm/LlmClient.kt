package com.taboia872.androidassist.llm

import com.taboia872.androidassist.net.Net
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.tools.ToolAbility
import com.taboia872.androidassist.tools.ToolCatalog
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
    val result: String,
    /**
     * The arguments the model passed, as the raw JSON string it sent.
     *
     * CRITICAL: replaying the call with `arguments = "{}"` hides the
     * parameters from the model — it then looks like the tool was called
     * with no arguments, and the model calls it AGAIN (the "tool spam").
     */
    val argsJson: String = "{}"
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
    /**
     * Is this a LOCAL endpoint (the llama.cpp server on the phone or on
     * the LAN)? The local model needs a much longer read timeout.
     */
    private fun isLocalEndpoint(baseUrl: String): Boolean {
        val u = baseUrl.lowercase()
        return u.contains("127.0.0.1") || u.contains("localhost") || u.contains("0.0.0.0")
    }

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
     *
     * Each spec carries the ABILITY it belongs to, so the user can switch
     * them ONE BY ONE (alarm without location, clipboard without map...).
     */
    private fun deviceToolSpecs(): List<Pair<String, JSONObject>> = listOf(
        ToolAbility.ALARM to tool(
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
        ToolAbility.TIMER to tool(
            "set_timer",
            "Start a countdown timer on the user's phone (e.g. 'remind me in 10 minutes', '5 minute egg timer').",
            "label" to "Short timer label",
            extraProps = mapOf(
                "seconds" to ("integer" to "Total duration in seconds")
            )
        ),
        ToolAbility.CALENDAR to tool(
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
        ToolAbility.OPEN_APP to tool(
            "open_app",
            "Open an installed app on the user's phone by name (e.g. WhatsApp, Spotify, Camera).",
            "app_name" to "App name as the user said it",
            required = listOf("app_name")
        ),
        ToolAbility.CLIPBOARD to tool(
            "read_clipboard",
            "Read the current clipboard text (e.g. when the user says 'translate this' or 'what does this say' with content copied).",
            "_" to "No parameters needed"
        ),
        ToolAbility.CLIPBOARD to tool(
            "add_to_clipboard",
            "Copy text to the clipboard so the user can paste it anywhere.",
            "text" to "The exact text to copy",
            required = listOf("text")
        ),
        ToolAbility.SHARE to tool(
            "share_text",
            "Open the Android share sheet with the given text so the user can send it to any app (WhatsApp, Telegram, email...).",
            "text" to "The text to share",
            required = listOf("text")
        ),
        ToolAbility.LOCATION to tool(
            "get_location",
            "Get the user's approximate current location (latitude/longitude, ~city block accuracy). Use it for local questions: transit (bus/metro stops and schedules), nearby places, weather. Coordinates are approximate and stay private.",
            "_" to "No parameters needed"
        ),
        ToolAbility.MAP to tool(
            "open_map",
            "Open the map on the user's phone. Give EITHER a place name (to search for it) OR a latitude/longitude pair (to show a precise point). When the user just says 'open the map', use a place name or the current location from get_location.",
            "place" to "Place name to search for, e.g. 'padaria perto de casa'. Leave empty when giving coordinates.",
            extraProps = mapOf(
                "latitude" to ("number" to "Latitude of the point (only with longitude)"),
                "longitude" to ("number" to "Longitude of the point (only with latitude)"),
                "label" to ("string" to "Short place label shown on the map pin")
            )
        ),
        ToolAbility.FILES to tool(
            "write_file",
            "Create or replace a TEXT file in the user's workspace folder (the folder they pointed at in Settings). Use it to save a report, a summary, a list or any text the user asks to keep.",
            "filename" to "File name with extension, e.g. notes.txt or resumo-2026-11-16.md",
            required = listOf("filename"),
            extraProps = mapOf(
                "content" to ("string" to "The full text to write into the file"),
                "folder" to ("string" to "Optional subfolder of the workspace to write into, e.g. Notes. Leave empty for the workspace root.")
            )
        ),
        ToolAbility.FILES to tool(
            "read_file",
            "Read a text file from the user's workspace folder. Use it before editing a file so you know its current content.",
            "filename" to "File name with extension, e.g. notes.txt",
            required = listOf("filename"),
            extraProps = mapOf(
                "folder" to ("string" to "Optional subfolder of the workspace to read from, e.g. Notes. Leave empty for the workspace root.")
            )
        ),
        ToolAbility.FILES to tool(
            "list_files",
            "List what is inside a folder of the user's workspace: the files AND the subfolders. Use it to discover what is there before reading or writing.",
            "folder" to "Optional subfolder to list, e.g. Notes. Leave empty to list the workspace root.",
            required = emptyList()
        ),
        ToolAbility.FILES to tool(
            "create_folder",
            "Create a FOLDER (subdirectory) inside the user's workspace folder. Use it when the user asks to organize files into a folder.",
            "name" to "Name of the new folder, e.g. Notas or Relatorios",
            required = listOf("name"),
            extraProps = mapOf(
                "folder" to ("string" to "Optional subfolder to create it inside. Leave empty to create it in the workspace root.")
            )
        ),
        ToolAbility.FILES to tool(
            "delete_file",
            "Delete a FILE from the user's workspace folder. Only use it when the user explicitly asks to delete or remove that file.",
            "filename" to "File name with extension, e.g. old-notes.txt",
            required = listOf("filename"),
            extraProps = mapOf(
                "folder" to ("string" to "Optional subfolder of the workspace to delete from, e.g. Notes. Leave empty for the workspace root.")
            )
        ),
        ToolAbility.FILES to tool(
            "delete_folder",
            "Delete a FOLDER from the user's workspace folder, together with everything inside it. Use it when the user asks to remove a directory or a subfolder.",
            "name" to "Name of the folder to delete, e.g. Notas",
            required = listOf("name"),
            extraProps = mapOf(
                "folder" to ("string" to "Optional subfolder of the workspace that CONTAINS the folder to delete. Leave empty for the workspace root.")
            )
        ),
        ToolAbility.OPEN_URL to tool(
            "open_url",
            "Open a web page on the device, in the browser. Use it when the user asks to open a link, a site, a YouTube video or playlist, or to see one of the results of a web search.",
            "url" to "The full address, starting with http:// or https://",
            required = listOf("url"),
            extraProps = mapOf(
                "title" to ("string" to "Optional label for the link (the title of the page), shown to the user.")
            )
        ),
        ToolAbility.SCHEDULE to tool(
            "schedule_task",
            "Schedule a task for the assistant to run BY ITSELF later (it works with the app closed). " +
                "Use it when the user asks for something to happen at a time or on a schedule " +
                "(a daily report, a reminder, a weekly summary). The task runs exactly as you would " +
                "answer it, and the result opens in its own conversation.",
            "title" to "Short title of the task (shown in the list)",
            required = listOf("title", "prompt", "time"),
            extraProps = mapOf(
                "prompt" to ("string" to "What the assistant must do at that time (a full instruction, in the user's language)"),
                "time" to ("string" to "The time of the day in 24h format, e.g. '17:00'"),
                "date" to ("string" to "The date for a ONE-TIME task, e.g. '2026-11-22'. Leave empty for daily/weekly."),
                "repeat" to ("string" to "once | daily | weekly (default: once when a date is given, daily otherwise)"),
                "days" to ("array" to "The days of the week for a weekly task: mon,tue,wed,thu,fri,sat,sun"),
                "tools" to ("array" to "The tools that task may use: web_search, alarm, timer, calendar, open_app, clipboard, share, location, map, files, messaging, open_url. Only the ones the user has enabled are accepted."),
                "persona" to ("string" to "Optional name of the persona (the assistant profile) to use for the task"),
                "context" to ("string" to "Optional instructions the model reads BEFORE the task (e.g. to tell it the answer is automatic and will be read aloud)."),
                "speak" to ("boolean" to "True to read the answer out loud when the task runs")
            )
        ),
        ToolAbility.SCHEDULE to tool(
            "list_tasks",
            "List the scheduled tasks of the assistant (what is scheduled, when it runs next, whether it is enabled). " +
                "Use it when the user asks what is scheduled, or to find the id of a task to cancel.",
            "query" to "Optional filter: a word of the title. Leave empty to list everything."
        ),
        ToolAbility.SCHEDULE to tool(
            "cancel_task",
            "Cancel a scheduled task (it stops running). Use the id from list_tasks.",
            "id" to "The id of the task (from list_tasks). If the id is unknown, the title is used instead.",
            required = listOf("id"),
            extraProps = mapOf(
                "title" to ("string" to "Optional title of the task to cancel")
            )
        ),
        ToolAbility.MESSAGING to tool(
            "send_message",
            "Send a message to a contact of the user's agenda (WhatsApp, Telegram, SMS — whichever messaging app is installed). The contact is chosen by NAME from the user's contacts, and the message opens ready to send — the user confirms it before it goes.",
            "contact" to "The contact's name as the user said it (partial name is fine)",
            required = listOf("contact"),
            extraProps = mapOf(
                "message" to ("string" to "The message text to send"),
                "app" to ("string" to "Optional messaging app to prefer: 'whatsapp', 'telegram', 'sms' — omit to let the user pick")
            )
        ),
    )

    /**
     * The tool specs of a request (the very same ones it sends) + the
     * names. Extracted so the PROMPT INSPECTOR shows exactly what
     * would go out — the specs can never drift apart.
     */
    private fun buildSpecs(
        toolsEnabled: Boolean,
        allowWebSearch: Boolean,
        allowedTools: List<String>?,
        webSearchOverride: Boolean?
    ): Pair<org.json.JSONArray, List<String>> {
        val specs = org.json.JSONArray()
        val names = mutableListOf<String>()
        if (!toolsEnabled) return Pair(specs, names)
        // Each tool group is INDEPENDENT: turning the web search off
        // must NOT hide the Android abilities, and vice-versa.
        val webAllowed = webSearchOverride
            ?: AppSettings.config.value.webSearchEnabled
        if (allowWebSearch && webAllowed &&
            (allowedTools == null || allowedTools.contains("web_search"))
        ) {
            specs.put(tool(
                "web_search",
                "Search the web for current information (news, prices, weather, " +
                    "recent events). Use when the question needs up-to-date facts.",
                "query" to "The search query, in the user's language",
                required = listOf("query")
            ))
        }
        // Android abilities — ONE BY ONE (the user can switch each).
        deviceToolSpecs().forEach { (ability, spec) ->
            if (AppSettings.abilityEnabled(ability) &&
                (allowedTools == null || allowedTools.contains(ability))
            ) specs.put(spec)
        }
        // Memory tools — their own group.
        if (AppSettings.config.value.memoryToolsEnabled &&
            (allowedTools == null || allowedTools.contains("memory"))
        ) {
            memoryToolSpecs().forEach { specs.put(it) }
        }
        for (i in 0 until specs.length()) {
            names += specs.getJSONObject(i)
                .getJSONObject("function").getString("name")
        }
        return Pair(specs, names)
    }

    /**
     * What a request would declare RIGHT NOW (the prompt inspector).
     */
    fun currentToolSpecs(): Pair<org.json.JSONArray, List<String>> = buildSpecs(
        toolsEnabled = AppSettings.anyToolEnabled(),
        allowWebSearch = true,
        allowedTools = null,
        webSearchOverride = null
    )

    /**
     * Memory tool specs — their OWN group, independent from the Android
     * abilities and from the web search.
     */
    private fun memoryToolSpecs(): List<JSONObject> = listOf(
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
     * supports it: the enabled tools are declared up front; if the model
     * wants fresh facts it emits a tool_call delta instead of guessing.
     * [toolsEnabled] gates the declaration; [toolExchanges] replays every
     * completed tool exchange (assistant tool_call + result) so the model
     * can chain MULTIPLE tools per turn.
     *
     * [allowWebSearch] is false on every round after the first: the web
     * search runs at most once per turn, but the Android abilities and the
     * memory tools stay declared.
     */
    fun chat(
        messages: List<Triple<String, String, String?>>,
        systemPrompt: String,
        toolsEnabled: Boolean = false,
        toolExchanges: List<ToolExchange> = emptyList(),
        allowWebSearch: Boolean = true,
        /**
         * The chat's own switch for the web search (null = the user's
         * setting). True turns it on even if the setting is off.
         */
        webSearchOverride: Boolean? = null,
        /**
         * The tools THIS request may declare (the ability ids +
         * "web_search" + "memory"). Null = the user's own settings. A
         * scheduled task passes its own list (chosen at creation time).
         */
        allowedTools: List<String>? = null,
        /**
         * A different configuration for THIS request (the scheduled
         * task falls back to the cloud when the local server is down).
         */
        cfgOverride: com.taboia872.androidassist.settings.AppConfig? = null,
        /**
         * The id of THIS conversation. The DuckProxy uses it to map the
         * conversation to a duck.ai chat (the app resends the whole history
         * on every turn, so the history itself cannot identify it).
         */
        conversationId: String? = null
    ): Flow<StreamEvent> = flow {
        val cfg = cfgOverride ?: AppSettings.config.value
        if (cfg.baseUrl.isBlank()) {
            emit(StreamEvent.Error("No base URL configured — open Settings."))
            return@flow
        }
        if (cfg.model.isBlank()) {
            emit(StreamEvent.Error("No model selected — open Settings."))
            return@flow
        }
        val keys = AppSettings.keysFor(cfg.providerId)
        if (LlmProviders.byId(cfg.providerId).requiresKey && keys.isEmpty()) {
            emit(StreamEvent.Error("No API key configured — open Settings."))
            return@flow
        }
        // The DuckProxy has its own dialect (see LlmProviders.DUCKPROXY).
        val isDuckProxy = cfg.providerId == LlmProviders.DUCKPROXY.id

        val body = JSONObject().apply {
            put("model", cfg.model)
            put("stream", true)
            // No-think mode (local thinking models): llama-server and
            // compatible servers accept chat_template_kwargs; others ignore
            // the extra field harmlessly.
            //
            // The DuckProxy reads reasoning_effort (it maps it to the
            // reasoning button of the site), so the switch works there too.
            if (AppSettings.config.value.noThink) {
                if (isDuckProxy) {
                    put("reasoning_effort", "none")
                } else {
                    put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
                }
            } else if (isDuckProxy) {
                // The switch is OFF: the model SHOULD think. The proxy only
                // turns the reasoning on when it is told to.
                put("reasoning_effort", "low")
            }
            // Tool specs + the NAMES actually declared. The names feed the
            // system prompt block (the model is told what it can call RIGHT
            // NOW) and are read back from the very same specs, so the prompt
            // and the declared tools can never drift apart.
            val (specs, declaredToolNames) = buildSpecs(
                toolsEnabled, allowWebSearch, allowedTools, webSearchOverride
            )
            if (specs.length() > 0) {
                put("tools", specs)
                put("tool_choice", "auto")
            }
            // The web search runs at most ONCE per turn: on the following
            // rounds it is neither declared nor disabled — it is already
            // spent, and the prompt says so (otherwise the model would think
            // the user switched it off).
            val usedUpToolNames = if (
                !allowWebSearch && AppSettings.config.value.webSearchEnabled
            ) listOf("web_search") else emptyList()
            val effectivePrompt = systemPrompt +
                ToolCatalog.availabilityBlock(
                    declaredToolNames, usedUpToolNames
                )
            put("messages", JSONArray().apply {
                if (effectivePrompt.isNotBlank()) {
                    put(JSONObject().put("role", "system").put("content", effectivePrompt))
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
                                // The REAL arguments the model sent — not "{}".
                                put("arguments", ex.argsJson.ifBlank { "{}" })
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
        //
        // KEY ROTATION: the candidate keys of this provider are tried in the
        // order of the global strategy. A key the server REJECTS (401/403/429)
        // never aborts the request — the next candidate is tried and only
        // when EVERY key fails the error surfaces.
        val strategy = AppSettings.config.value.keyRotationStrategy
        val slot = cfg.providerId
        val candidateKeys = if (keys.isEmpty()) listOf("") else keys
        val order = KeyRotation.candidates(slot, candidateKeys, strategy)

        var conn: HttpURLConnection? = null
        var chosenIdx = -1
        var lastErr = ""
        var lastCode = 0

        for (idx in order) {
            val key = candidateKeys[idx]
            val c = Net.open("${cfg.baseUrl.trimEnd('/')}/chat/completions").apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                // A LOCAL model (llama.cpp no celular) responde bem mais
                // devagar que a nuvem — 5 a 10 tok/s. Com 120 s o app
                // cortava a resposta no meio.
                // The DuckProxy drives a real browser: the answer can take
                // longer than the usual 120 s (its own limit is 180 s).
                readTimeout = if (isDuckProxy || isLocalEndpoint(cfg.baseUrl)) 300_000 else 120_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (key.isNotBlank()) setRequestProperty("Authorization", "Bearer $key")
                // The DuckProxy maps this id to a duck.ai chat. Without it,
                // the app (which resends the whole history on every turn)
                // would open a NEW chat on every message.
                if (isDuckProxy && !conversationId.isNullOrBlank()) {
                    setRequestProperty("X-Conversation-Id", conversationId)
                }
            }
            try {
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                if (c.responseCode == 200) {
                    conn = c
                    chosenIdx = idx
                    break
                }
                val code = c.responseCode
                val err = c.errorStream?.bufferedReader()?.readText()?.take(300) ?: "no body"
                lastCode = code
                lastErr = "HTTP $code: $err"
                c.disconnect()
                // 401/403/429 = the key was rejected or exhausted -> next one
                if (code == 401 || code == 403 || code == 429) {
                    KeyRotation.advanceOnFailure(slot, idx, strategy, candidateKeys.size)
                    continue
                }
                // Any other HTTP error is not a key problem -> give up
                emit(StreamEvent.Error(lastErr))
                return@flow
            } catch (e: Exception) {
                try { c.disconnect() } catch (e2: Exception) { }
                emit(StreamEvent.Error(e.message ?: e.javaClass.simpleName))
                return@flow
            }
        }

        val streamConn = conn
        if (streamConn == null) {
            // Every candidate key failed (401/403/429) — surface the last error
            emit(
                StreamEvent.Error(
                    if (lastErr.isNotBlank()) lastErr
                    else "HTTP $lastCode: no key worked"
                )
            )
            return@flow
        }
        // Only real keys count for the rotation state (local servers have none)
        if (chosenIdx >= 0 && keys.isNotEmpty()) {
            KeyRotation.markUsed(slot, chosenIdx, strategy)
        }

        // Legacy text-format tool calls (some cloud models): buffered while a
        // <function=...> block is open.
        var toolCallTextBuffer: String? = null

        // Streamed tool-call accumulators (arguments arrive in fragments)
        val pendingToolIds = mutableMapOf<Int, String>()
        val pendingToolNames = mutableMapOf<Int, String>()
        val pendingToolArgs = mutableMapOf<Int, String>()

        activeConn = streamConn
        try {
            // Decode with the charset the SERVER declared (falling back to
            // UTF-8). Using the platform default mangled accented text
            // whenever a provider answered with a different charset — the
            // model then wrote the mojibake into the file.
            val responseCharset = charsetOf(streamConn.contentType)
            BufferedReader(InputStreamReader(streamConn.inputStream, responseCharset)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.startsWith("data: ")) continue
                    val data = l.removePrefix("data: ").trim()
                    if (data == "[DONE]") break
                    if (data.isEmpty()) continue

                    // A server can report a failure INSIDE the stream
                    // (HTTP 200 + an error frame). The frame carries no
                    // "choices" — skipping it silently left the user with
                    // an empty answer and no explanation.
                    val frame = JSONObject(data)
                    val frameError = frame.optJSONObject("error")
                    if (frameError != null) {
                        emit(StreamEvent.Error(
                            frameError.optString("message", "The server reported an error.")
                        ))
                        break
                    }
                    val delta = frame
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
            streamConn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * The charset of an HTTP response, from its Content-Type.
     *
     * Falls back to UTF-8 (the JSON default) when the header is missing or
     * names a charset the JVM does not know.
     */
    private fun charsetOf(contentType: String?): java.nio.charset.Charset {
        if (!contentType.isNullOrBlank()) {
            val idx = contentType.indexOf("charset=", ignoreCase = true)
            if (idx >= 0) {
                val name = contentType.substring(idx + 8)
                    .split(';', ' ').first().trim()
                    .trim('"', '\'', ',')
                if (name.isNotBlank()) {
                    try {
                        return java.nio.charset.Charset.forName(name)
                    } catch (e: Exception) {
                        // Unknown charset -> keep UTF-8.
                    }
                }
            }
        }
        return java.nio.charset.StandardCharsets.UTF_8
    }

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
