package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PhoneSyncTest {
    @Test
    fun `codes are long, readable and forgiving to type`() {
        val code = PhoneSync.newCode()
        assertTrue(code, Regex("^[a-z2-9]{4}-[a-z2-9]{4}-[a-z2-9]{4}$").matches(code))
        assertEquals("abcdefghjkmn", PhoneSync.normalize(" ABCD-efgh jkmn "))
    }

    @Test
    fun `only Tailscale and the phone itself may connect`() {
        assertTrue(PhoneSync.isTailnet(InetAddress.getByName("100.101.102.103")))
        assertTrue(PhoneSync.isTailnet(InetAddress.getByName("127.0.0.1")))
        assertFalse("home Wi-Fi", PhoneSync.isTailnet(InetAddress.getByName("192.168.12.218")))
        assertFalse("phone hotspot", PhoneSync.isTailnet(InetAddress.getByName("10.83.251.90")))
        assertFalse("just outside Tailscale's range", PhoneSync.isTailnet(InetAddress.getByName("100.128.0.1")))
    }
}
