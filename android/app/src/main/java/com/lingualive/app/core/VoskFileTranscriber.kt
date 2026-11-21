package com.lingualive.app.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.concurrent.CancellationException

/** Vidéo / audio à la demande 100 % hors-ligne avec Vosk (tous les Android). */
class VoskFileTranscriber(private val ctx: Context) {

    /** @param onSegment (texte, début en secondes) — appelé sur le thread principal. */
    suspend fun transcribe(uri: Uri, langCode: String, onSegment: (String, Double?) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val model = VoskModels.get(ctx, langCode)
            val (extractor, format) = AudioDecoder.open(ctx, uri)
            val main = Handler(Looper.getMainLooper())
            val rec = Recognizer(model, 16000f).apply { setWords(true) }
            var count = 0

            fun emit(json: String) {
                val o = runCatching { JSONObject(json) }.getOrNull() ?: return
                val text = o.optString("text").trim()
                if (text.isEmpty()) return
                val start = o.optJSONArray("result")?.optJSONObject(0)?.optDouble("start")
                count++
                main.post { onSegment(text, start) }
            }

            try {
                AudioDecoder.decode(extractor, format, targetRate = 16000) { bytes ->
                    if (!isActive) throw CancellationException()
                    if (rec.acceptWaveForm(bytes, bytes.size)) emit(rec.result)
                }
                emit(rec.finalResult)
            } finally {
                rec.close()
                extractor.release()
            }
            count
        }
}
