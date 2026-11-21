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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingualive.app.core.Langs
import com.lingualive.app.core.LiveTranscriber
import com.lingualive.app.core.Prefs
import com.lingualive.app.core.Segment
import com.lingualive.app.core.SessionClient
import com.lingualive.app.core.Speaker
import com.lingualive.app.core.TranslationManager
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Réunion multilingue, chacun sur son téléphone (dans la même salle ou à distance).
 * Tout le monde peut parler dans sa langue ; chacun lit et entend les autres dans SA langue.
 */
@Composable
fun MeetingScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val askMic = rememberMicRequester()
    KeepScreenOn()

    var name by remember { mutableStateOf("") }
    var myLang by remember { mutableStateOf(Prefs.myLang(ctx)) }
    var codeInput by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<String?>(null) }
    var myId by remember { mutableIntStateOf(-1) }
    var members by remember { mutableIntStateOf(0) }
    var micOn by remember { mutableStateOf(false) }
    var audioOn by remember { mutableStateOf(true) }
    var myPartial by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Crée une réunion ou rejoins-en une avec son code") }
    var showTranscript by remember { mutableStateOf(false) }
    val segments = remember { mutableStateListOf<Segment>() }
    val othersTyping = remember { mutableStateMapOf<Int, String>() } // id -> "Nom : texte en cours"
    val translator = remember { TranslationManager() }
    val speaker = remember { Speaker(ctx) }

    fun addFinal(o: JSONObject, speak: Boolean) {
        val id = o.optLong("id")
        if (segments.any { it.id == id }) return
        val from = o.optInt("from")
        val mine = from == myId
        val text = o.optString("text")
        val lang = o.optString("lang", "fr")
        othersTyping.remove(from)
        segments.add(Segment(id, text, lang, speaker = if (mine) "Moi" else o.optString("name")))
        val target = myLang
        scope.launch {
            val t = runCatching { translator.translate(text, lang, target) }.getOrElse { text }
            val i = segments.indexOfFirst { it.id == id }
            if (i >= 0) segments[i] = segments[i].copy(translated = t)
            if (speak && !mine && audioOn) speaker.speak(t, target)
        }
    }

    val session = remember {
        SessionClient(
            onMessage = { o ->
                when (o.optString("type")) {
                    "joined" -> {
                        code = o.optString("code"); myId = o.optInt("you")
                        status = "Dans la réunion ${o.optString("code")}"
                        o.optJSONArray("history")?.let { h ->
                            for (i in 0 until h.length()) addFinal(h.getJSONObject(i), speak = false)
                        }
                    }
                    "presence" -> members = o.optInt("count")
                    "segment" -> {
                        if (o.optBoolean("final")) addFinal(o, speak = true)
                        else othersTyping[o.optInt("from")] = "${o.optString("name")} : ${o.optString("text")}"
                    }
                    "left" -> { othersTyping.remove(o.optInt("from")); status = "${o.optString("name")} est parti(e)" }
                    "ended" -> status = "Réunion terminée"
                    "error" -> status = o.optString("message")
                }
            },
            onState = { status = it }
        )
    }

    val transcriber = remember {
        LiveTranscriber(
            ctx,
            onPartial = { p ->
                myPartial = p
                session.send(SessionClient.segment(0, p, false, myLang))
            },
            onFinal = { f ->
                myPartial = ""
                session.send(SessionClient.segment(0, f, true, myLang)) // le serveur renvoie le segment à tous
            },
            onStatus = {}
        )
    }

    LaunchedEffect(myLang) {
        Prefs.setMyLang(ctx, myLang)
        if (micOn) transcriber.start(Langs.byCode(myLang).speechTag)
        segments.toList().forEach { s ->
            val t = runCatching { translator.translate(s.text, s.lang, myLang) }.getOrElse { s.text }
            val i = segments.indexOfFirst { it.id == s.id }
            if (i >= 0) segments[i] = segments[i].copy(translated = t)
        }
    }

    DisposableEffect(Unit) {
        onDispose { transcriber.stop(); session.close(); speaker.shutdown(); translator.close() }
    }
    BackHandler(onBack = onBack)

    val offline = remember { Prefs.offline(ctx) }
    var hostAddr by remember { mutableStateOf(Prefs.lastHost(ctx)) }
    var hosting by remember { mutableStateOf(false) }

    fun connectAnd(url: String, msg: JSONObject) {
        session.connect(url) { session.send(msg.put("name", name.ifBlank { "Invité" }).put("lang", myLang)) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TopBar("Réunion multilingue", onBack)

        if (code == null) {
            OutlinedTextField(name, { name = it.take(30) }, label = { Text("Mon prénom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            LangPicker("Ma langue (je parle et je lis)", myLang, { myLang = it })
            Spacer(Modifier.height(12.dp))
            Button(onClick = { hosting = true; connectAnd(Prefs.hostUrl(ctx), JSONObject().put("type", "create").put("mode", "meeting")) },
                modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Créer une réunion") }
            Spacer(Modifier.height(16.dp))
            if (offline) {
                HostPicker(hostAddr) { hostAddr = it }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = hostAddr.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                    onClick = { connectAnd(Prefs.joinUrl(ctx, hostAddr), JSONObject().put("type", "join").put("code", "")) }
                ) { Text("Rejoindre la réunion de ce téléphone") }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    codeInput, { codeInput = it.uppercase().take(6) },
                    label = { Text("Code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(enabled = codeInput.length == 6,
                    onClick = { connectAnd(Prefs.joinUrl(ctx, ""), JSONObject().put("type", "join").put("code", codeInput)) }) { Text("Rejoindre") }
            }
            Spacer(Modifier.height(8.dp))
            StatusLine(status)
            return@Column
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Code de la réunion", style = MaterialTheme.typography.labelMedium)
                    Text(code!!, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 5.sp)
                }
                Text("👥 $members", fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (offline && hosting) { HostAddressCard(); Spacer(Modifier.height(8.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LangPicker("Ma langue", myLang, { myLang = it }, Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Audio", style = MaterialTheme.typography.labelMedium)
                Switch(checked = audioOn, onCheckedChange = { audioOn = it; if (!it) speaker.stop() })
            }
        }
        StatusLine(status)
        Spacer(Modifier.height(8.dp))

        val last = segments.lastOrNull { it.speaker != "Moi" }
        val typing = othersTyping.values.lastOrNull()
        SubtitleBox(
            main = when {
                micOn && myPartial.isNotBlank() -> myPartial
                typing != null -> typing
                last != null -> "${last.speaker} : ${last.translated ?: last.text}"
                else -> ""
            },
            secondary = if (micOn) "🎤 Vous parlez…" else last?.text?.takeIf { last.translated != it }
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(segments.reversed(), key = { it.id }) { SegmentRow(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (micOn) Color(0xFFDC2626) else MaterialTheme.colorScheme.primary),
                onClick = {
                    if (micOn) { transcriber.stop(); micOn = false; myPartial = "" }
                    else askMic { transcriber.start(Langs.byCode(myLang).speechTag); micOn = true }
                }
            ) { Text(if (micOn) "■ Couper mon micro" else "🎤 Parler", fontSize = 16.sp) }
            OutlinedButton(onClick = { showTranscript = true }, modifier = Modifier.height(52.dp)) { Text("Transcription") }
        }
    }

    if (showTranscript) {
        TranscriptDialog("Réunion ${code ?: ""}", segments.toList(), myLang) { showTranscript = false }
    }
}
