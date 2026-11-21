package com.lingualive.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import kotlin.concurrent.thread

/**
 * Transcription continue à partir du micro.
 * Priorité au moteur Vosk INTÉGRÉ à l'appli (modèle téléchargé dans « Pack hors-ligne ») :
 * aucun réglage ni service du téléphone. À défaut, moteur de reconnaissance d'Android.
 * Le moteur s'arrête après chaque énoncé : on le relance automatiquement tant que [running] est vrai.
 */
class LiveTranscriber(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var langTag = "fr-FR"
    private var continuous = true
    private var useOnDevice = true

    /** Appelé quand une écoute « un seul énoncé » (continuous = false) se termine. */
    var onStopped: () -> Unit = {}
    private val handler = Handler(Looper.getMainLooper())
    private var vosk: SpeechService? = null
    private var session = 0

    fun start(speechTag: String, continuous: Boolean = true) {
        stopEngines()
        langTag = speechTag
        this.continuous = continuous
        val code = Langs.all.firstOrNull { it.speechTag == speechTag }?.code
        if (Packs.sttInstalled(context, code)) { startVosk(code!!); return }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus("Reconnaissance vocale indisponible sur cet appareil (installe l'appli Google).")
            return
        }
        running = true
        createRecognizer()
        listen()
        onStatus("Écoute en cours…")
    }

    fun stop() {
        stopEngines()
        onStatus("Arrêté")
    }

    private fun stopEngines() {
        running = false
        session++
        handler.removeCallbacksAndMessages(null)
        recognizer?.run {
            stopListening()
            cancel()
            destroy()
        }
        recognizer = null
        vosk?.let { runCatching { it.stop(); it.shutdown() } }
        vosk = null
    }

    // ---------- Moteur Vosk intégré ----------
    private fun startVosk(code: String) {
        running = true
        val mySession = ++session
        onStatus("Chargement du modèle vocal…")
        thread(name = "vosk-load") {
            val model = runCatching { VoskModels.get(context, code) }.getOrNull()
            handler.post {
                if (!running || mySession != session) return@post
                if (model == null) { onStatus("Modèle vocal illisible : réinstalle la langue"); finishOrStop(); return@post }
                try {
                    val rec = Recognizer(model, 16000f)
                    vosk = SpeechService(rec, 16000f).also { it.startListening(voskListener) }
                    onStatus("Écoute en cours (100 % hors-ligne)…")
                } catch (e: Exception) {
                    onStatus("Micro indisponible : ${e.message}"); finishOrStop()
                }
            }
        }
    }

    private fun finishOrStop() {
        vosk?.let { runCatching { it.stop(); it.shutdown() } }
        vosk = null
        if (!continuous) finishSingle() else running = false
    }

    private val voskListener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val t = hypothesis?.let { runCatching { JSONObject(it).optString("partial") }.getOrNull() }
            if (!t.isNullOrBlank()) onPartial(t)
        }

        override fun onResult(hypothesis: String?) {
            val t = hypothesis?.let { runCatching { JSONObject(it).optString("text") }.getOrNull() }
            if (!t.isNullOrBlank()) {
                onFinal(t)
                if (!continuous) finishOrStop()
            }
        }

        override fun onFinalResult(hypothesis: String?) = onResult(hypothesis)
        override fun onError(exception: Exception?) { onStatus("Erreur micro : ${exception?.message}") }
        override fun onTimeout() {}
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        // Reconnaissance 100 % sur l'appareil quand c'est possible (Android 13+), sinon moteur classique en « préférer hors-ligne ».
        val onDevice = useOnDevice && Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        recognizer = (if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                      else SpeechRecognizer.createSpeechRecognizer(context)).apply {
            setRecognitionListener(listener)
        }
    }

    private fun listen() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        recognizer?.startListening(intent)
    }

    private fun finishSingle() {
        running = false
        handler.removeCallbacksAndMessages(null)
        onStopped()
    }

    private fun restart(delayMs: Long, recreate: Boolean = false) {
        if (!running) return
        if (!continuous) { finishSingle(); return }
        handler.postDelayed({
            if (recreate) createRecognizer()
            listen()
        }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(onPartial)
        }

        override fun onResults(results: Bundle?) {
            results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(onFinal)
            restart(50)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onStatus("Permission micro refusée")
                    running = false
                    if (!continuous) onStopped()
                }
                12, 13 -> { // langue non disponible sur l'appareil : on bascule sur le moteur classique
                    if (useOnDevice) { useOnDevice = false; restart(200, recreate = true) }
                    else { onStatus("Langue non installée pour la reconnaissance hors-ligne (voir « Pack hors-ligne »)"); restart(1500) }
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(100)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> restart(600, recreate = true)
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    onStatus("Problème réseau de la reconnaissance vocale, nouvelle tentative…")
                    restart(1500, recreate = true)
                }
                else -> restart(500, recreate = true)
            }
        }
    }
}
