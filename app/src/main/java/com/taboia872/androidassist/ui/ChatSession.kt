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

data class ChatMessage(
    val role: String,
    val content: String,
    /** Final reasoning (thinking models), kept after the answer completes. */
    val reasoning: String = "",
    /** Attached image (JPEG base64) shown in the bubble and sent to vision models. */
    val imageBase64: String? = null
)

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

    /** Reasoning tokens streamed before the answer (thinking models). */
    var reasoning by mutableStateOf("")
        private set

    /** Short status label: Strings.t(Strings.thinking) / Strings.t(Strings.typing) while busy. */
    var status by mutableStateOf("")
        private set

    var chatId by mutableStateOf(UUID.randomUUID().toString())
        private set

    private val ctx: android.content.Context?
        get() = persistenceContext

    /** Restore a saved chat by id (history "continue"). */
    fun loadFrom(context: android.content.Context, id: String) {
        val loaded = ChatStore.load(context, id) ?: return
        chatId = loaded.id
        messages = loaded.messages.map {
            ChatMessage(it.first, it.second, imageBase64 = it.third)
        }
        reasoning = ""
        status = ""
        ChatStore.setCurrentId(context, id)
    }

    /** Start a fresh chat (New Chat). */
    fun reset(context: android.content.Context? = null) {
        chatId = UUID.randomUUID().toString()
        messages = emptyList()
        reasoning = ""
        status = ""
        busy = false
        context?.let { ChatStore.setCurrentId(it, chatId) }
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
        reasoning = ""
        status = Strings.t(Strings.thinking)
        messages = messages + ChatMessage(
            "user",
            q.ifBlank { "What's in this image?" },
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

            // FUNCTION CALLING (OpenAI tools pattern): when web search is on,
            // the web_search TOOL is declared in the request — the model
            // DECIDES when it needs fresh facts and emits a tool_call; we run
            // the search and send results back as role:"tool" on a follow-up
            // request. The model knows the tool exists (user's insight).
            val useTools = AppSettings.config.value.webSearchEnabled
            val history = messages.map { Triple(it.role, it.content, it.imageBase64) }
            val answer = StringBuilder()

            // Round 1: with tools declared. A tool_call may arrive here.
            var toolCall: Pair<String, String>? = null  // (id, query)
            var searchNotice: String? = null

            LlmClient.chat(
                history,
                basePrompt,
                toolsEnabled = useTools
            ).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        status = Strings.t(Strings.typing)
                        answer.append(event.text)
                        val base = if (messages.lastOrNull()?.role == "assistant")
                            messages.dropLast(1) else messages
                        messages = base + ChatMessage("assistant", answer.toString())
                    }
                    is StreamEvent.Reasoning -> reasoning += event.text
                    is StreamEvent.ToolCall -> { toolCall = event.id to event.query }
                    is StreamEvent.Error -> {
                        messages = messages + ChatMessage("assistant", "\u26a0\ufe0f ${event.message}")
                        status = ""
                    }
                }
            }

            // Round 2 (only if the model called the tool): run the search and
            // replay the conversation with the tool result attached.
            val call = toolCall
            if (call != null) {
                val searchQuery = call.second.ifBlank { q }
                status = Strings.t(Strings.searching)
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
                        // Surface the REAL reason to the model — and the user
                        val engine = AppSettings.config.value.searchEngine
                        searchNotice = "⚠️ Search ($engine): ${outcome.reason}"
                        "The web search FAILED: ${outcome.reason}. " +
                            "Tell the user the search is not working right now " +
                            "(quote the reason) — do NOT invent facts."
                    }
                }

                answer.setLength(0)
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
                            answer.append(event.text)
                            val base = if (messages.lastOrNull()?.role == "assistant")
                                messages.dropLast(1) else messages
                            messages = base + ChatMessage("assistant", answer.toString())
                        }
                        is StreamEvent.Reasoning -> reasoning += event.text
                        is StreamEvent.Error -> {
                            messages = messages + ChatMessage("assistant", "\u26a0\ufe0f ${event.message}")
                            status = ""
                        }
                        else -> {}
                    }
                }
            }

            val finalReasoning = reasoning
            reasoning = ""
            if (finalReasoning.isNotBlank() &&
                messages.lastOrNull()?.role == "assistant" &&
                answer.isNotEmpty()
            ) {
                messages = messages.dropLast(1) +
                    messages.last().copy(reasoning = finalReasoning)
            }

            // Search failure notice for the USER (small system-style line)
            searchNotice?.let { notice ->
                messages = messages + ChatMessage("assistant", notice)
            }

            // Speak the finished answer when TTS is enabled. Engine dispatch:
            // "system" (offline) or "fish" (FishAudio online). Failures
            // surface via the state flows — never silent.
            if (answer.isNotEmpty() && AppSettings.config.value.ttsEnabled) {
                when (AppSettings.config.value.ttsMethod) {
                    "fish" -> scope.launch { FishTts.speak(answer.toString()) }
                    else -> {
                        TtsManager.applyLanguage(AppSettings.config.value.voiceLanguage)
                        TtsManager.speak(answer.toString())
                    }
                }
            }

            // Auto-save the finished exchange (skip pure-error replies)
            val c = ctx
            if (c != null && messages.isNotEmpty() &&
                messages.none { it.content.startsWith("⚠️") }
            ) {
                withContext(Dispatchers.IO) {
                    ChatStore.save(
                        c, chatId,
                        messages.map { Triple(it.role, it.content, it.imageBase64) }
                    )
                    ChatStore.setCurrentId(c, chatId)
                }
            }
            } finally {
                // ALWAYS release the session — the busy-stuck bug: the
                // function-calling rewrite dropped this line and left the
                // input/mic locked forever after the first answer.
                busy = false
                status = ""
            }
        }
    }
}
