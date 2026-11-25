package app.sirenscout

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val METER_MIN_DBM = -100f
private const val METER_MAX_DBM = -35f
private const val GRAPH_WINDOW_MS = 120_000L

/**
 * Everything known about one detection, plus a live signal meter for walking toward it -
 * the screen stays on and the scanner runs at full speed while this is open.
 */
@Composable
fun DetailDialog(
    detection: Detection,
    running: Boolean,
    onHide: () -> Unit,
    onWatch: () -> Unit,
    onShowOnMap: (Detection) -> Unit,
    onClose: () -> Unit,
) {
    val items by Detections.items.collectAsStateWithLifecycle()
    val live = items[detection.key]
    val d = live ?: detection
    val sig = d.signature
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(500)
        }
    }

    // Finder mode: fastest scanning and screen on while this is open.
    DisposableEffect(d.key) {
        Detections.finding.value = d.key
        onDispose { if (Detections.finding.value == d.key) Detections.finding.value = null }
    }
    val view = LocalView.current
    DisposableEffect(Unit) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }

    val samples = remember(items, d.key) { Detections.signal(d.key) }
    val recent = samples.filter { now - it.time < 4_000 }
    val smoothed = recent.map { it.rssi }.average().takeIf { recent.isNotEmpty() }?.roundToInt()
    val secondsSinceSeen = ((now - d.lastSeen) / 1000).coerceAtLeast(0)
    val trend = trend(samples, now)
    val canMeter = live != null && running && d.source != Source.RF

    var beep by rememberSaveable { mutableStateOf(false) }
    BeepWhileFinding(enabled = beep && canMeter && smoothed != null, rssi = smoothed)

    FullScreenPage(onDismiss = onClose) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(sig?.category.color()))
                    Text(
                        d.title, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    if (d.source != Source.RF) TextButton(onClick = onWatch) { Text("Watch") }
                    TextButton(onClick = onHide) { Text("Hide") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                HorizontalDivider()
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // --- Live signal ---
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                d.source == Source.RF -> Text("Radio carrier at ${d.id} - ${d.name}")
                                !running -> Text("Press Start on the main screen to see the live signal.")
                                live == null -> Text("Not in range right now. Last seen ${ago(now, d.lastSeen)}.")
                                else -> {
                                    val shown = smoothed ?: d.rssi
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            "$shown",
                                            fontSize = 64.sp, fontWeight = FontWeight.Bold,
                                            color = if (secondsSinceSeen > 5) Color.Gray else Color.Unspecified,
                                        )
                                        Text(" dBm", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                                        Spacer(Modifier.weight(1f))
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(liveDistance(d, shown), style = MaterialTheme.typography.titleMedium)
                                            trend?.let { Text(it, color = if (it.startsWith("Getting stronger")) Color(0xFF66BB6A) else Color(0xFFFFB300)) }
                                        }
                                    }
                                    LinearProgressIndicator(
                                        progress = { ((shown - METER_MIN_DBM) / (METER_MAX_DBM - METER_MIN_DBM)).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().height(10.dp),
                                        color = sig?.category.color(),
                                    )
                                    Text(
                                        if (secondsSinceSeen > 5) "Not heard for ${secondsSinceSeen}s - move around slowly"
                                        else "Live · closer to 0 means closer to you",
                                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                                    )
                                    SignalGraph(samples, now, sig?.category.color())
                                }
                            }
                            if (canMeter) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Beep as I get closer")
                                        Text("Like a Geiger counter - faster beeps mean closer.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    }
                                    Switch(checked = beep, onCheckedChange = { beep = it })
                                }
                            }
                        }
                    }

                    if (sig?.category == Category.TRACKER) {
                        Text(
                            "To find it: hold the phone out and move slowly, pausing a few seconds at each spot. " +
                                "On a car, check wheel wells, bumpers, under the seats, the trunk lining, and the OBD port under the dashboard. " +
                                "In a bag, check pockets and the lining.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }

                    // --- What we know ---
                    val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    Info("What it is", sig?.label ?: "No signature match")
                    d.reason?.let { Info("Why it matched", it) }
                    VehicleRouters.quietReason(androidx.compose.ui.platform.LocalContext.current, d, Detections.items.value.values, System.currentTimeMillis())?.let { Info("Alerts", it) }
                    sig?.let { Info("Category", "${it.category.label} · ${it.confidence.name.lowercase(Locale.US)} confidence") }
                    d.name?.let { Info("Broadcast name", it) }
                    when {
                        d.isDrone -> Info("Identified", "${RemoteId.LABEL} - its position, height and operator are in the Air tab")
                        d.isJokeName -> Info("Identified", "${d.guess} - a home router named as a joke. Anyone can name their Wi-Fi anything; real police or surveillance gear doesn't announce itself.")
                        d.isTesla -> {}
                        else -> d.guess?.let { Info("Guess", "$it $GUESS_MARK") }
                    }
                    AiGuesser.lookup(d)?.let { ai ->
                        Info("On-device AI's guess", "${ai.label} ${AiGuesser.MARK} · ${ai.category.replace('_', ' ')}, ${ai.confidence} confidence")
                        Info("AI's reasoning", ai.why)
                    }
                    d.adSummary?.let { Info("What it advertises", it) }
                    Info("Heard on", d.source.label + (WifiBands.band(d.id)?.takeIf { d.source == Source.WIFI }?.let { ", $it band" } ?: ""))
                    Info(if (d.source == Source.RF) "Frequency" else "Address", d.id)
                    Info("First seen", time.format(Date(d.firstSeen)))
                    Info("Last seen", "${time.format(Date(d.lastSeen))} (${ago(now, d.lastSeen)})")
                    Info("Times picked up", "${d.count}")
                    d.withYou?.let { Info("Traveling with you", "With you ${formatWithYou(it)} so far, in one stretch") }
                    if (d.source != Source.RF) Info("Strongest signal", "${d.bestRssi} dBm (${proximity(d.bestRssi, d.source, WifiBands.mhz(d.id))})")
                    Detections.estimate(d)?.let { e ->
                        Info(
                            "Estimated location",
                            "%.5f, %.5f (±%s, from %d readings)".format(Locale.US, e.lat, e.lon, formatShortDistance(e.radiusM), e.readings),
                            action = "Show on map",
                        ) { onShowOnMap(d.copy(lat = e.lat, lon = e.lon)) }
                    }
                    if (d.lat != null && d.lon != null) {
                        Info("Where it was strongest", "%.5f, %.5f".format(Locale.US, d.lat, d.lon), action = "Show on map") {
                            onShowOnMap(d)
                        }
                    }
                    if (sig != null && sig.note.isNotEmpty()) Text(sig.note, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    SeenBefore(d, onShowOnMap)

                    var together by remember { mutableStateOf(false) }
                    if (d.source != Source.RF) OutlinedButton(onClick = { together = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Search together…")
                    }
                    if (together) SearchTogetherDialog(d) { together = false }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (d.source != Source.RF) OutlinedButton(onClick = onWatch, modifier = Modifier.weight(1f)) { Text("Watch…") }
                        OutlinedButton(onClick = onHide, modifier = Modifier.weight(1f)) { Text("Hide…") }
                        OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) { Text("Close") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Info(label: String, value: String, action: String? = null, onClick: (() -> Unit)? = null) {
    val link = MaterialTheme.colorScheme.primary
    Column(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) link else Color.Unspecified,
            textDecoration = if (onClick != null) TextDecoration.Underline else null,
        )
        if (action != null) Text("$action ›", style = MaterialTheme.typography.labelMedium, color = link)
    }
}

/** Compares the last 5 seconds with the 5 before that; signal readings are too noisy for less. */
private fun trend(samples: List<Detections.Sample>, now: Long): String? {
    val newer = samples.filter { now - it.time < 5_000 }.map { it.rssi }
    val older = samples.filter { now - it.time in 5_000 until 10_000 }.map { it.rssi }
    if (newer.size < 2 || older.size < 2) return null
    val change = newer.average() - older.average()
    return when {
        change >= 3 -> "Getting stronger ▲"
        change <= -3 -> "Getting weaker ▼"
        else -> "Holding steady"
    }
}

/** The last two minutes of readings, newest on the right. */
@Composable
private fun SignalGraph(samples: List<Detections.Sample>, now: Long, color: Color) {
    val points = samples.filter { now - it.time < GRAPH_WINDOW_MS }
    Canvas(Modifier.fillMaxWidth().height(80.dp)) {
        val grid = Color.Gray.copy(alpha = 0.3f)
        for (i in 0..2) {
            val y = size.height * i / 2
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        if (points.size < 2) return@Canvas
        val path = Path()
        points.forEachIndexed { i, s ->
            val x = size.width * (1f - (now - s.time).toFloat() / GRAPH_WINDOW_MS)
            val level = ((s.rssi - METER_MIN_DBM) / (METER_MAX_DBM - METER_MIN_DBM)).coerceIn(0f, 1f)
            val y = size.height * (1f - level)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
    }
    Text("Last 2 minutes", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
}

/** Short beeps, faster the stronger the signal: about every 1.5 s when far, 8 a second when very close. */
@Composable
private fun BeepWhileFinding(enabled: Boolean, rssi: Int?) {
    val latest by rememberUpdatedState(rssi)
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull() ?: return@LaunchedEffect
        try {
            while (true) {
                val level = (((latest ?: -100) - METER_MIN_DBM) / (METER_MAX_DBM - METER_MIN_DBM)).coerceIn(0f, 1f)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
                delay((1500 - level * 1380).toLong())
            }
        } finally {
            tone.release()
        }
    }
}

/**
 * The live meter's distance: this band's latest reading, and for a router on several bands the
 * others' too, so it matches the card.
 */
private fun liveDistance(d: Detection, rssi: Int): String {
    if (rssi >= 0) return "distance unknown"
    val mine = estimateMeters(rssi, d.source, WifiBands.mhz(d.id))
    val others = WifiBands.siblings(d, Detections.items.value.values)
        .filter { it.key != d.key && it.rssi < 0 }
        .map { estimateMeters(it.rssi, it.source, WifiBands.mhz(it.id)) }
    return roughDistance((others + mine).min())
}

/** Every other time this same device was seen, from the history: when, how long, how strong, and where. */
@Composable
private fun SeenBefore(d: Detection, onShowOnMap: (Detection) -> Unit) {
    if (d.source == Source.RF) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val changes by History.changes.collectAsStateWithLifecycle()
    val past by androidx.compose.runtime.produceState<List<Detection>?>(null, d.key, changes) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { History.encountersOf(context, d.source, d.id) }.getOrDefault(emptyList())
        }.filter { it.uid != d.uid }
    }
    val list = past ?: return
    var showAll by remember { mutableStateOf(false) }
    Text("Seen before", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
    if (list.isEmpty()) {
        Text("Not seen at any other time.", style = MaterialTheme.typography.bodyMedium)
    } else {
        val days = list.map { java.time.Instant.ofEpochMilli(it.firstSeen).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.toSet().size
        Text(
            "${list.size} other time${if (list.size == 1) "" else "s"}, on $days day${if (days == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        val fmtDay = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
        val fmtTime = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
        (if (showAll) list else list.take(8)).forEach { e ->
            val minutes = (e.lastSeen - e.firstSeen) / 60_000
            val line = "${fmtDay.format(Date(e.firstSeen))}, ${fmtTime.format(Date(e.firstSeen))}" +
                (if (minutes >= 1) "-${fmtTime.format(Date(e.lastSeen))} ($minutes min)" else "") +
                " · ${e.count}× · best ${e.bestRssi} dBm"
            val place = e.estimate?.let { it.lat to it.lon } ?: e.lat?.let { lat -> e.lon?.let { lon -> lat to lon } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (place != null) TextButton(onClick = { onShowOnMap(e.copy(lat = place.first, lon = place.second)) }) { Text("Map") }
            }
        }
        if (list.size > 8 && !showAll) TextButton(onClick = { showAll = true }) { Text("Show all ${list.size}") }
    }
    if (d.signature?.category == Category.TRACKER) {
        Text(
            "AirTags and other Find My or Find Hub tags change their address about once a day, so only the same day's " +
                "sightings can be linked; a tag seen on other days shows as a new one.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
    } else if (d.source == Source.BLE) {
        Text(
            "Many Bluetooth devices change their address every few minutes, so this only links sightings while the address stayed the same.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
    }
}
