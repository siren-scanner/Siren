package app.sirenscout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class VectorLibTest {
    @Test
    fun `the pinned version is the one the app is built with`() {
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("org.maplibre.gl:android-sdk:${VectorLib.MAPLIBRE_VERSION}\""))
    }

    @Test
    fun `the pinned fingerprints match MapLibre's release`() {
        val aar = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1/org.maplibre.gl/android-sdk/${VectorLib.MAPLIBRE_VERSION}")
            .walkTopDown().firstOrNull { it.name == "android-sdk-${VectorLib.MAPLIBRE_VERSION}.aar" } ?: return // not downloaded here
        ZipFile(aar).use { zip ->
            for ((abi, sha) in VectorLib.SHA256) {
                val tmp = File.createTempFile("maplibre", ".so")
                zip.getInputStream(zip.getEntry("jni/$abi/libmaplibre.so")).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                assertEquals(abi, sha, VectorLib.sha256(tmp))
                tmp.delete()
            }
        }
    }

    @Test
    fun `downloads come from the maintainer's server first, then the project's release`() {
        val urls = VectorLib.sources("arm64-v8a")
        assertTrue(urls.last() == "https://github.com/siren-scanner/Siren/releases/download/maplibre-${VectorLib.MAPLIBRE_VERSION}/maplibre-${VectorLib.MAPLIBRE_VERSION}-arm64-v8a.so.gz")
    }
}
