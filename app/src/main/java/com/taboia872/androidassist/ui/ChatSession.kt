package com.taboia872.androidassist.ui

import com.taboia872.androidassist.i18n.Strings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taboia872.androidassist.llm.LlmClient
import com.taboia872.androidassist.llm.StreamEvent
import com.taboia872.androidassist.llm.ToolExchange
import kotlinx.coroutines.Job
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.storage.ChatStore
import com.taboia872.androidassist.search.SearchEngines
import com.taboia872.androidassist.search.SearchOutcome
import com.taboia872.androidassist.memory.MemoryStore
import com.taboia872.androidassist.tools.DeviceTools
import com.taboia872.androidassist.tools.ToolAbility
import com.taboia872.androidassist.tts.TtsManager
import com.taboia872.androidassist.tts.FishTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Decode the literal \uXXXX sequences that some providers leave inside the
 * tool arguments, in every string value of the object.
 *
 * When a provider sends the arguments as a JSON STRING
 * (`{"arguments": "{\"content\": \"caf\\u00e7\"}"}`), the inner JSON is
 * parsed as text and org.json does not decode the escapes — the file would
 * be written with the literal "caf\u00e7". This walks the object and
 * decodes them.
 */
internal fun decodeUnicodeEscapes(obj: org.json.JSONObject): org.json.JSONObject {
    val out = org.json.JSONObject()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val v = obj.opt(key)) {
            is String -> out.put(key, decodeUnicodeEscapes(v))
            is org.json.JSONObject -> out.put(key, decodeUnicodeEscapes(v))
            is org.json.JSONArray -> out.put(key, decodeUnicodeEscapes(v))
            null -> out.put(key, org.json.JSONObject.NULL)
            else -> out.put(key, v)
        }
    }
    return out
}

/** Decode the literal \uXXXX sequences in every string of a JSON array. */
internal fun decodeUnicodeEscapes(arr: org.json.JSONArray): org.json.JSONArray {
    val out = org.json.JSONArray()
    for (i in 0 until arr.length()) {
        when (val v = arr.opt(i)) {
            is String -> out.put(decodeUnicodeEscapes(v))
            is org.json.JSONObject -> out.put(decodeUnicodeEscapes(v))
            is org.json.JSONArray -> out.put(decodeUnicodeEscapes(v))
            null -> out.put(org.json.JSONObject.NULL)
            else -> out.put(v)
        }
    }
    return out
}

