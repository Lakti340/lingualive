package com.lingualive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lingualive.app.core.Prefs
import com.lingualive.app.ui.AttendeeScreen
import com.lingualive.app.ui.ConversationScreen
import com.lingualive.app.ui.MeetingScreen
import com.lingualive.app.ui.OfflinePackScreen
import com.lingualive.app.core.LocalHost
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.lingualive.app.ui.PresenterScreen
import com.lingualive.app.ui.VodScreen

class MainActivity : ComponentActivity() {
    override fun onDestroy() {
        if (isFinishing) LocalHost.stop()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF4F46E5), secondary = Color(0xFF0EA5E9))) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) { App() }
            }
        }
    }
}

@Composable
fun App() {
    var screen by rememberSaveable { mutableStateOf("home") }
    val back = { screen = "home" }
    when (screen) {
        "presenter" -> PresenterScreen(back)
        "attendee" -> AttendeeScreen(back)
        "vod" -> VodScreen(back)
        "conversation" -> ConversationScreen(back)
        "meeting" -> MeetingScreen(back)
        "pack" -> OfflinePackScreen(back)
        else -> HomeScreen { screen = it }
    }
}

@Composable
fun HomeScreen(go: (String) -> Unit) {
    val ctx = LocalContext.current
    var server by remember { mutableStateOf(Prefs.server(ctx)) }
    var offline by remember { mutableStateOf(Prefs.offline(ctx)) }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())) {
        Text("LinguaLive", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            "Sous-titres, audio traduit, transcriptions et résumés en temps réel.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        Text("Dans les deux sens", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        ModeCard("💬  Conversation face à face", "Un seul téléphone posé entre deux personnes. Chacun appuie sur son micro et parle sa langue ; l'autre lit et entend la traduction.") { go("conversation") }
        ModeCard("👥  Réunion multilingue", "Chacun sur son téléphone, sur place ou à distance. Tout le monde peut parler ; chacun lit et entend les autres dans sa langue.") { go("meeting") }
        Spacer(Modifier.height(12.dp))
        Text("Un orateur → le public", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        ModeCard("🎤  Présenter", "Présentiel, hybride ou webinaire. Parlez dans votre langue : l'appli transcrit et diffuse un code de session. Marche aussi seul, sur un seul téléphone.") { go("presenter") }
        ModeCard("🎧  Rejoindre une session", "Dans la salle ou à distance : entrez le code, choisissez votre langue, lisez les sous-titres ou écoutez l'audio traduit.") { go("attendee") }
        ModeCard("🎬  Vidéo à la demande", "Importez un fichier audio ou vidéo pour obtenir la transcription, la traduction et un résumé.") { go("vod") }

        Spacer(Modifier.height(20.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("📴  Mode hors-ligne", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (offline) "Aucun internet : ce téléphone sert de serveur sur le Wi-Fi local ou son point d'accès."
                         else "Utilise un serveur en ligne (résumés IA, vidéos longues).",
                        style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = offline, onCheckedChange = { offline = it; Prefs.setOffline(ctx, it) })
            }
        }
        ModeCard("📦  Pack hors-ligne", "Tout se gère ici, dans l'appli : télécharge une fois chaque langue (traduction, écoute, voix). Aucun réglage du téléphone.") { go("pack") }
        Spacer(Modifier.height(8.dp))
        if (!offline) OutlinedTextField(
            value = server,
            onValueChange = { server = it; Prefs.setServer(ctx, it) },
            label = { Text("Adresse du serveur (WebSocket)") },
            supportingText = { Text("Ex. ws://192.168.1.20:8080/ws ou wss://mon-domaine.com/ws") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ModeCard(title: String, desc: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
