package app.sirenscout

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Watch this place": leave the phone somewhere (a hotel room, home), let it learn every device
 * around for a few minutes, then see what's new, gone or back. Phones and earbuds switch Bluetooth
 * addresses, so those are recognised by name and broadcast fingerprint, not address. After RF
 * Sentinel's feature of the same name.
 */
object PlaceWatch {
    const val LEARN_MS = 5 * 60_000L
    /** Not heard for this long after learning: gone. */
    const val GONE_MS = 5 * 60_000L

    data class Seen(val fingerprint: String, val title: String, val source: Source, val firstSeen: Long, val lastSeen: Long, val key: String)

    data class State(
        val startedAt: Long,
        val baseline: Map<String, Seen> = emptyMap(),
        val new: Map<String, Seen> = emptyMap(),
        /** Baseline devices that left and came back, with when they came back. */
        val back: Map<String, Long> = emptyMap(),
    ) {
        val learning get() = System.currentTimeMillis() - startedAt < LEARN_MS
        fun learningLeftMs(now: Long) = (startedAt + LEARN_MS - now).coerceAtLeast(0)
        fun gone(now: Long) = if (now - startedAt < LEARN_MS) emptyList()
        else baseline.values.filter { now - it.lastSeen > GONE_MS }
    }

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun start(now: Long = System.currentTimeMillis()) { _state.value = State(now) }
    fun stop() { _state.value = null }

    /** Identifies a device across Bluetooth address changes. */
    fun fingerprint(d: Detection): String = when {
        d.source == Source.BLE && (d.name != null || d.adSummary != null) -> "BLE|${d.name.orEmpty()}|${d.adSummary.orEmpty()}"
        else -> d.key
    }

    /** Folds in the current detections; returns devices that just turned up as new. */
    @Synchronized
    fun update(detections: Collection<Detection>, now: Long = System.currentTimeMillis()): List<Seen> {
        val s = _state.value ?: return emptyList()
        val learning = now - s.startedAt < LEARN_MS
        val baseline = s.baseline.toMutableMap()
        val new = s.new.toMutableMap()
        val back = s.back.toMutableMap()
        val fresh = ArrayList<Seen>()
        for (d in detections) {
            if (d.source == Source.RF || d.lastSeen < s.startedAt) continue
            val fp = fingerprint(d)
            val seen = Seen(fp, d.title, d.source, d.firstSeen.coerceAtLeast(s.startedAt), d.lastSeen, d.key)
            val known = baseline[fp]
            when {
                learning || known != null -> {
                    if (known != null && !learning && now - known.lastSeen > GONE_MS && d.lastSeen > known.lastSeen) back[fp] = now
                    baseline[fp] = seen.copy(firstSeen = known?.firstSeen ?: seen.firstSeen, lastSeen = maxOf(seen.lastSeen, known?.lastSeen ?: 0))
                }
                else -> {
                    val had = new[fp]
                    if (had == null) fresh += seen
                    new[fp] = seen.copy(firstSeen = had?.firstSeen ?: seen.firstSeen, lastSeen = maxOf(seen.lastSeen, had?.lastSeen ?: 0))
                }
            }
        }
        _state.value = s.copy(baseline = baseline, new = new, back = back)
        return fresh
    }
}
