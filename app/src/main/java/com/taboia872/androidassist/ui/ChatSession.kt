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

            // Snapshot BEFORE the turn streams: every round replays the same
            // conversation + ALL completed tool exchanges (multi-search loop).
            val useTools = AppSettings.config.value.webSearchEnabled
            val history = messages.map { Triple(it.role, it.answerText, it.imageBase64) }

            var searchNotice: String? = null
            var round = 0
            // loop guard uses toolExchanges below

            // ---- Search loop: round 1 with tools; after each tool result,
            //      replay with tools STILL enabled so the model may refine
            //      the query and search again (max 3 searches total) ----
            while (true) {
                val callId = streamRound(history, basePrompt, useTools && (round == 0 || toolExchanges.size < maxSearches))
                if (callId == null) break
                round++

                val searchQuery = pendingToolQuery
                status = Strings.t(Strings.searching)

                val outcome = SearchEngines.search(searchQuery)
                val detailForUser: String
                val toolResult: String
                when (outcome) {
                    is SearchOutcome.Ok -> {
                        if (outcome.results.isEmpty()) {
                            detailForUser = Strings.t(Strings.searchNoResults)
                            toolResult = "The web search returned NO results for " +
                                "\"$searchQuery\". " +
                                (if (toolExchanges.size + 1 < maxSearches)
                                    "Call web_search AGAIN with different, more specific terms."
                                else "Tell the user you could not find this information.")
                        } else {
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
                                if (toolExchanges.size + 1 < maxSearches) {
                                    append("\nIf these results do NOT answer the " +
                                        "question, call web_search again with " +
                                        "different, more specific search terms.")
                                }
                            }
                        }
                    }
                    is SearchOutcome.Failed -> {
                        val engine = AppSettings.config.value.searchEngine
                        searchNotice = "\u26a0\ufe0f Search ($engine): ${outcome.reason}"
                        detailForUser = outcome.reason
                        toolResult = "The web search FAILED: ${outcome.reason}. " +
                            (if (toolExchanges.size + 1 < maxSearches)
                                "You may call web_search ONE more time with different terms; " +
                                "if it fails again, tell the user the search is not working " +
                                "(quote the reason) — do NOT invent facts."
                            else "Tell the user the search is not working right now " +
                                "(quote the reason) — do NOT invent facts.")
                    }
                }

                // Tool block: numbered marker, results inside (tap to read)
                addBlock("tool", "\ud83d\udd0d ${toolExchanges.size + 1}x  $searchQuery", detailForUser)

                // The partial answer the model wrote before calling the tool
                // (if any) is captured in the exchange for replay.
                toolExchanges.add(ToolExchange(
                    callId = callId,
                    callName = "web_search",
                    assistantContent = answerBeforeTool(),
                    result = toolResult
                ))
            }

            // Search failure notice for the USER (last timeline block)
            searchNotice?.let { addBlock("answer", it) }

            finalizeTurn()
            status = ""

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

    /** Query of the tool call captured by the last streamRound. */
    private var pendingToolQuery = ""

    /** Completed tool exchanges of the CURRENT turn (multi-search loop). */
    private val toolExchanges = mutableListOf<ToolExchange>()

    /**
     * Stream one round into the turn timeline. Returns the tool call id when
     * the model wants to search (query captured in [pendingToolQuery]),
     * or null when the round finished without a tool call.
     */
    private fun streamRound(
        history: List<Triple<String, String, String?>>,
        basePrompt: String,
        toolsEnabled: Boolean
    ): String? {
        var callId: String? = null
        pendingToolQuery = ""
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
                    pendingToolQuery = event.query
                }
                is StreamEvent.Error -> {
                    addBlock("answer", "\u26a0\ufe0f ${event.message}")
                }
            }
        }
        return callId
    }

    /** Answer text streamed so far in the CURRENT turn (for tool replay). */
    private fun answerBeforeTool(): String {
        if (turnMsgIdx !in messages.indices) return ""
        return messages[turnMsgIdx].blocks
            .filter { it.kind == "answer" }
            .joinToString("\n\n") { it.text }
    }
}
