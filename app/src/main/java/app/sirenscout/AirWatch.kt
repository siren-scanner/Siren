package app.sirenscout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A drone (heard directly, by its Remote ID) or a law-enforcement aircraft (from ADS-B). */
data class AirContact(
    val key: String,
    val kind: Kind,
    val title: String,
    /** Second line: serial number and type for drones, owner and model for aircraft. */
    val detail: String,
    val lat: Double?,
    val lon: Double?,
    /** Drones: metres above take-off / ground. Aircraft: feet above sea level. */
    val altitudeText: String?,
    val speedMs: Double?,
    val headingDeg: Double?,
    val operatorLat: Double? = null,
    val operatorLon: Double? = null,
    val rssi: Int? = null,
    /** Metres from you when last seen, if both positions are known. */
    val distanceM: Double? = null,
    val firstSeen: Long,
    val lastSeen: Long,
) {
    enum class Kind(val label: String) { DRONE("Drone"), AIRCRAFT("Police aircraft") }
}

/**
 * Drones and law-enforcement aircraft around you. Drones come straight from their Remote ID
 * broadcasts; aircraft from [Adsb]. Each alerts once, then again only after [REALERT_MS].
 */
object AirWatch {
    /** A drone drops off this long after its last broadcast; an aircraft after this long out of the feed. */
    private const val DRONE_TIMEOUT_MS = 60_000L
    private const val AIRCRAFT_TIMEOUT_MS = 120_000L
    const val REALERT_MS = 30 * 60_000L

    private val _contacts = MutableStateFlow<Map<String, AirContact>>(emptyMap())
    val contacts: StateFlow<Map<String, AirContact>> = _contacts.asStateFlow()

    /** Remote ID fields collected so far per transmitter; messages arrive one kind at a time. */
    private val drones = HashMap<String, RemoteId.Drone>()
    private val alertedAt = HashMap<String, Long>()

    /** A Remote ID broadcast from [transmitter]; returns the contact if this is the first time it should alert. */
    @Synchronized
    fun onRemoteId(
        transmitter: String,
        rssi: Int,
        parse: (RemoteId.Drone) -> RemoteId.Drone?,
        meLat: Double?,
        meLon: Double?,
        now: Long = System.currentTimeMillis(),
    ): AirContact? {
        val d = parse(drones[transmitter] ?: RemoteId.Drone()) ?: return null
        drones[transmitter] = d
        val key = "drone|$transmitter"
        val prev = _contacts.value[key]
        val contact = AirContact(
            key = key,
            kind = AirContact.Kind.DRONE,
            title = "Drone" + (d.description?.let { " · $it" } ?: ""),
            detail = listOfNotNull(
                d.id?.let { "${d.idType ?: "ID"} $it" },
                d.aircraftType,
                d.operatorId?.let { "operator $it" },
            ).joinToString(" · ").ifEmpty { "Remote ID" },
            lat = d.lat,
            lon = d.lon,
            altitudeText = d.heightM?.let { formatShortDistance(it) + " up" },
            speedMs = d.speedMs,
            headingDeg = d.headingDeg,
            operatorLat = d.operatorLat,
            operatorLon = d.operatorLon,
            rssi = rssi,
            distanceM = distance(meLat, meLon, d.lat, d.lon) ?: distance(meLat, meLon, d.operatorLat, d.operatorLon),
            firstSeen = prev?.firstSeen ?: now,
            lastSeen = now,
        )
        _contacts.update { it + (key to contact) }
        return contact.takeIf { shouldAlert(key, now) }
    }

    /**
     * The latest law-enforcement aircraft from the feed. Returns those within [alertWithinM] of you
     * that should alert now.
     */
    @Synchronized
    fun onAircraft(
        found: List<Pair<Adsb.Aircraft, Adsb.Registration>>,
        meLat: Double?,
        meLon: Double?,
        alertWithinM: Double,
        now: Long = System.currentTimeMillis(),
    ): List<AirContact> {
        val updated = found.map { (a, reg) ->
            val key = "aircraft|${a.hex}"
            AirContact(
                key = key,
                kind = AirContact.Kind.AIRCRAFT,
                title = reg.owner,
                detail = listOfNotNull(reg.model.ifEmpty { null }, reg.tail, a.callsign?.takeIf { it != reg.tail }).joinToString(" · "),
                lat = a.lat,
                lon = a.lon,
                altitudeText = if (a.onGround) "on the ground" else a.altitudeFt?.let { "%,d ft".format(it) },
                speedMs = a.speedKt?.let { it * 0.514444 },
                headingDeg = a.trackDeg,
                distanceM = distance(meLat, meLon, a.lat, a.lon),
                firstSeen = _contacts.value[key]?.firstSeen ?: now,
                lastSeen = now,
            )
        }
        _contacts.update { it + updated.associateBy { c -> c.key } }
        return updated.filter { c ->
            val near = c.distanceM?.let { it <= alertWithinM } == true
            near && c.altitudeText != "on the ground" && shouldAlert(c.key, now)
        }
    }

    @Synchronized
    fun prune(now: Long = System.currentTimeMillis()) {
        _contacts.update { all ->
            all.filterValues { c ->
                now - c.lastSeen < if (c.kind == AirContact.Kind.DRONE) DRONE_TIMEOUT_MS else AIRCRAFT_TIMEOUT_MS
            }
        }
        val live = _contacts.value.keys
        drones.keys.retainAll { "drone|$it" in live }
    }

    @Synchronized
    fun clear() {
        _contacts.value = emptyMap()
        drones.clear()
    }

    private fun shouldAlert(key: String, now: Long): Boolean {
        val last = alertedAt[key]
        if (last != null && now - last < REALERT_MS) return false
        alertedAt[key] = now
        return true
    }

    private fun distance(aLat: Double?, aLon: Double?, bLat: Double?, bLon: Double?): Double? =
        if (aLat == null || aLon == null || bLat == null || bLon == null) null else Odometer.distanceM(aLat, aLon, bLat, bLon)
}
