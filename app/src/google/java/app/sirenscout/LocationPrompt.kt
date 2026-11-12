package app.sirenscout

import android.app.Activity
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/** Google Play services' in-app "Turn on location" dialog, falling back to the settings page. */
object LocationPrompt {
    fun show(activity: Activity, onAlreadyOn: () -> Unit, showDialog: (IntentSenderRequest) -> Unit, openSettings: () -> Unit) {
        val request = LocationSettingsRequest.Builder()
            .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000).build())
            .setAlwaysShow(true)
            .build()
        LocationServices.getSettingsClient(activity).checkLocationSettings(request)
            .addOnSuccessListener { onAlreadyOn() }
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) showDialog(IntentSenderRequest.Builder(e.resolution).build())
                else openSettings()
            }
    }
}
