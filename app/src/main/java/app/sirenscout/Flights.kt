package app.sirenscout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One airport from assets/airports.txt (OurAirports, built by tools/build-airports.py). */
data class Airport(val iata: String, val icao: String, val name: String, val city: String, val country: String, val lat: Double, val lon: Double) {
    val code get() = iata.ifEmpty { icao }
    val label get() = "$code · ${city.ifEmpty { name }}"
}

object Airports {
    @Volatile private var byCode: Map<String, Airport>? = null

    fun find(context: Context, code: String): Airport? {
        val c = code.trim().uppercase()
        if (c.isEmpty()) return null
        return load(context)[c]
    }

    private fun load(context: Context): Map<String, Airport> = byCode ?: synchronized(this) {
        byCode ?: context.assets.open("airports.txt").bufferedReader().use { parse(it.readText()) }.also { byCode = it }
    }

    fun parse(text: String): Map<String, Airport> {
        val map = HashMap<String, Airport>()
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            if (f.size < 7) continue
            val a = Airport(f[0], f[1], f[2], f[3], f[4], f[5].toDoubleOrNull() ?: continue, f[6].toDoubleOrNull() ?: continue)
            if (a.icao.isNotEmpty()) map.putIfAbsent(a.icao, a)
            if (a.iata.isNotEmpty()) map[a.iata] = a // IATA codes win: they're what's on tickets
        }
        return map
    }
}

/** A flight as the ADS-B feed sees it right now. */
data class LiveFlight(
    val hex: String,
    val callsign: String?,
    val registration: String?,
    val typeCode: String?,
    val lat: Double?,
    val lon: Double?,
    val altitudeFt: Int?,
    val onGround: Boolean,
    val speedKt: Double?,
    val trackDeg: Double?,
    val verticalRateFpm: Int?,
    val selectedAltitudeFt: Int?,
    val squawk: String?,
    val emergency: String?,
    val secondsSinceSeen: Double?,
)

/**
 * A flight the user asked to follow, with optional airports they entered and the days they expect
 * it ([startDay]..[endDay], ISO dates; blank: from now for 36 hours).
 */
data class TrackedFlight(
    val query: String,
    val from: String = "",
    val to: String = "",
    val startDay: String = "",
    val endDay: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    /** Status messages already sent ("<day>:<what>"), so each goes out once a day. */
    val notified: Set<String> = emptySet(),
    val phase: String = "",
    val lastSeenAt: Long = 0,
) {
    val id get() = query.uppercase()

    /** "Nov 15" or "Nov 15 – Nov 17"; empty without dates. */
    val daysText: String
        get() {
            if (startDay.isEmpty()) return ""
            val f = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d")
            val a = java.time.LocalDate.parse(startDay)
            val b = endDay.takeIf { it.isNotEmpty() }?.let(java.time.LocalDate::parse) ?: a
            return if (a == b) a.format(f) else "${a.format(f)} – ${b.format(f)}"
        }
}

/**
 * Looks flights up on the adsb.lol feed by flight number (AA100), callsign (AAL100), tail number
 * (N12345) or ICAO address, anywhere in the world, and keeps the user's tracked flights.
 */
object Flights {
    private const val API = "https://api.adsb.lol/v2"
    /** Tracked flights without dates are dropped this long after they're added. */
    private const val KEEP_MS = 36 * 3_600_000L
    /** A flight "day" runs until this hour the next morning, so a late flight landing after midnight still counts. */
    private const val DAY_ENDS_AT_HOUR = 4L

