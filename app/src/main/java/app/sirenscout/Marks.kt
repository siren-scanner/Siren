package app.sirenscout

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Marked moments: one tap saves just the time and where you were, so the History log can be
 * opened at that moment later (with everything that was around then outlined).
 */
object Marks {
    data class Mark(val time: Long, val lat: Double?, val lon: Double?)

    private val _marks = MutableStateFlow<List<Mark>>(emptyList())
    val marks: StateFlow<List<Mark>> = _marks.asStateFlow()

    /** The mark the History tab is showing, if any. */
    val selected = MutableStateFlow<Mark?>(null)

    private fun file(context: Context) = File(context.filesDir, "marks.csv")

    @Synchronized
    fun load(context: Context) {
        _marks.value = runCatching {
            file(context).readLines().mapNotNull { line ->
                val p = line.split(',')
                p[0].toLongOrNull()?.let { Mark(it, p.getOrNull(1)?.toDoubleOrNull(), p.getOrNull(2)?.toDoubleOrNull()) }
            }
        }.getOrDefault(emptyList()).sortedByDescending { it.time }
    }

    @Synchronized
    fun add(context: Context, mark: Mark) {
        _marks.value = (listOf(mark) + _marks.value).take(MAX)
        save(context)
    }

    @Synchronized
    fun remove(context: Context, mark: Mark) {
        _marks.value = _marks.value - mark
        if (selected.value == mark) selected.value = null
        save(context)
    }

    private fun save(context: Context) = runCatching {
        file(context).writeText(_marks.value.joinToString("\n") { "${it.time},${it.lat ?: ""},${it.lon ?: ""}" })
    }

    /** Was [d] around at [time]? (Seen within a minute either side of it.) */
    fun around(d: Detection, time: Long) = d.firstSeen - AROUND_MS <= time && time <= d.lastSeen + AROUND_MS

    private const val MAX = 200
    private const val AROUND_MS = 60_000L
}
