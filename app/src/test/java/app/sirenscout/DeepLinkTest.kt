package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeepLinkTest {
    @Test
    fun `links survive the trip through a notification`() {
        listOf(
            DeepLink.Device("BLE|00:25:DF:11:22:33"),
            DeepLink.Air("aircraft|a1423f", "Jefferson Parish Sheriffs Office"),
            DeepLink.Air("drone|AA:BB:CC:00:11:22", "Drone"),
            DeepLink.Flight("AA100"),
            DeepLink.Place(40.0123, -100.0456, "Flock Safety camera"),
        ).forEach { assertEquals(it, DeepLink.decode(it.encode())) }
        assertNull(DeepLink.decode("nonsense"))
    }
}
