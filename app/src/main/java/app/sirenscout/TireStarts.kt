package app.sirenscout

import android.content.Context

/**
 * Tire sensors heard at the start of scanning sessions (for learning your own car), shared by the
 * scanner and Settings so "Forget my car's tire sensors" works even while scanning.
 */
object TireStarts {
    private var loaded: MutableMap<String, Int>? = null

    @Synchronized
    fun map(context: Context): MutableMap<String, Int> =
        loaded ?: Prefs(context).tireStarts.toMutableMap().also { loaded = it }

    @Synchronized
    fun save(context: Context) { loaded?.let { Prefs(context).tireStarts = it } }

    @Synchronized
    fun forget(context: Context) {
        map(context).clear()
        Prefs(context).tireStarts = emptyMap()
    }
}
