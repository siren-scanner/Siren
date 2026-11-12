package app.sirenscout

import java.util.Locale

/**
 * Safe-sharing helpers: keep what's useful for troubleshooting or comparing notes (the maker part
 * of an address, rough area) while removing what identifies a specific device or place.
 */
object Redact {
    private val MAC = Regex("(?i)\\b([0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}):[0-9a-f]{2}:[0-9a-f]{2}:[0-9a-f]{2}\\b")

    /** "00:25:DF:12:34:56" -> "00:25:DF:••:••:••" anywhere in [text]. */
    fun text(text: String) = MAC.replace(text) { "${it.groupValues[1]}:••:••:••" }

    /** Two decimals is roughly 1 km - the neighbourhood, not the street. */
    fun coordinate(value: Double?) = value?.let { "%.2f".format(Locale.US, it) }
}
