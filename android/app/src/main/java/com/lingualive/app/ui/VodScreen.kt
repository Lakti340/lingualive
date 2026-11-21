package com.lingualive.app.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.os.Build
import com.lingualive.app.core.Api
import com.lingualive.app.core.Langs
import com.lingualive.app.core.OfflineFileTranscriber
import com.lingualive.app.core.Packs
import com.lingualive.app.core.VoskFileTranscriber
import com.lingualive.app.core.Prefs
import com.lingualive.app.core.Segment
import com.lingualive.app.core.Speaker
import com.lingualive.app.core.TranslationManager
import kotlinx.coroutines.launch

/** Vidéo / audio à la demande : on envoie le fichier au serveur pour transcription, puis on traduit. */
@Composable
fun VodScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<Uri?>(null) }
    var srcLang by remember { mutableStateOf("fr") }
    var myLang by remember { mutableStateOf(Prefs.myLang(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(if (Prefs.offline(ctx)) "Choisis un fichier audio ou vidéo (traité sur le téléphone)" else "Choisis un fichier audio ou vidéo (25 Mo max)") }
    var showTranscript by remember { mutableStateOf(false) }
    val segments = remember { mutableStateListOf<Segment>() }
    val translator = remember { TranslationManager() }
    val speaker = remember { Speaker(ctx) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) { file = uri; status = "Fichier prêt : ${uri.lastPathSegment}" }
    }

    DisposableEffect(Unit) { onDispose { speaker.shutdown(); translator.close() } }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TopBar("Vidéo à la demande", onBack)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LangPicker("Langue de la vidéo", srcLang, { srcLang = it }, Modifier.weight(1f))
            LangPicker("Traduire en", myLang, { myLang = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { picker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
            Text(if (file == null) "Choisir un fichier" else "Changer de fichier")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            enabled = file != null && !busy,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            onClick = click@{
                val uri = file ?: return@click
                busy = true; status = "Envoi et transcription en cours…"
                scope.launch {
                    try {
                        val result = if (Prefs.offline(ctx)) {
                            segments.clear()
                            if (Packs.sttInstalled(ctx, srcLang)) {
                                // Moteur Vosk intégré à l'appli : tous les Android, plus rapide que la durée de la vidéo
                                status = "Transcription sur le téléphone…"
                                VoskFileTranscriber(ctx).transcribe(uri, srcLang) { text, start ->
                                    segments.add(Segment(segments.size + 1L, text, srcLang, startSec = start))
                                    status = "Transcription sur le téléphone… ${segments.size} phrases"
                                }
                            } else if (Build.VERSION.SDK_INT >= 33 && OfflineFileTranscriber(ctx).isAvailable()) {
                                status = "Transcription sur le téléphone (aussi long que la vidéo)…"
                                OfflineFileTranscriber(ctx).transcribe(uri, Langs.byCode(srcLang).speechTag) { text ->
                                    segments.add(Segment(segments.size + 1L, text, srcLang))
                                }
                            } else error("Installe « ${Langs.byCode(srcLang).name} » dans Pack hors-ligne pour transcrire sans internet")
                            segments.toList()
                        } else Api.transcribe(ctx, Prefs.server(ctx), uri, srcLang)
                        segments.clear(); segments.addAll(result)
                        status = "Traduction…"
                        result.forEach { s ->
                            val t = runCatching { translator.translate(s.text, srcLang, myLang) }.getOrElse { s.text }
                            val i = segments.indexOfFirst { it.id == s.id }
                            if (i >= 0) segments[i] = segments[i].copy(translated = t)
                        }
                        status = "${result.size} segments transcrits et traduits"
                    } catch (e: Exception) {
                        status = "Erreur : ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            }
        ) { Text("Transcrire et traduire") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
        StatusLine(status)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedButton(enabled = segments.isNotEmpty(), onClick = {
                speaker.stop()
                segments.forEach { speaker.speak(it.translated ?: it.text, myLang) }
            }) { Text("▶ Audio traduit") }
            OutlinedButton(enabled = segments.isNotEmpty(), onClick = { speaker.stop() }) { Text("■ Stop") }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(segments, key = { it.id }) { SegmentRow(it) }
        }
        OutlinedButton(onClick = { showTranscript = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Transcription & résumé")
        }
    }

    if (showTranscript) {
        TranscriptDialog("Vidéo à la demande", segments.toList(), myLang) { showTranscript = false }
    }
}
