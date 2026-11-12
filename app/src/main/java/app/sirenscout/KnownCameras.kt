package app.sirenscout

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One direction a camera looks: compass bearing of the center and total width, in degrees. */
data class CameraView(val bearing: Float, val width: Float = DEFAULT_VIEW_WIDTH)

const val DEFAULT_VIEW_WIDTH = 50f

/** A license plate reader mapped in OpenStreetMap (largely by the DeFlock project). */
data class Camera(
    val id: Long,
    val lat: Double,
    val lon: Double,
    /** Where the camera points; empty when nobody mapped it. */
    val views: List<CameraView>,
    val maker: String?,
    val operator: String?,
) {
    val label get() = maker?.let { "$it plate reader" } ?: "Plate reader"
}

/** Downloads and caches plate-reader locations around wherever the user is or is looking. */
object KnownCameras {
    private const val FETCH_RADIUS_M = 25_000
    /** An area counts as covered when we've fetched within this distance of it. */
    private const val COVERED_M = 15_000.0
    private const val MAX_AGE_MS = 7 * 24 * 3600_000L
    /** Public Overpass servers, tried in order. They're free and often busy, so be patient. */
    private val OVERPASS_URLS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    private const val RETRY_AFTER_FAILURE_MS = 2 * 60_000L

    private data class Area(val lat: Double, val lon: Double, val fetchedAt: Long)

    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras: StateFlow<List<Camera>> = _cameras.asStateFlow()

    private var areas = listOf<Area>()
    private var loaded = false
    private var nextAttemptAt = 0L
    private val mutex = Mutex()

    private fun file(context: Context) = File(context.filesDir, "known_cameras.json")

    /** Fetches cameras around a point unless that area was fetched recently. Safe to call often. */
    suspend fun refreshAround(context: Context, lat: Double, lon: Double) = mutex.withLock {
        if (!loaded) load(context)
        val now = System.currentTimeMillis()
        val covered = areas.any {
            now - it.fetchedAt < MAX_AGE_MS && Odometer.distanceM(it.lat, it.lon, lat, lon) < COVERED_M
        }
        if (covered || now < nextAttemptAt) return@withLock
        // The map-data servers only learn the area (rounded to 0.1°, about 10 km), not where you are.
        // Within 15 km of the rounded spot is still at least 10 km inside the 25 km downloaded.
        val qLat = roundForQuery(lat)
        val qLon = roundForQuery(lon)
        val fetched = OVERPASS_URLS.firstNotNullOfOrNull { url ->
            runCatching { fetch(url, qLat, qLon) }
                .onFailure { AppLog.w("Known camera download from $url failed", it) }
                .getOrNull()
        }
        if (fetched == null) {
            nextAttemptAt = now + RETRY_AFTER_FAILURE_MS
            return@withLock
        }
        val merged = (_cameras.value.associateBy { it.id } + fetched.associateBy { it.id }).values.toList()
        areas = areas.filter { now - it.fetchedAt < MAX_AGE_MS } + Area(qLat, qLon, now)
        _cameras.value = merged
        save(context)
        AppLog.i("Known cameras: ${fetched.size} downloaded, ${merged.size} cached")
    }

    internal fun roundForQuery(deg: Double) = Math.round(deg * 10) / 10.0

