package app.sirenscout

import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import android.location.LocationManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

private val AlertRed = Color(0xFFE53935)

fun Category?.color() = when (this) {
    Category.POLICE -> Color(0xFF3D7BFF)
    Category.FIRE_EMS -> AlertRed
    Category.FLEET -> Color(0xFFFFB300)
    Category.RADIO -> Color(0xFFAB47BC)
    Category.SURVEILLANCE -> Color(0xFF26A69A)
    Category.TRACKER -> Color(0xFFEC407A)
    null -> Color(0xFF616161)
}

@Composable
fun SirenScoutApp(onStart: () -> Unit, onStop: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) { FullScreenHost {
        val items by Detections.items.collectAsStateWithLifecycle()
        val status by Detections.status.collectAsStateWithLifecycle()
        val now by produceState(System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(1_000)
            }
        }
        var tab by rememberSaveable { mutableStateOf(Tab.MATCHES) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        var sourceFilter by rememberSaveable { mutableStateOf<Source?>(null) }
        var showMap by rememberSaveable { mutableStateOf(false) }
        val ctx = LocalContext.current
        var hideEveryday by remember { mutableStateOf(Prefs(ctx).hideEveryday) }
        var confirmClearHistory by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        val context = LocalContext.current
        val historyVersion by History.changes.collectAsStateWithLifecycle()
        val history by produceState(emptyList<Detection>(), historyVersion) {
            value = withContext(Dispatchers.IO) { History.load(context) }
        }

        var exportSafe by remember { mutableStateOf(true) }
        var chooseExport by remember { mutableStateOf(false) }
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val safe = exportSafe
                val message = runCatching {
                    withContext(Dispatchers.IO) { History.exportCsv(context, uri, safe) }
                }.fold({ "Exported $it encounters${if (safe) " (safe)" else ""}" }, { "Export failed: ${it.message}" })
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }

        // Some phones stop delivering unfiltered BLE results once the screen turns off.
        val view = LocalView.current
        val keepScreenOn by produceState(true, status.running) {
            while (true) {
                value = AutoTune.keepScreenOn(context, Prefs(context))
                delay(10_000) // notices plugging in or unplugging
            }
        }
        SideEffect { view.keepScreenOn = status.running && keepScreenOn }

        val hidden by Hidden.rules.collectAsStateWithLifecycle()
        var hideTarget by remember { mutableStateOf<Detection?>(null) }
        var detailTarget by remember { mutableStateOf<Detection?>(null) }
        var watchTarget by remember { mutableStateOf<Detection?>(null) }
        val visible = items.values.filterNot(hidden::hides)
        val air = AirWatch.contacts.collectAsStateWithLifecycle().value.values
            .sortedWith(compareBy<AirContact> { it.distanceM ?: Double.MAX_VALUE }.thenByDescending { it.lastSeen })
        val matches = visible.filter { it.signature != null }.sortedByDescending { it.lastSeen }
        // Trackers are everywhere in other people's things; they alert only when they follow you.
        val active = matches.filter {
            it.signature?.category != Category.TRACKER && now - it.lastSeen < Detections.ACTIVE_WINDOW_MS
        }
        val tabItems = when (tab) {
            Tab.MATCHES -> matches
            Tab.ALL -> visible.sortedWith(
                compareByDescending<Detection> { it.signature != null }.thenByDescending { it.rssi }
            )
            Tab.WITH_ME -> visible.filter { it.withYou != null }.sortedByDescending { it.withYou!!.first }
            Tab.HISTORY -> history.filterNot(hidden::hides)
            Tab.AIR -> emptyList()
        }

        detailTarget?.let { d ->
            DetailDialog(
                d, running = status.running,
                onHide = { detailTarget = null; hideTarget = d },
                onWatch = { detailTarget = null; watchTarget = d },
                onShowOnMap = { target ->
                    detailTarget = null
                    showMap = true
                    MapFocus.request(target)
                },
                onClose = { detailTarget = null },
            )
        }
        hideTarget?.let { d -> HideDialog(d, onDismiss = { hideTarget = null }) }
        if (showSettings) SettingsScreen(onClose = { showSettings = false })
        watchTarget?.let { d -> WatchRuleDialog(d, onDismiss = { watchTarget = null }) }

        var update by remember { mutableStateOf<Updater.Update?>(null) }
        LaunchedEffect(Unit) { update = withContext(Dispatchers.IO) { Updater.checkIfDue(context) } }
        update?.let { u -> UpdateDialog(u, onDismiss = { update = null }) }

        var crash by remember { mutableStateOf(CrashReports.unseen(context)) }
        crash?.let { file ->
            AlertDialog(
                onDismissRequest = { CrashReports.markSeen(context); crash = null },
                title = { Text("Siren crashed last time") },
                text = { Text("A report was saved. Sharing it helps get the problem fixed. It contains no locations or detections.") },
                confirmButton = {
                    TextButton(onClick = {
                        CrashReports.markSeen(context)
                        crash = null
                        CrashReports.share(context, "Siren crash report", Redact.text(file.readText()))
                    }) { Text("Share report") }
                },
                dismissButton = {
                    TextButton(onClick = { CrashReports.markSeen(context); crash = null }) { Text("Not now") }
                },
            )
        }
        // Only All nearby leaves out everyday devices; With me keeps them, since your own gear and a
        // stranger's phone following you both look "everyday".
        val everydayHidden = if (tab == Tab.ALL && hideEveryday) tabItems.count(Everyday::isEveryday) else 0
        val shown = tabItems.filter {
            (sourceFilter == null || it.source == sourceFilter) && !(tab == Tab.ALL && hideEveryday && Everyday.isEveryday(it))
        }

        if (chooseExport) {
            AlertDialog(
                onDismissRequest = { chooseExport = false },
                title = { Text("Export history") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Safe export is for sharing: device addresses keep only the maker part, device and network names are left out, and locations are rounded to about 1 km.")
                        Text("Full export keeps everything - for your own records.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        chooseExport = false; exportSafe = true
                        exportLauncher.launch("siren-scout-history-${LocalDate.now()}-safe.csv")
                    }) { Text("Safe export") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        chooseExport = false; exportSafe = false
                        exportLauncher.launch("siren-scout-history-${LocalDate.now()}.csv")
                    }) { Text("Full export") }
                },
            )
        }

        if (confirmClearHistory) {
            AlertDialog(
                onDismissRequest = { confirmClearHistory = false },
                title = { Text("Delete history?") },
                text = { Text("This permanently deletes all ${history.size} saved encounters.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClearHistory = false
                        scope.launch(Dispatchers.IO) { History.clear(context) }
                    }) { Text("Delete", color = AlertRed) }
                },
                dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") } },
            )
        }

        val tabRow: @Composable () -> Unit = {
            Column {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = tab == Tab.MATCHES, onClick = { tab = Tab.MATCHES }, label = { Text("Matches (${matches.size})") })
                    FilterChip(selected = tab == Tab.ALL, onClick = { tab = Tab.ALL }, label = { Text("All nearby (${items.size})") })
                    FilterChip(selected = tab == Tab.WITH_ME, onClick = { tab = Tab.WITH_ME }, label = { Text("With me (${visible.count { it.withYou != null }})") })
                    FilterChip(selected = tab == Tab.AIR, onClick = { tab = Tab.AIR }, label = { Text("Air (${air.size})") })
                    FilterChip(selected = tab == Tab.HISTORY, onClick = { tab = Tab.HISTORY }, label = { Text("History (${history.count { !hidden.hides(it) }})") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (tab == Tab.AIR) "${air.size} drones / police aircraft · tap to see on the map"
                        else "${shown.size} shown" + (if (everydayHidden > 0) " · $everydayHidden everyday hidden" else "") + " · tap for details, hold to hide",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showMap = !showMap }) { Text(if (showMap) "List" else "Map") }
                    if (tab == Tab.HISTORY) {
                        TextButton(
                            onClick = { chooseExport = true },
                            enabled = history.isNotEmpty(),
                        ) { Text("Export") }
                    }
                    TextButton(onClick = {
                        if (tab == Tab.HISTORY) confirmClearHistory = true else Detections.clear()
                    }) { Text(if (tab == Tab.HISTORY) "Delete" else "Clear") }
                }
            }
        }
        val sourceRow: @Composable () -> Unit = {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tab == Tab.ALL) {
                    FilterChip(
                        selected = hideEveryday,
                        onClick = { hideEveryday = !hideEveryday; Prefs(context).hideEveryday = hideEveryday },
                        label = { Text("Hide everyday") },
                    )
                }
                FilterChip(
                    selected = sourceFilter == null, onClick = { sourceFilter = null },
                    label = { Text("All types") },
                )
                Source.entries.forEach { source ->
                    FilterChip(
                        selected = sourceFilter == source,
                        onClick = { sourceFilter = if (sourceFilter == source) null else source },
                        label = { Text("${source.label} (${tabItems.count { it.source == source }})") },
                    )
                }
            }
        }

        Scaffold { padding ->
            if (showMap) {
                val missingFix: @Composable () -> Unit = {
                    val located = shown.count { it.lat != null }
                    if (located < shown.size) {
                        Text(
                            "${shown.size - located} detection(s) have no GPS fix and aren't shown.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                        )
                    }
                }
                BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
                    if (maxWidth > maxHeight) {
                        // Sideways there's no height to stack controls above the map: put them beside it.
                        Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(
                                Modifier.weight(0.42f).fillMaxHeight().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Header(status, onStart, onStop, onToggleSettings = { showSettings = !showSettings })
                                AlertBanner(active, status.running)
                                tabRow()
                                sourceRow()
                                missingFix()
                            }
                            DetectionMap(shown, Modifier.weight(0.58f).fillMaxHeight(), air = air, onDetails = { detailTarget = it }, onHide = { hideTarget = it })
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // No room for settings above the map; show them in the list view instead.
                            Header(status, onStart, onStop, onToggleSettings = { showSettings = true })
                            AlertBanner(active, status.running)
                            tabRow()
                            sourceRow()
                            missingFix()
                            DetectionMap(shown, Modifier.fillMaxWidth().weight(1f), air = air, onDetails = { detailTarget = it }, onHide = { hideTarget = it })
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { Header(status, onStart, onStop, onToggleSettings = { showSettings = !showSettings }) }
                    item { AlertBanner(active, status.running) }
                    item { tabRow() }
                    if (tab == Tab.AIR) {
                        if (air.isEmpty()) item {
                            Text(
                                if (status.running) "No drones or police aircraft around right now." else "Press Start to listen for drones and check for police aircraft.",
                                style = MaterialTheme.typography.bodyMedium, color = Color.Gray,
                            )
                        }
                        items(air, key = { it.key }) { c ->
                            AirRow(c, now, onClick = {
                                if (c.lat != null && c.lon != null) {
                                    showMap = true
                                    MapFocus.pending.value = MapFocus.Request(c.key, c.lat, c.lon, c.title)
                                }
                            })
                        }
                    } else {
                        item { sourceRow() }
                        items(shown, key = { it.uid }) { DetectionRow(it, now, onClick = { detailTarget = it }, onLongClick = { hideTarget = it }) }
                    }
                }
            }
        }
    } }
}

