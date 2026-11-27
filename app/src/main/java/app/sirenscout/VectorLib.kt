package app.sirenscout

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * The vector map's engine (MapLibre's native library, about 10-13 MB) is an optional download, so
 * the app itself stays small. It's checked against the SHA-256 built into the app before it's
 * saved, and again before it's loaded. The F-Droid build has it included instead (F-Droid doesn't
 * allow apps to download code); see [BuildConfig.VECTOR_BUNDLED].
 * The download files are made by tools/build-vector-lib.sh.
 */
object VectorLib {
    /** Must match the MapLibre version in app/build.gradle.kts (checked by VectorLibTest). */
    const val MAPLIBRE_VERSION = "13.8.0"

    /** SHA-256 of libmaplibre.so in MapLibre's release, per chip type. */
    internal val SHA256 = mapOf(
        "arm64-v8a" to "89648f81fa8827ccfc9823e4481cfa016c049581612c28a84099c5ca4e6b133c",
        "armeabi-v7a" to "bfca896f728d4a0e4d13b714abc70114abc076d8c557ee044a982e8de5bb0a41",
    )

    sealed interface State {
        data object Missing : State
        data class Downloading(val done: Long, val total: Long) : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    val state = MutableStateFlow<State>(State.Missing)

    /** This phone's chip type, if there's an engine for it. */
    fun abi(): String? = Build.SUPPORTED_ABIS.firstOrNull { it in SHA256 }

    fun fileName(abi: String) = "maplibre-$MAPLIBRE_VERSION-$abi.so.gz"

    private fun file(context: Context) = File(File(context.filesDir, "vector-map"), "libmaplibre-$MAPLIBRE_VERSION.so")

    fun sizeBytes(context: Context): Long = File(context.filesDir, "vector-map").listFiles()?.sumOf { it.length() } ?: 0L

    /** The public edition downloads from the project's releases, the maintainer's builds from their own server first. */
    fun sources(abi: String): List<String> = buildList {
        for (base in listOf(DEFAULT_UPDATE_URL, FALLBACK_UPDATE_URL)) {
            if (base.isNotEmpty()) add(base.removeSuffix("/beta") + "/data/${fileName(abi)}")
        }
        add("https://github.com/siren-scanner/Siren/releases/download/maplibre-$MAPLIBRE_VERSION/${fileName(abi)}")
    }

    @Volatile private var verified: Boolean? = null

    /** Whether the vector map can be used: included in the app, or downloaded and intact. */
    fun available(context: Context): Boolean {
        if (BuildConfig.VECTOR_BUNDLED) return true
        verified?.let { return it }
        val abi = abi() ?: return false.also { verified = it }
        val f = file(context)
        val ok = f.exists() && sha256(f) == SHA256[abi]
        verified = ok
        state.value = if (ok) State.Ready else State.Missing
        return ok
    }

    /** Points MapLibre at the downloaded engine. Call before MapLibre.getInstance. */
    fun prepare(context: Context): Boolean {
        if (BuildConfig.VECTOR_BUNDLED) return true
        if (!available(context)) return false
        val path = file(context).path
        org.maplibre.android.LibraryLoader.setLibraryLoader(object : org.maplibre.android.LibraryLoader() {
            override fun load(name: String) {
                if (name == "maplibre") System.load(path) else System.loadLibrary(name)
            }
        })
        return true
    }

    /** Downloads, checks and saves the engine. Throws with a readable message when it fails. */
    fun download(context: Context) {
        val abi = abi() ?: throw IllegalStateException("The vector map isn't available for this phone's processor")
        var lastError: Exception? = null
        for (url in sources(abi)) {
            try {
                install(context, url, SHA256.getValue(abi))
                verified = true
                state.value = State.Ready
                AppLog.i("Vector map engine installed ($abi)")
                return
            } catch (e: Exception) {
                lastError = e
                AppLog.w("Vector map download from $url failed: ${e.message}")
            }
        }
        state.value = State.Failed(lastError?.message ?: "Download failed")
        throw lastError ?: IllegalStateException("No download address")
    }

    private fun install(context: Context, url: String, expected: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        val target = file(context)
        val tmp = File(target.parentFile, "download.tmp")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Server said ${conn.responseCode}")
            val total = conn.contentLengthLong
            var read = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) { read += it; state.value = State.Downloading(read, total) } }
            }
            target.parentFile!!.mkdirs()
            GZIPInputStream(counting).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (sha256(tmp) != expected) throw IllegalStateException("The download didn't match Siren's fingerprint for it")
            target.parentFile!!.listFiles()?.filter { it != tmp }?.forEach { it.delete() } // older versions
            if (!tmp.renameTo(target)) throw IllegalStateException("Couldn't save the vector map")
            target.setReadOnly()
        } finally {
            tmp.delete()
            conn.disconnect()
        }
    }

    /** Removes the downloaded engine; the classic map takes over. Takes effect fully after a restart. */
    fun remove(context: Context) {
        File(context.filesDir, "vector-map").deleteRecursively()
        verified = false
        state.value = State.Missing
    }

    internal fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
