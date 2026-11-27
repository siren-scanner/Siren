package app.sirenscout

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import android.location.Location
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.infowindow.InfoWindow
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Street level: individual buildings and parking spaces. */
internal const val FOCUS_ZOOM = 19.0

/** A few miles across: room for an aircraft or drone to move without leaving the screen. */
internal const val AIR_FOCUS_ZOOM = 12.0

/** What the shared map controls need from whichever map is drawing (OpenStreetMap tiles or vector). */
interface MapEngine {
    val lastFix: Location?
    /** The heading from the last minute, if moving. */
    fun lastFreshBearing(): Float?
    val isFollowing: Boolean
    fun follow(on: Boolean)
    /** Which way (clockwise from north) is up on the map now. */
    fun bearing(): Float

    /** Turns the map so [deg] (clockwise from north) points up; 0 is north up. */
    fun setBearing(deg: Float)
    fun moveTo(lat: Double, lon: Double, zoom: Double, animate: Boolean = false)
    fun fit(points: List<GeoPoint>)
    fun center(): GeoPoint?
    /** Location permission just granted. */
    fun startLocation()
    fun saveState()
    /** Runs [block] once the map has laid out and drawn the latest pins. */
    fun afterLayout(block: () -> Unit)
    /** A pin for something not in the device list, with a label. */
    fun showTempPin(lat: Double, lon: Double, title: String)
    /** Centres on an aircraft or drone and shows its label; false if it isn't on the map. */
    fun focusAir(key: String): Boolean
}

/** Map state shared by the controls and both kinds of map. */
class MapUi(headingUp: Boolean) {
    private val turnState = mutableStateOf(if (headingUp) MapTurn.HEADING else MapTurn.NORTH)
    val turn: MapTurn get() = turnState.value
    /** Read by location callbacks off the main thread, which can't read Compose state. */
    val turnNow = java.util.concurrent.atomic.AtomicReference(turnState.value)
    fun setTurn(t: MapTurn) { turnState.value = t; turnNow.set(t) }
    /** The pin whose card is showing, by detection uid. */
    val selected = mutableStateOf<String?>(null)
    var following by mutableStateOf(MapState.following)
    var center by mutableStateOf<GeoPoint?>(null)
    var zoom by mutableStateOf(15.0)
    /** A tapped camera, hotspot, teammate or aircraft (vector map; the classic map uses bubbles). */
    var info by mutableStateOf<MapInfo?>(null)
}

data class MapInfo(val title: String, val text: String, val source: String? = null, val onOpen: (() -> Unit)? = null)

/** Everything drawn on top of the base map. */
data class MapLayers(
    val located: List<Detection>,
    val selected: String?,
    val cameras: List<Camera>?,
    val hotspots: Map<String, List<PoliceData.Hotspot>>?,
    val live: Map<String, List<PoliceData.LiveCall>>?,
    val together: SearchTogether.Session?,
    val teammates: Map<Int, SearchTogether.Peer>,
    val teamEstimate: LocationEstimate?,
    val air: List<AirContact>,
    val heat: List<GeoPoint>,
    val drive: Traces.Shown?,
    /** What's alerting right now (the list's red banner), shown as a bar on the map. */
    val alerts: List<Detection> = emptyList(),
)

/**
 * The map: one pin per detection, at its estimated location or where its signal was strongest,
 * with known cameras, police data, searchers, aircraft, the history heatmap and a recorded drive.
 * Drawn as a sharp vector map or with classic OpenStreetMap tiles (Settings or Layers). Tapping a
 * pin shows a card with [onDetails] and [onHide], as tapping and holding a row does in the list.
 */
@Composable
fun DetectionMap(
    detections: List<Detection>,
    modifier: Modifier = Modifier,
    air: List<AirContact> = emptyList(),
    onAirDetails: (AirContact) -> Unit = {},
    onDetails: (Detection) -> Unit = {},
    onHide: (Detection) -> Unit = {},
    /** Shown in a page of its own: the button then leaves full screen instead of entering it. */
    fullScreen: Boolean = false,
    onFullScreen: (() -> Unit)? = null,
    /** The pin you picked (its uid), or null: lets a list beside the map follow along. */
    onPinSelected: (String?) -> Unit = {},
    /** What's alerting right now, for the red bar on the map. */
    alerts: List<Detection> = emptyList(),
) {
    val context = LocalContext.current
    val ui = remember { MapUi(Prefs(context).mapHeadingUp) }
    LaunchedEffect(ui.selected.value) { onPinSelected(ui.selected.value) }
    val style by MapStyle.current.collectAsStateWithLifecycle()
    val together by SearchTogether.session.collectAsStateWithLifecycle()
    val teammates by SearchTogether.peers.collectAsStateWithLifecycle()
    val teamEstimate by SearchTogether.estimate.collectAsStateWithLifecycle()
    val showCameras = remember { Prefs(context).knownCameras }
    val cameras by KnownCameras.cameras.collectAsStateWithLifecycle()
    val speedCameras = remember { Prefs(context).speedCameras }
    val wantedCameras = remember(cameras) { cameras.wanted(speedCameras) }
    val showPolice = remember { Prefs(context).policeData }
    val hotspots by PoliceData.hotspots.collectAsStateWithLifecycle()
    val liveCalls by PoliceData.live.collectAsStateWithLifecycle()
    val drive by Traces.shown.collectAsStateWithLifecycle()
    val heatDays by MapStyle.heatmapDays.collectAsStateWithLifecycle()
    var heat by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
    LaunchedEffect(heatDays) {
        heat = emptyList()
        if (heatDays == 0) return@LaunchedEffect
        while (true) {
            val since = if (heatDays < 0) 0L else System.currentTimeMillis() - heatDays * 86_400_000L
            heat = withContext(Dispatchers.IO) {
                runCatching { History.flaggedSince(context, since) }.getOrDefault(emptyList()).mapNotNull { d ->
                    d.estimate?.let { GeoPoint(it.lat, it.lon) } ?: d.lat?.let { GeoPoint(it, d.lon!!) }
                }
            }
            delay(5 * 60_000L) // new encounters are saved every few minutes
        }
    }
    val layers = MapLayers(
        located = detections.filter { it.lat != null && it.lon != null },
        selected = ui.selected.value,
        cameras = wantedCameras.takeIf { showCameras },
        hotspots = hotspots.takeIf { showPolice },
        live = liveCalls.takeIf { showPolice },
        together = together, teammates = teammates, teamEstimate = teamEstimate,
        air = air, heat = heat, drive = drive, alerts = alerts,
    )
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clipToBounds()) {
        // A short map (sharing the screen with the list) gets slimmer cards and scrolling buttons.
        val compact = maxHeight < 380.dp
        val buttonsMax = maxHeight
        // A new map when the style changes; both pick up where the other left off (MapState).
        val engine = key(style) {
            if (style == MapStyle.VECTOR && VectorLib.available(context)) VectorMap(ui, layers, onAirDetails) else OsmMap(ui, layers, onAirDetails)
        }
        MapChrome(engine, ui, layers, onDetails, onHide, fullScreen, onFullScreen, showCameras, showPolice, wantedCameras, hotspots, liveCalls, compact, buttonsMax)
    }
}

/** Which map draws, and the heatmap period; changed from the Layers dialog or Settings. */
object MapStyle {
    const val VECTOR = "vector"
    const val OSM = "osm"
    val current = kotlinx.coroutines.flow.MutableStateFlow(OSM)
    /** The vector map crashed while starting last time, so the classic map is back. */
    val fellBack = kotlinx.coroutines.flow.MutableStateFlow(false)
    val heatmapDays = kotlinx.coroutines.flow.MutableStateFlow(0)
    val HEATMAP_CHOICES = listOf(0 to "Off", 1 to "Last 24 hours", 7 to "Last 7 days", 30 to "Last 30 days", -1 to "Everything")

