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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lingualive.app.core.Prefs
import com.lingualive.app.core.Segment
import com.lingualive.app.core.SessionClient
import com.lingualive.app.core.Speaker
import com.lingualive.app.core.TranslationManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Participant : rejoint avec un code (dans la salle, à distance, ou en webinaire),
 * choisit SA langue, lit les sous-titres traduits et/ou écoute l'audio traduit.
 */
@Composable
fun AttendeeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    KeepScreenOn()

    var codeInput by remember { mutableStateOf("") }
    val offline = remember { Prefs.offline(ctx) }
    var hostAddr by remember { mutableStateOf(Prefs.lastHost(ctx)) }
    var myLang by remember { mutableStateOf(Prefs.myLang(ctx)) }
    var joined by remember { mutableStateOf(false) }
    var audioOn by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Entre le code donné par le présentateur") }
    var partial by remember { mutableStateOf("") }
    var partialTr by remember { mutableStateOf("") }
    var showTranscript by remember { mutableStateOf(false) }
    val segments = remember { mutableStateListOf<Segment>() }
    val translator = remember { TranslationManager() }
    val speaker = remember { Speaker(ctx) }
    var partialJob by remember { mutableStateOf<Job?>(null) }

    fun addFinal(id: Long, text: String, lang: String, speak: Boolean) {
        if (segments.any { it.id == id }) return
        segments.add(Segment(id, text, lang))
        val target = myLang
        scope.launch {
            val t = runCatching { translator.translate(text, lang, target) }.getOrElse { text }
            val i = segments.indexOfFirst { it.id == id }
            if (i >= 0) segments[i] = segments[i].copy(translated = t)
            if (speak && audioOn) speaker.speak(t, target)
        }
    }

    val session = remember {
        SessionClient(
            onMessage = { msg ->
                when (msg.optString("type")) {
                    "joined" -> {
                        joined = true
                        status = "Connecté à la session ${msg.optString("code")}" +
                            if (msg.optBoolean("ended")) " (terminée)" else ""
                        val h = msg.optJSONArray("history")
                        if (h != null) for (i in 0 until h.length()) {
                            val s = h.getJSONObject(i)
                            addFinal(s.optLong("id"), s.optString("text"), s.optString("lang", "fr"), speak = false)
                        }
                    }
                    "segment" -> {
                        val text = msg.optString("text")
                        val lang = msg.optString("lang", "fr")
                        if (msg.optBoolean("final")) {
                            partial = ""; partialTr = ""
                            partialJob?.cancel()
                            addFinal(msg.optLong("id"), text, lang, speak = true)
                        } else {
                            partial = text
                            partialJob?.cancel()
                            val target = myLang
                            partialJob = scope.launch {
                                delay(200)
                                partialTr = runCatching { translator.translate(text, lang, target) }.getOrElse { text }
                            }
                        }
                    }
                    "ended" -> status = "Le présentateur a terminé la session"
                    "error" -> status = msg.optString("message")
                }
            },
            onState = { status = it }
        )
    }

    // Si le participant change de langue, on retraduit tout l'historique.
    LaunchedEffect(myLang) {
        Prefs.setMyLang(ctx, myLang)
        segments.toList().forEach { s ->
            val t = runCatching { translator.translate(s.text, s.lang, myLang) }.getOrElse { s.text }
            val i = segments.indexOfFirst { it.id == s.id }
            if (i >= 0) segments[i] = segments[i].copy(translated = t)
        }
    }

    DisposableEffect(Unit) {
        onDispose { session.close(); speaker.shutdown(); translator.close() }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TopBar("Rejoindre", onBack)

        if (!joined && offline) {
            HostPicker(hostAddr) { hostAddr = it }
            Spacer(Modifier.height(8.dp))
        }
        if (!joined && !offline) {
            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it.uppercase().take(6) },
                label = { Text("Code de session") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
        }
        LangPicker("Ma langue", myLang, { myLang = it })
        Spacer(Modifier.height(8.dp))

        if (!joined) {
            Button(
                enabled = if (offline) hostAddr.isNotBlank() else codeInput.length == 6,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                onClick = {
                    session.connect(Prefs.joinUrl(ctx, hostAddr)) {
                        session.send(JSONObject().put("type", "join").put("code", codeInput))
                    }
                }
            ) { Text("Rejoindre la session") }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = audioOn, onCheckedChange = { audioOn = it; if (!it) speaker.stop() })
            Text("Audio traduit (lecture à voix haute)", style = MaterialTheme.typography.bodyMedium)
        }
        StatusLine(status)
        Spacer(Modifier.height(8.dp))

        val last = segments.lastOrNull()
        SubtitleBox(
            main = if (partial.isNotBlank()) partialTr.ifBlank { partial } else last?.translated ?: last?.text.orEmpty(),
            secondary = if (partial.isNotBlank()) partial else last?.text?.takeIf { last.translated != it }
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
        TranscriptDialog("Session $codeInput", segments.toList(), myLang) { showTranscript = false }
    }
}
