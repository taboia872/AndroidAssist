package com.taboia872.androidassist.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taboia872.androidassist.llm.LlmClient
import com.taboia872.androidassist.llm.StreamEvent
import com.taboia872.androidassist.storage.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ChatMessage(
    val role: String,
    val content: String,
    /** Final reasoning (thinking models), kept after the answer completes. */
    val reasoning: String = ""
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
        messages = loaded.messages.map { ChatMessage(it.first, it.second) }
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

    fun send(query: String, scope: CoroutineScope) {
        val q = query.trim()
        if (q.isEmpty() || busy) return
        busy = true
        reasoning = ""
        status = "Thinking..."
        messages = messages + ChatMessage("user", q)

        scope.launch {
            val history = messages.map { it.role to it.content }
            val answer = StringBuilder()
            LlmClient.chat(history).collect { event ->
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

            // Auto-save the finished exchange (skip pure-error replies)
            val c = ctx
            if (c != null && messages.isNotEmpty() &&
                messages.none { it.content.startsWith("⚠️") }
            ) {
                withContext(Dispatchers.IO) {
                    ChatStore.save(
                        c, chatId,
                        messages.map { it.role to it.content }
                    )
                    ChatStore.setCurrentId(c, chatId)
                }
            }
        }
    }
}
