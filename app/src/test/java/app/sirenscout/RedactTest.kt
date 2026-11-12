package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactTest {
    @Test
    fun `addresses keep only the maker part`() {
        assertEquals("BLE: 00:25:DF:••:••:•• near d0:fc:d0:••:••:••",
            Redact.text("BLE: 00:25:DF:12:34:56 near d0:fc:d0:12:34:56"))
        assertEquals("version 1.0.14, 18 networks", Redact.text("version 1.0.14, 18 networks"))
    }

    @Test
    fun `coordinates round to about a kilometre`() {
        assertEquals("40.12", Redact.coordinate(40.123456))
        assertEquals("-100.12", Redact.coordinate(-100.124512))
        assertEquals(null, Redact.coordinate(null))
    }
}
