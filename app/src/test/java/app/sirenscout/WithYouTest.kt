package app.sirenscout

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WithYouTest {
    private val t0 = 1_000_000_000_000L
    private val minute = 60_000L

    @After fun tearDown() = Detections.clear()

    /** A sighting [odo] metres into the trip; heard at [lat], [lon] (made-up places). */
    private fun see(at: Long, odo: Double, lat: Double = 40.0, lon: Double = -100.0) {
        Detections.addReading("BLE|AA:BB:CC:DD:EE:FF", lat, lon, -60, at)
        Detections.record(Source.BLE, "AA:BB:CC:DD:EE:FF", "My Watch", -60, null, false, null, odometerM = odo, now = at)
    }

    private fun current() = Detections.items.value.getValue("BLE|AA:BB:CC:DD:EE:FF")

    @Test
    fun `a device that rides along for a while qualifies`() {
        for (i in 0..20) see(t0 + i * minute, i * 400.0, lat = 40.0 + i * 0.0036) // 20 min, 8 km north
        val withYou = current().withYou
        assertNotNull(withYou)
        assertEquals(20 * minute, withYou!!.first)
        assertEquals(8000.0, withYou.second, 0.1)
    }

    @Test
    fun `sitting still next to something doesn't count`() {
        for (i in 0..30) see(t0 + i * minute, 50.0) // 30 min, no movement
        assertNull(current().withYou)
    }

    @Test
    fun `parked with GPS wobble doesn't count, even if the odometer drifted`() {
        // The odometer says 0.6 mi, but every sighting was within ~50 m: a router next to a parked car.
        for (i in 0..8) see(t0 + i * minute, i * 120.0, lat = 40.0 + (i % 3) * 0.0002, lon = -100.0 + (i % 2) * 0.0003)
        assertNull(current().withYou)
    }

    @Test
    fun `the odometer ignores position wobble while the GPS says we're stopped`() {
        val o = Odometer()
        o.add(40.0, -100.0, 5f, 0f)
        repeat(20) { o.add(40.0 + (it % 2) * 0.0004, -100.0, 5f, 0.2f) } // 45 m jumps, standing still
        assertEquals(0.0, o.meters, 0.0)
        o.add(40.01, -100.0, 5f, 20f) // really driving
        assertEquals(1112.0, o.meters, 5.0)
    }

    @Test
    fun `a quick pass doesn't count however far`() {
        for (i in 0..3) see(t0 + i * minute, i * 2000.0) // 3 min
        assertNull(current().withYou)
    }

    @Test
    fun `a long gap starts a new stretch`() {
        for (i in 0..20) see(t0 + i * minute, i * 400.0)
        see(t0 + 60 * minute, 30_000.0) // gone 40 min, then back
        assertNull(current().withYou)
    }

    @Test
    fun `formats hours and minutes`() {
        val text = formatWithYou(130 * minute to 1609.34 * 34)
        assertEquals(true, text.startsWith("2 h 10 min · "))
    }

    @Test
    fun `something that rode along stays with you after a long stop`() {
        for (i in 0..20) see(t0 + i * minute, i * 400.0, lat = 40.0 + i * 0.0036) // 8 km together
        // Then parked for over an hour: far more readings than are kept, all at one spot.
        for (i in 1..400) see(t0 + 20 * minute + i * 10_000L, 8000.0, lat = 40.072 + (i % 2) * 0.00001)
        assertNotNull(current().withYou)
    }

    @Test
    fun `a Wi-Fi reading of 0 is no reading, never the strongest`() {
        Detections.record(Source.WIFI, "10:00:00:00:00:01", "Net", -70, null, false, null, now = t0)
        Detections.record(Source.WIFI, "10:00:00:00:00:01", "Net", 0, null, false, null, now = t0 + 1000)
        assertEquals(-70, Detections.items.value.getValue("WIFI|10:00:00:00:00:01").bestRssi)
    }

    @Test
    fun `a jump from a stale fix to the real one isn't counted as travel`() {
        val o = Odometer()
        o.add(37.42, -122.08, 5f, null, t0)              // stale fix in California
        o.add(34.7465, -92.2896, 5f, 25f, t0 + 60_000)   // a minute later, the real one in Arkansas
        assertEquals(0.0, o.meters, 0.0)
        o.add(34.7565, -92.2896, 5f, 25f, t0 + 100_000)  // then really driving 1.1 km
        assertEquals(1112.0, o.meters, 5.0)
    }
}
