package app.sirenscout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksTest {
    private val t = 1_000_000_000_000L
    private fun d(first: Long, last: Long) = Detection(Source.BLE, "4A:00:00:00:00:01", null, -60, null, first, last, 1, 0, -60, null, null)

    @Test
    fun `around a mark means heard within a minute of it`() {
        assertTrue(Marks.around(d(t - 600_000, t + 5_000), t))   // there through the mark
        assertTrue(Marks.around(d(t + 30_000, t + 90_000), t))   // showed up just after
        assertTrue(Marks.around(d(t - 120_000, t - 45_000), t))  // left just before
        assertFalse(Marks.around(d(t - 600_000, t - 120_000), t)) // gone well before
        assertFalse(Marks.around(d(t + 120_000, t + 600_000), t)) // came well after
    }
}
