package com.lingualive.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lingualive.app.core.Api
import com.lingualive.app.core.Langs
import com.lingualive.app.core.LocalSummarizer
import com.lingualive.app.core.Prefs
import com.lingualive.app.core.Segment
import kotlinx.coroutines.launch

/** Transcription complète + résumé + export, dans la langue du lecteur. */
@Composable
fun TranscriptDialog(title: String, segments: List<Segment>, lang: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var translatedView by remember { mutableStateOf(true) }
    var summary by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    val lines = segments.map { if (translatedView) it.translated ?: it.text else it.text }
    val fullText = lines.joinToString("\n")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                TopBar("Transcription & résumé", onDismiss)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(translatedView, { translatedView = true }, label = { Text("Traduit (${Langs.byCode(lang).name})") })
                    FilterChip(!translatedView, { translatedView = false }, label = { Text("Original") })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !loading && segments.isNotEmpty(), onClick = {
                        loading = true; note = null
                        scope.launch {
                            summary = if (Prefs.offline(ctx)) {
                                note = "Résumé automatique (hors-ligne) :"
                                LocalSummarizer.summarize(lines)
                            } else try {
                                Api.summarize(Prefs.server(ctx), fullText, if (translatedView) lang else segments.first().lang)
                            } catch (e: Exception) {
                                note = "Résumé IA indisponible (${e.message}). Résumé automatique local :"
                                LocalSummarizer.summarize(lines)
                            }
                            loading = false
                        }
                    }) { Text("Générer le résumé") }
                    OutlinedButton(enabled = segments.isNotEmpty(), onClick = {
                        val export = buildString {
                            appendLine(title); appendLine()
                            summary?.let { appendLine("RÉSUMÉ"); appendLine(it); appendLine() }
                            appendLine("TRANSCRIPTION"); append(fullText)
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, title)
                            putExtra(Intent.EXTRA_TEXT, export)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Exporter la transcription"))
                    }) { Text("Partager") }
                }
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    if (loading) CircularProgressIndicator()
                    if (summary != null) {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Résumé", style = MaterialTheme.typography.titleMedium)
                                note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                Spacer(Modifier.height(4.dp))
                                Text(summary!!)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text("Transcription", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    if (segments.isEmpty()) Text("Aucun texte pour le moment.")
                    segments.forEach { seg ->
                        val shown = seg.copy(translated = if (translatedView) seg.translated else null)
                        SegmentRow(shown, showOriginal = false)
                    }
                }
            }
        }
    }
}
