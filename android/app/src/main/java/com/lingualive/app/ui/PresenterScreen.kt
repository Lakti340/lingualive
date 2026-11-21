package com.lingualive.app.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingualive.app.core.IdCounter
import com.lingualive.app.core.Langs
import com.lingualive.app.core.LiveTranscriber
import com.lingualive.app.core.Prefs
import com.lingualive.app.core.Segment
import com.lingualive.app.core.SessionClient
import com.lingualive.app.core.TranslationManager
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Présentateur : parle dans sa langue, l'appli transcrit et diffuse aux participants.
 * Fonctionne aussi seul (sans serveur) comme sous-titreur/traducteur sur un seul appareil.
 */
@Composable
fun PresenterScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val askMic = rememberMicRequester()
    KeepScreenOn()

    var spokenLang by remember { mutableStateOf("fr") }
    var previewLang by remember { mutableStateOf("en") }
    var running by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Prêt") }
    var code by remember { mutableStateOf<String?>(null) }
    var listeners by remember { mutableIntStateOf(0) }
    var showTranscript by remember { mutableStateOf(false) }
    val segments = remember { mutableStateListOf<Segment>() }
    val ids = remember { IdCounter() }
    val translator = remember { TranslationManager() }

    val session = remember {
        SessionClient(
            onMessage = { msg ->
                when (msg.optString("type")) {
                    "created" -> code = msg.optString("code")
                    "stats" -> listeners = msg.optInt("listeners")
                }
            },
            onState = { status = it }
        )
    }

    val transcriber = remember {
        LiveTranscriber(
            ctx,
            onPartial = { p ->
                partial = p
                session.send(SessionClient.segment(0, p, false, spokenLang))
            },
            onFinal = { f ->
                partial = ""
                val id = ids.next()
                val lang = spokenLang
                segments.add(Segment(id, f, lang))
                session.send(SessionClient.segment(id, f, true, lang))
                scope.launch {
                    val t = runCatching { translator.translate(f, lang, previewLang) }.getOrNull()
                    val i = segments.indexOfFirst { it.id == id }
                    if (i >= 0 && t != null) segments[i] = segments[i].copy(translated = t)
                }
            },
            onStatus = { status = it }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            transcriber.stop()
            session.send(JSONObject().put("type", "end"))
            session.close()
            translator.close()
        }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TopBar("Présenter", onBack)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LangPicker("Je parle en", spokenLang, {
                spokenLang = it
                if (running) transcriber.start(Langs.byCode(it).speechTag)
            }, Modifier.weight(1f))
            LangPicker("Aperçu traduit en", previewLang, { previewLang = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))

        Button(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = if (running) ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)) else ButtonDefaults.buttonColors(),
            onClick = {
                if (running) {
                    transcriber.stop(); running = false
                } else askMic {
                    transcriber.start(Langs.byCode(spokenLang).speechTag)
                    running = true
                    if (code == null) {
                        session.connect(Prefs.hostUrl(ctx)) {
                            session.send(JSONObject().put("type", "create").put("lang", spokenLang))
                        }
                    }
                }
            }
        ) { Text(if (running) "■ Arrêter" else "● Démarrer la session", fontSize = 17.sp) }

        Spacer(Modifier.height(8.dp))
        if (code != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Code à partager avec les participants", style = MaterialTheme.typography.labelMedium)
                    Text(code!!, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp)
                    Text("$listeners participant(s) connecté(s)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (Prefs.offline(ctx)) { HostAddressCard(); Spacer(Modifier.height(8.dp)) }
        }
        StatusLine(status)
        Spacer(Modifier.height(8.dp))

        val last = segments.lastOrNull()
        SubtitleBox(
            main = if (partial.isNotBlank()) partial else last?.translated ?: last?.text.orEmpty(),
            secondary = if (partial.isBlank()) last?.text?.takeIf { last.translated != null } else null
        )
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(segments.reversed(), key = { it.id }) { SegmentRow(it) }
        }
        OutlinedButton(onClick = { showTranscript = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Transcription & résumé")
        }
    }

    if (showTranscript) {
        TranscriptDialog("Session ${code ?: "locale"}", segments.toList(), previewLang) { showTranscript = false }
    }
}
