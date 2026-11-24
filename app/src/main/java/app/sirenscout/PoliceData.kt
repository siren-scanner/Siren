package app.sirenscout

import android.content.Context
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
import java.time.LocalDate
import kotlin.math.cos

/**
 * Police call data that cities publish as open data, pulled automatically when you're near one
 * of the cities in assets/police_feeds.json (newest copy read from the project on GitHub):
 *  - "history" feeds (e.g. New Orleans, updated daily) become traffic-stop hotspots: places
 *    officers stopped drivers often in the last few months, with the usual time of day;
 *  - "live" feeds (e.g. Dallas, every few minutes) show active calls on the map.
 * Siren asks the city for its whole data set, so the city doesn't learn where you are.
 */
object PoliceData {
    data class Feed(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val radiusKm: Double,
        val domain: String,
        val datasets: List<String>,
        val live: Boolean,
        val timeField: String?,
        val pointField: String?,
        val where: String?,
        val days: Int,
        val what: String,
        val typeField: String?,
        val statusField: String?,
        val addressFields: List<String>,
        val city: String?,
        val state: String?,
        val updated: String,
        val source: String,
        val sourceUrl: String,
    )

    data class Hotspot(
        val feedId: String,
        val lat: Double,
        val lon: Double,
        val count: Int,
        /** "7-9 AM" when most stops there fall in one two-hour window. */
        val peak: String?,
        val days: Int,
        val what: String,
    ) {
        val id get() = "$feedId|%.5f|%.5f".format(java.util.Locale.US, lat, lon)
    }

    data class LiveCall(
        val feedId: String,
        val id: String,
        val lat: Double,
        val lon: Double,
        val type: String,
        val status: String?,
        val time: String?,
        val address: String,
    )

    private const val REGISTRY_URL =
        "https://raw.githubusercontent.com/siren-scanner/Siren/main/app/src/main/assets/police_feeds.json"
    private const val REGISTRY_MAX_AGE_MS = 24 * 3600_000L
    private const val HISTORY_MAX_AGE_MS = 20 * 3600_000L
    private const val LIVE_EVERY_MS = 2 * 60_000L
    private const val RETRY_AFTER_FAILURE_MS = 10 * 60_000L
    /** Grid cell for grouping stops: about a block. */
    internal const val CELL_M = 150.0
    /** Fewer stops than this in the period isn't a pattern. */
    internal const val MIN_STOPS = 5
    private const val MAX_HOTSPOTS_PER_FEED = 600
    private const val USER_AGENT = "Siren (Android; https://github.com/siren-scanner/Siren)"

    private val _feeds = MutableStateFlow<List<Feed>>(emptyList())
    val feeds: StateFlow<List<Feed>> = _feeds.asStateFlow()
    private val _hotspots = MutableStateFlow<Map<String, List<Hotspot>>>(emptyMap())
    val hotspots: StateFlow<Map<String, List<Hotspot>>> = _hotspots.asStateFlow()
    private val _live = MutableStateFlow<Map<String, List<LiveCall>>>(emptyMap())
    val live: StateFlow<Map<String, List<LiveCall>>> = _live.asStateFlow()
    /** Per feed: when it was last downloaded, or why it failed. */
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    private val mutex = Mutex()
    private var loaded = false
    private val lastFetch = HashMap<String, Long>()
    private val failedAt = HashMap<String, Long>()
    private var registryCheckedAt = 0L

    private fun dir(context: Context) = File(context.filesDir, "police_data").apply { mkdirs() }

    // --- The feed list ---

