package com.taboia872.androidassist.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.taboia872.androidassist.llm.LlmProviders
import com.taboia872.androidassist.tools.ToolAbility
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
    /**
     * ALL API keys per slot. A slot is a provider id ("openrouter", "groq",
     * "fish", "brave"...). The value is the LIST of keys saved for that
     * slot — the rotation engine (llm/KeyRotation.kt) picks which one is
     * used, and retries the next one when the server rejects it.
     */
    val apiKeys: Map<String, List<String>> = emptyMap(),
    /**
     * GLOBAL rotation strategy for every slot:
     * "random" | "round_robin" | "last_used" | "last_unused".
     */
    val keyRotationStrategy: String = "round_robin",
    /** Rotation bookkeeping per slot (cursor / last used / used set). */
    val keyRotationState: String = "{}",
    val model: String = "",
    val systemPrompt: String = "You are a helpful, concise assistant. Always reply in the user's language.",
    // Voice (Groq Whisper, online phase)
    val autoStartVoice: Boolean = true,
    val autoSendVoice: Boolean = true,
    /** BCP-47 tag; empty = system locale — RECOGNITION (STT) language */
    val voiceLanguage: String = "",
    /** BCP-47 tag; empty = system locale — SYNTHESIS (TTS) language, independent */
    val ttsLanguage: String = "",
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
    /** Active search engine: parallel | duckduckgo | brave | exa | langsearch | jina */
    val searchEngine: String = "duckduckgo",
    /** Number of search results per query (all engines) */
    val searchResultCount: Int = 5,
    /** Region bias for search results: "" (none) | "br" | "us" | ... */
    val searchRegion: String = "",
    /** Freshness filter: "" (any) | "day" | "week" | "month" */
    val searchFreshness: String = "",
    /** Active persona id (profile); blank = default untitled profile */
    val activePersonaId: String = "",
    /** App UI language: "" (system), "en" or "pt" (pt-BR) */
    val appLanguage: String = "",
    /** Theme: "dynamic" (Material You), "light", "dark"; "" = follow system (static teal) */
    val appTheme: String = "",
    /** Device tools (alarm, timer, calendar, open app, clipboard, share) */
    val deviceToolsEnabled: Boolean = true,
    /**
     * Per-ability switches for the Android abilities (JSON map:
     * ability id -> enabled). A MISSING id means ENABLED, so installs that
     * never touched the switches keep every ability available.
     * [deviceToolsEnabled] is the master switch for the whole group.
     */
    val deviceToolToggles: String = "{}",
    /**
     * Memory tools (save_memory / search_memories / forget_memory) — INDEPENDENT
     * from the device tools: turning the Android abilities off must NOT kill
     * the user's memories (same class of bug as the web-search gate).
     */
    val memoryToolsEnabled: Boolean = true,
    /**
     * How many TOOL CALLS the model may make in ONE turn (rounds of
     * function calling). 1 = a single tool per turn; higher lets the
     * model chain (alarm, then open app, then calendar). The web search
     * always runs at most once per turn regardless of this value.
     */
    val maxToolCalls: Int = 4,
    /**
     * How many conversation TURNS (user+assistant exchanges) are kept in
     * the context sent to the model. Older turns are dropped from the
     * prompt (the chat file keeps everything). 0 = send everything.
     * Lower values = shorter prompt = faster and cheaper on local models.
     */
    val maxContextTurns: Int = 0,
    /**
     * How long a SILENCE must last to end the voice capture (ms).
     * Lower = the recording stops sooner (useful in noisy rooms where
     * the pause is hard to detect); higher = waits longer for the user
     * to finish the sentence. Clamped to 500..5000 by the callers.
     */
    val voiceSilenceMs: Int = 1500,
    /**
     * Calendar save mode: "direct" writes straight into the selected system
     * calendar (hands-free, needs WRITE_CALENDAR); "confirm" opens the
     * calendar app pre-filled (no permission). DIRECT only shows in the
     * user's Fossify Calendar if that calendar is enabled in its CalDAV
     * settings (Fossify mirrors system -> its own DB).
     */
    val calendarMode: String = "direct",
    /** Target calendar id (CalendarContract) for direct saves; 0 = none picked */
    val defaultCalendarId: Long = 0,
    /**
     * open_app whitelist: JSON array of package names the model may open.
     * EMPTY = allow any installed app (default, current behavior).
     */
    val allowedApps: String = "[]",
    /**
     * No-think mode: suppress reasoning for faster answers. Sends
     * chat_template_kwargs {enable_thinking:false} (llama-server) plus a
     * directive in the system prompt. For small/local models.
     */
    val noThink: Boolean = false,
    /** Accent color id: "" (default teal), teal|blue|purple|pink|red|orange|green */
    val accentColor: String = "",
    /** Favorite FishAudio voices: JSON array of {id,title} */
    val fishFavorites: String = "[]",
    /** Saved custom endpoints: JSON array of {id,name,baseUrl} */
    val customEndpoints: String = "[]"
) {
    /**
     * The active provider's keys (ALL of them). Empty = no key saved.
     * Use AppSettings.keysFor(slot) to get the list of one slot.
     */
    val apiKeysList: List<String> get() = apiKeys[providerId] ?: emptyList()

    /**
     * The key that will be used for the NEXT request of the active
     * provider — the first candidate of the rotation strategy. It is only
     * a preview (the real pick happens in KeyRotation.withKeys, which also
     * retries the next key when the server rejects it).
     */
    val apiKey: String get() = apiKeysList.firstOrNull() ?: ""
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
    val ttsLanguage: String,
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
            ttsLanguage = c.ttsLanguage,
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
                ttsLanguage = o.optString("ttsLanguage", ""),
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
        put("ttsLanguage", ttsLanguage)
        put("autoStartVoice", autoStartVoice); put("autoSendVoice", autoSendVoice)
    }
}

