package app.sirenscout

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Devices the user has chosen to ignore, like their own Wi-Fi or watch. Hidden emissions are
 * not shown, never alert and aren't saved to history.
 */
object Hidden {
    data class Rules(
        /** Detection keys ("SOURCE|address") mapped to the name shown when they were hidden. */
        val devices: Map<String, String> = emptyMap(),
        /** Exact device names / SSIDs, hidden on every radio and address. */
        val names: Set<String> = emptySet(),
        /** Address prefixes ("AA:BB:CC"), e.g. every network one router broadcasts. */
        val prefixes: Set<String> = emptySet(),
    ) {
        fun hides(source: Source, id: String, name: String?) =
            "${source.name}|$id" in devices || (name != null && name in names) ||
                prefixes.any { id.startsWith(it, ignoreCase = true) }

        fun hides(d: Detection) = hides(d.source, d.id, d.name)
    }

    private val _rules = MutableStateFlow(Rules())
    val rules: StateFlow<Rules> = _rules.asStateFlow()

    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) reload(context)
    }

    /** Re-reads the stored rules, e.g. after restoring a backup. */
    @Synchronized
    fun reload(context: Context) {
        val sp = context.applicationContext.getSharedPreferences("hidden", Context.MODE_PRIVATE)
        prefs = sp
        _rules.value = Rules(
            devices = sp.getStringSet("devices", emptySet())!!.associateWith { sp.getString("label:$it", "") ?: "" },
            names = sp.getStringSet("names", emptySet())!!.toSet(),
            prefixes = sp.getStringSet("prefixes", emptySet())!!.toSet(),
        )
    }

    fun hideDevice(d: Detection) =
        update(_rules.value.let { it.copy(devices = it.devices + (d.key to (d.name ?: ""))) })

    fun unhideDevice(key: String) = update(_rules.value.let { it.copy(devices = it.devices - key) })

    fun hideName(name: String) = update(_rules.value.let { it.copy(names = it.names + name) })

    fun unhideName(name: String) = update(_rules.value.let { it.copy(names = it.names - name) })

    fun hidePrefix(prefix: String) = update(_rules.value.let { it.copy(prefixes = it.prefixes + prefix.uppercase()) })

    fun unhidePrefix(prefix: String) = update(_rules.value.let { it.copy(prefixes = it.prefixes - prefix) })

    /** Adds another phone's hidden devices, names and prefixes to these. */
    fun merge(other: Rules) = update(_rules.value.let {
        Rules(other.devices + it.devices, it.names + other.names, it.prefixes + other.prefixes)
    })

    @Synchronized
    private fun update(next: Rules) {
        _rules.value = next
        val sp = prefs ?: return
        sp.edit().apply {
            sp.all.keys.filter { it.startsWith("label:") }.forEach(::remove)
            putStringSet("devices", next.devices.keys)
            next.devices.forEach { (key, label) -> putString("label:$key", label) }
            putStringSet("names", next.names)
            putStringSet("prefixes", next.prefixes)
        }.apply()
    }
}
