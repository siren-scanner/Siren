package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AirTest {
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private fun basicId(serial: String) = ByteArray(25).also { m ->
        m[0] = 0x02 // Basic ID, protocol version 2
        m[1] = 0x12 // serial number, multirotor
        serial.toByteArray().copyInto(m, 2)
    }

    private fun location(lat: Double, lon: Double, heightM: Double, speedMs: Double, heading: Int) = ByteArray(25).also { m ->
        m[0] = 0x12 // Location, version 2
        m[1] = (0x20 or (if (heading >= 180) 0x02 else 0)).toByte() // airborne, EW direction bit
        m[2] = (heading % 180).toByte()
        m[3] = (speedMs / 0.25).toInt().toByte()
        le32((lat * 1e7).toInt()).copyInto(m, 5)
        le32((lon * 1e7).toInt()).copyInto(m, 9)
        le16(((heightM + 1000) / 0.5).toInt()).copyInto(m, 17)
    }

    private fun system(lat: Double, lon: Double) = ByteArray(25).also { m ->
        m[0] = 0x42
        le32((lat * 1e7).toInt()).copyInto(m, 2)
        le32((lon * 1e7).toInt()).copyInto(m, 6)
    }

    @Test
    fun `decodes Bluetooth Remote ID one message at a time`() {
        var d = RemoteId.fromBle(byteArrayOf(0x0D, 1) + basicId("1581F5BKD225400D9X"))!!
        d = RemoteId.fromBle(byteArrayOf(0x0D, 2) + location(40.01, -100.02, 85.5, 6.0, 270), d)!!
        d = RemoteId.fromBle(byteArrayOf(0x0D, 3) + system(40.0, -100.0), d)!!
        assertEquals("1581F5BKD225400D9X", d.id)
        assertEquals("Serial number", d.idType)
        assertEquals("Multirotor / helicopter", d.aircraftType)
        assertEquals(40.01, d.lat!!, 1e-6)
        assertEquals(-100.02, d.lon!!, 1e-6)
        assertEquals(85.5, d.heightM!!, 0.01)
        assertEquals(6.0, d.speedMs!!, 0.01)
        assertEquals(270.0, d.headingDeg!!, 0.0)
        assertEquals(40.0, d.operatorLat!!, 1e-6)
    }

    @Test
    fun `decodes a message pack from a Wi-Fi beacon`() {
        val pack = byteArrayOf(0xF2.toByte(), 25, 2) + basicId("ABC123") + location(40.5, -99.5, 30.0, 2.0, 45)
        val element = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D, 7) + pack
        val d = RemoteId.fromWifiElement(element)!!
        assertEquals("ABC123", d.id)
        assertEquals(40.5, d.lat!!, 1e-6)
        assertEquals(45.0, d.headingDeg!!, 0.0)
    }

    @Test
    fun `ignores things that aren't Remote ID`() {
        assertNull(RemoteId.fromBle(ByteArray(27)))
        assertNull(RemoteId.fromWifiElement(byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04, 0) + ByteArray(30)))
    }

    @Test
    fun `reads the ADS-B feed and the police registry`() {
        val feed = """{"ac":[
            {"hex":"a1423f","flight":"N180SC  ","lat":40.05,"lon":-100.0,"alt_baro":1200,"gs":90.5,"track":180.0},
            {"hex":"abcdef","lat":41.0,"lon":-101.0,"alt_baro":"ground"},
            {"hex":"c0ffee"}
        ]}"""
        val ac = Adsb.parseFeed(feed)
        assertEquals(2, ac.size)
        assertEquals("N180SC", ac[0].callsign)
        assertEquals(1200, ac[0].altitudeFt)
        assertTrue(ac[1].onGround)

        val reg = Adsb.parseRegistry("a1423f\tN180SC\tSt Clair County Sheriffs Dept\tBell OH-58\n")
        assertEquals("St Clair County Sheriffs Dept", reg["a1423f"]!!.owner)
    }

    @Test
    fun `the shipped police aircraft list is well formed`() {
        val reg = Adsb.parseRegistry(File("src/main/assets/police_aircraft.txt").readText())
        assertTrue("expected hundreds of aircraft, got ${reg.size}", reg.size > 500)
        assertTrue(reg.keys.all { Regex("[0-9a-f]{6}").matches(it) })
    }

    @Test
    fun `police aircraft alert once when close, then again after half an hour`() {
        AirWatch.clear()
        val heli = Adsb.Aircraft("a1423f", "N180SC", 40.05, -100.0, 1200, 90.0, 180.0, onGround = false)
        val reg = Adsb.Registration("N180SC", "St Clair County Sheriffs Dept", "Bell OH-58")
        val far = Adsb.Aircraft("a00001", null, 40.9, -100.0, 3000, 120.0, 0.0, onGround = false)
        val t0 = 1_000_000L
        val first = AirWatch.onAircraft(listOf(heli to reg, far to reg), 40.0, -100.0, alertWithinM = 8_000.0, now = t0)
        assertEquals(listOf("aircraft|a1423f"), first.map { it.key }) // ~5.6 km away; the other is ~100 km
        assertEquals(2, AirWatch.contacts.value.size)
        assertTrue(AirWatch.onAircraft(listOf(heli to reg), 40.0, -100.0, 8_000.0, now = t0 + 60_000).isEmpty())
        assertEquals(1, AirWatch.onAircraft(listOf(heli to reg), 40.0, -100.0, 8_000.0, now = t0 + AirWatch.REALERT_MS + 1).size)
        AirWatch.prune(now = t0 + AirWatch.REALERT_MS + 1 + 200_000)
        assertTrue(AirWatch.contacts.value.isEmpty())
    }

    @Test
    fun `a drone alerts on its first broadcast and collects later ones`() {
        AirWatch.clear()
        val t0 = 5_000_000L
        assertNotNull(AirWatch.onRemoteId("AA:BB", -70, { RemoteId.fromBle(byteArrayOf(0x0D, 1) + basicId("XYZ"), it) }, 40.0, -100.0, t0))
        assertNull(AirWatch.onRemoteId("AA:BB", -68, { RemoteId.fromBle(byteArrayOf(0x0D, 2) + location(40.001, -100.0, 50.0, 3.0, 90), it) }, 40.0, -100.0, t0 + 1_000))
        val c = AirWatch.contacts.value.getValue("drone|AA:BB")
        assertTrue(c.detail.contains("XYZ"))
        assertEquals(111.0, c.distanceM!!, 2.0)
    }
}
