package com.taboia872.androidassist.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taboia872.androidassist.BuildConfig
import com.taboia872.androidassist.llm.LlmProviders
import com.taboia872.androidassist.llm.ModelCatalog
import com.taboia872.androidassist.llm.ModelInfo
import com.taboia872.androidassist.settings.AppSettings
import kotlinx.coroutines.launch

private enum class SettingsCategory(val label: String) {
    AI("AI"),
    VOICE("Voice"),
    SYSTEM("System")
}

/**
 * Settings in the base app's visual language: overview card on top, category
 * tabs, sections as rounded cards with icon list items, choices in large
 * dialogs. Driven entirely by AppSettings — one source of truth.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val cfg by AppSettings.config.collectAsState()
    val provider = LlmProviders.byId(cfg.providerId)

    var settingsCategoryName by rememberSaveable { mutableStateOf(SettingsCategory.AI.name) }
    val settingsCategory = SettingsCategory.valueOf(settingsCategoryName)
    val settingsScrollState = rememberScrollState()

    var showProviderDialog by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showPromptDialog by remember { mutableStateOf(false) }
    var showVoiceLangDialog by remember { mutableStateOf(false) }
    var showVoiceMethodDialog by remember { mutableStateOf(false) }
    var showWhisperModelDialog by remember { mutableStateOf(false) }

    LaunchedEffect(settingsCategory) { settingsScrollState.scrollTo(0) }

    val keyStatus = if (provider.requiresKey) {
        if (cfg.apiKey.isNotBlank()) "${provider.displayName} key saved"
        else "${provider.displayName} key needed"
    } else "No key required"

    val modelLabel = cfg.model.ifBlank { "Select model" }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(settingsScrollState)
                    .padding(bottom = 20.dp)
            ) {
                SettingsOverviewCard(
                    provider = provider.displayName,
                    model = modelLabel,
                    keyStatus = keyStatus
                )

                SettingsCategoryTabs(
                    selectedCategory = settingsCategory,
                    onCategorySelected = { settingsCategoryName = it.name }
                )

                if (settingsCategory == SettingsCategory.AI) {
                    SettingsSection(title = "API Configuration") {
                        SettingsItem(
                            icon = Icons.Default.Cloud,
                            title = "Cloud Provider",
                            subtitle = provider.displayName,
                            onClick = { showProviderDialog = true }
                        )
                        SettingsItem(
                            icon = Icons.Default.Key,
                            title = "${provider.displayName} API Key",
                            subtitle = if (cfg.apiKey.isNotBlank()) "Configured ✓"
                            else if (provider.requiresKey) "Required • saved encrypted"
                            else "Not required for this provider",
                            onClick = { showKeyDialog = true }
                        )
                        SettingsItem(
                            icon = Icons.Default.Link,
                            title = "Base URL",
                            subtitle = cfg.baseUrl.ifBlank { "Not set" },
                            onClick = { showUrlDialog = true }
                        )
                    }

                    SettingsSection(title = "AI Model") {
                        SettingsItem(
                            icon = Icons.Default.SmartToy,
                            title = "Model",
                            subtitle = modelLabel,
                            onClick = { showModelDialog = true }
                        )
                        SettingsItem(
                            icon = Icons.Default.Refresh,
                            title = "Refresh model catalog",
                            subtitle = "Fetch the live list from ${provider.displayName}",
                            onClick = { showModelDialog = true }
                        )
                        SettingsItem(
                            icon = Icons.Default.Edit,
                            title = "System Prompt",
                            subtitle = cfg.systemPrompt.take(50) +
                                if (cfg.systemPrompt.length > 50) "..." else "",
                            onClick = { showPromptDialog = true }
                        )
                    }
                }

                if (settingsCategory == SettingsCategory.VOICE) {
                    SettingsSection(title = "Voice Input") {
                        SettingsItem(
                            icon = Icons.Default.RecordVoiceOver,
                            title = "Speech recognition",
                            subtitle = if (cfg.voiceMethod == "system")
                                "System recognizer (free, no key)"
                            else "Groq Whisper (online)",
                            onClick = { showVoiceMethodDialog = true }
                        )
                        if (cfg.voiceMethod != "system") {
                            SettingsItem(
                                icon = Icons.Default.SmartToy,
                                title = "Whisper model",
                                subtitle = cfg.whisperModel,
                                onClick = { showWhisperModelDialog = true }
                            )
                        }
                        SettingsItemWithSwitch(
                            icon = Icons.Default.Mic,
                            title = "Auto-start listening",
                            subtitle = "Begin capturing audio the moment the assistant opens",
                            checked = cfg.autoStartVoice,
                            onCheckedChange = {
                                AppSettings.update { c -> c.copy(autoStartVoice = it) }
                            }
                        )
                        SettingsItemWithSwitch(
                            icon = Icons.AutoMirrored.Filled.Send,
                            title = "Auto-send after speech",
                            subtitle = "Send the transcript to the model as soon as speech ends",
                            checked = cfg.autoSendVoice,
                            onCheckedChange = {
                                AppSettings.update { c -> c.copy(autoSendVoice = it) }
                            }
                        )
                        SettingsItem(
                            icon = Icons.Default.Translate,
                            title = "Recognition language",
                            subtitle = cfg.voiceLanguage.ifBlank { "System default" },
                            onClick = { showVoiceLangDialog = true }
                        )
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                if (cfg.voiceMethod == "system")
                                    "Uses Android's built-in speech recognizer (usually the Google app). No API key required."
                                else
                                    "Transcription runs on Groq Whisper (online) using the Groq API key saved in the AI tab.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                if (settingsCategory == SettingsCategory.SYSTEM) {
                    SettingsSection(title = "About") {
                        SettingsItem(
                            icon = Icons.Default.Info,
                            title = "AndroidAssist",
                            subtitle = "v${BuildConfig.VERSION_NAME}",
                            onClick = {}
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showProviderDialog) {
        ProviderSelectionDialog(
            currentProviderId = cfg.providerId,
            onProviderSelected = {
                AppSettings.setProvider(it)
                showProviderDialog = false
            },
            onDismiss = { showProviderDialog = false }
        )
    }

    if (showKeyDialog) {
        ProviderKeyDialog(
            providerName = provider.displayName,
            hint = provider.keyHint,
            hasExistingKey = cfg.apiKey.isNotBlank(),
            onApiKeySaved = { key ->
                AppSettings.setApiKey(provider.id, key)
                showKeyDialog = false
            },
            onDismiss = { showKeyDialog = false }
        )
    }

    if (showUrlDialog) {
        BaseUrlDialog(
            currentUrl = cfg.baseUrl,
            onUrlSaved = { url ->
                AppSettings.update { it.copy(baseUrl = url.trim()) }
                showUrlDialog = false
            },
            onDismiss = { showUrlDialog = false }
        )
    }

    if (showModelDialog) {
        ModelSelectionDialog(
            baseUrl = cfg.baseUrl,
            apiKey = cfg.apiKey,
            currentModel = cfg.model,
            onModelSelected = { modelId ->
                AppSettings.update { it.copy(model = modelId) }
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false }
        )
    }

    if (showPromptDialog) {
        SystemPromptDialog(
            currentPrompt = cfg.systemPrompt,
            onPromptSaved = { prompt ->
                AppSettings.update { it.copy(systemPrompt = prompt) }
                showPromptDialog = false
            },
            onDismiss = { showPromptDialog = false }
        )
    }

    if (showVoiceLangDialog) {
        VoiceLanguageDialog(
            currentLanguage = cfg.voiceLanguage,
            onLanguageSelected = { lang ->
                AppSettings.update { it.copy(voiceLanguage = lang) }
                showVoiceLangDialog = false
            },
            onDismiss = { showVoiceLangDialog = false }
        )
    }

    if (showVoiceMethodDialog) {
        VoiceMethodDialog(
            currentMethod = cfg.voiceMethod,
            onMethodSelected = { method ->
                AppSettings.update { it.copy(voiceMethod = method) }
                showVoiceMethodDialog = false
            },
            onDismiss = { showVoiceMethodDialog = false }
        )
    }

    if (showWhisperModelDialog) {
        WhisperModelDialog(
            currentModel = cfg.whisperModel,
            onModelSelected = { model ->
                AppSettings.update { it.copy(whisperModel = model) }
                showWhisperModelDialog = false
            },
            onDismiss = { showWhisperModelDialog = false }
        )
    }
}

// ---------------------------------------------------------------- components

@Composable
private fun SettingsCategoryTabs(
    selectedCategory: SettingsCategory,
    onCategorySelected: (SettingsCategory) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsCategory.entries.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onCategorySelected(category) },
                label = { Text(category.label) }
            )
        }
    }
}

@Composable
private fun SettingsOverviewCard(
    provider: String,
    model: String,
    keyStatus: String
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 2.dp, end = 16.dp, bottom = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Assistant setup",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        keyStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsStatusChip(
                    icon = Icons.Default.Cloud,
                    label = provider,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            SettingsStatusChip(
                icon = Icons.Default.SmartToy,
                label = model,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun SettingsStatusChip(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 40.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)
        )
        Surface(
            modifier = Modifier.padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 0.dp
        ) {
            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingContent = {
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .defaultMinSize(minHeight = 72.dp)
            .clickable(onClick = onClick)
    )
}

/** Settings row with a trailing Switch (visual language of the base app). */
@Composable
private fun SettingsItemWithSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.large)
            .defaultMinSize(minHeight = 72.dp)
            .clickable { onCheckedChange(!checked) }
    )
}

