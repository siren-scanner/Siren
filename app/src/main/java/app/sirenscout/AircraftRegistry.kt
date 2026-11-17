package app.sirenscout

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The optional US aircraft registry: make, model, year, owner and home base for every
 * US-registered aircraft, from the FAA's public-domain registry. It's a separate download
 * (about 8 MB, 21 MB unpacked) so the app stays small; lookups then work offline.
 * Built by tools/build-aircraft-registry.py.
 */
object AircraftRegistry {
    data class Entry(
        val tail: String,
        val model: String?,
        val seats: Int?,
        val year: String?,
        val owner: String?,
        val place: String?,
        val engine: String?,
    )

    const val FILE_NAME = "aircraft-registry.txt.gz"
    private const val HEADER = "#siren-aircraft-registry 1"

    /** Where to download it: the public edition from the project's releases, the maintainer's builds from their own server first. */
    fun sources(): List<String> = buildList {
        for (base in listOf(DEFAULT_UPDATE_URL, FALLBACK_UPDATE_URL)) {
            if (base.isNotEmpty()) add(base.removeSuffix("/beta") + "/data/$FILE_NAME")
        }
        // Everyone falls back to the project's releases (the maintainer's phones when off Tailscale, say).
        add("https://github.com/siren-scanner/Siren/releases/latest/download/$FILE_NAME")
    }

    private fun dir(context: Context) = File(context.filesDir, "aircraft-registry")
    private fun aircraftFile(context: Context) = File(dir(context), "aircraft.txt")
    private fun indexFile(context: Context) = File(dir(context), "aircraft.idx")
    private fun modelsFile(context: Context) = File(dir(context), "models.txt")
    private fun dateFile(context: Context) = File(dir(context), "date.txt")

    fun installed(context: Context) = aircraftFile(context).exists() && indexFile(context).exists() && modelsFile(context).exists()

    /** Date of the FAA data, e.g. "2026-11-16". */
    fun dataDate(context: Context): String? = runCatching { dateFile(context).readText().trim() }.getOrNull()

    fun sizeBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    @Volatile private var models: Map<Int, Pair<String, Int?>>? = null
    // ConcurrentHashMap can't hold null, so "not in the registry" is stored as NOT_FOUND.
    private val NOT_FOUND = Entry("", null, null, null, null, null, null)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val lock = Any()

    fun delete(context: Context) = synchronized(lock) {
        dir(context).deleteRecursively()
        forget()
    }

    internal fun forget() { models = null; cache.clear() }

    /**
     * Downloads and unpacks the registry, replacing any older copy. [progress] gets bytes downloaded
     * and the total (or -1). Throws with a readable message when it fails.
     */
    fun download(context: Context, progress: (Long, Long) -> Unit) {
        var lastError: Exception? = null
        for (url in sources()) {
            try {
                install(context, url, progress)
                return
            } catch (e: Exception) {
                lastError = e
                AppLog.w("Aircraft registry download from $url failed: ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("No download address")
    }

    private fun install(context: Context, url: String, progress: (Long, Long) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        try {
            if (conn.responseCode != 200) throw IllegalStateException("Server said ${conn.responseCode}")
            val total = conn.contentLengthLong
            val tmp = File(context.filesDir, "aircraft-registry.tmp")
            var read = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) { read += it; progress(read, total) } }
            }
            val (date, count) = unpack(counting, tmp)
            synchronized(lock) {
                dir(context).deleteRecursively()
                if (!tmp.renameTo(dir(context))) throw IllegalStateException("Couldn't save the registry")
                this.models = null
                cache.clear()
            }
            AppLog.i("Aircraft registry installed: $count aircraft, FAA data from $date")
        } finally {
            conn.disconnect()
        }
    }