    internal fun parseRegistry(json: String): List<Feed> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("feeds") ?: return emptyList()
        fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotBlank() }
        fun JSONObject.list(k: String) = optJSONArray(k)?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
        return List(arr.length()) { arr.getJSONObject(it) }.mapNotNull { f ->
            runCatching {
                if (f.optString("type") != "socrata") return@mapNotNull null // the only kind Siren reads so far
                Feed(
                    id = f.getString("id"),
                    name = f.getString("name"),
                    lat = f.getDouble("lat"),
                    lon = f.getDouble("lon"),
                    radiusKm = f.optDouble("radiusKm", 30.0),
                    domain = f.getString("domain"),
                    datasets = f.list("datasets").ifEmpty { return@mapNotNull null },
                    live = f.optString("mode") == "live",
                    timeField = f.str("timeField"),
                    pointField = f.str("pointField"),
                    where = f.str("where"),
                    days = f.optInt("days", 90),
                    what = f.optString("what", "traffic stops"),
                    typeField = f.str("typeField"),
                    statusField = f.str("statusField"),
                    addressFields = f.list("addressFields"),
                    city = f.str("city"),
                    state = f.str("state"),
                    updated = f.optString("updated", ""),
                    source = f.optString("source", f.getString("name")),
                    sourceUrl = f.optString("sourceUrl", "https://${f.getString("domain")}"),
                )
            }.getOrNull()
        }
    }

    private fun loadLocked(context: Context) {
        if (loaded) return
        loaded = true
        val cached = File(dir(context), "feeds.json")
        val text = cached.takeIf { it.exists() }?.readText()
            ?: context.assets.open("police_feeds.json").bufferedReader().use { it.readText() }
        _feeds.value = runCatching { parseRegistry(text) }.getOrElse {
            parseRegistry(context.assets.open("police_feeds.json").bufferedReader().use { it.readText() })
        }
        // Saved hotspots and status from earlier downloads.
        val spots = HashMap<String, List<Hotspot>>()
        for (feed in _feeds.value.filter { !it.live }) {
            val f = File(dir(context), "${feed.id}.json")
            if (!f.exists()) continue
            runCatching {
                val j = JSONObject(f.readText())
                lastFetch[feed.id] = j.getLong("at")
                val a = j.getJSONArray("hotspots")
                spots[feed.id] = List(a.length()) { i ->
                    a.getJSONArray(i).let { h ->
                        Hotspot(feed.id, h.getDouble(0), h.getDouble(1), h.getInt(2), h.optString(3).ifEmpty { null }, feed.days, feed.what)
                    }
                }
                setStatus(feed.id, "${spots[feed.id]!!.size} hotspots · updated ${ago(lastFetch[feed.id]!!)}")
            }
        }
        _hotspots.value = spots
    }

    suspend fun ensureLoaded(context: Context) = mutex.withLock { loadLocked(context) }

    /** Reads the newest feed list from GitHub once a day, so cities can be added without an app update. */
    private fun refreshRegistry(context: Context) {
        val now = System.currentTimeMillis()
        if (now - registryCheckedAt < REGISTRY_MAX_AGE_MS) return
        registryCheckedAt = now
        runCatching {
            val text = get(REGISTRY_URL)
            val parsed = parseRegistry(text)
            if (parsed.isNotEmpty()) {
                File(dir(context), "feeds.json").writeText(text)
                _feeds.value = parsed
            }
        }.onFailure { AppLog.w("Police feed list download failed: ${it.message}") }
    }

    // --- Downloading ---

    /** Downloads whatever is due for the cities near [lat], [lon]. Safe to call often. */
    suspend fun refreshAround(context: Context, lat: Double, lon: Double, liveCalls: Boolean) = mutex.withLock {
        loadLocked(context)
        refreshRegistry(context)
        val now = System.currentTimeMillis()
        for (feed in _feeds.value) {
            if (Odometer.distanceM(lat, lon, feed.lat, feed.lon) > feed.radiusKm * 1000) {
                // Left the city: its live calls are no longer useful (hotspots stay for the map).
                if (feed.live && _live.value.containsKey(feed.id)) _live.value = _live.value - feed.id
                continue
            }
            if (feed.live && !liveCalls) continue
            if (now - (failedAt[feed.id] ?: 0L) < RETRY_AFTER_FAILURE_MS) continue
            val due = if (feed.live) LIVE_EVERY_MS else HISTORY_MAX_AGE_MS
            if (now - (lastFetch[feed.id] ?: 0L) < due) continue
            runCatching { if (feed.live) fetchLive(context, feed) else fetchHistory(context, feed) }
                .onSuccess { lastFetch[feed.id] = now; failedAt.remove(feed.id) }
                .onFailure {
                    failedAt[feed.id] = now
                    setStatus(feed.id, "Download failed (${it.message ?: it.javaClass.simpleName}) - will retry")
                    AppLog.w("Police data ${feed.id} failed", it)
                }
        }
    }

    private fun fetchHistory(context: Context, feed: Feed) {
        val stops = downloadStops(feed)
        val spots = aggregate(feed, stops)
        _hotspots.value = _hotspots.value + (feed.id to spots)
        val j = JSONObject().put("at", System.currentTimeMillis()).put("hotspots", JSONArray(spots.map {
            JSONArray().put(it.lat).put(it.lon).put(it.count).put(it.peak ?: "")
        }))
        File(dir(context), "${feed.id}.json").writeText(j.toString())
        setStatus(feed.id, "${spots.size} hotspots from ${stops.size} ${feed.what} · updated just now")
        AppLog.i("Police data ${feed.id}: ${stops.size} ${feed.what} -> ${spots.size} hotspots")
    }

    /** Every matching stop in the period: (lat, lon, hour of day or -1). */
    internal fun downloadStops(feed: Feed): List<Triple<Double, Double, Int>> {
        val time = feed.timeField ?: error("no time field")
        val point = feed.pointField ?: error("no point field")
        val since = LocalDate.now().minusDays(feed.days.toLong()).toString() + "T00:00:00"
        val where = listOfNotNull(feed.where, "$time > '$since'", "$point IS NOT NULL").joinToString(" AND ")
        val stops = ArrayList<Triple<Double, Double, Int>>()
        for (dataset in feed.datasets) {
            val rows = JSONArray(
                socrata(feed.domain, dataset, mapOf("\$select" to "$point,$time", "\$where" to where, "\$limit" to "100000"))
            )
            for (i in 0 until rows.length()) {
                val r = rows.getJSONObject(i)
                val coords = r.optJSONObject(point)?.optJSONArray("coordinates") ?: continue
                val hour = r.optString(time).takeIf { it.length >= 13 }?.substring(11, 13)?.toIntOrNull() ?: -1
                stops += Triple(coords.getDouble(1), coords.getDouble(0), hour)
            }
        }
        return stops
    }

    internal fun downloadLiveRows(feed: Feed) = JSONArray(socrata(feed.domain, feed.datasets.first(), mapOf("\$limit" to "2000")))

    private fun fetchLive(context: Context, feed: Feed) {
        val rows = downloadLiveRows(feed)
        val calls = ArrayList<LiveCall>()
        var looked = 0
        for (i in 0 until rows.length()) {
            val r = rows.getJSONObject(i)
            val address = liveAddress(r, feed.addressFields) ?: continue
            val type = feed.typeField?.let { r.optString(it) }?.takeIf { it.isNotBlank() } ?: "Police call"
            // Addresses become map positions through OpenStreetMap's search, slowly (its rules allow
            // about one lookup a second); the rest are placed on later refreshes.
            val place = Geocoder.cached(context, address, feed.city, feed.state)
                ?: if (looked < 15) { looked++; Geocoder.lookup(context, address, feed.city, feed.state) } else null
            if (place == null || place == Geocoder.NOT_FOUND) continue
            calls += LiveCall(
                feed.id, r.optString("incident_number").ifEmpty { "$address|$type" }, place.first, place.second,
                cleanType(type), feed.statusField?.let { r.optString(it) }?.takeIf { it.isNotBlank() },
                feed.timeField?.let { r.optString(it) }?.takeIf { it.isNotBlank() }?.take(5), address,
            )
        }
        _live.value = _live.value + (feed.id to calls)
        setStatus(feed.id, "${calls.size} of ${rows.length()} active calls on the map · updated just now")
    }

    /** "5600 Military Pkwy" from the feed's address fields. */
    internal fun liveAddress(r: JSONObject, fields: List<String>): String? =
        fields.mapNotNull { r.optString(it).trim().takeIf { s -> s.isNotEmpty() } }.joinToString(" ")
            .replace(Regex("\\s+"), " ").takeIf { it.isNotBlank() && fields.isNotEmpty() }

    /** "07 - Minor Accident" -> "Minor Accident". */
    internal fun cleanType(t: String) = t.replace(Regex("^[0-9A-Z]{1,4}\\s*-\\s*"), "").trim().trimStart('*').trim()

    private fun socrata(domain: String, dataset: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        return get("https://$domain/resource/$dataset.json?$query")
    }

    internal fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // --- Hotspots ---

    /** Groups stops into about-a-block cells and keeps the busy ones, busiest first. */
    internal fun aggregate(feed: Feed, stops: List<Triple<Double, Double, Int>>): List<Hotspot> {
        if (stops.isEmpty()) return emptyList()
        val dLat = CELL_M / 111_320.0
        val dLon = CELL_M / (111_320.0 * cos(Math.toRadians(feed.lat)))
        val cells = stops.groupBy { (lat, lon, _) -> Math.floorDiv((lat / dLat).toLong(), 1L) to Math.floorDiv((lon / dLon).toLong(), 1L) }
        return cells.values.filter { it.size >= MIN_STOPS }.map { pts ->
            Hotspot(
                feed.id, pts.sumOf { it.first } / pts.size, pts.sumOf { it.second } / pts.size, pts.size,
                peakHours(pts.map { it.third }), feed.days, feed.what,
            )
        }.sortedByDescending { it.count }.take(MAX_HOTSPOTS_PER_FEED)
    }

    /** "7-9 AM" when at least 35% of stops fall in one two-hour window. */
    internal fun peakHours(hours: List<Int>): String? {
        val valid = hours.filter { it in 0..23 }
        if (valid.size < MIN_STOPS) return null
        val byHour = IntArray(24).also { a -> valid.forEach { a[it]++ } }
        // The busiest two-hour window; on a tie, the one starting at the busier hour.
        val (start, n) = (0..23).map { h -> h to byHour[h] + byHour[(h + 1) % 24] }
            .maxWith(compareBy<Pair<Int, Int>> { it.second }.thenBy { byHour[it.first] })
        if (n * 100 < valid.size * 35) return null
        fun label(h: Int) = when (val x = h % 24) { 0 -> "12 AM"; 12 -> "12 PM"; in 1..11 -> "$x AM"; else -> "${x - 12} PM" }
        val a = label(start)
        val b = label(start + 2)
        // "7-9 AM" rather than "7 AM-9 AM" when both are in the same half of the day.
        return if (a.substringAfter(' ') == b.substringAfter(' ')) "${a.substringBefore(' ')}-$b" else "$a-$b"
    }

    /** Strong hotspots ahead of someone driving on [heading] within [radiusM]. */
    fun hotspotsAhead(all: List<Hotspot>, lat: Double, lon: Double, heading: Float, radiusM: Double, minCount: Int): List<Pair<Hotspot, Double>> =
        all.asSequence().filter { it.count >= minCount }.mapNotNull { h ->
            val d = Odometer.distanceM(lat, lon, h.lat, h.lon)
            if (d > radiusM) return@mapNotNull null
            if (d > 40 && angleBetween(bearingDeg(lat, lon, h.lat, h.lon), heading) > 45f) return@mapNotNull null
            h to d
        }.sortedBy { it.second }.toList()

    private fun setStatus(id: String, text: String) { _status.value = _status.value + (id to text) }

    private fun ago(ms: Long): String {
        val minutes = (System.currentTimeMillis() - ms) / 60_000
        return when {
            minutes < 2 -> "just now"
            minutes < 90 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / 1440} days ago"
        }
    }
}

