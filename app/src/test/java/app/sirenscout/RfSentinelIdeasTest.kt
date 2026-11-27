package app.sirenscout

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The identifiers and features added after RF Sentinel's (made-up addresses and names). */
class RfSentinelIdeasTest {
    private val t0 = 1_000_000_000_000L
    @After fun tearDown() { Detections.clear(); PlaceWatch.stop(); PoliceCars.clear() }

    private fun uuid(short: Int) = "%08x-0000-1000-8000-00805f9b34fb".format(short)

    @Test
    fun `new identifiers match`() {
        assertEquals("axon", Signatures.match(Source.BLE, "4A:00:00:00:00:01", null, serviceUuids = listOf(uuid(0xFC81)))?.id)
        assertEquals("axon", Signatures.match(Source.BLE, "4A:00:00:00:00:01", null, manufacturerIds = listOf(0x034D))?.id)
        assertEquals("axon", Signatures.match(Source.WIFI, "4A:00:00:00:00:01", "AB3-X1234")?.id)
        // The body-cam tag hidden in the broadcast, also stored reversed.
        assertEquals("axon", Signatures.match(Source.BLE, "4A:00:00:00:00:01", null, payload = "\\u0002\\u0001BWCDEVICE".toByteArray())?.id)
        assertEquals("axon", Signatures.match(Source.BLE, "4A:00:00:00:00:01", null, payload = "ECIVEDCWB".toByteArray())?.id)
        assertEquals("camera-glasses", Signatures.match(Source.BLE, "4A:00:00:00:00:01", null, manufacturerIds = listOf(0x0D53))?.id)
        assertEquals("card-skimmer", Signatures.match(Source.BT_CLASSIC, "00:00:00:00:00:01", "HC-05")?.id)
        assertNull(Signatures.match(Source.BT_CLASSIC, "00:00:00:00:00:01", "HC-05 garage door"))
        assertEquals("flock", Signatures.match(Source.WIFI, "B4:1E:52:00:00:01", "x")?.id)
        assertEquals("phone-forensics", Signatures.match(Source.WIFI, "70:B3:D5:71:F0:01", "x")?.id)
        assertEquals("ptt-button", Signatures.match(Source.BLE, "4A:00:00:00:00:01", "PTT-Z01")?.id)
        assertEquals(Category.PRIVACY, Signatures.byId("card-skimmer")?.category)
        assertEquals(Trackers.SMARTTAG, Trackers.classify(emptyMap(), mapOf(0xFD59 to byteArrayOf(1)), emptySet()))
    }

    private fun ble(addr: String, at: Long, rssi: Int = -60, name: String? = "Buds", ad: String = "maker 0x0075; services FD69") =
        Detections.record(Source.BLE, addr, name, rssi, null, false, null, adSummary = ad, now = at)

    @Test
    fun `a device that switches address carries on as the same encounter`() {
        ble("4A:00:00:00:00:01", t0)
        ble("4A:00:00:00:00:01", t0 + 60_000)
        ble("5B:00:00:00:00:02", t0 + 65_000) // quiet 5 s, then a new address with the same fingerprint
        val items = Detections.items.value
        assertEquals(setOf("BLE|5B:00:00:00:00:02"), items.keys)
        val d = items.getValue("BLE|5B:00:00:00:00:02")
        assertEquals(t0, d.firstSeen)
        assertEquals(3, d.count)
        assertEquals("BLE|4A:00:00:00:00:01|$t0", d.uid)
        assertEquals(listOf("4A:00:00:00:00:01"), d.previousIds)
    }

    @Test
    fun `look-alikes, still-heard devices and big signal jumps aren't linked`() {
        ble("4A:00:00:00:00:01", t0); ble("4A:00:00:00:00:03", t0) // two identical devices went quiet
        ble("5B:00:00:00:00:02", t0 + 5_000)
        assertEquals(3, Detections.items.value.size)
        Detections.clear()
        ble("4A:00:00:00:00:01", t0)
        ble("5B:00:00:00:00:02", t0 + 1_000) // the first one was heard just now: two devices
        assertEquals(2, Detections.items.value.size)
        Detections.clear()
        ble("4A:00:00:00:00:01", t0, rssi = -40)
        ble("5B:00:00:00:00:02", t0 + 5_000, rssi = -90)
        assertEquals(2, Detections.items.value.size)
    }

