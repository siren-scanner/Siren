package app.sirenscout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val HOW_TO_JOIN =
    "Everyone else: open the map, tap Join a search and enter this code. Phones reach each other on the same " +
        "hotspot or Wi-Fi (one person shares their hotspot, the rest join it)."

/** Start a search for [d] with other people, or see the one running. */
@Composable
fun SearchTogetherDialog(d: Detection, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val session by SearchTogether.session.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(Prefs(context).searchName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search together") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val s = session
                if (s == null) {
                    Text(
                        "Several people look for ${d.title} at once: every phone shares where it is and how strongly it " +
                            "hears it, so all your maps show one combined estimate that narrows down faster.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text("Your name (others see it)") }, singleLine = true)
                } else {
                    SessionDetails(s)
                    Text(HOW_TO_JOIN, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        },
        confirmButton = {
            if (session == null) TextButton(onClick = {
                Prefs(context).searchName = name
                SearchTogether.start(context, SearchTogether.Target(d.source, d.id, d.title))
            }) { Text("Start") }
            else TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            if (session != null) TextButton(onClick = { SearchTogether.stop() }) { Text("Stop search") }
            else TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Join a search someone else started, with their code. */
@Composable
fun JoinSearchDialog(initialCode: String = "", onDismiss: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(Prefs(context).searchName) }
    var code by remember { mutableStateOf(initialCode) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a search") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the code from the person who started the search.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = code, onValueChange = { code = it; error = null }, label = { Text("Code") }, singleLine = true)
                OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, label = { Text("Your name (others see it)") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Be on the same hotspot or Wi-Fi as the others. Scanning must be on for your phone to help find it.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs(context).searchName = name
                if (SearchTogether.join(context, code)) onDismiss() else error = "That code should be ${SearchTogether.CODE_LENGTH} letters and numbers"
            }) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SessionDetails(s: SearchTogether.Session) {
    val peers by SearchTogether.peers.collectAsStateWithLifecycle()
    val estimate by SearchTogether.estimate.collectAsStateWithLifecycle()
    Text("Code", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
    Text(SearchTogether.pretty(s.code), fontFamily = FontFamily.Monospace, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    val qr = remember(s.code) { qrBitmap(SearchTogether.joinLink(s.code)) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(
            bitmap = qr.asImageBitmap(), contentDescription = "QR code to join this search",
            modifier = Modifier.size(200.dp),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None,
        )
    }
    Text(
        "Others can scan this with their phone's camera to join (Siren must be installed), or type the code.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    Text(
        "Looking for: " + (s.target?.label ?: "waiting to hear from the others…"),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text(
        if (peers.isEmpty()) "No one else yet." else "With " + peers.values.joinToString { "${it.name} (${it.via})" },
        style = MaterialTheme.typography.bodyMedium,
    )
    SearchTogether.activeLinks.forEach { link ->
        val st by link.state.collectAsStateWithLifecycle()
        Text("${link.name}: $st", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
    estimate?.let { Text("Combined estimate: ±${formatShortDistance(it.radiusM)} from ${it.readings} readings", style = MaterialTheme.typography.bodySmall) }
}

/** On the map while a search runs: who's in it, how sure the combined estimate is, and Stop. */
@Composable
fun SearchCard(modifier: Modifier = Modifier) {
    val session by SearchTogether.session.collectAsStateWithLifecycle()
    val peers by SearchTogether.peers.collectAsStateWithLifecycle()
    val estimate by SearchTogether.estimate.collectAsStateWithLifecycle()
    val s = session ?: return
    Card(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Searching for ${s.target?.label ?: "…"}", style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(
                        listOfNotNull(
                            "with ${peers.size} other${if (peers.size == 1) "" else "s"}",
                            estimate?.let { "±${formatShortDistance(it.radiusM)}" },
                            "code ${SearchTogether.pretty(s.code)}",
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { SearchTogether.stop() }) { Text("Stop") }
            }
        }
    }
}

/** A black-on-white QR code for [text], one pixel per module plus a quiet zone (scaled up when drawn). */
internal fun qrBitmap(text: String): android.graphics.Bitmap {
    val m = com.google.zxing.qrcode.QRCodeWriter().encode(
        text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
    )
    val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return bmp
}
