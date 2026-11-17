package app.sirenscout

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.log10

/**
 * Which radio band each Wi-Fi access point is on, and distance estimates that allow for it. A
 * router usually broadcasts the same network on 2.4 GHz and 5 GHz from almost the same address
 * (…:08 and …:09); 5 GHz loses more strength over the same distance and through walls, so it reads
 * weaker without being further away.
 */
object WifiBands {
    private val mhz = ConcurrentHashMap<String, Int>()

    fun record(bssid: String, frequencyMhz: Int) {
        if (frequencyMhz > 0) mhz[bssid.lowercase()] = frequencyMhz
    }

    fun mhz(bssid: String): Int? = mhz[bssid.lowercase()]

    fun band(bssid: String): String? = mhz(bssid)?.let { label(it) }

    fun label(mhz: Int) = when {
        mhz < 3_000 -> "2.4 GHz"
        mhz < 5_925 -> "5 GHz"
        else -> "6 GHz"
    }

    /**
     * Signal strength one metre from a typical router on [mhz], and how fast it falls off: higher
     * bands lose more in free space (20·log10 of the frequency ratio) and through walls.
     */
    fun model(mhz: Int?): Pair<Double, Double> {
        val f = mhz ?: 2_437
        return (-35.0 - 20 * log10(f / 2_437.0)) to (if (f >= 4_900) 3.2 else 2.8)
    }

    /** The same router's other bands: same network name, addresses that differ only in the last byte. */
    fun siblings(d: Detection, all: Collection<Detection>): List<Detection> {
        if (d.source != Source.WIFI || d.name == null) return listOf(d)
        val prefix = d.id.lowercase().substringBeforeLast(':')
        return all.filter { it.source == Source.WIFI && it.name == d.name && it.id.lowercase().substringBeforeLast(':') == prefix }
            .ifEmpty { listOf(d) }
    }
}

/**
 * How far away [d] probably is, as text. For a Wi-Fi network broadcast on several bands, every
 * band gets the same answer: the closest estimate, since walls only ever make a signal weaker.
 */
fun distanceText(d: Detection, all: Collection<Detection> = Detections.items.value.values): String {
    val readings = WifiBands.siblings(d, all).filter { it.rssi < 0 }
    if (readings.isEmpty()) return "distance unknown"
    return roughDistance(readings.minOf { estimateMeters(it.rssi, it.source, WifiBands.mhz(it.id)) })
}

/** "about 40 ft away", but "within 5 ft" and "distance unknown" as they are. */
fun away(distance: String) = if (distance.startsWith("within") || distance == "distance unknown") distance else "$distance away"
