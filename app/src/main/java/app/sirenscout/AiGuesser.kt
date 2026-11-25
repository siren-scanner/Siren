package app.sirenscout

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

sealed class AiStatus(val text: String) {
    object Ready : AiStatus("Ready")
    object NeedsDownload : AiStatus("The AI model needs downloading (once, over Wi-Fi is best)")
    object Downloading : AiStatus("Downloading the AI model…")
    object Unsupported : AiStatus("Not supported on this phone")
    class Error(message: String) : AiStatus("Error: $message")
}

class AiError(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { BUSY, QUOTA, BACKGROUND, UNAVAILABLE, OTHER }
}

/** What the on-device model thinks a device is. Always shown with an "(AI)" mark. */
data class AiGuess(val label: String, val category: String, val confidence: String, val why: String) {
    val isConsumer get() = category == "consumer"
    val isOfficial get() = category in setOf("police", "fire_ems", "fleet", "surveillance")
}

/**
 * Asks the on-device model about devices Siren couldn't identify, a few at a time, only while
 * Siren is on screen (Android doesn't allow it otherwise). Answers are cached by a fingerprint of
 * what the device broadcasts, so each kind of device is only asked about once - even when it
 * changes its address.
 */
object AiGuesser {
    const val MARK = "(AI)"
    private const val BATCH = 6
    private const val INTERVAL_MS = 15_000L

