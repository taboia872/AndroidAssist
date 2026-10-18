package com.taboia872.androidassist.ui

import com.taboia872.androidassist.i18n.Strings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taboia872.androidassist.llm.LlmClient
import com.taboia872.androidassist.llm.StreamEvent
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.storage.ChatStore
import com.taboia872.androidassist.search.SearchEngines
import com.taboia872.androidassist.search.SearchOutcome
import com.taboia872.androidassist.tts.TtsManager
import com.taboia872.androidassist.tts.FishTts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    var messages by mutableStateOf(listOf<ChatMessage>())
        private set
    var busy by mutableStateOf(false)
        private set

    /** Short status label while busy. */
    var status by mutableStateOf("")
        private set

    private var chatId by mutableStateOf(UUID.randomUUID().toString())

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
        scope: CoroutineScope,
        imageBase64: String? = null
    ) {
        val q = query.trim()
        if (q.isEmpty() && imageBase64 == null) return
        if (busy) return
        busy = true
        turnMsgIdx = -1
        status = Strings.t(Strings.thinking)
        messages = messages + ChatMessage(
            "user",
            listOf(ChatBlock("answer", q.ifBlank { "What's in this image?" })),
            imageBase64 = imageBase64
        )

        scope.launch {
            try {
            // System prompt augmentation: models have no calendar.
            val today = java.text.SimpleDateFormat(
                "yyyy-MM-dd (EEEE)", java.util.Locale.getDefault()
            ).format(java.util.Date())
            val basePrompt = AppSettings.config.value.systemPrompt +
                "\n\nCurrent date: $today."

            // Snapshot BEFORE the turn streams: round 2 replays the same
            // conversation + the tool result (OpenAI function-calling shape).
            val useTools = AppSettings.config.value.webSearchEnabled
            val history = messages.map { Triple(it.role, it.answerText, it.imageBase64) }

            var toolCallId: String? = null
            var toolQuery = ""
            var searchNotice: String? = null

            // ---- Round 1 (tools declared): stream the timeline blocks ----
            LlmClient.chat(
                history,
                basePrompt,
                toolsEnabled = useTools
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
                        toolCallId = event.id
                        toolQuery = event.query
                    }
                    is StreamEvent.Error -> {
                        addBlock("answer", "\u26a0\ufe0f ${event.message}")
                    }
                }
            }

            // ---- Round 2 (tool result): search runs, tool block joins the
            //      SAME turn timeline, the answer streams after it ----
            val callId = toolCallId
            if (callId != null) {
                val searchQuery = toolQuery.ifBlank { q }
                status = Strings.t(Strings.searching)

                val outcome = SearchEngines.search(searchQuery)
                val detailForUser: String
                val toolResult: String
                when (outcome) {
                    is SearchOutcome.Ok -> {
                        detailForUser = buildString {
                            outcome.results.forEachIndexed { i, r ->
                                if (i > 0) append("\n\n")
                                append("${r.title}\n")
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
                            "(quote the reason) — do NOT invent facts."
                    }
                }

                // Tool block in the timeline: query on top, results inside
                addBlock("tool", "\ud83d\udd0d $searchQuery", detailForUser)

                LlmClient.chat(
                    history,
                    basePrompt,
                    toolsEnabled = false,
                    toolCallId = callId,
                    toolResult = toolResult
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
                        is StreamEvent.Error -> {
                            addBlock("answer", "\u26a0\ufe0f ${event.message}")
                        }
                        else -> {}
                    }
                }
            }

            // Search failure notice for the USER (last timeline block)
            searchNotice?.let { addBlock("answer", it) }

            finalizeTurn()
            status = ""

            // Speak the FINAL answer block only (not pre-search partials).
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
            } finally {
                // ALWAYS release the session — busy-stuck bug stays fixed.
                busy = false
                status = ""
            }
        }
    }
}
