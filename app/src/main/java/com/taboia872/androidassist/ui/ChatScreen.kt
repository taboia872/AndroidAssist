package com.taboia872.androidassist.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.storage.ChatStore
import com.taboia872.androidassist.voice.VoiceEngine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Main chat screen — same ChatSession machinery the overlay uses. Restores
 * the last chat on open; History button opens the saved-chats screen.
 * Mic button: same VoiceEngine flow as the overlay (VAD + Groq Whisper),
 * no auto-start here — the main screen is for browsing/continuing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    resumeChatId: String? = null,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val cfg by AppSettings.config.collectAsState()
    val context = LocalContext.current
    val session = remember {
        ChatSession(persistenceContext = context.applicationContext).also { s ->
            val restore = resumeChatId ?: ChatStore.currentId(context.applicationContext)
            if (restore != null) s.loadFrom(context.applicationContext, restore)
        }
    }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ---- voice (manual only on this screen) ----
    var listening by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun startVoice() {
        if (listening || session.busy) return
        if (!VoiceEngine.hasMicPermission(context)) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        listening = true
        voiceError = null
        val lang = AppSettings.config.value.voiceLanguage.ifBlank {
            Locale.getDefault().toLanguageTag()
        }
        VoiceEngine.recordAndTranscribe(
            context, lang,
            onResult = { result ->
                listening = false
                result.fold(
                    onSuccess = { text ->
                        if (AppSettings.config.value.autoSendVoice) {
                            session.send(text, scope)
                        } else {
                            input = text
                        }
                    },
                    onFailure = { e -> voiceError = e.message }
                )
            }
        )
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
                        if (session.status.isNotEmpty()) {
                            Text(
                                session.status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { session.reset(context.applicationContext) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "New Chat")
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = "Chat History")
                    }
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
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        enabled = !session.busy,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                        )
                    )
                    // Contextual action button: mic when the field is empty,
                    // send as soon as there is text to send.
                    FilledIconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                session.send(input, scope)
                                input = ""
                            } else if (listening) {
                                listening = false
                            } else {
                                startVoice()
                            }
                        },
                        enabled = !session.busy || input.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                            else if (listening) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        when {
                            input.isNotBlank() -> Icon(
                                Icons.AutoMirrored.Filled.Send, "Send",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            listening -> Icon(
                                Icons.Default.Stop, "Stop listening",
                                tint = MaterialTheme.colorScheme.onError
                            )
                            else -> Icon(
                                Icons.Default.Mic, "Voice input",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
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
                items(session.messages) { msg ->
                    MessageBubble(msg)
                }
                if (listening) {
                    item { ThinkingBubble("Listening...") }
                }
                if (session.reasoning.isNotEmpty()) {
                    item { ReasoningBubble(session.reasoning) }
                }
                if (session.busy && session.messages.lastOrNull()?.role != "assistant") {
                    item { ThinkingBubble(session.status) }
                }
                voiceError?.let { err ->
                    item {
                        Text(
                            "Voice error: $err",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
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
