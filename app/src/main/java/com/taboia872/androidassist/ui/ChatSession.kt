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
 * One renderable block inside a message bubble. Every thinking segment,
 * every tool call and every answer round gets its OWN block — the
 * llama.cpp-server chat model: simple chats render one answer block,
 * agentic chats render the full think/answer/tool/answer chain.
 */
data class ChatBlock(
    val kind: String,       // "reasoning" | "answer" | "tool"
    val text: String,
    /** Live-streaming flag: the block is still being written. */
    val streaming: Boolean = false
)

data class ChatMessage(
    val role: String,
    val blocks: List<ChatBlock>,
    /** Attached image (JPEG base64) shown in the bubble and sent to vision models. */
    val imageBase64: String? = null
) {
    /** Flat answer text (LLM history + TTS). */
    val answerText: String
        get() = blocks.filter { it.kind == "answer" }.joinToString("\n\n") { it.text }
}

/**
 * One conversation: message list + streaming state + send() + persistence.
 * Shared by the main chat screen and the assistant overlay so both behave
 * identically. Chats auto-save after every assistant reply and can be
 * reloaded by id (history screens pass a stored chat in).
 */
class ChatSession(private val persistenceContext: android.content.Context? = null) {

    var messages by mutableStateOf(listOf<ChatMessage>())
        private set
    var busy by mutableStateOf(false)
        private set

    /** Short status label: Strings.t(Strings.thinking) / Strings.t(Strings.typing) while busy. */
    var status by mutableStateOf("")
        private set

    private var chatId by mutableStateOf(UUID.randomUUID().toString())

    private val ctx: android.content.Context?
        get() = persistenceContext

