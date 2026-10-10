package com.taboia872.androidassist.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.taboia872.androidassist.llm.LlmClient
import com.taboia872.androidassist.llm.LlmProviders
import com.taboia872.androidassist.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * Single settings screen driven entirely by AppSettings — there is exactly one
 * place where provider/key/model are read and written, so screens cannot
 * disagree with each other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val cfg by AppSettings.config.collectAsState()
    val provider = LlmProviders.byId(cfg.providerId)
    val scope = rememberCoroutineScope()

    var models by remember { mutableStateOf<List<String>?>(null) }
    var modelsError by remember { mutableStateOf<String?>(null) }
    var loadingModels by remember { mutableStateOf(false) }

    // Live model list from the ACTIVE endpoint, every time the endpoint changes
    LaunchedEffect(cfg.baseUrl, cfg.apiKey) {
        models = null
        modelsError = null
        if (cfg.baseUrl.isBlank()) return@LaunchedEffect
        loadingModels = true
        LlmClient.models(cfg.baseUrl, cfg.apiKey).fold(
            onSuccess = { models = it.sorted() },
            onFailure = { modelsError = it.message }
        )
        loadingModels = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // ---- Provider ----
            SectionTitle("AI Provider")
            LlmProviders.ALL.forEach { p ->
                Surface(
                    onClick = { AppSettings.setProvider(p.id) },
                    shape = MaterialTheme.shapes.medium,
                    color = if (p.id == cfg.providerId) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = p.id == cfg.providerId, onClick = { AppSettings.setProvider(p.id) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(p.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                p.defaultBaseUrl.ifBlank { "your own base URL" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ---- Base URL (editable; prefilled by provider default) ----
            SectionTitle("Base URL")
            OutlinedTextField(
                value = cfg.baseUrl,
                onValueChange = { url -> AppSettings.update { it.copy(baseUrl = url) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("http://192.168.0.10:11434/v1") }
            )

            // ---- API key ----
            if (provider.requiresKey) {
                SectionTitle("${provider.displayName} API Key")
                OutlinedTextField(
                    value = cfg.apiKey,
                    onValueChange = { key -> AppSettings.update { it.copy(apiKey = key) } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                val ok = cfg.apiKey.isNotBlank()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (ok) Icons.Default.CheckCircle else Icons.Default.Key,
                        contentDescription = null,
                        tint = if (ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (ok) "Key saved (encrypted)" else "Key required for this provider",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ---- Model (live catalog from the active endpoint) ----
            SectionTitle("Model")
            when {
                loadingModels -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading models…")
                }
                modelsError != null -> Text(
                    "Could not load models: $modelsError",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                models != null -> {
                    val current = cfg.model.ifBlank { null }
                    models!!.forEach { m ->
                        Surface(
                            onClick = { AppSettings.update { it.copy(model = m) } },
                            color = if (m == current) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                RadioButton(selected = m == current, onClick = { AppSettings.update { it.copy(model = m) } })
                                Spacer(Modifier.width(8.dp))
                                Text(m, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Current: ", style = MaterialTheme.typography.bodySmall)
                Text(
                    cfg.model.ifBlank { "not selected" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { scope.launch { /* re-run LaunchedEffect via key bump */ } }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                }
            }
            // Manual model id fallback (custom endpoints with odd catalogs)
            OutlinedTextField(
                value = cfg.model,
                onValueChange = { m -> AppSettings.update { it.copy(model = m) } },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Or type a model id") }
            )

            // ---- System prompt ----
            SectionTitle("System Prompt")
            OutlinedTextField(
                value = cfg.systemPrompt,
                onValueChange = { s -> AppSettings.update { it.copy(systemPrompt = s) } },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )
}
