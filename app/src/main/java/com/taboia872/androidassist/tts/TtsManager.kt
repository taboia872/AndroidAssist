package com.taboia872.androidassist.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * Text-to-speech via the system TTS engine (offline, free, no key).
 * Shared instance: init once per process. Init status is OBSERVABLE —
 * screens can show why TTS is not speaking (engine missing, init failed)
 * instead of failing silently.
 *
 * Manifest note: API 30+ needs the TTS_SERVICE <queries> declaration for
 * the engines to be visible — the same class of bug the speech recognizer
 * had.
 */
object TtsManager {

    private const val TAG = "TtsManager"

    sealed class TtsState {
        data object Idle : TtsState()
        data object Initializing : TtsState()
        data object Ready : TtsState()
        data class Speaking(val utteranceId: String) : TtsState()
        data class Failed(val reason: String) : TtsState()
    }

    private var tts: TextToSpeech? = null

    private val _state = MutableStateFlow<TtsState>(TtsState.Idle)
    val state: StateFlow<TtsState> = _state

    fun init(context: Context, languageTag: String) {
        if (tts != null) return
        _state.value = TtsState.Initializing
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            when {
                status == TextToSpeech.SUCCESS -> {
                    _state.value = TtsState.Ready
                    applyLanguage(languageTag)
                    Log.i(TAG, "TTS engine ready")
                }
                status == TextToSpeech.ERROR -> {
                    _state.value = TtsState.Failed(
                        "TTS engine init failed — check a TTS engine is installed (Settings > System > Text-to-speech)"
                    )
                    Log.e(TAG, "TTS init ERROR")
                }
                else -> {
                    _state.value = TtsState.Failed("TTS init status $status")
                }
            }
        }
    }

    /** Languages the engine can actually speak (drives the test button). */
    fun isLanguageAvailable(languageTag: String): Boolean {
        val engine = tts ?: return false
        if (_state.value !is TtsState.Ready) return false
        val locale = if (languageTag.isBlank()) Locale.getDefault()
        else Locale.forLanguageTag(languageTag)
        return engine.isLanguageAvailable(locale) >= TextToSpeech.LANG_AVAILABLE
    }

    fun applyLanguage(languageTag: String) {
        val engine = tts ?: return
        val locale = if (languageTag.isBlank()) Locale.getDefault()
        else Locale.forLanguageTag(languageTag)
        engine.language = locale
    }

    /**
     * Speak text. Markdown noise is stripped so the engine reads clean
     * prose (pipes, asterisks, fences would be spoken literally).
     */
    fun speak(text: String, utteranceId: String = "answer"): Boolean {
        val engine = tts ?: run {
            _state.value = TtsState.Failed("TTS not initialized")
            return false
        }
        if (_state.value !is TtsState.Ready) return false

        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), " code block. ")
            .replace("```", " ")
            .replace(Regex("([*_~`#>]+)"), " ")
            .replace("|", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) return false

        engine.setOnUtteranceProgressListener(object :
            android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _state.value = TtsState.Speaking(utteranceId ?: "")
            }

            override fun onDone(utteranceId: String?) {
                _state.value = TtsState.Ready
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.value = TtsState.Failed("Speech failed for utterance $utteranceId")
            }
        })

        val result = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            _state.value = TtsState.Failed("Engine rejected the speech request ($result)")
            return false
        }
        return true
    }

    fun stop() {
        tts?.stop()
        if (_state.value is TtsState.Speaking) _state.value = TtsState.Ready
    }

    fun shutdown() {
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        _state.value = TtsState.Idle
    }
}
