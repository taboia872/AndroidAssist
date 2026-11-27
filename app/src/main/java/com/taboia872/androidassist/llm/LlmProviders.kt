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
    val requiresKey: Boolean,
    val keyHint: String = ""
)

object LlmProviders {
    val OPENROUTER = LlmProvider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", true, "Get a key at openrouter.ai/keys")
    val GROQ = LlmProvider("groq", "Groq", "https://api.groq.com/openai/v1", true, "Free at console.groq.com/keys")
    val OLLAMA_CLOUD = LlmProvider("ollama", "Ollama Cloud", "https://ollama.com/v1", true, "Create at ollama.com/settings/keys")
    val NVIDIA = LlmProvider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", true, "Create at build.nvidia.com/settings")
    /**
     * The llama.cpp server running ON THIS PHONE (the router in Termux):
     * a multi-model OpenAI-compatible server. No key, no account, no
     * internet — the assistant runs on the device.
     */
    val LOCAL = LlmProvider("local", "Local (llama.cpp)", "http://127.0.0.1:8080/v1", false, "")
    /**
     * The DuckProxy: a local OpenAI-compatible proxy over duck.ai, running
     * in the terminal (Chromium automation).
     *
     * It speaks the OpenAI protocol, but with its own quirks — the app
     * adapts to them in [LlmClient] instead of the proxy guessing what the
     * client wants:
     * - the conversation is identified by the header X-Conversation-Id (the
     *   proxy maps it to a duck.ai chat), because the app resends the whole
     *   history on every turn and the history alone cannot identify it;
     * - the "no-think" switch maps to reasoning_effort, the field the
     *   proxy understands (it turns the reasoning button of the site);
     * - the catalog is read through a real browser, so it needs a longer
     *   timeout than a normal API;
     * - the answer takes longer than a cloud API, so the read timeout is
     *   bigger.
     */
    val DUCKPROXY = LlmProvider(
        "duckproxy", "DuckProxy (duck.ai)", "http://127.0.0.1:3000/v1", false, ""
    )
    val CUSTOM = LlmProvider("custom", "Custom (e.g. Ollama on PC)", "", false, "")

    val ALL = listOf(OPENROUTER, GROQ, OLLAMA_CLOUD, NVIDIA, LOCAL, DUCKPROXY, CUSTOM)

    fun byId(id: String): LlmProvider = ALL.firstOrNull { it.id == id } ?: CUSTOM
}
