package app.sirenscout

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Saves settings, hidden devices and history to a single JSON file the user keeps, and restores
 * them. The camera map cache isn't included; it re-downloads by itself.
 */
object Backup {
    private const val FORMAT = 1
    private val PREF_FILES = listOf("settings", "hidden", "watch_rules", "ai_guesses")

    data class Summary(val encounters: Int, val hidden: Int) {
        override fun toString() = "$encounters history encounters, $hidden hidden devices/names and all settings"
    }

    fun export(context: Context, uri: Uri): Summary {
        val history = History.load(context, limit = Int.MAX_VALUE)
        val prefs = JSONObject()
        for (file in PREF_FILES) prefs.put(file, prefsToJson(context.getSharedPreferences(file, Context.MODE_PRIVATE)))
        val json = JSONObject()
            .put("app", "Siren")
            .put("format", FORMAT)
            .put("exportedAt", System.currentTimeMillis())
            .put("prefs", prefs)
            .put("history", JSONArray(history.map(::detectionToJson)))

        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("can't open $uri")
        out.bufferedWriter().use { it.write(json.toString(2)) }
        val rules = Hidden.rules.value
        return Summary(history.size, rules.devices.size + rules.names.size)
    }

    /** Settings and hidden lists are replaced; history is merged with what's already there. */
    fun import(context: Context, uri: Uri): Summary {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IOException("can't open $uri")
        val json = JSONObject(text)
        if (json.optString("app") !in setOf("Siren", "Siren Scout")) throw IOException("not a Siren backup")
        if (json.optInt("format") > FORMAT) throw IOException("backup is from a newer version of Siren")

        val prefs = json.getJSONObject("prefs")
        for (file in PREF_FILES) {
            prefs.optJSONObject(file)?.let { jsonToPrefs(it, context.getSharedPreferences(file, Context.MODE_PRIVATE)) }
        }
        Hidden.reload(context)
        WatchRules.reload(context)
        AiGuesser.reload(context)

        val rows = json.getJSONArray("history")
        val history = List(rows.length()) { rows.getJSONObject(it) }.mapNotNull(::jsonToDetection)
        History.save(context, history)

        val rules = Hidden.rules.value
        return Summary(history.size, rules.devices.size + rules.names.size)
    }

    private fun prefsToJson(sp: SharedPreferences) = JSONObject().apply {
        for ((key, value) in sp.all) {
            val typed = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is String -> JSONObject().put("t", "s").put("v", value)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            put(key, typed)
        }
    }

    private fun jsonToPrefs(json: JSONObject, sp: SharedPreferences) {
        val edit = sp.edit().clear()
        for (key in json.keys()) {
            val typed = json.getJSONObject(key)
            when (typed.getString("t")) {
                "b" -> edit.putBoolean(key, typed.getBoolean("v"))
                "i" -> edit.putInt(key, typed.getInt("v"))
                "l" -> edit.putLong(key, typed.getLong("v"))
                "f" -> edit.putFloat(key, typed.getDouble("v").toFloat())
                "s" -> edit.putString(key, typed.getString("v"))
                "set" -> typed.getJSONArray("v").let { a -> edit.putStringSet(key, List(a.length()) { a.getString(it) }.toSet()) }
            }
        }
        edit.commit()
    }

    internal fun detectionToJson(d: Detection) = JSONObject()
        .put("source", d.source.name).put("id", d.id).put("name", d.name)
        .put("signature", d.signature?.id).put("firstSeen", d.firstSeen).put("lastSeen", d.lastSeen)
        .put("count", d.count).put("rssi", d.rssi).put("bestRssi", d.bestRssi)
        .put("lat", d.lat).put("lon", d.lon).put("guess", d.guess).put("reason", d.reason)
        .apply {
            d.estimate?.let { e ->
                put("estLat", e.lat).put("estLon", e.lon).put("estRadius", e.radiusM).put("estReadings", e.readings)
            }
        }

    internal fun jsonToDetection(j: JSONObject): Detection? {
        val source = runCatching { Source.valueOf(j.getString("source")) }.getOrNull() ?: return null
        fun str(key: String) = if (j.isNull(key)) null else j.optString(key).takeIf { it.isNotEmpty() }
        fun dbl(key: String) = if (j.isNull(key) || !j.has(key)) null else j.getDouble(key)
        return Detection(
            source = source,
            id = j.getString("id"),
            name = str("name"),
            rssi = j.getInt("rssi"),
            signature = str("signature")?.let(Signatures::byId),
            firstSeen = j.getLong("firstSeen"),
            lastSeen = j.getLong("lastSeen"),
            count = j.getInt("count"),
            lastAlert = 0L,
            bestRssi = j.getInt("bestRssi"),
            lat = dbl("lat"),
            lon = dbl("lon"),
            guess = str("guess"),
            reason = str("reason"),
            estimate = dbl("estLat")?.let { lat ->
                LocationEstimate(lat, dbl("estLon") ?: return@let null, dbl("estRadius") ?: 0.0, j.optInt("estReadings"))
            },
        )
    }
}
