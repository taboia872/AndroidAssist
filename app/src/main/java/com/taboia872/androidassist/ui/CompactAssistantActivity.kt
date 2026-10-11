package com.taboia872.androidassist.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.ui.theme.AndroidAssistTheme

/**
 * The "bloquinho": bottom-sheet assistant opened by the system assistant
 * gesture. Self-contained — same ChatSession machinery as the main screen,
 * session-scoped until chat history persistence lands.
 */
class CompactAssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        enableEdgeToEdge()
        setContent {
            AndroidAssistTheme {
                AssistantOverlay(onDismiss = { finish() })
            }
        }
    }
}

@Composable
fun AssistantOverlay(onDismiss: () -> Unit) {
    val session = remember { ChatSession() }
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (expanded) MaterialTheme.colorScheme.background
                else Color.Black.copy(alpha = 0.4f)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = !expanded,
                onClick = onDismiss
            )
    ) {
        if (expanded) {
            FullscreenAssistant(
                session = session,
                onCollapse = { expanded = false },
                onDismiss = onDismiss
            )
        } else {
            CompactAssistantSheet(
                session = session,
                onExpand = { expanded = true },
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun CompactAssistantSheet(
    session: ChatSession,
    onExpand: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val lastUser = session.messages.lastOrNull { it.role == "user" }
    val lastAssistant = session.messages.lastOrNull { it.role == "assistant" }
    val hasContent = lastUser != null || lastAssistant != null ||
        session.status.isNotEmpty() || session.reasoning.isNotEmpty()

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        val sheetMaxHeight = maxHeight * 0.92f
        val responseMaxHeight = if (maxHeight < 420.dp) 120.dp else 200.dp

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .imePadding()
                .navigationBarsPadding()
                .heightIn(max = sheetMaxHeight)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(36.dp),
            shadowElevation = 24.dp,
            tonalElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Surface(
                        modifier = Modifier
                            .width(36.dp)
                            .height(4.dp),
                        shape = RoundedCornerShape(2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    ) {}
                }

                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(34.dp),
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                tonalElevation = 0.dp
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("AndroidAssist", style = MaterialTheme.typography.titleMedium)
                        }
                        Row {
                            IconButton(onClick = onExpand) {
                                Icon(
                                    Icons.Default.OpenInFull, "Expand",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.Default.Close, "Close",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Response area — the LAST exchange only (compact view)
                    if (hasContent) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .heightIn(max = responseMaxHeight)
                                .clip(RoundedCornerShape(20.dp))
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                )
                                .padding(14.dp)
                                .verticalScroll(scrollState)
                        ) {
                            lastUser?.let {
                                Text(
                                    "You: ${it.content}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (lastAssistant != null || session.reasoning.isNotEmpty() || session.busy) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }

                            if (session.reasoning.isNotEmpty() && lastAssistant == null) {
                                Text(
                                    session.reasoning,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontStyle = FontStyle.Italic,
                                        lineHeight = 16.sp
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                                if (session.busy && session.status.isEmpty()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }

                            if (session.status.isNotEmpty() && lastAssistant == null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        session.status,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            lastAssistant?.let {
                                MarkdownText(
                                    text = it.content,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    linkColor = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        LaunchedEffect(lastAssistant?.content, session.reasoning) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Input row (mic button lands with voice input, step 5)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Ask anything...") },
                            shape = RoundedCornerShape(28.dp),
                            singleLine = true,
                            enabled = !session.busy,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                            )
                        )
                        AnimatedVisibility(visible = !session.busy && input.isNotEmpty()) {
                            FilledIconButton(
                                onClick = {
                                    session.send(input, scope)
                                    input = ""
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send, "Send",
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenAssistant(
    session: ChatSession,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit
) {
    val cfg by AppSettings.config.collectAsState()
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            cfg.model.ifBlank { "AndroidAssist" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (session.status.isNotEmpty()) {
                            Text(
                                session.status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Default.KeyboardArrowDown, "Collapse")
                    }
                },
                actions = {
                    IconButton(onClick = { session.reset() }) {
                        Icon(Icons.Default.Refresh, "New Chat")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
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
                        placeholder = { Text(if (session.busy) "Generating..." else "Ask anything...") },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        enabled = !session.busy
                    )
                    FilledIconButton(
                        onClick = {
                            session.send(input, scope)
                            input = ""
                        },
                        enabled = input.isNotBlank() && !session.busy,
                        modifier = Modifier.size(48.dp)
                    ) {
                        if (session.busy) {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (session.messages.isEmpty()) {
                    item { WelcomeMessage() }
                }
                items(session.messages) { msg -> MessageBubble(msg) }
                if (session.reasoning.isNotEmpty()) {
                    item { ReasoningBubble(session.reasoning) }
                }
                if (session.busy && session.messages.lastOrNull()?.role != "assistant") {
                    item { ThinkingBubble(session.status) }
                }
            }

            LaunchedEffect(
                session.messages.size,
                session.messages.lastOrNull()?.content,
                session.reasoning
            ) {
                if (session.messages.isNotEmpty()) {
                    listState.animateScrollToItem(session.messages.lastIndex)
                }
            }
        }
    }
}
