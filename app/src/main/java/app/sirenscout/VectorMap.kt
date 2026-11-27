package app.sirenscout

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as PF
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** OpenFreeMap's free vector style (OpenStreetMap data, no key); text stays sharp at any zoom. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/**
 * MapLibre counts zoom on 512-pixel tiles, the classic map on 256: the same view is one level
 * lower here. Siren keeps zoom in classic units ([MapState], [MapEngine.moveTo]).
 */
private const val ZOOM_OFFSET = 1.0

/** Street level (classic-map zoom units). */
private const val STREET_ZOOM = 15.0

/** Further out than this (a state or more), following you shows nothing useful. */
private const val MIN_USEFUL_ZOOM = 11.0

/** Sharp vector map (MapLibre + OpenFreeMap), with the same layers as the classic map. */
@SuppressLint("MissingPermission")
@Composable
internal fun VectorMap(ui: MapUi, layers: MapLayers, onAirDetails: (AirContact) -> Unit): MapEngine {
    val context = LocalContext.current
    val holder = remember { VectorHolder(context, ui) }
    holder.onAirDetails = onAirDetails
    holder.latest = layers

    val textScale by MapText.scale.collectAsStateWithLifecycle()
    LaunchedEffect(textScale) { holder.applyTextScale(textScale) }
    val markerColor by LocationMarker.color.collectAsStateWithLifecycle()
    LaunchedEffect(markerColor) { holder.markerColor = markerColor; holder.startLocationIfAllowed() }

    // Heading up: turn the map with a real heading (fresh fix, moving), as the classic map does.
    LaunchedEffect(holder) {
        while (true) {
            delay(1_000)
            holder.trackHeading()
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val view = holder.view
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                // GPS only while the map is showing, like the classic map's location overlay.
                Lifecycle.Event.ON_RESUME -> { view.onResume(); holder.resumeGps() }
                Lifecycle.Event.ON_PAUSE -> { view.onPause(); holder.stopGps() }
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
        view.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.saveState()
            holder.stopGps()
            view.onPause(); view.onStop(); view.onDestroy()
        }
    }

    AndroidView(factory = { holder.view }, update = { holder.render(layers) })
    return holder
}

/** The MapLibre view, its layers, and the [MapEngine] the shared controls use. */
@SuppressLint("MissingPermission")
private class VectorHolder(private val context: android.content.Context, private val ui: MapUi) : MapEngine {
    var onAirDetails: (AirContact) -> Unit = {}
    var latest: MapLayers? = null
    var markerColor: Int = LocationMarker.color.value

    val view: MapView = run {
        Prefs(context).vectorStarting = true // cleared once the map has drawn (see MapStyle.load)
        VectorLib.prepare(context)
        MapLibre.getInstance(context)
        // Texture mode draws inside the app's own layout, so it renders reliably under the buttons
        // and cards on top (and in the emulator, where a separate surface didn't start drawing).
        MapView(context, org.maplibre.android.maps.MapLibreMapOptions.createFromAttributes(context).textureMode(true)).apply { onCreate(null) }
    }
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val pending = mutableListOf<() -> Unit>()
    private var locationOn = false
    /**
     * Whether the map shows a real place yet (a GPS fix, your own panning, a search). Until then it
     * shows a whole-country placeholder, which is never saved as "where the map was".
     */
    private var realView = MapState.center != null && MapState.zoom >= MIN_USEFUL_ZOOM
    private var gpsFix: Location? = null
    /** Follow as the map opens (as it was left), until location is on and MapLibre tracks for real. */
    private var wantFollow = MapState.following
    private var bearing: Float? = null
    private var bearingAt = 0L
    /** Each symbol layer's own text size, to scale for Map text size. */
    private val baseText = HashMap<String, Expression>()
    private var textScale = MapText.scale.value