    /** Unpacks a downloaded (gzipped) registry into [tmp]: returns the data date and the number of aircraft. */
    internal fun unpack(gz: java.io.InputStream, tmp: File): Pair<String, Int> {
            tmp.deleteRecursively(); tmp.mkdirs()
            var date = ""
            var count = 0
            GZIPInputStream(gz.buffered(), 64 * 1024).bufferedReader().use { input ->
                val first = input.readLine() ?: throw IllegalStateException("Empty file")
                if (!first.startsWith(HEADER)) throw IllegalStateException("Not an aircraft registry file")
                date = first.removePrefix(HEADER).trim()
                val aircraft = File(tmp, "aircraft.txt").outputStream().buffered()
                val index = java.io.DataOutputStream(File(tmp, "aircraft.idx").outputStream().buffered())
                val models = File(tmp, "models.txt").bufferedWriter()
                var offset = 0L
                try {
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.startsWith("M\t")) { models.write(line); models.write("\n"); continue }
                        if (line.length < 7 || line[6] != '\t') continue
                        val bytes = (line + "\n").toByteArray()
                        index.writeInt(offset.toInt())
                        aircraft.write(bytes)
                        offset += bytes.size
                        count++
                    }
                } finally {
                    aircraft.close(); index.close(); models.close()
                }
            }
            if (count < MIN_AIRCRAFT) throw IllegalStateException("The file looks incomplete ($count aircraft)")
            File(tmp, "date.txt").writeText(date)
            return date to count
    }

    /** A real registry has hundreds of thousands; fewer means a broken download. */
    internal var MIN_AIRCRAFT = 1000

    private fun models(dir: File): Map<Int, Pair<String, Int?>> = models ?: synchronized(lock) {
        models ?: File(dir, "models.txt").bufferedReader().useLines { lines ->
            lines.mapNotNull { l ->
                val p = l.split('\t')
                p.getOrNull(1)?.toIntOrNull()?.let { it to (p.getOrElse(2) { "" } to p.getOrNull(3)?.toIntOrNull()) }
            }.toMap()
        }.also { models = it }
    }

    /** The registration for this ICAO address, or null if it isn't a US aircraft or the registry isn't downloaded. */
    fun lookup(context: Context, hex: String): Entry? {
        if (!installed(context)) return null
        return lookupIn(dir(context), hex)
    }

    /** Cached lookup in an unpacked registry; remembers misses too. */
    internal fun lookupIn(dir: File, hex: String): Entry? {
        val key = hex.lowercase()
        return cache.getOrPut(key) { runCatching { find(dir, key) }.getOrNull() ?: NOT_FOUND }.takeIf { it !== NOT_FOUND }
    }

    /** Binary search of an unpacked registry in [dir]. */
    internal fun find(dir: File, key: String): Entry? = synchronized(lock) {
        RandomAccessFile(File(dir, "aircraft.idx"), "r").use { idx ->
            RandomAccessFile(File(dir, "aircraft.txt"), "r").use { data ->
                fun line(i: Long): String { idx.seek(i * 4); data.seek(idx.readInt().toLong()); return String(readLineBytes(data)) }
                var lo = 0L
                var hi = idx.length() / 4 - 1
                while (lo <= hi) {
                    val mid = (lo + hi) / 2
                    val l = line(mid)
                    val c = l.substring(0, 6).compareTo(key)
                    when {
                        c < 0 -> lo = mid + 1
                        c > 0 -> hi = mid - 1
                        else -> return parse(dir, l)
                    }
                }
                null
            }
        }
    }

    private fun readLineBytes(f: RandomAccessFile): ByteArray {
        val out = java.io.ByteArrayOutputStream(128)
        while (true) {
            val b = f.read()
            if (b < 0 || b == '\n'.code) break
            out.write(b)
        }
        return out.toByteArray()
    }

    private fun parse(dir: File, line: String): Entry {
        val p = line.split('\t')
        fun s(i: Int) = p.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
        val model = s(2)?.toIntOrNull()?.let { models(dir)[it] }
        return Entry(
            tail = s(1) ?: "",
            model = model?.first?.takeIf { it.isNotEmpty() },
            seats = model?.second,
            year = s(3)?.takeIf { it != "0" && it.length == 4 },
            owner = s(4),
            place = s(5),
            engine = s(6),
        )
    }
}
