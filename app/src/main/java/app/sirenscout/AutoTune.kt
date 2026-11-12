package app.sirenscout

import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.BatteryManager

/**
 * The settings actually in effect. With Auto-optimize on, these are chosen from the phone's
 * current state; with it off, they're the user's own settings.
 */
object AutoTune {
    /** Seconds of warning to aim for before reaching a known plate reader. */
    private const val CAMERA_WARNING_S = 15f
    private const val CAMERA_MIN_M = 150f
    private const val CAMERA_MAX_M = 500f

    fun isCharging(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    /** An RTL2832U-based SDR dongle on the USB port. */
    fun sdrConnected(context: Context): Boolean =
        context.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty()
            .any { it.vendorId == 0x0BDA && it.productId in setOf(0x2832, 0x2838) }

    /** On battery, let the screen sleep and rely on the targeted scan. */
    fun keepScreenOn(context: Context, prefs: Prefs) =
        if (prefs.autoOptimize) isCharging(context) else prefs.keepScreenOn

    fun screenOffScan(prefs: Prefs) = prefs.autoOptimize || prefs.screenOffScan

    fun fastWifi(prefs: Prefs) = prefs.autoOptimize || prefs.fastWifi

    fun bleScanMode(context: Context, prefs: Prefs) =
        if (prefs.autoOptimize && !isCharging(context)) ScanSettings.SCAN_MODE_BALANCED
        else ScanSettings.SCAN_MODE_LOW_LATENCY

    /** Only when there's something to listen with and something to listen for. */
    fun rfEnabled(context: Context, prefs: Prefs): Boolean {
        if (!prefs.autoOptimize) return prefs.rfEnabled
        if (prefs.frequenciesHz().isEmpty() && prefs.bands().isEmpty()) return false
        return !prefs.isLocalSdr || sdrConnected(context)
    }

    /** Far enough ahead to give about 15 seconds of warning at the current speed. */
    fun cameraAlertM(prefs: Prefs, speedMps: Float): Float =
        if (prefs.autoOptimize) (speedMps * CAMERA_WARNING_S).coerceIn(CAMERA_MIN_M, CAMERA_MAX_M)
        else prefs.cameraAlertM
}
