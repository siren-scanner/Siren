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

    private fun see(at: Long, odo: Double) =
        Detections.record(Source.BLE, "AA:BB:CC:DD:EE:FF", "My Watch", -60, null, false, null, odometerM = odo, now = at)

    private fun current() = Detections.items.value.getValue("BLE|AA:BB:CC:DD:EE:FF")

    @Test
    fun `a device that rides along for a while qualifies`() {
        for (i in 0..20) see(t0 + i * minute, i * 400.0) // 20 min, 8 km
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
}
