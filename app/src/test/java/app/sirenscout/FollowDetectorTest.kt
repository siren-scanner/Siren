package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FollowDetectorTest {
    private val minute = 60_000L
    private val start = 1_000_000_000_000L

    private fun detector() = FollowDetector(minDurationMs = 5 * minute, minDistanceM = 1609.0)

    @Test
    fun `alerts once when seen steadily while driving`() {
        val d = detector()
        var alerts = 0
        // Seen every 30 s for 10 minutes while driving 500 m per minute.
        for (i in 0..20) {
            val now = start + i * 30_000L
            if (d.sighting("cop", start, now, odometerM = i * 250.0) != null) alerts++
        }
        assertEquals(1, alerts)
    }

    @Test
    fun `alert fires as soon as time and distance are both met`() {
        val d = detector()
        val follow = (0..20).firstNotNullOfOrNull { i ->
            d.sighting("cop", start, start + i * 30_000L, odometerM = i * 250.0)
        }
        assertNotNull(follow)
        assertEquals(5 * minute, follow!!.durationMs) // 1609 m is reached by minute 3.5, time by minute 5
    }

    @Test
    fun `no alert while parked next to it`() {
        val d = detector()
        for (i in 0..40) assertNull(d.sighting("cop", start, start + i * 30_000L, odometerM = 0.0))
    }

    @Test
    fun `no alert for a quick pass even over long distance`() {
        val d = detector()
        for (i in 0..4) assertNull(d.sighting("cop", start, start + i * 30_000L, odometerM = i * 2000.0))
    }

    @Test
    fun `no alert when only seen at the start and end`() {
        val d = detector()
        assertNull(d.sighting("cop", start, start, 0.0))
        assertNull(d.sighting("cop", start, start + 30_000L, 200.0))
        // Nine minutes of silence, then seen again 5 km later.
        assertNull(d.sighting("cop", start, start + 9 * minute, 5000.0))
    }

    @Test
    fun `encounters are tracked separately`() {
        val d = detector()
        for (i in 0..20) d.sighting("a", start, start + i * 30_000L, i * 250.0)
        assertNull(d.sighting("b", start + 10 * minute, start + 10 * minute, 5000.0))
    }

    @Test
    fun `odometer ignores jitter and counts real movement`() {
        val odo = Odometer()
        odo.add(40.0, -100.0, 8f)
        repeat(50) { odo.add(40.0 + (it % 2) * 0.00003, -100.0, 8f) } // ~3 m wobble
        assertEquals(0.0, odo.meters, 0.0)
        odo.add(40.01, -100.0, 8f) // ~1.1 km north
        assertEquals(1112.0, odo.meters, 5.0)
        odo.add(40.13, -100.0, 200f) // too inaccurate, ignored
        assertEquals(1112.0, odo.meters, 5.0)
    }
}
