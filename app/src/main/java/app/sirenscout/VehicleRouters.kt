package app.sirenscout

import android.content.Context
import android.location.Location
import org.json.JSONObject
import java.io.File

/**
 * Vehicle routers (Cradlepoint, Sierra AirLink, Peplink, Inseego) are in police, fire and EMS
 * vehicles - and also in buses, shops, ATMs and as personal hotspots. So on their own they're
 * weak evidence. They alert only when:
 *  - their network name is a vehicle model's default (an IBR900, say), or
 *  - police or fire / EMS gear was also heard in the last few minutes,
 * and never once they've been seen in the same place on an earlier day (a fixed install).
 * Settings > Alerts > Vehicle routers on their own brings back the old, alert-on-everything rule.
 */
object VehicleRouters {
    /** Cradlepoint's in-vehicle models, as they appear in default network names ("IBR900-1a2"). */
    private val VEHICLE_MODEL = Regex("^(IBR\\d{3,4}|R\\d{3,4}|COR\\b|MG90|MP70|MAX[- ]?(BR1|TRANSIT|HD))", RegexOption.IGNORE_CASE)
    /** Other police gear heard this recently backs a router up. */
    private const val CORROBORATE_MS = 3 * 60_000L
    /** Seen within this distance of an earlier day's sighting: it doesn't move. */
    private const val SAME_PLACE_M = 300.0
    private const val MAX_REMEMBERED = 3000

    fun isRouter(sig: Signature?) = sig != null && sig.category == Category.FLEET && sig.sources == setOf(Source.WIFI)

    fun vehicleModelName(name: String?) = name != null && VEHICLE_MODEL.containsMatchIn(name)

    /** Police or fire / EMS gear heard in the last few minutes, other than routers. */
    fun corroborated(all: Collection<Detection>, now: Long) = all.any {
        val c = it.signature?.category
        (c == Category.POLICE || c == Category.FIRE_EMS) && now - it.lastSeen < CORROBORATE_MS
    }

    /** Whether a router sighting should alert (and count in the red banner). Non-routers always may. */
    fun mayAlert(context: Context, d: Detection, all: Collection<Detection>, now: Long) =
        mayAlert(context, d.signature, d.id, d.name, all, now)

    fun mayAlert(context: Context, sig: Signature?, id: String, name: String?, all: Collection<Detection>, now: Long): Boolean {
        if (!isRouter(sig)) return true
        if (isFixed(context, id)) return false
        if (Prefs(context).routersAlone) return true
        return vehicleModelName(name) || corroborated(all, now)
    }

    /** Why a router isn't alerting, for its details; null when it does. */
    fun quietReason(context: Context, d: Detection, all: Collection<Detection>, now: Long): String? = when {
        !isRouter(d.signature) -> null
        isFixed(context, d.id) -> "Seen in the same place on an earlier day, so probably fixed in a building - no alerts"
        mayAlert(context, d, all, now) -> null
        else -> "No alert on its own: the same routers are in buses and shops. It alerts when police or fire / EMS gear is also nearby, or its name is a vehicle model's"
    }

    // --- Remembering where routers were seen, to spot fixed ones ---

    internal class Sighting(val lat: Double, val lon: Double, val day: Long, var fixed: Boolean)

    private var seen: MutableMap<String, Sighting>? = null

    private fun file(context: Context) = File(context.filesDir, "vehicle_routers.json")

    @Synchronized
    private fun load(context: Context): MutableMap<String, Sighting> = seen ?: LinkedHashMap<String, Sighting>().also { map ->
        runCatching {
            val j = JSONObject(file(context).readText())
            j.keys().forEach { k ->
                val o = j.getJSONObject(k)
                map[k] = Sighting(o.getDouble("lat"), o.getDouble("lon"), o.getLong("day"), o.optBoolean("fixed"))
            }
        }
        seen = map
    }

    @Synchronized
    fun isFixed(context: Context, bssid: String) = load(context)[bssid.uppercase()]?.fixed == true

    /** Notes where a router was seen; a second day in the same place marks it as fixed. */
    @Synchronized
    fun noteSighting(context: Context, bssid: String, location: Location?, now: Long = System.currentTimeMillis()) {
        if (location == null || location.accuracy > 100f) return
        val map = load(context)
        val key = bssid.uppercase()
        val changed = step(map, key, location.latitude, location.longitude, day(now))
        if (!changed) return
        while (map.size > MAX_REMEMBERED) map.remove(map.keys.first())
        val j = JSONObject()
        map.forEach { (k, s) -> j.put(k, JSONObject().put("lat", s.lat).put("lon", s.lon).put("day", s.day).put("fixed", s.fixed)) }
        runCatching { file(context).writeText(j.toString()) }
    }

    /** Updates [map] with a sighting; returns whether anything changed. */
    internal fun step(map: MutableMap<String, Sighting>, key: String, lat: Double, lon: Double, day: Long): Boolean {
        val before = map[key]
        return when {
            before == null -> { map[key] = Sighting(lat, lon, day, false); true }
            before.fixed -> false
            Odometer.distanceM(before.lat, before.lon, lat, lon) > SAME_PLACE_M -> {
                // Somewhere else: it moves, so start over from here.
                map[key] = Sighting(lat, lon, day, false); true
            }
            day > before.day -> { before.fixed = true; AppLog.i("Router $key seen in the same place on another day: treated as fixed"); true }
            else -> false
        }
    }

    /** Local calendar day number. */
    internal fun day(ms: Long): Long = (ms + java.util.TimeZone.getDefault().getOffset(ms)) / 86_400_000L
}