// ------------------------------------------------------------------- dialogs

@Composable
private fun ProviderSelectionDialog(
    currentProviderId: String,
    onProviderSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cloud Provider", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LlmProviders.ALL.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProviderSelected(p.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentProviderId == p.id,
                            onClick = { onProviderSelected(p.id) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(p.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                p.defaultBaseUrl.removePrefix("https://")
                                    .ifBlank { "your own base URL" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ProviderKeyDialog(
    providerName: String,
    hint: String,
    hasExistingKey: Boolean,
    onApiKeySaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var apiKey by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$providerName API Key") },
        text = {
            Column {
                if (hasExistingKey) {
                    Text(
                        "An API key is already configured. Enter a new key to replace it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    visualTransformation = if (visible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                "Toggle"
                            )
                        }
                    }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onApiKeySaved(apiKey) },
                enabled = apiKey.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun BaseUrlDialog(
    currentUrl: String,
    onUrlSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Base URL") },
        text = {
            Column {
                Text(
                    "OpenAI-compatible endpoint. Leave the provider default unless you run your own server (e.g. Ollama on http://192.168.0.10:11434/v1).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    placeholder = { Text("http://192.168.0.10:11434/v1") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onUrlSaved(url) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * THE model picker: fetches the live catalog from the ACTIVE server with a
 * refresh button, search filter (OpenRouter has 300+ entries), free/premium
 * grouping, vision chips, and a manual-ID fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSelectionDialog(
    baseUrl: String,
    apiKey: String,
    currentModel: String,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var models by remember { mutableStateOf<List<ModelInfo>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var showCustomInput by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load(force: Boolean) {
        loading = true
        error = null
        scope.launch {
            ModelCatalog.fetch(baseUrl, apiKey, forceRefresh = force).fold(
                onSuccess = { models = it },
                onFailure = { error = it.message }
            )
            loading = false
        }
    }

    // Auto-fetch on open (cache makes reopening instant)
    LaunchedEffect(baseUrl, apiKey) { load(force = false) }

    if (showCustomInput) {
        CustomModelDialog(
            currentCustomModel = currentModel,
            onModelSaved = { modelId ->
                if (modelId.isNotBlank()) onModelSelected(modelId.trim())
            },
            onDismiss = { showCustomInput = false }
        )
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column {
                TopAppBar(
                    title = { Text("Select Model") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { load(force = true) },
                            enabled = !loading
                        ) {
                            Icon(Icons.Default.Refresh, "Refresh catalog")
                        }
                    }
                )

                // Search/filter — essential for OpenRouter-sized catalogs
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Filter models...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )

                when {
                    loading -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Loading models from server...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    error != null -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Cloud,
                                null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Could not load models:\n$error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { load(force = true) }) {
                                    Icon(Icons.Default.Refresh, null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Retry")
                                }
                                OutlinedButton(onClick = { showCustomInput = true }) {
                                    Text("Type model ID")
                                }
                            }
                        }
                    }

                    else -> {
                        val all = models.orEmpty()
                        val q = query.trim().lowercase()
                        val filtered = if (q.isEmpty()) all
                        else all.filter {
                            it.id.lowercase().contains(q) || it.name.lowercase().contains(q)
                        }

                        if (filtered.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (all.isEmpty()) "No models available"
                                    else "No models match \"$query\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                val freeModels = filtered.filter { it.isFree }
                                val premiumModels = filtered.filter { !it.isFree }

                                if (freeModels.isNotEmpty()) {
                                    item {
                                        Text(
                                            "Free Models",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(
                                                start = 16.dp, end = 16.dp, top = 8.dp
                                            )
                                        )
                                    }
                                    items(freeModels) { model ->
                                        ModelItem(
                                            model = model,
                                            isSelected = model.id == currentModel,
                                            onClick = { onModelSelected(model.id) }
                                        )
                                    }
                                }
                                if (premiumModels.isNotEmpty()) {
                                    item {
                                        Text(
                                            if (freeModels.isNotEmpty()) "Premium Models"
                                            else "Available Models",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(
                                                start = 16.dp, end = 16.dp, top = 8.dp
                                            )
                                        )
                                    }
                                    items(premiumModels) { model ->
                                        ModelItem(
                                            model = model,
                                            isSelected = model.id == currentModel,
                                            onClick = { onModelSelected(model.id) }
                                        )
                                    }
                                }

                                item {
                                    Text(
                                        "Manual",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(
                                            start = 16.dp, end = 16.dp, top = 8.dp
                                        )
                                    )
                                }
                                item {
                                    ListItem(
                                        headlineContent = {
                                            Text(
                                                "Custom model...",
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        supportingContent = {
                                            Text(
                                                if (currentModel.isNotBlank()) "Current: $currentModel"
                                                else "Type any model ID this server accepts"
                                            )
                                        },
                                        leadingContent = {
                                            RadioButton(
                                                selected = false,
                                                onClick = { showCustomInput = true }
                                            )
                                        },
                                        modifier = Modifier
                                            .clickable { showCustomInput = true }
                                            .padding(vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(visible = models != null) {
                    Text(
                        "${models?.size ?: 0} models • live from server",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelItem(
    model: ModelInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                model.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.id, style = MaterialTheme.typography.bodySmall)
                Text(model.description)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (model.isFree) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("Free") },
                            leadingIcon = {
                                Icon(Icons.Default.Savings, null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                    if (model.supportsVision) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("Vision") },
                            leadingIcon = {
                                Icon(Icons.Default.Visibility, null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                }
            }
        },
        leadingContent = {
            RadioButton(selected = isSelected, onClick = onClick)
        },
        colors = ListItemDefaults.colors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
    )
}

@Composable
private fun CustomModelDialog(
    currentCustomModel: String,
    onModelSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var modelId by remember { mutableStateOf(currentCustomModel) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom Model ID") },
        text = {
            Column {
                Text(
                    "Enter a model ID this server accepts (e.g. llama-3.3-70b-versatile).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model ID") },
                    placeholder = { Text("provider/model-name") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onModelSaved(modelId) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SystemPromptDialog(
    currentPrompt: String,
    onPromptSaved: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by remember { mutableStateOf(currentPrompt) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text("System Prompt", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "This prompt defines how the AI assistant behaves.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    label = { Text("System Prompt") },
                    placeholder = { Text("Enter instructions for the AI...") },
                    shape = RoundedCornerShape(16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            prompt = "You are a helpful, concise assistant. Always reply in the user's language."
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Reset") }
                    Button(
                        onClick = { onPromptSaved(prompt) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("Save") }
                }
            }
        }
    }
}

/** Common Whisper languages; empty choice = system locale. */
@Composable
/** Common Whisper languages; empty choice = system locale. */
@Composable
private fun VoiceLanguageDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val languages = listOf(
        "" to "System default",
        "pt" to "Português",
        "en" to "English",
        "es" to "Español",
        "fr" to "Français",
        "de" to "Deutsch",
        "it" to "Italiano",
        "ja" to "日本語",
        "ko" to "한국어",
        "zh" to "中文",
        "ru" to "Русский"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recognition language") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                languages.forEach { (code, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLanguageSelected(code) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentLanguage == code,
                            onClick = { onLanguageSelected(code) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Voice input engine: system recognizer vs Groq Whisper. */
@Composable
private fun VoiceMethodDialog(
    currentMethod: String,
    onMethodSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val methods = listOf(
        Triple("system", "System recognizer", "Android's built-in (Google app). Free, no API key."),
        Triple("groq", "Groq Whisper", "Online transcription using the Groq API key.")
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Speech recognition") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                methods.forEach { (id, name, description) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMethodSelected(id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentMethod == id,
                            onClick = { onMethodSelected(id) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/** Groq Whisper transcription model. */
@Composable
private fun WhisperModelDialog(
    currentModel: String,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val models = listOf(
        Triple("whisper-large-v3", "whisper-large-v3", "Most accurate"),
        Triple("whisper-large-v3-turbo", "whisper-large-v3-turbo", "Faster, slightly less accurate")
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Whisper model") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                models.forEach { (id, name, description) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModelSelected(id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentModel == id,
                            onClick = { onModelSelected(id) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
