package com.taboia872.androidassist.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.taboia872.androidassist.llm.LlmProviders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The single source of truth for app configuration. Every screen reads the
 * same StateFlow; every write goes through update(). No screen ever touches
 * SharedPreferences or checks a provider by itself — that is how state
 * desync bugs are structurally impossible here.
 *
 * API keys are stored PER PROVIDER (like the base app's key slots): switching
 * from OpenRouter to Groq keeps both keys, each provider remembers its own.
 */
data class AppConfig(
    val providerId: String = LlmProviders.OPENROUTER.id,
    val baseUrl: String = LlmProviders.OPENROUTER.defaultBaseUrl,
    val apiKeys: Map<String, String> = emptyMap(),
    val model: String = "",
    val systemPrompt: String = "You are a helpful, concise assistant. Always reply in the user's language.",
    // Voice (Groq Whisper, online phase)
    val autoStartVoice: Boolean = true,
    val autoSendVoice: Boolean = true,
    /** BCP-47 tag; empty = system locale */
    val voiceLanguage: String = "",
    /** Voice input engine: "system" (SpeechRecognizer) or "groq" (Whisper) */
    val voiceMethod: String = "groq",
    /** Groq Whisper model used for transcription */
    val whisperModel: String = "whisper-large-v3",
    /** Read finished answers aloud with the system TTS engine */
    val ttsEnabled: Boolean = false,
    /** Search the web (DuckDuckGo, keyless) before sending to the model */
    val webSearchEnabled: Boolean = false
) {
    /** Key for the ACTIVE provider — the one place the active key is read. */
    val apiKey: String get() = apiKeys[providerId] ?: ""
}

object AppSettings {

    private const val FILE = "androidassist_secure_prefs"
    private const val K_PROVIDER = "provider_id"
    private const val K_BASE_URL = "base_url"
    private const val K_API_KEYS = "api_keys_json"
    private const val K_MODEL = "model"
    private const val K_PROMPT = "system_prompt"
    private const val K_AUTO_START_VOICE = "auto_start_voice"
    private const val K_AUTO_SEND_VOICE = "auto_send_voice"
    private const val K_VOICE_LANG = "voice_language"
    private const val K_VOICE_METHOD = "voice_method"
    private const val K_WHISPER_MODEL = "whisper_model"
    private const val K_TTS_ENABLED = "tts_enabled"
    private const val K_WEB_SEARCH = "web_search_enabled"
    private const val K_LEGACY_KEY = "api_key" // pre per-provider layout

    private var prefs: SharedPreferences? = null

    private val _config = MutableStateFlow(AppConfig())
    val config: StateFlow<AppConfig> = _config

    /** Call once from Application/Activity onCreate. Thread-safe. */
    fun init(context: Context) {
        synchronized(this) {
            if (prefs == null) {
                val masterKey = MasterKey.Builder(context.applicationContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                prefs = EncryptedSharedPreferences.create(
                    context.applicationContext, FILE, masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                _config.value = load()
            }
        }
    }

    private fun p(): SharedPreferences =
        prefs ?: throw IllegalStateException("AppSettings.init(context) was not called")

    private fun load(): AppConfig {
        val p = p()
        val providerId = p.getString(K_PROVIDER, null) ?: LlmProviders.OPENROUTER.id

        val keysJson = p.getString(K_API_KEYS, null)
        val keys: MutableMap<String, String> = if (keysJson != null) {
            val obj = JSONObject(keysJson)
            val m = mutableMapOf<String, String>()
            for (k in obj.keys()) m[k] = obj.getString(k)
            m
        } else mutableMapOf()

        // One-time migration from the old single-key field
        val legacy = p.getString(K_LEGACY_KEY, null)
        if (!legacy.isNullOrBlank() && !keys.containsKey(providerId)) {
            keys[providerId] = legacy
            p.edit().putString(K_API_KEYS, JSONObject(keys).toString())
                .remove(K_LEGACY_KEY).apply()
        }

        return AppConfig(
            providerId = providerId,
            baseUrl = p.getString(K_BASE_URL, null)
                ?: LlmProviders.byId(providerId).defaultBaseUrl,
            apiKeys = keys,
            model = p.getString(K_MODEL, "") ?: "",
            systemPrompt = p.getString(K_PROMPT, null) ?: AppConfig().systemPrompt,
            autoStartVoice = p.getBoolean(K_AUTO_START_VOICE, true),
            autoSendVoice = p.getBoolean(K_AUTO_SEND_VOICE, true),
            voiceLanguage = p.getString(K_VOICE_LANG, "") ?: "",
            voiceMethod = p.getString(K_VOICE_METHOD, "groq") ?: "groq",
            whisperModel = p.getString(K_WHISPER_MODEL, "whisper-large-v3")
                ?: "whisper-large-v3",
            ttsEnabled = p.getBoolean(K_TTS_ENABLED, false),
            webSearchEnabled = p.getBoolean(K_WEB_SEARCH, false)
        )
    }

    /** Atomically change config: persist + emit to all collectors. */
    fun update(transform: (AppConfig) -> AppConfig) {
        val next = transform(_config.value)
        p().edit()
            .putString(K_PROVIDER, next.providerId)
            .putString(K_BASE_URL, next.baseUrl)
            .putString(K_API_KEYS, JSONObject(next.apiKeys).toString())
            .putString(K_MODEL, next.model)
            .putString(K_PROMPT, next.systemPrompt)
            .putBoolean(K_AUTO_START_VOICE, next.autoStartVoice)
            .putBoolean(K_AUTO_SEND_VOICE, next.autoSendVoice)
            .putString(K_VOICE_LANG, next.voiceLanguage)
            .putString(K_VOICE_METHOD, next.voiceMethod)
            .putString(K_WHISPER_MODEL, next.whisperModel)
            .putBoolean(K_TTS_ENABLED, next.ttsEnabled)
            .putBoolean(K_WEB_SEARCH, next.webSearchEnabled)
            .apply()
        _config.value = next
    }

    /**
     * Switch provider: resets the base URL to the provider default and clears
     * the model pick (catalogs differ per provider). Keys are per-provider,
     * so they are untouched.
     */
    fun setProvider(providerId: String) = update {
        it.copy(
            providerId = providerId,
            baseUrl = LlmProviders.byId(providerId).defaultBaseUrl,
            model = ""
        )
    }

    /** Save/replace the key of one provider without touching the others. */
    fun setApiKey(providerId: String, key: String) = update {
        it.copy(apiKeys = it.apiKeys + (providerId to key.trim()))
    }
}
