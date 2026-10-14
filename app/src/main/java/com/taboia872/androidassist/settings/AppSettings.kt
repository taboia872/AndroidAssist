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
    /** Voice input engine: "system" (SpeechRecognizer) or "groq" (Whisper).
     *  Fresh installs default to SYSTEM (user request). Existing installs
     *  keep their saved value; migration below rewrites the old default. */
    val voiceMethod: String = "system",
    /** Groq Whisper model used for transcription */
    val whisperModel: String = "whisper-large-v3",
    /** Read finished answers aloud with the system TTS engine */
    val ttsEnabled: Boolean = false,
    /** Speech synthesis engine: "system" (offline) or "fish" (FishAudio online) */
    val ttsMethod: String = "system",
    /** FishAudio TTS model (goes in the request HEADER, not body) */
    val fishModel: String = "s2.1-pro-free",
    /** FishAudio voice model id (reference_id); blank = platform default voice */
    val fishVoiceId: String = "",
    /** Display name of the selected FishAudio voice */
    val fishVoiceName: String = "",
    /** Search the web (DuckDuckGo, keyless) before sending to the model */
    val webSearchEnabled: Boolean = false,
    /** Active search engine: duckduckgo | brave | exa | langsearch */
    val searchEngine: String = "duckduckgo",
    /** Active persona id (profile); blank = default untitled profile */
    val activePersonaId: String = "",
    /** App UI language: "" (system), "en" or "pt" (pt-BR) */
    val appLanguage: String = "",
    /** Favorite FishAudio voices: JSON array of {id,title} */
    val fishFavorites: String = "[]"
) {
    /** Key for the ACTIVE provider — the one place the active key is read. */
    val apiKey: String get() = apiKeys[providerId] ?: ""
}

/**
 * A named profile ("persona"): everything that defines a personality in one
 * bundle. Switching personas swaps the profile slice into AppConfig; API
 * keys stay global (a Groq key is a Groq key for every persona).
 */
