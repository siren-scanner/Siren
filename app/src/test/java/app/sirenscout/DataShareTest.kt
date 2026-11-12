package app.sirenscout

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class DataShareTest {
    @Test
    fun `everything survives the trip to another phone`() {
        val c = DataShare.Contents(
            ai = mapOf("BLE||maker 0x004C" to AiGuess("Car key fob", "consumer", "high", "Strong steady beacon")),
            hidden = Hidden.Rules(mapOf("WIFI|AA:BB:CC:DD:EE:FF" to "Home"), setOf("Galaxy Watch"), setOf("AA:BB:CC")),
            rules = listOf(WatchRule("1", WatchRule.Kind.NAME_CONTAINS, "Unit", "County unit", Category.POLICE)),
            history = listOf(
                Detection(Source.BLE, "AA:BB:CC:00:11:22", "Axon", -60, null, 1000, 2000, 3, 0, -55, 40.0, -100.0, "Axon · body camera"),
            ),
        )
        val back = DataShare.fromJson(JSONObject(DataShare.toJson(c).toString()))
        assertEquals(c.ai, back.ai)
        assertEquals(c.hidden, back.hidden)
        assertEquals(c.rules, back.rules)
        assertEquals(1, back.history.size)
        assertEquals("Axon", back.history[0].name)
        assertEquals(40.0, back.history[0].lat!!, 0.0)
    }

    @Test
    fun `refuses files that aren't shared data`() {
        assertThrows(IOException::class.java) { DataShare.fromJson(JSONObject().put("app", "Siren").put("format", 1)) }
        assertThrows(IOException::class.java) { DataShare.fromJson(JSONObject().put("app", "Siren").put("kind", "share").put("format", 99)) }
    }
}
