package app.sirenscout

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PoliceDataTest {
    private val registry = File("src/main/assets/police_feeds.json").readText()
    private val feeds = PoliceData.parseRegistry(registry)
    private val nola = feeds.first { it.id == "new-orleans" }

    @Test
    fun `the bundled feed list parses`() {
        assertEquals(setOf("new-orleans", "dallas-live"), feeds.map { it.id }.toSet())
        assertTrue(!nola.live && nola.pointField == "location" && nola.datasets.size == 2)
        val dallas = feeds.first { it.id == "dallas-live" }
        assertTrue(dallas.live && dallas.addressFields == listOf("block", "location") && dallas.city == "Dallas")
        // Unknown kinds of feed are skipped, not fatal.
        assertEquals(0, PoliceData.parseRegistry("""{"feeds":[{"id":"x","type":"arcgis"}]}""").size)
    }

    @Test
    fun `stops group into block-sized hotspots, quiet places dropped`() {
        // 8 stops on one corner (made-up spot), 2 a mile away.
        val corner = List(8) { Triple(29.9500 + it * 0.00001, -90.0700, 8) }
        val far = List(2) { Triple(29.9650, -90.0700, 14) }
        val spots = PoliceData.aggregate(nola, corner + far)
        assertEquals(1, spots.size)
        assertEquals(8, spots[0].count)
        assertEquals(29.95, spots[0].lat, 0.001)
        assertEquals("8-10 AM", spots[0].peak)
    }

    @Test
    fun `peak hours only when there is one`() {
        assertEquals("7-9 AM", PoliceData.peakHours(listOf(7, 7, 8, 8, 7, 15, 20, 8)))
        assertEquals("11 AM-1 PM", PoliceData.peakHours(listOf(11, 11, 12, 12, 11, 3)))
        assertEquals("11 PM-1 AM", PoliceData.peakHours(listOf(23, 23, 0, 0, 23, 12)))
        assertNull(PoliceData.peakHours(List(24) { it }))   // spread over the day
        assertNull(PoliceData.peakHours(listOf(8, 8)))     // too few
    }

    @Test
    fun `live call text`() {
        assertEquals("Minor Accident", PoliceData.cleanType("07 - Minor Accident"))
        assertEquals("PD Requested by Fire", PoliceData.cleanType("**PD Requested by Fire"))
        assertEquals("Robbery", PoliceData.cleanType("20 - Robbery"))
        val row = JSONObject("""{"block":"5600","location":"Military  Pkwy"}""")
        assertEquals("5600 Military Pkwy", PoliceData.liveAddress(row, listOf("block", "location")))
        assertNull(PoliceData.liveAddress(JSONObject("{}"), listOf("block", "location")))
    }

    @Test
    fun `hotspots ahead while driving`() {
        val spot = PoliceData.Hotspot("t", 30.001, -90.0, 20, null, 90, "traffic stops")   // ~110 m north
        val quiet = spot.copy(lat = 30.0015, count = 6)
        val behind = spot.copy(lat = 29.999)
        val ahead = PoliceData.hotspotsAhead(listOf(spot, quiet, behind), 30.0, -90.0, heading = 0f, radiusM = 300.0, minCount = 10)
        assertEquals(listOf(spot), ahead.map { it.first })
    }

    @Test
    fun `real feeds download (set SIREN_NET_TESTS=1)`() {
        if (System.getenv("SIREN_NET_TESTS") == null) return
        val stops = PoliceData.downloadStops(nola)
        val spots = PoliceData.aggregate(nola, stops)
        println("New Orleans: ${stops.size} stops -> ${spots.size} hotspots; busiest ${spots.take(3).map { "${it.count} (${it.peak})" }}")
        assertTrue(stops.size > 500)
        val rows = PoliceData.downloadLiveRows(feeds.first { it.id == "dallas-live" })
        println("Dallas: ${rows.length()} active calls, e.g. ${(0 until minOf(3, rows.length())).map { rows.getJSONObject(it).optString("nature_of_call") }}")
        assertTrue(rows.length() > 0)
    }
}
