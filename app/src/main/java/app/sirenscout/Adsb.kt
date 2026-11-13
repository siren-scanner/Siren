package app.sirenscout

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Police and other law-enforcement aircraft, from the free community ADS-B feed at adsb.lol
 * (open data) matched against aircraft registered to law-enforcement agencies in the FAA
 * registry (assets/police_aircraft.txt, built by tools/build-police-aircraft.py).
 */
object Adsb {
    /** Rounded position (about 10 km) and radius: the feed only learns the area, not where you are. */
    private const val RADIUS_NM = 30
    private const val API = "https://api.adsb.lol/v2/point"

    data class Registration(val tail: String, val owner: String, val model: String)

    data class Aircraft(
        val hex: String,
        val callsign: String?,
        val lat: Double,
        val lon: Double,
        val altitudeFt: Int?,
        val speedKt: Double?,
        val trackDeg: Double?,
        val onGround: Boolean,
    )

    @Volatile private var police: Map<String, Registration>? = null

    fun registrations(context: Context): Map<String, Registration> = police ?: synchronized(this) {
        police ?: context.assets.open("police_aircraft.txt").bufferedReader().use { parseRegistry(it.readText()) }
            .also { police = it }
    }

    fun parseRegistry(text: String): Map<String, Registration> = text.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 4 || f[0].length != 6) null else f[0].lowercase() to Registration(f[1], f[2], f[3])
    }.toMap()

    /** Every aircraft with a position in the feed's answer. */
    fun parseFeed(json: String): List<Aircraft> {
        val ac = JSONObject(json).optJSONArray("ac") ?: return emptyList()
        return (0 until ac.length()).mapNotNull { i ->
            val a = ac.getJSONObject(i)
            if (!a.has("lat") || !a.has("lon")) return@mapNotNull null
            val alt = a.opt("alt_baro")
            Aircraft(
                hex = a.optString("hex").trim().lowercase().removePrefix("~"),
                callsign = a.optString("flight").trim().takeIf { it.isNotEmpty() },
                lat = a.getDouble("lat"),
                lon = a.getDouble("lon"),
                altitudeFt = (alt as? Number)?.toInt(),
                speedKt = a.optDouble("gs").takeUnless { it.isNaN() },
                trackDeg = a.optDouble("track").takeUnless { it.isNaN() },
                onGround = alt == "ground",
            )
        }
    }

    /**
     * Every aircraft around ([lat], [lon]), with its law-enforcement registration if it has one;
     * throws if the feed can't be reached.
     */
    fun fetch(context: Context, lat: Double, lon: Double): List<Pair<Aircraft, Registration?>> {
        val reg = registrations(context)
        val url = "$API/${KnownCameras.roundForQuery(lat)}/${KnownCameras.roundForQuery(lon)}/$RADIUS_NM"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "Siren (${context.packageName})")
        val body = try {
            if (conn.responseCode != 200) throw java.io.IOException("adsb.lol answered ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        return parseFeed(body).map { a -> a to reg[a.hex] }
    }
}
