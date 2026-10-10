package com.taboia872.androidassist.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    var statusText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun send() {
        val q = input.trim()
        if (q.isEmpty() || busy) return
        input = ""
        busy = true
        reasoning = ""
        statusText = "Thinking..."
        messages = messages + ChatMessage("user", q)

        scope.launch {
            val history = messages.map { it.role to it.content }
            val answer = StringBuilder()
            LlmClient.chat(history).collect { event ->
                when (event) {
                    is StreamEvent.Content -> {
                        statusText = "Typing..."
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
            statusText = ""
            reasoning = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            cfg.model.ifBlank { "AndroidAssist" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (statusText.isNotEmpty()) {
                            Text(
                                statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .imePadding()
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(if (busy) "Generating..." else "Ask anything...") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        enabled = !busy,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                        )
                    )
                    FilledIconButton(
                        onClick = { send() },
                        enabled = input.isNotBlank() && !busy,
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
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
                if (messages.isEmpty() && reasoning.isEmpty()) {
                    item { WelcomeMessage() }
                }
                items(messages) { msg ->
                    MessageBubble(msg)
                }
                if (reasoning.isNotEmpty()) {
                    item {
                        ReasoningBubble(reasoning)
                    }
                }
                if (busy && messages.none { it.role == "assistant" }) {
                    item {
                        ThinkingBubble(statusText)
                    }
                }
            }

            LaunchedEffect(messages.size, reasoning) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
            }
        }
    }
}

@Composable
private fun WelcomeMessage() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "How can I help you?",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Ask anything or pick a model in Settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = if (isUser) 20.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 20.dp,
                bottomStart = 20.dp,
                bottomEnd = 20.dp
            ),
            tonalElevation = if (isUser) 0.dp else 1.dp,
            color = if (isUser) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (isUser) {
                Text(
                    msg.content,
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                MarkdownText(
                    text = msg.content,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    linkColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ReasoningBubble(content: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                content,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontStyle = FontStyle.Italic,
                    lineHeight = 16.sp
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ThinkingBubble(status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 1.dp,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    status.ifEmpty { "Thinking..." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
