package app.sirenscout

import android.content.Context
import android.location.Location
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Recorded drives: the route the phone took while Siren was scanning, kept on the phone only, so
 * a trip can be looked at again on the map with the flagged equipment seen along it. One file per
 * scanning session ("time,lat,lon" lines); sessions that hardly moved aren't kept. After RF
 * Sentinel's recorded traces.
 */
object Traces {
    data class Point(val time: Long, val lat: Double, val lon: Double)

    data class Trace(val file: File, val start: Long, val end: Long) {
        val id get() = file.name
    }

    /** The trace shown on the map, with its points and the flagged devices seen during it. */
    data class Shown(val trace: Trace, val points: List<Point>, val flagged: List<Detection>)

    val shown = MutableStateFlow<Shown?>(null)

    private const val KEEP = 100
    /** A session that covered less than this is a stop, not a drive. */
    const val MIN_DRIVE_M = 300.0
    private const val MIN_STEP_M = 15.0
    private const val MAX_ACCURACY_M = 30f

    private fun dir(context: Context) = File(context.filesDir, "traces").apply { mkdirs() }

    fun list(context: Context): List<Trace> = list(dir(context))

    internal fun list(dir: File): List<Trace> =
        dir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty()
            .mapNotNull { f -> f.name.removeSuffix(".csv").toLongOrNull()?.let { Trace(f, it, f.lastModified()) } }
            .sortedByDescending { it.start }

    fun load(trace: Trace): List<Point> = runCatching {
        trace.file.readLines().mapNotNull { line ->
            val p = line.split(',')
            if (p.size < 3) null else Point(p[0].toLong(), p[1].toDouble(), p[2].toDouble())
        }
    }.getOrDefault(emptyList())

    fun lengthM(points: List<Point>) = points.zipWithNext().sumOf { (a, b) -> Odometer.distanceM(a.lat, a.lon, b.lat, b.lon) }

    fun delete(trace: Trace) {
        trace.file.delete()
        if (shown.value?.trace?.id == trace.id) shown.value = null
    }

    fun deleteAll(context: Context) {
        dir(context).listFiles().orEmpty().forEach { it.delete() }
        shown.value = null
    }

    /** Shows [trace] on the map with the flagged equipment seen while it was recorded. */
    fun show(context: Context, trace: Trace) {
        val points = load(trace)
        val flagged = runCatching { History.flaggedBetween(context, trace.start, trace.end) }.getOrDefault(emptyList())
        shown.value = Shown(trace, points, flagged)
    }

    /** Records one scanning session. */
    class Recorder internal constructor(private val dir: File, now: Long = System.currentTimeMillis()) {
        constructor(context: Context) : this(dir(context))

        private val file = File(dir, "$now.csv")
        private var last: Point? = null
        private var meters = 0.0

        @Synchronized
        fun add(loc: Location) {
            if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
            add(Point(loc.time, loc.latitude, loc.longitude))
        }

        @Synchronized
        internal fun add(p: Point) {
            val prev = last
            if (prev != null) {
                val d = Odometer.distanceM(prev.lat, prev.lon, p.lat, p.lon)
                if (d < MIN_STEP_M) return
                meters += d
            }
            last = p
            runCatching { file.appendText("${p.time},${"%.6f".format(java.util.Locale.US, p.lat)},${"%.6f".format(java.util.Locale.US, p.lon)}\n") }
        }

        /** Ends the session: drops it if it barely moved, and keeps only the newest drives. */
        @Synchronized
        fun finish() {
            if (meters < MIN_DRIVE_M) file.delete()
            list(dir).drop(KEEP).forEach { it.file.delete() }
        }
    }
}
