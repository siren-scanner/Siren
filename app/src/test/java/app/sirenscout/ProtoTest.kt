package app.sirenscout

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtoTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `encodes like protobuf`() {
        // ToRadio { want_config_id: 42 } -> field 3 varint
        assertArrayEquals(hex("182a"), ProtoWriter().uint(3, 42).toByteArray())
        // varint over 127: 300 -> ac 02; PRIVATE_APP portnum 256 -> 80 02
        assertArrayEquals(hex("08ac02"), ProtoWriter().uint(1, 300).toByteArray())
        assertArrayEquals(hex("088002"), ProtoWriter().uint(1, 256).toByteArray())
        // fixed32 broadcast address 0xFFFFFFFF in field 2 -> tag 0x15, little-endian
        assertArrayEquals(hex("15ffffffff"), ProtoWriter().fixed32(2, -1).toByteArray())
        // nested message: Data { portnum: 256, payload: [01 02] } inside field 4
        val data = ProtoWriter().uint(1, 256).bytes(2, byteArrayOf(1, 2))
        assertArrayEquals(hex("2207" + "088002" + "12020102"), ProtoWriter().message(4, data).toByteArray())
    }

    @Test
    fun `reads back what it writes, including a received Meshtastic packet`() {
        val payload = byteArrayOf(9, 8, 7)
        val data = ProtoWriter().uint(MeshtasticLink.DATA_PORTNUM, MeshtasticLink.PRIVATE_APP).bytes(MeshtasticLink.DATA_PAYLOAD, payload)
        val pkt = ProtoWriter().fixed32(1, 0x12345678).fixed32(MeshtasticLink.PKT_TO, -1).uint(MeshtasticLink.PKT_CHANNEL, 6)
            .message(MeshtasticLink.PKT_DECODED, data)
        val fromRadio = ProtoWriter().uint(1, 99).message(MeshtasticLink.FROM_PACKET, pkt).toByteArray()

        val p = ProtoReader(ProtoReader(fromRadio).first(MeshtasticLink.FROM_PACKET)!!.bytes!!)
        assertEquals(0x12345678L, p.first(1)!!.long)
        assertEquals(0xFFFFFFFFL, p.first(MeshtasticLink.PKT_TO)!!.long)
        assertEquals(6L, p.first(MeshtasticLink.PKT_CHANNEL)!!.long)
        val d = ProtoReader(p.first(MeshtasticLink.PKT_DECODED)!!.bytes!!)
        assertEquals(256L, d.first(MeshtasticLink.DATA_PORTNUM)!!.long)
        assertArrayEquals(payload, d.first(MeshtasticLink.DATA_PAYLOAD)!!.bytes)
    }

    @Test
    fun `search messages fit a Meshtastic packet and the channel key comes from the code`() {
        val (sid, key) = SearchTogether.keysFor("ABCD2345EF")
        val longest = SearchTogether.Message.Hello(65535, "x".repeat(40), SearchTogether.Target(Source.WIFI, "y".repeat(60), "z".repeat(60)))
        assert(SearchTogether.seal(SearchTogether.encode(longest), sid, key).size <= MeshtasticLink.MAX_PAYLOAD)
        assertEquals(16, MeshtasticLink.psk("ABCD2345EF").size)
        assertArrayEquals(MeshtasticLink.psk("ABCD2345EF"), MeshtasticLink.psk("ABCD2345EF"))
        assert(MeshtasticLink.CHANNEL_NAME.length <= 11)
    }
}
