package com.lingualive.app.core

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.vosk.Model
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Packs de langue gérés ENTIÈREMENT par l'appli (aucun réglage du téléphone) :
 *  - reconnaissance vocale : modèles Vosk (fonctionnent sur tous les Android, sans Google)
 *  - voix de lecture : voix Piper exécutées par sherpa-onnx
 *  - traduction : ML Kit (téléchargée depuis l'écran « Pack hors-ligne »)
 * Tout est rangé dans le stockage privé de l'appli.
 */
object Packs {
    data class Pack(
        val code: String,
        val stt: String?, val sttMb: Int,     // nom du modèle Vosk
        val tts: String?, val ttsMb: Int,     // nom de la voix Piper
    )

    val all: Map<String, Pack> = listOf(
        Pack("fr", "vosk-model-small-fr-0.22", 41, "fr_FR-siwis-medium", 65),
        Pack("en", "vosk-model-small-en-us-0.15", 40, "en_US-amy-medium", 65),
        // Modèle spécial arabe TUNISIEN (LinTO)
        Pack("ar", "vosk-model-small-ar-tn-0.1-linto", 158, "ar_JO-kareem-medium", 65),
        Pack("es", "vosk-model-small-es-0.42", 39, "es_ES-davefx-medium", 65),
        Pack("de", "vosk-model-small-de-0.15", 45, "de_DE-thorsten-medium", 65),
        Pack("it", "vosk-model-small-it-0.22", 48, "it_IT-riccardo-x_low", 30),
        Pack("pt", "vosk-model-small-pt-0.3", 31, "pt_PT-tugao-medium", 65),
        Pack("tr", "vosk-model-small-tr-0.3", 35, "tr_TR-dfki-medium", 65),
        Pack("nl", "vosk-model-small-nl-0.22", 39, "nl_BE-nathalie-medium", 65),
        Pack("ru", "vosk-model-small-ru-0.22", 45, "ru_RU-irina-medium", 65),
        Pack("zh", "vosk-model-small-cn-0.22", 42, "zh_CN-huayan-medium", 65),
        Pack("ja", "vosk-model-small-ja-0.22", 48, null, 0),
        Pack("ko", "vosk-model-small-ko-0.22", 82, null, 0),
        Pack("hi", "vosk-model-small-hi-0.22", 42, null, 0),
    ).associateBy { it.code }

    private fun root(ctx: Context) = File(ctx.filesDir, "models")
    fun sttDir(ctx: Context, code: String) = File(root(ctx), "stt/$code")
    fun ttsDir(ctx: Context, code: String) = File(root(ctx), "tts/$code")
    fun sttInstalled(ctx: Context, code: String?) = code != null && File(sttDir(ctx, code), ".ok").exists()
    fun ttsInstalled(ctx: Context, code: String?) = code != null && File(ttsDir(ctx, code), ".ok").exists()
    fun usedMb(ctx: Context): Long =
        root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)

    private fun sttUrl(name: String) = "https://alphacephei.com/vosk/models/$name.zip"
    private fun ttsUrl(name: String) =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$name.tar.bz2"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Télécharge et installe la reconnaissance vocale d'une langue. */
    suspend fun installStt(ctx: Context, code: String, onProgress: (Float) -> Unit) {
        val name = all[code]?.stt ?: return
        val tmp = File(ctx.cacheDir, "stt-$code.zip")
        download(sttUrl(name), tmp, onProgress)
        withContext(Dispatchers.IO) {
            val dir = sttDir(ctx, code)
            VoskModels.evict(code)
            dir.deleteRecursively(); dir.mkdirs()
            tmp.inputStream().use { unzipStripTop(it, dir) }
            tmp.delete()
            File(dir, ".ok").writeText(name)
        }
    }

    /** Télécharge et installe la voix de lecture d'une langue. */
    suspend fun installTts(ctx: Context, code: String, onProgress: (Float) -> Unit) {
        val name = all[code]?.tts ?: return
        val tmp = File(ctx.cacheDir, "tts-$code.tar.bz2")
        download(ttsUrl(name), tmp, onProgress)
        withContext(Dispatchers.IO) {
            val dir = ttsDir(ctx, code)
            PiperVoices.evict(code)
            dir.deleteRecursively(); dir.mkdirs()
            tmp.inputStream().use { untarBz2StripTop(it, dir) }
            tmp.delete()
            File(dir, ".ok").writeText(name)
        }
    }

    fun delete(ctx: Context, code: String) {
        VoskModels.evict(code); PiperVoices.evict(code)
        sttDir(ctx, code).deleteRecursively()
        ttsDir(ctx, code).deleteRecursively()
    }

    private suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) error("Téléchargement impossible (HTTP ${r.code})")
            val body = r.body ?: error("Réponse vide")
            val total = body.contentLength().coerceAtLeast(1)
            var done = 0L
            var lastReport = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            lastReport = done
                            val p = (done.toFloat() / total).coerceIn(0f, 1f)
                            withContext(Dispatchers.Main) { onProgress(p) }
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) { onProgress(1f) }
        }
    }

    /** Extrait une archive en retirant le dossier racine (protégé contre les chemins malveillants). */
    private fun safeTarget(outDir: File, entryName: String): File? {
        val parts = entryName.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (parts.size <= 1) return null
        val f = File(outDir, parts.drop(1).joinToString("/"))
        return if (f.canonicalPath.startsWith(outDir.canonicalPath + File.separator)) f else null
    }

    private fun unzipStripTop(input: InputStream, outDir: File) {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val target = safeTarget(outDir, e.name) ?: continue
                if (e.isDirectory) target.mkdirs()
                else { target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
            }
        }
    }

    private fun untarBz2StripTop(input: InputStream, outDir: File) {
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(input))).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val target = safeTarget(outDir, e.name) ?: continue
                if (e.isDirectory) target.mkdirs()
                else { target.parentFile?.mkdirs(); target.outputStream().use { tar.copyTo(it) } }
            }
        }
    }
}

/** Modèles Vosk chargés en mémoire (le chargement prend 1 à 3 secondes, on le garde). */
object VoskModels {
    private val cache = HashMap<String, Model>()

    @Synchronized
    fun get(ctx: Context, code: String): Model =
        cache.getOrPut(code) {
            // On ne garde que 2 langues en mémoire pour économiser la RAM.
            if (cache.size >= 2) cache.keys.first().let { k -> cache.remove(k)?.close() }
            Model(Packs.sttDir(ctx, code).absolutePath)
        }

    @Synchronized
    fun evict(code: String) { cache.remove(code)?.close() }
}

/** Voix Piper chargées par sherpa-onnx. */
object PiperVoices {
    private val cache = HashMap<String, OfflineTts>()

    @Synchronized
    fun get(ctx: Context, code: String): OfflineTts? {
        cache[code]?.let { return it }
        val dir = Packs.ttsDir(ctx, code)
        val onnx = dir.listFiles()?.firstOrNull { it.name.endsWith(".onnx") } ?: return null
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = onnx.absolutePath,
                    tokens = File(dir, "tokens.txt").absolutePath,
                    dataDir = File(dir, "espeak-ng-data").absolutePath,
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
        )
        if (cache.size >= 2) cache.keys.first().let { k -> cache.remove(k)?.release() }
        return OfflineTts(assetManager = null, config = config).also { cache[code] = it }
    }

    @Synchronized
    fun evict(code: String) { cache.remove(code)?.release() }
}