/** A drone or police aircraft in the Air tab. Tap to see it on the map. */
@Composable
private fun AirRow(c: AirContact, now: Long, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(AIR_COLOR))
            Column(Modifier.padding(12.dp)) {
                Text(c.title, style = MaterialTheme.typography.titleMedium)
                Text(c.kind.label + " · " + c.detail, style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A4AE))
                Text(
                    listOfNotNull(
                        c.distanceM?.let { formatShortDistance(it) + " away" },
                        c.altitudeText,
                        c.speedMs?.takeIf { it > 0.5 }?.let { if (usesMiles()) "%.0f mph".format(it * 2.23694) else "%.0f km/h".format(it * 3.6) },
                        c.headingDeg?.let { "heading " + compass(it.toFloat()) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                c.operatorLat?.let { _ ->
                    Text("Operator position broadcast - tap to see it on the map", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Text(
                    (c.rssi?.let { "$it dBm · " } ?: "") + "seen ${((now - c.lastSeen) / 1000).coerceAtLeast(0)}s ago",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        }
    }
}

/** Drones and aircraft, in the list and on the map. */
val AIR_COLOR = Color(0xFF7E57C2)

@Composable
private fun Header(status: ScanStatus, onStart: () -> Unit, onStop: () -> Unit, onToggleSettings: () -> Unit) {
    val context = LocalContext.current
    val locationOn = remember(status.running) {
        context.getSystemService(LocationManager::class.java)?.let(LocationManagerCompat::isLocationEnabled) ?: true
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onToggleSettings) { Text("Settings") }
            if (status.running) {
                Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = AlertRed)) { Text("Stop") }
            } else {
                Button(onClick = onStart) { Text("Start") }
            }
        }
        if (!locationOn) {
            Text(
                "Location services are off - Android hides Bluetooth and Wi-Fi scan results until they're on.",
                color = Color(0xFFFFB300), style = MaterialTheme.typography.bodySmall,
            )
        }
        if (status.running) {
            val style = MaterialTheme.typography.bodySmall
            Text("BLE: ${status.ble}", style = style)
            Text("Bluetooth: ${status.classic}", style = style)
            Text("Wi-Fi: ${status.wifi}", style = style)
            Text("RF: ${status.rf}", style = style)
        }
    }
}

