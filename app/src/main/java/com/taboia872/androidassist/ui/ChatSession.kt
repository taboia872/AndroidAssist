package com.taboia872.androidassist.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taboia872.androidassist.llm.LlmClient
import com.taboia872.androidassist.llm.StreamEvent
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.storage.ChatStore
import com.taboia872.androidassist.search.DuckDuckGo
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

    /** Short status label: "Thinking..." / "Typing..." while busy. */
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
        status = "Thinking..."
        messages = messages + ChatMessage(
            "user",
            q.ifBlank { "What's in this image?" },
            imageBase64 = imageBase64
        )

        scope.launch {
            // Web search (keyless DuckDuckGo): fetch results and inject them
            // as a context block so the model answers with fresh facts.
            // Single hook in ChatSession — covers main chat and overlay.
            var effectiveQuery = q
            if (AppSettings.config.value.webSearchEnabled) {
                status = "Searching..."
                val results = DuckDuckGo.search(q)
                if (results.isNotEmpty()) {
                    val context = buildString {
                        append("Web search results for \"$q\":\n")
                        results.forEach { r ->
                            append("\n---\n")
                            append("Title: ${r.title}\n")
                            append("URL: ${r.url}\n")
                            append("Snippet: ${r.snippet}")
                        }
                        append("\n---\n\n")
                    }
                    effectiveQuery = context + q
                } else {
                    // Search failed/empty (rate-limit, offline...): tell the
                    // model explicitly so it does NOT hallucinate fresh
                    // facts — the exact failure the user reported.
                    effectiveQuery =
                        "NOTE: a web search for this question was attempted but returned no results. " +
                        "You have NO current information about this. Answer honestly: say you " +
                        "cannot access live data right now, and clearly label anything uncertain " +
                        "as uncertain. Do NOT invent numbers, dates or facts.\n\n" + q
                }
                status = "Thinking..."
            }

            val history = if (effectiveQuery == q) {
                messages.map { Triple(it.role, it.content, it.imageBase64) }
            } else {
                messages.dropLast(1).map { Triple(it.role, it.content, it.imageBase64) } +
                    listOf(Triple("user", effectiveQuery, imageBase64))
            }
            val answer = StringBuilder()
            LlmClient.chat(
                history,
                AppSettings.config.value.systemPrompt
            ).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        status = "Typing..."
                        answer.append(event.text)
                        val base = if (messages.lastOrNull()?.role == "assistant")
                            messages.dropLast(1) else messages
                        messages = base + ChatMessage("assistant", answer.toString())
                    }
                    is StreamEvent.Reasoning -> reasoning += event.text
                    is StreamEvent.Error -> {
                        messages = messages + ChatMessage("assistant", "⚠️ ${event.message}")
                        status = ""
                    }
                }
            }
            busy = false
            status = ""

            // Keep the finished reasoning attached to the answer bubble
            // (collapsible UI) instead of discarding it.
            val finalReasoning = reasoning
            reasoning = ""
            if (finalReasoning.isNotBlank() &&
                messages.lastOrNull()?.role == "assistant" &&
                answer.isNotEmpty()
            ) {
                messages = messages.dropLast(1) +
                    messages.last().copy(reasoning = finalReasoning)
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
        }
    }
}
