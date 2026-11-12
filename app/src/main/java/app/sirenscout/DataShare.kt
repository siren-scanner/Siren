package app.sirenscout

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Passes what one phone has learned to another: AI identifications, hidden devices, watch rules
 * and (if wanted) history. Unlike a backup, importing adds to what's already there and leaves the
 * other phone's settings alone - so a phone without on-device AI still gets the AI's answers.
 */
object DataShare {
    private const val FORMAT = 1

    data class Contents(
        val ai: Map<String, AiGuess> = emptyMap(),
        val hidden: Hidden.Rules = Hidden.Rules(),
        val rules: List<WatchRule> = emptyList(),
        val history: List<Detection> = emptyList(),
    ) {
        override fun toString() = listOfNotNull(
            ai.size.takeIf { it > 0 }?.let { "$it AI identifications" },
            (hidden.devices.size + hidden.names.size + hidden.prefixes.size).takeIf { it > 0 }?.let { "$it hidden devices/names" },
            rules.size.takeIf { it > 0 }?.let { "$it watch rules" },
            history.size.takeIf { it > 0 }?.let { "$it history encounters" },
        ).joinToString(", ").ifEmpty { "nothing" }
    }

    /** What this phone has, for the share dialog's counts. */
    fun current(context: Context, withHistory: Boolean) = Contents(
        ai = AiGuesser.guesses.value,
        hidden = Hidden.rules.value,
        rules = WatchRules.rules.value,
        history = if (withHistory) History.load(context, limit = Int.MAX_VALUE) else emptyList(),
    )

    fun toJson(c: Contents): JSONObject = JSONObject()
        .put("app", "Siren")
        .put("kind", "share")
        .put("format", FORMAT)
        .put("exportedAt", System.currentTimeMillis())
        .put("ai", JSONObject().apply { c.ai.forEach { (k, g) -> put(k, AiGuesser.toJson(g)) } })
        .put("hidden", JSONObject()
            .put("devices", JSONObject().apply { c.hidden.devices.forEach { (k, label) -> put(k, label) } })
            .put("names", JSONArray(c.hidden.names.toList()))
            .put("prefixes", JSONArray(c.hidden.prefixes.toList())))
        .put("rules", JSONArray(c.rules.map(WatchRules::toJson)))
        .put("history", JSONArray(c.history.map(Backup::detectionToJson)))

    fun fromJson(json: JSONObject): Contents {
        if (json.optString("app") != "Siren" || json.optString("kind") != "share") throw IOException("not a Siren shared-data file")
        if (json.optInt("format") > FORMAT) throw IOException("file is from a newer version of Siren - update this phone first")
        fun strings(a: JSONArray?) = if (a == null) emptySet() else List(a.length()) { a.getString(it) }.toSet()
        val ai = json.optJSONObject("ai")
        val hidden = json.optJSONObject("hidden")
        val devices = hidden?.optJSONObject("devices")
        val rules = json.optJSONArray("rules")
        val history = json.optJSONArray("history")
        return Contents(
            ai = ai?.keys()?.asSequence()?.mapNotNull { k -> AiGuesser.fromJson(ai.getJSONObject(k))?.let { k to it } }?.toMap().orEmpty(),
            hidden = Hidden.Rules(
                devices = devices?.keys()?.asSequence()?.associateWith { devices.getString(it) }.orEmpty(),
                names = strings(hidden?.optJSONArray("names")),
                prefixes = strings(hidden?.optJSONArray("prefixes")),
            ),
            rules = if (rules == null) emptyList() else List(rules.length()) { rules.getJSONObject(it) }.mapNotNull(WatchRules::fromJson),
            history = if (history == null) emptyList() else List(history.length()) { history.getJSONObject(it) }.mapNotNull(Backup::jsonToDetection),
        )
    }

    /** Writes the file and opens Android's share menu (Quick Share, Bluetooth, messages…). */
    fun share(context: Context, c: Contents) {
        val dir = File(context.cacheDir, "share").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() }
        }
        val file = File(dir, "siren-shared-data-${LocalDate.now()}.json")
        file.writeText(toJson(c).toString())
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri(file.name, uri) }
        context.startActivity(Intent.createChooser(send, "Share Siren data").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun read(context: Context, uri: Uri): Contents {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IOException("can't open the file")
        return fromJson(JSONObject(text))
    }

    /** Adds [c] to this phone. Existing AI answers, rules and hidden entries are kept; only new ones are added. */
    fun import(context: Context, c: Contents): Contents {
        val newAi = AiGuesser.merge(context, c.ai)
        val before = Hidden.rules.value
        Hidden.merge(c.hidden)
        val after = Hidden.rules.value
        val newRules = WatchRules.merge(c.rules)
        History.save(context, c.history)
        AppLog.i("Imported shared data: $c")
        return Contents(
            ai = newAi,
            hidden = Hidden.Rules(after.devices - before.devices.keys, after.names - before.names, after.prefixes - before.prefixes),
            rules = newRules,
            history = c.history,
        )
    }
}
