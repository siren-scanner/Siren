package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaKeyTest {
    private fun ble(name: String?, guess: String? = TeslaKey.guess(name)) =
        Detection(Source.BLE, "AA:BB:CC:00:11:22", name, -48, null, 0, 0, 1, 0, -48, null, null, guess)

    @Test
    fun `recognises Tesla phone-key names and nothing else`() {
        assertTrue(TeslaKey.isTesla("S5584429372bd69baC"))
        assertTrue("other side of the car", TeslaKey.isTesla("S5584429372bd69baD"))
        assertFalse(TeslaKey.isTesla("S5584429372bd69ba"))
        assertFalse(TeslaKey.isTesla("S5584429372BD69BAC"))
        assertFalse(TeslaKey.isTesla("Galaxy Buds"))
        assertNull(TeslaKey.guess(null))
    }

    @Test
    fun `shown as Tesla car key without a guess mark`() {
        val d = ble("S5584429372bd69baC")
        assertEquals(TeslaKey.LABEL, d.title)
        assertNull(d.guessLine)
        assertEquals("Galaxy Buds", ble("Galaxy Buds", "Samsung · earbuds").title)
    }
}
