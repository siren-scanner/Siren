package app.sirenscout

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DistanceEstimateTest {
    private val saved = Locale.getDefault()

    @After
    fun restore() = Locale.setDefault(saved)

    @Test
    fun `stronger signals are closer, and Wi-Fi reaches further than Bluetooth`() {
        assertTrue(estimateMeters(-60, Source.BLE) < estimateMeters(-80, Source.BLE))
        assertTrue(estimateMeters(-80, Source.WIFI) > estimateMeters(-80, Source.BLE))
        assertEquals(1.0, estimateMeters(-40, Source.BLE), 0.0) // never closer than a metre
        assertEquals(26.0, estimateMeters(-90, Source.BLE), 2.0)
    }

    @Test
    fun `distances are rounded to sensible steps in feet or metres`() {
        Locale.setDefault(Locale.US)
        assertEquals("within 5 ft", roughDistance(1.0))
        assertEquals("about 30 ft", roughDistance(9.2))
        assertEquals("about 80 ft", roughDistance(25.0))
        assertEquals("about 650 ft", roughDistance(200.0))
        assertEquals("about 1.2 mi", roughDistance(2_000.0))
        Locale.setDefault(Locale.GERMANY)
        assertEquals("within 2 m", roughDistance(1.0))
        assertEquals("about 9 m", roughDistance(9.2))
        assertEquals("about 25 m", roughDistance(26.0))
        assertEquals("about 200 m", roughDistance(200.0))
    }
}
