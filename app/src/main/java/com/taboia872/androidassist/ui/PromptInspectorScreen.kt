package com.taboia872.androidassist.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.i18n.Strings
import com.taboia872.androidassist.llm.LlmClient
import com.taboia872.androidassist.memory.MemoryStore
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.tools.ToolCatalog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The PROMPT INSPECTOR: shows exactly what the app would send to the
 * model with the CURRENT settings — the system prompt, the memories,
 * the tools that are declared — and how big that initial prompt is.
 *
 * It stops right where the user's message would go, so the user can
 * see the fixed cost of every conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptInspectorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cfg = AppSettings.config.value

    val preview = remember(cfg, context) {
        // The same pieces the real request builds.
        val now = Date()
        val today = SimpleDateFormat("yyyy-MM-dd (EEEE)", Locale.getDefault()).format(now)
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val zone = TimeZone.getDefault().id
        var system = cfg.systemPrompt +
            "\n\nCurrent date: $today.\nCurrent time: $clock ($zone)."
        if (cfg.noThink) {
            system += "\n\nDo NOT produce any reasoning or thinking " +
                "process; answer directly and concisely."
        }
        if (cfg.memoriesInPrompt) {
            system += MemoryStore.promptBlock(
                context,
                ToolCatalog.memoryToolsDeclared(cfg, null)
            )
        }
        val (specs, names) = LlmClient.currentToolSpecs()
        val availability = ToolCatalog.availabilityBlock(names, emptyList())
        val toolsJson = if (specs.length() > 0) {
            "\n\n" + specs.toString(2)
        } else {
            ""
        }
        val systemPart = system + availability
        PromptPreview(
            systemPart = systemPart,
            toolsJson = toolsJson,
            toolNames = names
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(Strings.t(Strings.promptInspectorTitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, Strings.t(Strings.back))
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(
                            android.content.Context.CLIPBOARD_SERVICE
                        ) as? android.content.ClipboardManager
                        clipboard?.setPrimaryClip(
                            android.content.ClipData.newPlainText(
                                Strings.t(Strings.promptInspectorTitle),
                                preview.fullText()
                            )
                        )
                        android.widget.Toast.makeText(
                            context,
                            Strings.t(Strings.promptInspectorCopied),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, Strings.t(Strings.copyPrompt))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // The size: the fixed cost of every conversation.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        Strings.t(Strings.promptInspectorSize),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        buildString {
                            append(Strings.t(Strings.promptInspectorChars))
                            append(": ")
                            append(preview.totalChars)
                            append("\n")
                            append(Strings.t(Strings.promptInspectorTokens))
                            append(": ~")
                            append(preview.estimatedTokens)
                            append("\n")
                            append(Strings.t(Strings.promptInspectorTools))
                            append(": ")
                            append(
                                if (preview.toolNames.isEmpty()) {
                                    Strings.t(Strings.promptInspectorNoTools)
                                } else {
                                    preview.toolNames.joinToString(", ")
                                }
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                Strings.t(Strings.promptInspectorNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            // The text itself: the system prompt, then the tools.
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        Strings.t(Strings.promptInspectorSystem),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        preview.systemPart,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    if (preview.toolsJson.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            Strings.t(Strings.promptInspectorToolsJson),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            preview.toolsJson,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        Strings.t(Strings.promptInspectorUserMessageHere),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

/** The preview pieces (the system prompt, the tools and the sizes). */
private data class PromptPreview(
    val systemPart: String,
    val toolsJson: String,
    val toolNames: List<String>
) {
    /** Everything the request sends before the user's message. */
    fun fullText(): String = systemPart + toolsJson +
        "\n\n[the user's message goes here]"

    /** The characters of the whole initial request (prompt + tools). */
    val totalChars: Int get() = systemPart.length + toolsJson.length

    /** A rough token estimate (the characters divided by 4). */
    val estimatedTokens: Int get() = totalChars / 4
}
