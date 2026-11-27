package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TpmsTest {
    private val rnd = Random(7)
    /** A made-up sensor message: 32-bit ID, pressure, temperature, flags, check byte. */
    private fun message(id: Long, pressure: Int, temp: Int) =
        id.toString(2).padStart(32, '0') + pressure.toString(2).padStart(8, '0') + temp.toString(2).padStart(8, '0') + "0010110110100110"

    private fun withNoise(sig: Pair<DoubleArray, DoubleArray>, sigma: Double, pad: Int = 600): Pair<DoubleArray, DoubleArray> {
        val n = sig.first.size + 2 * pad
        val re = DoubleArray(n) { rnd.nextGaussianish() * sigma }
        val im = DoubleArray(n) { rnd.nextGaussianish() * sigma }
        for (j in sig.first.indices) { re[j + pad] += sig.first[j]; im[j + pad] += sig.second[j] }
        return re to im
    }

    private fun Random.nextGaussianish() = (0 until 6).sumOf { nextDouble(-1.0, 1.0) } / 1.4

    private fun expectId(frames: List<Tpms.Frame>, payload: String) {
        // The decoder keeps the start bit, so the fingerprint is "1" + the start of the message.
        val want = Tpms.Frame("1" + payload, 'F').id
        assertTrue("got ${frames.map { it.id + "/" + it.modulation }}, want $want", frames.any { it.id == want })
    }

    @Test
    fun `reads a frequency-shift sensor with noise and an off-tune carrier`() {
        for ((half, offset) in listOf(7 to 0.25, 13 to -0.3)) { // ~18 and ~10 kbit/s, carrier off by tens of kHz
            val payload = message(0x1A2B3C4DL, 0x64, 0x41)
            val sig = Tpms.fskBaseband(Tpms.manchesterEncode(payload), half, 0.6, offset, 20.0)
            val (re, im) = withNoise(sig, 2.0)
            expectId(Tpms.decodeBaseband(re, im), payload)
        }
    }

    @Test
    fun `reads an on-off sensor`() {
        val payload = message(0x0BADF00DL, 0x60, 0x3C)
        val sig = Tpms.ookBaseband(Tpms.manchesterEncode(payload), 9, 0.1, 25.0)
        val (re, im) = withNoise(sig, 2.0)
        expectId(Tpms.decodeBaseband(re, im), payload)
    }

    @Test
    fun `the same sensor gives the same fingerprint whatever its pressure and temperature`() {
        val ids = (0 until 3).map { k ->
            val payload = message(0x00C0FFEEL, 0x50 + k * 7, 0x30 + k)
            val (re, im) = withNoise(Tpms.fskBaseband(Tpms.manchesterEncode(payload), 7, 0.6, 0.1, 20.0), 2.0)
            Tpms.decodeBaseband(re, im).first { it.modulation == 'F' }.id
        }
        assertEquals(1, ids.toSet().size)
    }

    @Test
    fun `plain noise decodes to nothing`() {
        val (re, im) = withNoise(DoubleArray(0) to DoubleArray(0), 2.0, pad = 40_000)
        assertTrue(Tpms.decodeBaseband(re, im).isEmpty())
    }

    @Test
    fun `works from raw dongle samples tuned below 315 MHz`() {
        val payload = message(0x13572468L, 0x66, 0x40)
        // Build 1.024 MS/s I/Q: the sensor 250 kHz above the tuned frequency, FSK +-35 kHz, 19.2 kbit/s.
        val fs = RtlTcpMonitor.SAMPLE_RATE.toDouble()
        val halves = Tpms.manchesterEncode(payload)
        val spH = (fs / 38_400).toInt()
        val n = halves.length * spH + 40_000
        val iq = ByteArray(2 * n)
        var ph = 0.0
        for (j in 0 until n) {
            val k = j - 20_000
            val on = k in 0 until halves.length * spH
            val f = 250_000.0 + if (on && halves[k / spH] == '1') 35_000.0 else -35_000.0
            ph += 2 * Math.PI * f / fs
            val a = if (on) 40.0 else 0.0
            iq[2 * j] = (127.5 + a * kotlin.math.cos(ph) + rnd.nextGaussianish() * 3).toInt().coerceIn(0, 255).toByte()
            iq[2 * j + 1] = (127.5 + a * kotlin.math.sin(ph) + rnd.nextGaussianish() * 3).toInt().coerceIn(0, 255).toByte()
        }
        expectId(Tpms.decode(iq), payload)
    }

    @Test
    fun `a message in the overlap between chunks is reported once`() {
        val payload = message(0x2468ACE0L, 0x60, 0x40)
        val (re, im) = withNoise(Tpms.fskBaseband(Tpms.manchesterEncode(payload), 7, 0.6, 0.1, 20.0), 2.0, pad = 800)
        val n = re.size
        // The message sits entirely in the first part, which the previous call already covered.
        assertTrue(Tpms.decodeBaseband(re, im, skipEndingBefore = n - 400).isEmpty())
        assertTrue(Tpms.decodeBaseband(re, im, skipEndingBefore = 0).isNotEmpty())
    }
}