    init {
        setCacheLimit()
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = false // the North up / Heading up button does this
            m.uiSettings.isAttributionEnabled = true // OpenFreeMap / OpenMapTiles / OpenStreetMap credits
            m.uiSettings.attributionGravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            val c = MapState.center?.takeIf { realView }
            val known = if (c == null) lastKnownPosition() else null
            m.cameraPosition = when {
                c != null -> CameraPosition.Builder()
                    .target(LatLng(c.latitude, c.longitude)).zoom(MapState.zoom - ZOOM_OFFSET).bearing(-MapState.orientation.toDouble()).build()
                // Not yet anywhere: start at street level where the phone last was.
                known != null -> CameraPosition.Builder().target(LatLng(known.latitude, known.longitude)).zoom(STREET_ZOOM - ZOOM_OFFSET).build()
                else -> CameraPosition.Builder().target(LatLng(39.5, -98.35)).zoom(3.0).build()
            }
            if (known != null) realView = true
            // Panning or zooming by hand makes whatever's shown a real view.
            m.addOnCameraMoveStartedListener { reason -> if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) realView = true }
            m.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                override fun onRotateBegin(detector: org.maplibre.android.gestures.RotateGestureDetector) {
                    if (ui.turnNow.get() != MapTurn.FREE) ui.setTurn(MapTurn.FREE)
                }
                override fun onRotate(detector: org.maplibre.android.gestures.RotateGestureDetector) {}
                override fun onRotateEnd(detector: org.maplibre.android.gestures.RotateGestureDetector) {}
            })
            m.addOnCameraIdleListener {
                val t = m.cameraPosition.target ?: return@addOnCameraIdleListener
                ui.center = GeoPoint(t.latitude, t.longitude)
                ui.zoom = m.cameraPosition.zoom + ZOOM_OFFSET
                saveState()
                latest?.let { l -> style?.let { s -> drawCameras(s, l.cameras) } } // cone length follows the zoom
            }
            m.addOnMapClickListener { point -> onTap(m, point); true }
            // Drawn without crashing: no need to fall back next time, even if the style can't load (offline).
            view.addOnDidFinishRenderingFrameListener { _, _, _ -> if (Prefs(context).vectorStarting) Prefs(context).vectorStarting = false }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                style = s
                addLayers(s)
                applyTextScale(textScale)
                startLocationIfAllowed()
                latest?.let { render(it) }
                pending.forEach { it() }
                pending.clear()
            }
        }
    }

    private fun setCacheLimit() {
        runCatching {
            org.maplibre.android.offline.OfflineManager.getInstance(context).setMaximumAmbientCacheSize(
                Prefs(context).mapCacheMb * 1_000_000L,
                object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
                    override fun onSuccess() {}
                    override fun onError(message: String) { AppLog.w("Vector map cache limit: $message") }
                },
            )
        }
    }

    // --- MapEngine ---

    override val lastFix: Location? get() = gpsFix
    override fun lastFreshBearing(): Float? = bearing?.takeIf { android.os.SystemClock.elapsedRealtime() - bearingAt < 60_000 }
    override val isFollowing: Boolean get() = if (locationOn) map?.locationComponent?.cameraMode != CameraMode.NONE else wantFollow

    /**
     * Following uses MapLibre's own tracking modes, which also turn the map: any camera move made
     * from outside (even turning it to north) cancels tracking, as dragging does.
     */
    private fun trackingMode(turn: MapTurn) = when (turn) {
        MapTurn.HEADING -> CameraMode.TRACKING_GPS // turns with the direction of travel
        MapTurn.NORTH -> CameraMode.TRACKING_GPS_NORTH
        MapTurn.FREE -> CameraMode.TRACKING // keeps the hand-turned angle
    }

    override fun follow(on: Boolean) {
        wantFollow = on
        val lc = map?.locationComponent ?: return
        if (!locationOn) return
        lc.cameraMode = if (on) trackingMode(ui.turnNow.get()) else CameraMode.NONE
        // From a state- or country-wide view, following you there is no use: come in to street level.
        val zoom = (map?.cameraPosition?.zoom ?: 0.0) + ZOOM_OFFSET
        if (on && zoom < MIN_USEFUL_ZOOM) lc.zoomWhileTracking(STREET_ZOOM - ZOOM_OFFSET, 700)
        saveState()
    }

    override fun bearing(): Float = map?.cameraPosition?.bearing?.toFloat() ?: 0f

    override fun setBearing(deg: Float) {
        val lc = map?.locationComponent
        if (lc != null && locationOn && lc.cameraMode != CameraMode.NONE) {
            // Following: switch tracking mode rather than move the camera (which would stop following).
            lc.cameraMode = if (deg == 0f && ui.turnNow.get() != MapTurn.HEADING) CameraMode.TRACKING_GPS_NORTH else trackingMode(ui.turnNow.get())
            return
        }
        map?.animateCamera(CameraUpdateFactory.bearingTo(deg.toDouble()), 400)
    }

    override fun moveTo(lat: Double, lon: Double, zoom: Double, animate: Boolean) {
        realView = true
        val update = CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), zoom - ZOOM_OFFSET)
        if (animate) map?.animateCamera(update, 600) else map?.moveCamera(update)
    }

    override fun fit(points: List<GeoPoint>) {
        val m = map ?: return
        val distinct = points.distinctBy { it.latitude to it.longitude }
        if (distinct.isEmpty()) return
        realView = true
        if (distinct.size == 1) { moveTo(distinct[0].latitude, distinct[0].longitude, 17.0, animate = true); return }
        val bounds = LatLngBounds.Builder().includes(distinct.map { LatLng(it.latitude, it.longitude) }).build()
        // Room for the buttons along the left and top edges.
        val dp = context.resources.displayMetrics.density
        val pad = intArrayOf((150 * dp).roundToInt(), (72 * dp).roundToInt(), (40 * dp).roundToInt(), (130 * dp).roundToInt())
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad[0], pad[1], pad[2], pad[3]), 600)
    }

    override fun center(): GeoPoint? = map?.cameraPosition?.target?.let { GeoPoint(it.latitude, it.longitude) }

    override fun startLocation() { startLocationIfAllowed(); follow(true) }

    override fun saveState() {
        if (!realView) return
        val m = map ?: return
        val t = m.cameraPosition.target ?: return
        MapState.rememberRaw(t.latitude, t.longitude, m.cameraPosition.zoom + ZOOM_OFFSET, -m.cameraPosition.bearing.toFloat(), isFollowing)
    }

    override fun afterLayout(block: () -> Unit) {
        if (style == null) pending += block else view.post(block)
    }

    override fun showTempPin(lat: Double, lon: Double, title: String) {
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>(SRC_TEMP)?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply { addStringProperty("icon", pinName(null)) })
        ui.info = MapInfo(title, "Strongest signal was here")
    }

    override fun focusAir(key: String): Boolean {
        val c = latest?.air?.firstOrNull { it.key == key } ?: return false
        val lat = c.lat ?: return false
        val lon = c.lon ?: return false
        moveTo(lat, lon, AIR_FOCUS_ZOOM)
        ui.info = airInfo(c)
        return true
    }

    // --- Location and heading ---

    fun startLocationIfAllowed() {
        val m = map ?: return
        val s = style ?: return
        if (DemoMode.active) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lc = m.locationComponent
        val options = LocationComponentOptions.builder(context)
            .foregroundTintColor(markerColor).bearingTintColor(markerColor).accuracyAlpha(0.12f)
            // Glide to each new fix in half the time since the last one, not 1.1x: less trailing behind.
            .trackingAnimationDurationMultiplier(0.5f).accuracyAnimationEnabled(false).build()
        if (!locationOn) {
            // Siren feeds GPS fixes itself (startGps): MapLibre's own engine mixes in late network fixes.
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(context, s).useDefaultLocationEngine(false).locationComponentOptions(options).build())
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.NORMAL
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                // Dragging the map stops following; mirror that into the controls.
                override fun onCameraTrackingDismissed() { wantFollow = false; ui.following = false; saveState() }
                override fun onCameraTrackingChanged(currentMode: Int) {}
            })
            locationOn = true
            lc.cameraMode = if (!realView || wantFollow) trackingMode(ui.turnNow.get()) else CameraMode.NONE
            startGps()
        } else {
            lc.applyStyle(options)
        }
    }

    fun trackHeading() {
        val loc = lastFix ?: return
        // Only a fresh fix while moving gives a real heading (see HeadingUpLocationOverlay).
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000
        if (ageMs > 10_000 || !loc.hasBearing() || loc.speed < 2.5f) return
        bearing = loc.bearing
        bearingAt = android.os.SystemClock.elapsedRealtime()
        // While following, MapLibre's tracking mode turns the map itself (see trackingMode).
    }

    private val gpsListener = android.location.LocationListener { loc -> onFix(loc) }
    private var gpsStarted = false

    /** GPS fixes straight from the chip, twice a second when available, like the classic map. */
    fun startGps() {
        if (gpsStarted) return
        val lm = context.getSystemService(android.location.LocationManager::class.java) ?: return
        runCatching {
            lm.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 500L, 0f, gpsListener, android.os.Looper.getMainLooper())
            // Network fixes only until GPS has a lock (indoors, just after start).
            if (lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(android.location.LocationManager.NETWORK_PROVIDER, 2_000L, 0f, gpsListener, android.os.Looper.getMainLooper())
            }
            gpsStarted = true
        }.onFailure { AppLog.w("Vector map location: ${it.message}") }
        lastKnownPosition()?.let { onFix(it) }
    }

    /** Back on screen: GPS again, if location was on. */
    fun resumeGps() { if (locationOn) startGps() }

    fun stopGps() {
        if (!gpsStarted) return
        runCatching { context.getSystemService(android.location.LocationManager::class.java)?.removeUpdates(gpsListener) }
        gpsStarted = false
    }

    private fun onFix(loc: Location) {
        val prev = gpsFix
        // A network fix never replaces a GPS fix from the last 10 seconds.
        if (loc.provider != android.location.LocationManager.GPS_PROVIDER && prev?.provider == android.location.LocationManager.GPS_PROVIDER &&
            loc.time - prev.time < 10_000
        ) return
        gpsFix = loc
        if (locationOn) map?.locationComponent?.forceLocationUpdate(loc)
        // The first real position: go there at street level (the map may still show the placeholder).
        if (!realView) {
            realView = true
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), STREET_ZOOM - ZOOM_OFFSET))
        }
    }

    private fun lastKnownPosition(): Location? = runCatching {
        val lm = context.getSystemService(android.location.LocationManager::class.java)
        listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { lm?.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()

    // --- Text size ---

    fun applyTextScale(scale: Float) {
        textScale = scale
        val s = style ?: return
        for (layer in s.layers) {
            if (layer !is SymbolLayer || layer.id.startsWith("siren-")) continue
            val base = baseText.getOrPut(layer.id) {
                layer.textSize.expression ?: literal(layer.textSize.value ?: 16f)
            }
            layer.setProperties(PF.textSize(if (scale == 1f) base else Expression.product(literal(scale), base)))
        }
    }

    // --- Layers ---

    private fun addLayers(s: Style) {
        val bitmaps = Images(context)
        for (cat in listOf(null) + Category.entries) s.addImage(pinName(cat), bitmaps.pin(cat))
        s.addImage("pin-selected", (highlightPinDrawable(context)).toBitmap())
        ContextCompat.getDrawable(context, R.drawable.ic_alpr)?.let { s.addImage("cam-plate", it.toBitmap()) }
        ContextCompat.getDrawable(context, R.drawable.ic_speed_camera)?.let { s.addImage("cam-speed", it.toBitmap()) }

        val empty = FeatureCollection.fromFeatures(emptyList())
        listOf(SRC_HEAT, SRC_POLICE, SRC_CONES, SRC_CIRCLES, SRC_DRIVE, SRC_DRIVE_DOTS, SRC_CAMERAS, SRC_TEAM, SRC_FLAGGED, SRC_AIR, SRC_TEMP, SRC_SELECTED)
            .forEach { s.addSource(GeoJsonSource(it, empty)) }
        s.addSource(GeoJsonSource(SRC_EVERYDAY, empty, GeoJsonOptions().withCluster(true).withClusterMaxZoom(17).withClusterRadius(45)))

        // The heatmap goes under the map's own labels so street names stay readable.
        val firstLabel = s.layers.firstOrNull { it is SymbolLayer }?.id
        val heat = HeatmapLayer("siren-heat", SRC_HEAT).withProperties(
            PF.heatmapRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), stop(5, 6f), stop(15, 30f))),
            PF.heatmapIntensity(Expression.interpolate(Expression.linear(), Expression.zoom(), stop(5, 0.6f), stop(15, 1.5f))),
            PF.heatmapOpacity(0.75f),
            PF.heatmapColor(
                Expression.interpolate(
                    Expression.linear(), Expression.heatmapDensity(),
                    stop(0, Expression.rgba(0, 0, 0, 0)), stop(0.2, Expression.rgba(255, 145, 0, 0.45)),
                    stop(0.6, Expression.rgba(255, 61, 0, 0.75)), stop(1, Expression.rgba(213, 0, 0, 0.9)),
                ),
            ),
        )
        if (firstLabel != null) s.addLayerBelow(heat, firstLabel) else s.addLayer(heat)

        fun add(layer: Layer) = s.addLayer(layer)
        add(CircleLayer("siren-police", SRC_POLICE).withProperties(
            PF.circleColor(Expression.toColor(get("color"))), PF.circleRadius(get("radius")),
            PF.circleStrokeColor("#FFFFFF"), PF.circleStrokeWidth(1.5f),
        ))
        add(FillLayer("siren-cones", SRC_CONES).withProperties(PF.fillColor(Expression.toColor(get("color"))), PF.fillOpacity(0.25f)))
        add(LineLayer("siren-cones-edge", SRC_CONES).withProperties(PF.lineColor(Expression.toColor(get("color"))), PF.lineWidth(1f), PF.lineOpacity(0.6f)))
        add(FillLayer("siren-circles", SRC_CIRCLES).withProperties(PF.fillColor(Expression.toColor(get("color"))), PF.fillOpacity(0.19f)))
        add(LineLayer("siren-circles-edge", SRC_CIRCLES).withProperties(PF.lineColor(Expression.toColor(get("color"))), PF.lineWidth(1.5f), PF.lineOpacity(0.6f)))
        add(LineLayer("siren-drive", SRC_DRIVE).withProperties(
            PF.lineColor("#1E88E5"), PF.lineWidth(5f), PF.lineOpacity(0.8f),
            PF.lineCap(Property.LINE_CAP_ROUND), PF.lineJoin(Property.LINE_JOIN_ROUND),
        ))
        add(CircleLayer("siren-drive-dots", SRC_DRIVE_DOTS).withProperties(
            PF.circleColor(Expression.toColor(get("color"))), PF.circleRadius(6f), PF.circleStrokeColor("#FFFFFF"), PF.circleStrokeWidth(2f),
        ))
        add(SymbolLayer("siren-cameras", SRC_CAMERAS).withProperties(
            PF.iconImage(get("icon")), PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
        ))
        add(CircleLayer("siren-team", SRC_TEAM).withProperties(
            PF.circleColor(Expression.toColor(get("color"))), PF.circleRadius(8f), PF.circleStrokeColor("#FFFFFF"), PF.circleStrokeWidth(2f),
        ))
        add(CircleLayer("siren-clusters", SRC_EVERYDAY).withFilter(has("point_count")).withProperties(
            PF.circleColor("#546E7A"), PF.circleRadius(17f), PF.circleStrokeColor("#FFFFFF"), PF.circleStrokeWidth(2.5f),
        ))
        add(SymbolLayer("siren-cluster-count", SRC_EVERYDAY).withFilter(has("point_count")).withProperties(
            PF.textField(Expression.toString(get("point_count"))), PF.textFont(arrayOf("Noto Sans Bold")),
            PF.textSize(14f), PF.textColor("#FFFFFF"), PF.textAllowOverlap(true), PF.textIgnorePlacement(true),
        ))
        val pin = arrayOf(PF.iconImage(get("icon")), PF.iconAnchor(Property.ICON_ANCHOR_BOTTOM), PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true))
        add(SymbolLayer("siren-everyday", SRC_EVERYDAY).withFilter(Expression.not(has("point_count"))).withProperties(*pin))
        add(SymbolLayer("siren-flagged", SRC_FLAGGED).withProperties(*pin))
        add(SymbolLayer("siren-air", SRC_AIR).withProperties(
            PF.iconImage(get("icon")), PF.iconRotate(get("heading")), PF.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PF.iconAllowOverlap(true), PF.iconIgnorePlacement(true),
        ))
        add(SymbolLayer("siren-temp", SRC_TEMP).withProperties(*pin))
        add(SymbolLayer("siren-selected", SRC_SELECTED).withProperties(*pin))
    }

    private var shown: MapLayers? = null
    private var shownCameraZoom = -1

    /** Puts [l] on the map; each part is redrawn only when it changed. */
    fun render(l: MapLayers) {
        val s = style ?: return
        val was = shown
        shown = l
        if (was?.heat !== l.heat) s.src(SRC_HEAT)?.setGeoJson(FeatureCollection.fromFeatures(l.heat.map { Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude)) }))
        if (was?.drive !== l.drive) drawDrive(s, l.drive)
        if (was?.cameras !== l.cameras) { shownCameraZoom = -1; drawCameras(s, l.cameras) }
        if (was?.hotspots !== l.hotspots || was?.live !== l.live) drawPolice(s, l)
        if (was?.together !== l.together || was?.teammates !== l.teammates || was?.teamEstimate !== l.teamEstimate || was?.located !== l.located || was?.selected != l.selected) {
            drawCircles(s, l)
        }
        if (was?.together !== l.together || was?.teammates !== l.teammates || was?.teamEstimate !== l.teamEstimate) drawTeam(s, l)
        if (was?.air !== l.air) drawAir(s, l.air)
        if (was?.located !== l.located || was?.selected != l.selected) drawPins(s, l)
    }

    private fun Style.src(id: String) = getSourceAs<GeoJsonSource>(id)

    private fun pinPoint(d: Detection): Point {
        val est = Detections.estimate(d)
        return Point.fromLngLat(est?.lon ?: d.lon!!, est?.lat ?: d.lat!!)
    }

    private fun drawPins(s: Style, l: MapLayers) {
        val everyday = ArrayList<Feature>(); val flagged = ArrayList<Feature>(); val selected = ArrayList<Feature>()
        for (d in l.located) {
            val f = Feature.fromGeometry(pinPoint(d)).apply {
                addStringProperty("uid", d.uid)
                addStringProperty("icon", if (d.uid == l.selected) "pin-selected" else pinName(d.signature?.category))
            }
            when {
                d.uid == l.selected -> selected += f
                d.signature != null -> flagged += f
                else -> everyday += f
            }
        }
        s.src(SRC_EVERYDAY)?.setGeoJson(FeatureCollection.fromFeatures(everyday))
        s.src(SRC_FLAGGED)?.setGeoJson(FeatureCollection.fromFeatures(flagged))
        s.src(SRC_SELECTED)?.setGeoJson(FeatureCollection.fromFeatures(selected))
    }

    private fun drawCircles(s: Style, l: MapLayers) {
        val features = ArrayList<Feature>()
        for (d in l.located) {
            val est = Detections.estimate(d) ?: continue
            if (!showCircle(d, est, l.selected)) continue
            features += Feature.fromGeometry(circle(est.lat, est.lon, est.radiusM)).apply { addStringProperty("color", css(d.signature?.category.color().toArgb())) }
        }
        val team = l.teamEstimate
        if (l.together != null && team != null) {
            features += Feature.fromGeometry(circle(team.lat, team.lon, team.radiusM)).apply { addStringProperty("color", "#FFB300") }
        }
        s.src(SRC_CIRCLES)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun drawTeam(s: Style, l: MapLayers) {
        val features = ArrayList<Feature>()
        if (l.together != null) {
            for (p in l.teammates.values) {
                if (p.lat == null || p.lon == null) continue
                features += Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                    addStringProperty("color", "#43A047")
                    addStringProperty("title", p.name)
                    addStringProperty("text", "Searching with you · via ${p.via}")
                }
            }
            l.teamEstimate?.let { e ->
                features += Feature.fromGeometry(Point.fromLngLat(e.lon, e.lat)).apply {
                    addStringProperty("color", "#FFB300")
                    addStringProperty("title", l.together.target?.label ?: "Search target")
                    addStringProperty("text", "Everyone's readings together: ±${formatShortDistance(e.radiusM)}, from ${e.readings} readings")
                }
            }
        }
        s.src(SRC_TEAM)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun drawPolice(s: Style, l: MapLayers) {
        val features = ArrayList<Feature>()
        val feeds = PoliceData.feeds.value.associateBy { it.id }
        for ((feedId, spots) in l.hotspots.orEmpty()) {
            val top = spots.maxOfOrNull { it.count } ?: continue
            for (h in spots) {
                // Three sizes, by how busy the place is compared with the city's busiest.
                val radius = when { h.count >= top / 2 -> 11f; h.count >= top / 5 -> 8.5f; else -> 6f }
                features += Feature.fromGeometry(Point.fromLngLat(h.lon, h.lat)).apply {
                    addStringProperty("color", if (h.isCrashes) "rgba(251,140,0,0.8)" else "rgba(229,57,53,0.8)")
                    addNumberProperty("radius", radius)
                    addStringProperty("title", if (h.isCrashes) "Crash hotspot" else "Traffic-stop hotspot")
                    addStringProperty("text", "${h.count} ${h.what} here ${h.period}" + (h.peak?.let { "\nMost around $it" } ?: ""))
                    feeds[feedId]?.let { addStringProperty("source", "${it.source} (updated ${it.updated})") }
                }
            }
        }
        for ((feedId, calls) in l.live.orEmpty()) for (c in calls) {
            features += Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply {
                addStringProperty("color", "#1E88E5")
                addNumberProperty("radius", 7f)
                addStringProperty("title", "Police call: ${c.type}")
                addStringProperty("text", listOfNotNull(c.status, c.time?.let { "since $it" }, c.address).joinToString("\n"))
                feeds[feedId]?.let { addStringProperty("source", "${it.source} (live)") }
            }
        }
        s.src(SRC_POLICE)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    fun drawCameras(s: Style, cameras: List<Camera>?) {
        val zoom = ((map?.cameraPosition?.zoom ?: 14.0) + ZOOM_OFFSET).roundToInt()
        if (zoom == shownCameraZoom && cameras === shown?.cameras) return
        shownCameraZoom = zoom
        val icons = ArrayList<Feature>(); val cones = ArrayList<Feature>()
        val lat = map?.cameraPosition?.target?.latitude ?: 0.0
        // Long enough to poke out from under the icon at any zoom, but at least the real reading range.
        val coneLength = maxOf(70.0, 90.0 * TileSystem.GroundResolution(lat, zoom.toDouble()))
        for (cam in cameras.orEmpty()) {
            val plate = cam.kind == CameraKind.PLATE_READER
            icons += Feature.fromGeometry(Point.fromLngLat(cam.lon, cam.lat)).apply {
                addStringProperty("icon", if (plate) "cam-plate" else "cam-speed")
                addStringProperty("title", cam.label)
                addStringProperty(
                    "text",
                    (if (cam.views.isEmpty()) "Direction not mapped" else "Faces " + cam.views.joinToString(", ") { "${it.bearing.roundToInt()}° ${compass(it.bearing)}" }) +
                        (cam.operator?.let { "\nOperated by $it" } ?: ""),
                )
                addStringProperty("source", cam.source)
            }
            if (zoom >= 12) for (v in cam.views) {
                val start = v.bearing - v.width / 2
                val steps = (v.width / 5).roundToInt().coerceAtLeast(2)
                val ring = buildList {
                    add(Point.fromLngLat(cam.lon, cam.lat))
                    for (i in 0..steps) add(offsetPoint(cam.lat, cam.lon, coneLength, start + v.width * i / steps))
                    add(Point.fromLngLat(cam.lon, cam.lat))
                }
                cones += Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply { addStringProperty("color", if (plate) "#26A69A" else "#F9A825") }
            }
        }
        s.src(SRC_CAMERAS)?.setGeoJson(FeatureCollection.fromFeatures(icons))
        s.src(SRC_CONES)?.setGeoJson(FeatureCollection.fromFeatures(cones))
    }

    private fun drawAir(s: Style, air: List<AirContact>) {
        val features = ArrayList<Feature>()
        for (c in air) {
            val color = airColor(c.kind).toArgb()
            if (c.lat != null && c.lon != null) {
                val pointing = c.headingDeg != null
                val icon = "air-${if (pointing) "arrow" else "dot"}-${Integer.toHexString(color)}"
                if (s.getImage(icon) == null) s.addImage(icon, if (pointing) LocationMarker.arrow(context, color) else LocationMarker.dot(context, color))
                features += Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply {
                    addStringProperty("icon", icon)
                    addNumberProperty("heading", c.headingDeg ?: 0.0)
                    addStringProperty("key", c.key)
                }
            }
            if (c.operatorLat != null && c.operatorLon != null) {
                val icon = "air-dot-operator"
                if (s.getImage(icon) == null) s.addImage(icon, LocationMarker.dot(context, 0xFF424242.toInt()))
                features += Feature.fromGeometry(Point.fromLngLat(c.operatorLon, c.operatorLat)).apply {
                    addStringProperty("icon", icon)
                    addNumberProperty("heading", 0)
                    addStringProperty("title", "Drone operator")
                    addStringProperty("text", "Where the drone says its operator is (${c.detail})")
                }
            }
        }
        s.src(SRC_AIR)?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun drawDrive(s: Style, drive: Traces.Shown?) {
        val line = drive?.points?.takeIf { it.size >= 2 }?.let { pts ->
            listOf(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it.lon, it.lat) })))
        }.orEmpty()
        s.src(SRC_DRIVE)?.setGeoJson(FeatureCollection.fromFeatures(line))
        val dots = drive?.flagged.orEmpty().mapNotNull { d ->
            val lat = d.estimate?.lat ?: d.lat ?: return@mapNotNull null
            val lon = d.estimate?.lon ?: d.lon ?: return@mapNotNull null
            Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                addStringProperty("color", css(d.signature?.category.color().toArgb()))
                addStringProperty("title", d.title)
                addStringProperty("text", "${d.source.label} · ${time(context, d.firstSeen)}")
            }
        }
        s.src(SRC_DRIVE_DOTS)?.setGeoJson(FeatureCollection.fromFeatures(dots))
    }

    // --- Taps ---

    private fun onTap(m: MapLibreMap, point: LatLng) {
        val p = m.projection.toScreenLocation(point)
        val r = 14 * context.resources.displayMetrics.density
        val box = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
        fun hit(vararg layers: String) = m.queryRenderedFeatures(box, *layers).firstOrNull()
        hit("siren-selected", "siren-flagged", "siren-everyday")?.getStringProperty("uid")?.let { uid ->
            ui.info = null
            ui.selected.value = uid
            return
        }
        hit("siren-clusters")?.let { f ->
            (f.geometry() as? Point)?.let { g ->
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(g.latitude(), g.longitude()), m.cameraPosition.zoom + 2), 400)
            }
            return
        }
        hit("siren-air")?.let { f ->
            val key = f.getStringProperty("key")
            val c = key?.let { k -> latest?.air?.firstOrNull { it.key == k } }
            ui.selected.value = null
            ui.info = if (c != null) airInfo(c) else MapInfo(f.getStringProperty("title") ?: "", f.getStringProperty("text") ?: "")
            return
        }
        hit("siren-cameras", "siren-team", "siren-drive-dots", "siren-police")?.let { f ->
            ui.selected.value = null
            ui.info = MapInfo(
                f.getStringProperty("title") ?: "",
                f.getStringProperty("text") ?: "",
                if (f.hasProperty("source")) f.getStringProperty("source") else null,
            )
            return
        }
        // An empty spot closes whatever card is open.
        ui.selected.value = null
        ui.info = null
        style?.src(SRC_TEMP)?.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
    }

    private fun airInfo(c: AirContact) = MapInfo(
        c.kind.label + ": " + c.title,
        listOfNotNull(c.detail, c.altitudeText, c.distanceM?.let { formatShortDistance(it) + " away" }).joinToString("\n"),
        onOpen = { onAirDetails(c) },
    )

    companion object {
        const val SRC_HEAT = "siren-heat-src"
        const val SRC_POLICE = "siren-police-src"
        const val SRC_CONES = "siren-cones-src"
        const val SRC_CIRCLES = "siren-circles-src"
        const val SRC_DRIVE = "siren-drive-src"
        const val SRC_DRIVE_DOTS = "siren-drive-dots-src"
        const val SRC_CAMERAS = "siren-cameras-src"
        const val SRC_TEAM = "siren-team-src"
        const val SRC_EVERYDAY = "siren-everyday-src"
        const val SRC_FLAGGED = "siren-flagged-src"
        const val SRC_AIR = "siren-air-src"
        const val SRC_TEMP = "siren-temp-src"
        const val SRC_SELECTED = "siren-selected-src"
    }
}

