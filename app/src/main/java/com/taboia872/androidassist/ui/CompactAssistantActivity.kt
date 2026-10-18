package com.taboia872.androidassist.ui

import com.taboia872.androidassist.i18n.Strings
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.tts.TtsManager
import com.taboia872.androidassist.ui.theme.AndroidAssistTheme
import com.taboia872.androidassist.voice.VoiceEngine
import com.taboia872.androidassist.voice.SystemRecognizer
import java.util.Locale

/**
 * The "bloquinho": bottom-sheet assistant opened by the system assistant
 * gesture. Auto-starts voice capture on open (if enabled) like the base app.
 */
class CompactAssistantActivity : ComponentActivity() {

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            android.widget.Toast.makeText(
                this, Strings.t(Strings.micRequired),
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        TtsManager.init(this, AppSettings.config.value.ttsLanguage)
        enableEdgeToEdge()

        // Voice capture never passes through MainActivity — request here.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            AndroidAssistTheme {
                AssistantOverlay(
                    onDismiss = { finish() },
                    requestMic = {
                        if (ContextCompat.checkSelfPermission(
                                this, Manifest.permission.RECORD_AUDIO
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun AssistantOverlay(onDismiss: () -> Unit, requestMic: () -> Unit = {}) {
    val context = LocalContext.current
    // ACTION_ASSIST always starts a FRESH chat — the gesture is a new
    // query by definition. Saved chats live in History (and continue
    // there), so nothing is lost by starting clean.
    val session = remember {
        ChatSession(persistenceContext = context.applicationContext)
    }
    var expanded by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // ---- Voice state machine: Idle / Listening ----
    var listening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun startVoice() {
        if (listening || session.busy) return
        val cfg = AppSettings.config.value
        if (!VoiceEngine.hasMicPermission(context)) {
            requestMic()
            return
        }
        listening = true
        transcript = ""
        voiceError = null
        val lang = cfg.voiceLanguage.ifBlank {
            Locale.getDefault().toLanguageTag()
        }
        when (cfg.voiceMethod) {
            "system" -> SystemRecognizer.listen(
                context, lang,
                onPartial = { partial -> transcript = partial },
                onResult = { result ->
                    listening = false
                    // Clear BEFORE handling: with auto-send the text becomes a
                    // real user message; leaving it in `transcript` rendered the
                    // message twice (bubble + "You: ..." live transcript).
                    transcript = ""
                    result.fold(
                        onSuccess = { text ->
                            if (AppSettings.config.value.autoSendVoice) {
                                session.send(text, scope)
                            } else {
                                transcript = text
                            }
                        },
                        onFailure = { e -> voiceError = e.message }
                    )
                }
            )
            else -> VoiceEngine.recordAndTranscribe(
                context, lang,
                onResult = { result ->
                    listening = false
                    transcript = ""
                    result.fold(
                        onSuccess = { text ->
                            if (AppSettings.config.value.autoSendVoice) {
                                session.send(text, scope)
                            } else {
                                transcript = text
                            }
                        },
                        onFailure = { e -> voiceError = e.message }
                    )
                }
            )
        }
    }

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
        when {
            showHistory -> ChatHistoryScreen(
                onChatSelected = { chatId ->
                    session.loadFrom(context.applicationContext, chatId)
                    showHistory = false
                },
                onBack = { showHistory = false },
                onDismiss = onDismiss
            )
            expanded -> FullscreenAssistant(
                session = session,
                listening = listening,
                transcript = transcript,
                voiceError = voiceError,
                onMic = { if (listening) listening = false else startVoice() },
                onCollapse = { expanded = false },
                onDismiss = onDismiss,
                onShowHistory = { showHistory = true }
            )
            else -> CompactAssistantSheet(
                session = session,
                listening = listening,
                transcript = transcript,
                voiceError = voiceError,
                onMic = { if (listening) listening = false else startVoice() },
                onExpand = { expanded = true },
                onDismiss = onDismiss
            )
        }

        // Auto-start voice on open — the base app behavior you liked
        LaunchedEffect(Unit) {
            if (AppSettings.config.value.autoStartVoice) startVoice()
        }
    }
}

@Composable
private fun CompactAssistantSheet(
    session: ChatSession,
    listening: Boolean,
    transcript: String,
    voiceError: String?,
    onMic: () -> Unit,
    onExpand: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val lastUser = session.messages.lastOrNull { it.role == "user" }
    val lastAssistant = session.messages.lastOrNull { it.role == "assistant" }
    val showVoice = listening || transcript.isNotEmpty() || voiceError != null
    val hasContent = lastUser != null || lastAssistant != null ||
        session.status.isNotEmpty() || showVoice

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
                                    Icons.Default.OpenInFull, Strings.t(Strings.expand),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.Default.Close, Strings.t(Strings.close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

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
                                    "${Strings.t(Strings.youColon)} ${it.answerText}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (lastAssistant != null || session.busy) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }

                            // Live reasoning streams as blocks inside the streaming
                            // message; show live reasoning blocks here when present.
                            val liveReasoning = session.messages.lastOrNull()
                                ?.blocks?.filter { it.kind == "reasoning" }.orEmpty()
                            if (liveReasoning.isNotEmpty() && lastAssistant == null) {
                                liveReasoning.forEach { rb ->
                                    ReasoningBlock(rb.text, liveStream = true)
                                    Spacer(modifier = Modifier.height(6.dp))
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
                                // Render ALL blocks: reasoning (collapsed), tool, answers
                                it.blocks.forEachIndexed { idx, block ->
                                    if (idx > 0) Spacer(modifier = Modifier.height(8.dp))
                                    when (block.kind) {
                                        "reasoning" -> ReasoningBlock(block.text)
                                        "tool" -> ToolBlock(block.text)
                                        else -> if (block.text.isNotBlank()) MarkdownText(
                                            text = block.text,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            linkColor = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            if (listening) {
                                if (lastUser != null) Spacer(modifier = Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        Strings.t(Strings.listening),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }

                            if (transcript.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "${Strings.t(Strings.youColon)} $transcript",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            voiceError?.let { err ->
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    Strings.t(Strings.voiceErrorColon) + " $err",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        LaunchedEffect(lastAssistant?.blocks?.size, transcript) {
                            scrollState.animateScrollTo(scrollState.maxValue)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Input row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(28.dp),
                            singleLine = true,
                            enabled = !session.busy && !listening,
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
                                } else {
                                    onMic()
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
                                    Icons.AutoMirrored.Filled.Send, Strings.t(Strings.send),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                                listening -> Icon(
                                    Icons.Default.Stop, Strings.t(Strings.stopListening),
                                    tint = MaterialTheme.colorScheme.onError,
                                    modifier = Modifier.size(22.dp)
                                )
                                else -> Icon(
                                    Icons.Default.Mic, Strings.t(Strings.voiceInput),
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(22.dp)
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
    listening: Boolean,
    transcript: String,
    voiceError: String?,
    onMic: () -> Unit,
    onCollapse: () -> Unit,
    onDismiss: () -> Unit,
    onShowHistory: () -> Unit = {}
) {
    val cfg by AppSettings.config.collectAsState()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var viewerImage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            AppSettings.activePersonaName()
                                .takeIf { it != "Default" }
                                ?: cfg.model.ifBlank { "AndroidAssist" },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            when {
                                listening -> Strings.t(Strings.listening)
                                session.busy -> session.status.ifBlank { Strings.t(Strings.thinking) }
                                else -> Strings.t(Strings.ready)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onCollapse) {
                        Icon(Icons.Default.KeyboardArrowDown, "Collapse")
                    }
                },
                actions = {
                    IconButton(onClick = { session.reset(context.applicationContext) }) {
                        Icon(Icons.Default.Refresh, Strings.t(Strings.newChat))
                    }
                    IconButton(onClick = onShowHistory) {
                        Icon(Icons.Default.History, Strings.t(Strings.chatHistory))
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
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        enabled = !session.busy && !listening
                    )
                    // Contextual action button: mic when the field is empty,
                    // send as soon as there is text to send.
                    FilledIconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                session.send(input, scope)
                                input = ""
                            } else {
                                onMic()
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
                                Icons.AutoMirrored.Filled.Send, Strings.t(Strings.send),
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                            listening -> Icon(
                                Icons.Default.Stop, Strings.t(Strings.stopListening),
                                tint = MaterialTheme.colorScheme.onError
                            )
                            else -> Icon(
                                Icons.Default.Mic, Strings.t(Strings.voiceInput),
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
                if (session.messages.isEmpty() && transcript.isEmpty()) {
                    // No welcome card (user request) — empty state is just empty.
                }
                if (listening && transcript.isEmpty()) {
                    item { ThinkingBubble(Strings.t(Strings.listening)) }
                }
                if (transcript.isNotEmpty()) {
                    item { MessageBubble(ChatMessage("user", listOf(ChatBlock("answer", transcript)))) }
                }
                items(session.messages) { msg ->
                    MessageBubble(msg, onImageClick = { viewerImage = it })
                }
                if (session.busy && session.messages.lastOrNull()?.role != "assistant") {
                    item { ThinkingBubble(session.status) }
                }
                voiceError?.let { err ->
                    item {
                        Text(
                            Strings.t(Strings.voiceErrorColon) + " $err",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            LaunchedEffect(
                session.messages.size,
                session.messages.lastOrNull()?.blocks?.lastOrNull()?.text,
                transcript
            ) {
                if (session.messages.isNotEmpty()) {
                    listState.animateScrollToItem(session.messages.lastIndex)
                }
            }

            viewerImage?.let { img ->
                ImageViewerDialog(imageBase64 = img, onDismiss = { viewerImage = null })
            }
        }
    }
}
