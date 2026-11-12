package app.sirenscout

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A signature the user made themselves, e.g. for equipment their local agency uses. */
data class WatchRule(
    val id: String,
    val kind: Kind,
    /** An address, an address prefix ("AA:BB:CC") or a name, depending on [kind]. */
    val value: String,
    val label: String,
    val category: Category,
) {
    enum class Kind(val describe: String) {
        ADDRESS("address is"),
        PREFIX("address starts with"),
        NAME_EXACT("name is"),
        NAME_CONTAINS("name contains"),
    }

    val description get() = "${kind.describe} “$value”"

    fun matches(address: String?, name: String?): Boolean = when (kind) {
        Kind.ADDRESS -> address.equals(value, ignoreCase = true)
        Kind.PREFIX -> address?.startsWith(value, ignoreCase = true) == true
        Kind.NAME_EXACT -> name.equals(value, ignoreCase = true)
        Kind.NAME_CONTAINS -> name?.contains(value, ignoreCase = true) == true
    }

    val signature
        get() = Signature(
            id = "custom-$id",
            label = label,
            category = category,
            confidence = Confidence.MEDIUM,
            sources = setOf(Source.BLE, Source.BT_CLASSIC, Source.WIFI),
            note = "Your own watch rule: $description.",
        )
}

object WatchRules {
    /** Categories a user rule can file a device under. */
    val CATEGORIES = listOf(Category.POLICE, Category.FIRE_EMS, Category.FLEET, Category.SURVEILLANCE)

    private val _rules = MutableStateFlow<List<WatchRule>>(emptyList())
    val rules: StateFlow<List<WatchRule>> = _rules.asStateFlow()
    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) reload(context)
    }

    /** Re-reads the stored rules, e.g. after restoring a backup. */
    @Synchronized
    fun reload(context: Context) {
        val sp = context.applicationContext.getSharedPreferences("watch_rules", Context.MODE_PRIVATE)
        prefs = sp
        _rules.value = runCatching {
            val a = JSONArray(sp.getString("rules", "[]"))
            List(a.length()) { a.getJSONObject(it) }.mapNotNull(::fromJson)
        }.getOrDefault(emptyList())
    }

    fun toJson(r: WatchRule): JSONObject = JSONObject().put("id", r.id).put("kind", r.kind.name).put("value", r.value)
        .put("label", r.label).put("category", r.category.name)

    fun fromJson(j: JSONObject): WatchRule? = runCatching {
        WatchRule(
            id = j.getString("id"),
            kind = WatchRule.Kind.valueOf(j.getString("kind")),
            value = j.getString("value"),
            label = j.getString("label"),
            category = Category.valueOf(j.getString("category")),
        )
    }.getOrNull()

    /** Adds rules from another phone, skipping any this phone already has (same kind and value). Returns the new ones. */
    fun merge(other: List<WatchRule>): List<WatchRule> {
        val current = _rules.value
        val new = other.filter { o -> current.none { it.id == o.id || (it.kind == o.kind && it.value.equals(o.value, ignoreCase = true)) } }
            .distinctBy { it.kind to it.value.lowercase() }
        if (new.isNotEmpty()) save(current + new)
        return new
    }

    /** First rule matching this device, with the reason, if any. */
    fun match(address: String?, name: String?): Pair<Signature, String>? =
        _rules.value.firstOrNull { it.matches(address, name) }
            ?.let { it.signature to "Your watch rule: ${it.description}" }

    fun byId(signatureId: String): Signature? =
        _rules.value.firstOrNull { "custom-${it.id}" == signatureId }?.signature

    fun add(kind: WatchRule.Kind, value: String, label: String, category: Category) =
        save(_rules.value + WatchRule(UUID.randomUUID().toString(), kind, value.trim(), label.trim(), category))

    fun remove(id: String) = save(_rules.value.filterNot { it.id == id })

    @Synchronized
    private fun save(next: List<WatchRule>) {
        _rules.value = next
        val json = JSONArray(next.map(::toJson))
        prefs?.edit()?.putString("rules", json.toString())?.apply()
        AppLog.i("Watch rules: ${next.size}")
    }
}
