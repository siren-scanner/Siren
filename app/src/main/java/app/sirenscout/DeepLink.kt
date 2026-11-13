package app.sirenscout

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What a tapped notification is about, so the app opens straight to it: a device's details, a
 * drone or aircraft, a tracked flight, or a place on the map (a plate reader).
 */
sealed class DeepLink {
    data class Device(val key: String) : DeepLink()
    data class Air(val key: String, val title: String) : DeepLink()
    data class Flight(val id: String) : DeepLink()
    data class Place(val lat: Double, val lon: Double, val title: String) : DeepLink()

    fun encode(): String = when (this) {
        is Device -> listOf("device", key)
        is Air -> listOf("air", key, title)
        is Flight -> listOf("flight", id)
        is Place -> listOf("place", lat.toString(), lon.toString(), title)
    }.joinToString(SEP)

    companion object {
        private const val EXTRA = "app.sirenscout.OPEN"
        private const val SEP = "\t"

        /** The link from the last tapped notification, until the screen has opened it. */
        val pending = MutableStateFlow<DeepLink?>(null)

        fun decode(s: String): DeepLink? {
            val p = s.split(SEP)
            return when (p.firstOrNull()) {
                "device" -> p.getOrNull(1)?.let(::Device)
                "air" -> p.getOrNull(1)?.let { Air(it, p.getOrNull(2).orEmpty()) }
                "flight" -> p.getOrNull(1)?.let(::Flight)
                "place" -> {
                    val lat = p.getOrNull(1)?.toDoubleOrNull()
                    val lon = p.getOrNull(2)?.toDoubleOrNull()
                    if (lat == null || lon == null) null else Place(lat, lon, p.getOrNull(3).orEmpty())
                }
                else -> null
            }
        }

        /** Opens the app at [link]. Each link gets its own PendingIntent, so one notification's link never replaces another's. */
        fun intent(context: Context, link: DeepLink): PendingIntent {
            val code = link.encode()
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .setData(Uri.parse("siren://open/" + Uri.encode(code)))
                .putExtra(EXTRA, code)
            return PendingIntent.getActivity(
                context, code.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /** Called with the intent that opened the app. */
        fun handle(intent: Intent?) {
            intent?.getStringExtra(EXTRA)?.let(::decode)?.let { pending.value = it }
        }
    }
}
