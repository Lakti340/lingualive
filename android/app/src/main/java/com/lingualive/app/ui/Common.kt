package com.lingualive.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.lingualive.app.core.Langs
import com.lingualive.app.core.Segment

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) { Text("← Retour") }
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun LangPicker(label: String, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(Langs.byCode(selected).name + "  ▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Langs.all.forEach { l ->
                    DropdownMenuItem(text = { Text(l.name) }, onClick = { onSelect(l.code); open = false })
                }
            }
        }
    }
}

/** Retourne une fonction qui exécute l'action après avoir obtenu la permission micro. */
@Composable
fun rememberMicRequester(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pending.value?.invoke()
        else Toast.makeText(ctx, "Le micro est nécessaire pour la transcription", Toast.LENGTH_LONG).show()
        pending.value = null
    }
    return remember(launcher) {
        { action: () -> Unit ->
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) action()
            else {
                pending.value = action
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Grand bandeau de sous-titres en direct. */
@Composable
fun SubtitleBox(main: String, secondary: String?, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp)
            .background(Color(0xFF111827), RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Bottom
    ) {
        if (!secondary.isNullOrBlank()) {
            Text(secondary, color = Color(0xFF9CA3AF), fontSize = 14.sp)
            Spacer(Modifier.padding(2.dp))
        }
        Text(
            main.ifBlank { "Les sous-titres apparaîtront ici…" },
            color = if (main.isBlank()) Color(0xFF6B7280) else Color.White,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun SegmentRow(seg: Segment, showOriginal: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        val ts = seg.startSec?.let { s -> "%02d:%02d  ".format((s / 60).toInt(), (s % 60).toInt()) } ?: ""
        val who = seg.speaker?.let { "$it : " } ?: ""
        Text(ts + who + (seg.translated ?: seg.text), style = MaterialTheme.typography.bodyLarge)
        if (showOriginal && seg.translated != null && seg.translated != seg.text) {
            Text(seg.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StatusLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
