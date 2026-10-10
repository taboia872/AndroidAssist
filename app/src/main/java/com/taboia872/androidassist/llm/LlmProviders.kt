package com.taboia872.androidassist.llm

/**
 * Catalog of OpenAI-compatible chat backends. The app talks to ONE generic
 * endpoint ("$baseUrl/chat/completions"); providers differ only in default
 * URL and whether a key is required. Everything is user-overridable in
 * Settings — no hardcoded branch anywhere else in the codebase.
 */
data class LlmProvider(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val requiresKey: Boolean
)

object LlmProviders {
    val OPENROUTER = LlmProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", true)
    val GROQ = LlmProvider("groq", "Groq", "https://api.groq.com/openai/v1", true)
    val OLLAMA_CLOUD = LlmProvider("ollama", "Ollama Cloud", "https://ollama.com/v1", true)
    val NVIDIA = LlmProvider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", true)
    val CUSTOM = LlmProvider("custom", "Custom (e.g. Ollama on PC)", "", false)

    val ALL = listOf(OPENROUTER, GROQ, OLLAMA_CLOUD, NVIDIA, CUSTOM)

    fun byId(id: String): LlmProvider = ALL.firstOrNull { it.id == id } ?: CUSTOM
}
