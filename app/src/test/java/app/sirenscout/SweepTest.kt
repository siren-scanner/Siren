package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.random.Random

class SweepTest {
    /** A device toward [at] degrees: up to 15 dB stronger facing it, with a few dB of noise. */
    private fun sweep(at: Float, step: Float = 3f, noise: Double = 3.0, seed: Int = 1): SweepModel {
        val m = SweepModel(); val r = Random(seed)
        var h = 0f
        while (h < 360f) {
            val facing = cos(Math.toRadians((h - at).toDouble()))
            m.add(h, (-75 + 7.5 * (facing + 1) + r.nextDouble(-noise, noise)).toInt())
            h += step
        }
        return m
    }

    private fun angleDiff(a: Float, b: Float) = abs(((a - b + 540f) % 360f) - 180f)

    @Test
    fun `finds the direction within about 20 degrees, wherever it is`() {
        for ((i, at) in listOf(0f, 45f, 135f, 200f, 300f, 355f).withIndex()) {
            val r = sweep(at, seed = i).result()
            assertNotNull("no result for $at", r)
            assertEquals("for $at got ${r!!.bearingDeg}", 0f, angleDiff(r.bearingDeg, at), 20f)
        }
    }

    @Test
    fun `no answer until most of the circle is covered, or when it's the same all round`() {
        val half = SweepModel()
        var h = 0f
        while (h < 160f) { half.add(h, -60); h += 3f }
        assertNull(half.result())
        val flat = SweepModel()
        h = 0f
        while (h < 360f) { flat.add(h, -70); h += 3f }
        assertNull(flat.result())
    }
}
