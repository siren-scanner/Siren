package app.sirenscout

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** One signal reading taken at a known spot. */
data class Reading(val lat: Double, val lon: Double, val rssi: Double, val time: Long)

/** Where a stationary device probably is, and how sure we are (radius in metres). */
data class LocationEstimate(val lat: Double, val lon: Double, val radiusM: Double, val readings: Int)

/**
 * Estimates where a stationary transmitter is from signal readings taken along your route.
 *
 * Signal strength falls with distance roughly as rssi = P - 10·n·log10(d). For each candidate
 * position on a grid around the route we fit the best P and n to all readings and keep the
 * position that explains them best. The uncertainty radius covers every candidate that fits
 * almost as well - which is large when the readings can't tell, e.g. which side of a straight
 * road something is on.
 */
object LocationEstimator {
    const val MIN_READINGS = 8
    /** The route must cover at least this much ground to say anything. */
    private const val MIN_SPREAD_M = 40.0
    /** Readings this flat carry no distance information. */
    private const val MIN_RSSI_RANGE_DB = 6.0
    private const val MARGIN_M = 150.0
    private const val GRID = 60
    private const val METERS_PER_DEG_LAT = 110_540.0

    fun estimate(readings: List<Reading>): LocationEstimate? {
        if (readings.size < MIN_READINGS) return null
        val rssiRange = readings.maxOf { it.rssi } - readings.minOf { it.rssi }
        if (rssiRange < MIN_RSSI_RANGE_DB) return null

        // Work in metres on a flat local map around the first reading.
        val lat0 = readings[0].lat
        val lon0 = readings[0].lon
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(lat0))
        val xs = DoubleArray(readings.size) { (readings[it].lon - lon0) * mPerDegLon }
        val ys = DoubleArray(readings.size) { (readings[it].lat - lat0) * METERS_PER_DEG_LAT }
        val rs = DoubleArray(readings.size) { readings[it].rssi }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        if (hypot(maxX - minX, maxY - minY) < MIN_SPREAD_M) return null

        val x0 = minX - MARGIN_M; val y0 = minY - MARGIN_M
        val w = (maxX - minX) + 2 * MARGIN_M; val h = (maxY - minY) + 2 * MARGIN_M
        val cell = max(w, h) / GRID
        val nx = (w / cell).toInt() + 1; val ny = (h / cell).toInt() + 1
        val errors = DoubleArray(nx * ny)
        var best = 0
        for (j in 0 until ny) for (i in 0 until nx) {
            val e = fitError(x0 + i * cell, y0 + j * cell, xs, ys, rs)
            errors[j * nx + i] = e
            if (e < errors[best]) best = j * nx + i
        }
        val bx = x0 + (best % nx) * cell; val by = y0 + (best / nx) * cell

        // Everything within ~20% of the best fit is about as believable; its spread is the radius.
        val threshold = errors[best] * 1.2 + 1e-9
        var radius = cell
        for (k in errors.indices) if (errors[k] <= threshold) {
            radius = max(radius, hypot(x0 + (k % nx) * cell - bx, y0 + (k / nx) * cell - by))
        }
        return LocationEstimate(
            lat = lat0 + by / METERS_PER_DEG_LAT,
            lon = lon0 + bx / mPerDegLon,
            radiusM = radius,
            readings = readings.size,
        )
    }

    /** Mean squared error of the best path-loss fit if the transmitter were at (px, py). */
    private fun fitError(px: Double, py: Double, xs: DoubleArray, ys: DoubleArray, rs: DoubleArray): Double {
        val n = xs.size
        val ls = DoubleArray(n) { log10(max(1.0, hypot(xs[it] - px, ys[it] - py))) }
        val mx = ls.average(); val my = rs.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { sxy += (ls[i] - mx) * (rs[i] - my); sxx += (ls[i] - mx) * (ls[i] - mx) }
        // Slope is -10·n; keep n physical (1.5-4.5) so a bad spot can't fake a good fit.
        val slope = (if (sxx > 1e-9) sxy / sxx else 0.0).coerceIn(-45.0, -15.0)
        val intercept = my - slope * mx
        var err = 0.0
        for (i in 0 until n) { val r = rs[i] - (intercept + slope * ls[i]); err += r * r }
        return err / n
    }

    /** Typical spread of the readings, for tests and diagnostics. */
    fun rssiStdDev(readings: List<Reading>): Double {
        val m = readings.map { it.rssi }.average()
        return sqrt(readings.sumOf { (it.rssi - m) * (it.rssi - m) } / readings.size)
    }
}
