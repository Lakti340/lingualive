package com.lingualive.app.ui

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.material3.TextButton
import com.lingualive.app.core.Packs
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.lingualive.app.core.Langs
import com.lingualive.app.core.LocalHost
import com.lingualive.app.core.Prefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Affiche l'adresse que les autres doivent utiliser pour se connecter à ce téléphone. */
@Composable
fun HostAddressCard() {
    val ips = remember { LocalHost.ipAddresses() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Hors-ligne : les autres se connectent à ce téléphone", style = MaterialTheme.typography.labelMedium)
            Text(ips.firstOrNull() ?: "Active ton Wi-Fi ou ton point d'accès", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Même Wi-Fi ou point d'accès de ce téléphone (pas besoin d'internet). Ils te trouvent aussi automatiquement.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Côté participant hors-ligne : trouve les téléphones hôtes à proximité, ou saisie manuelle de l'adresse. */
@Composable
fun HostPicker(address: String, onAddress: (String) -> Unit) {
    val ctx = LocalContext.current
    val found = remember { mutableStateListOf<Pair<String, String>>() }

    DisposableEffect(Unit) {
        val nsd = ctx.getSystemService(NsdManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        fun next() {
            if (resolving || nsd == null) return
            val s = queue.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; next() }
                }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val ip = serviceInfo.host?.hostAddress
                    main.post {
                        if (ip != null && found.none { it.second == ip }) found.add(serviceInfo.serviceName to ip)
                        resolving = false; next()
                    }
                }
            })
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { main.post { queue.addLast(serviceInfo); next() } }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                main.post { found.removeAll { it.first == serviceInfo.serviceName } }
            }
        }
        runCatching { nsd?.discoverServices(LocalHost.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        onDispose { runCatching { nsd?.stopServiceDiscovery(listener) } }
    }

    Column {
        Text("Téléphones hôtes à proximité", style = MaterialTheme.typography.labelMedium)
        if (found.isEmpty()) Text("Recherche sur le Wi-Fi local…", style = MaterialTheme.typography.bodySmall)
        found.forEach { (name, ip) ->
            OutlinedButton(onClick = { onAddress(ip) }, modifier = Modifier.fillMaxWidth()) {
                Text((if (ip == address) "✓ " else "") + "$name  ($ip)")
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = address, onValueChange = { onAddress(it.trim()) },
            label = { Text("…ou adresse de l'hôte (ex. 192.168.43.1)") },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Pack hors-ligne : TOUT se télécharge et se gère ici, dans l'appli.
 * Pour chaque langue : traduction (ML Kit), reconnaissance vocale (Vosk), voix de lecture (Piper).
 */
@Composable
fun OfflinePackScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { RemoteModelManager.getInstance() }
    val translationInstalled = remember { mutableStateListOf<String>() }
    val selected = remember { mutableStateListOf("fr", "en", "ar") }
    var refreshTick by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var step by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0f) }
    var status by remember { mutableStateOf("") }

    suspend fun refresh() {
        val models = runCatching { manager.getDownloadedModels(TranslateRemoteModel::class.java).await() }.getOrNull()
        translationInstalled.clear()
        models?.forEach { translationInstalled.add(it.language) }
        refreshTick++
    }
    LaunchedEffect(Unit) { refresh() }
    BackHandler(onBack = { job?.cancel(); onBack() })

    fun trOk(code: String) = TranslateLanguage.fromLanguageTag(code)?.let { it in translationInstalled } ?: false

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TopBar("Pack hors-ligne", { job?.cancel(); onBack() })
        Text("Télécharge ici, une seule fois avec internet, tout ce qu'il faut pour chaque langue. " +
            "Aucun réglage du téléphone n'est nécessaire.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        key(refreshTick) { StatusLine("Espace utilisé par les langues : ${Packs.usedMb(ctx)} Mo (+ traductions)") }
        Spacer(Modifier.height(8.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Langs.all.forEach { l ->
                val pack = Packs.all[l.code]
                key(refreshTick, l.code) {
                    val stt = Packs.sttInstalled(ctx, l.code)
                    val tts = Packs.ttsInstalled(ctx, l.code)
                    val tr = trOk(l.code)
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = l.code in selected, enabled = !busy,
                                onCheckedChange = { if (it) selected.add(l.code) else selected.remove(l.code) })
                            Column(Modifier.weight(1f)) {
                                Text(l.name, fontWeight = FontWeight.SemiBold)
                                Text(
                                    (if (tr) "✓" else "○") + " Traduction   " +
                                        (if (stt) "✓" else "○") + " Écoute" + (pack?.let { " (${it.sttMb} Mo)" } ?: "") + "   " +
                                        if (pack?.tts == null) "· Voix : celle du téléphone"
                                        else (if (tts) "✓" else "○") + " Voix (${pack.ttsMb} Mo)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (l.code == "ar") Text("Écoute optimisée pour l'arabe tunisien", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            if (stt || tts || tr) TextButton(enabled = !busy, onClick = {
                                scope.launch {
                                    Packs.delete(ctx, l.code)
                                    TranslateLanguage.fromLanguageTag(l.code)?.takeIf { l.code != "en" }?.let {
                                        runCatching { manager.deleteDownloadedModel(TranslateRemoteModel.Builder(it).build()).await() }
                                    }
                                    refresh()
                                }
                            }) { Text("Suppr.") }
                        }
                    }
                }
            }
        }

        if (busy) {
            Text(step, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        if (status.isNotBlank()) StatusLine(status)
        Spacer(Modifier.height(6.dp))
        if (!busy) Button(enabled = selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp), onClick = {
            busy = true; status = ""
            job = scope.launch {
                val errors = mutableListOf<String>()
                for (code in selected.toList()) {
                    val name = Langs.byCode(code).name
                    val pack = Packs.all[code]
                    // 1. Traduction
                    TranslateLanguage.fromLanguageTag(code)?.let { ml ->
                        if (!trOk(code)) {
                            step = "$name : traduction…"; progress = 0f
                            runCatching { manager.download(TranslateRemoteModel.Builder(ml).build(), DownloadConditions.Builder().build()).await() }
                                .onFailure { errors += "$name (traduction)" }
                        }
                    }
                    // 2. Reconnaissance vocale intégrée
                    if (pack?.stt != null && !Packs.sttInstalled(ctx, code)) {
                        step = "$name : écoute (${pack.sttMb} Mo)…"
                        runCatching { Packs.installStt(ctx, code) { progress = it } }
                            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; errors += "$name (écoute)" }
                    }
                    // 3. Voix de lecture intégrée
                    if (pack?.tts != null && !Packs.ttsInstalled(ctx, code)) {
                        step = "$name : voix (${pack.ttsMb} Mo)…"
                        runCatching { Packs.installTts(ctx, code) { progress = it } }
                            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; errors += "$name (voix)" }
                    }
                    refresh()
                }
                status = if (errors.isEmpty()) "Terminé : les langues cochées marchent maintenant sans internet."
                         else "Terminé avec des erreurs : ${errors.joinToString()}. Réessaie avec une meilleure connexion."
                busy = false
            }
            job?.invokeOnCompletion { busy = false }
        }) { Text("Télécharger les langues cochées") }
        else OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { job?.cancel(); status = "Téléchargement annulé" }) { Text("Annuler") }
    }
}
