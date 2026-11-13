package app.sirenscout

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (requiredPermissions().all(::isGranted)) {
                ensureRequirements(then = ::startScanning)
            } else {
                AppLog.w("Required permissions denied: ${requiredPermissions().filterNot(::isGranted)}")
                Toast.makeText(this, "Bluetooth and location permissions are required to scan", Toast.LENGTH_LONG).show()
            }
        }

    private val sdrDriverLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            launchService()
            if (result.resultCode != RESULT_OK) {
                Detections.setStatus { it.copy(rf = "SDR driver didn't start - is the dongle plugged in?") }
            }
        }

    // --- Turning on Bluetooth, Location, Wi-Fi scanning and battery exemption ---

    private enum class Requirement { BLUETOOTH, LOCATION, WIFI, BATTERY }

    /** Prompts the user declined; not asked again until the next Start. */
    private val declined = mutableSetOf<Requirement>()
    private var pending: Requirement? = null
    private var afterRequirements: (() -> Unit)? = null

    private val requirementLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onRequirementResult() }
    private val locationResolutionLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { onRequirementResult() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hidden.init(this)
        TrackerMutes.init(this)
        WatchRules.init(this)
        LocationMarker.init(this)
        AiGuesser.init(this)
        Flights.load(this)
        FlightWatchService.start(this) // picks up flights tracked before Siren was closed
        // On-device AI is only allowed while the app is on screen, so tie it to the resumed state.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) { AiGuesser.run(this@MainActivity) }
        }
        setContent {
            SirenScoutApp(onStart = ::requestStart, onStop = ::stopScanning)
        }
    }

    override fun onResume() {
        super.onResume()
        // Something may have been switched off while we were away; put it back while scanning.
        if (Detections.status.value.running && pending == null) ensureRequirements(then = {})
    }

    private fun requiredPermissions() = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        // Android 12+ ignores a precise-location request that doesn't also ask for approximate.
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun optionalPermissions() = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun requestStart() {
        declined.clear()
        val missing = (requiredPermissions() + optionalPermissions()).filterNot(::isGranted)
        if (missing.isEmpty()) ensureRequirements(then = ::startScanning)
        else permissionLauncher.launch(missing.toTypedArray())
    }

    /** Asks for each enabled requirement that's off, one at a time, then runs [then]. */
    private fun ensureRequirements(then: () -> Unit) {
        afterRequirements = then
        nextRequirement()
    }

    private fun nextRequirement() {
        val prefs = Prefs(this)
        val next = Requirement.entries.firstOrNull { r ->
            r !in declined && when (r) {
                Requirement.BLUETOOTH -> prefs.autoBluetooth && bluetoothOff()
                Requirement.LOCATION -> prefs.autoLocation && locationOff()
                Requirement.WIFI -> prefs.autoWifi && wifiScanningOff()
                Requirement.BATTERY -> prefs.autoBattery && batteryOptimized()
            }
        }
        pending = next
        if (next == null) {
            afterRequirements?.invoke()
            afterRequirements = null
            return
        }
        try {
            ask(next)
        } catch (e: ActivityNotFoundException) {
            declined += next
            nextRequirement()
        }
    }

    private fun onRequirementResult() {
        val r = pending ?: return
        val stillOff = when (r) {
            Requirement.BLUETOOTH -> bluetoothOff()
            Requirement.LOCATION -> locationOff()
            Requirement.WIFI -> wifiScanningOff()
            Requirement.BATTERY -> batteryOptimized()
        }
        if (stillOff) {
            declined += r
            AppLog.i("User declined turning on ${r.name.lowercase()}")
        }
        nextRequirement()
    }

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is granted before we get here
    private fun ask(r: Requirement) {
        when (r) {
            Requirement.BLUETOOTH -> requirementLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            Requirement.LOCATION -> askLocation()
            Requirement.WIFI -> requirementLauncher.launch(
                if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            )
            Requirement.BATTERY -> @SuppressLint("BatteryLife") requirementLauncher.launch(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
    }

    /** Google's in-app "Turn on location" dialog where available, else the system settings page. */
    private fun askLocation() = LocationPrompt.show(
        this,
        onAlreadyOn = { onRequirementResult() },
        showDialog = { locationResolutionLauncher.launch(it) },
        openSettings = { requirementLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
    )

    private fun bluetoothOff() =
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == false

    private fun locationOff() =
        getSystemService(LocationManager::class.java)?.let { !LocationManagerCompat.isLocationEnabled(it) } ?: false

    /** Android can scan for networks with Wi-Fi off when "Wi-Fi scanning" is on, which is enough. */
    @Suppress("DEPRECATION")
    private fun wifiScanningOff() = applicationContext.getSystemService(WifiManager::class.java)
        ?.let { !it.isWifiEnabled && !it.isScanAlwaysAvailable } ?: false

    private fun batteryOptimized() =
        !getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    // --- Scanning ---

    private fun startScanning() {
        val prefs = Prefs(this)
        if (!AutoTune.rfEnabled(this, prefs) || !prefs.isLocalSdr) {
            launchService()
            return
        }
        // Ask the "RTL2832U driver" app to open the USB dongle and serve rtl_tcp on localhost.
        val uri = "iqsrc://-a 127.0.0.1 -p ${prefs.rtlPort} -s ${RtlTcpMonitor.SAMPLE_RATE}"
        try {
            sdrDriverLauncher.launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (e: ActivityNotFoundException) {
            launchService()
            Detections.setStatus { it.copy(rf = "install the \"RTL2832U driver\" app to use a USB SDR") }
        }
    }

    private fun launchService() {
        ContextCompat.startForegroundService(this, Intent(this, ScanService::class.java))
    }

    private fun stopScanning() {
        stopService(Intent(this, ScanService::class.java))
    }
}
