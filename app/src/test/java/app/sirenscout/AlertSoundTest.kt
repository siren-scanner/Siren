package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AlertSoundTest {
    @Test
    fun `tones have the right length, pauses are silent, edges fade in`() {
        val pcm = AlertSound.synth(listOf(1000.0 to 100, 0.0 to 50, 1000.0 to 100))
        assertEquals(22_050 * 250 / 1000, pcm.size)
        val pause = pcm.copyOfRange(2205, 2205 + 1102)
        assertTrue(pause.all { it.toInt() == 0 })
        assertTrue("starts quietly, no click", abs(pcm[1].toInt()) < 500)
        assertTrue("reaches full volume", pcm.maxOf { it.toInt() } > 20_000)
    }
}