@Composable
private fun AlertBanner(active: List<Detection>, running: Boolean) {
    val strongest = active.maxByOrNull { it.rssi }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (strongest != null) AlertRed else MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (strongest == null) {
                Text(
                    if (running) "No emergency equipment detected" else "Not scanning",
                    style = MaterialTheme.typography.titleMedium,
                )
            } else {
                Text(
                    "Emergency equipment nearby (${active.size})",
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White,
                )
                val where = if (strongest.source == Source.RF) strongest.id else "${strongest.rssi} dBm, ${proximity(strongest.rssi)}"
                Text("${strongest.signature?.label} - $where", color = Color.White)
            }
        }
    }
}

private enum class Tab { MATCHES, ALL, WITH_ME, AIR, HISTORY }

internal fun ago(now: Long, time: Long): String {
    val s = ((now - time) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
            .format(java.util.Date(time))
    }
}

@OptIn(ExperimentalFoundationApi::class) // combinedClickable, for press-and-hold to hide
@Composable
private fun DetectionRow(d: Detection, now: Long, onClick: () -> Unit, onLongClick: () -> Unit) {
    val sig = d.signature
    val stale = now - d.lastSeen > Detections.ACTIVE_WINDOW_MS
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(sig?.category.color()))
            Column(Modifier.padding(12.dp).weight(1f)) {
                Text(
                    d.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (stale) Color.Gray else Color.Unspecified,
                )
                if (sig != null && d.name != null) Text(d.name, style = MaterialTheme.typography.bodyMedium)
                d.withYou?.let {
                    Text("With you ${formatWithYou(it)}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4FC3F7))
                }
                AiGuesser.lookup(d)?.takeIf { it.category != "unknown" }?.let {
                    Text(
                        "${it.label} ${AiGuesser.MARK} · ${it.category.replace('_', ' ')}, ${it.confidence}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.isOfficial) Color(0xFFFF8A65) else Color(0xFFB39DDB),
                    )
                }
                d.guessLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A4AE)) }
                Text(
                    "${d.source.label} · ${d.id}" + if (d.source != Source.RF) " · ${d.rssi} dBm" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "seen ${ago(now, d.lastSeen)} · ${d.count}×" + (sig?.let { " · ${it.category.label} · ${it.confidence.name.lowercase()} confidence" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
                if (sig != null && sig.note.isNotEmpty()) {
                    Text(sig.note, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
        }
    }
}


@Composable
internal fun HideDialog(d: Detection, onDismiss: () -> Unit) {
    val title = if (d.isTesla) TeslaKey.LABEL else d.name ?: d.signature?.label ?: d.guess?.let { if (d.isIdentified) it else "$it $GUESS_MARK" } ?: d.id
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hide $title?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Hidden emissions aren't listed, never alert and aren't saved. " +
                        "You can unhide them in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val sig = d.signature
                if (sig?.category == Category.TRACKER) {
                    val context = LocalContext.current
                    TextButton(onClick = { Hidden.hideDevice(d); TrackerMutes.mute(context, sig.id); onDismiss() }) {
                        Text("It's mine - hide it and mute all ${sig.label} alerts for 24 hours")
                    }
                    Text(
                        "Trackers can change their address, so muting the type stops repeat alerts from your own.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                TextButton(onClick = { Hidden.hideDevice(d); onDismiss() }) {
                    Text("Hide this ${d.source.label} device (${d.id})")
                }
                val prefix = d.id.take(8)
                if (d.source != Source.BLE && d.source != Source.RF && prefix.length == 8) {
                    TextButton(onClick = { Hidden.hidePrefix(prefix); onDismiss() }) {
                        Text("Hide everything with an address starting $prefix")
                    }
                    if (d.source == Source.WIFI) Text(
                        "Catches every network one router broadcasts, including unnamed ones.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
                if (d.name != null) {
                    TextButton(onClick = { Hidden.hideName(d.name); onDismiss() }) {
                        Text("Hide everything named \u201c${d.name}\u201d")
                    }
                    Text(
                        if (d.source == Source.WIFI) "Covers every band and access point of this network."
                        else "Better for watches and phones, which change their Bluetooth address.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun HiddenList() {
    val rules by Hidden.rules.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mutes by TrackerMutes.muted.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val activeMutes = mutes.filterValues { it > now }
    if (activeMutes.isNotEmpty()) {
        Text("Muted tracker alerts", style = MaterialTheme.typography.titleSmall)
        val time = android.text.format.DateFormat.getTimeFormat(context)
        activeMutes.forEach { (id, until) ->
            HiddenEntry(Signatures.byId(id)?.label ?: id, "until ${time.format(java.util.Date(until))}", button = "Unmute") {
                TrackerMutes.unmute(context, id)
            }
        }
    }
    Text("Hidden (${rules.devices.size + rules.names.size + rules.prefixes.size})", style = MaterialTheme.typography.titleSmall)
    if (rules.devices.isEmpty() && rules.names.isEmpty() && rules.prefixes.isEmpty()) {
        Text(
            "Nothing hidden. Press and hold a device in the list to hide it.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
    }
    rules.prefixes.sorted().forEach { prefix ->
        HiddenEntry("Address starts with $prefix", "all radios") { Hidden.unhidePrefix(prefix) }
    }
    rules.names.sorted().forEach { name ->
        HiddenEntry("Named \u201c$name\u201d", "all radios") { Hidden.unhideName(name) }
    }
    rules.devices.entries.sortedBy { it.value }.forEach { (key, label) ->
        val source = runCatching { Source.valueOf(key.substringBefore('|')) }.getOrNull()
        HiddenEntry(label.ifEmpty { "Unnamed device" }, "${source?.label ?: ""} · ${key.substringAfter('|')}") {
            Hidden.unhideDevice(key)
        }
    }
}

@Composable
private fun HiddenEntry(title: String, detail: String, button: String = "Unhide", onUnhide: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        TextButton(onClick = onUnhide) { Text(button) }
    }
}

@Composable
internal fun AutoEnableSettings(prefs: Prefs) {
    Text("Turn on automatically", style = MaterialTheme.typography.titleSmall)
    Text(
        "When you press Start, and when you come back to the app while scanning, Siren asks " +
            "Android to turn these on. Decline a prompt and it won't ask again until the next Start.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    AutoSwitch("Bluetooth", "Needed for most police equipment.", prefs.autoBluetooth) { prefs.autoBluetooth = it }
    AutoSwitch("Location", "Android hides scan results while Location is off.", prefs.autoLocation) { prefs.autoLocation = it }
    AutoSwitch("Wi-Fi scanning", "Opens the Wi-Fi panel only if both Wi-Fi and Android's Wi-Fi scanning are off.", prefs.autoWifi) { prefs.autoWifi = it }
    AutoSwitch("Ignore battery optimization", "Stops Android from pausing scans in the background.", prefs.autoBattery) { prefs.autoBattery = it }
    HorizontalDivider()
}

@Composable
internal fun AutoSwitch(
    title: String,
    detail: String,
    initial: Boolean,
    /** When set, the switch is locked to this value (chosen by Auto-optimize). */
    locked: Boolean? = null,
    onChange: (Boolean) -> Unit,
) {
    var on by remember { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (locked != null) Color.Gray else Color.Unspecified)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = locked ?: on, onCheckedChange = { on = it; onChange(it) }, enabled = locked == null)
    }
}

@Composable
internal fun ScanningSettings(prefs: Prefs, auto: Boolean, onAutoChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val throttlingOff = remember { wifiThrottlingOff(context) }
    val charging = remember { AutoTune.isCharging(context) }
    Text("Scanning", style = MaterialTheme.typography.titleSmall)
    AutoSwitch(
        "Auto-optimize (recommended)",
        "Picks the settings below from what the phone is doing: screen on and fastest Bluetooth scanning " +
            "while charging, battery-saving on battery, camera warnings timed to your speed, and RF only " +
            "when an SDR is plugged in. Turn off to set them yourself." +
            if (auto) " Right now: ${if (charging) "charging - screen stays on, fastest scanning" else "on battery - screen may sleep, balanced scanning"}." else "",
        auto,
    ) { onAutoChange(it) }
    AutoSwitch(
        "Fast Wi-Fi scans when allowed",
        if (throttlingOff) "Wi-Fi scan throttling is off on this phone, so Wi-Fi is scanned every 8 s."
        else "Wi-Fi scan throttling is on, so Wi-Fi is scanned every 31 s. For every 8 s, turn off " +
            "Developer options > Wi-Fi scan throttling.",
        prefs.fastWifi,
        locked = if (auto) true else null,
    ) { prefs.fastWifi = it }
    AutoSwitch(
        "Targeted scan with screen off",
        "Android pauses normal Bluetooth scanning when the screen is off. This keeps watching for Flock " +
            "and Raven IDs; other signatures need the screen on.",
        prefs.screenOffScan,
        locked = if (auto) true else null,
    ) { prefs.screenOffScan = it }
    AutoSwitch(
        "Keep screen on while scanning",
        if (auto) "Auto-optimize: on while charging, off on battery."
        else "Needed to catch everything. Turn off to save battery and rely on the targeted scan.",
        prefs.keepScreenOn,
        locked = if (auto) charging else null,
    ) { prefs.keepScreenOn = it }
    HorizontalDivider()
}

@Composable
internal fun BackupButtons() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    fun run(action: suspend () -> String) = scope.launch {
        val message = runCatching { withContext(Dispatchers.IO) { action() } }.getOrElse { "Failed: ${it.message}" }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) run { "Backed up ${Backup.export(context, uri)}" }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        confirmRestore = uri
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("Restore backup?") },
            text = { Text("Your current settings and hidden list will be replaced by the backup's. History from the backup is added to what's here.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = null
                    run { "Restored ${Backup.import(context, uri)}. Reopen Settings to see restored values." }
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { exportLauncher.launch("siren-scout-backup-${LocalDate.now()}.json") },
            modifier = Modifier.weight(1f),
        ) { Text("Back up") }
        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
            modifier = Modifier.weight(1f),
        ) { Text("Restore") }
    }
    Text(
        "Saves settings, hidden devices, AI identifications and history to a file you choose. Use it before reinstalling or to move to a new phone.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
}

@Composable
internal fun DataShareButtons() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var incoming by remember { mutableStateOf<DataShare.Contents?>(null) }

    fun run(action: suspend () -> String) = scope.launch {
        val message = runCatching { withContext(Dispatchers.IO) { action() } }.getOrElse { "Failed: ${it.message}" }
        if (message.isNotEmpty()) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    val openLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { DataShare.read(context, uri) } }
                .onSuccess { incoming = it }
                .onFailure { Toast.makeText(context, "Couldn't read it: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    if (choosing) {
        val ai = AiGuesser.guesses.collectAsStateWithLifecycle().value.size
        val hidden = Hidden.rules.collectAsStateWithLifecycle().value.let { it.devices.size + it.names.size + it.prefixes.size }
        val rules = WatchRules.rules.collectAsStateWithLifecycle().value.size
        var withAi by remember { mutableStateOf(true) }
        var withHidden by remember { mutableStateOf(true) }
        var withRules by remember { mutableStateOf(true) }
        var withHistory by remember { mutableStateOf(false) }
        @Composable
        fun Choice(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
            Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = checked, onCheckedChange = onChange)
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Share data") },
            text = {
                Column {
                    Choice("AI identifications ($ai)", withAi) { withAi = it }
                    Choice("Hidden devices and names ($hidden)", withHidden) { withHidden = it }
                    Choice("Watch rules ($rules)", withRules) { withRules = it }
                    Choice("History, with where things were seen", withHistory) { withHistory = it }
                    Text(
                        "History shows where you've been, so only share it with a phone you trust.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    choosing = false
                    run {
                        val all = DataShare.current(context, withHistory)
                        val c = DataShare.Contents(
                            ai = if (withAi) all.ai else emptyMap(),
                            hidden = if (withHidden) all.hidden else Hidden.Rules(),
                            rules = if (withRules) all.rules else emptyList(),
                            history = all.history,
                        )
                        withContext(Dispatchers.Main) { DataShare.share(context, c) }
                        ""
                    }
                }) { Text("Share") }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
    }

    incoming?.let { c ->
        AlertDialog(
            onDismissRequest = { incoming = null },
            title = { Text("Add shared data?") },
            text = { Text("This file has $c. They'll be added to this phone; nothing here is removed or replaced, and settings aren't touched.") },
            confirmButton = {
                TextButton(onClick = {
                    incoming = null
                    run { "Added ${DataShare.import(context, c)}" }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { incoming = null }) { Text("Cancel") } },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { choosing = true }, modifier = Modifier.weight(1f)) { Text("Share data") }
        OutlinedButton(
            onClick = { openLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
            modifier = Modifier.weight(1f),
        ) { Text("Add shared data") }
    }
    Text(
        "Send what this phone has learned - AI identifications, hidden devices, watch rules - to another phone with Siren, " +
            "through Quick Share or a message. On that phone, tap Add shared data and pick the file (Quick Share saves to Downloads).",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
}

@Composable
internal fun PhoneSyncSettings() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val running = Detections.status.collectAsStateWithLifecycle().value.running
    val status by PhoneSync.status.collectAsStateWithLifecycle()

    var serve by remember { mutableStateOf(prefs.syncServe) }
    var code by remember { mutableStateOf(prefs.syncCode) }
    SettingRow(
        "Let my other phones sync with this one",
        "While scanning, your other phones with Siren can send their data here and fetch this phone's, over Tailscale. " +
            "Nothing on the local Wi-Fi or hotspot can connect.",
        serve,
    ) {
        serve = it
        prefs.syncServe = it
        if (running) { if (it) PhoneSync.start(context) else PhoneSync.stop() }
    }
    if (serve) {
        val address = remember { PhoneSync.tailscaleAddress() }
        Text(
            if (address != null) "On the other phone, enter\nAddress: $address\nCode: $code"
            else "Tailscale isn't connected on this phone, so there's no address to give. Turn it on in the Tailscale app.\nCode: $code",
            style = MaterialTheme.typography.bodyMedium,
        )
        TextButton(onClick = { prefs.newSyncCode(); code = prefs.syncCode }) { Text("New code") }
    }

    var partner by remember { mutableStateOf(prefs.syncPartner) }
    var partnerCode by remember { mutableStateOf(prefs.syncPartnerCode) }
    Text("Sync with another phone", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Enter the address and code the other phone shows. Then every 10 minutes while scanning, this phone sends its data there and adds the other phone's here.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    OutlinedTextField(
        value = partner, onValueChange = { partner = it.trim(); prefs.syncPartner = partner },
        label = { Text("Other phone's address, e.g. 100.101.102.103") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = partnerCode, onValueChange = { partnerCode = it.trim(); prefs.syncPartnerCode = partnerCode },
        label = { Text("Its code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { PhoneSync.status.value = "Syncing…"; scope.launch { PhoneSync.syncOnce(context) } },
            enabled = partner.isNotBlank() && partnerCode.isNotBlank(),
        ) { Text("Sync now") }
        if (partner.isNotBlank()) TextButton(onClick = {
            partner = ""; partnerCode = ""; prefs.syncPartner = ""; prefs.syncPartnerCode = ""; PhoneSync.status.value = ""
        }) { Text("Stop syncing") }
    }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)

    var history by remember { mutableStateOf(prefs.syncHistory) }
    SettingRow(
        "Include history",
        "History includes where things were seen, which shows where you've been. Only applies to what this phone sends.",
        history,
    ) { history = it; prefs.syncHistory = it }
}

@Composable
internal fun DiagnosticsButtons() {
    val context = LocalContext.current
    var reports by remember { mutableStateOf(CrashReports.reports(context)) }
    Text("Diagnostics", style = MaterialTheme.typography.titleSmall)
    ScanHealthPanel()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { CrashReports.share(context, "Siren activity log", CrashReports.diagnostics(context)) },
            modifier = Modifier.weight(1f),
        ) { Text("Share activity log") }
        OutlinedButton(
            onClick = { reports.firstOrNull()?.let { CrashReports.share(context, "Siren crash report", Redact.text(it.readText())) } },
            enabled = reports.isNotEmpty(),
            modifier = Modifier.weight(1f),
        ) { Text("Share last crash") }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (reports.isEmpty()) "No crashes recorded."
            else "${reports.size} crash report(s) saved, latest ${reports.first().name.removePrefix("crash-").removeSuffix(".txt")}.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1f),
        )
        if (reports.isNotEmpty()) {
            TextButton(onClick = { CrashReports.deleteAll(context); reports = emptyList() }) { Text("Delete") }
        }
    }
    Text(
        "If something isn't working, share the activity log. Neither contains locations or detections.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    HorizontalDivider()
}

@Composable
private fun UpdateDialog(update: Updater.Update, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Update available: ${update.versionName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (update.notes.isNotBlank()) Text(update.notes)
                Text(
                    when {
                        busy -> "Downloading…"
                        !Updater.canInstall(context) -> "First, allow Siren to install apps: tap Allow, switch it on, then come back and tap Update."
                        else -> "Android will ask you to confirm. Your data is kept."
                    },
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (!Updater.canInstall(context)) {
                    context.startActivity(Updater.allowInstallsIntent(context))
                    return@TextButton
                }
                busy = true
                scope.launch {
                    val error = withContext(Dispatchers.IO) { runCatching { Updater.downloadAndInstall(context, update) }.exceptionOrNull() }
                    busy = false
                    if (error != null) {
                        AppLog.w("Update download failed", error)
                        Toast.makeText(context, "Update failed: ${error.message}", Toast.LENGTH_LONG).show()
                    }
                    onDismiss()
                }
            }) { Text(if (Updater.canInstall(context)) "Update" else "Allow") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Later") } },
    )
}

@Composable
internal fun UpdateSettings(prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(prefs.updateUrl) }
    var found by remember { mutableStateOf<Updater.Update?>(null) }
    var checking by remember { mutableStateOf(false) }
    found?.let { UpdateDialog(it, onDismiss = { found = null }) }

    Text("Updates", style = MaterialTheme.typography.titleSmall)
    Text(
        "Version ${appVersion(context)}. New versions come from your laptop over Tailscale - keep the Tailscale app connected.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    OutlinedTextField(
        value = url, onValueChange = { url = it; prefs.updateUrl = it },
        label = { Text("Update server") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
    OutlinedButton(
        enabled = url.isNotBlank() && !checking,
        onClick = {
            checking = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { Updater.check(context, url) } }
                checking = false
                result.onSuccess { u ->
                    if (u != null) found = u
                    else Toast.makeText(context, "You have the latest version", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    AppLog.w("Update check failed", it)
                    Toast.makeText(context, "Can't reach the update server. Is Tailscale connected?", Toast.LENGTH_LONG).show()
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(if (checking) "Checking…" else "Check for updates") }
    HorizontalDivider()
}

/** Live view of what each scanner is receiving, so "is it working?" has a quick answer. */
@Composable
private fun ScanHealthPanel() {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val start = ScanHealth.startedAt
    if (start == null) {
        Text("Scan health appears here while scanning.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        return
    }
    val warn = Color(0xFFFFB300)
    val running = now - start
    val ble = ScanHealth.blePerMinute(now)
    val classic = ScanHealth.classicPerMinute(now)
    val wifiAge = ScanHealth.lastWifiResults.takeIf { it > 0 }?.let { (now - it) / 1000 }
    val style = MaterialTheme.typography.bodySmall
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Scan health · running ${running / 3_600_000}h ${(running / 60_000) % 60}m", style = MaterialTheme.typography.labelLarge)
            Text(
                "BLE: $ble broadcasts in the last minute" +
                    if (ble == 0 && running > 60_000) " - nothing heard; is Bluetooth on, or is the screen off?" else "",
                style = style, color = if (ble == 0 && running > 60_000) warn else Color.Unspecified,
            )
            Text("Bluetooth: $classic devices found in the last minute", style = style)
            Text(
                "Wi-Fi: " + when {
                    wifiAge == null -> "no results yet"
                    wifiAge > 90 -> "last results ${wifiAge}s ago - scans may be blocked; is Location on?"
                    else -> "last results ${wifiAge}s ago"
                },
                style = style, color = if (wifiAge != null && wifiAge > 90) warn else Color.Unspecified,
            )
            val problems = buildList {
                if (ScanHealth.bleFailures > 0) add("${ScanHealth.bleFailures} BLE scan failure(s) (${ScanHealth.lastBleError})")
                if (ScanHealth.wifiRefused > 0) add("${ScanHealth.wifiRefused} Wi-Fi scan(s) refused by Android")
            }
            Text(
                if (problems.isEmpty()) "Problems: none" else "Problems: " + problems.joinToString("; "),
                style = style, color = if (problems.isEmpty()) Color.Gray else warn,
            )
        }
    }
}

@Composable
internal fun BandSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun CarDisplaySettings(prefs: Prefs) {
    val status by Detections.status.collectAsStateWithLifecycle()
    var on by remember { mutableStateOf(prefs.carDisplay) }
    var code by remember { mutableStateOf(prefs.carCode) }
    // Interfaces come and go (hotspot, USB tethering), so re-check every few seconds.
    var onWifi by remember { mutableStateOf(prefs.carOnWifi) }
    val addresses by produceState(CarDisplay.servingAddresses(), on, onWifi) {
        while (true) {
            value = CarDisplay.servingAddresses()
            delay(3_000)
        }
    }
    Text("Car display (experimental)", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Show Siren in another screen's browser")
            Text(
                "Serves a live page from this phone to devices on its hotspot or USB tethering, such as a car's browser. Starts when you press Start.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
        Switch(checked = on, onCheckedChange = {
            on = it
            prefs.carDisplay = it
            // Takes effect immediately if already scanning.
            if (status.running) { if (it) CarDisplay.start(code) else CarDisplay.stop() }
        })
    }
    if (on) {
        SettingRow(
            "Also on Wi-Fi networks",
            "For a car or screen on the same Wi-Fi as the phone, like at home. Anyone else on that network could try the page's address, so only use networks you trust.",
            onWifi,
        ) { onWifi = it; prefs.carOnWifi = it; CarDisplay.allowWifi = it }
        if (!status.running) {
            Text("Press Start, then open one of these addresses:", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB300))
        } else {
            Text("Open one of these in the other screen's browser:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (addresses.isEmpty()) {
            Text(
                "No local network yet. Turn on this phone's hotspot (or USB tethering) and connect the car to it.",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
        addresses.forEach { (kind, ip) ->
            Column {
                Text(kind, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                Text("http://$ip:${CarDisplay.PORT}/$code", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Secret code: $code", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                code = CarDisplay.newCode()
                prefs.carCode = code
                if (status.running) CarDisplay.restart(code)
            }) { Text("New code") }
        }
        val serving by produceState(CarDisplay.serving, status.running, on) {
            while (true) {
                value = CarDisplay.serving
                delay(1_000)
            }
        }
        Text(
            when {
                serving -> "Page is live. A new code applies immediately; open the new address in the car."
                status.running -> "Not serving${CarDisplay.error?.let { ": $it" } ?: " yet"}."
                else -> "The page starts when you press Start."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (serving) Color(0xFF66BB6A) else Color(0xFFFFB300),
        )
    }
    HorizontalDivider()
}

/** Colour swatches for your own position marker on the map. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocationColorSetting() {
    val context = LocalContext.current
    val current by LocationMarker.color.collectAsStateWithLifecycle()
    Text("Map", style = MaterialTheme.typography.titleSmall)
    Text("Your location marker color", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LocationMarker.COLORS.forEach { (name, argb) ->
            val selected = argb == current
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (selected) Color.White else Color.Transparent)
                        .padding(3.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .combinedClickable(onClick = { LocationMarker.set(context, argb) }),
                )
                Text(name, style = MaterialTheme.typography.labelSmall, color = if (selected) Color.Unspecified else Color.Gray)
            }
        }
    }
    HorizontalDivider()
}
