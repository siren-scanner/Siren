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

    private fun wifi(bssid: String, rssi: Int, name: String? = "home_network") =
        Detection(Source.WIFI, bssid, name, rssi, null, 0, 0, 1, 0, rssi, null, null, null)

    @Test
    fun `one router's 2_4 and 5 GHz networks get the same distance`() {
        Locale.setDefault(Locale.US)
        WifiBands.record("02:11:22:33:44:08", 2437)
        WifiBands.record("02:11:22:33:44:09", 5180)
        val low = wifi("02:11:22:33:44:08", -52)
        val high = wifi("02:11:22:33:44:09", -67)
        val neighbour = wifi("aa:bb:cc:00:11:22", -67, "Smith Family")
        val all = listOf(low, high, neighbour)
        assertEquals(distanceText(low, all), distanceText(high, all))
        assertEquals("5 GHz", WifiBands.band("02:11:22:33:44:09"))
        // On its own, 5 GHz at -67 is estimated closer than 2.4 GHz at -67: it fades faster.
        assertTrue(estimateMeters(-67, Source.WIFI, 5180) < estimateMeters(-67, Source.WIFI, 2437))
        assertTrue(distanceText(neighbour, all) != distanceText(high, all))
    }

    @Test
    fun `a 0 dBm reading isn't a real signal`() {
        assertEquals("distance unknown", proximity(0, Source.WIFI))
        assertEquals("distance unknown", distanceText(wifi("02:aa:bb:cc:dd:ee", 0, "Provider_ABC123"), emptyList()))
    }
}
