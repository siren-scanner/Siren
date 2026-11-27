package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrowdsTest {
    private val t = 1_000_000_000_000L
    private val zebraUuid = "0000fe79-0000-1000-8000-00805f9b34fb"

    @Test
    fun `store printers don't match, an unrenamed one with Zebra's ID does`() {
        assertNull(Signatures.match(Source.BLE, "48:A4:93:B7:86:7F", "WMP04.1766", serviceUuids = listOf(zebraUuid)))
        assertEquals("fleet-printer", Signatures.match(Source.BLE, "48:A4:93:B7:86:7F", "XXZQL123456789", serviceUuids = listOf(zebraUuid))?.id)
        assertNull(Signatures.match(Source.BLE, "48:A4:93:B7:86:7F", "XXZQL123456789")) // the name alone isn't enough
    }

    private fun d(i: Int, sig: String, lat: Double = 34.75, seen: Long = t) =
        Detection(Source.BLE, "4A:00:00:00:00:%02X".format(i), null, -70, Signatures.byId(sig), seen - 60_000, seen, 5, 0, -70, lat + i * 0.0001, -92.29)

    @Test
    fun `many of one kind in one spot is a business`() {
        val shop = (1..8).map { d(it, "ems-devices") }
        assertTrue(Crowds.crowded(Signatures.byId("ems-devices"), shop, t))
        assertFalse(shop.any { Crowds.isMatch(it, shop, t) })
        val two = (1..2).map { d(it, "ems-devices") }
        assertTrue(two.all { Crowds.isMatch(it, two, t) })
        // Spread over miles (passing ambulances on a drive), not a crowd.
        val spread = (1..6).map { d(it, "ems-devices", lat = 34.75 + it * 0.05) }
        assertFalse(Crowds.crowded(Signatures.byId("ems-devices"), spread, t))
    }

    @Test
    fun `trackers are matches only once they've been with you`() {
        val tag = Detection(Source.BLE, "EC:18:38:77:78:D1", null, -90, Trackers.APPLE, t - 60_000, t, 2, 0, -90, 34.75, -92.29)
        assertFalse(Crowds.isMatch(tag, listOf(tag), t))
        val following = tag.copy(firstSeen = t - 30 * 60_000, startOdometerM = 0.0, lastOdometerM = 15_000.0, spreadM = 10_000.0)
        assertTrue(Crowds.isMatch(following, listOf(following), t))
    }

    @Test
    fun `police gear in one spot is never a business, low-confidence radios can be`() {
        val scene = (1..8).map { d(it, "axon") }
        assertFalse(Crowds.crowded(Signatures.byId("axon"), scene, t))
        assertTrue(scene.all { Crowds.isMatch(it, scene, t) })
        val mall = (1..8).map { d(it, "motorola-solutions") }
        assertTrue(Crowds.crowded(Signatures.byId("motorola-solutions"), mall, t))
    }
}
