package app.sirenscout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTogetherTest {
    private val code = "ABCD2345EF"

    @Test
    fun `messages round-trip through encoding and encryption`() {
        val (sid, key) = SearchTogether.keysFor(code)
        val target = SearchTogether.Target(Source.BLE, "6C:1A:44:E2:90:5B", "AirTag / Find My device")
        val msgs = listOf(
            SearchTogether.Message.Hello(42, "Dad", target),
            SearchTogether.Message.Position(42, 41.161616, -112.026312),
            SearchTogether.Message.Read(42, 41.161616, -112.026312, -73, 1_764_000_000_000L),
            SearchTogether.Message.Bye(42),
        )
        for (m in msgs) {
            val plain = SearchTogether.encode(m)
            val back = SearchTogether.decode(SearchTogether.open(SearchTogether.seal(plain, sid, key), sid, key)!!)!!
            assertEquals(m.from, back.from)
            assertArrayEquals(plain, SearchTogether.encode(back))
        }
        val read = SearchTogether.decode(SearchTogether.encode(msgs[2])) as SearchTogether.Message.Read
        assertEquals(41.161616, read.lat, 1e-6)
        assertEquals(-73, read.rssi)
        assertEquals(1_764_000_000_000L, read.time)
        val hello = SearchTogether.decode(SearchTogether.encode(msgs[0])) as SearchTogether.Message.Hello
        assertEquals(target, hello.target)
    }

    @Test
    fun `another code can't read or fake messages`() {
        val (sid, key) = SearchTogether.keysFor(code)
        val (otherSid, otherKey) = SearchTogether.keysFor("ZZZZ2345EF")
        val sealed = SearchTogether.seal(SearchTogether.encode(SearchTogether.Message.Bye(1)), sid, key)
        assertNull(SearchTogether.open(sealed, otherSid, otherKey))
        // Tampering is caught.
        val bent = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(SearchTogether.open(bent, sid, key))
    }

    @Test
    fun `a sealed reading fits a mesh text message`() {
        val (sid, key) = SearchTogether.keysFor(code)
        val sealed = SearchTogether.seal(SearchTogether.encode(SearchTogether.Message.Read(7, 30.0, -90.0, -80, 1L)), sid, key)
        val asText = java.util.Base64.getEncoder().encodeToString(sealed)
        assertTrue("${asText.length} chars", asText.length <= 80)
    }

    @Test
    fun `codes are forgiving to type and mesh updates slow down for big teams`() {
        assertEquals(code, SearchTogether.normalize("abcd-2345 ef"))
        assertEquals("ABCD-2345-EF", SearchTogether.pretty(code))
        assertEquals(SearchTogether.CODE_LENGTH, SearchTogether.newCode().length)
        assertEquals(20_000L, SearchTogether.slowEveryMs(2))
        assertEquals(20_000L, SearchTogether.slowEveryMs(3))
        assertEquals(60_000L, SearchTogether.slowEveryMs(9))
    }

    @Test
    fun `every message fits a MeshCore channel datagram, and the radio channel key is shared by the code`() {
        val (sid, key) = SearchTogether.keysFor(code)
        val longest = SearchTogether.Message.Hello(
            65535, "x".repeat(40),
            SearchTogether.Target(Source.WIFI, "y".repeat(60), "z".repeat(60)),
        )
        val sealed = SearchTogether.seal(SearchTogether.encode(longest), sid, key)
        assertTrue("${sealed.size} bytes", sealed.size <= MeshCoreLink.MAX_DATA)
        assertArrayEquals(MeshCoreLink.channelSecret(code), MeshCoreLink.channelSecret(code))
        assertEquals(16, MeshCoreLink.channelSecret(code).size)
        assertTrue(!MeshCoreLink.channelSecret(code).contentEquals(MeshCoreLink.channelSecret("ZZZZ2345EF")))
    }

    @Test
    fun `mesh radios are told apart from earbuds and cars by name`() {
        listOf("MeshCore-1A2B3C4D", "Meshtastic_1a2b", "T-Echo", "RAK4631", "Heltec V3", "T1000-E").forEach {
            assertTrue(it, MeshRadios.looksLikeRadio(it))
        }
        listOf("Pixel Buds Pro", "Tesla Model 3", "Galaxy Watch", "JBL Flip 6", null).forEach {
            assertTrue("$it", !MeshRadios.looksLikeRadio(it))
        }
    }

    @Test
    fun `radios are sorted into their own lists by name`() {
        assertEquals(MeshRadios.Kind.MESHCORE, MeshRadios.kind("MeshCore-0F0E0D0C"))
        assertEquals(MeshRadios.Kind.MESHTASTIC, MeshRadios.kind("Meshtastic_ab12"))
        assertEquals(MeshRadios.Kind.UNKNOWN, MeshRadios.kind("T-Echo"))
    }

    @Test
    fun `the QR code a camera reads back is a join link with the code`() {
        val link = SearchTogether.joinLink(code)
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(link, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0)
        val w = matrix.width; val h = matrix.height
        val pixels = IntArray(w * h) { if (matrix[it % w, it / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        val read = com.google.zxing.qrcode.QRCodeReader().decode(
            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(w, h, pixels))),
            mapOf(com.google.zxing.DecodeHintType.PURE_BARCODE to true),
        ).text
        assertEquals(link, read)
        assertEquals(code, SearchTogether.codeFromLink(read))
        assertNull(SearchTogether.codeFromLink("https://example.com/join/ABCD2345EF"))
        assertNull(SearchTogether.codeFromLink("siren://join/TOOSHORT"))
    }
}
