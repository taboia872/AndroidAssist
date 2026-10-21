package com.taboia872.androidassist.ui

import com.taboia872.androidassist.i18n.Strings
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Switch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.taboia872.androidassist.llm.LlmProviders
import com.taboia872.androidassist.llm.ModelCatalog
import com.taboia872.androidassist.llm.ModelInfo
import com.taboia872.androidassist.search.SearchEngines
import com.taboia872.androidassist.settings.AppSettings
import com.taboia872.androidassist.tts.TtsManager
import com.taboia872.androidassist.tts.FishTts
import com.taboia872.androidassist.tts.FishVoice
import kotlinx.coroutines.launch

private enum class SettingsCategory(val label: String) {
    AI(Strings.t(Strings.aiTab)),
    VOICE(Strings.t(Strings.voiceTab)),
    OUTPUT(Strings.t(Strings.outputTab)),
    TOOLS(Strings.t(Strings.toolsTab)),
    SYSTEM(Strings.t(Strings.systemTab))
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
    val context = LocalContext.current

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
    var showVoiceGroqKeyDialog by remember { mutableStateOf(false) }
    var showTtsMethodDialog by remember { mutableStateOf(false) }
    var showFishKeyDialog by remember { mutableStateOf(false) }
    var showFishModelDialog by remember { mutableStateOf(false) }
    var showFishVoiceDialog by remember { mutableStateOf(false) }
    var showSearchEngineDialog by remember { mutableStateOf(false) }
    var showSearchKeyDialog by remember { mutableStateOf(false) }
    var showPersonaDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showTtsLangDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAccentDialog by remember { mutableStateOf(false) }
    var showSearchCountDialog by remember { mutableStateOf(false) }
    var showSearchRegionDialog by remember { mutableStateOf(false) }
    var showSearchFreshnessDialog by remember { mutableStateOf(false) }

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
                title = { Text(Strings.t(Strings.titleSettings)) },
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
                    personaName = AppSettings.activePersonaName(),
                    hasPersonas = AppSettings.personas().isNotEmpty(),
                    onPersonaClick = { showPersonaDialog = true },
                    provider = provider.displayName,
                    model = modelLabel,
                    keyStatus = keyStatus
                )

                SettingsCategoryTabs(
                    selectedCategory = settingsCategory,
                    onCategorySelected = { settingsCategoryName = it.name }
                )

                if (settingsCategory == SettingsCategory.AI) {
                    SettingsSection(title = Strings.t(Strings.apiConfig)) {
                        SettingsItem(
                            icon = Icons.Default.Cloud,
                            title = Strings.t(Strings.cloudProvider),
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

                    SettingsSection(title = Strings.t(Strings.aiModel)) {
                        SettingsItem(
                            icon = Icons.Default.SmartToy,
                            title = Strings.t(Strings.model),
                            subtitle = modelLabel,
                            onClick = { showModelDialog = true }
                        )
                                                SettingsItem(
                            icon = Icons.Default.Edit,
                            title = Strings.t(Strings.systemPrompt),
                            subtitle = cfg.systemPrompt.take(50) +
                                if (cfg.systemPrompt.length > 50) "..." else "",
                            onClick = { showPromptDialog = true }
                        )
                        SettingsItemWithSwitch(
                            icon = Icons.Default.Bolt,
                            title = Strings.t(Strings.noThinkLabel),
                            subtitle = Strings.t(Strings.noThinkSub),
                            checked = cfg.noThink,
                            onCheckedChange = { on ->
                                AppSettings.update { it.copy(noThink = on) }
                            }
                        )
                    }


                }

                if (settingsCategory == SettingsCategory.VOICE) {
                    SettingsSection(title = Strings.t(Strings.voiceInputSection)) {
                        SettingsItem(
                            icon = Icons.Default.RecordVoiceOver,
                            title = Strings.t(Strings.speechRecognition),
                            subtitle = if (cfg.voiceMethod == "system")
                                Strings.t(Strings.systemRecognizerShort)
                            else Strings.t(Strings.groqWhisperOnline),
                            onClick = { showVoiceMethodDialog = true }
                        )
                        if (cfg.voiceMethod != "system") {
                            SettingsItem(
                                icon = Icons.Default.SmartToy,
                                title = Strings.t(Strings.whisperModelItem),
                                subtitle = cfg.whisperModel,
                                onClick = { showWhisperModelDialog = true }
                            )
                        }
                        SettingsItemWithSwitch(
                            icon = Icons.Default.Mic,
                            title = Strings.t(Strings.autoStart),
                            subtitle = Strings.t(Strings.autoStartSub),
                            checked = cfg.autoStartVoice,
                            onCheckedChange = {
                                AppSettings.update { c -> c.copy(autoStartVoice = it) }
                            }
                        )
                        SettingsItemWithSwitch(
                            icon = Icons.AutoMirrored.Filled.Send,
                            title = Strings.t(Strings.autoSend),
                            subtitle = Strings.t(Strings.autoSendSub),
                            checked = cfg.autoSendVoice,
                            onCheckedChange = {
                                AppSettings.update { c -> c.copy(autoSendVoice = it) }
                            }
                        )
                        SettingsItem(
                            icon = Icons.Default.Translate,
                            title = Strings.t(Strings.recognitionLanguage),
                            subtitle = cfg.voiceLanguage.ifBlank { Strings.t(Strings.systemDefault) },
                            onClick = { showVoiceLangDialog = true }
                        )
                        if (cfg.voiceMethod != "system") {
                            SettingsItem(
                                icon = Icons.Default.Key,
                                title = Strings.t(Strings.groqApiKey),
                                subtitle = if ((cfg.apiKeys["groq"] ?: "").isNotBlank())
                                    Strings.t(Strings.configured) else Strings.t(Strings.requiredForWhisper),
                                onClick = { showVoiceGroqKeyDialog = true }
                            )
                        }
                    }
                }

                if (settingsCategory == SettingsCategory.OUTPUT) {
                    val testScope = rememberCoroutineScope()
                    SettingsSection(title = Strings.t(Strings.speechOutputSection)) {
                        val ttsState by TtsManager.state.collectAsState()
                        val fishState by FishTts.state.collectAsState()
                        val activeSubtitle = when (cfg.ttsMethod) {
                            "fish" -> when (val fs = fishState) {
                                is TtsManager.TtsState.Failed -> fs.reason
                                is TtsManager.TtsState.Speaking -> Strings.t(Strings.speaking)
                                is TtsManager.TtsState.Initializing -> Strings.t(Strings.fishSynth)
                                else -> "FishAudio online • ${cfg.fishModel}"
                            }
                            else -> when (ttsState) {
                                is TtsManager.TtsState.Failed ->
                                    (ttsState as TtsManager.TtsState.Failed).reason
                                is TtsManager.TtsState.Speaking -> Strings.t(Strings.speaking)
                                is TtsManager.TtsState.Initializing -> Strings.t(Strings.ttsEngineStarting)
                                else -> Strings.t(Strings.systemTtsOffline)
                            }
                        }
                        SettingsItemWithSwitch(
                            icon = Icons.Default.VolumeUp,
                            title = Strings.t(Strings.readAloud),
                            subtitle = activeSubtitle,
                            checked = cfg.ttsEnabled,
                            onCheckedChange = { enabled ->
                                AppSettings.update { c -> c.copy(ttsEnabled = enabled) }
                                if (!enabled) {
                                    TtsManager.stop()
                                    FishTts.stop()
                                }
                            }
                        )
                        SettingsItem(
                            icon = Icons.Default.RecordVoiceOver,
                            title = Strings.t(Strings.speechEngine),
                            subtitle = if (cfg.ttsMethod == "fish")
                                Strings.t(Strings.fishOnlineNatural)
                            else Strings.t(Strings.systemTtsOfflineShort),
                            onClick = { showTtsMethodDialog = true }
                        )
                        if (cfg.ttsMethod != "fish") {
                            SettingsItem(
                                icon = Icons.Default.Translate,
                                title = Strings.t(Strings.synthesisLanguage),
                                subtitle = cfg.ttsLanguage.ifBlank { Strings.t(Strings.systemDefault) },
                                onClick = { showTtsLangDialog = true }
                            )
                        }
                        if (cfg.ttsMethod == "fish") {
                            SettingsItem(
                                icon = Icons.Default.Key,
                                title = Strings.t(Strings.fishApiKey),
                                subtitle = if ((cfg.apiKeys["fish"] ?: "").isNotBlank())
                                    Strings.t(Strings.configured) else Strings.t(Strings.requiredFish),
                                onClick = { showFishKeyDialog = true }
                            )
                            SettingsItem(
                                icon = Icons.Default.SmartToy,
                                title = Strings.t(Strings.fishModelItem),
                                subtitle = cfg.fishModel,
                                onClick = { showFishModelDialog = true }
                            )
                            SettingsItem(
                                icon = Icons.Default.Face,
                                title = Strings.t(Strings.voiceTab),
                                subtitle = cfg.fishVoiceName.ifBlank { Strings.t(Strings.platformDefaultVoice) },
                                onClick = { showFishVoiceDialog = true }
                            )
                        }
                        SettingsItem(
                            icon = Icons.Default.PlayArrow,
                            title = Strings.t(Strings.testVoice),
                            subtitle = Strings.t(Strings.testVoiceSub),
                            onClick = {
                                if (cfg.ttsMethod == "fish") {
                                    testScope.launch { FishTts.speak(Strings.t(Strings.testSentence)) }
                                } else if (!TtsManager.speak(
                                        Strings.t(Strings.testSentence),
                                        utteranceId = "test"
                                    )
                                ) {
                                    android.widget.Toast.makeText(
                                        context,
                                        Strings.t(Strings.ttsNotReady),
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )
                    }
                }

                if (settingsCategory == SettingsCategory.TOOLS) {
                    SettingsSection(title = Strings.t(Strings.webSearchSection)) {
                        SettingsItemWithSwitch(
                            icon = Icons.Default.TravelExplore,
                            title = Strings.t(Strings.searchTheWeb),
                            subtitle = "${Strings.t(Strings.freshFacts)} • ${SearchEngines.byId(cfg.searchEngine).name}",
                            checked = cfg.webSearchEnabled,
                            onCheckedChange = { enabled ->
                                AppSettings.update { c -> c.copy(webSearchEnabled = enabled) }
                            }
                        )
                        SettingsItem(
                            icon = Icons.Default.Search,
                            title = Strings.t(Strings.searchEngine),
                            subtitle = SearchEngines.byId(cfg.searchEngine).name +
                                if (SearchEngines.isConfigured(cfg.searchEngine)) ""
                                else " • ${Strings.t(Strings.apiKeyNeeded)}",
                            onClick = { showSearchEngineDialog = true }
                        )
                        val engine = SearchEngines.byId(cfg.searchEngine)
                        if (engine.keySlot != null) {
                            SettingsItem(
                                icon = Icons.Default.Key,
                                title = "${engine.name} ${Strings.t(Strings.apiKeyItem)}",
                                subtitle = if ((cfg.apiKeys[engine.keySlot] ?: "").isNotBlank())
                                    Strings.t(Strings.configured)
                                else engine.hint,
                                onClick = { showSearchKeyDialog = true }
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                "${Strings.t(Strings.privacyColon)} ${engine.privacy}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    SettingsSection(title = Strings.t(Strings.searchParamsSection)) {
                        SettingsItem(
                            icon = Icons.Default.Tune,
                            title = Strings.t(Strings.resultCount),
                            subtitle = "${cfg.searchResultCount} ${Strings.t(Strings.results)}",
                            onClick = { showSearchCountDialog = true }
                        )
                        SettingsItem(
                            icon = Icons.Default.Public,
                            title = Strings.t(Strings.searchRegionLabel),
                            subtitle = if (cfg.searchRegion.isBlank()) Strings.t(Strings.systemDefault)
                            else regionName(cfg.searchRegion),
                            onClick = { showSearchRegionDialog = true }
                        )
                        SettingsItem(
                            icon = Icons.Default.Schedule,
                            title = Strings.t(Strings.searchFreshnessLabel),
                            subtitle = when (cfg.searchFreshness) {
                                "day" -> Strings.t(Strings.freshDay)
                                "week" -> Strings.t(Strings.freshWeek)
                                "month" -> Strings.t(Strings.freshMonth)
                                else -> Strings.t(Strings.freshAny)
                            },
                            onClick = { showSearchFreshnessDialog = true }
                        )
                    }
                }

                if (settingsCategory == SettingsCategory.SYSTEM) {
                    val roleManager = remember {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                            context.getSystemService(android.app.role.RoleManager::class.java)
                        else null
                    }
                    // Observable (not remember-cached forever): refreshes after
                    // each role request so the UI reflects reality immediately.
                    var isDefaultAssistant by remember {
                        mutableStateOf(
                            roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true &&
                                roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
                        )
                    }

                    fun refreshAssistantRole() {
                        isDefaultAssistant =
                            roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT) == true &&
                                roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)
                    }

                    // MIUI/HyperOS often silently swallows the RoleManager
                    // request dialog for third-party assistants. Fallback:
                    // deep-link the system voice-input/assistant settings so
                    // the user can flip it manually.
                    fun openSystemAssistantSettings() {
                        try {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (e: Exception) {
                            try {
                                context.startActivity(
                                    Intent(android.provider.Settings.ACTION_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e2: Exception) {
                            }
                        }
                    }

                    val intentLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) {
                        // Re-check: AOSP grants here. On MIUI the dialog never
                        // showed, so the role is still unheld → send the user
                        // to system settings instead.
                        refreshAssistantRole()
                        if (!isDefaultAssistant) {
                            android.widget.Toast.makeText(
                                context,
                                Strings.t(Strings.ifNoDialogAppeared),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            openSystemAssistantSettings()
                        }
                    }

                    SettingsSection(title = Strings.t(Strings.themeSection)) {
                        SettingsItem(
                            icon = Icons.Default.Palette,
                            title = Strings.t(Strings.appThemeLabel),
                            subtitle = when (cfg.appTheme) {
                                "dynamic" -> Strings.t(Strings.themeDynamic)
                                "light" -> Strings.t(Strings.themeLight)
                                "dark" -> Strings.t(Strings.themeDark)
                                else -> Strings.t(Strings.themeSystem)
                            },
                            onClick = { showThemeDialog = true }
                        )
                        SettingsItem(
                            icon = Icons.Default.Circle,
                            title = Strings.t(Strings.accentColorLabel),
                            subtitle = accentName(cfg.accentColor),
                            onClick = { showAccentDialog = true }
                        )
                    }
                    SettingsSection(title = Strings.t(Strings.appLanguage)) {
                        SettingsItem(
                            icon = Icons.Default.Language,
                            title = Strings.t(Strings.appLanguage),
                            subtitle = when (cfg.appLanguage) {
                                "pt" -> "Português (Brasil)"
                                "en" -> "English"
                                else -> Strings.t(Strings.systemDefault)
                            },
                            onClick = { showLanguageDialog = true }
                        )
                    }
                    SettingsSection(title = Strings.t(Strings.assistantSection)) {
                        if (!isDefaultAssistant) {
                            SettingsItem(
                                icon = Icons.Default.AutoAwesome,
                                title = Strings.t(Strings.setDefaultAssistant),
                                subtitle = Strings.t(Strings.setDefaultAssistantSub),
                                onClick = {
                                    val rm = roleManager
                                    if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_ASSISTANT)) {
                                        intentLauncher.launch(
                                            rm.createRequestRoleIntent(
                                                android.app.role.RoleManager.ROLE_ASSISTANT
                                            )
                                        )
                                    } else {
                                        openSystemAssistantSettings()
                                    }
                                }
                            )
                        } else {
                            SettingsItem(
                                icon = Icons.Default.CheckCircle,
                                title = Strings.t(Strings.defaultAssistantOk),
                                subtitle = Strings.t(Strings.defaultAssistantOkSub),
                                onClick = { openSystemAssistantSettings() },
                                trailingArrow = false
                            )
                        }
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

    if (showVoiceGroqKeyDialog) {
        ProviderKeyDialog(
            providerName = "Groq",
            hint = "Free at console.groq.com/keys",
            hasExistingKey = (cfg.apiKeys["groq"] ?: "").isNotBlank(),
            onApiKeySaved = { key ->
                AppSettings.setApiKey("groq", key)
                showVoiceGroqKeyDialog = false
            },
            onDismiss = { showVoiceGroqKeyDialog = false }
        )
    }

    if (showTtsMethodDialog) {
        TtsMethodDialog(
            currentMethod = cfg.ttsMethod,
            onMethodSelected = { method ->
                AppSettings.update { it.copy(ttsMethod = method) }
                showTtsMethodDialog = false
            },
            onDismiss = { showTtsMethodDialog = false }
        )
    }

    if (showFishKeyDialog) {
        ProviderKeyDialog(
            providerName = "FishAudio",
            hint = "Create at fish.audio > API Keys (free dev tier available)",
            hasExistingKey = (cfg.apiKeys["fish"] ?: "").isNotBlank(),
            onApiKeySaved = { key ->
                AppSettings.setApiKey("fish", key)
                showFishKeyDialog = false
            },
            onDismiss = { showFishKeyDialog = false }
        )
    }

    if (showFishModelDialog) {
        FishModelDialog(
            currentModel = cfg.fishModel,
            onModelSelected = { model ->
                AppSettings.update { it.copy(fishModel = model) }
                showFishModelDialog = false
            },
            onDismiss = { showFishModelDialog = false }
        )
    }

    if (showFishVoiceDialog) {
        FishVoiceDialog(
            currentVoiceId = cfg.fishVoiceId,
            onVoiceSelected = { id, name ->
                AppSettings.update {
                    it.copy(fishVoiceId = id, fishVoiceName = name)
                }
                showFishVoiceDialog = false
            },
            onDismiss = { showFishVoiceDialog = false }
        )
    }

    if (showSearchEngineDialog) {
        SearchEngineDialog(
            currentEngine = cfg.searchEngine,
            onEngineSelected = { engineId ->
                AppSettings.update { it.copy(searchEngine = engineId) }
                showSearchEngineDialog = false
            },
            onDismiss = { showSearchEngineDialog = false }
        )
    }

    if (showSearchKeyDialog) {
        val engine = SearchEngines.byId(cfg.searchEngine)
        if (engine.keySlot != null) {
            val slot = engine.keySlot
            ProviderKeyDialog(
                providerName = engine.name,
                hint = engine.hint,
                hasExistingKey = (cfg.apiKeys[slot] ?: "").isNotBlank(),
                onApiKeySaved = { key ->
                    AppSettings.setApiKey(slot, key)
                    showSearchKeyDialog = false
                },
                onDismiss = { showSearchKeyDialog = false }
            )
        }
    }

    if (showLanguageDialog) {
        AppLanguageDialog(
            current = cfg.appLanguage,
            onSelected = { lang ->
                AppSettings.update { it.copy(appLanguage = lang) }
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showAccentDialog) {
        AccentColorDialog(
            current = cfg.accentColor,
            onSelected = { id ->
                AppSettings.update { it.copy(accentColor = id) }
                showAccentDialog = false
            },
            onDismiss = { showAccentDialog = false }
        )
    }

    if (showThemeDialog) {
        AppThemeDialog(
            current = cfg.appTheme,
            onSelected = { t ->
                AppSettings.update { it.copy(appTheme = t) }
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showTtsLangDialog) {
        TtsLanguageDialog(
            currentLanguage = cfg.ttsLanguage,
            onLanguageSelected = { lang ->
                AppSettings.update { it.copy(ttsLanguage = lang) }
                TtsManager.applyLanguage(lang)
                showTtsLangDialog = false
            },
            onDismiss = { showTtsLangDialog = false }
        )
    }

    if (showSearchCountDialog) {
        SearchCountDialog(
            current = cfg.searchResultCount,
            onSelected = { n ->
                AppSettings.update { it.copy(searchResultCount = n) }
                showSearchCountDialog = false
            },
            onDismiss = { showSearchCountDialog = false }
        )
    }

    if (showSearchRegionDialog) {
        SearchRegionDialog(
            current = cfg.searchRegion,
            onSelected = { r ->
                AppSettings.update { it.copy(searchRegion = r) }
                showSearchRegionDialog = false
            },
            onDismiss = { showSearchRegionDialog = false }
        )
    }

    if (showSearchFreshnessDialog) {
        SearchFreshnessDialog(
            current = cfg.searchFreshness,
            onSelected = { f ->
                AppSettings.update { it.copy(searchFreshness = f) }
                showSearchFreshnessDialog = false
            },
            onDismiss = { showSearchFreshnessDialog = false }
        )
    }

    if (showPersonaDialog) {
        PersonaDialog(
            activeId = cfg.activePersonaId,
            onDismiss = { showPersonaDialog = false }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsOverviewCard(
    personaName: String,
    hasPersonas: Boolean,
    onPersonaClick: () -> Unit,
    provider: String,
    model: String,
    keyStatus: String
) {
    ElevatedCard(
        onClick = onPersonaClick,
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
                        Icons.Default.Face,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        personaName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        if (hasPersonas) "Tap to switch persona" else keyStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                    )
                }
                if (hasPersonas) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
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
    onClick: () -> Unit,
    trailingArrow: Boolean = true
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
            if (trailingArrow) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                )
            }
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
    val cfg by AppSettings.config.collectAsState()
    var endpoints by remember(cfg.customEndpoints) { mutableStateOf(AppSettings.customEndpointList()) }
    var editing by remember { mutableStateOf<AppSettings.CustomEndpoint?>(null) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var addingNew by remember { mutableStateOf(false) }

    fun refresh() { endpoints = AppSettings.customEndpointList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.cloudProvider), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LlmProviders.ALL.filter { it.id != "custom" }.forEach { p ->
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
                                p.defaultBaseUrl.removePrefix("https://"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // Saved custom endpoints — selectable, editable, deletable
                Text(
                    Strings.t(Strings.customEndpointsTitle),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                endpoints.forEach { ep ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentProviderId == "custom" && ep.id == activeCustomEndpointId(),
                            onClick = {
                                AppSettings.selectCustomEndpoint(ep.id)
                                onProviderSelected("custom")
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ep.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                ep.baseUrl.removePrefix("https://").removePrefix("http://"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            editing = ep
                            newName = ep.name
                            newUrl = ep.baseUrl
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, Strings.t(Strings.rename), modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = {
                            AppSettings.deleteCustomEndpoint(ep.id)
                            refresh()
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Delete, Strings.t(Strings.delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                // Add / edit form
                if (editing != null || addingNew) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(Strings.t(Strings.nameLabel)) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(Strings.t(Strings.baseUrl)) },
                        placeholder = { Text("http://192.168.0.10:11434/v1") },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            if (newUrl.isNotBlank()) {
                                AppSettings.saveCustomEndpoint(editing?.id, newName, newUrl)
                                editing = null
                                addingNew = false
                                newName = ""; newUrl = ""
                                refresh()
                            }
                        }) { Text(Strings.t(Strings.save)) }
                        TextButton(onClick = { editing = null; addingNew = false; newName = ""; newUrl = "" }) {
                            Text(Strings.t(Strings.cancel))
                        }
                    }
                } else {
                    TextButton(onClick = { addingNew = true }) {
                        Text(Strings.t(Strings.addEndpoint))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/** Marks the saved endpoint whose URL matches the active baseUrl. */
private fun activeCustomEndpointId(): String {
    val cur = AppSettings.config.value.baseUrl
    return AppSettings.customEndpointList().firstOrNull { it.baseUrl == cur }?.id ?: ""
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
        title = { Text("$providerName ${Strings.t(Strings.apiKeyItem)}") },
        text = {
            Column {
                if (hasExistingKey) {
                    Text(
                        Strings.t(Strings.anApiKeyIsConfigured),
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
                    label = { Text(Strings.t(Strings.apiKeyItem)) },
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
            ) { Text(Strings.t(Strings.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
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
        title = { Text(Strings.t(Strings.baseUrl)) },
        text = {
            Column {
                Text(
                    Strings.t(Strings.openaiCompatibleHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.t(Strings.baseUrl)) },
                    placeholder = { Text("http://192.168.0.10:11434/v1") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onUrlSaved(url) }) { Text(Strings.t(Strings.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
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
                    title = { Text(Strings.t(Strings.selectModel)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, Strings.t(Strings.close))
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = { load(force = true) },
                            enabled = !loading
                        ) {
                            Icon(Icons.Default.Refresh, Strings.t(Strings.refreshCatalogBtn))
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
                    placeholder = { Text(Strings.t(Strings.filterModels)) },
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
                                Strings.t(Strings.loadingModels),
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
                                "${Strings.t(Strings.couldNotLoadModels)}\n$error",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { load(force = true) }) {
                                    Icon(Icons.Default.Refresh, null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(Strings.t(Strings.retry))
                                }
                                OutlinedButton(onClick = { showCustomInput = true }) {
                                    Text(Strings.t(Strings.typeModelIdButton))
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
                                    if (all.isEmpty()) Strings.t(Strings.noModelsAvailable)
                                    else "${Strings.t(Strings.noModelsMatch)} \"$query\"",
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
                                            Strings.t(Strings.freeModels),
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
                                            if (freeModels.isNotEmpty()) Strings.t(Strings.premiumModels)
                                            else Strings.t(Strings.availableModels),
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
                                        Strings.t(Strings.manual),
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
                                                Strings.t(Strings.customModel),
                                                style = MaterialTheme.typography.bodyLarge,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        supportingContent = {
                                            Text(
                                                if (currentModel.isNotBlank()) "${Strings.t(Strings.current)} $currentModel"
                                                else Strings.t(Strings.typeModelId)
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
                        "${models?.size ?: 0} ${Strings.t(Strings.modelsLiveFromServer)}",
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
                            label = { Text(Strings.t(Strings.free)) },
                            leadingIcon = {
                                Icon(Icons.Default.Savings, null, modifier = Modifier.size(14.dp))
                            }
                        )
                    }
                    if (model.supportsVision) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(Strings.t(Strings.vision)) },
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
        title = { Text(Strings.t(Strings.customModelTitle)) },
        text = {
            Column {
                Text(
                    Strings.t(Strings.enterModelId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.t(Strings.modelIdLabel)) },
                    placeholder = { Text(Strings.t(Strings.providerSlashModel)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { onModelSaved(modelId) }) { Text(Strings.t(Strings.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
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
                Text(Strings.t(Strings.systemPrompt), style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    Strings.t(Strings.promptBehaves),
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
                    label = { Text(Strings.t(Strings.systemPrompt)) },
                    placeholder = { Text(Strings.t(Strings.enterInstructions)) },
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
                    ) { Text(Strings.t(Strings.reset)) }
                    Button(
                        onClick = { onPromptSaved(prompt) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text(Strings.t(Strings.save)) }
                }
            }
        }
    }
}

/** Common Whisper languages; empty choice = system locale. */
@Composable
private fun VoiceLanguageDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val languages = listOf(
        "" to Strings.t(Strings.systemDefault),
        "pt" to Strings.t(Strings.langPortuguese),
        "en" to Strings.t(Strings.langEnglish),
        "es" to Strings.t(Strings.langSpanish),
        "fr" to Strings.t(Strings.langFrench),
        "de" to Strings.t(Strings.langGerman),
        "it" to Strings.t(Strings.langItalian),
        "ja" to Strings.t(Strings.langJapanese),
        "ko" to Strings.t(Strings.langKorean),
        "zh" to Strings.t(Strings.langChinese),
        "ru" to Strings.t(Strings.langRussian)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.recognitionLanguageTitle)) },
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
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
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
        Triple("system", Strings.t(Strings.systemRecognizer), Strings.t(Strings.systemRecognizerSub)),
        Triple("groq", Strings.t(Strings.groqWhisper), Strings.t(Strings.groqWhisperSub))
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.speechRecognition)) },
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
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
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
        Triple("whisper-large-v3", "whisper-large-v3", Strings.t(Strings.mostAccurate)),
        Triple("whisper-large-v3-turbo", "whisper-large-v3-turbo", Strings.t(Strings.fasterSlightlyLess))
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.whisperModelItem)) },
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
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/** Speech output engine: system TTS or FishAudio online. */
@Composable
private fun TtsMethodDialog(
    currentMethod: String,
    onMethodSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val methods = listOf(
        Triple("system", Strings.t(Strings.systemTts), Strings.t(Strings.systemTtsSub)),
        Triple("fish", Strings.t(Strings.fishAudio), Strings.t(Strings.fishAudioSub))
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.speechEngine)) },
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
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/** FishAudio synthesis model (header param). */
@Composable
private fun FishModelDialog(
    currentModel: String,
    onModelSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val models = listOf(
        Triple("s2.1-pro-free", "s2.1-pro-free", Strings.t(Strings.fishModelS21Free)),
        Triple("s2.1-pro", "s2.1-pro", Strings.t(Strings.fishModelS21)),
        Triple("s2-pro", "s2-pro", Strings.t(Strings.fishModelS2pro)),
        Triple("speech-1.5", "speech-1.5", Strings.t(Strings.fishModelLegacy))
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.fishModelItem)) },
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
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/**
 * FishAudio voice picker: FAVORITES section pinned on top (no fetch
 * needed), then the live catalog from GET /model with language filter
 * and name search, plus the manual reference_id fallback.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FishVoiceDialog(
    currentVoiceId: String,
    onVoiceSelected: (id: String, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var voices by remember { mutableStateOf<List<FishVoice>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var manualInput by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var langFilter by remember { mutableStateOf("") }
    var page by remember { mutableStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(AppSettings.fishFavoriteList()) }

    fun refreshFavorites() {
        favorites = AppSettings.fishFavoriteList()
    }

    // Load a page from the server. Server-side search via title= param;
    // append=true keeps previous items (Load more).
    suspend fun loadPage(search: String, lang: String, pageNumber: Int, append: Boolean) {
        loading = true
        error = null
        FishTts.listVoices(lang, search, pageNumber).fold(
            onSuccess = { pageData ->
                voices = if (append) voices + pageData.voices else pageData.voices
                hasMore = pageData.hasMore
                page = pageNumber
            },
            onFailure = { e ->
                error = e.message
            }
        )
        loading = false
    }

    // Reload (page 1) whenever language filter or the typed search changes
    LaunchedEffect(langFilter, query) {
        kotlinx.coroutines.delay(350)  // debounce typing
        loadPage(query.trim(), langFilter, 1, append = false)
    }

    if (manualInput) {
        AlertDialog(
            onDismissRequest = { manualInput = false },
            title = { Text(Strings.t(Strings.voiceIdLabel)) },
            text = {
                Column {
                    Text(
                        "fish.audio/m/...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    var id by remember { mutableStateOf(currentVoiceId) }
                    OutlinedTextField(
                        value = id,
                        onValueChange = { id = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = { onVoiceSelected(id.trim(), Strings.t(Strings.customVoice)) }) {
                        Text(Strings.t(Strings.save))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { manualInput = false }) {
                    Text(Strings.t(Strings.cancel))
                }
            }
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
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            Column {
                TopAppBar(
                    title = { Text(Strings.t(Strings.selectVoice)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, Strings.t(Strings.close))
                        }
                    }
                )

                // Language filter + search row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = langFilter.isBlank(),
                        onClick = { langFilter = "" },
                        label = { Text(Strings.t(Strings.all)) }
                    )
                    FilterChip(
                        selected = langFilter == "pt",
                        onClick = { langFilter = "pt" },
                        label = { Text("PT") }
                    )
                    FilterChip(
                        selected = langFilter == "en",
                        onClick = { langFilter = "en" },
                        label = { Text("EN") }
                    )
                    FilterChip(
                        selected = langFilter == "es",
                        onClick = { langFilter = "es" },
                        label = { Text("ES") }
                    )
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text(Strings.t(Strings.filterByName)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp)
                )

                LazyColumn(modifier = Modifier.weight(1f)) {
                    // Default voice option
                    item {
                        ListItem(
                            headlineContent = {
                                Text(Strings.t(Strings.platformDefaultVoice))
                            },
                            supportingContent = {
                                Text(Strings.t(Strings.referenceId))
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = currentVoiceId.isBlank(),
                                    onClick = { onVoiceSelected("", "") }
                                )
                            },
                            modifier = Modifier.clickable { onVoiceSelected("", "") }
                        )
                    }

                    // Favorites section — pinned, no fetch needed
                    if (favorites.isNotEmpty()) {
                        item {
                            Text(
                                Strings.t(Strings.favorites),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(
                                    start = 16.dp, end = 16.dp, top = 8.dp
                                )
                            )
                        }
                        items(favorites.map { FishVoice(it.first, it.second) }) { v ->
                            VoiceRow(
                                voice = v,
                                selected = currentVoiceId == v.id,
                                isFavorite = true,
                                onSelect = onVoiceSelected,
                                onToggleFavorite = {
                                    AppSettings.toggleFishFavorite(v.id, v.title)
                                    refreshFavorites()
                                }
                            )
                        }
                    }

                    // Live catalog section
                    item {
                        Text(
                            if (langFilter.isBlank()) "Catalog"
                            else "Language: $langFilter",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = 16.dp, end = 16.dp, top = 8.dp
                            )
                        )
                    }
                    when {
                        loading -> item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        error != null -> item {
                            Text(
                                "${Strings.t(Strings.couldNotListVoices)} $error",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        else -> {
                            items(voices) { v ->
                                VoiceRow(
                                    voice = v,
                                    selected = currentVoiceId == v.id,
                                    isFavorite = AppSettings.isFishFavorite(v.id),
                                    onSelect = onVoiceSelected,
                                    onToggleFavorite = {
                                        AppSettings.toggleFishFavorite(v.id, v.title)
                                        refreshFavorites()
                                    }
                                )
                            }
                            if (hasMore && !loading) {
                                item {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                loadPage(query.trim(), langFilter, page + 1, append = true)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        shape = RoundedCornerShape(12.dp)
                                    ) { Text(Strings.t(Strings.loadMore)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: FishVoice,
    selected: Boolean,
    isFavorite: Boolean,
    onSelect: (id: String, name: String) -> Unit,
    onToggleFavorite: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(voice.title, style = MaterialTheme.typography.bodyLarge)
        },
        leadingContent = {
            RadioButton(selected = selected, onClick = { onSelect(voice.id, voice.title) })
        },
        trailingContent = {
            IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (isFavorite) Strings.t(Strings.unfavorite) else Strings.t(Strings.favorite),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable { onSelect(voice.id, voice.title) }
    )
}

/** Web search engine picker with per-engine privacy notes. */
@Composable
private fun SearchEngineDialog(
    currentEngine: String,
    onEngineSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.searchEngine)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SearchEngines.ALL.forEach { engine ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEngineSelected(engine.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentEngine == engine.id,
                            onClick = { onEngineSelected(engine.id) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(engine.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                engine.privacy,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (engine.keySlot == null) Strings.t(Strings.noApiKeyNeeded)
                                else SearchEngines.isConfigured(engine.id).let {
                                    if (it) Strings.t(Strings.keyConfigured) else engine.hint
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/**
 * Persona manager: switch the active persona, save the current config as a
 * new persona, rename or delete saved ones. The current settings edit the
 * ACTIVE persona in place — configure first, then save/switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonaDialog(
    activeId: String,
    onDismiss: () -> Unit
) {
    var personas by remember { mutableStateOf(AppSettings.personas()) }
    var newName by remember { mutableStateOf("") }
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.personas)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    Strings.t(Strings.personaSubtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                personas.forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = p.id == activeId,
                            onClick = {
                                AppSettings.switchPersona(p.id)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${LlmProviders.byId(p.providerId).displayName} • " +
                                    p.model.ifBlank { Strings.t(Strings.noModel) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (p.id == activeId) {
                            IconButton(onClick = {
                                renaming = p.id
                                renameText = p.name
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Edit, "Rename",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        IconButton(onClick = { deleting = p.id }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                Icons.Default.Delete, Strings.t(Strings.delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                if (renaming != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(Strings.t(Strings.newName)) },
                        shape = RoundedCornerShape(14.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            AppSettings.updatePersona(renaming!!, renameText)
                            personas = AppSettings.personas()
                            renaming = null
                        }) { Text(Strings.t(Strings.saveName)) }
                        TextButton(onClick = { renaming = null }) { Text(Strings.t(Strings.cancel)) }
                    }
                }

                if (deleting != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        Strings.t(Strings.deletePersonaQ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                AppSettings.deletePersona(deleting!!)
                                personas = AppSettings.personas()
                                deleting = null
                            },
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text(Strings.t(Strings.delete)) }
                        TextButton(onClick = { deleting = null }) { Text(Strings.t(Strings.cancel)) }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    Strings.t(Strings.saveCurrentAsPersona),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(Strings.t(Strings.namePlaceholder)) },
                    shape = RoundedCornerShape(14.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (newName.isNotBlank()) {
                            AppSettings.createPersona(newName)
                            newName = ""
                            onDismiss()
                        }
                    },
                    enabled = newName.isNotBlank()
                ) { Text(Strings.t(Strings.savePersona)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.close)) }
        }
    )
}

/** App UI language picker: system default, English, Português (Brasil). */
@Composable
private fun AppLanguageDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val langs = listOf(
        Triple("", Strings.t(Strings.systemDefault), Strings.t(Strings.followsDevice)),
        Triple("en", "English", ""),
        Triple("pt", "Português (Brasil)", "")
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.appLanguage)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                langs.forEach { (code, name, description) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(code) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = current == code,
                            onClick = { onSelected(code) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            if (description.isNotBlank()) {
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/** Human names for the DDG/Brave region codes. */
private fun regionName(code: String): String = when (code) {
    "br" -> "Brasil"
    "us" -> "United States"
    "uk" -> "United Kingdom"
    "pt" -> "Portugal"
    "es" -> "España"
    "fr" -> "France"
    "de" -> "Deutschland"
    "it" -> "Italia"
    "jp" -> "Japan"
    "mx" -> "México"
    "ar" -> "Argentina"
    else -> code
}

/** Result count picker: 1..10 */
@Composable
private fun SearchCountDialog(
    current: Int,
    onSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.resultCount)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                (1..10).forEach { n ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(n) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == n, onClick = { onSelected(n) })
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(n.toString(), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/** Region picker: system default + common countries. */
@Composable
private fun SearchRegionDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.searchRegionLabel)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    "" to Strings.t(Strings.systemDefault),
                    "br" to "Brasil",
                    "us" to "United States",
                    "uk" to "United Kingdom",
                    "pt" to "Portugal",
                    "es" to "España",
                    "fr" to "France",
                    "de" to "Deutschland",
                    "it" to "Italia",
                    "jp" to "Japan",
                    "mx" to "México",
                    "ar" to "Argentina"
                ).forEach { (code, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(code) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == code, onClick = { onSelected(code) })
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/** Freshness picker. */
@Composable
private fun SearchFreshnessDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val options = listOf(
        "" to Strings.t(Strings.freshAny),
        "day" to Strings.t(Strings.freshDay),
        "week" to Strings.t(Strings.freshWeek),
        "month" to Strings.t(Strings.freshMonth)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.searchFreshnessLabel)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (code, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(code) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = current == code, onClick = { onSelected(code) })
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/** TTS synthesis language — INDEPENDENT of the recognition language. */
@Composable
private fun TtsLanguageDialog(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val languages = listOf(
        "" to Strings.t(Strings.systemDefault),
        "pt" to Strings.t(Strings.langPortuguese),
        "en" to Strings.t(Strings.langEnglish),
        "es" to Strings.t(Strings.langSpanish),
        "fr" to Strings.t(Strings.langFrench),
        "de" to Strings.t(Strings.langGerman),
        "it" to Strings.t(Strings.langItalian),
        "ja" to Strings.t(Strings.langJapanese),
        "ko" to Strings.t(Strings.langKorean),
        "zh" to Strings.t(Strings.langChinese),
        "ru" to Strings.t(Strings.langRussian)
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.synthesisLanguage)) },
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
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/** App theme picker: dynamic (Material You) / light / dark / system. */
@Composable
private fun AppThemeDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.appThemeLabel)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    "" to Strings.t(Strings.themeSystem),
                    "dynamic" to Strings.t(Strings.themeDynamic),
                    "light" to Strings.t(Strings.themeLight),
                    "dark" to Strings.t(Strings.themeDark)
                ).forEach { (code, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(code) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = current == code,
                            onClick = { onSelected(code) }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}

/** Human name of an accent id. */
private fun accentName(id: String): String = when (id) {
    "teal" -> "Teal"
    "blue" -> "Blue"
    "purple" -> "Purple"
    "pink" -> "Pink"
    "red" -> "Red"
    "orange" -> "Orange"
    "green" -> "Green"
    else -> Strings.t(Strings.accentDefault)
}

/** Accent color picker: swatches for each preset. */
@Composable
private fun AccentColorDialog(
    current: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.t(Strings.accentColorLabel)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // default = teal identity of the app
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelected("") }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = current.isEmpty(), onClick = { onSelected("") })
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(Strings.t(Strings.accentDefault), style = MaterialTheme.typography.bodyLarge)
                }
                // presets
                com.taboia872.androidassist.ui.theme.ACCENT_PRESETS
                    .filter { it.first.isNotEmpty() }
                    .forEach { (id, color) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelected(id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = current == id,
                                onClick = { onSelected(id) }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(color ?: MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(accentName(id), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(Strings.t(Strings.cancel)) }
        }
    )
}
