package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvilTwinTest {
    // Made-up networks and addresses.
    private val vendors = mapOf("10:00:00" to "Netgear", "20:00:00" to "Espressif", "30:00:00" to "Cisco")
    private fun vendor(b: String) = vendors[b.take(8).uppercase()]
    private fun ap(b: String, ssid: String?, secure: Boolean) = EvilTwin.Ap(b, ssid, secure)

    @Test
    fun `open copy of a password-protected network is flagged`() {
        val found = EvilTwin.find(listOf(ap("10:00:00:00:00:01", "HomeNet", true), ap("20:00:00:00:00:09", "HomeNet", false)), null, ::vendor)
        assertEquals(setOf("20:00:00:00:00:09"), found.keys)
        assertTrue(found.values.first().contains("Open"))
    }

    @Test
    fun `public hotspots from many access points are left alone`() {
        val aps = listOf(ap("10:00:00:00:00:01", "CoffeeGuest", false), ap("30:00:00:00:00:02", "CoffeeGuest", false), ap("20:00:00:00:00:03", "CoffeeGuest", false))
        assertEquals(emptyMap<String, String>(), EvilTwin.find(aps, null, ::vendor))
    }

    @Test
    fun `a gadget copying the network you're on is flagged, its own mesh nodes and extenders are not`() {
        val mine = ap("10:00:00:aa:bb:01", "HomeNet", true)
        val sameRouter5g = ap("12:00:00:aa:bb:02", "HomeNet", true) // same box, made-up bit set
        val meshNode = ap("12:00:00:cc:dd:07", "HomeNet", true) // another node of the same make, made-up address
        val extender = ap("30:00:00:00:00:09", "HomeNet", true) // another brand's extender: fine
        val phoneHotspot = ap("da:11:22:33:44:55", "HomeNet", true)
        val found = EvilTwin.find(listOf(mine, sameRouter5g, meshNode, extender, phoneHotspot), "HomeNet" to "10:00:00:AA:BB:01", ::vendor)
        assertEquals(setOf("da:11:22:33:44:55"), found.keys)
    }

    @Test
    fun `neighbours sharing a factory-default name aren't flagged`() {
        val aps = listOf(ap("10:00:00:00:00:01", "linksys", true), ap("30:00:00:00:00:02", "linksys", false), ap("10:00:00:00:00:03", "NETGEAR42", true), ap("10:00:00:00:00:04", "NETGEAR42", false))
        assertEquals(emptyMap<String, String>(), EvilTwin.find(aps, null, ::vendor))
        // ...unless it's the network you're on.
        assertEquals(setOf("30:00:00:00:00:02"), EvilTwin.find(aps, "linksys" to "10:00:00:00:00:01", ::vendor).keys)
    }

    @Test
    fun `attack hardware by maker, and security parsing`() {
        assertTrue(EvilTwin.find(listOf(ap("00:13:37:12:34:56", "Free WiFi", false)), null, ::vendor).isNotEmpty())
        assertTrue(EvilTwin.secure("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertTrue(!EvilTwin.secure("[ESS]"))
        assertTrue(!EvilTwin.secure("[OWE_TRANSITION][ESS]")) // Enhanced Open counts as open
        assertTrue(!EvilTwin.secure("[RSN-OWE-CCMP][ESS]"))
    }

    @Test
    fun `a fake is hidden by its address, not by the real network's name`() {
        val fake = Detection(Source.WIFI, "da:11:22:33:44:55", "HomeNet", -50, EvilTwin.SIGNATURE, 0, 0, 1, 0, -50, null, null)
        assertTrue(!Hidden.Rules(names = setOf("HomeNet")).hides(fake))
        assertTrue(Hidden.Rules(devices = mapOf("WIFI|da:11:22:33:44:55" to "")).hides(fake))
        assertTrue(Hidden.Rules(names = setOf("HomeNet")).hides(fake.copy(signature = null)))
    }
}
