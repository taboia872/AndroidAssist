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
import com.taboia872.androidassist.tts.TtsManager
import com.taboia872.androidassist.tts.FishTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
            // System prompt augmentation: models have no calendar. The
            // no-think directive rides along when enabled.
            val today = java.text.SimpleDateFormat(
                "yyyy-MM-dd (EEEE)", java.util.Locale.getDefault()
            ).format(java.util.Date())
            var basePrompt = AppSettings.config.value.systemPrompt +
                "\n\nCurrent date: $today."
            if (AppSettings.config.value.noThink) {
                basePrompt += "\n\nDo NOT produce any reasoning or thinking " +
                    "process; answer directly and concisely."
            }
            // Persistent memories (core instructions + preferences) ride the
            // system prompt; facts/skills stay retrievable via search_memories.
            val appCtx = ctx ?: context0
            if (appCtx != null) {
                basePrompt += MemoryStore.promptBlock(appCtx)
            }

            // Snapshot BEFORE the turn streams: every round replays the same
            // conversation + ALL completed tool exchanges (multi-search loop).
            val useTools = AppSettings.config.value.webSearchEnabled
            val history = messages.map { Triple(it.role, it.answerText, it.imageBase64) }


            var searchNotice: String? = null
            // ---- Single search round: tools declared; if the model calls,
            //      run the search and replay with the result attached. ----
            val callId = streamRound(history, basePrompt, useTools)
            if (callId != null) {
                val toolName = pendingToolName
                val toolHeader: String
                val toolResult: String
                if (toolName == "web_search") {
                    status = Strings.t(Strings.searching)
                    val searchQuery = try {
                        org.json.JSONObject(pendingToolArgs).optString("query", "").ifBlank { q }
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
                } else if (toolName == "save_memory" || toolName == "search_memories" ||
                    toolName == "forget_memory") {
                    // MEMORY TOOLS: local storage — no device Intent.
                    val c = ctx
                    if (c != null) {
                        val argsObj = try {
                            org.json.JSONObject(pendingToolArgs)
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
                                android.widget.Toast.makeText(
                                    c,
                                    "\ud83d\udcae " + com.taboia872.androidassist.i18n.Strings.t(
                                        com.taboia872.androidassist.i18n.Strings.memorySavedToast
                                    ) + " " + mem.text.take(80),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            "search_memories" -> {
                                val q0 = argsObj.optString("query", "")
                                val hits = withContext(Dispatchers.IO) {
                                    MemoryStore.search(c, q0)
                                }
                                toolHeader = "\ud83d\udd0e " + q0.ifBlank { "\u2026" }
                                toolResult = if (hits.isEmpty()) {
                                    "No memories match '" + q0 + "'."
                                } else {
                                    "Memories found:\n" + hits.joinToString("\n") {
                                        "[" + it.kind + "] " + it.text + " (id " + it.id + ")"
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
                                toolResult = if (gone) "Memory deleted: '" + target + "'."
                                else "No memory matched '" + target + "' — nothing deleted."
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
                    val res = if (c != null) {
                        // IO dispatcher: get_location waits for a GPS/network
                        // fix (seconds) and reverse-geocodes over HTTP —
                        // must not block the main thread.
                        withContext(Dispatchers.IO) {
                            DeviceTools.execute(c, toolName, pendingToolArgs)
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
                    callId = callId,
                    callName = toolName,
                    assistantContent = answerBeforeTool(),
                    result = toolResult
                ))
                // Final round with the tool result attached
                streamRound(history, basePrompt, toolsEnabled = false)
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

    /** Completed tool exchanges of the CURRENT turn (multi-search loop). */
    private val toolExchanges = mutableListOf<ToolExchange>()

    /**
     * Stream one round into the turn timeline. Returns the tool call id when
     * the model wants to search (call captured in the pendingTool* fields),
     * or null when the round finished without a tool call.
     */
    private suspend fun streamRound(
        history: List<Triple<String, String, String?>>,
        basePrompt: String,
        toolsEnabled: Boolean
    ): String? {
        var callId: String? = null
        pendingToolId = ""
        pendingToolName = ""
        pendingToolArgs = ""
        LlmClient.chat(
            history,
            basePrompt,
            toolsEnabled = toolsEnabled,
            toolExchanges = this.toolExchanges
        ).collect { event ->
            when (event) {
                is StreamEvent.Reasoning -> {
                    status = Strings.t(Strings.thinking)
                    streamAppend("reasoning", event.text)
                }
                is StreamEvent.Content -> {
                    status = Strings.t(Strings.typing)
                    streamAppend("answer", event.text)
                }
                is StreamEvent.ToolCall -> {
                    callId = event.id
                    pendingToolId = event.id
                    pendingToolName = event.name
                    pendingToolArgs = event.args
                }
                is StreamEvent.Error -> {
                    addBlock("answer", "\u26a0\ufe0f ${event.message}")
                }
            }
        }
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

    /** Answer text streamed so far in the CURRENT turn (for tool replay). */
    private fun answerBeforeTool(): String {
        if (turnMsgIdx !in messages.indices) return ""
        return messages[turnMsgIdx].blocks
            .filter { it.kind == "answer" }
            .joinToString("\n\n") { it.text }
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