    private fun fetch(url: String, lat: Double, lon: Double): List<Camera> {
        val query = """[out:json][timeout:25];node["surveillance:type"="ALPR"](around:$FETCH_RADIUS_M,$lat,$lon);out;"""
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "SirenScout/0.1 (Android)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            if (conn.responseCode != 200) throw IllegalStateException("Overpass HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            return parseOverpass(json)
        } finally {
            conn.disconnect()
        }
    }

    fun parseOverpass(json: JSONObject): List<Camera> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        return List(elements.length()) { elements.getJSONObject(it) }.mapNotNull { e ->
            if (e.optString("type") != "node") return@mapNotNull null
            val tags = e.optJSONObject("tags") ?: JSONObject()
            fun tag(key: String) = tags.optString(key).takeIf { it.isNotBlank() }
            Camera(
                id = e.getLong("id"),
                lat = e.getDouble("lat"),
                lon = e.getDouble("lon"),
                views = parseDirections(tag("camera:direction") ?: tag("direction")),
                maker = tag("manufacturer") ?: tag("brand"),
                operator = tag("operator"),
            )
        }
    }

    private fun load(context: Context) {
        loaded = true
        val f = file(context)
        if (!f.exists()) return
        runCatching {
            val json = JSONObject(f.readText())
            val a = json.getJSONArray("areas")
            areas = List(a.length()) { a.getJSONObject(it) }
                .map { Area(it.getDouble("lat"), it.getDouble("lon"), it.getLong("t")) }
            val c = json.getJSONArray("cameras")
            _cameras.value = List(c.length()) { c.getJSONObject(it) }.map {
                val views = it.getJSONArray("views")
                Camera(
                    id = it.getLong("id"),
                    lat = it.getDouble("lat"),
                    lon = it.getDouble("lon"),
                    views = List(views.length()) { i ->
                        views.getJSONArray(i).let { v -> CameraView(v.getDouble(0).toFloat(), v.getDouble(1).toFloat()) }
                    },
                    maker = it.optString("maker").takeIf { s -> s.isNotEmpty() },
                    operator = it.optString("operator").takeIf { s -> s.isNotEmpty() },
                )
            }
        }
    }

    /** Called from inside the mutex. */
    private fun save(context: Context) {
        val json = JSONObject()
            .put("areas", JSONArray(areas.map { JSONObject().put("lat", it.lat).put("lon", it.lon).put("t", it.fetchedAt) }))
            .put("cameras", JSONArray(_cameras.value.map {
                JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon)
                    .put("views", JSONArray(it.views.map { v -> JSONArray().put(v.bearing.toDouble()).put(v.width.toDouble()) }))
                    .put("maker", it.maker ?: "").put("operator", it.operator ?: "")
            }))
        file(context).writeText(json.toString())
    }

    /** Ensures the cache is read so the map can show cameras before any fetch. */
    suspend fun ensureLoaded(context: Context) = mutex.withLock { if (!loaded) load(context) }
}

private val CARDINALS = listOf(
    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
).withIndex().associate { (i, name) -> name to i * 22.5f }

/**
 * Parses OSM direction values: degrees ("270"), cardinals ("NE"), several separated by ";",
 * and ranges ("45-135", an explicit field of view).
 */
fun parseDirections(value: String?): List<CameraView> {
    if (value.isNullOrBlank()) return emptyList()
    return value.split(';').mapNotNull { part ->
        val p = part.trim().uppercase()
        (CARDINALS[p] ?: p.toFloatOrNull()?.let(::normalizeDegrees))?.let { return@mapNotNull CameraView(it) }
        val range = Regex("^(\\d+(?:\\.\\d+)?)\\s*-\\s*(\\d+(?:\\.\\d+)?)$").find(p) ?: return@mapNotNull null
        val a = range.groupValues[1].toFloat()
        var b = range.groupValues[2].toFloat()
        if (b < a) b += 360f // e.g. 315-45 wraps through north
        CameraView(normalizeDegrees((a + b) / 2), b - a)
    }
}

private fun normalizeDegrees(d: Float) = ((d % 360f) + 360f) % 360f

/** Smallest angle between two bearings, 0..180. */
fun angleBetween(a: Float, b: Float): Float {
    val d = abs(normalizeDegrees(a) - normalizeDegrees(b))
    return if (d > 180f) 360f - d else d
}

fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lon2 - lon1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return normalizeDegrees(Math.toDegrees(atan2(y, x)).toFloat())
}

enum class Facing(val text: String) {
    TOWARD("facing toward you - can read your front plate"),
    AWAY("facing the way you're going - reads your rear plate as you pass"),
    ACROSS("facing across your path"),
    UNKNOWN("direction not mapped"),
}

/** Which way a camera faces relative to someone traveling on [heading]. */
fun facing(camera: Camera, heading: Float?): Facing {
    if (camera.views.isEmpty() || heading == null) return Facing.UNKNOWN
    if (camera.views.any { angleBetween(it.bearing, heading + 180f) <= 60f }) return Facing.TOWARD
    if (camera.views.any { angleBetween(it.bearing, heading) <= 60f }) return Facing.AWAY
    return Facing.ACROSS
}

/**
 * Cameras within [radiusM] that are ahead of you. With no reliable heading (standing still),
 * everything within range counts.
 */
fun camerasAhead(
    cameras: List<Camera>,
    lat: Double,
    lon: Double,
    heading: Float?,
    radiusM: Double,
): List<Pair<Camera, Double>> = cameras.mapNotNull { c ->
    val d = Odometer.distanceM(lat, lon, c.lat, c.lon)
    if (d > radiusM) return@mapNotNull null
    if (heading != null && d > 30 && angleBetween(bearingDeg(lat, lon, c.lat, c.lon), heading) > 60f) return@mapNotNull null
    c to d
}.sortedBy { it.second }
