package app.sirenscout

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Which alerts post a notification, and which of those also go to a connected watch. Sounds have
 * their own switches (AlertSound); an alert that doesn't notify still shows in Siren's lists.
 */
object AlertNotify {
    enum class Kind(val label: String) {
        POLICE("Police equipment nearby"),
        FOLLOWING("Police equipment traveling with you"),
        TRACKER("Unknown tracker traveling with you"),
        RADIO("Public-safety radio transmitting"),
        SURVEILLANCE("Flock, Raven and other surveillance sensors nearby"),
        PLATE_READER("Mapped plate reader ahead"),
        SPEED_CAMERA("Speed or red-light camera ahead"),
        AIR("Drones and police aircraft"),
        FLIGHTS("Tracked flight updates"),
        OTHER("Fleet gear and fire / EMS"),
    }

    fun forCategory(category: Category) = when (category) {
        Category.POLICE -> Kind.POLICE
        Category.RADIO -> Kind.RADIO
        Category.TRACKER -> Kind.TRACKER
        Category.SURVEILLANCE -> Kind.SURVEILLANCE
        Category.FIRE_EMS, Category.FLEET -> Kind.OTHER
    }

    fun enabled(context: Context, kind: Kind) =
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getBoolean("notify_${kind.name.lowercase()}", true)

    fun setEnabled(context: Context, kind: Kind, on: Boolean) =
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit().putBoolean("notify_${kind.name.lowercase()}", on).apply()

    fun onWatch(context: Context, kind: Kind) =
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getBoolean("watch_${kind.name.lowercase()}", true)

    fun setOnWatch(context: Context, kind: Kind, on: Boolean) =
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).edit().putBoolean("watch_${kind.name.lowercase()}", on).apply()

    /** Posts [builder] as notification [id] if [kind] notifies; kept off watches unless chosen. */
    fun post(context: Context, kind: Kind, id: Int, builder: NotificationCompat.Builder) {
        if (!enabled(context, kind)) return
        // "Local only" notifications stay on the phone: Wear OS and other bridges don't copy them.
        builder.setLocalOnly(!onWatch(context, kind))
        runCatching { NotificationManagerCompat.from(context).notify(id, builder.build()) }
    }
}
