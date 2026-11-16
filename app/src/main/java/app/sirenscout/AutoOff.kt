package app.sirenscout

import android.content.Context

/**
 * Some phones (GrapheneOS, for one) switch Bluetooth off by themselves after a while with nothing
 * connected. Siren only listens and never connects, so to the phone it looks unused - and
 * scanning stops. Android doesn't let apps read that timer, so Siren remembers when Bluetooth was
 * switched off under it while scanning, and warns about it.
 */
object AutoOff {
    const val BLUETOOTH_SETTING = "Settings > Connected devices > Connection preferences > Bluetooth > Turn off Bluetooth automatically"
    private const val RECENT_MS = 7 * 24 * 3_600_000L

    /** Called when Bluetooth goes off while Siren is scanning. */
    fun bluetoothTurnedOff(context: Context) {
        prefs(context).edit().putLong("bt_off_at", System.currentTimeMillis())
            .putInt("bt_off_count", prefs(context).getInt("bt_off_count", 0) + 1).apply()
    }

    /** How many times Bluetooth was switched off while scanning in the last week, or 0. */
    fun recentBluetoothOffs(context: Context): Int {
        val p = prefs(context)
        return if (System.currentTimeMillis() - p.getLong("bt_off_at", 0) < RECENT_MS) p.getInt("bt_off_count", 0) else 0
    }

    fun dismiss(context: Context) = prefs(context).edit().remove("bt_off_at").remove("bt_off_count").apply()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("auto_off", Context.MODE_PRIVATE)
}
