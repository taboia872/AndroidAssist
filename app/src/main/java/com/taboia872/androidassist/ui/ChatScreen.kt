package com.taboia872.androidassist.ui

import com.taboia872.androidassist.i18n.Strings
import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.tts.FishTts
import com.taboia872.androidassist.tts.TtsManager
import com.taboia872.androidassist.util.PdfExtractor
import com.taboia872.androidassist.util.PdfResult
import com.taboia872.androidassist.voice.VoiceEngine
import com.taboia872.androidassist.voice.SystemRecognizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Main chat screen — same ChatSession machinery the overlay uses. Restores
 * the last chat on open; History button opens the saved-chats screen.
 * Mic button: same VoiceEngine flow as the overlay (VAD + Groq Whisper),
 * no auto-start here — the main screen is for browsing/continuing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    resumeChatId: String? = null,
    onConsumeResumeId: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val cfg by AppSettings.config.collectAsState()
    val context = LocalContext.current
    // App-scoped session: streaming and the end-of-turn save survive
    // navigation (Settings/History) and backgrounding.
    val session = ChatSessions.main
    // Browser-style: the top bar hides when the chat scrolls DOWN and comes
    // back when you scroll up (enterAlways).
    //
    // With NO chat started there is nothing to scroll, so the bar must NOT
    // collapse (user request): canScroll gates the whole nested-scroll
    // handling. The lambda reads the live message list, so the bar starts
    // collapsing the moment the first message arrives.
    val topBarState = rememberTopAppBarState()
    val topBarBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        state = topBarState,
        canScroll = remember(session) { { session.messages.isNotEmpty() } }
    )
    // A new (empty) chat brings the bar back — it never stays hidden.
    val hasMessages = session.messages.isNotEmpty()
    LaunchedEffect(hasMessages) {
        if (!hasMessages) {
            topBarState.heightOffset = 0f
            topBarState.contentOffset = 0f
        }
    }
    // Load a history-picked chat ONCE and clear the request — re-entering the
    // screen from Settings must not reload it again over the live conversation.
    LaunchedEffect(resumeChatId) {
        if (resumeChatId != null) {
            session.loadFrom(context.applicationContext, resumeChatId!!)
            onConsumeResumeId()
        }
    }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll only until the user scrolls manually: streaming keeps

    // A NEW chat: prime the local server's prompt cache (the system
    // prompt + the tools + the memories) so the FIRST answer is as fast
    // as the second one.
    LaunchedEffect(session.chatId) {
        delay(700)
        if (session.messages.isEmpty()) {
            session.warmUp()
        }
    }

    // ---- the "+" menu (attach, web search, tools) ----
    var showToolsMenu by remember { mutableStateOf(false) }
    // null = the user's own setting (the Configurations).
    var webSearchOn by remember { mutableStateOf<Boolean?>(null) }
    val webSearchEffective = webSearchOn
        ?: AppSettings.config.value.webSearchEnabled
    LaunchedEffect(webSearchOn) {
        session.webSearchOverride = webSearchOn
    }

    // ---- attachments ----
    var attachedImage by remember { mutableStateOf<Bitmap?>(null) }
    var attachedImageBase64 by remember { mutableStateOf<String?>(null) }
    var attachedPdfSummary by remember { mutableStateOf<String?>(null) }
    var attachedPdfText by remember { mutableStateOf<String?>(null) }
    var attachError by remember { mutableStateOf<String?>(null) }
    var viewerImage by remember { mutableStateOf<String?>(null) }

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            val type = context.contentResolver.getType(it) ?: ""
            if (type == "application/pdf") {
                attachError = null
                scope.launch {
                    when (val result = PdfExtractor.extractText(context, it)) {
                        is PdfResult.Success -> {
                            attachedPdfText = result.text
                            attachedPdfSummary = result.summary
                        }
                        is PdfResult.Error -> attachError = result.message
                    }
                }
            } else {
                // Image: decode, downscale to max 1024px, JPEG base64
                try {
                    context.contentResolver.openInputStream(it).use { stream ->
                        val bmp = BitmapFactory.decodeStream(stream) ?: return@use
                        val max = 1024
                        val scaled = if (bmp.width > max || bmp.height > max) {
                            val ratio = minOf(
                                max.toFloat() / bmp.width,
                                max.toFloat() / bmp.height
                            )
                            Bitmap.createScaledBitmap(
                                bmp,
                                (bmp.width * ratio).toInt(),
                                (bmp.height * ratio).toInt(),
                                true
                            )
                        } else bmp
                        attachedImage = scaled
                        val out = java.io.ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        attachedImageBase64 =
                            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                    }
                } catch (e: Exception) {
                    attachError = "Could not read image"
                }
            }
        }
    }

    fun clearAttachments() {
        attachedImage = null
        attachedImageBase64 = null
        attachedPdfSummary = null
        attachedPdfText = null
    }

    fun sendCurrent() {
        when {
            attachedPdfText != null -> {
                val q = input.ifBlank { "Summarize this document" }
                session.send(
                    "Document content:\n---\n${attachedPdfText}\n---\n\nQuestion: $q"
                )
            }
            attachedImageBase64 != null ->
                session.send(input, attachedImageBase64)
            else -> session.send(input)
        }
        input = ""
        clearAttachments()
    }

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
        when (AppSettings.config.value.voiceMethod) {
            "system" -> SystemRecognizer.listen(
                context, lang,
                onPartial = { partial -> input = partial },
                onResult = { result ->
                    listening = false
                    // Clear the field BEFORE handling the result: auto-send
                    // turns the transcript into a sent message, and the stale
                    // text was being left behind in the input (user report).
                    input = ""
                    result.fold(
                        onSuccess = { text ->
                            if (AppSettings.config.value.autoSendVoice) {
                                session.send(text)
                            } else {
                                input = text
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
                    input = ""
                    result.fold(
                        onSuccess = { text ->
                            if (AppSettings.config.value.autoSendVoice) {
                                session.send(text)
                            } else {
                                input = text
                            }
                        },
                        onFailure = { e -> voiceError = e.message }
                    )
                }
            )
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(topBarBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = topBarBehavior,
                title = {
                    Column {
                        Text(
                            AppSettings.activePersonaName()
                                .takeIf { it != "Default" }
                                ?: cfg.model.ifBlank { "AndroidAssist" },
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
                    // 1) SPEAK: toggle the spoken answer (TTS) right from the
                    //    top bar — the same switch as Settings > Output, so
                    //    the two never disagree.
                    IconButton(onClick = {
                        val on = !cfg.ttsEnabled
                        AppSettings.update { c -> c.copy(ttsEnabled = on) }
                        if (!on) {
                            // Turning it off stops the speech in flight.
                            TtsManager.stop()
                            FishTts.stop()
                        }
                    }) {
                        Icon(
                            if (cfg.ttsEnabled) Icons.Default.VolumeUp
                            else Icons.Default.VolumeOff,
                            contentDescription = Strings.t(
                                if (cfg.ttsEnabled) Strings.speakAloudOn
                                else Strings.speakAloudOff
                            ),
                            tint = if (cfg.ttsEnabled)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // 2) NEW MESSAGE: the "compose" bubble of the AI chat
                    //    apps — it starts a fresh conversation.
                    IconButton(onClick = { session.reset(context.applicationContext) }) {
                        Icon(
                            Icons.Default.AddComment,
                            contentDescription = Strings.t(Strings.newMessage)
                        )
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(Icons.Default.History, contentDescription = Strings.t(Strings.chatHistory))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = Strings.t(Strings.settings))
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
                Column {
                    // Attachment preview strip
                    attachedImage?.let { bmp ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 8.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Attached image",
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                Strings.t(Strings.imageAttached),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { clearAttachments() }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Close, Strings.t(Strings.remove),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    attachedPdfSummary?.let { summary ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 8.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { clearAttachments() }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Close, Strings.t(Strings.remove),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    attachError?.let { err ->
                        Text(
                            err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                        )
                    }
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 5.dp, bottom = 5.dp)
                        .imePadding()
                        .navigationBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { showToolsMenu = true },
                        enabled = !session.busy,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Add, Strings.t(Strings.chatMenuTitle),
                            tint = if (attachedImageBase64 != null || attachedPdfText != null)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                    // send as soon as there is text or an attachment to send.
                    FilledIconButton(
                        onClick = {
                            when {
                                session.busy -> session.stop()
                                input.isNotBlank() || attachedImageBase64 != null || attachedPdfText != null -> sendCurrent()
                                listening -> listening = false
                                else -> startVoice()
                            }
                        },
                        enabled = true,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = when {
                                session.busy -> MaterialTheme.colorScheme.error
                                input.isNotBlank() || attachedImageBase64 != null || attachedPdfText != null -> MaterialTheme.colorScheme.primary
                                listening -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                    ) {
                        when {
                            session.busy -> Icon(
                                Icons.Default.Stop, Strings.t(Strings.stop),
                                tint = MaterialTheme.colorScheme.onError
                            )
                            input.isNotBlank() || attachedImageBase64 != null || attachedPdfText != null -> Icon(
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
                    MessageBubble(msg, onImageClick = { img -> viewerImage = img })
                }
                if (listening) {
                    item { ThinkingBubble(Strings.t(Strings.listening)) }
                }
                if (session.busy && session.messages.lastOrNull()?.role != "assistant") {
                    item { ThinkingBubble(session.status) }
                }
                voiceError?.let { err ->
                    item {
                        Text(
                            Strings.t(Strings.voiceErrorColon) + " $err",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
                // The SENTINEL: an empty item at the very end of the list.
                // Scrolling to it is the exact "bottom" — whatever the
                // height of the last bubble. Without it, scrolling to the
                // last MESSAGE puts its TOP at the top of the screen, so
                // the view jumps up while the text streams.
                item { Spacer(Modifier.height(1.dp)) }
            }


            viewerImage?.let { img ->
                ImageViewerDialog(imageBase64 = img, onDismiss = { viewerImage = null })
            }
        }
    }

    // The "+" menu: the attachments, the web search and the tools.
    if (showToolsMenu) {
        ModalBottomSheet(
            onDismissRequest = { showToolsMenu = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    Strings.t(Strings.chatMenuTitle),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(
                        onClick = {
                            showToolsMenu = false
                            attachLauncher.launch(arrayOf("image/*"))
                        }
                    ) {
                        Icon(Icons.Default.Image, null, Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(Strings.t(Strings.attachPhoto))
                    }
                    FilledTonalButton(
                        onClick = {
                            showToolsMenu = false
                            attachLauncher.launch(arrayOf("application/pdf"))
                        }
                    ) {
                        Icon(Icons.Default.Description, null, Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(Strings.t(Strings.attachDocument))
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        Strings.t(Strings.webSearchSection),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = webSearchEffective,
                        onCheckedChange = { webSearchOn = it }
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    Strings.t(Strings.chatMenuTools),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                abilityGroups.forEach { (groupTitle, rows) ->
                    Text(
                        Strings.t(groupTitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    rows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                row.icon, null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                Strings.t(row.titleKey),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = AppSettings.abilityEnabled(row.id),
                                onCheckedChange = { on ->
                                    AppSettings.setAbilityEnabled(row.id, on)
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }

}
