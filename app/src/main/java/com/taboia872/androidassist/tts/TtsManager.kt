package com.taboia872.androidassist.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Text-to-speech via the system TTS engine (offline, free, no key).
 * Single shared instance: init once per process, speak/cancel from any
 * screen. Locale follows the voiceLanguage setting (empty = system).
 */
object TtsManager {

    private var tts: TextToSpeech? = null
    @Volatile
    private var ready = false

    /** Listener notified when a spoken answer finishes (or fails). */
    var onDone: (() -> Unit)? = null

    fun init(context: Context, languageTag: String) {
        if (tts != null) return
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) applyLanguage(languageTag)
        }
    }

    fun applyLanguage(languageTag: String) {
        val engine = tts ?: return
        val locale = if (languageTag.isBlank()) Locale.getDefault()
        else Locale.forLanguageTag(languageTag)
        engine.language = locale
    }

    fun isReady(): Boolean = ready

    /**
     * Speak a finished answer. Strips markdown noise the engine would read
     * aloud literally (pipes, asterisks, fences) and cancels anything
     * already speaking.
     */
    fun speak(text: String) {
        val engine = tts ?: return
        if (!ready) return
        engine.stop()

        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), " code block. ")
            .replace("```", " ")
            .replace(Regex("([*_~`#>]+)"), " ")
            .replace(Regex("\\|"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) return

        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "answer")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        ready = false
    }

    fun setProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "answer") onDone?.invoke()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == "answer") onDone?.invoke()
            }
        })
    }
}