    fun load(context: android.content.Context) {
        val p = Prefs(context)
        if (p.vectorStarting) {
            p.vectorStarting = false
            p.mapStyle = OSM
            fellBack.value = true
            AppLog.w("The vector map didn't finish starting last time; back to the OpenStreetMap map")
        }
        // The vector map needs its engine (an optional download); without it, the classic map.
        current.value = if (p.mapStyle == VECTOR && !VectorLib.available(context)) OSM else p.mapStyle
        heatmapDays.value = p.heatmapDays
    }

    fun set(context: android.content.Context, style: String) { Prefs(context).mapStyle = style; current.value = style; fellBack.value = false }
    fun setHeatmap(context: android.content.Context, days: Int) { Prefs(context).heatmapDays = days; heatmapDays.value = days }
}

/** The buttons, cards and dialogs over either map. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MapChrome(
    engine: MapEngine,
    ui: MapUi,
    layers: MapLayers,
    onDetails: (Detection) -> Unit,
    onHide: (Detection) -> Unit,
    fullScreen: Boolean,
    onFullScreen: (() -> Unit)?,
    showCameras: Boolean,
    showPolice: Boolean,
    cameras: List<Camera>,
    hotspots: Map<String, List<PoliceData.Hotspot>>,
    liveCalls: Map<String, List<PoliceData.LiveCall>>,
    compact: Boolean = false,
    height: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity,
) {
    val context = LocalContext.current
    var searching by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf(false) }
    var layersOpen by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(MapState.searched) }
    val together = layers.together
    val located = layers.located
    var hasLocation by remember {
        mutableStateOf(
            // Demo data (debug builds, for screenshots) never shows the real location, nor asks for it.
            DemoMode.active || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    fun where() = ui.center ?: engine.center()

    if (showCameras) {
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { KnownCameras.ensureLoaded(context) } }
        // Load cameras around wherever the map is. Polled rather than keyed on the center, which
        // changes with every GPS fix while following; already-covered areas return immediately.
        LaunchedEffect(engine) {
            while (true) {
                delay(3_000)
                val c = where() ?: continue
                // 0,0 is the map's default before any fix - open ocean, nothing to download.
                if (abs(c.latitude) < 1 && abs(c.longitude) < 1) continue
                withContext(Dispatchers.IO) { KnownCameras.refreshAround(context, c.latitude, c.longitude) }
            }
        }
    }
    if (showPolice) {
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PoliceData.ensureLoaded(context) } }
        // Like the cameras: download a city's police data when the map is looking at it.
        LaunchedEffect(engine) {
            val live = Prefs(context).liveCalls
            while (true) {
                delay(10_000)
                val c = where() ?: continue
                if (abs(c.latitude) < 1 && abs(c.longitude) < 1) continue
                withContext(Dispatchers.IO) { PoliceData.refreshAround(context, c.latitude, c.longitude, live) }
            }
        }
    }

    // "Show on map": stop following, zoom to street level on the spot, and open its pin.
    val focus by MapFocus.pending.collectAsStateWithLifecycle()
    LaunchedEffect(focus, engine) {
        val f = focus ?: return@LaunchedEffect
        engine.follow(false)
        ui.following = false
        engine.afterLayout {
            engine.setBearing(0f)
            ui.info = null
            // Aircraft move fast: zoom out to a few miles around where it is now, not street level.
            if (engine.focusAir(f.uid)) return@afterLayout
            engine.moveTo(f.lat, f.lon, FOCUS_ZOOM)
            if (located.any { it.uid == f.uid }) ui.selected.value = f.uid
            else engine.showTempPin(f.lat, f.lon, f.title) // not in the current list (e.g. filtered out)
        }
        MapFocus.pending.value = null
    }

    // The map's turn, for the compass rose (polled: the two map engines report it differently).
    var bearing by remember { mutableStateOf(0f) }
    LaunchedEffect(engine) { while (true) { bearing = engine.bearing(); delay(200) } }
    val alerts = layers.alerts
    // Alerts on the map itself: a red bar across the top, which flashes when something new shows up.
    val barHeight = if (alerts.isNotEmpty()) 52.dp else 0.dp
    if (alerts.isNotEmpty()) AlertBar(alerts, Modifier.align(Alignment.TopCenter).padding(horizontal = 8.dp, vertical = 6.dp)) { d ->
        val est = Detections.estimate(d)
        if (est != null) MapFocus.request(d, est.lat, est.lon) else MapFocus.request(d)
    }
    val gap = Arrangement.spacedBy(8.dp)
    Column(
        Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp + barHeight)
            // On a short map the buttons scroll rather than run off the bottom.
            .then(if (height != androidx.compose.ui.unit.Dp.Infinity) Modifier.heightIn(max = height - 16.dp - barHeight) else Modifier)
            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = gap,
    ) {
        MapIconButton("Search a place", onClick = { searching = true }, icon = MapIcons.search)
        if (located.isNotEmpty()) MapIconButton("Show all on the map", onClick = {
            engine.follow(false)
            ui.following = false
            engine.setBearing(0f)
            if (ui.turn == MapTurn.FREE) ui.setTurn(if (Prefs(context).mapHeadingUp) MapTurn.HEADING else MapTurn.NORTH)
            val points = located.map { d -> Detections.estimate(d)?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(d.lat!!, d.lon!!) }
            engine.fit(points)
        }, icon = MapIcons.showAll)
        if (hasLocation) MapIconButton("Report camera here", onClick = {
            // Passed a camera that isn't on the map? Save the spot now, add it to DeFlock later.
            val fix = engine.lastFix
            if (fix == null) {
                android.widget.Toast.makeText(context, "No location yet - try again in a moment", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                CameraReports.add(
                    context,
                    CameraReports.Spot(System.currentTimeMillis(), fix.latitude, fix.longitude, fix.takeIf { it.hasBearing() }?.bearing),
                )
                android.widget.Toast.makeText(context, "Camera spot saved - add it later from Settings > Cameras", android.widget.Toast.LENGTH_LONG).show()
            }
        }, icon = MapIcons.camera)
        if (together == null) MapIconButton("Join a search", onClick = { joining = true }, icon = MapIcons.people)
    }
    Column(
        Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 8.dp + barHeight),
        verticalArrangement = gap,
    ) {
        // Compass rose: north up <-> heading up; a hand-turned map goes back to north first.
        MapIconButton(
            when (ui.turn) { MapTurn.HEADING -> "Heading up - tap for north up"; MapTurn.NORTH -> "North up - tap for heading up"; MapTurn.FREE -> "Turned by hand - tap for north up" },
            onClick = {
                val next = if (ui.turn == MapTurn.NORTH) MapTurn.HEADING else MapTurn.NORTH
                ui.setTurn(next)
                Prefs(context).mapHeadingUp = next == MapTurn.HEADING
                if (next == MapTurn.NORTH) engine.setBearing(0f)
                else engine.lastFreshBearing()?.let { if (engine.isFollowing) engine.setBearing(it) }
                engine.saveState()
            },
            active = ui.turn == MapTurn.HEADING,
            icon = if (ui.turn == MapTurn.HEADING) MapIcons.heading else MapIcons.compass(bearing),
        )
        MapIconButton("Map layers", onClick = { layersOpen = true }, icon = MapIcons.layers)
        onFullScreen?.let { open -> MapIconButton(if (fullScreen) "Leave full screen" else "Full screen", onClick = open, icon = MapIcons.fullScreen(fullScreen)) }
    }
    if (!hasLocation) {
        // Android 12+ ignores a request for precise location that doesn't also ask for approximate.
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            hasLocation = granted
            if (granted) {
                engine.startLocation()
                ui.following = true
            }
        }
        Card(Modifier.align(Alignment.Center).padding(24.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Location is off for Siren", style = MaterialTheme.typography.titleMedium)
                Text("The map needs it to show where you are and the plate readers around you.")
                Button(onClick = {
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }) { Text("Allow location") }
            }
        }
    }
    val chosen = ui.selected.value?.let { uid -> located.firstOrNull { it.uid == uid } }
    val info = ui.info
    when {
        chosen != null && compact -> SlimPinCard(
            chosen,
            onDetails = { onDetails(chosen) },
            onClose = { ui.selected.value = null },
            modifier = Modifier.align(Alignment.BottomCenter).padding(6.dp),
        )
        chosen != null -> PinCard(
            chosen,
            onDetails = { onDetails(chosen) },
            onHide = { ui.selected.value = null; onHide(chosen) },
            onClose = { ui.selected.value = null },
            modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
        )
        info != null -> InfoCard(info, onClose = { ui.info = null }, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp))
        together != null -> SearchCard(Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(0.72f))
        searched != null -> PlaceCard(
            searched!!, cameras, hotspots, liveCalls,
            onClose = { searched = null; MapState.searched = null },
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(0.72f),
        )
        layers.drive != null -> DriveCard(layers.drive, Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(0.72f))
    }
    if (joining) JoinSearchDialog { joining = false }
    if (layersOpen) LayersDialog(onDismiss = { layersOpen = false }, onShowDrive = { shown ->
        layersOpen = false
        engine.follow(false)
        ui.following = false
        engine.setBearing(0f)
        engine.fit(shown.points.map { GeoPoint(it.lat, it.lon) })
    })
    if (searching) PlaceSearchDialog(
        onDismiss = { searching = false },
        onPick = { place ->
            searching = false
            engine.follow(false)
            ui.following = false
            engine.setBearing(0f)
            if (ui.turn == MapTurn.FREE) ui.setTurn(if (Prefs(context).mapHeadingUp) MapTurn.HEADING else MapTurn.NORTH)
            engine.moveTo(place.lat, place.lon, 12.0)
            ui.center = GeoPoint(place.lat, place.lon)
            engine.saveState()
            searched = place
            MapState.searched = place
        },
    )
    if (hasLocation && chosen == null && info == null) {
        // Locate me (filled while following).
        MapIconButton(
            if (ui.following) "Following you" else "Follow me",
            onClick = {
                engine.follow(true)
                ui.following = true
                // Following again: back to your chosen mode rather than the hand-turned angle.
                val mode = if (Prefs(context).mapHeadingUp) MapTurn.HEADING else MapTurn.NORTH
                ui.setTurn(mode)
                if (mode == MapTurn.NORTH) engine.setBearing(0f)
            },
            active = ui.following,
            icon = MapIcons.locate(ui.following),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
    }
}

/** A tapped camera, hotspot, aircraft or teammate. */
@Composable
private fun InfoCard(info: MapInfo, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(info.title, style = MaterialTheme.typography.titleMedium)
            if (info.text.isNotBlank()) Text(info.text, style = MaterialTheme.typography.bodySmall)
            info.source?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                info.onOpen?.let { open -> TextButton(onClick = { onClose(); open() }) { Text("Details") } }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/** The recorded drive on the map: when, how far, and the flagged equipment seen along it. */
@Composable
private fun DriveCard(shown: Traces.Shown, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Drive · ${time(context, shown.trace.start)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { Traces.shown.value = null }) { Text("Close") }
            }
            val minutes = (shown.trace.end - shown.trace.start) / 60_000
            Text(
                "${formatShortDistance(Traces.lengthM(shown.points))} · ${minutes / 60} h ${minutes % 60} min · " +
                    "${shown.flagged.size} flagged along the way",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Map style, history heatmap and recorded drives. */
@Composable
private fun LayersDialog(onDismiss: () -> Unit, onShowDrive: (Traces.Shown) -> Unit) {
    val context = LocalContext.current
    val style by MapStyle.current.collectAsStateWithLifecycle()
    val heatDays by MapStyle.heatmapDays.collectAsStateWithLifecycle()
    val prefs = remember { Prefs(context) }
    var record by remember { mutableStateOf(prefs.recordDrives) }
    var drives by remember { mutableStateOf(Traces.list(context)) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map layers") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item { Text("Map", style = MaterialTheme.typography.titleSmall) }
                if (MapStyle.fellBack.value) item {
                    Text("The vector map failed to start last time, so the classic map is back. You can try it again.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB300))
                }
                item { MapStylePicker() }
                item { Text("History heatmap", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                item { Text("Where flagged equipment showed up before, from Siren's history.", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                item {
                    for ((days, label) in MapStyle.HEATMAP_CHOICES) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { MapStyle.setHeatmap(context, days) }) {
                            androidx.compose.material3.RadioButton(selected = heatDays == days, onClick = { MapStyle.setHeatmap(context, days) })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                item { Text("Drives", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Record my drives while scanning", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = record, onCheckedChange = { record = it; prefs.recordDrives = it })
                    }
                }
                item { Text("Kept on this phone only (the newest 100); starts with the next scan.", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                if (drives.isEmpty()) item { Text("No drives recorded yet.", style = MaterialTheme.typography.bodySmall) }
                items(drives.size) { i ->
                    val t = drives[i]
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val minutes = (t.end - t.start) / 60_000
                        Text(
                            "${time(context, t.start)} · ${minutes / 60} h ${minutes % 60} min",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).clickable {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Traces.show(context, t) }
                                    Traces.shown.value?.let(onShowDrive)
                                }
                            }.padding(vertical = 6.dp),
                        )
                        TextButton(onClick = { Traces.delete(t); drives = Traces.list(context) }) { Text("Delete") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/**
 * Vector or classic map. The vector map's engine is a one-time download (unless the app came with
 * it), offered right here; it can be removed again.
 */
@Composable
internal fun MapStylePicker() {
    val context = LocalContext.current
    val style by MapStyle.current.collectAsStateWithLifecycle()
    val lib by VectorLib.state.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val have = remember(lib) { VectorLib.available(context) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = have) { MapStyle.set(context, MapStyle.VECTOR) }) {
            androidx.compose.material3.RadioButton(selected = style == MapStyle.VECTOR, enabled = have, onClick = { MapStyle.set(context, MapStyle.VECTOR) })
            Text("Vector - sharp street names at any zoom", style = MaterialTheme.typography.bodyMedium)
        }
        if (!have) {
            val abi = VectorLib.abi()
            when {
                abi == null -> Text("Not available for this phone's processor.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                lib is VectorLib.State.Downloading -> {
                    val d = lib as VectorLib.State.Downloading
                    Text(
                        "Downloading… " + (if (d.total > 0) "${d.done * 100 / d.total}%" else "${d.done / 1_000_000} MB"),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 48.dp),
                    )
                }
                else -> Column(Modifier.padding(start = 48.dp)) {
                    Text(
                        "Needs a one-time download of the map engine (about 4 MB), kept separate so the app stays small.",
                        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                    )
                    (lib as? VectorLib.State.Failed)?.let { Text("Download failed: ${it.message}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB300)) }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) { runCatching { VectorLib.download(context) }.isSuccess }
                                if (ok) MapStyle.set(context, MapStyle.VECTOR)
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text("Download vector map") }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { MapStyle.set(context, MapStyle.OSM) }) {
            androidx.compose.material3.RadioButton(selected = style == MapStyle.OSM, onClick = { MapStyle.set(context, MapStyle.OSM) })
            Text("OpenStreetMap - the classic map", style = MaterialTheme.typography.bodyMedium)
        }
        if (have && !BuildConfig.VECTOR_BUNDLED) TextButton(
            onClick = { MapStyle.set(context, MapStyle.OSM); VectorLib.remove(context) },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) { Text("Remove the vector map (${VectorLib.sizeBytes(context) / 1_000_000} MB)") }
    }
}

/** Classic OpenStreetMap tiles (osmdroid). */
@Composable
private fun OsmMap(ui: MapUi, layers: MapLayers, onAirDetails: (AirContact) -> Unit): MapEngine {
    val context = LocalContext.current
    val markers = remember { mutableMapOf<String, Marker>() }
    /** Translucent uncertainty circles for estimated locations, by detection uid. */
    val circles = remember { mutableMapOf<String, Polygon>() }
    /** Exact zoom, so pin groups are redone on every zoom change (not just whole steps). */
    var zoomExact by remember { mutableStateOf(15.0) }
    var zoom by remember { mutableStateOf(15) }
    val cameraLayer = remember { CameraLayer() }
    val policeLayer = remember { PoliceLayer() }
    val teamLayer = remember { TeamLayer() }
    val airLayer = remember { AirLayer() }
    val heatLayer = remember { HeatLayer() }
    val driveLayer = remember { DriveLayer() }
    airLayer.onDetails = onAirDetails
    val (mapView, me) = remember {
        MapCache.configure(context)
        MapView(context).run {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Map tiles have their street names drawn in. Unscaled, they're drawn pixel for pixel,
            // which makes the text tiny on sharp screens; scale them to the screen, then by choice.
            isTilesScaledToDpi = true
            tilesScaleFactor = MapText.scale.value
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(15.0)
            val me = HeadingUpLocationOverlay(GpsMyLocationProvider(context), this) { ui.turnNow.get() == MapTurn.HEADING }
            // Two fingers turn the map by hand; it then stays where you put it until you reset it.
            overlays.add(object : org.osmdroid.views.overlay.gestures.RotationGestureOverlay(this) {
                override fun onRotate(deltaAngle: Float) {
                    super.onRotate(deltaAngle)
                    if (ui.turnNow.get() != MapTurn.FREE) ui.setTurn(MapTurn.FREE)
                }
            }.apply { isEnabled = true })
            if (!DemoMode.active && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                me.enableMyLocation()
                me.enableFollowLocation()
            }
            // Pick up where the other map left off (full screen and back show the same place).
            MapState.center?.let { c ->
                controller.setZoom(MapState.zoom)
                controller.setCenter(c)
                if (!MapState.following) {
                    me.disableFollowLocation()
                    mapOrientation = MapState.orientation
                }
            }
            overlays.add(me)
            overlays.add(CopyrightOverlay(context)) // "© OpenStreetMap contributors", required by OSM
            // Any tap closes open popups. Returning false lets a tapped pin still open its own.
            overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                    InfoWindow.closeAllInfoWindowsOn(this@run)
                    ui.selected.value = null
                    return false
                }

                override fun longPressHelper(p: GeoPoint?) = false
            }))
            // Follow mode switches itself off when the map is dragged; mirror that into the UI.
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    ui.following = me.isFollowLocationEnabled
                    ui.center = mapCenter as GeoPoint
                    MapState.remember(this@run, me.isFollowLocationEnabled)
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    zoom = zoomLevelDouble.roundToInt()
                    zoomExact = zoomLevelDouble
                    ui.zoom = zoomLevelDouble
                    MapState.remember(this@run, me.isFollowLocationEnabled)
                    return false
                }
            })
            this to me
        }
    }
    val focusPin = remember { Marker(mapView) }
    val engine = remember {
        object : MapEngine {
            override val lastFix get() = me.lastFix
            override fun lastFreshBearing() = me.lastFreshBearing()
            override val isFollowing get() = me.isFollowLocationEnabled
            override fun follow(on: Boolean) {
                if (on) {
                    me.enableFollowLocation()
                    // From a state- or country-wide view, come in to street level.
                    if (mapView.zoomLevelDouble < 11.0) mapView.controller.setZoom(15.0)
                } else me.disableFollowLocation()
            }
            override fun setBearing(deg: Float) { mapView.mapOrientation = -deg }
            override fun bearing() = -mapView.mapOrientation
            override fun moveTo(lat: Double, lon: Double, zoom: Double, animate: Boolean) {
                if (animate) mapView.controller.animateTo(GeoPoint(lat, lon), zoom, 600L)
                else { mapView.controller.setZoom(zoom); mapView.controller.setCenter(GeoPoint(lat, lon)) }
            }
            override fun fit(points: List<GeoPoint>) {
                if (points.isEmpty()) return
                if (points.size == 1) mapView.controller.animateTo(points[0], 17.0, 600L)
                else mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), true)
            }
            override fun center() = mapView.mapCenter.let { GeoPoint(it.latitude, it.longitude) }
            override fun startLocation() { me.enableMyLocation(); me.enableFollowLocation() }
            override fun saveState() = MapState.remember(mapView, me.isFollowLocationEnabled)
            override fun afterLayout(block: () -> Unit) { mapView.post { block(); mapView.invalidate() } }
            override fun showTempPin(lat: Double, lon: Double, title: String) {
                InfoWindow.closeAllInfoWindowsOn(mapView)
                focusPin.apply {
                    position = GeoPoint(lat, lon)
                    this.title = title
                    snippet = "Strongest signal was here"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    if (this !in mapView.overlays) mapView.overlays.add(this)
                }.showInfoWindow()
            }
            override fun focusAir(key: String): Boolean {
                val m = airLayer.marker(key) ?: return false
                mapView.controller.setZoom(AIR_FOCUS_ZOOM)
                mapView.controller.setCenter(m.position)
                m.showInfoWindow()
                return true
            }
        }
    }

    // Bigger map text, whenever the setting changes.
    val textScale by MapText.scale.collectAsStateWithLifecycle()
    LaunchedEffect(textScale) {
        if (mapView.tilesScaleFactor != textScale) {
            mapView.tilesScaleFactor = textScale
            mapView.invalidate()
        }
    }

    // Your own position marker, recoloured whenever the setting changes.
    val markerColor by LocationMarker.color.collectAsStateWithLifecycle()
    LaunchedEffect(markerColor) {
        me.setDirectionArrow(LocationMarker.dot(context, markerColor), LocationMarker.arrow(context, markerColor))
        me.setPersonAnchor(0.5f, 0.5f)
        me.setDirectionAnchor(0.5f, 0.5f)
        mapView.invalidate()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            MapState.remember(mapView, me.isFollowLocationEnabled)
            mapView.onDetach()
        }
    }

    val located = layers.located
    /** Each pin's own icon (its category color), to restore after it was highlighted. */
    val pinIcons = remember { mutableMapOf<String, android.graphics.drawable.Drawable>() }
    val highlight = remember { highlightPin(context) }
    /** Bubbles standing in for groups of crowded pins. */
    val groupMarkers = remember { mutableListOf<Marker>() }
    AndroidView(factory = { mapView }, update = { map ->
        zoomExact // read here so every zoom change re-runs this block and regroups the pins
        heatLayer.update(map, layers.heat)
        driveLayer.update(map, layers.drive)
        layers.cameras?.let { cameraLayer.update(map, it, zoom) }
        if (layers.hotspots != null) policeLayer.update(map, layers.hotspots, layers.live.orEmpty(), zoom)
        teamLayer.update(map, layers.together, layers.teammates, layers.teamEstimate)
        airLayer.update(map, layers.air)
        // Update markers in place so an open info window survives new scan results.
        val keys = located.mapTo(HashSet()) { it.uid }
        markers.keys.filterNot(keys::contains).forEach { key ->
            markers.remove(key)?.let { it.closeInfoWindow(); map.overlays.remove(it) }
        }
        circles.keys.filterNot(keys::contains).forEach { key -> circles.remove(key)?.let(map.overlays::remove) }
        for (d in located) {
            val marker = markers.getOrPut(d.uid) {
                Marker(map).apply {
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = ContextCompat.getDrawable(context, org.osmdroid.library.R.drawable.marker_default)
                        ?.mutate()?.apply { setTint(d.signature?.category.color().toArgb()) }
                    icon?.let { pinIcons[d.uid] = it }
                    val uid = d.uid
                    setOnMarkerClickListener { _, _ ->
                        InfoWindow.closeAllInfoWindowsOn(map)
                        ui.selected.value = uid
                        true
                    }
                    map.overlays.add(this)
                }
            }
            // A pin at the estimated location when there is one, else where it was strongest.
            val est = Detections.estimate(d)
            marker.position = est?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(d.lat!!, d.lon!!)
            // A circle is only worth drawing for the pin you tapped, or a matched device whose estimate
            // is fairly tight. Every nearby device's big circle from one spot just piles up as clutter.
            if (est != null && showCircle(d, est, layers.selected)) {
                val circle = circles.getOrPut(d.uid) {
                    Polygon(map).apply {
                        val c = d.signature?.category.color().toArgb()
                        fillPaint.color = (c and 0x00FFFFFF) or 0x30000000
                        outlinePaint.color = (c and 0x00FFFFFF) or 0x90000000.toInt()
                        outlinePaint.strokeWidth = 2f
                        setOnClickListener { _, _, _ -> false } // taps go to the pin
                        map.overlays.add(map.overlays.indexOf(marker).coerceAtLeast(0), this)
                    }
                }
                circle.points = Polygon.pointsAsCircle(GeoPoint(est.lat, est.lon), est.radiusM)
            } else {
                circles.remove(d.uid)?.let(map.overlays::remove)
            }
        }
        // The chosen pin stands out: bigger and bright, drawn on top of the rest.
        val chosenUid = layers.selected
        for ((uid, m) in markers) {
            val want = if (uid == chosenUid) highlight else pinIcons[uid]
            if (want != null && m.icon !== want) m.icon = want
        }
        markers[chosenUid]?.let { m -> map.overlays.remove(m); map.overlays.add(m) }
        groupPins(map, located, markers, circles, chosenUid, groupMarkers)
        map.invalidate()
    })
    return engine
}

/** A circle is worth drawing for the chosen pin, or a matched device whose estimate is fairly tight. */
internal fun showCircle(d: Detection, est: LocationEstimate, selected: String?) =
    d.uid == selected || (d.signature != null && est.radiusM <= MAX_CIRCLE_M)

/** History heatmap on the classic map: a soft red glow per flagged encounter, adding up where they cluster. */
private class HeatLayer {
    private var shown: List<GeoPoint>? = null
    private val overlay = object : Overlay() {
        var points: List<GeoPoint> = emptyList()
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: android.graphics.Canvas, mapView: MapView, shadow: Boolean) {
            if (shadow || points.isEmpty()) return
            val r = 28 * mapView.resources.displayMetrics.density
            val proj = mapView.projection
            val p = android.graphics.Point()
            for (g in points) {
                proj.toPixels(g, p)
                if (p.x < -r || p.y < -r || p.x > mapView.width + r || p.y > mapView.height + r) continue
                paint.shader = android.graphics.RadialGradient(
                    p.x.toFloat(), p.y.toFloat(), r, intArrayOf(0x66FF1744, 0x33FF9100, 0x00FF9100), null,
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), r, paint)
            }
        }
    }

    fun update(map: MapView, points: List<GeoPoint>) {
        if (points === shown) return
        shown = points
        overlay.points = points
        if (overlay !in map.overlays) map.overlays.add(1.coerceAtMost(map.overlays.size), overlay) // just above the tap catcher
    }
}

