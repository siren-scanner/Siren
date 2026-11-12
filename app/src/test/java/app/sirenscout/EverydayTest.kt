package app.sirenscout

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EverydayTest {
    private fun d(guess: String?, signature: Signature? = null) =
        Detection(Source.WIFI, "AA:BB:CC:DD:EE:FF", null, -70, signature, 0, 0, 1, 0, -70, null, null, guess)

    @Test
    fun `ordinary gear is everyday`() {
        listOf(
            "Commscope", "Arcadyan · home router", "Apple · AirPods / Beats", "Apple · iPhone / iPad / Mac / Watch",
            "Bose", "Samsung · TV", "Google · beacon", "Apple · AirTag / Find My", "Garmin · watch",
            "Microsoft · Windows PC", "home router", "printer",
        ).forEach { assertTrue(it, Everyday.isEveryday(d(it))) }
    }

    @Test
    fun `matches, unknowns and possible vehicles stay visible`() {
        assertFalse(Everyday.isEveryday(d(null)))
        assertFalse(Everyday.isEveryday(d("Ford car hotspot")))
        assertFalse(Everyday.isEveryday(d("Toyota · car hotspot")))
        assertFalse(Everyday.isEveryday(d("Cisco Systems")))
        assertFalse(Everyday.isEveryday(d("Commscope · home router", Signatures.byId("cradlepoint"))))
        assertFalse(Everyday.isEveryday(d("Motorola Solutions")))
    }
}
