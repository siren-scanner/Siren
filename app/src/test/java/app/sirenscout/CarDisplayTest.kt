package app.sirenscout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.Socket

class CarDisplayTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val PORT = 18_080 // 8080 may be taken on the machine running the tests

    @After
    fun tearDown() {
        CarDisplay.stop()
        scope.cancel()
        Detections.clear()
    }

    private fun get(path: String): Pair<String, String> {
        repeat(50) {
            runCatching {
                Socket("127.0.0.1", PORT).use { s ->
                    s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    val raw = s.getInputStream().readBytes().decodeToString()
                    return raw.substringBefore("\r\n") to raw.substringAfter("\r\n\r\n")
                }
            }
            Thread.sleep(50) // server still starting
        }
        error("server never answered")
    }

    @Test
    fun `serves the page and live state only with the code`() {
        CarDisplay.start("abc234", PORT)
        Detections.record(Source.BLE, "00:25:DF:11:22:33", "Axon Body 3", -62, Signatures.byId("axon"), alertEligible = false, location = null)
        CarDisplay.publishAlert("Police: Axon body camera", "Axon Body 3")

        // The address as Siren displays it (no trailing slash) must lead to the page.
        val (redirect, _) = get("/abc234")
        assertEquals("HTTP/1.1 302 Found", redirect)

        val (pageStatus, page) = get("/abc234/")
        assertEquals("HTTP/1.1 200 OK", pageStatus)
        assertTrue(page.contains("<title>Siren</title>"))

        val (stateStatus, body) = get("/abc234/state")
        assertEquals("HTTP/1.1 200 OK", stateStatus)
        val state = JSONObject(body)
        assertEquals(1, state.getJSONArray("active").length())
        assertEquals("Axon body camera / Signal / Fleet", state.getJSONArray("active").getJSONObject(0).getString("title"))
        assertEquals("Police: Axon body camera", state.getJSONObject("alert").getString("title"))

        assertEquals("HTTP/1.1 404 Not Found", get("/wrong1/").first)
        assertEquals("HTTP/1.1 404 Not Found", get("/").first)

        // Leave the page and a sample state behind so the page can be checked in a real browser.
        File("build/cardisplay/abc234").apply { mkdirs() }.let { dir ->
            File(dir, "index.html").writeText(page)
            File(dir, "state").writeText(body)
        }
    }

    @Test
    fun `ignores an address after repeated wrong codes`() {
        CarDisplay.start("abc23456", PORT)
        assertEquals("HTTP/1.1 200 OK", get("/abc23456/state").first)
        repeat(10) { assertEquals("HTTP/1.1 404 Not Found", get("/guess$it/state").first) }
        assertEquals("locked out, even with the right code", "", get("/abc23456/state").first)
    }

    @Test
    fun `new codes are 8 characters`() {
        assertTrue(Regex("^[a-z2-9]{8}$").matches(CarDisplay.newCode()))
    }
}
