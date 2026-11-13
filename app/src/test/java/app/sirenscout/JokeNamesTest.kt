package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JokeNamesTest {
    @Test
    fun `prank agency names on home routers are labelled as jokes`() {
        assertEquals("Joke network name · Arcadyan home router", JokeNames.label("FBI Surveillance Van 2", "Arcadyan"))
        assertEquals("Joke network name · Netgear home router", JokeNames.label("NSA Listening Post", "Netgear · home router"))
        assertEquals("Joke network name · Commscope home router", JokeNames.label("Police Van 3", "Commscope"))
    }

    @Test
    fun `ordinary names, fleet gear and unknown makers are left alone`() {
        assertNull(JokeNames.label("Smith Family WiFi", "Arcadyan"))
        assertNull("could be a real agency's router", JokeNames.label("FBI Surveillance Van", "Cradlepoint"))
        assertNull(JokeNames.label("Police Unit 14", "Ford car hotspot"))
        assertNull(JokeNames.label("FBI Surveillance Van", null))
        assertNull("not a whole word", JokeNames.label("Copper Kettle Cafe", "Arcadyan"))
    }

    @Test
    fun `joke networks count as everyday and the AI isn't asked`() {
        val d = Detection(Source.WIFI, "AA:BB:CC:00:11:22", "FBI Surveillance Van 2", -85, null, 0, 0, 1, 0, -85, null, null,
            JokeNames.label("FBI Surveillance Van 2", "Arcadyan"))
        assertTrue(d.isJokeName)
        assertTrue(Everyday.isEveryday(d))
        assertTrue(AiGuesser.candidates(listOf(d), emptyMap(), Hidden.Rules()).isEmpty())
        assertTrue(AiGuesser.buildPrompt(emptyList()).contains("FBI Surveillance Van"))
    }
}