    /** The flight day [now] falls in, as an ISO date. */
    fun tripDay(now: Long = System.currentTimeMillis()): String =
        java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).minusHours(DAY_ENDS_AT_HOUR).toLocalDate().toString()

    /** Whether [t] is expected on [day] (always, for a flight without dates). */
    fun isActive(t: TrackedFlight, day: String): Boolean =
        t.startDay.isEmpty() || (day >= t.startDay && day <= t.endDay.ifEmpty { t.startDay })

    /** Still worth keeping: its last day hasn't passed, or (without dates) it was added in the last 36 hours. */
    private fun current(t: TrackedFlight, now: Long) =
        if (t.startDay.isEmpty()) now - t.addedAt < KEEP_MS else tripDay(now) <= t.endDay.ifEmpty { t.startDay }

    /** Two-letter airline codes on tickets, to the three-letter codes aircraft broadcast, and the airline. */
    val AIRLINES: Map<String, Pair<String, String>> = listOf(
        "AA AAL American Airlines", "DL DAL Delta Air Lines", "UA UAL United Airlines", "WN SWA Southwest Airlines",
        "B6 JBU JetBlue", "AS ASA Alaska Airlines", "NK NKS Spirit Airlines", "F9 FFT Frontier Airlines",
        "G4 AAY Allegiant Air", "HA HAL Hawaiian Airlines", "SY SCX Sun Country Airlines", "MX MXY Breeze Airways",
        "QX QXE Horizon Air", "OO SKW SkyWest Airlines", "YX RPA Republic Airways", "9E EDV Endeavor Air",
        "MQ ENY Envoy Air", "OH JIA PSA Airlines", "YV ASH Mesa Airlines", "ZW AWI Air Wisconsin", "G7 GJS GoJet Airlines",
        "C5 UCA CommuteAir", "PT PDT Piedmont Airlines", "AC ACA Air Canada", "WS WJA WestJet", "AM AMX Aeromexico",
        "Y4 VOI Volaris", "BA BAW British Airways", "VS VIR Virgin Atlantic", "LH DLH Lufthansa", "AF AFR Air France",
        "KL KLM KLM", "IB IBE Iberia", "EI EIN Aer Lingus", "LX SWR Swiss", "OS AUA Austrian Airlines", "SK SAS SAS",
        "AY FIN Finnair", "TK THY Turkish Airlines", "EK UAE Emirates", "QR QTR Qatar Airways", "EY ETD Etihad Airways",
        "SQ SIA Singapore Airlines", "CX CPA Cathay Pacific", "JL JAL Japan Airlines", "NH ANA All Nippon Airways",
        "KE KAL Korean Air", "QF QFA Qantas", "NZ ANZ Air New Zealand", "LA LAN LATAM", "CM CMP Copa Airlines",
        "AV AVA Avianca", "FR RYR Ryanair", "U2 EZY easyJet", "W6 WZZ Wizz Air", "AI AIC Air India",
        "ET ETH Ethiopian Airlines", "5X UPS UPS Airlines", "FX FDX FedEx",
    ).associate { line -> line.split(' ', limit = 3).let { (iata, icao, name) -> iata to (icao to name) } }

    private val byIcao = AIRLINES.values.associate { (icao, name) -> icao to name }

    /** How to look [query] up: the feed's endpoint and value. */
    fun lookupPath(query: String): Pair<String, String>? {
        val q = query.uppercase().filter { it.isLetterOrDigit() }
        if (q.isEmpty()) return null
        Regex("^([A-Z0-9]{2})0*(\\d{1,4}[A-Z]?)$").find(q)?.let { m ->
            AIRLINES[m.groupValues[1]]?.let { (icao, _) -> return "callsign" to icao + m.groupValues[2] }
        }
        if (Regex("^N[0-9][0-9A-Z]{0,4}$").matches(q)) return "reg" to q
        if (Regex("^[0-9A-F]{6}$").matches(q) && q.any { it.isDigit() }) return "hex" to q.lowercase()
        Regex("^([A-Z]{3})0*(\\d{1,4}[A-Z]?)$").find(q)?.let { m -> return "callsign" to m.groupValues[1] + m.groupValues[2] }
        return "callsign" to q
    }

    /** Full aircraft type and registered owner from hexdb.io, by ICAO address; cached. */
    private val details = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()

    fun aircraftDetails(hex: String): Pair<String?, String?> = details.getOrPut(hex) {
        runCatching {
            val conn = URL("https://hexdb.io/api/v1/aircraft/$hex").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val j = try { JSONObject(conn.inputStream.bufferedReader().use { it.readText() }) } finally { conn.disconnect() }
            fun s(k: String) = j.optString(k).trim().takeIf { it.isNotEmpty() }
            listOfNotNull(s("Manufacturer"), s("Type")).joinToString(" ").ifEmpty { null } to s("RegisteredOwners")
        }.getOrDefault(null to null)
    }

    /** "American Airlines" for AAL123, if known. */
    fun airline(callsign: String?): String? = callsign?.takeIf { it.length > 3 }?.let { byIcao[it.take(3)] }

    /** The flight right now, or null if the feed doesn't see it (not flying, or out of coverage). Throws on network errors. */
    fun lookup(context: Context, query: String): LiveFlight? {
        val (kind, value) = lookupPath(query) ?: return null
        val conn = URL("$API/$kind/${URLEncoder.encode(value, "UTF-8")}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Siren (${context.packageName})")
        val body = try {
            if (conn.responseCode != 200) throw java.io.IOException("adsb.lol answered ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return parse(body).firstOrNull()
    }

    fun parse(json: String): List<LiveFlight> {
        val ac = JSONObject(json).optJSONArray("ac") ?: return emptyList()
        return (0 until ac.length()).map { i ->
            val a = ac.getJSONObject(i)
            val alt = a.opt("alt_baro")
            fun int(key: String) = (a.opt(key) as? Number)?.toInt()
            fun dbl(key: String) = a.optDouble(key).takeUnless { it.isNaN() }
            fun str(key: String) = a.optString(key).trim().takeIf { it.isNotEmpty() }
            LiveFlight(
                hex = a.optString("hex").trim().lowercase(),
                callsign = str("flight"),
                registration = str("r"),
                typeCode = str("t"),
                lat = dbl("lat"),
                lon = dbl("lon"),
                altitudeFt = (alt as? Number)?.toInt(),
                onGround = alt == "ground",
                speedKt = dbl("gs"),
                trackDeg = dbl("track"),
                verticalRateFpm = int("baro_rate") ?: int("geom_rate"),
                selectedAltitudeFt = int("nav_altitude_mcp"),
                squawk = str("squawk"),
                emergency = str("emergency")?.takeIf { it != "none" },
                secondsSinceSeen = dbl("seen"),
            )
        }
    }

    // ---- Status changes ----------------------------------------------------------------------

    /** Where a flight is in its trip, from its height and climb rate. */
    fun phase(f: LiveFlight): String = when {
        f.onGround || ((f.altitudeFt ?: 99_999) < 300 && (f.speedKt ?: 999.0) < 80) -> "ground"
        (f.verticalRateFpm ?: 0) > 500 -> "climbing"
        (f.verticalRateFpm ?: 0) < -500 -> "descending"
        (f.altitudeFt ?: 0) >= 10_000 -> "cruising"
        else -> "airborne"
    }

    /** One status message to send, and the key that stops it repeating. */
    data class Update(val key: String, val title: String, val text: String)

    /**
     * What changed for [t] given its latest [live] state (null: not seen). Returns the messages to
     * send and the flight's new record.
     */
    fun updates(
        t: TrackedFlight,
        live: LiveFlight?,
        from: Airport?,
        to: Airport?,
        meLat: Double?,
        meLon: Double?,
        now: Long = System.currentTimeMillis(),
    ): Pair<List<Update>, TrackedFlight> {
        val name = live?.callsign ?: t.query.uppercase()
        val out = mutableListOf<Update>()
        // Each day starts afresh, so a flight tracked over several days is reported every day.
        val day = tripDay(now)
        fun once(key: String, title: String, text: String) {
            if ("$day:$key" !in t.notified) out += Update("$day:$key", "$name: $title", text)
        }
        if (live == null) {
            val wasFlying = t.phase.isNotEmpty() && t.phase != "ground"
            if (wasFlying && now - t.lastSeenAt > 10 * 60_000L) {
                once("lost-${t.lastSeenAt}", "signal lost", "Not heard for 10 minutes - it may have landed or flown out of receiver coverage.")
            }
            return out to t.copy(notified = t.notified + out.map { it.key })
        }
        val phase = phase(live)
        val alt = live.altitudeFt?.let { "%,d ft".format(it) }
        once("found", "found", listOfNotNull(phase, alt, describeTo(live, to)).joinToString(" · "))
        when {
            phase == "climbing" && t.phase == "ground" -> once("takeoff", "took off", listOfNotNull(from?.let { "From ${it.label}" }, alt).joinToString(" · "))
            phase == "cruising" && t.phase == "climbing" -> once("cruise", "reached cruising altitude", alt ?: "")
            phase == "descending" && t.phase in setOf("cruising", "airborne") && (live.altitudeFt ?: 0) > 3_000 ->
                once("descent", "started descending", listOfNotNull(alt, describeTo(live, to)).joinToString(" · "))
            phase == "ground" && t.phase.isNotEmpty() && t.phase != "ground" ->
                once("landed", "landed", to?.let { "At or near ${it.label}" } ?: "On the ground")
        }
        live.emergency?.let { once("emergency-$it", "EMERGENCY ($it)", "Squawking ${live.squawk ?: "an emergency code"}") }
        if (to != null && live.lat != null && live.lon != null && phase != "ground") {
            val left = Odometer.distanceM(live.lat, live.lon, to.lat, to.lon)
            if (left < 80_000) once("approach", "about ${minutesTo(left, live.speedKt) ?: "a few"} minutes from ${to.code}", describeTo(live, to) ?: "")
        }
        if (meLat != null && meLon != null && live.lat != null && live.lon != null && phase != "ground") {
            if (Odometer.distanceM(meLat, meLon, live.lat, live.lon) < 16_000) once("near-me", "near you", "${formatShortDistance(Odometer.distanceM(meLat, meLon, live.lat, live.lon))} away · ${alt ?: ""}")
        }
        return out to t.copy(notified = t.notified + out.map { it.key }, phase = phase, lastSeenAt = now)
    }

    /** "312 mi to DFW, about 41 min". */
    fun describeTo(live: LiveFlight, to: Airport?): String? {
        if (to == null || live.lat == null || live.lon == null) return null
        val left = Odometer.distanceM(live.lat, live.lon, to.lat, to.lon)
        return "${formatShortDistance(left)} to ${to.code}" + (minutesTo(left, live.speedKt)?.let { ", about $it min" } ?: "")
    }

    private fun minutesTo(meters: Double, speedKt: Double?): Int? =
        speedKt?.takeIf { it > 50 }?.let { (meters / (it * 0.514444) / 60).toInt() }

    // ---- Recent searches ----------------------------------------------------------------------

    /** A flight the user looked up, with the airports they entered. */
    data class Search(val query: String, val from: String, val to: String)

    private const val MAX_RECENT = 10

    /** Most recent first. */
    fun recent(context: Context): List<Search> = runCatching {
        val a = JSONArray(prefs(context).getString("recent", "[]"))
        List(a.length()) { a.getJSONObject(it) }.map { Search(it.getString("query"), it.optString("from"), it.optString("to")) }
    }.getOrDefault(emptyList())

    /** Puts [s] at the top of the recent searches (replacing an older search for the same flight). */
    fun remember(context: Context, s: Search): List<Search> {
        val list = (listOf(s) + recent(context).filterNot { it.query.equals(s.query, ignoreCase = true) }).take(MAX_RECENT)
        saveRecent(context, list)
        return list
    }

    fun forget(context: Context, query: String): List<Search> =
        recent(context).filterNot { it.query.equals(query, ignoreCase = true) }.also { saveRecent(context, it) }

    fun clearRecent(context: Context) = saveRecent(context, emptyList())

    private fun saveRecent(context: Context, list: List<Search>) = prefs(context).edit()
        .putString("recent", JSONArray(list.map { JSONObject().put("query", it.query).put("from", it.from).put("to", it.to) }).toString())
        .apply()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("flights", Context.MODE_PRIVATE)

    // ---- The user's tracked flights ----------------------------------------------------------

    private val _tracked = MutableStateFlow<List<TrackedFlight>>(emptyList())
    val tracked: StateFlow<List<TrackedFlight>> = _tracked.asStateFlow()
    /** Latest live state per tracked flight id; null while not seen. */
    val live = MutableStateFlow<Map<String, LiveFlight?>>(emptyMap())
    @Volatile private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val sp = context.applicationContext.getSharedPreferences("flights", Context.MODE_PRIVATE)
        _tracked.value = runCatching {
            val a = JSONArray(sp.getString("tracked", "[]"))
            List(a.length()) { a.getJSONObject(it) }.map { j ->
                TrackedFlight(
                    query = j.getString("query"), from = j.optString("from"), to = j.optString("to"),
                    startDay = j.optString("startDay"), endDay = j.optString("endDay"),
                    addedAt = j.optLong("addedAt"), phase = j.optString("phase"), lastSeenAt = j.optLong("lastSeenAt"),
                    notified = j.optJSONArray("notified")?.let { n -> List(n.length()) { n.getString(it) }.toSet() }.orEmpty(),
                )
            }.filter { current(it, System.currentTimeMillis()) }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(context: Context, list: List<TrackedFlight>) {
        _tracked.value = list
        val json = JSONArray(list.map { t ->
            JSONObject().put("query", t.query).put("from", t.from).put("to", t.to).put("addedAt", t.addedAt)
                .put("startDay", t.startDay).put("endDay", t.endDay)
                .put("phase", t.phase).put("lastSeenAt", t.lastSeenAt).put("notified", JSONArray(t.notified.toList()))
        })
        context.applicationContext.getSharedPreferences("flights", Context.MODE_PRIVATE).edit().putString("tracked", json.toString()).apply()
    }

    fun track(context: Context, t: TrackedFlight) {
        load(context)
        save(context, _tracked.value.filterNot { it.id == t.id } + t)
        FlightWatchService.start(context)
    }

    fun untrack(context: Context, id: String) {
        load(context)
        save(context, _tracked.value.filterNot { it.id == id })
        live.value = live.value - id
    }

    /** Drops flights whose days are over; true if any are left. */
    fun prune(context: Context): Boolean {
        load(context)
        val keep = _tracked.value.filter { current(it, System.currentTimeMillis()) }
        if (keep.size != _tracked.value.size) save(context, keep)
        return keep.isNotEmpty()
    }
}
