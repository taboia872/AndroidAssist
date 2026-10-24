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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.util.PdfExtractor
import com.taboia872.androidassist.util.PdfResult
import com.taboia872.androidassist.voice.VoiceEngine
import com.taboia872.androidassist.voice.SystemRecognizer
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
    onConsumeResumeId: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit
) {
    // Browser-style: the top bar hides when the chat scrolls DOWN and comes
    // back when you scroll up (enterAlways).
    val topBarBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val cfg by AppSettings.config.collectAsState()
    val context = LocalContext.current
    // App-scoped session: streaming and the end-of-turn save survive
    // navigation (Settings/History) and backgrounding.
    val session = ChatSessions.main
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
    // yanking the view back to the bottom (user report). One nudge per new
    // message, then hands off.
    var autoScroll by remember { mutableStateOf(true) }

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
                    IconButton(onClick = { session.reset(context.applicationContext) }) {
                        Icon(Icons.Default.Refresh, contentDescription = Strings.t(Strings.newChat))
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
                        onClick = { attachLauncher.launch(arrayOf("image/*", "application/pdf")) },
                        enabled = !session.busy,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.AttachFile, "Attach",
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
            }

            // user drag cancels auto-follow
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) autoScroll = false
            }
            // new message re-enables follow ONCE (next user drag cancels again)
            LaunchedEffect(session.messages.size) {
                if (session.messages.isNotEmpty()) {
                    autoScroll = true
                    listState.animateScrollToItem(session.messages.lastIndex)
                }
            }

            viewerImage?.let { img ->
                ImageViewerDialog(imageBase64 = img, onDismiss = { viewerImage = null })
            }
        }
    }
}