data class Persona(
    val id: String,
    val name: String,
    val systemPrompt: String,
    val providerId: String,
    val baseUrl: String,
    val model: String,
    val webSearchEnabled: Boolean,
    val searchEngine: String,
    val ttsEnabled: Boolean,
    val ttsMethod: String,
    val fishModel: String,
    val fishVoiceId: String,
    val fishVoiceName: String,
    val voiceMethod: String,
    val whisperModel: String,
    val voiceLanguage: String,
    val autoStartVoice: Boolean,
    val autoSendVoice: Boolean
) {
    companion object {
        fun fromConfig(id: String, name: String, c: AppConfig) = Persona(
            id = id,
            name = name,
            systemPrompt = c.systemPrompt,
            providerId = c.providerId,
            baseUrl = c.baseUrl,
            model = c.model,
            webSearchEnabled = c.webSearchEnabled,
            searchEngine = c.searchEngine,
            ttsEnabled = c.ttsEnabled,
            ttsMethod = c.ttsMethod,
            fishModel = c.fishModel,
            fishVoiceId = c.fishVoiceId,
            fishVoiceName = c.fishVoiceName,
            voiceMethod = c.voiceMethod,
            whisperModel = c.whisperModel,
            voiceLanguage = c.voiceLanguage,
            autoStartVoice = c.autoStartVoice,
            autoSendVoice = c.autoSendVoice
        )

        fun fromJson(o: JSONObject): Persona? {
            val id = o.optString("id")
            if (id.isBlank()) return null
            return Persona(
                id = id,
                name = o.optString("name").ifBlank { "Persona" },
                systemPrompt = o.optString("systemPrompt", ""),
                providerId = o.optString("providerId", LlmProviders.OPENROUTER.id),
                baseUrl = o.optString("baseUrl", ""),
                model = o.optString("model", ""),
                webSearchEnabled = o.optBoolean("webSearchEnabled", false),
                searchEngine = o.optString("searchEngine", "duckduckgo"),
                ttsEnabled = o.optBoolean("ttsEnabled", false),
                ttsMethod = o.optString("ttsMethod", "system"),
                fishModel = o.optString("fishModel", "s2.1-pro-free"),
                fishVoiceId = o.optString("fishVoiceId", ""),
                fishVoiceName = o.optString("fishVoiceName", ""),
                voiceMethod = o.optString("voiceMethod", "groq"),
                whisperModel = o.optString("whisperModel", "whisper-large-v3"),
                voiceLanguage = o.optString("voiceLanguage", ""),
                autoStartVoice = o.optBoolean("autoStartVoice", true),
                autoSendVoice = o.optBoolean("autoSendVoice", true)
            )
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("systemPrompt", systemPrompt)
        put("providerId", providerId); put("baseUrl", baseUrl); put("model", model)
        put("webSearchEnabled", webSearchEnabled); put("searchEngine", searchEngine)
        put("ttsEnabled", ttsEnabled); put("ttsMethod", ttsMethod)
        put("fishModel", fishModel); put("fishVoiceId", fishVoiceId)
        put("fishVoiceName", fishVoiceName)
        put("voiceMethod", voiceMethod); put("whisperModel", whisperModel)
        put("voiceLanguage", voiceLanguage)
        put("autoStartVoice", autoStartVoice); put("autoSendVoice", autoSendVoice)
    }
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
    private const val K_TTS_METHOD = "tts_method"
    private const val K_FISH_MODEL = "fish_model"
    private const val K_FISH_VOICE_ID = "fish_voice_id"
    private const val K_FISH_VOICE_NAME = "fish_voice_name"
    private const val K_WEB_SEARCH = "web_search_enabled"
    private const val K_SEARCH_ENGINE = "search_engine"
    private const val K_ACTIVE_PERSONA = "active_persona_id"
    private const val K_APP_LANGUAGE = "app_language"
    private const val K_FISH_FAVORITES = "fish_favorites"
    private const val K_PERSONAS = "personas_json"
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
            voiceMethod = p.getString(K_VOICE_METHOD, "system") ?: "system",
            whisperModel = p.getString(K_WHISPER_MODEL, "whisper-large-v3")
                ?: "whisper-large-v3",
            ttsEnabled = p.getBoolean(K_TTS_ENABLED, false),
            ttsMethod = p.getString(K_TTS_METHOD, "system") ?: "system",
            fishModel = p.getString(K_FISH_MODEL, "s2.1-pro-free") ?: "s2.1-pro-free",
            fishVoiceId = p.getString(K_FISH_VOICE_ID, "") ?: "",
            fishVoiceName = p.getString(K_FISH_VOICE_NAME, "") ?: "",
            webSearchEnabled = p.getBoolean(K_WEB_SEARCH, false),
            searchEngine = p.getString(K_SEARCH_ENGINE, "duckduckgo") ?: "duckduckgo",
            activePersonaId = p.getString(K_ACTIVE_PERSONA, "") ?: "",
            appLanguage = p.getString(K_APP_LANGUAGE, "") ?: "",
            fishFavorites = p.getString(K_FISH_FAVORITES, "[]") ?: "[]"
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
            .putString(K_TTS_METHOD, next.ttsMethod)
            .putString(K_FISH_MODEL, next.fishModel)
            .putString(K_FISH_VOICE_ID, next.fishVoiceId)
            .putString(K_FISH_VOICE_NAME, next.fishVoiceName)
            .putBoolean(K_WEB_SEARCH, next.webSearchEnabled)
            .putString(K_SEARCH_ENGINE, next.searchEngine)
            .putString(K_ACTIVE_PERSONA, next.activePersonaId)
            .putString(K_APP_LANGUAGE, next.appLanguage)
            .putString(K_FISH_FAVORITES, next.fishFavorites)
            .apply()
        _config.value = next
    }

    // ------------------------------------------------------------- personas

    private fun loadPersonas(): MutableList<Persona> {
        val json = p().getString(K_PERSONAS, null) ?: return mutableListOf()
        return try {
            val arr = org.json.JSONArray(json)
            val out = mutableListOf<Persona>()
            for (i in 0 until arr.length()) {
                Persona.fromJson(arr.optJSONObject(i) ?: continue)?.let { out.add(it) }
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun savePersonas(list: List<Persona>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(it.toJson()) }
        p().edit().putString(K_PERSONAS, arr.toString()).apply()
    }

    /** All saved personas. */
    fun personas(): List<Persona> = loadPersonas()

    /**
     * Snapshot the CURRENT config as a new persona. Returns the created id.
     * The new persona becomes active immediately.
     */
    fun createPersona(name: String): String {
        val id = java.util.UUID.randomUUID().toString()
        val persona = Persona.fromConfig(id, name.trim().ifBlank { "Persona" }, _config.value)
        val list = loadPersonas()
        list.add(persona)
        savePersonas(list)
        update { it.copy(activePersonaId = id) }
        return id
    }

    /** Overwrite the given persona with the current config (edit-in-place). */
    fun updatePersona(id: String, newName: String) {
        val list = loadPersonas()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = Persona.fromConfig(
                id, newName.trim().ifBlank { list[idx].name }, _config.value
            )
            savePersonas(list)
        }
    }

    /** Apply a persona's slice to the live config and make it active. */
    fun switchPersona(id: String) {
        val persona = loadPersonas().firstOrNull { it.id == id } ?: return
        update { c ->
            c.copy(
                systemPrompt = persona.systemPrompt,
                providerId = persona.providerId,
                baseUrl = persona.baseUrl,
                model = persona.model,
                webSearchEnabled = persona.webSearchEnabled,
                searchEngine = persona.searchEngine,
                ttsEnabled = persona.ttsEnabled,
                ttsMethod = persona.ttsMethod,
                fishModel = persona.fishModel,
                fishVoiceId = persona.fishVoiceId,
                fishVoiceName = persona.fishVoiceName,
                voiceMethod = persona.voiceMethod,
                whisperModel = persona.whisperModel,
                voiceLanguage = persona.voiceLanguage,
                autoStartVoice = persona.autoStartVoice,
                autoSendVoice = persona.autoSendVoice,
                activePersonaId = id
            )
        }
    }

    /** Delete a persona; clearing the active one falls back to live config. */
    fun deletePersona(id: String) {
        val list = loadPersonas()
        val list2 = list.filter { it.id != id }
        savePersonas(list2)
        if (_config.value.activePersonaId == id) {
            update { it.copy(activePersonaId = "") }
        }
    }

    // ------------------------------------------------------- fish favorites

    /** Favorite FishAudio voices as (id, title) pairs. */
    fun fishFavoriteList(): List<Pair<String, String>> {
        return try {
            val arr = org.json.JSONArray(_config.value.fishFavorites)
            val out = mutableListOf<Pair<String, String>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotBlank()) out.add(id to o.optString("title").ifBlank { id })
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun toggleFishFavorite(id: String, title: String) {
        val cur = fishFavoriteList().toMutableList()
        val existing = cur.indexOfFirst { it.first == id }
        if (existing >= 0) cur.removeAt(existing) else cur.add(id to title)
        val arr = org.json.JSONArray()
        cur.forEach { arr.put(org.json.JSONObject().put("id", it.first).put("title", it.second)) }
        update { it.copy(fishFavorites = arr.toString()) }
    }

    fun isFishFavorite(id: String): Boolean =
        fishFavoriteList().any { it.first == id }

    /** Display name of the active persona (or default). */
    fun activePersonaName(): String {
        val id = _config.value.activePersonaId
        if (id.isBlank()) return "Default"
        return loadPersonas().firstOrNull { it.id == id }?.name ?: "Default"
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
