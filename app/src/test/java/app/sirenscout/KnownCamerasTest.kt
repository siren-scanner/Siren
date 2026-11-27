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

    @Test
    fun `speed and red-light cameras from nodes and enforcement relations`() {
        val json = org.json.JSONObject(
            """{"elements":[
              {"type":"node","id":1,"lat":40.0,"lon":-100.0,"tags":{"surveillance:type":"ALPR","manufacturer":"Flock Safety"}},
              {"type":"node","id":2,"lat":40.1,"lon":-100.0,"tags":{"highway":"speed_camera","maxspeed":"45 mph","direction":"90"}},
              {"type":"node","id":3,"lat":40.2,"lon":-100.0,"tags":{"man_made":"surveillance"}},
              {"type":"node","id":4,"lat":40.3,"lon":-100.0},
              {"type":"node","id":5,"lat":40.4,"lon":-100.0},
              {"type":"node","id":6,"lat":40.5,"lon":-100.0,"tags":{"amenity":"bench"}},
              {"type":"relation","id":10,"tags":{"type":"enforcement","enforcement":"traffic_signals"},
               "members":[{"type":"node","ref":3,"role":"device"},{"type":"node","ref":4,"role":"from"}]},
              {"type":"relation","id":11,"tags":{"type":"enforcement","enforcement":"maxspeed","maxspeed":"50"},
               "members":[{"type":"node","ref":5,"role":"device"}]}
            ]}"""
        )
        val cams = KnownCameras.parseOverpass(json).associateBy { it.id }
        assertEquals(setOf(1L, 2L, 3L, 5L), cams.keys) // 4 is only where enforcement starts, 6 isn't a camera
        assertEquals(CameraKind.PLATE_READER, cams[1]!!.kind)
        assertEquals("Flock Safety plate reader", cams[1]!!.label)
        assertEquals("Speed camera · 45 mph limit", cams[2]!!.label)
        assertEquals(90f, cams[2]!!.views.single().bearing, 0.01f)
        assertEquals("Red-light camera", cams[3]!!.label)
        assertEquals("Speed camera · 50 km/h limit", cams[5]!!.label)
        assertEquals("OpenStreetMap", cams[5]!!.source)
        assertEquals(listOf(1L), cams.values.toList().wanted(speedCameras = false).map { it.id })
    }

    @Test
    fun `speed cameras describe their facing without plate talk`() {
        val speed = Camera(1, 40.0, -100.0, listOf(CameraView(180f)), null, null, CameraKind.SPEED)
        assertEquals("facing toward you - checks traffic coming your way", facingText(speed, 0f))
        assertEquals("at the lights ahead", facingText(speed.copy(kind = CameraKind.RED_LIGHT, views = emptyList()), 0f))
    }

    @Test
    fun `point ahead along a heading`() {
        val (lat, lon) = pointAhead(40.0, -100.0, 0f, 30_000.0)      // due north, 30 km
        assertEquals(40.2698, lat, 0.001); assertEquals(-100.0, lon, 0.0001)
        val (lat2, lon2) = pointAhead(40.0, -100.0, 270f, 30_000.0)  // due west
        assertEquals(40.0, lat2, 0.01); assertEquals(-100.352, lon2, 0.002)
    }

    @Test
    fun `camera download retries back off`() {
        assertEquals(listOf(2L, 5L, 10L, 30L), KnownCameras.RETRY_DELAYS_MS.map { it / 60_000 })
    }
}
