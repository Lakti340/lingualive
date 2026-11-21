package com.lingualive.app.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Audio traduit.
 * 1) Voix Piper intégrée à l'appli (téléchargée dans « Pack hors-ligne ») — aucune config téléphone.
 * 2) Sinon, secours : synthèse vocale du téléphone.
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val app = context.applicationContext
    @Volatile private var systemReady = false
    private val system = TextToSpeech(app, this)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var generation = 0
    @Volatile private var track: AudioTrack? = null

    override fun onInit(status: Int) { systemReady = status == TextToSpeech.SUCCESS }

    fun speak(text: String, langCode: String, flush: Boolean = false) {
        if (text.isBlank()) return
        if (flush) stop()
        val gen = generation
        if (Packs.ttsInstalled(app, langCode)) {
            worker.execute {
                if (gen != generation) return@execute
                val voice = runCatching { PiperVoices.get(app, langCode) }.getOrNull()
                if (voice == null) { speakSystem(text, langCode); return@execute }
                val audio = runCatching { voice.generate(text = text, sid = 0, speed = 1.0f) }.getOrNull()
                if (audio == null || gen != generation) return@execute
                play(audio.samples, audio.sampleRate, gen)
            }
        } else speakSystem(text, langCode)
    }

    private fun speakSystem(text: String, langCode: String) {
        if (!systemReady) return
        system.language = Locale.forLanguageTag(langCode)
        system.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    private fun play(samples: FloatArray, rate: Int, gen: Int) {
        if (samples.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, rate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try {
            t.play()
            var off = 0
            while (off < samples.size && gen == generation) {
                val n = t.write(samples, off, minOf(4096, samples.size - off), AudioTrack.WRITE_BLOCKING)
                if (n <= 0) break
                off += n
            }
            // Attendre la fin de la lecture du tampon
            while (gen == generation && t.playbackHeadPosition < samples.size) Thread.sleep(20)
        } catch (_: Exception) {
        } finally {
            runCatching { t.stop() }
            t.release()
            if (track === t) track = null
        }
    }

    fun stop() {
        generation++
        runCatching { system.stop() }
        runCatching { track?.pause(); track?.flush() }
    }

    fun shutdown() {
        stop()
        worker.shutdownNow()
        runCatching { system.shutdown() }
    }
}
