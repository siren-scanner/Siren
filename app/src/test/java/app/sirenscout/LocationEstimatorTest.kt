package app.sirenscout

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.random.Random

class LocationEstimatorTest {
    private val lat0 = 40.0
    private val lon0 = -100.0
    private val mLon = 111_320.0 * cos(Math.toRadians(lat0))
    private val mLat = 110_540.0

    /** Readings along a path of (x, y) metres for a transmitter at (sx, sy), with noise. */
    private fun drive(path: List<Pair<Double, Double>>, sx: Double, sy: Double, noiseDb: Double = 3.0, seed: Int = 1): List<Reading> {
        val rnd = Random(seed)
        return path.mapIndexed { i, (x, y) ->
            val d = maxOf(1.0, hypot(x - sx, y - sy))
            val rssi = -45 - 25 * log10(d) + rnd.nextDouble(-noiseDb, noiseDb)
            Reading(lat0 + y / mLat, lon0 + x / mLon, rssi, i * 1000L)
        }
    }

    private fun meters(e: LocationEstimate) = Pair((e.lon - lon0) * mLon, (e.lat - lat0) * mLat)

    private fun straight() = (-300..300 step 15).map { it.toDouble() to 0.0 }

    @Test
    fun `along a straight road it finds the right spot along the road`() {
        // Parked 40 m off the road at x=120. Which side can't be known from one pass.
        val e = LocationEstimator.estimate(drive(straight(), 120.0, 40.0))
        assertNotNull(e)
        val (x, _) = meters(e!!)
        assertTrue("along-road error ${x - 120}", kotlin.math.abs(x - 120) < 40)
        assertTrue("radius ${e.radiusM} should cover the true spot", hypot(meters(e).first - 120, meters(e).second - 40) <= e.radiusM + 1)
    }

    @Test
    fun `a turn in the route pins it down`() {
        val path = straight() + (15..300 step 15).map { 300.0 to it.toDouble() } // drive east, then turn north
        val e = LocationEstimator.estimate(drive(path, 220.0, 90.0))!!
        val (x, y) = meters(e)
        val err = hypot(x - 220, y - 90)
        assertTrue("error $err m", err < 45)
        assertTrue("radius ${e.radiusM} should cover the error $err", err <= e.radiusM + 1)
    }

    @Test
    fun `refuses to guess without enough to go on`() {
        assertNull(LocationEstimator.estimate(drive(straight().take(5), 0.0, 30.0))) // too few readings
        assertNull(LocationEstimator.estimate(drive((0..20).map { it * 1.0 to 0.0 }, 0.0, 30.0))) // barely moved
        val flat = straight().mapIndexed { i, (x, y) -> Reading(lat0 + y / mLat, lon0 + x / mLon, -70.0, i * 1000L) }
        assertNull(LocationEstimator.estimate(flat)) // signal never changes
    }
}
