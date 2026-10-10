package com.taboia872.androidassist.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.taboia872.androidassist.llm.LlmProviders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single source of truth for app configuration. Every screen reads the
 * same StateFlow; every write goes through update(). No screen ever touches
 * SharedPreferences or checks a provider by itself — that is how state
 * desync bugs are structurally impossible here.
 */
data class AppConfig(
    val providerId: String = LlmProviders.OPENROUTER.id,
    val baseUrl: String = LlmProviders.OPENROUTER.defaultBaseUrl,
    val apiKey: String = "",
    val model: String = "",
    val systemPrompt: String = "You are a helpful, concise assistant. Always reply in the user's language."
)

object AppSettings {

    private const val FILE = "androidassist_secure_prefs"
    private const val K_PROVIDER = "provider_id"
    private const val K_BASE_URL = "base_url"
    private const val K_API_KEY = "api_key"
    private const val K_MODEL = "model"
    private const val K_PROMPT = "system_prompt"

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
        val defaults = AppConfig()
        val providerId = p.getString(K_PROVIDER, null) ?: defaults.providerId
        return AppConfig(
            providerId = providerId,
            baseUrl = p.getString(K_BASE_URL, null)
                ?: LlmProviders.byId(providerId).defaultBaseUrl,
            apiKey = p.getString(K_API_KEY, "") ?: "",
            model = p.getString(K_MODEL, "") ?: "",
            systemPrompt = p.getString(K_PROMPT, null) ?: defaults.systemPrompt
        )
    }

    /** Atomically change config: persist + emit to all collectors. */
    fun update(transform: (AppConfig) -> AppConfig) {
        val next = transform(_config.value)
        p().edit()
            .putString(K_PROVIDER, next.providerId)
            .putString(K_BASE_URL, next.baseUrl)
            .putString(K_API_KEY, next.apiKey)
            .putString(K_MODEL, next.model)
            .putString(K_PROMPT, next.systemPrompt)
            .apply()
        _config.value = next
    }

    /**
     * Switch provider: resets the base URL to the provider default and clears
     * the model pick (catalogs differ per provider). Key is kept — reusing a
     * Groq key across Groq sessions is the common case.
     */
    fun setProvider(providerId: String) = update {
        it.copy(
            providerId = providerId,
            baseUrl = LlmProviders.byId(providerId).defaultBaseUrl,
            model = ""
        )
    }
}
