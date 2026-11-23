package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleRoutersTest {
    // Made-up addresses on Cradlepoint's registered prefix.
    private val bssid = "00:30:44:12:34:56"

    @Test
    fun `cradlepoint business models don't match, vehicle models and custom names do`() {
        assertNull(Signatures.match(Source.WIFI, bssid, "E300-a1b"))       // branch router in a shop
        assertNull(Signatures.match(Source.WIFI, bssid, "AER2200-c3d"))
        assertNull(Signatures.match(Source.WIFI, bssid, "CBA850-0f9"))
        assertEquals("cradlepoint", Signatures.match(Source.WIFI, bssid, "IBR900-21e")?.id)
        assertEquals("cradlepoint", Signatures.match(Source.WIFI, bssid, "R1900-5g1")?.id)
        assertEquals("cradlepoint", Signatures.match(Source.WIFI, bssid, "Unit 12")?.id)   // custom name: still a maybe
        assertEquals("cradlepoint", Signatures.match(Source.WIFI, bssid, null)?.id)
        assertEquals("business router", DeviceGuesser.wifi(bssid, "E300-a1b")?.substringAfter(" · "))
    }

    @Test
    fun `vehicle model names`() {
        assertTrue(VehicleRouters.vehicleModelName("IBR900-21e"))
        assertTrue(VehicleRouters.vehicleModelName("IBR1700-600M-abc"))
        assertTrue(VehicleRouters.vehicleModelName("MG90-1234"))
        assertFalse(VehicleRouters.vehicleModelName("Unit 12"))
        assertFalse(VehicleRouters.vehicleModelName("E300-a1b"))
        assertFalse(VehicleRouters.vehicleModelName(null))
    }

    @Test
    fun `other police or EMS gear in the last few minutes backs a router up`() {
        val now = 1_000_000_000L
        fun det(sig: Signature?, lastSeen: Long) = Detection(Source.BLE, "x", null, -60, sig, lastSeen, lastSeen, 1, 0L, -60, null, null)
        val axon = Signatures.byId("axon")
        val router = Signatures.byId("cradlepoint")
        assertTrue(VehicleRouters.corroborated(listOf(det(axon, now - 60_000)), now))
        assertFalse(VehicleRouters.corroborated(listOf(det(axon, now - 10 * 60_000)), now)) // too long ago
        assertFalse(VehicleRouters.corroborated(listOf(det(router, now)), now))            // routers don't back each other up
        assertTrue(VehicleRouters.isRouter(router))
        assertFalse(VehicleRouters.isRouter(axon))
        assertFalse(VehicleRouters.isRouter(Signatures.byId("rugged-laptop")))
    }

    @Test
    fun `a router seen in the same place on another day is fixed, one that moves is not`() {
        val map = HashMap<String, VehicleRouters.Sighting>()
        val day = 20_000L
        VehicleRouters.step(map, "A", 40.0, -100.0, day)
        VehicleRouters.step(map, "A", 40.0005, -100.0, day)       // same day, ~55 m away: nothing yet
        assertFalse(map["A"]!!.fixed)
        VehicleRouters.step(map, "A", 40.001, -100.0, day + 1)     // next day, ~110 m: fixed
        assertTrue(map["A"]!!.fixed)

        VehicleRouters.step(map, "B", 40.0, -100.0, day)
        VehicleRouters.step(map, "B", 40.05, -100.0, day + 1)      // next day, 5.5 km away: it moves
        assertFalse(map["B"]!!.fixed)
        VehicleRouters.step(map, "B", 40.2, -100.0, day + 2)
        assertFalse(map["B"]!!.fixed)
    }
}
