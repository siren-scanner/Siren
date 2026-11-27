package app.sirenscout

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spots equipment that keeps traveling with you: seen for a long stretch, consistently, while
 * you covered real distance. Distance matters because sitting next to a parked cruiser for ten
 * minutes isn't being followed.
 */
class FollowDetector(
    private val minDurationMs: Long,
    private val minDistanceM: Double,
    /** Fraction of the elapsed minutes the device must have been seen in. */
    private val minPresence: Double = 0.6,
) {
    data class Follow(val durationMs: Long, val distanceM: Double, val minutesSeen: Int)

    private class Track(val startOdometerM: Double, val firstSeen: Long) {
        val minutes = HashSet<Long>()
        var alerted = false
    }

    private val tracks = HashMap<String, Track>()

    /**
     * Records a sighting of encounter [uid] and returns a [Follow] the first time it qualifies.
     * [odometerM] is how far the phone has traveled in total, from [Odometer].
     */
    fun sighting(uid: String, firstSeen: Long, now: Long, odometerM: Double, heardAlongTheWay: Boolean = true): Follow? {
        val track = tracks.getOrPut(uid) { Track(odometerM, firstSeen) }
        track.minutes.add(now / 60_000)
        // Not yet heard at places far enough apart: it may just be next to you while you sit still.
        if (track.alerted || !heardAlongTheWay) return null

        val duration = now - track.firstSeen
        val distance = odometerM - track.startOdometerM
        val elapsedMinutes = duration / 60_000 + 1
        val presentEnough = track.minutes.size >= max(3.0, minPresence * elapsedMinutes)
        if (duration < minDurationMs || distance < minDistanceM || !presentEnough) return null

        track.alerted = true
        return Follow(duration, distance, track.minutes.size)
    }

    /** Drops tracks not seen for a while, so memory stays bounded on long drives. */
    fun prune(now: Long, maxIdleMs: Long = Detections.ENCOUNTER_GAP_MS) {
        tracks.values.removeAll { now - (it.minutes.maxOrNull() ?: 0) * 60_000 > maxIdleMs }
    }
}

/** Accumulates distance traveled, ignoring GPS jitter while stationary. */
class Odometer(private val maxAccuracyM: Float = 30f) {
    var meters = 0.0
        private set
    private var anchorLat = Double.NaN
    private var anchorLon = 0.0
    private var anchorTime: Long? = null

    /**
     * [speedMps] is the GPS's own speed reading (Doppler, independent of the position): when it
     * says we're standing still, position wobble isn't counted. Parked under a canopy or near
     * buildings the position wanders tens of metres, which used to add up to phantom miles.
     */
    fun add(lat: Double, lon: Double, accuracyM: Float, speedMps: Float? = null, timeMs: Long? = null) {
        if (accuracyM > maxAccuracyM) return
        if (speedMps != null && speedMps < MIN_MOVING_MPS && !anchorLat.isNaN()) return
        if (anchorLat.isNaN()) {
            anchorLat = lat
            anchorLon = lon
            anchorTime = timeMs
            return
        }
        val d = distanceM(anchorLat, anchorLon, lat, lon)
        // A stale first fix (e.g. from before a flight) jumping to the real one isn't travel: start over there.
        val dt = if (timeMs != null && anchorTime != null) (timeMs - anchorTime!!) / 1000.0 else null
        if (dt != null && dt > 0 && d / dt > MAX_PLAUSIBLE_MPS && d > 1_000) {
            anchorLat = lat; anchorLon = lon; anchorTime = timeMs
            return
        }
        // Only advance once we've clearly moved beyond the fix's own uncertainty.
        if (d > max(accuracyM.toDouble(), 10.0)) {
            meters += d
            anchorLat = lat
            anchorLon = lon
            anchorTime = timeMs
        }
    }

    companion object {
        /** Below this GPS speed (about 2 mph) we count as standing still. */
        const val MIN_MOVING_MPS = 0.9f
        /** Faster than this (about 170 mph) between two fixes is a jump in the data, not driving. */
        const val MAX_PLAUSIBLE_MPS = 75.0

        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
            return 2 * 6_371_000.0 * asin(sqrt(a))
        }
    }
}
