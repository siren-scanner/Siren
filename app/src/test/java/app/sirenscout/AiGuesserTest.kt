package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiGuesserTest {
    private fun d(source: Source, id: String, name: String?, ad: String? = null, guess: String? = null, sig: Signature? = null, rssi: Int = -70) =
        Detection(source, id, name, rssi, sig, 0, 0, 1, 0, rssi, null, null, guess, adSummary = ad)

    @Test
    fun `reads well-formed answers and ignores junk`() {
        val text = """
            Sure! Here you go:
            1 | consumer | high | Apple AirPods | Apple type 0x07 is AirPods pairing
            2. | Police | medium | Patrol car laptop | name MDT-14 matches mobile data terminals
            3 | made-up | low | Something | no idea
            9 | consumer | high | Out of range | should be dropped
            garbage line
        """.trimIndent()
        val parsed = AiGuesser.parse(text, count = 3)
        assertEquals(setOf(1, 2, 3), parsed.keys)
        assertEquals("consumer", parsed.getValue(1).category)
        assertEquals("police", parsed.getValue(2).category)
        assertEquals("medium", parsed.getValue(2).confidence)
        assertEquals("Patrol car laptop", parsed.getValue(2).label)
        assertEquals("unknown", parsed.getValue(3).category) // unrecognised category is downgraded
    }

    @Test
    fun `lookalike devices share a key, different ones don't`() {
        val a = d(Source.BLE, "11:22:33:44:55:66", "Unit 14", ad = "maker 0x004C (Apple) type 0x10")
        val b = d(Source.BLE, "AA:BB:CC:DD:EE:FF", "Unit 15", ad = "maker 0x004C (Apple) type 0x10")
        val c = d(Source.BLE, "AA:BB:CC:DD:EE:00", "Unit 15", ad = "maker 0x0075 (Samsung)")
        assertEquals(AiGuesser.key(a), AiGuesser.key(b)) // address and unit number don't matter
        assertNotEquals(AiGuesser.key(a), AiGuesser.key(c))
    }

    @Test
    fun `asks only about unidentified devices with something to go on`() {
        val items = listOf(
            d(Source.BLE, "1", "Mystery-1", rssi = -50),
            d(Source.BLE, "2", "Mystery-2", rssi = -60), // same kind as #1 once digits are wildcarded
            d(Source.BLE, "3", null),                    // nothing to go on
            d(Source.BLE, "4", "Axon", sig = Signatures.byId("axon")), // already identified
            d(Source.WIFI, "5", "HomeNet", guess = "Arcadyan · home router"),
        )
        val picked = AiGuesser.candidates(items, emptyMap(), Hidden.Rules())
        assertEquals(listOf("1", "5"), picked.map { it.id })
        val known = mapOf(AiGuesser.key(items[0]) to AiGuess("x", "consumer", "high", "y"))
        assertEquals(listOf("5"), AiGuesser.candidates(items, known, Hidden.Rules()).map { it.id })
    }

    @Test
    fun `prompt numbers devices and asks for the line format`() {
        val prompt = AiGuesser.buildPrompt(listOf(
            d(Source.BLE, "1", "MDT-14", ad = "maker 0x0006 (Microsoft) type 0x01"),
            d(Source.WIFI, "2", "PD-UNIT-7", guess = "Sierra Wireless"),
        ))
        assertTrue(prompt.contains("1. Bluetooth LE | name: \"MDT-14\""))
        assertTrue(prompt.contains("2. Wi-Fi network | name: \"PD-UNIT-7\" | maker/type from registries: Sierra Wireless"))
        assertTrue(prompt.contains("number | category | confidence"))
        assertFalse(prompt.contains("1:2")) // no addresses or locations are included
    }
}
