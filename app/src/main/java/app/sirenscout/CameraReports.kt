package app.sirenscout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Spots where you passed a camera that isn't on the map, saved with one tap while driving, to add
 * to DeFlock / OpenStreetMap later. Kept on the phone only.
 */
object CameraReports {
    data class Spot(val time: Long, val lat: Double, val lon: Double, val heading: Float?)

    private val _spots = MutableStateFlow<List<Spot>>(emptyList())
    val spots: StateFlow<List<Spot>> = _spots.asStateFlow()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "camera_reports.json")

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _spots.value = runCatching {
            val a = JSONArray(file(context).readText())
            List(a.length()) { a.getJSONObject(it) }.map {
                Spot(it.getLong("t"), it.getDouble("lat"), it.getDouble("lon"), if (it.has("h")) it.getDouble("h").toFloat() else null)
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(context: Context, spot: Spot) { load(context); _spots.value = _spots.value + spot; save(context) }

    @Synchronized
    fun remove(context: Context, spot: Spot) { load(context); _spots.value = _spots.value - spot; save(context) }

    private fun save(context: Context) {
        val a = JSONArray(_spots.value.map { s ->
            JSONObject().put("t", s.time).put("lat", s.lat).put("lon", s.lon).apply { s.heading?.let { put("h", it.toDouble()) } }
        })
        runCatching { file(context).writeText(a.toString()) }
    }

    /** DeFlock's map, centred on the spot. */
    fun deflockUrl(s: Spot) = "https://deflock.me/legacy-map#map=18/%.6f/%.6f".format(java.util.Locale.US, s.lat, s.lon)

    /** OpenStreetMap's editor at the spot, to add the camera yourself. */
    fun osmEditUrl(s: Spot) = "https://www.openstreetmap.org/edit#map=19/%.6f/%.6f".format(java.util.Locale.US, s.lat, s.lon)
}
