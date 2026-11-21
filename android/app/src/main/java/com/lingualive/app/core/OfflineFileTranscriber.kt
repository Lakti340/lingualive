package com.lingualive.app.core

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Vidéo / audio à la demande SANS internet (Android 13+).
 * Le fichier est décodé en audio brut (PCM) sur le téléphone, puis envoyé au moteur
 * de reconnaissance vocale « sur l'appareil » d'Android.
 */
@RequiresApi(33)
class OfflineFileTranscriber(private val context: Context) {

    fun isAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    suspend fun transcribe(uri: Uri, speechTag: String, onSegment: (String) -> Unit): Int =
        suspendCancellableCoroutine { cont ->
            val (extractor, format) = try {
                AudioDecoder.open(context, uri)
            } catch (e: Exception) {
                cont.resumeWithException(e); return@suspendCancellableCoroutine
            }
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)

            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            var count = 0
            var finished = false
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)

            fun finish(error: Exception? = null) {
                if (finished) return
                finished = true
                runCatching { recognizer.destroy() }
                runCatching { readSide.close() }
                if (error != null && count == 0) cont.resumeWithException(error) else cont.resume(count)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onSegmentResults(segmentResults: Bundle) {
                    segmentResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?.let { count++; onSegment(it) }
                }

                override fun onEndOfSegmentedSession() = finish()

                override fun onResults(results: Bundle?) {
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?.let { count++; onSegment(it) }
                    finish()
                }

                override fun onError(error: Int) = finish(
                    IllegalStateException(
                        if (error == 12 || error == 13) "Langue non installée pour la reconnaissance hors-ligne (voir « Pack hors-ligne »)"
                        else "Erreur de reconnaissance ($error)"
                    )
                )
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechTag)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, rate)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            recognizer.startListening(intent)

            // Décodage du fichier -> PCM 16 bits mono, écrit dans le tuyau lu par le moteur vocal.
            thread(name = "decode-audio") {
                ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { out ->
                    runCatching { AudioDecoder.decode(extractor, format, targetRate = null) { out.write(it) } }
                }
                extractor.release()
            }

            cont.invokeOnCancellation { runCatching { recognizer.cancel() }; finish() }
        }
}
