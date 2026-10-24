package com.taboia872.androidassist.ui

import com.taboia872.androidassist.i18n.Strings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.Dialog

/**
 * Block-based chat rendering (llama.cpp-style). Each message renders its
 * blocks IN ORDER: reasoning blocks are collapsible (minimized by default),
 * tool blocks show the search marker, answer blocks render markdown.
 * ALL text is selectable. Attached images are clickable -> fullscreen.
 */

@Composable
fun MessageBubble(msg: ChatMessage, onImageClick: (String) -> Unit = {}) {
    val isUser = msg.role == "user"
    // Landscape/tablet: bubbles expand to 80% of the screen width instead
    // of the fixed 320/340dp portrait size (user report).
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val bubbleMax = maxOf(screenW * 0.8f, 320.dp)

    // Decode attached image for display (if any)
    val imageBitmap = remember(msg.imageBase64) {
        msg.imageBase64?.let {
            try {
                val bytes = android.util.Base64.decode(it, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }

    // USER: single answer block, selectable, image clickable
    if (isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            SelectionContainer {
                Surface(
                    modifier = Modifier.widthIn(max = bubbleMax),
                    shape = RoundedCornerShape(
                        topStart = 20.dp, topEnd = 4.dp,
                        bottomStart = 20.dp, bottomEnd = 20.dp
                    ),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        imageBitmap?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = Strings.t(Strings.imageAttached),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onImageClick(msg.imageBase64 ?: "") }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        msg.blocks.forEach { b ->
                            if (b.text.isNotBlank()) {
                                Text(
                                    b.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // ASSISTANT: the CHAT TIMELINE. Reasoning and tool blocks render as
    // BARE blocks OUTSIDE any bubble (user request — chronological order,
    // no bubble around them); answer blocks render inside the bubble.
    // Image attaches to the first answer bubble.
    Column(modifier = Modifier.fillMaxWidth()) {
        var imageShown = false
        msg.blocks.forEach { block ->
            when (block.kind) {
                "reasoning" -> {
                    ReasoningBlock(block.text, liveStream = block.streaming)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                "tool" -> {
                    ToolBlock(block.text, block.detail)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                else -> {
                    // Answer bubble (image rides along on the first one)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start
                    ) {
                        SelectionContainer {
                            Surface(
                                modifier = Modifier.widthIn(max = bubbleMax),
                                shape = RoundedCornerShape(
                                    topStart = 4.dp, topEnd = 20.dp,
                                    bottomStart = 20.dp, bottomEnd = 20.dp
                                ),
                                tonalElevation = 1.dp,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    if (!imageShown) {
                                        imageBitmap?.let { bmp ->
                                            Image(
                                                bitmap = bmp.asImageBitmap(),
                                                contentDescription = Strings.t(Strings.imageAttached),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .heightIn(max = 200.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .clickable { onImageClick(msg.imageBase64 ?: "") }
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                        }
                                        imageShown = true
                                    }
                                    AnswerBlock(block)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

/** Answer block: markdown, standardized bodyLarge font. Streaming blocks use
 *  the synchronous renderer (the library re-parses async per token and
 *  flickers); finished blocks use the full library renderer. */
@Composable
private fun AnswerBlock(block: ChatBlock) {
    Column {
        if (block.streaming) {
            MarkdownTextStreaming(
                text = block.text,
                color = MaterialTheme.colorScheme.onSurface,
                linkColor = MaterialTheme.colorScheme.primary
            )
        } else {
            MarkdownText(
                text = block.text,
                color = MaterialTheme.colorScheme.onSurface,
                linkColor = MaterialTheme.colorScheme.primary
            )
        }
        if (block.streaming) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "▍",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * Reasoning block: COLLAPSIBLE, minimized by default, separate block per
 * thought (user request — llama.cpp style). Streaming reasoning shows live.
 */
@Composable
fun ReasoningBlock(text: String, liveStream: Boolean = false) {
    // State is NOT keyed on text: rekeying on every streamed token kept
    // resetting expanded=false — the block retraced/retracted and could
    // not be read while the model was still thinking. User taps win —
    // including COLLAPSING a live-streaming block.
    var expanded by remember { mutableStateOf(false) }
    var userCollapsed by remember { mutableStateOf(false) }
    // While LIVE: the block auto-opens unless the user collapsed it. The
    // tap handler below keeps userCollapsed in sync.
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        if (liveStream) {
                            userCollapsed = !userCollapsed
                            expanded = false
                        } else {
                            expanded = !expanded
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    Strings.t(Strings.thoughtProcess) + if (liveStream) " …" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                val openNow = if (liveStream) !userCollapsed else expanded
                Icon(
                    if (openNow) Icons.Default.KeyboardArrowUp
                    else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (openNow) Strings.t(Strings.collapse)
                    else Strings.t(Strings.expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded || (liveStream && !userCollapsed)) {
                Text(
                    text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Tool block: the visible search marker, separate block per tool call.
 * COLLAPSIBLE like the reasoning block — tap to read what the search
 * engine actually returned (titles, URLs, snippets, or the failure).
 */
@Composable
fun ToolBlock(text: String, detail: String = "") {
    var expanded by rememberSaveable(text + detail) { mutableStateOf(false) }
    // Icon follows the tool: search (🔍) keeps TravelExplore; device tools
    // (⏰ ⏱ 📅 📱 📋 🔗) map to their Material icons.
    val icon: androidx.compose.ui.graphics.vector.ImageVector = when {
        text.startsWith("\ud83d\udd0d") -> Icons.Default.TravelExplore
        text.startsWith("\u23f0") -> Icons.Default.Alarm
        text.startsWith("\u23f1") -> Icons.Default.Timer
        text.startsWith("\ud83d\udcc5") -> Icons.Default.Event
        text.startsWith("\ud83d\udcf1") -> Icons.Default.Apps
        text.startsWith("\ud83d\udccb") -> Icons.Default.ContentPaste
        text.startsWith("\ud83d\udd17") -> Icons.Default.Share
        text.startsWith("\ud83d\udccd") -> Icons.Default.Place
        else -> Icons.Default.Build
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = detail.isNotBlank()) { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text.removePrefix("\ud83d\udd0d").removePrefix("\u23f0")
                        .removePrefix("\u23f1").removePrefix("\ud83d\udcc5")
                        .removePrefix("\ud83d\udcf1").removePrefix("\ud83d\udccb")
                        .removePrefix("\ud83d\udd17")
                        .removePrefix("\ud83d\udccd"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                    maxLines = 2
                )
                if (detail.isNotBlank()) {
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    detail,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Fullscreen image viewer (click attached image to open, tap to close). */
@Composable
fun ImageViewerDialog(imageBase64: String, onDismiss: () -> Unit) {
    val bmp = remember(imageBase64) {
        try {
            val bytes = android.util.Base64.decode(imageBase64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            bmp?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = Strings.t(Strings.imageAttached),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun ThinkingBubble(status: String) {
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
                    status.ifEmpty { Strings.t(Strings.thinking) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
