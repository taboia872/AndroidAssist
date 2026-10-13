package com.taboia872.androidassist.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Voice input via the SYSTEM speech recognizer (Google app on most
 * devices): free, no API key, on-device or Google-cloud depending on the
 * recognizer. Same callback shape as VoiceEngine so screens don't care
 * which engine is active.
 *
 * Manifest note: API 30+ needs the RECOGNIZE_SPEECH / RecognitionService
 * <queries> declarations for the recognizer to be visible.
 */
object SystemRecognizer {

    fun hasMicPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    fun isAvailable(context: Context): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Start recognition. Ends on user silence (EXTRA_SPEECH_INPUT_COMPLETE_SILENCE
     * ~2s). [onPartial] gets live partials when the recognizer emits them.
     */
    fun listen(
        context: Context,
        language: String,
        onPartial: (String) -> Unit,
        onResult: (Result<String>) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onResult(Result.failure(IllegalStateException("No speech recognizer on this device")))
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val langTag = language.ifBlank { Locale.getDefault().toLanguageTag() }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
                    SpeechRecognizer.ERROR_NETWORK -> "Recognizer network error"
                    else -> "Recognizer error $error"
                }
                recognizer.destroy()
                onResult(Result.failure(IllegalStateException(message)))
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                recognizer.destroy()
                if (text.isEmpty()) {
                    onResult(Result.failure(IllegalStateException("No speech detected")))
                } else {
                    onResult(Result.success(text))
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isNotBlank()) onPartial(text)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_THRESHOLD_MILLIS,
                2000L
            )
        }
        recognizer.startListening(intent)
    }
}
