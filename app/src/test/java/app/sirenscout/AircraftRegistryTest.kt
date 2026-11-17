package app.sirenscout

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class AircraftRegistryTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = kotlin.io.path.createTempDirectory("registry").toFile()
        AircraftRegistry.MIN_AIRCRAFT = 1
        AircraftRegistry.forget()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        AircraftRegistry.MIN_AIRCRAFT = 1000
        AircraftRegistry.forget()
    }

    private fun gz(text: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    // Made-up aircraft, in the format tools/build-aircraft-registry.py writes (sorted by address).
    private val sample = """
        #siren-aircraft-registry 1 2026-11-16
        M	0	Cessna 172N	4
        M	1	Boeing 737-824	189
        a00001	N1	0	1978	Example Flying Club	Springfield, IL	piston
        a12345	N123AB	1	2002	Example Leasing Trust	Chicago, IL	turbofan
        a5f00d	N500X	0	0	Jane Example	Ames, IA	piston
        adf7c7	N99999	1		Example Air Inc		turbofan
    """.trimIndent() + "\n"

    @Test fun findsEveryAircraftAndJoinsTheModel() {
        val (date, count) = AircraftRegistry.unpack(ByteArrayInputStream(gz(sample)), dir)
        assertEquals("2026-11-16", date)
        assertEquals(4, count)
        val e = AircraftRegistry.find(dir, "a12345")!!
        assertEquals("N123AB", e.tail)
        assertEquals("Boeing 737-824", e.model)
        assertEquals(189, e.seats)
        assertEquals("2002", e.year)
        assertEquals("Example Leasing Trust", e.owner)
        assertEquals("Chicago, IL", e.place)
        assertEquals("turbofan", e.engine)
        // First and last lines of the file, and empty fields.
        assertEquals("N1", AircraftRegistry.find(dir, "a00001")!!.tail)
        val last = AircraftRegistry.find(dir, "adf7c7")!!
        assertNull(last.year)
        assertNull(last.place)
        assertNull(AircraftRegistry.find(dir, "a5f00d")!!.year) // "0" means unknown
    }

    @Test fun unknownAddressesAreNotFound() {
        AircraftRegistry.unpack(ByteArrayInputStream(gz(sample)), dir)
        assertNull(AircraftRegistry.find(dir, "400abc")) // a British aircraft
        assertNull(AircraftRegistry.find(dir, "a00000"))
        assertNull(AircraftRegistry.find(dir, "ffffff"))
    }

    @Test fun refusesOtherFiles() {
        assertThrows(IllegalStateException::class.java) {
            AircraftRegistry.unpack(ByteArrayInputStream(gz("<html>Not found</html>\n")), dir)
        }
    }

    @Test fun realRegistryIfAvailable() {
        // Optional check against a real download: set SIREN_REGISTRY=/path/aircraft-registry.txt.gz.
        val path = System.getenv("SIREN_REGISTRY") ?: return
        AircraftRegistry.MIN_AIRCRAFT = 1000
        val (_, count) = AircraftRegistry.unpack(File(path).inputStream(), dir)
        println("real registry: $count aircraft")
        listOf("a43ea6", "a1b42f", "aa5b6c").forEach { println("$it -> ${AircraftRegistry.find(dir, it)}") }
    }
}