/** A recorded drive: its route as a blue line, and the flagged equipment seen along it as dots. */
private class DriveLayer {
    private var shown: Traces.Shown? = null
    private val overlays = mutableListOf<Overlay>()

    fun update(map: MapView, drive: Traces.Shown?) {
        if (drive === shown) return
        shown = drive
        overlays.forEach { (it as? Marker)?.closeInfoWindow() }
        map.overlays.removeAll(overlays.toSet())
        overlays.clear()
        if (drive == null) return
        overlays += org.osmdroid.views.overlay.Polyline(map).apply {
            setPoints(drive.points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = 0xCC1E88E5.toInt()
            outlinePaint.strokeWidth = 6 * map.resources.displayMetrics.density
            outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
        }
        for (d in drive.flagged) {
            val lat = d.estimate?.lat ?: d.lat ?: continue
            val lon = d.estimate?.lon ?: d.lon ?: continue
            overlays += Marker(map).apply {
                position = GeoPoint(lat, lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = android.graphics.drawable.BitmapDrawable(map.resources, LocationMarker.dot(map.context, d.signature?.category.color().toArgb()))
                title = d.title
                snippet = "${d.source.label} · ${time(map.context, d.firstSeen)}"
            }
        }
        map.overlays.addAll(1.coerceAtMost(map.overlays.size), overlays)
    }
}

/** Type a place, pick from what OpenStreetMap finds. */
@Composable
private fun PlaceSearchDialog(onDismiss: () -> Unit, onPick: (Geocoder.Place) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Geocoder.Place>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun go() {
        if (query.isBlank() || busy) return
        busy = true; message = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Geocoder.search(query) } }
            busy = false
            r.onSuccess { results = it; if (it.isEmpty()) message = "Nothing found - try adding the state" }
                .onFailure { message = "Search failed - check the internet connection" }
        }
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search a place") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("e.g. Little Rock, Arkansas") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { go() }),
                )
                if (busy) Text("Searching…", style = MaterialTheme.typography.bodySmall)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                results.forEach { p ->
                    Text(
                        p.name, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp),
                    )
                }
                Text(
                    "Uses OpenStreetMap's search, which is sent what you type - not your location.",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                )
            }
        },
        confirmButton = { TextButton(onClick = { go() }) { Text("Search") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** What Siren knows about the area around a searched place, filling in as it downloads. */
@Composable
private fun PlaceCard(
    place: Geocoder.Place,
    cameras: List<Camera>,
    hotspots: Map<String, List<PoliceData.Hotspot>>,
    live: Map<String, List<PoliceData.LiveCall>>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val radius = 25_000.0
    fun near(lat: Double, lon: Double) = Odometer.distanceM(place.lat, place.lon, lat, lon) <= radius
    val near = cameras.filter { near(it.lat, it.lon) }
    val plate = near.count { it.kind == CameraKind.PLATE_READER }
    val speed = near.size - plate
    val spots = hotspots.values.flatten().filter { near(it.lat, it.lon) }
    val calls = live.values.flatten().count { near(it.lat, it.lon) }
    val feeds = PoliceData.feeds.collectAsStateWithLifecycle().value.filter { it.covers(place.lat, place.lon) }
    val prefs = remember { Prefs(context) }
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(place.name.substringBefore(", United States"), style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            val small = MaterialTheme.typography.bodySmall
            Text("Within 15 mi (25 km):", style = small, color = Color.Gray)
            Text(
                if (!prefs.knownCameras) "Cameras: off (Settings > Cameras)"
                else "$plate plate reader${if (plate == 1) "" else "s"}" + (if (speed > 0) ", $speed speed / red-light camera${if (speed == 1) "" else "s"}" else ""),
                style = small,
            )
            val stops = spots.count { !it.isCrashes }
            val crashes = spots.count { it.isCrashes }
            Text(
                when {
                    !prefs.policeData -> "Police & traffic data: off (Settings > Police call data)"
                    feeds.isEmpty() -> "Police & traffic data: none on Siren's list for this area"
                    else -> listOfNotNull(
                        stops.takeIf { it > 0 }?.let { "$it traffic-stop hotspots" },
                        crashes.takeIf { it > 0 }?.let { "$it crash hotspots" },
                        calls.takeIf { it > 0 }?.let { "$it live police calls" },
                    ).joinToString(", ").ifEmpty { "Downloading ${feeds.joinToString { it.name }}…" }
                },
                style = small,
            )
            if (feeds.isEmpty()) PoliceData.suggestPlaceUrl(place.name.substringBefore(", United States"))?.let { url ->
                TextButton(
                    onClick = { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text("Suggest adding this place") }
            }
        }
    }
}

internal fun highlightPinDrawable(ctx: android.content.Context) = highlightPin(ctx)

/** The chosen pin: half again as big, bright cyan with a white rim, so it stands out among gray ones. */
private fun highlightPin(ctx: android.content.Context): android.graphics.drawable.Drawable {
    val base = ContextCompat.getDrawable(ctx, org.osmdroid.library.R.drawable.marker_default)!!
    val w = (base.intrinsicWidth * 1.6).toInt(); val h = (base.intrinsicHeight * 1.6).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    base.mutate().apply { setTint(0xFFFFFFFF.toInt()); setBounds(0, 0, w, h); draw(c) }
    val inset = (w * 0.09).toInt()
    ContextCompat.getDrawable(ctx, org.osmdroid.library.R.drawable.marker_default)!!.mutate().apply {
        setTint(0xFF00B8D4.toInt()); setBounds(inset, inset, w - inset, h - inset); draw(c)
    }
    return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
}

/**
 * Where pins pile up (hundreds on All nearby), groups of overlapping everyday pins become one
 * bubble with a count; tap it to zoom in. Matched devices and the chosen pin are never grouped,
 * and from street level up every pin shows.
 */
private fun groupPins(
    map: MapView,
    located: List<Detection>,
    markers: Map<String, Marker>,
    circles: Map<String, Polygon>,
    chosenUid: String?,
    groupMarkers: MutableList<Marker>,
) {
    map.overlays.removeAll(groupMarkers.toSet())
    groupMarkers.clear()
    markers.values.forEach { it.isEnabled = true }
    circles.values.forEach { it.isEnabled = true }
    if (map.width == 0 || map.zoomLevelDouble >= GROUP_UNTIL_ZOOM) return
    val radiusPx = 36 * map.resources.displayMetrics.density
    val proj = map.projection
    data class Group(var x: Float, var y: Float, val members: MutableList<String>)
    val groups = ArrayList<Group>()
    for (d in located) {
        if (d.signature != null || d.uid == chosenUid) continue
        val m = markers[d.uid] ?: continue
        val p = proj.toPixels(m.position, null)
        val g = groups.firstOrNull { kotlin.math.hypot(it.x - p.x, it.y - p.y) < radiusPx }
        if (g == null) groups += Group(p.x.toFloat(), p.y.toFloat(), mutableListOf(d.uid)) else g.members += d.uid
    }
    val res = map.resources
    for (g in groups.filter { it.members.size >= GROUP_MIN }) {
        g.members.forEach { markers[it]?.isEnabled = false; circles[it]?.isEnabled = false }
        val at = g.members.mapNotNull { markers[it]?.position }
        val center = GeoPoint(at.sumOf { it.latitude } / at.size, at.sumOf { it.longitude } / at.size)
        groupMarkers += Marker(map).apply {
            position = center
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = android.graphics.drawable.BitmapDrawable(res, countBubble(map.context, g.members.size))
            setOnMarkerClickListener { _, mv -> mv.controller.animateTo(center, mv.zoomLevelDouble + 2, 400L); true }
        }
    }
    // Under the regular pins' layer order doesn't matter much; on top so the count is readable.
    map.overlays.addAll(groupMarkers)
}

/** A gray disc with a white rim and the number of pins it stands for. */
private fun countBubble(ctx: android.content.Context, n: Int): android.graphics.Bitmap {
    val d = ctx.resources.displayMetrics.density
    val size = ((if (n >= 100) 40 else 34) * d).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = 0xFFFFFFFF.toInt(); c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.color = 0xFF546E7A.toInt(); c.drawCircle(size / 2f, size / 2f, size / 2f - 2.5f * d, paint)
    paint.color = 0xFFFFFFFF.toInt(); paint.textSize = 14 * d; paint.textAlign = android.graphics.Paint.Align.CENTER
    paint.isFakeBoldText = true
    c.drawText(n.toString(), size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2, paint)
    return bmp
}

/** Pins group into count bubbles below this zoom (street level and closer shows them all). */
private const val GROUP_UNTIL_ZOOM = 18.0
private const val GROUP_MIN = 4

/** Matched devices only show their estimate circle once it's within this radius (the tapped one always). */
internal const val MAX_CIRCLE_M = 150.0

/** The pin card on a short map: one line, so it doesn't cover the map. */
@Composable
private fun SlimPinCard(d: Detection, onDetails: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(d.name ?: d.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onDetails) { Text("Details") }
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

/** What a tapped pin is, with the same actions as the list: details, and hiding it. */
@Composable
private fun PinCard(d: Detection, onDetails: () -> Unit, onHide: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val est = Detections.estimate(d)
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(d.title, style = MaterialTheme.typography.titleMedium)
            if (d.signature != null && d.name != null) Text(d.name, style = MaterialTheme.typography.bodyMedium)
            d.guessLine?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF90A4AE)) }
            Text(
                buildString {
                    append("${d.source.label} · ${d.id}")
                    // 0 means Android gave no signal reading (some Wi-Fi results), not a strong one.
                    if (d.source != Source.RF) append(if (d.bestRssi < 0) " · strongest ${d.bestRssi} dBm" else " · no signal reading")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${d.count}× · first ${time(context, d.firstSeen)} · last ${time(context, d.lastSeen)}" +
                    (est?.let { " · estimated location ±${formatShortDistance(it.radiusM)}" } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDetails) { Text("Details") }
                TextButton(onClick = onHide) { Text("Hide") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/** Drones and police aircraft: an arrow pointing where each is heading, and a drone's operator if broadcast. */
private class AirLayer {
    private val markers = HashMap<String, Marker>()
    private val operators = HashMap<String, Marker>()
    private val latest = HashMap<String, AirContact>()
    /** Tapping a drone's or aircraft's bubble opens everything known about it. */
    var onDetails: (AirContact) -> Unit = {}

    /** The usual bubble, but tapping it opens the details instead of just closing it. */
    private inner class DetailsBubble(map: MapView, private val key: String) :
        org.osmdroid.views.overlay.infowindow.MarkerInfoWindow(org.osmdroid.library.R.layout.bonuspack_bubble, map) {
        override fun onOpen(item: Any?) {
            super.onOpen(item)
            view.setOnTouchListener(null)
            view.setOnClickListener {
                close()
                latest[key]?.let(onDetails)
            }
        }
    }

    fun marker(key: String): Marker? = markers[key]

    fun update(map: MapView, air: List<AirContact>) {
        val keys = air.mapTo(HashSet()) { it.key }
        for (gone in markers.keys - keys) markers.remove(gone)?.let { it.closeInfoWindow(); map.overlays.remove(it) }
        for (gone in operators.keys - keys) operators.remove(gone)?.let { it.closeInfoWindow(); map.overlays.remove(it) }
        latest.clear()
        air.associateByTo(latest) { it.key }
        for (c in air) {
            val color = airColor(c.kind).toArgb()
            if (c.lat != null && c.lon != null) {
                val m = markers.getOrPut(c.key) {
                    Marker(map).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        isFlat = true // turns with the map
                        infoWindow = DetailsBubble(map, c.key)
                        map.overlays.add(this)
                    }
                }
                val pointing = c.headingDeg != null
                if (m.icon == null || m.relatedObject != (pointing to color)) {
                    m.icon = android.graphics.drawable.BitmapDrawable(
                        map.resources,
                        if (pointing) LocationMarker.arrow(map.context, color) else LocationMarker.dot(map.context, color),
                    )
                    m.relatedObject = pointing to color
                }
                m.rotation = -(c.headingDeg ?: 0.0).toFloat()
                m.position = GeoPoint(c.lat, c.lon)
                m.title = c.kind.label + ": " + c.title
                m.snippet = listOfNotNull(c.detail, c.altitudeText, c.distanceM?.let { formatShortDistance(it) + " away" }).joinToString("<br>")
                m.subDescription = "Tap for everything known about it"
            }
            if (c.operatorLat != null && c.operatorLon != null) {
                val op = operators.getOrPut(c.key) {
                    Marker(map).apply {
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        icon = android.graphics.drawable.BitmapDrawable(map.resources, LocationMarker.dot(map.context, 0xFF424242.toInt()))
                        title = "Drone operator"
                        map.overlays.add(this)
                    }
                }
                op.position = GeoPoint(c.operatorLat, c.operatorLon)
                op.snippet = "Where the drone says its operator is (${c.detail})"
            }
        }
    }
}

/** Search together: the other searchers (green dots) and everyone's combined estimate (amber circle). */
private class TeamLayer {
    private var shown: Triple<Any?, Any?, Any?>? = null
    private val overlays = mutableListOf<Overlay>()

    fun update(
        map: MapView,
        session: SearchTogether.Session?,
        peers: Map<Int, SearchTogether.Peer>,
        estimate: LocationEstimate?,
    ) {
        val key = Triple(session, peers, estimate)
        if (shown == key) return
        shown = key
        overlays.forEach { (it as? Marker)?.closeInfoWindow() }
        map.overlays.removeAll(overlays.toSet())
        overlays.clear()
        if (session == null) { map.invalidate(); return }
        val ctx = map.context
        val peerIcon = android.graphics.drawable.BitmapDrawable(map.resources, LocationMarker.dot(ctx, 0xFF43A047.toInt()))
        for (p in peers.values) {
            if (p.lat == null || p.lon == null) continue
            overlays += Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = peerIcon
                title = p.name
                snippet = "Searching with you · via ${p.via}"
            }
        }
        if (estimate != null) {
            overlays += Polygon(map).apply {
                points = Polygon.pointsAsCircle(GeoPoint(estimate.lat, estimate.lon), estimate.radiusM)
                fillPaint.color = 0x40FFB300
                outlinePaint.color = 0xE0FFB300.toInt()
                outlinePaint.strokeWidth = 4f
                setOnClickListener { _, _, _ -> false }
            }
            overlays += Marker(map).apply {
                position = GeoPoint(estimate.lat, estimate.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                title = session.target?.label ?: "Search target"
                snippet = "Everyone's readings together: ±${formatShortDistance(estimate.radiusM)}, from ${estimate.readings} readings"
            }
        }
        map.overlays.addAll(overlays)
        map.invalidate()
    }
}

/** Traffic-stop hotspots (red), crash hotspots (orange) - bigger for busier places - and live police calls (blue). */
private class PoliceLayer {
    private var shown: Pair<Map<String, List<PoliceData.Hotspot>>, Map<String, List<PoliceData.LiveCall>>>? = null
    private var shownDetail = false
    private val overlays = mutableListOf<Overlay>()

    fun update(map: MapView, hotspots: Map<String, List<PoliceData.Hotspot>>, live: Map<String, List<PoliceData.LiveCall>>, zoom: Int) {
        // Hundreds of hotspots would bury a zoomed-out map: only the busiest show below zoom 13.
        val detail = zoom >= 13
        if (shown?.first === hotspots && shown?.second === live && detail == shownDetail) return
        shown = hotspots to live
        shownDetail = detail
        overlays.forEach { (it as? Marker)?.closeInfoWindow() }
        map.overlays.removeAll(overlays.toSet())
        overlays.clear()
        val feeds = PoliceData.feeds.value.associateBy { it.id }
        val ctx = map.context
        val icons = HashMap<Int, android.graphics.drawable.Drawable>()
        for ((feedId, spots) in hotspots) {
            val feed = feeds[feedId]
            val top = spots.maxOfOrNull { it.count } ?: continue
            for (h in spots) {
                if (!detail && h.count < top / 4) continue
                // Three sizes, by how busy the place is compared with the city's busiest.
                val size = when { h.count >= top / 2 -> 26; h.count >= top / 5 -> 20; else -> 14 }
                overlays += Marker(map).apply {
                    position = GeoPoint(h.lat, h.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    // Traffic stops red, crashes orange.
                    val color = if (h.isCrashes) 0xCCFB8C00.toInt() else 0xCCE53935.toInt()
                    icon = icons.getOrPut(size * 2 + if (h.isCrashes) 1 else 0) { disc(ctx, color, size) }
                    title = if (h.isCrashes) "Crash hotspot" else "Traffic-stop hotspot"
                    snippet = "${h.count} ${h.what} here ${h.period}" +
                        (h.peak?.let { "<br>Most around $it" } ?: "")
                    subDescription = feed?.let { "${it.source} (updated ${it.updated})" }
                }
            }
        }
        val liveIcon = disc(ctx, 0xFF1E88E5.toInt(), 16)
        for ((feedId, calls) in live) {
            val feed = feeds[feedId]
            for (c in calls) {
                overlays += Marker(map).apply {
                    position = GeoPoint(c.lat, c.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = liveIcon
                    title = "Police call: ${c.type}"
                    snippet = listOfNotNull(c.status, c.time?.let { "since $it" }, c.address).joinToString("<br>")
                    subDescription = feed?.let { "${it.source} (live)" }
                }
            }
        }
        // Under the cameras, pins and location dot.
        map.overlays.addAll(0, overlays)
        map.invalidate()
    }

    /** A filled disc with a white rim, [sizeDp] across. */
    private fun disc(ctx: android.content.Context, color: Int, sizeDp: Int): android.graphics.drawable.Drawable {
        val px = (sizeDp * ctx.resources.displayMetrics.density).roundToInt()
        val bmp = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(px / 2f, px / 2f, px / 2f, paint)
        paint.color = color
        canvas.drawCircle(px / 2f, px / 2f, px / 2f - px / 8f, paint)
        return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
    }
}

/** Draws known cameras: a translucent cone for each direction they face, and a pin. */
private class CameraLayer {
    private var shown: List<Camera>? = null
    private var shownZoom = -1
    private val overlays = mutableListOf<Overlay>()

    fun update(map: MapView, cameras: List<Camera>, zoom: Int) {
        if (cameras === shown && zoom == shownZoom) return
        shown = cameras
        shownZoom = zoom
        // Long enough to poke out from under the icon at any zoom, but at least the real reading range.
        val coneLength = maxOf(
            CONE_MIN_LENGTH_M,
            CONE_PIXELS * TileSystem.GroundResolution(map.mapCenter.latitude, zoom.toDouble()),
        )
        map.overlays.removeAll(overlays.toSet())
        overlays.clear()
        val plateIcon = ContextCompat.getDrawable(map.context, R.drawable.ic_alpr)
        val speedIcon = ContextCompat.getDrawable(map.context, R.drawable.ic_speed_camera)
        for (cam in cameras) {
            if (zoom >= MIN_CONE_ZOOM) for (view in cam.views) overlays += cone(map, cam, view, coneLength)
        }
        for (cam in cameras) {
            overlays += Marker(map).apply {
                position = GeoPoint(cam.lat, cam.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                this.icon = if (cam.kind == CameraKind.PLATE_READER) plateIcon else speedIcon
                title = cam.label
                snippet = buildString {
                    append(
                        if (cam.views.isEmpty()) "Direction not mapped"
                        else "Faces " + cam.views.joinToString(", ") { "${it.bearing.roundToInt()}° ${compass(it.bearing)}" }
                    )
                    cam.operator?.let { append("<br>Operated by ").append(it) }
                }
                subDescription = cam.source
            }
        }
        // Underneath the location dot and detection pins.
        map.overlays.addAll(0, overlays)
        map.invalidate()
    }

    private fun cone(map: MapView, cam: Camera, view: CameraView, length: Double) = Polygon(map).apply {
        val start = view.bearing - view.width / 2
        val steps = (view.width / 5).roundToInt().coerceAtLeast(2)
        points = buildList {
            add(GeoPoint(cam.lat, cam.lon))
            for (i in 0..steps) add(offset(cam.lat, cam.lon, length, start + view.width * i / steps))
            add(GeoPoint(cam.lat, cam.lon))
        }
        val rgb = if (cam.kind == CameraKind.PLATE_READER) 0x26A69A else 0xF9A825
        fillPaint.color = 0x40000000 or rgb
        outlinePaint.color = 0xA0000000.toInt() or rgb
        outlinePaint.strokeWidth = 2f
        setOnClickListener { _, _, _ -> false } // taps go to the pin, not the cone
    }

    private fun offset(lat: Double, lon: Double, meters: Double, bearing: Float): GeoPoint {
        val b = Math.toRadians(bearing.toDouble())
        val dLat = meters * cos(b) / 111_320.0
        val dLon = meters * sin(b) / (111_320.0 * cos(Math.toRadians(lat)))
        return GeoPoint(lat + dLat, lon + dLon)
    }

    companion object {
        /** Roughly how far a plate reader reads. */
        private const val CONE_MIN_LENGTH_M = 70.0
        private const val CONE_PIXELS = 90.0
        /** Zoomed out further than this, cones are just clutter. */
        private const val MIN_CONE_ZOOM = 12
    }
}

internal fun time(context: android.content.Context, millis: Long) =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME)

/**
 * Location overlay that, while following, turns the map so the direction of travel points up.
 * GPS bearing is noise when standing still, so the last heading is kept below walking-ish speed.
 */
private class HeadingUpLocationOverlay(
    provider: IMyLocationProvider,
    private val map: MapView,
    private val headingUp: () -> Boolean,
) : MyLocationNewOverlay(provider, map) {
    private var bearing: Float? = null
    private var bearingAt = 0L

    override fun onLocationChanged(location: Location?, source: IMyLocationProvider?) {
        super.onLocationChanged(location, source)
        if (location == null) return
        // Only a fresh fix while moving gives a real heading. The first fix after the map opens
        // (or the phone turns sideways) can be an old cached one, whose heading would turn the
        // map to wherever you were driving last - that left the map stuck rotated.
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > MAX_FIX_AGE_MS || !location.hasBearing() || location.speed < MIN_HEADING_SPEED_MPS) return
        bearing = location.bearing
        bearingAt = android.os.SystemClock.elapsedRealtime()
        if (isFollowLocationEnabled && headingUp()) map.post { map.mapOrientation = -location.bearing }
    }

    /** The heading from the last minute, if moving. */
    fun lastFreshBearing(): Float? = bearing?.takeIf { android.os.SystemClock.elapsedRealtime() - bearingAt < 60_000 }

    companion object {
        private const val MIN_HEADING_SPEED_MPS = 2.5f // ~5.6 mph
        private const val MAX_FIX_AGE_MS = 10_000L
    }
}

/** Map text size: how much bigger map tiles (and the street names drawn in them) are shown. */
object MapText {
    val SIZES = listOf(1f to "Normal", 1.5f to "Large", 2f to "Extra large", 2.5f to "Largest")
    val scale = kotlinx.coroutines.flow.MutableStateFlow(1f)

    fun load(context: android.content.Context) { scale.value = Prefs(context).mapTextScale }

    fun set(context: android.content.Context, v: Float) {
        Prefs(context).mapTextScale = v
        scale.value = v
    }
}

enum class MapTurn { HEADING, NORTH, FREE }

/** Where the map was last looking, shared by the normal and full-screen maps (for this app run). */
object MapState {
    var center: GeoPoint? = null
    var zoom = 15.0
    var following = true
    var orientation = 0f
    /** The place last searched for, so its card survives switching to full screen and back. */
    var searched: Geocoder.Place? = null

    fun rememberRaw(lat: Double, lon: Double, zoom: Double, orientation: Float, following: Boolean) {
        if (abs(lat) < 1 && abs(lon) < 1) return
        center = GeoPoint(lat, lon)
        this.zoom = zoom
        this.following = following
        this.orientation = orientation
    }

    fun remember(map: MapView, following: Boolean) {
        val c = map.mapCenter
        if (abs(c.latitude) < 1 && abs(c.longitude) < 1) return // the default before any fix
        center = GeoPoint(c.latitude, c.longitude)
        zoom = map.zoomLevelDouble
        this.following = following
        orientation = map.mapOrientation
    }
}

/** A one-off request for the map to zoom to a detection, e.g. from "Show on map" in its details. */
object MapFocus {
    data class Request(val uid: String, val lat: Double, val lon: Double, val title: String)

    val pending = kotlinx.coroutines.flow.MutableStateFlow<Request?>(null)

    fun request(d: Detection) {
        val lat = d.lat ?: return
        val lon = d.lon ?: return
        pending.value = Request(d.uid, lat, lon, d.title)
    }

    fun request(d: Detection, lat: Double, lon: Double) {
        pending.value = Request(d.uid, lat, lon, d.title)
    }
}

private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

/** "N", "NE"… for a bearing in degrees. */
fun compass(bearing: Float) = COMPASS[((((bearing % 360f) + 360f + 22.5f) % 360f) / 45f).toInt()]

/** The red alert bar across the top of the map: what's detected, how close, and how many more. */
@Composable
private fun AlertBar(alerts: List<Detection>, modifier: Modifier, onTap: (Detection) -> Unit) {
    val strongest = alerts.maxBy { it.rssi }
    // Flash when something new shows up.
    var seen by remember { mutableStateOf(emptySet<String>()) }
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(alerts.map { it.uid }.toSet()) {
        val now = alerts.mapTo(HashSet()) { it.uid }
        if ((now - seen).isNotEmpty()) repeat(3) { flash.animateTo(1f, androidx.compose.animation.core.tween(180)); flash.animateTo(0f, androidx.compose.animation.core.tween(260)) }
        seen = now
    }
    val red = Color(0xFFE53935)
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.lerp(red, Color.White, flash.value * 0.55f)),
        modifier = modifier.fillMaxWidth().clickable { onTap(strongest) },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "\u26A0 ${strongest.signature?.label ?: strongest.title}" +
                    (if (strongest.source != Source.RF) " \u00B7 ${away(distanceText(strongest))}" else "") +
                    (if (alerts.size > 1) " (+${alerts.size - 1} more)" else ""),
                color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
