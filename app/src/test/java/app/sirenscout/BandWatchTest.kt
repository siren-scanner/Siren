package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

class BandWatchTest {
    private val rate = 1_024_000
    private val analyzer = BandAnalyzer(rate)

    /** 8-bit unsigned IQ like an RTL-SDR: a tone at [offsetHz] from center, plus noise. */
    private fun capture(offsetHz: Double?, amplitude: Double = 40.0, noise: Double = 3.0, samples: Int = 65_536, seed: Int = 1): ByteArray {
        val rnd = Random(seed)
        val out = ByteArray(samples * 2)
        for (n in 0 until samples) {
            var i = rnd.nextDouble(-noise, noise)
            var q = rnd.nextDouble(-noise, noise)
            if (offsetHz != null) {
                val ph = 2 * PI * offsetHz * n / rate
                i += amplitude * cos(ph)
                q += amplitude * sin(ph)
            }
            out[2 * n] = (127.5 + i).roundToInt().coerceIn(0, 255).toByte()
            out[2 * n + 1] = (127.5 + q).roundToInt().coerceIn(0, 255).toByte()
        }
        return out
    }

    @Test
    fun `steps cover the whole band`() {
        val steps = analyzer.steps(Bands.PS_700)
        val half = analyzer.usableSpanHz / 2
        assertTrue(steps.first() - half <= Bands.PS_700.loHz)
        assertTrue(steps.last() + half >= Bands.PS_700.hiHz)
        assertEquals(8, steps.size) // 6 MHz in ~0.8 MHz steps
    }

    @Test
    fun `a tone shows up in the right channel`() {
        val center = 801_000_000L
        val powers = analyzer.channelPowers(capture(offsetHz = 200_000.0), center, Bands.PS_700)
        val loudest = powers.maxByOrNull { it.value }!!.key
        // 801.2 MHz falls in the 12.5 kHz channel centred at 801.20625 MHz.
        assertEquals(801_206_250L, loudest)
        val median = powers.values.sorted()[powers.size / 2]
        assertTrue("tone should stand well above the noise", powers.getValue(loudest) - median > 20)
    }

    @Test
    fun `channels outside the band or near DC are skipped`() {
        val powers = analyzer.channelPowers(capture(null), 799_200_000L, Bands.PS_700)
        assertTrue(powers.keys.all { it >= Bands.PS_700.loHz && it < Bands.PS_700.hiHz })
        assertTrue(powers.keys.none { kotlin.math.abs(it - 799_200_000L) < 6_250 })
    }

    @Test
    fun `floors ignore quiet sweeps and catch a new transmitter`() {
        val center = 801_000_000L
        val floors = ChannelFloors(thresholdDb = 15f)
        repeat(4) { assertNull(floors.update(analyzer.channelPowers(capture(null, seed = it), center, Bands.PS_700))) }
        val hit = floors.update(analyzer.channelPowers(capture(offsetHz = -150_000.0, seed = 9), center, Bands.PS_700))
        assertNotNull(hit)
        assertEquals(800_856_250L, hit!!.channelHz)
        assertTrue(hit.aboveDb >= 15)
    }

    @Test
    fun `an always-on carrier fades into the floor`() {
        val center = 801_000_000L
        val floors = ChannelFloors(thresholdDb = 15f)
        // A carrier present from the start never stands out against its own floor.
        repeat(10) { assertNull(floors.update(analyzer.channelPowers(capture(offsetHz = 100_000.0, seed = it), center, Bands.PS_700))) }
    }
}
