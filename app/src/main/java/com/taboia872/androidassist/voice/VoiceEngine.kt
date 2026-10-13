package com.taboia872.androidassist.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import com.taboia872.androidassist.net.Net
import java.util.Locale
import kotlin.math.sqrt

/**
 * Online voice input: mic capture with a simple energy VAD (silence stops
 * recording, like the base app's flow) and transcription via Groq's
 * Whisper API using the Groq API key already stored in AppSettings.
 *
 * Deliberately NO SpeechRecognizer/system recognizer: this phase is online-
 * only with Groq, per project decisions.
 */
object VoiceEngine {

    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // VAD tuning: after speech started, this much trailing silence ends it
    private const val SILENCE_END_MS = 1_500L
    private const val MAX_RECORD_MS = 30_000L
    private const val ENERGY_THRESHOLD = 600.0

    /** Microphone permission present? Cheap pre-check before launching UI. */
    fun hasMicPermission(context: Context): Boolean =
        context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Record until silence-after-speech or the hard cap, then transcribe.
     * Model comes from AppSettings.whisperModel (selected in Settings).
     */
    fun recordAndTranscribe(
        context: Context,
        language: String,
        onLevel: (Double) -> Unit = {},
        onResult: (Result<String>) -> Unit
    ) {
        Thread {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuf <= 0) {
                onResult(Result.failure(IllegalStateException("AudioRecord unavailable")))
                return@Thread
            }
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING,
                    minBuf * 4
                )
            } catch (e: Exception) {
                onResult(Result.failure(e))
                return@Thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                onResult(Result.failure(IllegalStateException("Mic init failed")))
                return@Thread
            }

            val pcm = ByteArrayOutputStream()
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100ms
            var speechStarted = false
            var silenceStart = -1L
            val startedAt = System.currentTimeMillis()

            record.startRecording()
            try {
                while (true) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) break
                    var sum = 0.0
                    for (i in 0 until n) sum += chunk[i].toDouble() * chunk[i]
                    val rms = sqrt(sum / n)
                    onLevel(rms)

                    if (rms > ENERGY_THRESHOLD) {
                        speechStarted = true
                        silenceStart = -1
                    } else if (speechStarted) {
                        val now = System.currentTimeMillis()
                        if (silenceStart < 0) silenceStart = now
                        if (now - silenceStart >= SILENCE_END_MS) break
                    }
                    if (System.currentTimeMillis() - startedAt > MAX_RECORD_MS) break

                    for (i in 0 until n) pcm.write(
                        byteArrayOf(
                            (chunk[i].toInt() and 0xFF).toByte(),
                            ((chunk[i].toInt() shr 8) and 0xFF).toByte()
                        )
                    )
                }
            } finally {
                record.stop()
                record.release()
            }

            if (!speechStarted) {
                onResult(Result.failure(IllegalStateException("No speech detected")))
                return@Thread
            }

            val result = transcribeGroq(pcm.toByteArray(), language)
            onResult(result)
        }.start()
    }

    /** WAV header + PCM → Groq audio/transcriptions, model from settings. */
    fun transcribeGroq(pcm: ByteArray, language: String): Result<String> {
        val cfg = com.taboia872.androidassist.settings.AppSettings.config.value
        val groqKey = cfg.apiKeys["groq"] ?: cfg.apiKeys[cfg.providerId] ?: ""
        if (groqKey.isBlank()) {
            return Result.failure(IllegalStateException("No Groq API key — add it in Settings"))
        }
        val model = cfg.whisperModel.ifBlank { "whisper-large-v3" }

        return try {
            val boundary = "----AndroidAssist${System.currentTimeMillis()}"
            val wav = toWav(pcm, SAMPLE_RATE)

            val conn = Net.open("https://api.groq.com/openai/v1/audio/transcriptions").apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 60_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $groqKey")
                setRequestProperty(
                    "Content-Type", "multipart/form-data; boundary=$boundary"
                )
            }

            val body = ByteArrayOutputStream()
            fun part(name: String, value: String) {
                body.write("--$boundary\r\n".toByteArray())
                body.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                body.write("$value\r\n".toByteArray())
            }
            body.write("--$boundary\r\n".toByteArray())
            body.write(
                ("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n" +
                    "Content-Type: audio/wav\r\n\r\n").toByteArray()
            )
            body.write(wav)
            body.write("\r\n".toByteArray())
            part("model", model)
            if (language.isNotBlank()) part("language", normalizeLang(language))
            part("response_format", "json")
            body.write("--$boundary--\r\n".toByteArray())

            conn.outputStream.use { it.write(body.toByteArray()) }

            if (conn.responseCode != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText()?.take(300) ?: ""
                Result.failure(RuntimeException("HTTP ${conn.responseCode}: $err"))
            } else {
                val text = JSONObject(conn.inputStream.bufferedReader().readText())
                    .optString("text", "")
                    .trim()
                if (text.isEmpty()) Result.failure(RuntimeException("Empty transcription"))
                else Result.success(text)
            }
        } catch (e: JSONException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // no connection ref here outside try; HttpURLConnection cleans up
        }
    }

    /** "pt-BR" / "pt" → "pt"; Whisper wants ISO 639-1. */
    private fun normalizeLang(tag: String): String =
        tag.substringBefore('-').lowercase(Locale.ROOT)

    private fun toWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2 // mono 16-bit
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray()); le32(36 + pcm.size)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1)
        le32(sampleRate); le32(byteRate); le16(2); le16(16)
        out.write("data".toByteArray()); le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
