package app.sirenscout

import android.app.Activity
import androidx.activity.result.IntentSenderRequest

/** Without Google Play services there's no in-app dialog: open the system's location settings. */
object LocationPrompt {
    fun show(activity: Activity, onAlreadyOn: () -> Unit, showDialog: (IntentSenderRequest) -> Unit, openSettings: () -> Unit) =
        openSettings()
}