object AppSettings {

    private const val FILE = "androidassist_secure_prefs"
    private const val K_PROVIDER = "provider_id"
    private const val K_BASE_URL = "base_url"
    private const val K_API_KEYS = "api_keys_json"
    private const val K_KEY_ROTATION_STRATEGY = "key_rotation_strategy"
    private const val K_KEY_ROTATION_STATE = "key_rotation_state"
    private const val K_MODEL = "model"
    private const val K_PROMPT = "system_prompt"
    private const val K_AUTO_START_VOICE = "auto_start_voice"
    private const val K_AUTO_SEND_VOICE = "auto_send_voice"
    private const val K_VOICE_LANG = "voice_language"
    private const val K_TTS_LANG = "tts_language"
    private const val K_VOICE_METHOD = "voice_method"
    private const val K_WHISPER_MODEL = "whisper_model"
    private const val K_TTS_ENABLED = "tts_enabled"
    private const val K_TTS_METHOD = "tts_method"
    private const val K_FISH_MODEL = "fish_model"
    private const val K_FISH_VOICE_ID = "fish_voice_id"
    private const val K_FISH_VOICE_NAME = "fish_voice_name"
    private const val K_WEB_SEARCH = "web_search_enabled"
    private const val K_SEARCH_ENGINE = "search_engine"
    private const val K_SEARCH_RESULT_COUNT = "search_result_count"
    private const val K_SEARCH_REGION = "search_region"
    private const val K_SEARCH_FRESHNESS = "search_freshness"
    private const val K_ACTIVE_PERSONA = "active_persona_id"
    private const val K_APP_LANGUAGE = "app_language"
    private const val K_APP_THEME = "app_theme"
    private const val K_NO_THINK = "no_think"
    private const val K_DEVICE_TOOLS = "device_tools"
    private const val K_DEVICE_TOOL_TOGGLES = "device_tool_toggles"
    private const val K_MEMORY_TOOLS = "memory_tools"
    private const val K_MAX_TOOL_CALLS = "max_tool_calls"
    private const val K_MAX_CONTEXT_TURNS = "max_context_turns"
    private const val K_VOICE_SILENCE_MS = "voice_silence_ms"
    private const val K_CALENDAR_MODE = "calendar_mode"
    private const val K_DEFAULT_CALENDAR = "default_calendar_id"
    private const val K_ALLOWED_APPS = "allowed_apps"
    private const val K_ACCENT_COLOR = "accent_color"
    private const val K_FISH_FAVORITES = "fish_favorites"
    private const val K_CUSTOM_ENDPOINTS = "custom_endpoints"
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

