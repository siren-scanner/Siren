package app.sirenscout

import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Detection(
    val source: Source,
    val id: String,
    val name: String?,
    val rssi: Int,
    val signature: Signature?,
    val firstSeen: Long,
    val lastSeen: Long,
    val count: Int,
    val lastAlert: Long,
    /** Strongest signal seen, and where the phone was at the time - roughly the closest approach. */
    val bestRssi: Int,
    val lat: Double?,
    val lon: Double?,
    /** What [DeviceGuesser] thinks this is, e.g. "Apple · AirPods". Unverified. */
    val guess: String? = null,
    /** Plain-language reason it matched its signature, e.g. "Address starts with 00:25:DF…". */
    val reason: String? = null,
    /** How far you'd traveled (phone odometer) at this encounter's first and latest sighting. */
    val startOdometerM: Double? = null,
    val lastOdometerM: Double? = null,
    /** BLE only: the stable parts of what it advertises (see [DeviceGuesser.summary]). */
    val adSummary: String? = null,
    /** Saved location estimate (history rows); live detections use [Detections.estimate]. */
    val estimate: LocationEstimate? = null,
) {
    /**
     * Time and distance this device has stayed with you, once that's long enough to mean
     * something: 5+ minutes while you moved 500 m+. A stranger at a red light never qualifies.
     */
    val withYou: Pair<Long, Double>?
        get() {
            val distance = (lastOdometerM ?: return null) - (startOdometerM ?: return null)
            val duration = lastSeen - firstSeen
            return (duration to distance).takeIf { duration >= WITH_YOU_MIN_MS && distance >= WITH_YOU_MIN_M }
        }

    /** Shown in lists: the signature, the broadcast name, or failing those a marked guess. */
    val title get() = signature?.label ?: if (isTesla) TeslaKey.LABEL else name ?: guess?.let(::markGuess) ?: "Unknown device"

    /** The guess as a secondary line, when it isn't already the title. */
    val guessLine get() = guess?.takeIf { (signature != null || name != null) && !isTesla }?.let(::markGuess)

    /** A Tesla's phone-key signal: certain from its name, so shown as "Tesla car key" without a guess mark. */
    val isTesla get() = TeslaKey.isTesla(name)

    /** A drone sending Remote ID: known from what it broadcasts, so not a guess. Its position is in the Air tab. */
    val isDrone get() = guess == RemoteId.LABEL

    /** Identified from what it broadcasts rather than guessed: no (UNK), and the AI isn't asked. */
    val isIdentified get() = isTesla || isDrone

    private fun markGuess(g: String) = if (isIdentified) g else "$g $GUESS_MARK"

    val key get() = "${source.name}|$id"
    /** Identifies this encounter; the same device seen again after a gap gets a new one. */
    val uid get() = "$key|$firstSeen"
}

private const val WITH_YOU_MIN_MS = 5 * 60_000L
private const val WITH_YOU_MIN_M = 500.0

/** "2 h 10 min · 34 mi" */
fun formatWithYou(withYou: Pair<Long, Double>): String {
    val (ms, meters) = withYou
    val minutes = ms / 60_000
    val time = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    return "$time · ${formatDistance(meters)}"
}

/** Appended to anything [DeviceGuesser] inferred rather than read from the device. */
const val GUESS_MARK = "(UNK)"

data class ScanStatus(
    val running: Boolean = false,
    val ble: String = "idle",
    val classic: String = "idle",
    val wifi: String = "idle",
    val rf: String = "off",
)

/** In-memory store shared between the scanning service and the UI. */
object Detections {
    /** A match seen within this window counts as "nearby now". */
    const val ACTIVE_WINDOW_MS = 30_000L
    /** A device unseen for this long starts a new encounter (a new history row and map pin). */
    const val ENCOUNTER_GAP_MS = 10 * 60_000L
    private const val REALERT_MS = 120_000L
    private const val KEEP_MATCHES_MS = 30 * 60_000L
    private const val KEEP_OTHERS_MS = 2 * 60_000L

    private val _items = MutableStateFlow<Map<String, Detection>>(emptyMap())
    val items: StateFlow<Map<String, Detection>> = _items.asStateFlow()

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    /** One signal reading, kept briefly for the detail view's live meter and graph. */
    data class Sample(val time: Long, val rssi: Int)

    private const val SIGNAL_WINDOW_MS = 3 * 60_000L
    private val signals = HashMap<String, ArrayDeque<Sample>>()

    /** Key of the detection the user is trying to find; the scanner switches to full speed. */
    val finding = MutableStateFlow<String?>(null)

    @Synchronized
    fun signal(key: String): List<Sample> = signals[key]?.toList().orEmpty()

    /** Signal readings with the phone's position, for estimating where stationary devices are. */
    private val readings = HashMap<String, ArrayDeque<Reading>>()
    private val estimates = HashMap<String, Pair<Int, LocationEstimate?>>()
    private const val MAX_READINGS = 300

