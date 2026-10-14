package com.taboia872.androidassist.tts

import android.media.MediaPlayer
import android.util.Log
import com.taboia872.androidassist.net.Net
import com.taboia872.androidassist.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class FishVoice(val id: String, val title: String)

/**
 * FishAudio online TTS (https://api.fish.audio/v1/tts).
 *
 * API SHAPE GOTCHA (confirmed in current docs): the MODEL goes in the
 * request HEADER ("model: s2.1-pro-free"), NOT in the JSON body. The body
 * carries reference_id (voice), format and sampling params. Free dev
 * tier = "s2.1-pro-free". Voice list comes from POST /v1/model with
 * page_size — same live-catalog pattern as the LLM model picker.
 *
 * Playback: response bytes (mp3) are spooled to a temp file and played
 * with MediaPlayer. Failures surface through TtsManager.state — never
 * silent.
 */
object FishTts {

    private const val TAG = "FishTts"
    private const val ENDPOINT = "https://api.fish.audio/v1/tts"
    private const val MODELS_ENDPOINT = "https://api.fish.audio/v1/model"

    private var player: MediaPlayer? = null

    private val _state = MutableStateFlow<TtsManager.TtsState>(TtsManager.TtsState.Idle)
    val state: StateFlow<TtsManager.TtsState> = _state

    fun hasKey(): Boolean =
        (AppSettings.config.value.apiKeys["fish"] ?: "").isNotBlank()

    /**
     * List the voices visible to this API key. FishAudio changed endpoints
     * over time (user hit HTTP 404 on the old one), so try in order:
     *  1. GET /v1/voices          (ElevenLabs-compat shape: {voices:[{voice_id,name}]})
     *  2. GET /v1/model/list-models (items shape)
     *  3. POST /v1/model           (legacy {items:[{_id,title}]} shape)
     * First endpoint returning 200 with a parseable list wins.
     */
    suspend fun listVoices(): Result<List<FishVoice>> = withContext(Dispatchers.IO) {
        val key = AppSettings.config.value.apiKeys["fish"] ?: ""
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No FishAudio API key"))
        }
        val attempts = listOf<() -> List<FishVoice>?>(
            { listViaGet("/v1/voices") },
            { listViaGet("/v1/model/list-models") },
            { listViaLegacyModelPost() }
        )
        for (attempt in attempts) {
            try {
                attempt()?.let { if (it.isNotEmpty()) return@withContext Result.success(it) }
            } catch (e: Exception) {
                // try next endpoint
            }
        }
        Result.failure(IllegalStateException("No voices found (endpoints returned empty or failed)"))
    }

    private fun parseItems(arr: org.json.JSONArray): List<FishVoice> {
        val out = ArrayList<FishVoice>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val id = item.optString("_id", item.optString("id", item.optString("voice_id", "")))
            if (id.isEmpty()) continue
            val title = item.optString("title", item.optString("name", "")).ifEmpty { "Voice $id" }
            out.add(FishVoice(id, title))
        }
        return out
    }

    private fun listViaGet(path: String): List<FishVoice>? {
        val key = AppSettings.config.value.apiKeys["fish"] ?: ""
        val conn = Net.open("https://api.fish.audio$path").apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            // Bare-array response
            if (body.trimStart().startsWith("[")) {
                return parseItems(org.json.JSONArray(body))
            }
            val root = JSONObject(body)
            // ElevenLabs shape: {voices:[...]}
            root.optJSONArray("voices")?.let { return parseItems(it) }
            // items shape: {items:[...]}
            root.optJSONArray("items")?.let { return parseItems(it) }
            return null
        } finally {
            conn.disconnect()
        }
    }

    private fun listViaLegacyModelPost(): List<FishVoice>? {
        val key = AppSettings.config.value.apiKeys["fish"] ?: ""
        val body = JSONObject().apply {
            put("page_size", 100)
            put("page_number", 1)
        }
        val conn = Net.open("https://api.fish.audio/v1/model").apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        try {
            if (conn.responseCode != 200) return null
            val root = JSONObject(conn.inputStream.bufferedReader().readText())
            root.optJSONArray("items")?.let { return parseItems(it) }
            return null
        } finally {
            conn.disconnect()
        }
    }

    suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        val key = AppSettings.config.value.apiKeys["fish"] ?: ""
        if (key.isBlank()) {
            _state.value = TtsManager.TtsState.Failed("No FishAudio API key — add it in Settings")
            return@withContext false
        }
        val cfg = AppSettings.config.value

        // Strip markdown noise so the voice reads clean prose
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), " code block. ")
            .replace("```", " ")
            .replace(Regex("([*_~`#>]+)"), " ")
            .replace("|", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) return@withContext false

        _state.value = TtsManager.TtsState.Initializing
        try {
            val body = JSONObject().apply {
                put("text", clean)
                put("format", "mp3")
                put("mp3_bitrate", 128)
                put("latency", "balanced")
                put("normalize", true)
                // Selected voice; blank = platform default voice
                if (cfg.fishVoiceId.isNotBlank()) {
                    put("reference_id", cfg.fishVoiceId)
                }
            }

            val conn = Net.open(ENDPOINT).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                // MODEL IN HEADER — FishAudio contract
                setRequestProperty("model", cfg.fishModel.ifBlank { "s2.1-pro-free" })
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            if (status != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
                Log.e(TAG, "FishAudio HTTP $status: $err")
                _state.value = TtsManager.TtsState.Failed("FishAudio HTTP $status")
                return@withContext false
            }

            // Spool to cache file, then play
            val outFile = File.createTempFile("fish_tts", ".mp3", null)
            conn.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, 8192)
                }
            }
            conn.disconnect()

            play(outFile)
            true
        } catch (e: Exception) {
            Log.e(TAG, "FishAudio failed", e)
            _state.value = TtsManager.TtsState.Failed("FishAudio: ${e.message}")
            false
        }
    }

    private fun play(file: File) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener {
                it.start()
                _state.value = TtsManager.TtsState.Speaking("fish")
            }
            setOnCompletionListener {
                _state.value = TtsManager.TtsState.Ready
                it.release()
                player = null
                file.delete()
            }
            setOnErrorListener { mp, what, extra ->
                _state.value = TtsManager.TtsState.Failed("Playback error $what/$extra")
                mp.release()
                player = null
                file.delete()
                true
            }
            prepare()
        }
    }

    fun stop() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
                it.release()
            } catch (e: Exception) {
            }
        }
        player = null
        if (_state.value is TtsManager.TtsState.Speaking) _state.value = TtsManager.TtsState.Ready
    }
}