    val status = MutableStateFlow<AiStatus>(AiStatus.Ready)
    val guesses = MutableStateFlow<Map<String, AiGuess>>(emptyMap())
    /** Short note on the loop's state, shown in Settings. */
    val activity = MutableStateFlow("")
    @Volatile private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.applicationContext.getSharedPreferences("ai_guesses", Context.MODE_PRIVATE)
        prefs = sp
        guesses.value = sp.all.mapNotNull { (k, v) ->
            runCatching { fromJson(JSONObject(v as String))?.let { k to it } }.getOrNull()
        }.toMap()
    }

    fun toJson(g: AiGuess): JSONObject =
        JSONObject().put("label", g.label).put("category", g.category).put("confidence", g.confidence).put("why", g.why)

    fun fromJson(j: JSONObject): AiGuess? = runCatching {
        AiGuess(j.getString("label"), j.getString("category"), j.getString("confidence"), j.getString("why"))
    }.getOrNull()

    /** Adds answers from another phone; this phone's own answers win. Returns the ones that were new. */
    fun merge(context: Context, other: Map<String, AiGuess>): Map<String, AiGuess> {
        init(context)
        val new = other - guesses.value.keys
        remember(new)
        return new
    }

    /** Re-reads stored answers, e.g. after restoring a backup. */
    @Synchronized
    fun reload(context: Context) {
        prefs = null
        init(context)
    }

    fun clear(context: Context) {
        init(context)
        prefs?.edit()?.clear()?.apply()
        guesses.value = emptyMap()
    }

    fun lookup(d: Detection): AiGuess? = if (d.signature != null || d.isIdentified) null
        else guesses.value[key(d)]?.let { AiCheck.check(d.source, d.name, AiCheck.makerOf(d), it) }

    /**
     * Groups devices that look alike: numbers in names are wildcarded ("Unit 14" ~ "Unit 15"),
     * and Bluetooth devices are keyed by what they advertise rather than their changing address.
     */
    fun key(d: Detection): String {
        val name = d.name?.lowercase()?.replace(Regex("[0-9]+"), "#")?.trim().orEmpty()
        val what = when (d.source) {
            Source.BLE -> d.adSummary.orEmpty()
            else -> d.guess?.substringBefore(" · ").orEmpty()
        }
        return "${d.source.name}|$name|$what"
    }

    /** Devices worth asking about: unidentified, not hidden, with something to go on. */
    fun candidates(items: Collection<Detection>, known: Map<String, AiGuess>, hidden: Hidden.Rules): List<Detection> =
        items.asSequence()
            .filter { it.signature == null && it.source != Source.RF && !hidden.hides(it) && !it.isIdentified }
            .filter { it.name != null || it.adSummary != null || it.guess != null }
            .distinctBy(::key)
            .filter { key(it) !in known }
            .sortedByDescending { it.rssi }
            .take(BATCH)
            .toList()

    fun buildPrompt(devices: List<Detection>): String = buildString {
        appendLine(
            "You identify radio devices that a phone in a car picked up. For each numbered device, say what it most " +
                "likely is. Categories: police (body cameras, police radios, in-car police computers), fire_ems, " +
                "fleet (vehicle routers, rugged laptops, light-bar controllers), surveillance (plate readers, " +
                "gunshot detectors), tracker (AirTag, Tile and similar), consumer (phones, headphones, watches, home " +
                "routers, TVs, cars' own systems), infrastructure (shops, offices, traffic, utilities), unknown. Use " +
                "unknown when the details don't support a real guess - never invent a brand. Names are typed in by the " +
                "owner and are often jokes: home Wi-Fi called \"FBI Surveillance Van\", \"NSA Listening Post\" or " +
                "\"Police Van 3\" is a prank, and real police or surveillance gear never announces itself in its name. " +
                "Judge by the maker and type first: a home-router or consumer maker means consumer, whatever the name " +
                "says. Answer with exactly one " +
                "line per device, in this format and nothing else:"
        )
        appendLine("number | category | confidence (low, medium or high) | what it is, in under 6 words | why, in under 15 words")
        appendLine()
        devices.forEachIndexed { i, d ->
            append("${i + 1}. ").append(
                when (d.source) {
                    Source.BLE -> "Bluetooth LE"
                    Source.BT_CLASSIC -> "Bluetooth"
                    Source.WIFI -> "Wi-Fi network"
                    Source.RF -> "Radio"
                }
            )
            d.name?.let { append(" | name: \"").append(it.take(40)).append('"') }
            d.guess?.let { append(" | maker/type from registries: ").append(it) }
            d.adSummary?.let { append(" | advertisement: ").append(it) }
            append(" | signal ${d.rssi} dBm")
            appendLine()
        }
    }

    private val LINE = Regex("""^\s*(\d+)\s*[.|)]?\s*\|\s*([a-z_ /-]+?)\s*\|\s*(low|medium|high)\s*\|\s*([^|]+?)\s*\|\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
    private val CATEGORIES = setOf("police", "fire_ems", "fleet", "surveillance", "tracker", "consumer", "infrastructure", "unknown")

    /** Reads the model's lines back into guesses by device number; ignores anything malformed. */
    fun parse(text: String, count: Int): Map<Int, AiGuess> = text.lines().mapNotNull { line ->
        val m = LINE.find(line) ?: return@mapNotNull null
        val n = m.groupValues[1].toInt()
        if (n !in 1..count) return@mapNotNull null
        val category = m.groupValues[2].lowercase().replace(' ', '_').replace('/', '_')
            .let { c -> if (c in CATEGORIES) c else if (c.startsWith("fire")) "fire_ems" else "unknown" }
        n to AiGuess(
            label = m.groupValues[4].trim().take(60),
            category = category,
            confidence = m.groupValues[3].lowercase(),
            why = m.groupValues[5].trim().take(140),
        )
    }.toMap()

    /** The loop that runs while Siren is on screen; returns when cancelled. */
    suspend fun run(context: Context) {
        init(context)
        if (!AiEngine.AVAILABLE_IN_BUILD) return
        while (true) {
            // Checked every time round, so the switch in Settings takes effect without leaving the app.
            if (!Prefs(context).aiEnabled) {
                activity.value = "Off - turn on Use on-device AI to start"
                delay(2_000)
                continue
            }
            val s = AiEngine.status(context)
            status.value = s
            if (s != AiStatus.Ready) {
                delay(30_000)
                continue
            }
            val batch = candidates(Detections.items.value.values, guesses.value, Hidden.rules.value)
            if (batch.isEmpty()) {
                activity.value = "Nothing new to look at"
                delay(INTERVAL_MS)
                continue
            }
            activity.value = "Looking at ${batch.size} device(s)…"
            val pause = try {
                val answer = AiEngine.generate(context, buildPrompt(batch))
                val parsed = parse(answer, batch.size)
                remember(batch.mapIndexedNotNull { i, d -> parsed[i + 1]?.let { key(d) to it } }.toMap())
                activity.value = "Identified ${parsed.size} of ${batch.size} just now · ${guesses.value.size} kinds known"
                AppLog.i("AI: ${parsed.size}/${batch.size} answered")
                INTERVAL_MS
            } catch (e: AiError) {
                activity.value = when (e.kind) {
                    AiError.Kind.BUSY -> "AI busy - trying again shortly"
                    AiError.Kind.QUOTA -> "Android's daily AI battery allowance for $APP_NAME is used up - paused for an hour"
                    AiError.Kind.BACKGROUND -> "Paused - only works while $APP_NAME is on screen"
                    AiError.Kind.UNAVAILABLE -> "AI not available: ${e.message}"
                    AiError.Kind.OTHER -> "AI error: ${e.message}"
                }
                AppLog.w("AI ${e.kind}: ${e.message}")
                when (e.kind) {
                    AiError.Kind.BUSY -> 30_000L
                    AiError.Kind.QUOTA -> 3_600_000L
                    else -> 60_000L
                }
            }
            delay(pause)
        }
    }

    private fun remember(new: Map<String, AiGuess>) {
        if (new.isEmpty()) return
        guesses.update { it + new }
        prefs?.edit()?.apply {
            new.forEach { (k, g) -> putString(k, toJson(g).toString()) }
        }?.apply()
    }
}
