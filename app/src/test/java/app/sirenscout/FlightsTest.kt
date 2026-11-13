package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FlightsTest {
    @Test
    fun `flight numbers, callsigns and tail numbers become feed lookups`() {
        assertEquals("callsign" to "AAL100", Flights.lookupPath("AA100"))
        assertEquals("callsign" to "AAL100", Flights.lookupPath("aa 0100"))
        assertEquals("callsign" to "DAL2895", Flights.lookupPath("DL2895"))
        assertEquals("callsign" to "DAL2895", Flights.lookupPath("DAL2895"))
        assertEquals("callsign" to "JBU5", Flights.lookupPath("B6 005"))
        assertEquals("reg" to "N12345", Flights.lookupPath("N12345"))
        assertEquals("hex" to "a0a30e", Flights.lookupPath("A0A30E"))
        assertEquals("American Airlines", Flights.airline("AAL100"))
    }

    private fun live(alt: Int?, rate: Int, ground: Boolean = false, lat: Double = 35.0, speed: Double = 400.0) =
        LiveFlight("a0a30e", "AAL100", "N123AA", "B738", lat, -97.0, alt, ground, speed, 90.0, rate, null, "1234", null, 1.0)

    @Test
    fun `status updates follow the flight through its trip, once each`() {
        val dfw = Airport("DFW", "KDFW", "Dallas Fort Worth", "Dallas", "US", 32.8968, -97.0380)
        val jfk = Airport("JFK", "KJFK", "John F. Kennedy", "New York", "US", 40.6394, -73.7793)
        var t = TrackedFlight("AA100", "DFW", "JFK")
        fun step(f: LiveFlight?, now: Long): List<String> {
            val (u, next) = Flights.updates(t, f, dfw, jfk, null, null, now)
            t = next
            return u.map { it.key.substringAfter(':') }
        }
        assertEquals(listOf("found"), step(live(null, 0, ground = true, speed = 10.0), 0))
        assertEquals(listOf("takeoff"), step(live(3_000, 2_500, speed = 220.0), 60_000))
        assertEquals(listOf("cruise"), step(live(35_000, 0), 120_000))
        assertTrue(step(live(35_000, 0), 180_000).isEmpty())
        assertEquals(listOf("descent"), step(live(30_000, -1_500), 240_000))
        assertEquals(listOf("approach"), step(live(9_000, -1_500, lat = 40.3, speed = 250.0).copy(lon = -74.0), 300_000))
        assertEquals(listOf("landed"), step(live(null, 0, ground = true, lat = 40.64, speed = 20.0), 360_000))
    }

    @Test
    fun `emergencies and lost signal are reported`() {
        var t = TrackedFlight("AA100", phase = "cruising", lastSeenAt = 0)
        val (u1, t1) = Flights.updates(t, live(35_000, 0).copy(emergency = "general", squawk = "7700"), null, null, null, null, 1_000)
        assertTrue(u1.any { it.key.endsWith(":emergency-general") && it.title.contains("EMERGENCY") })
        t = t1
        val (u2, _) = Flights.updates(t, null, null, null, null, null, 1_000 + 11 * 60_000L)
        assertTrue(u2.any { it.title.contains("signal lost") })
    }

    @Test
    fun `airports load by IATA and ICAO code`() {
        val all = Airports.parse(File("src/main/assets/airports.txt").readText())
        assertEquals("KJFK", all.getValue("JFK").icao)
        assertEquals("JFK", all.getValue("KJFK").iata)
        assertTrue(all.size > 6_000)
    }

    @Test
    fun `flights with dates are only followed on their days, afresh each day`() {
        val t = TrackedFlight("AA100", startDay = "2026-11-15", endDay = "2026-11-17")
        assertTrue(!Flights.isActive(t, "2026-11-14"))
        assertTrue(Flights.isActive(t, "2026-11-15"))
        assertTrue(Flights.isActive(t, "2026-11-17"))
        assertTrue(!Flights.isActive(t, "2026-11-18"))
        assertTrue(Flights.isActive(TrackedFlight("AA100"), "2030-01-01"))
        assertEquals("Sun Nov 15 – Tue Nov 17", t.daysText)

        // The same status is reported again on the next day.
        val zone = java.time.ZoneId.systemDefault()
        val day1 = java.time.LocalDateTime.of(2026, 11, 15, 12, 0).atZone(zone).toInstant().toEpochMilli()
        val day2 = day1 + 24 * 3_600_000L
        val (u1, t1) = Flights.updates(t, live(null, 0, ground = true, speed = 5.0), null, null, null, null, day1)
        val (u2, _) = Flights.updates(t1, live(null, 0, ground = true, speed = 5.0), null, null, null, null, day2)
        assertEquals(listOf("2026-11-15:found"), u1.map { it.key })
        assertEquals(listOf("2026-11-16:found"), u2.map { it.key })
    }

    @Test
    fun `a late flight still counts as the day it was expected`() {
        val zone = java.time.ZoneId.systemDefault()
        val halfPastMidnight = java.time.LocalDateTime.of(2026, 11, 16, 0, 30).atZone(zone).toInstant().toEpochMilli()
        assertEquals("2026-11-15", Flights.tripDay(halfPastMidnight))
    }
}
