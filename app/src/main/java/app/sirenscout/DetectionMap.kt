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
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
private const val FOCUS_ZOOM = 19.0

/** A few miles across: room for an aircraft or drone to move without leaving the screen. */
private const val AIR_FOCUS_ZOOM = 12.0

/**
 * OpenStreetMap view with one pin per detection, placed where its signal was strongest. Tapping a
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
) {
    val context = LocalContext.current
    /** How the map turns: with your heading, never (north up), or wherever you turned it by hand. */
    var turn by remember { mutableStateOf(if (Prefs(context).mapHeadingUp) MapTurn.HEADING else MapTurn.NORTH) }
    /** "Search a place": the dialog, and the place the map was sent to (with its summary card). */
    var searching by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(MapState.searched) }
    // The location overlay reads this off the main thread, so it can't read Compose state.
    val turnNow = remember { java.util.concurrent.atomic.AtomicReference(turn) }
    fun setTurn(t: MapTurn) { turn = t; turnNow.set(t) }
    val markers = remember { mutableMapOf<String, Marker>() }
    /** The pin whose card is showing, by detection uid. */
    val selectedState = remember { mutableStateOf<String?>(null) }
    var selected by selectedState
    /** Translucent uncertainty circles for estimated locations, by detection uid. */
    val circles = remember { mutableMapOf<String, Polygon>() }
    var following by remember { mutableStateOf(MapState.following) }
    var center by remember { mutableStateOf<GeoPoint?>(null) }
    var zoom by remember { mutableStateOf(15) }
    val showCameras = remember { Prefs(context).knownCameras }
    val cameras by KnownCameras.cameras.collectAsStateWithLifecycle()
    val speedCameras = remember { Prefs(context).speedCameras }
    val wantedCameras = remember(cameras) { cameras.wanted(speedCameras) }
    val cameraLayer = remember { CameraLayer() }
    val showPolice = remember { Prefs(context).policeData }
    val hotspots by PoliceData.hotspots.collectAsStateWithLifecycle()
    val liveCalls by PoliceData.live.collectAsStateWithLifecycle()
    val policeLayer = remember { PoliceLayer() }
    val airLayer = remember { AirLayer() }
    airLayer.onDetails = onAirDetails
    var hasLocation by remember {
        mutableStateOf(
            // Demo data (debug builds, for screenshots) never shows the real location, nor asks for it.
            DemoMode.active || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
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
            val me = HeadingUpLocationOverlay(GpsMyLocationProvider(context), this) { turnNow.get() == MapTurn.HEADING }
            // Two fingers turn the map by hand; it then stays where you put it until you reset it.
            overlays.add(object : org.osmdroid.views.overlay.gestures.RotationGestureOverlay(this) {
                override fun onRotate(deltaAngle: Float) {
                    super.onRotate(deltaAngle)
                    if (turnNow.get() != MapTurn.FREE) setTurn(MapTurn.FREE)
                }
            }.apply { isEnabled = true })
            if (!DemoMode.active) {
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
                    selectedState.value = null
                    return false
                }

                override fun longPressHelper(p: GeoPoint?) = false
            }))
            // Follow mode switches itself off when the map is dragged; mirror that into the UI.
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?): Boolean {
                    following = me.isFollowLocationEnabled
                    center = mapCenter as GeoPoint
                    MapState.remember(this@run, me.isFollowLocationEnabled)
                    return false
                }

                override fun onZoom(event: ZoomEvent?): Boolean {
                    zoom = zoomLevelDouble.roundToInt()
                    MapState.remember(this@run, me.isFollowLocationEnabled)
                    return false
                }
            })
            this to me
        }
    }

    if (showCameras) {
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { KnownCameras.ensureLoaded(context) } }
        // Load cameras around wherever the map is. Polled rather than keyed on the center, which
        // changes with every GPS fix while following; already-covered areas return immediately.
        LaunchedEffect(Unit) {
            while (true) {
                delay(3_000)
                val c = center ?: mapView.mapCenter.let { GeoPoint(it.latitude, it.longitude) }
                // 0,0 is the map's default before any fix - open ocean, nothing to download.
                if (abs(c.latitude) < 1 && abs(c.longitude) < 1) continue
                withContext(Dispatchers.IO) { KnownCameras.refreshAround(context, c.latitude, c.longitude) }
            }
        }
    }

    if (showPolice) {
        LaunchedEffect(Unit) { withContext(Dispatchers.IO) { PoliceData.ensureLoaded(context) } }
        // Like the cameras: download a city's police data when the map is looking at it.
        LaunchedEffect(Unit) {
            val live = Prefs(context).liveCalls
            while (true) {
                delay(10_000)
                val c = center ?: mapView.mapCenter.let { GeoPoint(it.latitude, it.longitude) }
                if (abs(c.latitude) < 1 && abs(c.longitude) < 1) continue
                withContext(Dispatchers.IO) { PoliceData.refreshAround(context, c.latitude, c.longitude, live) }
            }
        }
    }

    // "Show on map": stop following, zoom to street level on the spot, and open its pin.
    val focus by MapFocus.pending.collectAsStateWithLifecycle()
    val focusPin = remember { Marker(mapView) }
    LaunchedEffect(focus) {
        val f = focus ?: return@LaunchedEffect
        me.disableFollowLocation()
        following = false
        // After layout and the marker update, so the zoom sticks and the pin exists.
        mapView.post {
            val point = GeoPoint(f.lat, f.lon)
            mapView.mapOrientation = 0f
            org.osmdroid.views.overlay.infowindow.InfoWindow.closeAllInfoWindowsOn(mapView)
            // Aircraft move fast: zoom out to a few miles around where it is now, not street level.
            airLayer.marker(f.uid)?.let {
                mapView.controller.setZoom(AIR_FOCUS_ZOOM)
                mapView.controller.setCenter(it.position)
                it.showInfoWindow()
                mapView.invalidate()
                return@post
            }
            mapView.controller.setZoom(FOCUS_ZOOM)
            mapView.controller.setCenter(point)
            if (f.uid in markers) {
                selected = f.uid
                mapView.invalidate()
                return@post
            }
            val pin = focusPin.apply {
                // Not in the current list (e.g. filtered out): drop a temporary pin instead.
                position = point
                title = f.title
                snippet = "Strongest signal was here"
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                if (this !in mapView.overlays) mapView.overlays.add(this)
            }
            pin.showInfoWindow()
            mapView.invalidate()
        }
        MapFocus.pending.value = null
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
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    val located = detections.filter { it.lat != null && it.lon != null }
    Box(modifier.clipToBounds()) {
        AndroidView(factory = { mapView }, update = { map ->
            if (showCameras) cameraLayer.update(map, wantedCameras, zoom)
            if (showPolice) policeLayer.update(map, hotspots, liveCalls, zoom)
            airLayer.update(map, air)
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
                        val uid = d.uid
                        setOnMarkerClickListener { _, _ ->
                            InfoWindow.closeAllInfoWindowsOn(map)
                            selectedState.value = uid
                            true
                        }
                        map.overlays.add(this)
                    }
                }
                // A pin at the estimated location when there is one, else where it was strongest.
                val est = Detections.estimate(d)
                marker.position = est?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(d.lat!!, d.lon!!)
                if (est != null) {
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
            map.invalidate()
        })
        Column(
            Modifier.align(Alignment.TopStart).padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val small = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            FilledTonalButton(
                onClick = {
                    // Heading up <-> North up; from a hand-turned map, back to north first.
                    val next = if (turn == MapTurn.NORTH) MapTurn.HEADING else MapTurn.NORTH
                    setTurn(next)
                    Prefs(context).mapHeadingUp = next == MapTurn.HEADING
                    if (next == MapTurn.NORTH) mapView.mapOrientation = 0f
                    else me.lastFreshBearing()?.let { if (me.isFollowLocationEnabled) mapView.mapOrientation = -it }
                    MapState.remember(mapView, me.isFollowLocationEnabled)
                },
                contentPadding = small,
            ) {
                Text(when (turn) {
                    MapTurn.HEADING -> "\u2191 Heading up"
                    MapTurn.NORTH -> "N\u2191 North up"
                    MapTurn.FREE -> "N\u2191 Reset to north"
                })
            }
            FilledTonalButton(onClick = { searching = true }, contentPadding = small) { Text("Search a place") }
            onFullScreen?.let { open ->
                FilledTonalButton(onClick = open, contentPadding = small) { Text(if (fullScreen) "Exit full screen" else "Full screen") }
            }
        }
        if (located.isNotEmpty()) {
            FilledTonalButton(
                onClick = {
                    me.disableFollowLocation()
                    following = false
                    mapView.mapOrientation = 0f
                    if (turn == MapTurn.FREE) setTurn(if (Prefs(context).mapHeadingUp) MapTurn.HEADING else MapTurn.NORTH)
                    val points = located.map { d -> Detections.estimate(d)?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(d.lat!!, d.lon!!) }
                    if (points.size == 1) mapView.controller.animateTo(points[0], 17.0, 600L)
                    else mapView.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), true)
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) { Text("Show all") }
        }
        if (!hasLocation) {
            // Android 12+ ignores a request for precise location that doesn't also ask for approximate.
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
                val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
                hasLocation = granted
                if (granted) {
                    me.enableMyLocation()
                    me.enableFollowLocation()
                    following = true
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
        val chosen = selected?.let { uid -> located.firstOrNull { it.uid == uid } }
        if (chosen != null) {
            PinCard(
                chosen,
                onDetails = { onDetails(chosen) },
                onHide = { selected = null; onHide(chosen) },
                onClose = { selected = null },
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
        searched?.let { place ->
            if (chosen == null) PlaceCard(
                place, cameras, hotspots, liveCalls,
                onClose = { searched = null; MapState.searched = null },
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(0.72f),
            )
        }
        if (searching) PlaceSearchDialog(
            onDismiss = { searching = false },
            onPick = { place ->
                searching = false
                me.disableFollowLocation()
                following = false
                mapView.mapOrientation = 0f
                if (turn == MapTurn.FREE) setTurn(if (Prefs(context).mapHeadingUp) MapTurn.HEADING else MapTurn.NORTH)
                mapView.controller.setZoom(12.0)
                mapView.controller.setCenter(GeoPoint(place.lat, place.lon))
                center = GeoPoint(place.lat, place.lon)
                MapState.remember(mapView, false)
                searched = place
                MapState.searched = place
            },
        )
        if (!following && hasLocation && chosen == null) {
            FilledTonalButton(
                onClick = {
                    me.enableFollowLocation()
                    following = true
                    // Following again: back to your chosen mode rather than the hand-turned angle.
                    val mode = if (Prefs(context).mapHeadingUp) MapTurn.HEADING else MapTurn.NORTH
                    setTurn(mode)
                    if (mode == MapTurn.NORTH) mapView.mapOrientation = 0f
                    if (mapView.zoomLevelDouble < 15.0) mapView.controller.setZoom(16.0)
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Text("Follow me") }
        }
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
                    if (d.source != Source.RF) append(" · strongest ${d.bestRssi} dBm")
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

private fun time(context: android.content.Context, millis: Long) =
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