    @Test
    fun `police gear whose signals move together is one vehicle`() {
        fun d(id: String, sig: String) = Detection(Source.BLE, id, null, -60, Signatures.byId(sig), t0, t0 + 60_000, 30, 0, -50, null, null)
        val cam = d("4A:00:00:00:00:01", "axon"); val router = d("4A:00:00:00:00:02", "cradlepoint"); val other = d("4A:00:00:00:00:03", "digital-ally")
        // A car driving past: rises then falls; the third device moves on its own.
        val curve = listOf(-90, -80, -70, -60, -55, -60, -70, -80, -90, -95)
        val samples = mapOf(
            cam.key to curve.mapIndexed { i, r -> Detections.Sample(t0 + 40_000 + i * 2_000L, r) },
            router.key to curve.mapIndexed { i, r -> Detections.Sample(t0 + 40_500 + i * 2_000L, r - 8) },
            other.key to curve.mapIndexed { i, r -> Detections.Sample(t0 + 40_000 + i * 2_000L, -145 - r) }, // fades as they peak
        )
        val groups = PoliceCars.find(listOf(cam, router, other), { samples[it].orEmpty() }, t0 + 60_000)
        assertEquals(1, groups.size)
        assertEquals(setOf(cam.key, router.key), groups.single().members.map { it.key }.toSet())
        // Two routers (no police gear) never make a police car.
        val router2 = d("4A:00:00:00:00:04", "peplink")
        val s2 = mapOf(router.key to samples.getValue(router.key), router2.key to samples.getValue(cam.key))
        assertTrue(PoliceCars.find(listOf(router, router2), { s2[it].orEmpty() }, t0 + 60_000).isEmpty())
    }

    @Test
    fun `watch this place learns, then reports new, gone and back`() {
        fun det(id: String, at: Long, name: String? = null, src: Source = Source.WIFI) =
            Detection(src, id, name, -60, null, at, at, 1, 0, -60, null, null, adSummary = if (src == Source.BLE) "maker 0x004C" else null)
        PlaceWatch.start(t0)
        PlaceWatch.update(listOf(det("10:00:00:00:00:01", t0 + 1_000), det("4A:00:00:00:00:01", t0 + 1_000, "Phone", Source.BLE)), t0 + 1_000)
        // The phone stays around through the learning minutes.
        PlaceWatch.update(listOf(det("4A:00:00:00:00:01", t0 + PlaceWatch.LEARN_MS - 1_000, "Phone", Source.BLE)), t0 + PlaceWatch.LEARN_MS - 1_000)
        val later = t0 + PlaceWatch.LEARN_MS + 60_000
        // The phone switched address (same fingerprint): not new. A new network is.
        val fresh = PlaceWatch.update(listOf(det("5B:00:00:00:00:09", later, "Phone", Source.BLE), det("20:00:00:00:00:02", later)), later)
        assertEquals(listOf("WIFI|20:00:00:00:00:02"), fresh.map { it.key })
        val s = PlaceWatch.state.value!!
        assertEquals(listOf("WIFI|10:00:00:00:00:01"), s.gone(later + PlaceWatch.GONE_MS).map { it.key })
        val back = later + PlaceWatch.GONE_MS + 60_000
        PlaceWatch.update(listOf(det("10:00:00:00:00:01", back)), back)
        assertEquals(setOf("WIFI|10:00:00:00:00:01"), PlaceWatch.state.value!!.back.keys)
    }

    @Test
    fun `a police vehicle that breaks up and re-forms alerts once`() {
        fun d(id: String, sig: String) = Detection(Source.BLE, id, null, -60, Signatures.byId(sig), t0, t0 + 60_000, 30, 0, -50, null, null)
        val cam = d("4A:00:00:00:00:11", "axon"); val router = d("4A:00:00:00:00:12", "cradlepoint")
        val curve = listOf(-90, -80, -70, -60, -55, -60, -70, -80, -90, -95)
        val samples = mapOf(
            cam.key to curve.mapIndexed { i, r -> Detections.Sample(t0 + 40_000 + i * 2_000L, r) },
            router.key to curve.mapIndexed { i, r -> Detections.Sample(t0 + 40_500 + i * 2_000L, r - 8) },
        )
        val now = t0 + 60_000
        assertEquals(1, PoliceCars.update(listOf(cam, router), { samples[it].orEmpty() }, now).size)
        assertEquals(0, PoliceCars.update(listOf(cam), { samples[it].orEmpty() }, now + 5_000).size) // broke up
        assertEquals(0, PoliceCars.update(listOf(cam, router), { samples[it].orEmpty() }, now + 10_000).size) // re-formed: same car
    }
}