/** Street addresses to map positions through OpenStreetMap's Nominatim search, cached on the phone. */
object Geocoder {
    /** Remembered "no such address", so it isn't looked up again. */
    val NOT_FOUND = 999.0 to 999.0
    private var cache: MutableMap<String, Pair<Double, Double>>? = null
    private var lastLookup = 0L

    private fun file(context: Context) = File(context.filesDir, "police_data/geocode.json")

    private fun key(address: String, city: String?, state: String?) = "$address, $city, $state".uppercase()

    @Synchronized
    private fun load(context: Context): MutableMap<String, Pair<Double, Double>> = cache ?: HashMap<String, Pair<Double, Double>>().also { m ->
        runCatching {
            val j = JSONObject(file(context).readText())
            j.keys().forEach { k -> val a = j.getJSONArray(k); m[k] = a.getDouble(0) to a.getDouble(1) }
        }
        cache = m
    }

    @Synchronized
    fun cached(context: Context, address: String, city: String?, state: String?) = load(context)[key(address, city, state)]

    @Synchronized
    fun lookup(context: Context, address: String, city: String?, state: String?): Pair<Double, Double>? {
        val wait = 1_100 - (System.currentTimeMillis() - lastLookup)
        if (wait > 0) Thread.sleep(wait)
        lastLookup = System.currentTimeMillis()
        val q = listOfNotNull("street=$address", city?.let { "city=$it" }, state?.let { "state=$it" })
            .joinToString("&") { p -> p.substringBefore('=') + "=" + URLEncoder.encode(p.substringAfter('='), "UTF-8") }
        val result = runCatching {
            val a = JSONArray(PoliceData.get("https://nominatim.openstreetmap.org/search?$q&countrycodes=us&format=json&limit=1"))
            if (a.length() == 0) NOT_FOUND else a.getJSONObject(0).let { it.getString("lat").toDouble() to it.getString("lon").toDouble() }
        }.getOrNull() ?: return null // network trouble: try again later
        val m = load(context)
        m[key(address, city, state)] = result
        while (m.size > 5000) m.remove(m.keys.first())
        runCatching {
            val j = JSONObject()
            m.forEach { (k, v) -> j.put(k, JSONArray().put(v.first).put(v.second)) }
            file(context).writeText(j.toString())
        }
        return result
    }
}