    /**
     * Parse the stored keys JSON. Supports BOTH formats:
     *  - v2: {slot: ["key1", "key2"]}      (several keys per slot)
     *  - v1: {slot: "key"}                 (one key per slot — migrated)
     */
    private fun parseKeys(keysJson: String): MutableMap<String, List<String>> {
        val m = mutableMapOf<String, List<String>>()
        try {
            val obj = JSONObject(keysJson)
            for (k in obj.keys()) {
                when (val v = obj.get(k)) {
                    is String -> if (v.isNotBlank()) m[k] = listOf(v)
                    is org.json.JSONArray -> {
                        val list = mutableListOf<String>()
                        for (i in 0 until v.length()) {
                            val s = v.optString(i)
                            if (s.isNotBlank()) list.add(s)
                        }
                        if (list.isNotEmpty()) m[k] = list
                    }
                    else -> Unit
                }
            }
        } catch (e: Exception) {
        }
        return m
    }

    /** Serialize the keys map into the v2 format. */
    private fun dumpKeys(keys: Map<String, List<String>>): String {
        val obj = JSONObject()
        keys.forEach { (slot, list) ->
            val arr = org.json.JSONArray()
            list.forEach { arr.put(it) }
            obj.put(slot, arr)
        }
        return obj.toString()
    }

    private fun load(): AppConfig {
        val p = p()
        val providerId = p.getString(K_PROVIDER, null) ?: LlmProviders.OPENROUTER.id

        val keysJson = p.getString(K_API_KEYS, null)
        val keys: MutableMap<String, List<String>> =
            if (keysJson != null) parseKeys(keysJson) else mutableMapOf()

        // One-time migration from the old single-key field
        val legacy = p.getString(K_LEGACY_KEY, null)
        if (!legacy.isNullOrBlank() && keys[providerId].isNullOrEmpty()) {
            keys[providerId] = listOf(legacy)
            p.edit().putString(K_API_KEYS, dumpKeys(keys))
                .remove(K_LEGACY_KEY).apply()
        }

        return AppConfig(
            providerId = providerId,
            baseUrl = p.getString(K_BASE_URL, null)
                ?: LlmProviders.byId(providerId).defaultBaseUrl,
            apiKeys = keys,
            keyRotationStrategy = p.getString(K_KEY_ROTATION_STRATEGY, "round_robin")
                ?: "round_robin",
            keyRotationState = p.getString(K_KEY_ROTATION_STATE, "{}") ?: "{}",
            model = p.getString(K_MODEL, "") ?: "",
            systemPrompt = p.getString(K_PROMPT, null) ?: AppConfig().systemPrompt,
            autoStartVoice = p.getBoolean(K_AUTO_START_VOICE, true),
            autoSendVoice = p.getBoolean(K_AUTO_SEND_VOICE, true),
            voiceLanguage = p.getString(K_VOICE_LANG, "") ?: "",
            ttsLanguage = p.getString(K_TTS_LANG, "") ?: "",
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
            searchResultCount = p.getInt(K_SEARCH_RESULT_COUNT, 5),
            searchRegion = p.getString(K_SEARCH_REGION, "") ?: "",
            searchFreshness = p.getString(K_SEARCH_FRESHNESS, "") ?: "",
            activePersonaId = p.getString(K_ACTIVE_PERSONA, "") ?: "",
            appLanguage = p.getString(K_APP_LANGUAGE, "") ?: "",
            appTheme = p.getString(K_APP_THEME, "") ?: "",
            noThink = p.getBoolean(K_NO_THINK, false),
            deviceToolsEnabled = p.getBoolean(K_DEVICE_TOOLS, true),
            deviceToolToggles = p.getString(K_DEVICE_TOOL_TOGGLES, "{}") ?: "{}",
            memoryToolsEnabled = p.getBoolean(K_MEMORY_TOOLS, true),
            maxToolCalls = p.getInt(K_MAX_TOOL_CALLS, 4),
            maxContextTurns = p.getInt(K_MAX_CONTEXT_TURNS, 0),
            voiceSilenceMs = p.getInt(K_VOICE_SILENCE_MS, 1500),
            calendarMode = p.getString(K_CALENDAR_MODE, "direct") ?: "direct",
            defaultCalendarId = p.getLong(K_DEFAULT_CALENDAR, 0),
            allowedApps = p.getString(K_ALLOWED_APPS, "[]") ?: "[]",
            accentColor = p.getString(K_ACCENT_COLOR, "") ?: "",
            fishFavorites = p.getString(K_FISH_FAVORITES, "[]") ?: "[]",
            customEndpoints = p.getString(K_CUSTOM_ENDPOINTS, "[]") ?: "[]"
        )
    }

