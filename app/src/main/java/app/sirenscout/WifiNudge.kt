package app.sirenscout

import kotlinx.coroutines.channels.Channel

/**
 * Android only allows frequent Wi-Fi scans while the app is on screen. This says whether Siren is,
 * and wakes the Wi-Fi loop to try a fast scan right away when it comes back on screen.
 */
object WifiNudge {
    /** Siren's main screen is showing (resumed). */
    @Volatile var sirenOnScreen = false
        private set

    /** Wakes the Wi-Fi loop for an immediate scan. */
    val wake = Channel<Unit>(Channel.CONFLATED)

    fun shown(on: Boolean) {
        sirenOnScreen = on
        if (on) wake.trySend(Unit)
    }
}
