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
     * List voices from FishAudio's public catalog: GET /model
     * (root path — NOT /v1/model, which 404s since the API moved).
     * Supports language filter (e.g. "pt") and page_size; returns
     * {items:[{_id,title,description,languages,...}]}.
     */
    suspend fun listVoices(language: String = ""): Result<List<FishVoice>> =
        withContext(Dispatchers.IO) {
            val key = AppSettings.config.value.apiKeys["fish"] ?: ""
            if (key.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("No FishAudio API key")
                )
            }
            runCatching {
                var url = "https://api.fish.audio/model?page_size=50"
                if (language.isNotBlank()) url += "&language=$language"
                val conn = Net.open(url).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Authorization", "Bearer $key")
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (conn.responseCode != 200) {
                        throw RuntimeException("HTTP ${conn.responseCode}")
                    }
                    val root = JSONObject(conn.inputStream.bufferedReader().readText())
                    val items = root.optJSONArray("items") ?: org.json.JSONArray()
                    val out = ArrayList<FishVoice>(items.length())
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optString("_id", "")
                        if (id.isEmpty()) continue
                        val title = item.optString("title", "").ifEmpty { "Voice $id" }
                        out.add(FishVoice(id, title))
                    }
                    out
                } finally {
                    conn.disconnect()
                }
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