    private fun addReading(key: String, location: Location, rssi: Int, now: Long) {
        val list = readings.getOrPut(key) { ArrayDeque() }
        val last = list.lastOrNull()
        val r = Reading(location.latitude, location.longitude, rssi.toDouble(), now)
        // Readings from the same spot a moment apart are averaged rather than piled up.
        if (last != null && now - last.time < 5_000 &&
            Odometer.distanceM(last.lat, last.lon, r.lat, r.lon) < 3.0
        ) {
            list[list.size - 1] = last.copy(rssi = (last.rssi + r.rssi) / 2)
        } else {
            list.addLast(r)
            while (list.size > MAX_READINGS) list.removeFirst()
        }
    }

    /**
     * Where a stationary device probably is, from this encounter's readings; null when there
     * isn't enough to go on or it's moving with you.
     */
    @Synchronized
    fun estimate(d: Detection): LocationEstimate? {
        if (d.source == Source.RF || d.withYou != null) return d.estimate
        val list = readings[d.key] ?: return d.estimate
        val cached = estimates[d.key]
        if (cached != null && cached.first == list.size) return cached.second
        val e = LocationEstimator.estimate(list.toList())
        estimates[d.key] = list.size to e
        return e
    }

    fun setStatus(change: (ScanStatus) -> ScanStatus) {
        val before = _status.value
        _status.update(change)
        logChanges(before, _status.value)
    }

    private fun logChanges(a: ScanStatus, b: ScanStatus) {
        fun same(x: String, y: String) = x.replace(Regex("\\d+"), "#") == y.replace(Regex("\\d+"), "#")
        if (a.running != b.running) AppLog.i(if (b.running) "Scanning started" else "Scanning stopped")
        if (!same(a.ble, b.ble)) AppLog.i("BLE: ${b.ble}")
        if (!same(a.classic, b.classic)) AppLog.i("Bluetooth: ${b.classic}")
        if (!same(a.wifi, b.wifi)) AppLog.i("Wi-Fi: ${b.wifi}")
        if (!same(a.rf, b.rf)) AppLog.i("RF: ${b.rf}")
    }

    /** Records a sighting and returns the detection if it should raise an alert now. */
    @Synchronized
    fun record(
        source: Source,
        id: String,
        name: String?,
        rssi: Int,
        signature: Signature?,
        alertEligible: Boolean,
        location: Location?,
        guess: String? = null,
        reason: String? = null,
        odometerM: Double? = null,
        adSummary: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Detection? {
        val key = "${source.name}|$id"
        val existing = _items.value[key]
        val alert = alertEligible && (existing == null || now - existing.lastAlert > REALERT_MS)
        val prev = existing?.takeIf { now - it.lastSeen < ENCOUNTER_GAP_MS }
        val strongest = prev == null || rssi >= prev.bestRssi
        val moveMarker = location != null && (strongest || prev?.lat == null)
        val next = Detection(
            source = source,
            id = id,
            name = name ?: prev?.name,
            rssi = rssi,
            signature = signature,
            firstSeen = prev?.firstSeen ?: now,
            lastSeen = now,
            count = (prev?.count ?: 0) + 1,
            lastAlert = if (alert) now else existing?.lastAlert ?: 0L,
            bestRssi = if (strongest) rssi else prev!!.bestRssi,
            lat = if (moveMarker) location!!.latitude else prev?.lat,
            lon = if (moveMarker) location!!.longitude else prev?.lon,
            guess = guess ?: prev?.guess,
            reason = reason ?: prev?.reason,
            startOdometerM = prev?.startOdometerM ?: odometerM,
            lastOdometerM = odometerM ?: prev?.lastOdometerM,
            adSummary = adSummary ?: prev?.adSummary,
        )
        _items.value = _items.value + (key to next)
        // A new encounter starts its readings afresh; the old place no longer applies.
        if (prev == null) { readings.remove(key); estimates.remove(key) }
        if (location != null) addReading(key, location, rssi, now)
        signals.getOrPut(key) { ArrayDeque() }.apply {
            addLast(Sample(now, rssi))
            while (isNotEmpty() && now - first().time > SIGNAL_WINDOW_MS) removeFirst()
        }
        return next.takeIf { alert }
    }

    @Synchronized
    fun prune(now: Long = System.currentTimeMillis()) {
        _items.value = _items.value.filterValues {
            now - it.lastSeen < if (it.signature != null) KEEP_MATCHES_MS else KEEP_OTHERS_MS
        }
        signals.keys.retainAll(_items.value.keys)
        readings.keys.retainAll(_items.value.keys)
        estimates.keys.retainAll(_items.value.keys)
    }

    @Synchronized
    fun remove(key: String) {
        _items.value = _items.value - key
    }

    @Synchronized
    fun clear() {
        _items.value = emptyMap()
        signals.clear()
        readings.clear()
        estimates.clear()
    }
}