    /** Restore a saved chat by id (history "continue"). */
    fun loadFrom(context: android.content.Context, id: String) {
        val loaded = ChatStore.load(context, id) ?: return
        chatId = id
        messages = loaded.map { m ->
            ChatMessage(m.role, m.blocks.map { ChatBlock(it.kind, it.text) }, m.imageBase64)
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
        context?.let { ChatStore.setCurrentId(it, chatId) }
    }

    // -------- live block append helpers (streaming UI) --------

    private fun appendBlock(block: ChatBlock): Int {
        messages = messages + ChatMessage("assistant", listOf(block))
        return messages.size - 1
    }

    private fun appendToBlock(msgIdx: Int, text: String) {
        if (msgIdx !in messages.indices) return
        messages = messages.toMutableList().also {
            val m = it[msgIdx]
            val blocks = m.blocks.toMutableList()
            val lastIdx = blocks.indexOfLast { b -> b.streaming }
            if (lastIdx >= 0) {
                blocks[lastIdx] = blocks[lastIdx].copy(text = blocks[lastIdx].text + text)
            } else {
                blocks.add(ChatBlock("answer", text, streaming = true))
            }
            it[msgIdx] = m.copy(blocks = blocks)
        }
    }

    private fun finalizeBlocks() {
        messages = messages.map { msg ->
            if (msg.blocks.any { it.streaming })
                msg.copy(blocks = msg.blocks.map { it.copy(streaming = false) })
            else msg
        }
    }

    fun send(
        query: String,
        scope: CoroutineScope,
        imageBase64: String? = null
    ) {
        val q = query.trim()
        if (q.isEmpty() && imageBase64 == null) return
        if (busy) return
        busy = true
        status = Strings.t(Strings.thinking)
        messages = messages + ChatMessage(
            "user",
            listOf(ChatBlock("answer", q.ifBlank { "What's in this image?" })),
            imageBase64 = imageBase64
        )

        scope.launch {
            try {
            // System prompt augmentation: models have no calendar — give
            // them today's date so "que dia é hoje" works without search.
            val today = java.text.SimpleDateFormat(
                "yyyy-MM-dd (EEEE)", java.util.Locale.getDefault()
            ).format(java.util.Date())
            val basePrompt = AppSettings.config.value.systemPrompt +
                "\n\nCurrent date: $today."

            // FUNCTION CALLING (OpenAI tools pattern) with BLOCK ACCUMULATION:
            // each streamed segment (reasoning / answer / tool call) becomes its
            // own block; agentic rounds append NEW messages, nothing is replaced.
            val useTools = AppSettings.config.value.webSearchEnabled
            val history = messages.map { Triple(it.role, it.answerText, it.imageBase64) }

            var toolCall: Pair<String, String>? = null  // (id, query)
            var searchNotice: String? = null

            // Round 1
            var streamMsgIdx = -1
            LlmClient.chat(
                history,
                basePrompt,
                toolsEnabled = useTools
            ).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        status = Strings.t(Strings.typing)
                        if (streamMsgIdx < 0) {
                            streamMsgIdx = appendBlock(ChatBlock("answer", event.text, streaming = true))
                        } else {
                            appendToBlock(streamMsgIdx, event.text)
                        }
                    }
                    is StreamEvent.Reasoning -> {
                        // Every reasoning segment = its own block. A new
                        // reasoning burst while an answer block is streaming
                        // closes the answer and starts a fresh block chain in
                        // a NEW message (agentic think->answer->think rounds).
                        if (streamMsgIdx < 0) {
                            streamMsgIdx = appendBlock(ChatBlock("reasoning", event.text, streaming = true))
                        } else {
                            val m = messages[streamMsgIdx]
                            val lastIsReasoning = m.blocks.lastOrNull()?.kind == "reasoning" &&
                                m.blocks.lastOrNull()?.streaming == true
                            if (lastIsReasoning) {
                                appendToBlock(streamMsgIdx, event.text)
                            } else {
                                // close current block, open reasoning in same message
                                messages = messages.toMutableList().also {
                                    val mm = it[streamMsgIdx]
                                    it[streamMsgIdx] = mm.copy(blocks = mm.blocks.map { b -> b.copy(streaming = false) })
                                }
                                messages = messages.toMutableList().also {
                                    val mm = it[streamMsgIdx]
                                    it[streamMsgIdx] = mm.copy(blocks = mm.blocks + ChatBlock("reasoning", event.text, streaming = true))
                                }
                            }
                        }
                    }
                    is StreamEvent.ToolCall -> { toolCall = event.id to event.query }
                    is StreamEvent.Error -> {
                        appendBlock(ChatBlock("answer", "\u26a0\ufe0f ${event.message}"))
                        status = ""
                    }
                }
            }

            // Round 2 (only if the model called the tool)
            val call = toolCall
            if (call != null) {
                val searchQuery = call.second.ifBlank { q }
                status = Strings.t(Strings.searching)

                // Tool block: visible marker of what the model searched
                messages = messages + ChatMessage(
                    "assistant",
                    listOf(ChatBlock("tool", "\ud83d\udd0d $searchQuery"))
                )

                val outcome = SearchEngines.search(searchQuery)
                val toolResult = when (outcome) {
                    is SearchOutcome.Ok -> {
                        buildString {
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
                        "The web search FAILED: ${outcome.reason}. " +
                            "Tell the user the search is not working right now " +
                            "(quote the reason) — do NOT invent facts."
                    }
                }

                LlmClient.chat(
                    history,
                    basePrompt,
                    toolsEnabled = false,
                    toolCallId = call.first,
                    toolResult = toolResult
                ).collect { event ->
                    when (event) {
                        is StreamEvent.Content -> {
                            status = Strings.t(Strings.typing)
                            if (streamMsgIdx < 0 || !messages.getOrNull(streamMsgIdx)?.role.equals("assistant")) {
                                streamMsgIdx = appendBlock(ChatBlock("answer", event.text, streaming = true))
                            } else {
                                val lastBlock = messages[streamMsgIdx].blocks.lastOrNull()
                                if (lastBlock?.kind == "answer" && lastBlock.streaming) {
                                    appendToBlock(streamMsgIdx, event.text)
                                } else {
                                    messages = messages.toMutableList().also {
                                        val mm = it[streamMsgIdx]
                                        it[streamMsgIdx] = mm.copy(blocks = mm.blocks.map { b -> b.copy(streaming = false) } + ChatBlock("answer", event.text, streaming = true))
                                    }
                                }
                            }
                        }
                        is StreamEvent.Reasoning -> {
                            if (streamMsgIdx >= 0) {
                                messages = messages.toMutableList().also {
                                    val mm = it[streamMsgIdx]
                                    it[streamMsgIdx] = mm.copy(blocks = mm.blocks.map { b -> b.copy(streaming = false) } + ChatBlock("reasoning", event.text, streaming = true))
                                }
                            }
                        }
                        is StreamEvent.Error -> {
                            appendBlock(ChatBlock("answer", "\u26a0\ufe0f ${event.message}"))
                            status = ""
                        }
                        else -> {}
                    }
                }
            }

            finalizeBlocks()
            status = ""

            // Search failure notice for the USER (small system-style line)
            searchNotice?.let { notice ->
                messages = messages + ChatMessage("assistant", listOf(ChatBlock("answer", notice)))
            }

            // Speak the finished answer when TTS is enabled. Engine dispatch:
            // "system" (offline) or "fish" (FishAudio online). Failures
            // surface via the state flows — never silent.
            val lastAssistantText = messages.lastOrNull { it.role == "assistant" }?.answerText
            if (!lastAssistantText.isNullOrBlank() &&
                !lastAssistantText.startsWith("\ud83d\udd0d") &&
                !lastAssistantText.startsWith("\u26a0") &&
                AppSettings.config.value.ttsEnabled
            ) {
                when (AppSettings.config.value.ttsMethod) {
                    "fish" -> scope.launch { FishTts.speak(lastAssistantText) }
                    else -> {
                        TtsManager.applyLanguage(AppSettings.config.value.ttsLanguage)
                        TtsManager.speak(lastAssistantText)
                    }
                }
            }

            // Auto-save the finished exchange (skip pure-error replies)
            val c = ctx
            if (c != null && messages.isNotEmpty() &&
                messages.none { b -> b.blocks.any { it.text.startsWith("\u26a0\ufe0f") } }
            ) {
                withContext(Dispatchers.IO) {
                    ChatStore.save(
                        c, chatId,
                        messages.map { m ->
                            ChatStore.StoredMessage(
                                m.role,
                                m.blocks.map { ChatStore.Block(it.kind, it.text) },
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