private fun pinName(cat: Category?) = "pin-" + (cat?.name ?: "none")

/** Pin bitmaps in each category's color, like the classic map's. */
private class Images(private val context: android.content.Context) {
    // Drawn through a canvas: toBitmap() on a tinted bitmap drawable returns the untinted picture.
    fun pin(cat: Category?): Bitmap {
        val d = ContextCompat.getDrawable(context, org.osmdroid.library.R.drawable.marker_default)!!.mutate()
        d.setTint(cat.color().toArgb())
        val bmp = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, bmp.width, bmp.height)
        d.draw(android.graphics.Canvas(bmp))
        return bmp
    }
}

/** "#RRGGBB" for an ARGB color, as map styles want. */
private fun css(argb: Int) = "#%06X".format(argb and 0xFFFFFF)

private fun offsetPoint(lat: Double, lon: Double, meters: Double, bearing: Float): Point {
    val b = Math.toRadians(bearing.toDouble())
    val dLat = meters * cos(b) / 111_320.0
    val dLon = meters * sin(b) / (111_320.0 * cos(Math.toRadians(lat)))
    return Point.fromLngLat(lon + dLon, lat + dLat)
}

/** A circle as a 64-sided polygon. */
private fun circle(lat: Double, lon: Double, radiusM: Double): Polygon =
    Polygon.fromLngLats(listOf((0..64).map { offsetPoint(lat, lon, radiusM, it * 360f / 64) }))
