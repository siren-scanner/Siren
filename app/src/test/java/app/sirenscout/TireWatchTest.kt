package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TireWatchTest {
    private val t0 = 1_000_000_000_000L
    private val min = 60_000L

    /** Drives north 600 m a minute; [ids] are heard every minute for [minutes]. */
    private fun drive(w: TireWatch, ids: List<String>, minutes: Int, start: Long = t0 + 10 * min): TireWatch.Follow? {
        var follow: TireWatch.Follow? = null
        for (m in 0..minutes) for (id in ids) {
            w.heard(id, start + m * min, 40.0 + m * 0.0054, -100.0, m * 600.0).second?.let { follow = it }
        }
        return follow
    }

    @Test
    fun `a car whose tires stay with you is one alert, after enough time and distance`() {
        val w = TireWatch(HashMap(), sessionStart = t0)
        assertNull(drive(TireWatch(HashMap(), t0), listOf("aaaa0001", "aaaa0002"), 6)) // 6 min, 3.6 km: not yet
        val f = drive(w, listOf("aaaa0001", "aaaa0002", "aaaa0003", "aaaa0004"), 15)
        assertNotNull(f)
        assertEquals(4, f!!.sensors.size)
        assertTrue(f.distanceM >= 5_000)
        assertNull(drive(w, listOf("aaaa0001"), 20, start = t0 + 30 * min)) // no second alert for the same car
    }

    @Test
    fun `your own car is learned from the start of two drives and never alerts`() {
        val starts = HashMap<String, Int>()
        // Two sessions where your tires are heard in the first minutes.
        for (session in 0..1) {
            val s0 = t0 + session * 1000 * min
            val w = TireWatch(starts, sessionStart = s0)
            for (m in 0..3) w.heard("0w0c0001", s0 + m * 20_000L, 40.0, -100.0, 0.0)
        }
        val w = TireWatch(starts, sessionStart = t0 + 5000 * min)
        assertTrue(w.isOwn("0w0c0001"))
        assertNull(drive(w, listOf("0w0c0001"), 30, start = t0 + 5000 * min))
    }

    @Test
    fun `a sensor heard once is noise, not a car`() {
        val w = TireWatch(HashMap(), t0)
        val (confirmed, _) = w.heard("deadbeef", t0, 40.0, -100.0, 0.0)
        assertNull(confirmed)
        assertNotNull(w.heard("deadbeef", t0 + 30_000, 40.0, -100.0, 0.0).first)
    }

    @Test
    fun `parked next to the same car doesn't count`() {
        val w = TireWatch(HashMap(), t0)
        var f: TireWatch.Follow? = null
        for (m in 0..40) w.heard("bbbb0001", t0 + 10 * min + m * min, 40.0, -100.0, 0.0).second?.let { f = it }
        assertNull(f)
    }
}
