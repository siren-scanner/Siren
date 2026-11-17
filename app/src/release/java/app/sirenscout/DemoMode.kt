package app.sirenscout

import android.app.Activity
import android.content.Intent

/** Demo data for screenshots exists only in debug builds; release builds do nothing. */
object DemoMode {
    const val active = false

    fun maybeStart(activity: Activity, intent: Intent?) = Unit
}
