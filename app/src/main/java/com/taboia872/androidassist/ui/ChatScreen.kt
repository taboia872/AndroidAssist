package com.taboia872.androidassist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.llm.LlmClient
import com.taboia872.androidassist.llm.StreamEvent
import com.taboia872.androidassist.settings.AppSettings
import kotlinx.coroutines.launch

data class ChatMessage(val role: String, val content: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(onOpenSettings: () -> Unit) {
    val cfg by AppSettings.config.collectAsState()
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf<ChatMessage>()) }
    var busy by remember { mutableStateOf(false) }
    var reasoning by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        input = ""
        busy = true
        reasoning = ""
        messages = messages + ChatMessage("user", q)

        scope.launch {
            val history = messages.map { it.role to it.content }
            val answer = StringBuilder()
            LlmClient.chat(history).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        answer.append(event.text)
                        messages = messages.dropLast(1) + ChatMessage("assistant", answer.toString())
                    }
                    is StreamEvent.Reasoning -> reasoning += event.text
                    is StreamEvent.Error -> {
                        messages = messages.dropLast(1) +
                            ChatMessage("assistant", "⚠️ ${event.message}")
                    }
                }
            }
            busy = false
            reasoning = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cfg.model.ifBlank { "AndroidAssist" }) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages) { msg ->
                    MessageBubble(msg)
                }
                if (reasoning.isNotEmpty()) {
                    item {
                        Text(
                            reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            LaunchedEffect(messages.size, reasoning) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    enabled = !busy
                )
                FilledIconButton(
                    onClick = { send() },
                    enabled = input.isNotBlank() && !busy
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Surface(
        color = if (isUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            msg.content,
            modifier = Modifier.padding(12.dp),
            textAlign = if (isUser) TextAlign.End else TextAlign.Start
        )
    }
}
