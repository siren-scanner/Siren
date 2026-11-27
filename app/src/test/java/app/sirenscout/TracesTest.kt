package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TracesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val t0 = 1_000_000_000_000L

    @Test
    fun `a drive is kept with its route, small jitter skipped`() {
        val dir = tmp.newFolder("traces")
        val r = Traces.Recorder(dir, t0)
        r.add(Traces.Point(t0, 40.0, -100.0))
        r.add(Traces.Point(t0 + 1_000, 40.00005, -100.0)) // ~5 m: jitter, skipped
        for (i in 1..10) r.add(Traces.Point(t0 + i * 10_000L, 40.0 + i * 0.001, -100.0)) // ~1.1 km north
        r.finish()
        val trace = Traces.list(dir).single()
        assertEquals(t0, trace.start)
        val pts = Traces.load(trace)
        assertEquals(11, pts.size)
        assertEquals(1112.0, Traces.lengthM(pts), 5.0)
    }

    @Test
    fun `a session that barely moved isn't kept, and only the newest 100 drives stay`() {
        val dir = tmp.newFolder("traces")
        Traces.Recorder(dir, t0).apply { add(Traces.Point(t0, 40.0, -100.0)); add(Traces.Point(t0 + 1, 40.0005, -100.0)); finish() }
        assertTrue(Traces.list(dir).isEmpty())
        for (n in 0 until 103) {
            Traces.Recorder(dir, t0 + n).apply { add(Traces.Point(t0, 40.0, -100.0)); add(Traces.Point(t0 + 1, 40.01, -100.0)); finish() }
        }
        val kept = Traces.list(dir)
        assertEquals(100, kept.size)
        assertEquals(t0 + 102, kept.first().start)
    }

    @Test
    fun `estimate circles only for the chosen pin or tight matched estimates`() {
        val d = Detection(Source.BLE, "4A:00:00:00:00:01", null, -60, null, t0, t0, 1, 0, -60, 40.0, -100.0)
        val tight = LocationEstimate(40.0, -100.0, 50.0, 10)
        assertTrue(!showCircle(d, tight, null))
        assertTrue(showCircle(d, tight, d.uid))
        assertTrue(showCircle(d.copy(signature = Signatures.byId("axon")), tight, null))
        assertTrue(!showCircle(d.copy(signature = Signatures.byId("axon")), tight.copy(radiusM = 400.0), null))
    }
}
