package app.sirenscout

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Persistent log of matched detections. Each row is one encounter: a device seen continuously,
 * with gaps shorter than [Detections.ENCOUNTER_GAP_MS].
 */
object History {
    private val _changes = MutableStateFlow(0)
    /** Bumped after every write, so the UI knows to reload. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    @Volatile private var helper: Helper? = null

    private fun db(context: Context): SQLiteDatabase =
        (helper ?: synchronized(this) {
            helper ?: Helper(context.applicationContext).also { helper = it }
        }).writableDatabase

    fun save(context: Context, detections: Collection<Detection>) {
        if (detections.isEmpty()) return
        val db = db(context)
        db.beginTransaction()
        try {
            for (d in detections) {
                val values = ContentValues().apply {
                    put("uid", d.uid)
                    put("source", d.source.name)
                    put("device_id", d.id)
                    put("name", d.name)
                    put("signature_id", d.signature?.id)
                    put("first_seen", d.firstSeen)
                    put("last_seen", d.lastSeen)
                    put("count", d.count)
                    put("rssi", d.rssi)
                    put("best_rssi", d.bestRssi)
                    put("lat", d.lat)
                    put("lon", d.lon)
                    put("guess", d.guess)
                    put("reason", d.reason)
                    d.estimate?.let { e ->
                        put("est_lat", e.lat); put("est_lon", e.lon); put("est_radius", e.radiusM); put("est_readings", e.readings)
                    }
                }
                db.insertWithOnConflict("encounters", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _changes.update { it + 1 }
    }

    fun load(context: Context, limit: Int = 5000): List<Detection> =
        db(context).query("encounters", null, null, null, null, null, "last_seen DESC", limit.toString()).use { c ->
            fun col(name: String) = c.getColumnIndexOrThrow(name)
            val iSource = col("source")
            val iId = col("device_id")
            val iName = col("name")
            val iSig = col("signature_id")
            val iFirst = col("first_seen")
            val iLast = col("last_seen")
            val iCount = col("count")
            val iRssi = col("rssi")
            val iBest = col("best_rssi")
            val iLat = col("lat")
            val iLon = col("lon")
            val iGuess = col("guess")
            val iReason = col("reason")
            val iEstLat = col("est_lat")
            val iEstLon = col("est_lon")
            val iEstRadius = col("est_radius")
            val iEstReadings = col("est_readings")
            buildList {
                while (c.moveToNext()) {
                    val source = runCatching { Source.valueOf(c.getString(iSource)) }.getOrNull() ?: continue
                    add(
                        Detection(
                            source = source,
                            id = c.getString(iId),
                            name = if (c.isNull(iName)) null else c.getString(iName),
                            rssi = c.getInt(iRssi),
                            signature = if (c.isNull(iSig)) null else Signatures.byId(c.getString(iSig)),
                            firstSeen = c.getLong(iFirst),
                            lastSeen = c.getLong(iLast),
                            count = c.getInt(iCount),
                            lastAlert = 0L,
                            bestRssi = c.getInt(iBest),
                            lat = if (c.isNull(iLat)) null else c.getDouble(iLat),
                            lon = if (c.isNull(iLon)) null else c.getDouble(iLon),
                            guess = if (c.isNull(iGuess)) null else c.getString(iGuess),
                            reason = if (c.isNull(iReason)) null else c.getString(iReason),
                            estimate = if (c.isNull(iEstLat)) null else LocationEstimate(
                                c.getDouble(iEstLat), c.getDouble(iEstLon), c.getDouble(iEstRadius), c.getInt(iEstReadings),
                            ),
                        )
                    )
                }
            }
        }

    /** Writes every saved encounter to [uri] as CSV and returns how many rows were written. */
    /**
     * With [safe], device addresses keep only their maker prefix, broadcast names (which include
     * home network names) are dropped, and locations are rounded to about 1 km.
     */
    fun exportCsv(context: Context, uri: Uri, safe: Boolean = false): Int {
        val rows = load(context, limit = Int.MAX_VALUE)
        val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val zone = ZoneId.systemDefault()
        fun ts(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).format(time)
        fun field(v: Any?): String {
            val s = v?.toString() ?: return ""
            return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"${s.replace("\"", "\"\"")}\"" else s
        }

        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("can't open $uri")
        out.bufferedWriter().use { w ->
            w.write(
                "first_seen,last_seen,source,device_id,name,signature,category,confidence," +
                    "sightings,last_rssi,best_rssi,latitude,longitude,guess,why_it_matched," +
                        "estimated_latitude,estimated_longitude,estimate_radius_m\r\n"
            )
            for (d in rows) {
                val sig = d.signature
                val reason = if (safe) d.reason?.let { r -> Redact.text(d.name?.let { r.replace(it, "…") } ?: r) } else d.reason
                w.write(
                    listOf(
                        ts(d.firstSeen), ts(d.lastSeen), d.source.label,
                        if (safe) Redact.text(d.id) else d.id,
                        if (safe) null else d.name,
                        sig?.label, sig?.category?.label, sig?.confidence?.name?.lowercase(),
                        d.count, d.rssi, d.bestRssi,
                        if (safe) Redact.coordinate(d.lat) else d.lat,
                        if (safe) Redact.coordinate(d.lon) else d.lon,
                        d.guess?.let { "$it $GUESS_MARK" }, reason,
                        if (safe) Redact.coordinate(d.estimate?.lat) else d.estimate?.lat,
                        if (safe) Redact.coordinate(d.estimate?.lon) else d.estimate?.lon,
                        d.estimate?.radiusM?.let { Math.round(it) },
                    ).joinToString(",", transform = ::field)
                )
                w.write("\r\n")
            }
        }
        return rows.size
    }

    fun clear(context: Context) {
        db(context).delete("encounters", null, null)
        _changes.update { it + 1 }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "history.db", null, 4) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE encounters (
                    uid TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    name TEXT,
                    signature_id TEXT,
                    first_seen INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    count INTEGER NOT NULL,
                    rssi INTEGER NOT NULL,
                    best_rssi INTEGER NOT NULL,
                    lat REAL,
                    lon REAL,
                    guess TEXT,
                    reason TEXT,
                    est_lat REAL,
                    est_lon REAL,
                    est_radius REAL,
                    est_readings INTEGER
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX encounters_last_seen ON encounters(last_seen)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE encounters ADD COLUMN guess TEXT")
            if (oldVersion < 3) db.execSQL("ALTER TABLE encounters ADD COLUMN reason TEXT")
            if (oldVersion < 4) {
                for (col in listOf("est_lat REAL", "est_lon REAL", "est_radius REAL", "est_readings INTEGER")) {
                    db.execSQL("ALTER TABLE encounters ADD COLUMN $col")
                }
            }
        }
    }
}
