package app.sirenscout

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AiCheckTest {
    private fun g(category: String, label: String = "x") = AiGuess(label, category, "high", "because")

    @Test
    fun `joke names on home routers are not police`() {
        val r = AiCheck.check(Source.WIFI, "ATF Van 3", "Tp-Link,Ltd · home router", g("police", "Police vehicle network"))
        assertEquals("consumer", r.category)
        assertEquals("Home Wi-Fi network", r.label)
    }

    @Test
    fun `known look-alikes get their real identity`() {
        assertEquals("Tank level monitor (propane, fuel)", AiCheck.check(Source.BLE, null, "maker 0x03B1 (Otodata Wireless Network)", g("police")).label)
        assertEquals("Commercial truck tracker (ELD)", AiCheck.check(Source.WIFI, "Example Trucking", "Samsara Networks", g("fleet")).label)
        assertEquals("Car's built-in Wi-Fi hotspot", AiCheck.check(Source.WIFI, "HOTSPOTA1B2", "Flextronics", g("fleet")).label)
        assertEquals("Home security camera or alarm", AiCheck.check(Source.WIFI, "Ring-1a2b3c", "security camera", g("surveillance")).label)
        assertEquals("iBeacon (shop or venue beacon)", AiCheck.check(Source.BLE, null, "maker 0x004C (Apple) type 0x02", g("tracker")).label)
    }

    @Test
    fun `real public-safety makers keep the AI's answer`() {
        val r = AiCheck.check(Source.WIFI, "Medic 12", "AirLink Communications", g("fire_ems", "Ambulance router"))
        assertEquals("fire_ems", r.category)
        val axon = AiCheck.check(Source.BLE, null, "maker 0x0A27 (Axon Enterprise)", g("police", "Body camera"))
        assertEquals("police", axon.category)
    }

    @Test
    fun `ordinary answers pass through`() {
        val ok = g("consumer", "Home Wi-Fi router")
        assertEquals(ok, AiCheck.check(Source.WIFI, "Smith Family", "Netgear · home router", ok))
    }

    @Test
    fun `review stored guesses (set SIREN_AI_ROWS to an exported list)`() {
        val path = System.getenv("SIREN_AI_ROWS") ?: return
        val rows = JSONArray(File(path).readText())
        val before = HashMap<String, Int>(); val after = HashMap<String, Int>(); var changed = 0
        val official = setOf("police", "fire_ems", "fleet", "surveillance")
        val kept = ArrayList<String>()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val src = Source.valueOf(o.getString("src"))
            val ai = AiGuess(o.getString("label"), o.getString("category"), o.getString("confidence"), o.getString("why"))
            val r = AiCheck.check(src, o.getString("name"), o.getString("what"), ai)
            before.merge(ai.category, 1, Int::plus); after.merge(r.category, 1, Int::plus)
            if (r != ai) changed++
            if (r.category in official) kept += "${r.category}: ${r.label} <- ${o.getString("what").take(40)}"
        }
        println("changed $changed of ${rows.length()}"); println("before $before"); println("after  $after")
        kept.forEach { println("  still official: $it") }
    }
}
