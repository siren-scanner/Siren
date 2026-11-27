package app.sirenscout

import android.content.Context
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.SqlTileWriter
import java.io.File

/**
 * The map pictures (tiles) kept on the phone so areas you've seen load without data. Capped at
 * the size chosen in Settings > Storage; the oldest go first when it's full.
 */
object MapCache {
    /** Choices for the cap, in MB. */
    val SIZES = listOf(50, 100, 250, 500)
    const val DEFAULT_MB = 100

    private fun dir(context: Context) = File(context.cacheDir, "osmdroid")

    /** Points the map library at Siren's cache and applies the cap. Call before creating a map. */
    fun configure(context: Context) {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName // required by the OSM tile servers
            osmdroidBasePath = dir(context)
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
            val max = Prefs(context).mapCacheMb * 1_000_000L
            tileFileSystemCacheMaxBytes = max
            // When full, trim to 80% so it isn't trimming on every new tile.
            tileFileSystemCacheTrimBytes = max * 8 / 10
        }
    }

    fun sizeBytes(context: Context): Long =
        dir(context).walkTopDown().filter { it.isFile }.sumOf { it.length() } + vectorFiles(context).sumOf { it.length() }

    /** The vector map's own tile database (MapLibre's "ambient cache"). */
    private fun vectorFiles(context: Context) =
        listOf(context.filesDir, context.cacheDir).flatMap { d -> d.listFiles { f -> f.name.startsWith("mbgl-offline.db") }.orEmpty().toList() }

    /** MapLibre's file source is used from the main thread. */
    private fun onMain(context: Context, block: (org.maplibre.android.offline.OfflineManager) -> Unit) =
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                if (!VectorLib.prepare(context)) return@post // no vector map, so no vector cache
                org.maplibre.android.MapLibre.getInstance(context)
                block(org.maplibre.android.offline.OfflineManager.getInstance(context))
            }.onFailure { AppLog.w("Vector map cache: ${it.message}") }
        }

    private val quiet = object : org.maplibre.android.offline.OfflineManager.FileSourceCallback {
        override fun onSuccess() {}
        override fun onError(message: String) { AppLog.w("Vector map cache: $message") }
    }

    /** Applies a new cap now, trimming the oldest tiles if the cache is bigger. */
    fun setLimit(context: Context, mb: Int) {
        Prefs(context).mapCacheMb = mb
        configure(context)
        runCatching { SqlTileWriter().apply { runCleanupOperation(); onDetach() } }
        compact(context)
        onMain(context) { it.setMaximumAmbientCacheSize(mb * 1_000_000L, quiet) }
    }

    /** SQLite keeps a file's size after deleting rows; this gives the space back. */
    private fun compact(context: Context) {
        val db = File(dir(context), "tiles/${SqlTileWriter.DATABASE_FILENAME}")
        if (!db.exists()) return
        runCatching {
            android.database.sqlite.SQLiteDatabase.openDatabase(db.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE)
                .use { it.execSQL("VACUUM") }
        }.onFailure { AppLog.w("Map cache compaction failed: ${it.message}") }
    }

    /** Deletes every saved map tile; areas download again when viewed. */
    fun clear(context: Context) {
        configure(context)
        runCatching { SqlTileWriter().apply { purgeCache(); onDetach() } }
        compact(context)
        onMain(context) { it.clearAmbientCache(quiet) }
    }
}
