package com.taboia872.androidassist.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.taboia872.androidassist.llm.LlmClient
import com.taboia872.androidassist.llm.StreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class ChatMessage(val role: String, val content: String)

/**
 * One conversation: message list + streaming state + send(). Shared by the
 * main chat screen and the assistant overlay so both behave identically.
 * All state is Compose state — every screen showing a ChatSession updates
 * live while tokens stream in.
 */
class ChatSession {

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
                        // Replace the streaming assistant bubble — never the
                        // user's message (old code ate the user bubble).
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
            reasoning = ""
        }
    }

    fun reset() {
        messages = emptyList()
        reasoning = ""
        status = ""
        busy = false
    }
}
