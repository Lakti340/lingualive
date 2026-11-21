package com.lingualive.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingualive.app.core.IdCounter
import com.lingualive.app.core.Langs
import com.lingualive.app.core.LiveTranscriber
import com.lingualive.app.core.Segment
import com.lingualive.app.core.Speaker
import com.lingualive.app.core.TranslationManager
import kotlinx.coroutines.launch

/**
 * Conversation face à face sur UN téléphone posé entre deux personnes.
 * La moitié du haut est retournée pour la personne d'en face.
 * Chacun appuie sur son micro, parle, et l'autre lit / entend la traduction dans sa langue.
 */
@Composable
fun ConversationScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val askMic = rememberMicRequester()
    KeepScreenOn()

    var langA by remember { mutableStateOf("fr") }   // personne en bas (toi)
    var langB by remember { mutableStateOf("en") }   // personne en face (écran retourné)
    var active by remember { mutableIntStateOf(0) }  // 0 = personne, 1 = A parle, 2 = B parle
    var partialA by remember { mutableStateOf("") }
    var partialB by remember { mutableStateOf("") }
    var forA by remember { mutableStateOf("") }      // ce que B a dit, traduit pour A
    var forB by remember { mutableStateOf("") }      // ce que A a dit, traduit pour B
    var ownA by remember { mutableStateOf("") }
    var ownB by remember { mutableStateOf("") }
    var showTranscript by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<Segment>() }
    val ids = remember { IdCounter() }
    val translator = remember { TranslationManager() }
    val speaker = remember { Speaker(ctx) }

    val transcriber = remember {
        LiveTranscriber(
            ctx,
            onPartial = { p -> if (active == 1) partialA = p else if (active == 2) partialB = p },
            onFinal = { text ->
                val who = active
                val from = if (who == 1) langA else langB
                val to = if (who == 1) langB else langA
                val a = langA
                if (who == 1) { ownA = text; partialA = "" } else { ownB = text; partialB = "" }
                val id = ids.next()
                scope.launch {
                    val t = runCatching { translator.translate(text, from, to) }.getOrElse { text }
                    if (who == 1) forB = t else forA = t
                    // Pour la transcription : tout est aussi disponible dans la langue de A.
                    history.add(Segment(id, text, from, translated = if (who == 1) text else t,
                        speaker = if (who == 1) Langs.byCode(a).name else Langs.byCode(from).name))
                    speaker.speak(t, to, flush = true)
                }
            },
            onStatus = {}
        ).also { it.onStopped = { active = 0; partialA = ""; partialB = "" } }
    }

    fun talk(who: Int) {
        if (active == who) { transcriber.stop(); active = 0; return }
        askMic {
            speaker.stop()
            transcriber.stop()
            active = who
            val lang = if (who == 1) langA else langB
            transcriber.start(Langs.byCode(lang).speechTag, continuous = false)
        }
    }

    DisposableEffect(Unit) {
        onDispose { transcriber.stop(); speaker.shutdown(); translator.close() }
    }
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize()) {
        // Personne B (en face) : moitié retournée
        Box(Modifier.weight(1f).fillMaxWidth().rotate(180f)) {
            HalfPanel(
                lang = langB, onLang = { langB = it },
                listening = active == 2, partial = partialB,
                received = forB, own = ownB,
                accent = Color(0xFF0EA5E9),
                onMic = { talk(2) }
            )
        }
        Row(
            Modifier.fillMaxWidth().background(Color(0xFF111827)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("← Retour", color = Color.White) }
            TextButton(onClick = { showTranscript = true }) { Text("Transcription", color = Color.White) }
        }
        // Personne A (toi)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            HalfPanel(
                lang = langA, onLang = { langA = it },
                listening = active == 1, partial = partialA,
                received = forA, own = ownA,
                accent = Color(0xFF4F46E5),
                onMic = { talk(1) }
            )
        }
    }

    if (showTranscript) {
        TranscriptDialog("Conversation", history.toList(), langA) { showTranscript = false }
    }
}

@Composable
private fun HalfPanel(
    lang: String,
    onLang: (String) -> Unit,
    listening: Boolean,
    partial: String,
    received: String,
    own: String,
    accent: Color,
    onMic: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        LangPicker("Ma langue", lang, onLang)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(Color(0xFFF3F4F6), MaterialTheme.shapes.large)
                .padding(14.dp)
        ) {
            Column(Modifier.align(Alignment.BottomStart)) {
                if (own.isNotBlank() && !listening) {
                    Text("Vous : $own", fontSize = 13.sp, color = Color(0xFF6B7280))
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    when {
                        listening -> partial.ifBlank { "Je vous écoute…" }
                        received.isNotBlank() -> received
                        else -> "Appuyez sur le micro et parlez"
                    },
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    color = if (listening || received.isNotBlank()) Color(0xFF111827) else Color(0xFF9CA3AF)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onMic,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (listening) Color(0xFFDC2626) else accent),
            modifier = Modifier.size(72.dp).align(Alignment.CenterHorizontally)
        ) { Text(if (listening) "■" else "🎤", fontSize = 26.sp, textAlign = TextAlign.Center) }
    }
}
