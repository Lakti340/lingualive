package com.lingualive.app.core

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Appels HTTP au serveur : résumé (IA) et transcription de fichiers (vidéo à la demande). */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    /** ws://hote:8080/ws -> http://hote:8080 */
    fun httpBase(wsUrl: String): String =
        wsUrl.trim()
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://")
            .removeSuffix("/")
            .removeSuffix("/ws")

    suspend fun summarize(serverWsUrl: String, text: String, lang: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("text", text).put("lang", lang).toString()
                .toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("${httpBase(serverWsUrl)}/api/summarize").post(body).build()
            client.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                val json = runCatching { JSONObject(s) }.getOrNull()
                if (!r.isSuccessful) error(json?.optString("error")?.ifBlank { null } ?: "HTTP ${r.code}")
                json?.optString("summary").orEmpty()
            }
        }

    suspend fun transcribe(context: Context, serverWsUrl: String, uri: Uri, lang: String): List<Segment> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "media"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Impossible de lire le fichier")

            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("lang", lang)
                .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType()))
                .build()
            val req = Request.Builder().url("${httpBase(serverWsUrl)}/api/transcribe").post(body).build()
            client.newCall(req).execute().use { r ->
                val s = r.body?.string().orEmpty()
                val json = runCatching { JSONObject(s) }.getOrNull()
                if (!r.isSuccessful) error(json?.optString("error")?.ifBlank { null } ?: "HTTP ${r.code}")
                val arr = json?.optJSONArray("segments") ?: return@use emptyList()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Segment(
                        id = i + 1L,
                        text = o.optString("text"),
                        lang = lang,
                        startSec = o.optDouble("start", 0.0),
                    )
                }
            }
        }
}

/** Résumé de secours, sans IA ni réseau : garde les phrases les plus représentatives. */
object LocalSummarizer {
    fun summarize(sentences: List<String>, max: Int = 6): String {
        val clean = sentences.map { it.trim() }.filter { it.length > 15 }
        if (clean.isEmpty()) return "Pas assez de contenu pour un résumé."
        if (clean.size <= max) return clean.joinToString("\n") { "• $it" }
        val freq = HashMap<String, Int>()
        clean.forEach { s -> words(s).forEach { freq[it] = (freq[it] ?: 0) + 1 } }
        return clean.mapIndexed { i, s ->
            val w = words(s)
            Triple(i, s, if (w.isEmpty()) 0.0 else w.sumOf { freq[it] ?: 0 }.toDouble() / w.size)
        }
            .sortedByDescending { it.third }
            .take(max)
            .sortedBy { it.first }
            .joinToString("\n") { "• ${it.second}" }
    }

    private fun words(s: String) =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 3 }
}