    /** Atomically change config: persist + emit to all collectors. */
    fun update(transform: (AppConfig) -> AppConfig) {
        val next = transform(_config.value)
        p().edit()
            .putString(K_PROVIDER, next.providerId)
            .putString(K_BASE_URL, next.baseUrl)
            .putString(K_API_KEYS, dumpKeys(next.apiKeys))
            .putString(K_KEY_ROTATION_STRATEGY, next.keyRotationStrategy)
            .putString(K_KEY_ROTATION_STATE, next.keyRotationState)
            .putString(K_MODEL, next.model)
            .putString(K_PROMPT, next.systemPrompt)
            .putBoolean(K_AUTO_START_VOICE, next.autoStartVoice)
            .putBoolean(K_AUTO_SEND_VOICE, next.autoSendVoice)
            .putString(K_VOICE_LANG, next.voiceLanguage)
            .putString(K_TTS_LANG, next.ttsLanguage)
            .putString(K_VOICE_METHOD, next.voiceMethod)
            .putString(K_WHISPER_MODEL, next.whisperModel)
            .putBoolean(K_TTS_ENABLED, next.ttsEnabled)
            .putString(K_TTS_METHOD, next.ttsMethod)
            .putString(K_FISH_MODEL, next.fishModel)
            .putString(K_FISH_VOICE_ID, next.fishVoiceId)
            .putString(K_FISH_VOICE_NAME, next.fishVoiceName)
            .putBoolean(K_WEB_SEARCH, next.webSearchEnabled)
            .putString(K_SEARCH_ENGINE, next.searchEngine)
            .putInt(K_SEARCH_RESULT_COUNT, next.searchResultCount)
            .putString(K_SEARCH_REGION, next.searchRegion)
            .putString(K_SEARCH_FRESHNESS, next.searchFreshness)
            .putString(K_ACTIVE_PERSONA, next.activePersonaId)
            .putString(K_APP_LANGUAGE, next.appLanguage)
            .putString(K_APP_THEME, next.appTheme)
            .putBoolean(K_NO_THINK, next.noThink)
            .putBoolean(K_DEVICE_TOOLS, next.deviceToolsEnabled)
            .putString(K_DEVICE_TOOL_TOGGLES, next.deviceToolToggles)
            .putBoolean(K_MEMORY_TOOLS, next.memoryToolsEnabled)
            .putInt(K_MAX_TOOL_CALLS, next.maxToolCalls)
            .putInt(K_MAX_CONTEXT_TURNS, next.maxContextTurns)
            .putInt(K_VOICE_SILENCE_MS, next.voiceSilenceMs)
            .putString(K_CALENDAR_MODE, next.calendarMode)
            .putLong(K_DEFAULT_CALENDAR, next.defaultCalendarId)
            .putString(K_ALLOWED_APPS, next.allowedApps)
            .putString(K_ACCENT_COLOR, next.accentColor)
            .putString(K_FISH_FAVORITES, next.fishFavorites)
            .putString(K_CUSTOM_ENDPOINTS, next.customEndpoints)
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

    /** Replace the whole persona list (backup restore). */
    fun replacePersonas(list: List<Persona>) {
        savePersonas(list)
    }

    /**
     * Apply a persona's PROFILE to the live config — the same slice
     * [switchPersona] applies, but WITHOUT changing the active persona id.
     * Used by the backup restore: the backed-up profile (prompt, model,
     * voice in/out) really takes effect.
     */
    fun applyPersonaProfile(p: Persona) = update { c ->
        c.copy(
            systemPrompt = p.systemPrompt,
            providerId = p.providerId,
            baseUrl = p.baseUrl,
            model = p.model,
            webSearchEnabled = p.webSearchEnabled,
            searchEngine = p.searchEngine,
            ttsEnabled = p.ttsEnabled,
            ttsMethod = p.ttsMethod,
            fishModel = p.fishModel,
            fishVoiceId = p.fishVoiceId,
            fishVoiceName = p.fishVoiceName,
            voiceMethod = p.voiceMethod,
            whisperModel = p.whisperModel,
            voiceLanguage = p.voiceLanguage,
            ttsLanguage = p.ttsLanguage,
            autoStartVoice = p.autoStartVoice,
            autoSendVoice = p.autoSendVoice
        )
    }

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
                ttsLanguage = persona.ttsLanguage,
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

    // ------------------------------------------------------ custom endpoints

    data class CustomEndpoint(val id: String, val name: String, val baseUrl: String)

    fun customEndpointList(): List<CustomEndpoint> {
        return try {
            val arr = org.json.JSONArray(_config.value.customEndpoints)
            val out = mutableListOf<CustomEndpoint>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isNotBlank()) out.add(
                    CustomEndpoint(id, o.optString("name").ifBlank { id }, o.optString("baseUrl"))
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Save (or update) a named custom endpoint; returns its id. */
    fun saveCustomEndpoint(id: String?, name: String, baseUrl: String): String {
        val list = customEndpointList().toMutableList()
        val eid = id ?: java.util.UUID.randomUUID().toString()
        val entry = CustomEndpoint(eid, name.trim().ifBlank { "Endpoint" }, baseUrl.trim())
        val idx = list.indexOfFirst { it.id == eid }
        if (idx >= 0) list[idx] = entry else list.add(entry)
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl))
        }
        update { it.copy(customEndpoints = arr.toString()) }
        return eid
    }