/** Decode every \uXXXX sequence in a string (e.g. "\u00e7" -> "ç"). */
internal fun decodeUnicodeEscapes(text: String): String {
    if (!text.contains("\\u")) return text
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 5 < text.length && text[i + 1] == 'u') {
            val code = text.substring(i + 2, i + 6).toIntOrNull(16)
            if (code != null && code in 0..0xFFFF) {
                if (code in 0xD800..0xDBFF) {
                    // A high surrogate: the pair with the low surrogate is
                    // the real character (e.g. an emoji).
                    val low = if (i + 11 < text.length &&
                        text[i + 6] == '\\' && text[i + 7] == 'u'
                    ) {
                        text.substring(i + 8, i + 12).toIntOrNull(16)
                    } else null
                    if (low != null && low in 0xDC00..0xDFFF) {
                        val point = 0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                        sb.appendCodePoint(point)
                        i += 12
                        continue
                    }
                    // A lone high surrogate is not a valid character:
                    // leave the text as it is.
                    sb.append(c)
                    i++
                    continue
                }
                if (code in 0xDC00..0xDFFF) {
                    // A lone low surrogate: also invalid on its own.
                    sb.append(c)
                    i++
                    continue
                }
                sb.append(code.toChar())
                i += 6
                continue
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/**
 * One renderable block in the chat TIMELINE. Every event of an assistant
 * turn is its own block, stacked one below the other: a thinking segment
 * is a "reasoning" block, a web search is a "tool" block (with the raw
 * results in [detail] — tap to read), each answer round an "answer" block.
 */
data class ChatBlock(
    val kind: String,       // "reasoning" | "answer" | "tool"
    val text: String,
    /** Live-streaming flag: the block is still being written. */
    val streaming: Boolean = false,
    /** Tool blocks: what the tool returned (search results / failure reason). */
    val detail: String = ""
)

data class ChatMessage(
    val role: String,
    val blocks: List<ChatBlock>,
    /** Attached image (JPEG base64) shown in the bubble and sent to vision models. */
    val imageBase64: String? = null
) {
    /** Flat answer text (LLM history). */
    val answerText: String
        get() = blocks.filter { it.kind == "answer" }.joinToString("\n\n") { it.text }
}

/**
 * One conversation: message list + streaming state + send() + persistence.
 * Shared by the main chat screen and the assistant overlay so both behave
 * identically. Chats auto-save after every assistant reply and can be
 * reloaded by id (history screens pass a stored chat in).
 *
 * TURN TIMELINE: one assistant message per send(). Blocks append into that
 * message in arrival order — reasoning, answer, tool, reasoning, answer —
 * each event its own block, nothing ever merges across kinds.
 */
class ChatSession(private val persistenceContext: android.content.Context? = null) {

    companion object {
        /**
         * HARD ceiling for tool calls per turn — the user setting
         * (AppSettings.maxToolCalls) is clamped to this range, so a
         * misconfigured value can never make the app loop forever.
         */
        const val MAX_TOOL_CALLS_HARD_LIMIT = 8
    }

    /**
     * OWN app-lifetime scope. Streaming and the end-of-turn save MUST NOT
     * depend on the caller's screen scope: navigating to Settings (or any
     * screen) used to cancel the in-flight answer and skip the save —
     * the "message cut" and "chat not saved" bugs. This scope lives as
     * long as the process.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var messages by mutableStateOf(listOf<ChatMessage>())
        private set
    var busy by mutableStateOf(false)
        private set

    /** Short status label while busy. */
    var status by mutableStateOf("")
        private set

    private var chatId by mutableStateOf(UUID.randomUUID().toString())

    /** The in-flight turn job — stop() cancels it (user STOP button). */
    private var turnJob: Job? = null


    /** Index of the assistant message being built for the CURRENT turn. */
    private var turnMsgIdx = -1

    private val ctx: android.content.Context?
        get() = persistenceContext

    /** Restore a saved chat by id (history "continue"). */
    fun loadFrom(context: android.content.Context, id: String) {
        val loaded = ChatStore.load(context, id) ?: return
        chatId = id
        turnMsgIdx = -1
        messages = loaded.map { m ->
            ChatMessage(
                m.role,
                m.blocks.map { ChatBlock(it.kind, it.text, detail = it.detail) },
                m.imageBase64
            )
        }
        status = ""
        ChatStore.setCurrentId(context, id)
    }

    /** Start a fresh chat (New Chat). */
    fun reset(context: android.content.Context? = null) {
        chatId = UUID.randomUUID().toString()
        messages = emptyList()
        status = ""
        busy = false
        turnMsgIdx = -1
        context?.let { ChatStore.setCurrentId(it, chatId) }
    }

    /**
     * User pressed STOP: cancel the streaming turn. Blocks streamed so far
     * stay visible (finalized, not streaming); no partial save.
     */
    fun stop() {
        // Abort the HTTP stream FIRST: job.cancel alone leaves the local
        // server generating (llama.cpp report) — disconnect makes the
        // blocked read() throw and the server sees the client gone.
        LlmClient.abortActive()
        turnJob?.cancel()
        turnJob = null
        finalizeTurn()
        busy = false
        status = ""
    }

    // ----------------------------------------------- turn timeline core

    /** The turn message exists from the first streamed event on. */
    private fun ensureTurnMessage() {
        if (turnMsgIdx in messages.indices &&
            messages[turnMsgIdx].role == "assistant"
        ) return
        messages = messages + ChatMessage("assistant", emptyList())
        turnMsgIdx = messages.size - 1
    }

    /**
     * Append streamed [text] of [kind] to the timeline. Continues the last
     * streaming block when it is the SAME kind; otherwise closes it and
     * starts a NEW block — so a thinking segment is ONE block (however many
     * tokens it arrives in) and the answer NEVER leaks into a reasoning
     * block.
     */
    private fun streamAppend(kind: String, text: String) {
        ensureTurnMessage()
        val m = messages[turnMsgIdx]
        val last = m.blocks.lastOrNull()
        if (last != null && last.streaming && last.kind == kind) {
            val blocks = m.blocks.toMutableList()
            blocks[blocks.size - 1] = last.copy(text = last.text + text)
            messages = messages.toMutableList().also { it[turnMsgIdx] = m.copy(blocks = blocks) }
        } else {
            messages = messages.toMutableList().also {
                it[turnMsgIdx] = m.copy(
                    blocks = m.blocks.map { b -> b.copy(streaming = false) } +
                        ChatBlock(kind, text, streaming = true)
                )
            }
        }
    }

    /** Append a COMPLETE block (tool marker with its results, notices). */
    private fun addBlock(kind: String, text: String, detail: String = "") {
        ensureTurnMessage()
        val m = messages[turnMsgIdx]
        messages = messages.toMutableList().also {
            it[turnMsgIdx] = m.copy(
                blocks = m.blocks.map { b -> b.copy(streaming = false) } +
                    ChatBlock(kind, text, streaming = false, detail = detail)
            )
        }
    }

    /** End of turn: clear streaming flags. */
    private fun finalizeTurn() {
        if (turnMsgIdx in messages.indices) {
            val m = messages[turnMsgIdx]
            if (m.blocks.any { it.streaming }) {
                messages = messages.toMutableList().also {
                    it[turnMsgIdx] = m.copy(
                        blocks = m.blocks.map { b -> b.copy(streaming = false) }
                    )
                }
            }
        }
        turnMsgIdx = -1
    }

    // ---------------------------------------------------------------- send

    fun send(
        query: String,
        imageBase64: String? = null
    ) {
        val q = query.trim()
        if (q.isEmpty() && imageBase64 == null) return
        if (busy) return
        busy = true
        turnMsgIdx = -1
        toolExchanges.clear()
        status = Strings.t(Strings.thinking)
        messages = messages + ChatMessage(
            "user",
            listOf(ChatBlock("answer", q.ifBlank { "What's in this image?" })),
            imageBase64 = imageBase64
        )

        turnJob = scope.launch {
            try {
            // System prompt augmentation: models have no clock. The
            // date AND the local time ride along, so the model can answer
            // "what time is it" and do time-based reasoning (schedules,
            // "in 2 hours", "tomorrow at 7"). The no-think directive rides
            // along when enabled.
            val now = java.util.Date()
            val today = java.text.SimpleDateFormat(
                "yyyy-MM-dd (EEEE)", java.util.Locale.getDefault()
            ).format(now)
            val clock = java.text.SimpleDateFormat(
                "HH:mm", java.util.Locale.getDefault()
            ).format(now)
            val zone = java.util.TimeZone.getDefault().id
            var basePrompt = AppSettings.config.value.systemPrompt +
                "\n\nCurrent date: $today.\nCurrent time: $clock ($zone)."
            if (AppSettings.config.value.noThink) {
                basePrompt += "\n\nDo NOT produce any reasoning or thinking " +
                    "process; answer directly and concisely."
            }
            // Persistent memories (core instructions + preferences) ride the
            // system prompt; facts/skills stay retrievable via search_memories.
            ctx?.let { basePrompt += MemoryStore.promptBlock(it) }

            // Snapshot BEFORE the turn streams: every round replays the same
            // conversation + ALL completed tool exchanges (multi-search loop).
            //
            // TOOL GROUPS ARE INDEPENDENT: the request declares tools when ANY
            // group is on (web search, Android abilities, memories). The old
            // gate used webSearchEnabled alone, so turning the search off
            // also hid EVERY Android tool from the model (user report).
            val useTools = AppSettings.anyToolEnabled()
            // How many tool calls this turn may make (user setting).
            val maxToolCalls = AppSettings.config.value.maxToolCalls
                .coerceIn(1, MAX_TOOL_CALLS_HARD_LIMIT)
            // How many conversation turns go into the context (user setting).
            // 0 = send everything.
            val maxContextTurns = AppSettings.config.value.maxContextTurns
                .coerceAtLeast(0)
            val history = buildHistory(messages, maxContextTurns)


            var searchNotice: String? = null
            // ---- Tool rounds: the model declares a tool, we run it and
            //      replay the answer with the result attached. Up to
            //      maxToolCalls rounds (user setting) so several abilities
            //      can fire in one turn (alarm AND open app). The WEB
            //      SEARCH only runs in the FIRST round (no search-retry
            //      loop — user request).
            var callId = streamRound(history, basePrompt, useTools, allowWebSearch = true)
            var toolRound = 0
            // The tool+arguments pairs already used in THIS turn. A repeat
            // is refused: a model that does not see its own arguments
            // (or that got a useless result) would call the same tool
            // forever — the "spamming tool after tool" behaviour.
            val usedToolCalls = mutableSetOf<String>()
            // The web search runs at most ONCE per turn.
            var webSearchUsedThisTurn = false
            // play_media ALSO runs at most ONCE per turn: it OPENS a
            // player, so calling it again with a different title does
            // not make sense — the first call either opened the player or
            // it did not. The model was calling it three times in a row
            // ("?" -> "a text with two tracks" -> the right name) and
            // then claiming the music was not in the library.
            var playMediaUsedThisTurn = false
            while (callId != null && toolRound < maxToolCalls) {
                toolRound++
                // EVERY call the model made in THIS round. A model can ask
                // for several tools at once; running only the first one
                // made it ask again on the next round (the "tool spam").
                val callsThisRound = pendingToolCalls.ifEmpty {
                    listOf(Triple(callId, pendingToolName, pendingToolArgs))
                }
                for ((theCallId, toolName, toolArgs) in callsThisRound) {
                    val callSignature = "$toolName|${toolArgs.ifBlank { "{}" }}"
                    // What the model wrote BEFORE this tool call (per round).
                    val assistantContent = roundAnswerText()
                    val toolHeader: String
                    val toolResult: String
                    val knownTool = toolName == "web_search" ||
                        toolName in ToolAbility.MEMORY_TOOLS ||
                        ToolAbility.of(toolName) != null
                    if (!usedToolCalls.add(callSignature)) {
                        // The SAME tool with the SAME arguments was already run
                        // in this turn. Refuse it and make the model answer.
                        toolHeader = "\ud83d\udd12 $toolName (repetida)"
                        toolResult = "You already called '$toolName' with these exact " +
                            "arguments in this turn. Do NOT call it again. Use the result " +
                            "you already have and answer the user now."
                        addBlock("tool", toolHeader, "")
                    } else if (knownTool && !AppSettings.toolAllowed(toolName)) {
                        // The user switched this ability OFF: refuse with an
                        // explanation instead of running it.
                        toolHeader = "\ud83d\udd12 $toolName"
                        toolResult = "The tool '$toolName' is DISABLED by the user " +
                            "(Settings > Tools). Do NOT use it and do NOT pretend it " +
                            "worked — tell the user it is turned off."
                        addBlock("tool", toolHeader, "")
                    } else if (toolName == "play_media" && playMediaUsedThisTurn) {
                        // The player was already opened (or already
                        // refused) in this turn. Do not open it again —
                        // tell the model to answer with what it has.
                        toolHeader = "\ud83d\udd12 play_media (repetida)"
                        toolResult = "play_media already ran in this turn, so the " +
                            "player was already opened (or already failed). Do NOT " +
                            "call it again with a different title — answer the user " +
                            "now with the result you already have. If the track was " +
                            "not found, say which tracks ARE available (use " +
                            "list_media) instead of trying again."
                        addBlock("tool", toolHeader, "")
                    } else if (toolName == "web_search" && webSearchUsedThisTurn) {
                        // The search already ran in this turn (the model asked
                        // for it twice in the same round).
                        toolHeader = "\ud83d\udd12 web_search (repetida)"
                        toolResult = "The web search already ran in this turn. Do NOT " +
                            "search again — use the results you already have and answer."
                        addBlock("tool", toolHeader, "")
                    } else if (toolName == "web_search") {
                        webSearchUsedThisTurn = true
                        status = Strings.t(Strings.searching)
                        val searchQuery = try {
                            org.json.JSONObject(toolArgs).optString("query", "").ifBlank { q }
                        } catch (e: Exception) { q }
                        val outcome = SearchEngines.search(searchQuery)
                        val detailForUser: String
                        when (outcome) {
                            is SearchOutcome.Ok -> {
                                detailForUser = buildString {
                                    outcome.results.forEachIndexed { i, r ->
                                        if (i > 0) append("\n\n")
                                        append("${i + 1}. ${r.title}\n")
                                        append("${r.url}\n")
                                        append(r.snippet)
                                    }
                                }
                                toolResult = buildString {
                                    append("Web search results for \"$searchQuery\":\n")
                                    outcome.results.forEach { r ->
                                        append("\n---\n")
                                        append("Title: ${r.title}\n")
                                        append("URL: ${r.url}\n")
                                        append("Snippet: ${r.snippet}")
                                    }
                                    append("\n---\n")
                                }
                            }
                            is SearchOutcome.Failed -> {
                                val engine = AppSettings.config.value.searchEngine
                                searchNotice = "\u26a0\ufe0f Search ($engine): ${outcome.reason}"
                                detailForUser = outcome.reason
                                toolResult = "The web search FAILED: ${outcome.reason}. " +
                                    "Tell the user the search is not working right now " +
                                    "(quote the reason) - do NOT invent facts."
                            }
                        }
                        toolHeader = "\ud83d\udd0d $searchQuery"
                        addBlock("tool", toolHeader, detailForUser)
                    } else if (toolName in ToolAbility.MEMORY_TOOLS) {
                        // MEMORY TOOLS: local storage — no device Intent.
                        val c = ctx
                        if (c != null) {
                            val argsObj = try {
                                // The provider may deliver "arguments" as a
                                // JSON STRING, in which case org.json leaves
                                // the inner \uXXXX escapes literal ("caf\u00e7"
                                // instead of "café"). Decode them here.
                                decodeUnicodeEscapes(org.json.JSONObject(toolArgs))
                            } catch (e: Exception) { org.json.JSONObject() }
                            when (toolName) {
                                "save_memory" -> {
                                    val kind = argsObj.optString("kind", "fact")
                                    val text = argsObj.optString("text", "")
                                    val mem = withContext(Dispatchers.IO) {
                                        MemoryStore.add(c, kind, text, source = "model")
                                    }
                                    toolHeader = "\ud83d\udcae " + mem.kind + ": " + mem.text.take(60)
                                    toolResult = "Memory saved (kind=" + mem.kind + "): '" +
                                        mem.text + "'. Apply " +
                                        (if (mem.kind == "core") "this instruction from NOW ON in every reply"
                                         else "this when relevant") + "."
                                    addBlock("tool", toolHeader, "")
                                }
                                "search_memories" -> {
                                    val q0 = argsObj.optString("query", "")
                                    val hits = withContext(Dispatchers.IO) {
                                        MemoryStore.search(c, q0)
                                    }
                                    toolHeader = "\ud83d\udd0e " + q0.ifBlank { "\u2026" }
                                    toolResult = if (hits.isEmpty()) {
                                        "No memories match '$q0'."
                                    } else {
                                        "Memories found:\n" + hits.joinToString("\n") {
                                            "[${it.kind}] ${it.text} (id ${it.id})"
                                        }
                                    }
                                    addBlock("tool", toolHeader, toolResult)
                                }
                                "forget_memory" -> {
                                    val target = argsObj.optString("id_or_text", "")
                                    val gone = withContext(Dispatchers.IO) {
                                        MemoryStore.forget(c, target)
                                    }
                                    toolHeader = "\ud83e\udde9 " + target.take(60)
                                    toolResult = if (gone) "Memory deleted: '$target'."
                                    else "No memory matched '$target' — nothing deleted."
                                    addBlock("tool", toolHeader, "")
                                }
                                else -> {
                                    toolHeader = toolName
                                    toolResult = "Unknown memory tool."
                                    addBlock("tool", toolHeader, "")
                                }
                            }
                        } else {
                            toolHeader = toolName
                            toolResult = "Cannot access memories in this context."
                            addBlock("tool", toolHeader, "")
                        }
                    } else {
                        // DEVICE TOOL: executed locally via Android Intents (alarm,
                        // timer, calendar, open app, clipboard, share).
                        val c = ctx
                        // play_media opens a player: it makes sense at most
                        // ONCE per turn, whatever the title.
                        if (toolName == "play_media") playMediaUsedThisTurn = true
                        val res = if (c != null) {
                            // IO dispatcher: get_location waits for a GPS/network
                            // fix (seconds) and reverse-geocodes over HTTP —
                            // must not block the main thread.
                            withContext(Dispatchers.IO) {
                                DeviceTools.execute(c, toolName, toolArgs)
                            }
                        } else {
                            DeviceTools.Result(
                                false, toolName,
                                "Cannot run device tools in this context.", "search"
                            )
                        }
                        toolHeader = res.userText
                        toolResult = res.modelText
                        addBlock("tool", toolHeader, "")
                }

                toolExchanges.add(ToolExchange(
                    callId = theCallId,
                    callName = toolName,
                    assistantContent = assistantContent,
                    result = toolResult,
                    // The REAL arguments: without them the model sees its
                    // own call replayed with "{}" and calls the tool again.
                    argsJson = toolArgs.ifBlank { "{}" }
                ))
                }
                // Next round: the abilities stay declared (the model may call
                // another one), but the web search does NOT (single search
                // per turn).
                callId = streamRound(history, basePrompt, useTools, allowWebSearch = false)
            }
            // Round limit reached with a tool call still pending: ask the
            // model for a final answer WITHOUT tools, so the turn always
            // ends with a reply instead of a dangling call.
            if (callId != null) {
                streamRound(history, basePrompt, toolsEnabled = false, allowWebSearch = false)
            }

            // Search failure notice for the USER (last timeline block)
            searchNotice?.let { addBlock("answer", it) }

            finalizeTurn()
            status = ""

            // Heads-up: the model finished while the app was backgrounded —
            // tell the user WITHOUT leaking the answer into the notification
            // (privacy: shade can show over other apps / lock screen).
            if (!ChatSessions.appInForeground) {
                notifyAnswered()
            }

            // Speak the FINAL answer block only.
            val lastMsg = messages.lastOrNull { it.role == "assistant" }
            val lastAnswer = lastMsg?.blocks?.lastOrNull { it.kind == "answer" }?.text
            if (!lastAnswer.isNullOrBlank() &&
                !lastAnswer.startsWith("\u26a0\ufe0f") &&
                AppSettings.config.value.ttsEnabled
            ) {
                when (AppSettings.config.value.ttsMethod) {
                    "fish" -> scope.launch { FishTts.speak(lastAnswer) }
                    else -> {
                        TtsManager.applyLanguage(AppSettings.config.value.ttsLanguage)
                        TtsManager.speak(lastAnswer)
                    }
                }
            }

            // Auto-save the finished exchange (skip pure-error turns)
            val c = ctx
            if (c != null && messages.isNotEmpty() &&
                messages.none { m -> m.blocks.any { it.text.startsWith("\u26a0\ufe0f") } }
            ) {
                withContext(Dispatchers.IO) {
                    ChatStore.save(
                        c, chatId,
                        messages.map { m ->
                            ChatStore.StoredMessage(
                                m.role,
                                m.blocks.map { ChatStore.Block(it.kind, it.text, it.detail) },
                                m.imageBase64
                            )
                        }
                    )
                    ChatStore.setCurrentId(c, chatId)
                }
            }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // STOP pressed: keep what was streamed, release the session.
                finalizeTurn()
            } finally {
                // ALWAYS release the session — busy-stuck bug stays fixed.
                turnJob = null
                busy = false
                status = ""
            }
        }
    }

    // ------------------------------------------------------------ internals

    /** Tool call captured by the last streamRound: (id, name, args JSON). */
    private var pendingToolId = ""
    private var pendingToolName = ""
    private var pendingToolArgs = ""

    /**
     * EVERY tool call of the last round. A model can ask for several tools
     * in one response — keeping only the last pair LOST the others, so the
     * model asked again on the next round, forever ("tool spam").
     */
    private var pendingToolCalls: List<Triple<String, String, String>> = emptyList()

    /** Completed tool exchanges of the CURRENT turn (multi-search loop). */
    private val toolExchanges = mutableListOf<ToolExchange>()

    /** Answer text streamed in the CURRENT round (reset at every round). */
    private var roundAnswer = ""

    /**
     * Stream one round into the turn timeline. Returns the tool call id when
     * the model wants a tool (call captured in the pendingTool* fields),
     * or null when the round finished without a tool call.
     *
     * [allowWebSearch] is false on every round AFTER the first one: the web
     * search runs at most once per turn, but the Android abilities stay
     * available so the model can chain them (alarm + open app).
     */
    private suspend fun streamRound(
        history: List<Triple<String, String, String?>>,
        basePrompt: String,
        toolsEnabled: Boolean,
        allowWebSearch: Boolean
    ): String? {
        var callId: String? = null
        pendingToolId = ""
        pendingToolName = ""
        pendingToolArgs = ""
        // EVERY call of this round (the model can ask for several at once).
        val calls = mutableListOf<Triple<String, String, String>>()
        // Reset BEFORE the stream: if this round asks for no tool at all,
        // the list must be EMPTY (not the previous round's calls).
        pendingToolCalls = emptyList()
        // Content streamed in THIS round (before a possible tool call).
        roundAnswer = ""
        LlmClient.chat(
            history,
            basePrompt,
            toolsEnabled = toolsEnabled,
            toolExchanges = this.toolExchanges,
            allowWebSearch = allowWebSearch
        ).collect { event ->
            when (event) {
                is StreamEvent.Reasoning -> {
                    status = Strings.t(Strings.thinking)
                    streamAppend("reasoning", event.text)
                }
                is StreamEvent.Content -> {
                    status = Strings.t(Strings.typing)
                    roundAnswer += event.text
                    streamAppend("answer", event.text)
                }
                is StreamEvent.ToolCall -> {
                    callId = event.id
                    pendingToolId = event.id
                    pendingToolName = event.name
                    pendingToolArgs = event.args
                    // Keep EVERY call: several tools can be requested in the
                    // same response and all of them must be executed.
                    calls.add(Triple(event.id, event.name, event.args))
                }
                is StreamEvent.Error -> {
                    addBlock("answer", "\u26a0\ufe0f ${event.message}")
                }
            }
        }
        pendingToolCalls = calls
        return callId
    }

    /** "The model answered" notification (no content) when backgrounded. */
    private fun notifyAnswered() {
        val c = ctx ?: return
        val notificationManager =
            c.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
        val channelId = "assistant_answers"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Model answers",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
        val intent = android.content.Intent(c, com.taboia872.androidassist.MainActivity::class.java)
        val pending = android.app.PendingIntent.getActivity(
            c, 0, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val title = com.taboia872.androidassist.i18n.Strings.t(
            com.taboia872.androidassist.i18n.Strings.modelAnsweredTitle)
        val text = com.taboia872.androidassist.i18n.Strings.t(
            com.taboia872.androidassist.i18n.Strings.modelAnsweredBody)
        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(c, channelId)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(c)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            notificationManager.notify(1001, notification)
        } catch (e: SecurityException) {
            // Notification permission not granted (API 33+) — skip silently
        }
    }

    /**
     * The answer text streamed in the CURRENT round (for the tool replay).
     * Per round, not per turn: in a chained turn (alarm then open app) each
     * tool call carries only what the model wrote before IT.
     */
    private fun roundAnswerText(): String = roundAnswer

    /**
     * The conversation history sent to the model, limited to the last
     * [maxTurns] conversation turns (user+assistant exchanges).
     *
     * 0 = send everything. The chat FILE keeps every message — this only
     * trims what goes into the request, so a long chat does not grow the
     * prompt forever (faster and cheaper on local models).
     */
    private fun buildHistory(
        all: List<ChatMessage>,
        maxTurns: Int
    ): List<Triple<String, String, String?>> {
        val full = all.map { Triple(it.role, it.answerText, it.imageBase64) }
        if (maxTurns <= 0 || all.size <= maxTurns * 2) return full
        // Keep the LAST maxTurns user messages and the assistant replies
        // around them — drop only the oldest tail.
        val keepFrom = all.size - (maxTurns * 2)
        return full.subList(keepFrom, full.size).toList()
    }
}

/**
 * App-scoped chat sessions: `main` (MainActivity) and `overlay` (assistant
 * gesture). Created once per process — streaming survives screen changes
 * and backgrounding because the session owns its coroutine scope.
 */
object ChatSessions {

    /** App in the FOREGROUND? Activities flip this in onResume/onPause. */
    @Volatile var appInForeground = false
    @Volatile private var initialized = false
    lateinit var main: ChatSession
        private set
    lateinit var overlay: ChatSession
        private set

    fun init(context: android.content.Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            main = ChatSession(app)
            overlay = ChatSession(app)
            initialized = true
        }
    }
}
