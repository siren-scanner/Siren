package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackersTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun `separated AirTag counts, one near its owner does not`() {
        val separated = bytes(0x12, 0x19, 0x10) + ByteArray(24)
        val nearOwner = bytes(0x12, 0x02, 0x00, 0x01)
        assertEquals(Trackers.APPLE, Trackers.classify(mapOf(0x004C to separated), emptyMap(), emptySet()))
        assertNull(Trackers.classify(mapOf(0x004C to nearOwner), emptyMap(), emptySet()))
    }

    @Test
    fun `ordinary iPhone and AirPods broadcasts are not trackers`() {
        assertNull(Trackers.classify(mapOf(0x004C to bytes(0x10, 0x05, 0x01)), emptyMap(), emptySet())) // nearby info
        assertNull(Trackers.classify(mapOf(0x004C to bytes(0x07, 0x19, 0x01)), emptyMap(), emptySet())) // AirPods pairing
    }

    @Test
    fun `tile smarttag dult and find hub`() {
        assertEquals(Trackers.TILE, Trackers.classify(emptyMap(), emptyMap(), setOf(0xFEED)))
        assertEquals(Trackers.TILE, Trackers.classify(emptyMap(), mapOf(0xFD84 to bytes(1)), emptySet()))
        assertEquals(Trackers.SMARTTAG, Trackers.classify(emptyMap(), mapOf(0xFD5A to bytes(1, 2)), emptySet()))
        assertEquals(Trackers.DULT, Trackers.classify(emptyMap(), mapOf(0xFCB2 to bytes(0)), emptySet()))
        assertEquals(Trackers.GOOGLE, Trackers.classify(emptyMap(), mapOf(0xFEAA to bytes(0x41, 0x00)), emptySet()))
    }

    @Test
    fun `find hub tag near its owner is not a tracker`() {
        // Frame 0x40 rotates its address every ~15 min; counting it flooded an owner with alerts.
        assertNull(Trackers.classify(emptyMap(), mapOf(0xFEAA to bytes(0x40, 0x00)), emptySet()))
    }

    @Test
    fun `plain eddystone beacons are not trackers`() {
        assertNull(Trackers.classify(emptyMap(), mapOf(0xFEAA to bytes(0x10, 0x00)), emptySet())) // Eddystone-URL
        assertNull(Trackers.classify(emptyMap(), mapOf(0xFEAA to bytes(0x00)), emptySet()))       // Eddystone-UID
    }
}