    fun deleteCustomEndpoint(id: String) {
        val list = customEndpointList().filter { it.id != id }
        val arr = org.json.JSONArray()
        list.forEach {
            arr.put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("baseUrl", it.baseUrl))
        }
        update { it.copy(customEndpoints = arr.toString()) }
    }

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
     *
     * CUSTOM is the exception: its baseUrl is the SAVED endpoint the user
     * picked — wiping it back to "" broke selecting a custom endpoint right
     * after creating it (user report). setCustomEndpoint() sets the URL.
     */
    fun setProvider(providerId: String) = update {
        it.copy(
            providerId = providerId,
            baseUrl = if (providerId == "custom") it.baseUrl
                .takeIf { b -> b.isNotBlank() }
                ?: customEndpointList().firstOrNull()?.baseUrl.orEmpty()
            else LlmProviders.byId(providerId).defaultBaseUrl,
            model = ""
        )
    }

    /** Select a saved custom endpoint: applies its URL to the live config. */
    fun selectCustomEndpoint(id: String) {
        val ep = customEndpointList().firstOrNull { it.id == id } ?: return
        update {
            it.copy(
                providerId = "custom",
                baseUrl = ep.baseUrl,
                model = ""
            )
        }
    }

    // ------------------------------------------------------- api key slots

    /** ALL keys saved for one slot (provider id, "groq", "fish", ...). */
    fun keysFor(slot: String): List<String> = _config.value.apiKeys[slot] ?: emptyList()

    /** Add a key to a slot (duplicates are ignored). */
    fun addApiKey(slot: String, key: String) {
        val k = key.trim()
        if (k.isEmpty()) return
        update { c ->
            val list = (c.apiKeys[slot] ?: emptyList()).toMutableList()
            if (!list.contains(k)) list.add(k)
            c.copy(apiKeys = c.apiKeys + (slot to list))
        }
    }

    /** Remove ONE key from a slot (by value). */
    fun removeApiKey(slot: String, key: String) = update { c ->
        val list = (c.apiKeys[slot] ?: emptyList()).filter { it != key }
        val next = if (list.isEmpty()) c.apiKeys - slot else c.apiKeys + (slot to list)
        c.copy(apiKeys = next)
    }

    /** Replace the whole list of one slot. */
    fun setApiKeys(slot: String, keys: List<String>) = update { c ->
        val clean = keys.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val next = if (clean.isEmpty()) c.apiKeys - slot else c.apiKeys + (slot to clean)
        c.copy(apiKeys = next)
    }

    /**
     * Legacy single-key setter kept for compatibility: replaces the slot
     * with exactly one key.
     */
    fun setApiKey(providerId: String, key: String) = setApiKeys(providerId, listOf(key))

    /** The GLOBAL rotation strategy used for every slot. */
    fun setKeyRotationStrategy(strategy: String) = update {
        it.copy(keyRotationStrategy = strategy)
    }

    // ------------------------------------------------------ app whitelist

    /** Allowed packages for open_app; empty = all apps allowed. */
    fun allowedAppList(): List<String> = try {
        val arr = org.json.JSONArray(_config.value.allowedApps)
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (e: Exception) { emptyList() }

    fun toggleAllowedApp(pkg: String) {
        val cur = allowedAppList().toMutableList()
        val i = cur.indexOf(pkg)
        if (i >= 0) cur.removeAt(i) else cur.add(pkg)
        val arr = org.json.JSONArray()
        cur.forEach { arr.put(it) }
        update { it.copy(allowedApps = arr.toString()) }
    }

    // ------------------------------------------------- per-ability toggles

    /**
     * The per-ability switch map (ability id -> enabled). A MISSING id means
     * ENABLED — only the abilities the user explicitly turned off are false.
     */
    fun abilityToggles(): Map<String, Boolean> = parseAbilityToggles(_config.value.deviceToolToggles)

    /** Parse the ability map from a raw JSON string. */
    private fun parseAbilityToggles(json: String): Map<String, Boolean> = try {
        val obj = JSONObject(json)
        val out = mutableMapOf<String, Boolean>()
        for (k in obj.keys()) out[k] = obj.optBoolean(k, true)
        out
    } catch (e: Exception) {
        emptyMap()
    }

    /**
     * Is this Android ability available to the model? Needs BOTH the master
     * switch (deviceToolsEnabled) AND the ability's own switch.
     *
     * [togglesJson] is the raw map from the observed config — passing it
     * keeps the UI reactive (the switch re-reads the value on every
     * recomposition instead of reading the flow imperatively).
     */
    fun abilityEnabled(abilityId: String, togglesJson: String): Boolean {
        if (!_config.value.deviceToolsEnabled) return false
        return parseAbilityToggles(togglesJson)[abilityId] ?: true
    }

    /** Same check, reading the live config directly (non-UI callers). */
    fun abilityEnabled(abilityId: String): Boolean =
        abilityEnabled(abilityId, _config.value.deviceToolToggles)

    /** Turn one ability on/off (its own switch; the master stays untouched). */
    fun setAbilityEnabled(abilityId: String, enabled: Boolean) = update { c ->
        val map = parseAbilityToggles(c.deviceToolToggles).toMutableMap()
        map[abilityId] = enabled
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        c.copy(deviceToolToggles = obj.toString())
    }

    /** Is this tool function allowed right now? */
    fun toolAllowed(toolName: String): Boolean {
        if (toolName == "web_search") return _config.value.webSearchEnabled
        if (toolName in ToolAbility.MEMORY_TOOLS) return _config.value.memoryToolsEnabled
        val ability = ToolAbility.of(toolName) ?: return false
        return abilityEnabled(ability)
    }

    /** Any tool at all available? (drives whether the request declares tools) */
    fun anyToolEnabled(): Boolean {
        val c = _config.value
        if (c.webSearchEnabled) return true
        if (c.memoryToolsEnabled) return true
        if (!c.deviceToolsEnabled) return false
        return ToolAbility.TOOLS.keys.any { abilityEnabled(it) }
    }
}
