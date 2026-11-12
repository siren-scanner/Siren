package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    @Test
    fun `reasons say what matched`() {
        val (axon, why) = Signatures.matchWithReason(Source.BLE, "00:25:DF:12:34:56", null)!!
        assertEquals("axon", axon.id)
        assertTrue(why, why.startsWith("Address starts with 00:25:DF"))

        val (flock, nameWhy) = Signatures.matchWithReason(Source.BLE, "C1:22:33:44:55:66", "Penguin-1234567890")!!
        assertEquals("flock", flock.id)
        assertTrue(nameWhy, nameWhy.contains("penguin"))

        val (_, idWhy) = Signatures.matchWithReason(Source.BLE, "C1:22:33:44:55:66", null, manufacturerIds = listOf(0x09C8))!!
        assertTrue(idWhy, idWhy.startsWith("Bluetooth manufacturer ID 0x09C8"))
    }

    @Test
    fun `inseego routers are fleet gear on wifi only`() {
        assertEquals("inseego", Signatures.match(Source.WIFI, "78:0C:71:AA:BB:CC", "anything")?.id)
        assertNull(Signatures.match(Source.BLE, "78:0C:71:AA:BB:CC", null))
    }

    @Test
    fun `watch rules match by kind`() {
        fun rule(kind: WatchRule.Kind, value: String) = WatchRule("1", kind, value, "Test", Category.POLICE)
        assertTrue(rule(WatchRule.Kind.ADDRESS, "aa:bb:cc:dd:ee:ff").matches("AA:BB:CC:DD:EE:FF", null))
        assertTrue(rule(WatchRule.Kind.PREFIX, "AA:BB:CC").matches("aa:bb:cc:01:02:03", null))
        assertFalse(rule(WatchRule.Kind.PREFIX, "AA:BB:CC").matches("AA:BB:CD:01:02:03", null))
        assertTrue(rule(WatchRule.Kind.NAME_EXACT, "Unit 12").matches(null, "unit 12"))
        assertFalse(rule(WatchRule.Kind.NAME_EXACT, "Unit 12").matches(null, "Unit 123"))
        assertTrue(rule(WatchRule.Kind.NAME_CONTAINS, "sheriff").matches(null, "COUNTY-SHERIFF-04"))
        assertFalse(rule(WatchRule.Kind.NAME_CONTAINS, "sheriff").matches("AA:BB:CC:DD:EE:FF", null))
    }

    @Test
    fun `hidden prefixes cover every address from one router`() {
        val rules = Hidden.Rules(prefixes = setOf("D0:FC:D0"))
        assertTrue(rules.hides(Source.WIFI, "d0:fc:d0:12:34:57", null))
        assertFalse(rules.hides(Source.WIFI, "d0:fc:d1:12:34:57", null))
    }
}
