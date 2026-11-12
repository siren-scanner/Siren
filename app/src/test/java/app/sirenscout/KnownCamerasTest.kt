package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Test

class KnownCamerasTest {
    private fun cam(vararg dirs: Float, lat: Double = 40.0, lon: Double = -100.0) =
        Camera(1, lat, lon, dirs.map { CameraView(it) }, null, null)

    @Test
    fun `parses degrees cardinals lists and ranges`() {
        assertEquals(listOf(CameraView(270f)), parseDirections("270"))
        assertEquals(listOf(CameraView(45f)), parseDirections("NE"))
        assertEquals(listOf(CameraView(337.5f)), parseDirections("nnw"))
        assertEquals(listOf(CameraView(0f), CameraView(180f)), parseDirections("0;180"))
        assertEquals(listOf(CameraView(90f, 90f)), parseDirections("45-135"))
        assertEquals(listOf(CameraView(0f, 90f)), parseDirections("315-45")) // wraps through north
        assertEquals(listOf(CameraView(213f, 60f), CameraView(72f, 60f)), parseDirections("183-243;42-102"))
        assertEquals(listOf(CameraView(10f)), parseDirections("370"))
        assertEquals(emptyList<CameraView>(), parseDirections(null))
        assertEquals(listOf(CameraView(90f)), parseDirections("E;garbage"))
    }

    @Test
    fun `classifies which way a camera faces`() {
        // Driving north (0 deg).
        assertEquals(Facing.TOWARD, facing(cam(180f), 0f)) // points south, at oncoming traffic
        assertEquals(Facing.AWAY, facing(cam(20f), 0f))
        assertEquals(Facing.ACROSS, facing(cam(90f), 0f))
        assertEquals(Facing.TOWARD, facing(cam(90f, 170f), 0f)) // any direction toward you wins
        assertEquals(Facing.UNKNOWN, facing(cam(), 0f))
        assertEquals(Facing.UNKNOWN, facing(cam(180f), null))
    }

    @Test
    fun `only cameras ahead and in range count`() {
        val ahead = cam(180f, lat = 40.001, lon = -100.0)   // ~110 m north
        val behind = cam(0f, lat = 39.999, lon = -100.0)    // ~110 m south
        val far = cam(180f, lat = 40.01, lon = -100.0)      // ~1.1 km north
        val all = listOf(ahead, behind, far)
        assertEquals(listOf(ahead), camerasAhead(all, 40.0, -100.0, heading = 0f, radiusM = 250.0).map { it.first })
        // Stopped: no heading, everything in range.
        assertEquals(2, camerasAhead(all, 40.0, -100.0, heading = null, radiusM = 250.0).size)
    }

    @Test
    fun `bearing math`() {
        assertEquals(0f, bearingDeg(40.0, -100.0, 40.1, -100.0), 0.5f)
        assertEquals(90f, bearingDeg(40.0, -100.0, 40.0, -99.9), 0.5f)
        assertEquals(20f, angleBetween(350f, 10f), 0.001f)
    }

    @Test
    fun `lookups only reveal the area, rounded to 0_1 degree`() {
        assertEquals(40.1, KnownCameras.roundForQuery(40.123456), 0.0)
        assertEquals(-100.1, KnownCameras.roundForQuery(-100.124512), 0.0)
    }
}
