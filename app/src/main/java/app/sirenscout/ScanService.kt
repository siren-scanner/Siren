package app.sirenscout

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/** Foreground service that runs every scanner and raises alerts on signature matches. */
@SuppressLint("MissingPermission") // MainActivity requests everything before starting us
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var prefs: Prefs
    private var btAdapter: BluetoothAdapter? = null
    private var wifi: WifiManager? = null
    private var started = false

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Scanning", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Emergency vehicle alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                // Siren plays its own alert sounds (AlertSound), so the notification itself is quiet.
                setSound(null, null)
            }
        )
        nm.deleteNotificationChannel("alerts") // the old channel, which played the standard sound
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MARK) {
            // Save the log up to now first, so the moment is complete in History.
            val loc = if (started) currentLocation() else null
            scope.launch(Dispatchers.IO) {
                if (started) runCatching { saveHistory() }
                Marks.add(this@ScanService, Marks.Mark(System.currentTimeMillis(), loc?.latitude, loc?.longitude))
            }
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MUTE_TRACKER) {
            intent.getStringExtra(EXTRA_SIGNATURE)?.let { TrackerMutes.mute(this, it) }
            NotificationManagerCompat.from(this).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true

        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoingNotification(),
            if (Build.VERSION.SDK_INT >= 29) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else 0,
        )
        prefs = Prefs(this)
        if (prefs.recordDrives) drive = Traces.Recorder(this)
        Hidden.init(this)
        TrackerMutes.init(this)
        WatchRules.init(this)
        followDetector = FollowDetector(prefs.followMinutes * 60_000L, prefs.followDistanceM.toDouble())
        trackerDetector = FollowDetector(prefs.trackerMinutes * 60_000L, TRACKER_MIN_DISTANCE_M)
        btAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        Detections.setStatus { ScanStatus(running = true) }
        ScanHealth.started()

        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Classic discovery results come from the Bluetooth app, not the core system, so a
        // not-exported receiver never gets them (Siren saw 0 classic devices for this reason).
        // These are protected broadcasts: only the system and Bluetooth can send them.
        ContextCompat.registerReceiver(
            this, btReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch(Dispatchers.IO) { DeviceGuesser.load(this@ScanService) }
        startLocation()
        CarDisplay.allowWifi = prefs.carOnWifi
        if (prefs.carDisplay) CarDisplay.start(prefs.carCode)
        if (prefs.syncServe) PhoneSync.start(this)
        scope.launch { PhoneSync.loop(this@ScanService) }
        startBle()
        startClassic()
        startWifi()
        startRf()
        startAircraft()
        scope.launch {
            while (isActive) {
                delay(30_000)
                Detections.prune()
                AirWatch.prune()
                withContext(Dispatchers.Main) {
                    followDetector.prune(System.currentTimeMillis())
                    trackerDetector.prune(System.currentTimeMillis())
                }
            }
        }
        // Police vehicles (gear moving together) and Watch this place.
        scope.launch {
            while (isActive) {
                delay(5_000)
                val now = System.currentTimeMillis()
                val items = Detections.items.value.values
                for (g in PoliceCars.update(items, Detections::signal, now)) {
                    val lead = g.members.maxBy { it.rssi }
                    notifyFollowing(
                        lead, "Possible police vehicle", g.label,
                        "These signals rise and fall together, so they're probably in one vehicle: ${g.label}.",
                        kind = AlertNotify.Kind.POLICE_CAR,
                    )
                }
                val newHere = PlaceWatch.update(items, now)
                if (newHere.isNotEmpty() && now - placeWatchNotifiedAt > PLACE_WATCH_NOTIFY_MS) {
                    placeWatchNotifiedAt = now
                    val total = PlaceWatch.state.value?.new?.size ?: newHere.size
                    val d = Detections.items.value[newHere.first().key] ?: items.first()
                    notifyFollowing(
                        d, "New device where you're watching", newHere.joinToString(", ") { it.title },
                        "$total new since Siren learned this place: " + newHere.joinToString(", ") { it.title },
                        sound = AlertSound.Kind.OTHER, kind = AlertNotify.Kind.PLACE_WATCH,
                    )
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HISTORY_FLUSH_MS)
                saveHistory()
            }
        }
        // Not sticky: Android won't let a location foreground service restart from the background.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { SdrDriver.stop() }
        runCatching { drive?.finish() }
        drive = null
        CarDisplay.stop()
        PhoneSync.stop()
        scope.cancel()
        if (started) {
            runCatching { saveHistory() }
            unregisterReceiver(receiver)
            unregisterReceiver(btReceiver)
            unregisterReceiver(screenReceiver)
            stopScreenOffScan()
            runCatching { btAdapter?.bluetoothLeScanner?.stopScan(bleCallback) }
            runCatching { btAdapter?.cancelDiscovery() }
            getSystemService(LocationManager::class.java)?.removeUpdates(locationListener)
        }
        Detections.setStatus { ScanStatus(running = false) }
        ScanHealth.stopped()
        super.onDestroy()
    }

    // --- Bluetooth Low Energy ---

    private val bleCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onBle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onBle)
        override fun onScanFailed(errorCode: Int) {
            ScanHealth.bleFailed("error $errorCode")
            Detections.setStatus { it.copy(ble = "scan failed (error $errorCode)") }
        }
    }

    private fun onBle(r: ScanResult) {
        ScanHealth.ble()
        val record = r.scanRecord
        val droneData = record?.serviceData?.get(REMOTE_ID_UUID)?.takeIf { RemoteId.fromBle(it) != null }
        droneData?.let { data -> onRemoteId(r.device.address, r.rssi) { RemoteId.fromBle(data, it) } }
        val name = record?.deviceName ?: runCatching { r.device.name }.getOrNull()
        val manufacturer = record?.manufacturerSpecificData?.let { d -> (0 until d.size()).associate { d.keyAt(it) to d.valueAt(it) } }.orEmpty()
        val services = (record?.serviceUuids.orEmpty() + record?.serviceData?.keys.orEmpty())
            .map { it.uuid.toString().lowercase() }
        val tracker = Trackers.classify(
            manufacturer,
            record?.serviceData.orEmpty().mapNotNull { (k, v) -> DeviceGuesser.uuid16(k)?.let { it to v } }.toMap(),
            record?.serviceUuids.orEmpty().mapNotNull(DeviceGuesser::uuid16).toSet(),
        )
        sighting(Source.BLE, r.device.address, name, r.rssi, manufacturer.keys.toList(), services, tracker, DeviceGuesser.summary(r), payload = record?.bytes) {
            TeslaKey.guess(name) ?: (if (droneData != null) RemoteId.LABEL else null) ?: DeviceGuesser.ble(r)
        }
    }

    /** Wakes the BLE loop to restart the scan right away, e.g. when the finder opens or closes. */
    private val bleRestart = Channel<Unit>(Channel.CONFLATED)

    private fun startBle() = scope.launch {
        launch { Detections.finding.drop(1).collect { bleRestart.trySend(Unit) } }
        while (isActive) {
            // Picked on every restart, so plugging in or unplugging takes effect within minutes.
            // Finding a device always gets the fastest mode, whatever the battery.
            val mode = if (Detections.finding.value != null) ScanSettings.SCAN_MODE_LOW_LATENCY
            else AutoTune.bleScanMode(this@ScanService, prefs)
            val settings = ScanSettings.Builder().setScanMode(mode).apply {
                // Also hear Bluetooth 5 long-range broadcasts, which many drones use for Remote ID.
                if (btAdapter?.isLeCodedPhySupported == true) setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }.build()
            val scanner = btAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            if (scanner == null) {
                Detections.setStatus { it.copy(ble = "Bluetooth is off") }
                delay(5_000)
                continue
            }
            scanner.startScan(null, settings, bleCallback)
            Detections.setStatus { it.copy(ble = if (screenOffScanning) "targeted scan (screen off)" else "scanning") }
            // Android quietly downgrades unfiltered scans after 30 minutes, so restart regularly.
            withTimeoutOrNull(BLE_RESTART_MS) { bleRestart.receive() }
            runCatching { scanner.stopScan(bleCallback) }
            delay(1_000)
        }
    }

    // --- Screen-off targeted BLE scan ---

    // Android stops delivering unfiltered BLE results while the screen is off, but filtered scans
    // keep running. Filters can only match exact values, so this covers manufacturer IDs, service
    // UUIDs and exact names - not name patterns or MAC prefixes.
    private var screenOffScanning = false

    private val screenOffCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onBle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onBle)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (AutoTune.screenOffScan(prefs)) startScreenOffScan()
                Intent.ACTION_SCREEN_ON -> {
                    stopScreenOffScan()
                    WifiNudge.wake.trySend(Unit) // try a fast Wi-Fi scan now that the screen is on
                }
            }
        }
    }

    private fun screenOffFilters(): List<ScanFilter> = buildList {
        val sigs = Signatures.BUILTIN
        for (id in sigs.flatMap { it.manufacturerIds }.distinct()) {
            add(ScanFilter.Builder().setManufacturerData(id, ByteArray(0)).build())
        }
        for (uuid in sigs.flatMap { it.serviceUuids }.distinct().map(ParcelUuid::fromString)) {
            add(ScanFilter.Builder().setServiceUuid(uuid).build())
            add(ScanFilter.Builder().setServiceData(uuid, ByteArray(0)).build())
        }
        for (name in SCREEN_OFF_NAMES) add(ScanFilter.Builder().setDeviceName(name).build())
        add(ScanFilter.Builder().setServiceData(REMOTE_ID_UUID, ByteArray(0)).build()) // drones
        // Item trackers: separated Find My devices, and the trackers' own service IDs.
        add(ScanFilter.Builder().setManufacturerData(0x004C, Trackers.appleFilterData, byteArrayOf(-1, -1)).build())
        for (u in Trackers.filterUuids16.map { ParcelUuid.fromString("%08x-0000-1000-8000-00805f9b34fb".format(it)) }) {
            add(ScanFilter.Builder().setServiceUuid(u).build())
            add(ScanFilter.Builder().setServiceData(u, ByteArray(0)).build())
        }
    }

    private fun startScreenOffScan() {
        val scanner = btAdapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        runCatching { scanner.startScan(screenOffFilters(), settings, screenOffCallback) }
            .onSuccess {
                screenOffScanning = true
                Detections.setStatus { it.copy(ble = "targeted scan (screen off)") }
            }
    }

    private fun stopScreenOffScan() {
        if (!screenOffScanning) return
        screenOffScanning = false
        runCatching { btAdapter?.bluetoothLeScanner?.stopScan(screenOffCallback) }
        Detections.setStatus { it.copy(ble = "scanning") }
    }

    // --- Classic Bluetooth discovery ---

    private fun startClassic() = scope.launch {
        while (isActive) {
            val adapter = btAdapter
            Detections.setStatus {
                it.copy(
                    classic = when {
                        adapter == null || !adapter.isEnabled -> "Bluetooth is off"
                        adapter.isDiscovering || adapter.startDiscovery() -> "discovering"
                        else -> "discovery refused"
                    }
                )
            }
            // A discovery pass takes ~12 s and slows BLE scanning, so leave gaps between passes.
            delay(CLASSIC_INTERVAL_MS)
        }
    }

    // --- Wi-Fi ---

    /** When Android last refused a scan; fast scans are retried a while after. */
    private var wifiRefusedAt = -1L
    private var wifiEverySeconds = WIFI_INTERVAL_MS / 1000

    private fun startWifi() = scope.launch {
        val w = wifi ?: return@launch
        // Fast scans that went through in a row. Throttled phones refuse the 5th within 2 minutes,
        // so more than that proves throttling is off, even where the setting can't be read.
        var fastStreak = 0
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            // Re-checked every pass, so flipping the Developer option takes effect without a restart.
            val fast = AutoTune.fastWifi(prefs) && (wifiThrottlingOff(this@ScanService) ||
                WifiThrottle.detectedOff || wifiRefusedAt < 0 || now - wifiRefusedAt > FAST_WIFI_RETRY_MS)
            @Suppress("DEPRECATION") // still the only way to request a scan
            val requested = w.startScan()
            if (requested) {
                fastStreak = if (fast) fastStreak + 1 else 0
                if (fastStreak > 4) WifiThrottle.detectedOff = true
            } else {
                fastStreak = 0
                // Android refuses scans from apps that aren't on screen whatever the throttling setting
                // (screen off, the lock screen, another app in front), so only a refusal while Siren is
                // showing says fast scans aren't allowed on this phone. Counting the others started a
                // 10-minute wait each time the screen came on at the lock screen.
                val screenOn = getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
                if (screenOn && WifiNudge.sirenOnScreen) {
                    wifiRefusedAt = now
                    if (fast) WifiThrottle.detectedOff = false
                }
                ScanHealth.wifiRefused()
                Detections.setStatus { it.copy(wifi = "scan throttled; using passive results") }
            }
            wifiEverySeconds = (if (fast && requested) FAST_WIFI_INTERVAL_MS else WIFI_INTERVAL_MS) / 1000
            // Android allows 4 scans per 2 minutes for a foreground app unless throttling is off.
            kotlinx.coroutines.withTimeoutOrNull(wifiEverySeconds * 1000) { WifiNudge.wake.receive() }
        }
    }

    /** Fake Wi-Fi addresses flagged recently: why, and when. */
    private val flaggedTwins = HashMap<String, Pair<String, Long>>()

    private fun onWifiResults() {
        flaggedTwins.values.removeAll { System.currentTimeMillis() - it.second > TWIN_MEMORY_MS }
        val results = runCatching { wifi?.scanResults }.getOrNull() ?: return
        ScanHealth.wifiResults()
        val nowMicros = SystemClock.elapsedRealtime() * 1000
        val fresh = results.filter { nowMicros - it.timestamp < 60_000_000 }
        // Fake copies of networks (evil twins), judged across the whole scan.
        val twins = runCatching {
            @Suppress("DEPRECATION")
            val conn = wifi?.connectionInfo
            @Suppress("DEPRECATION")
            val connected = conn?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                ?.let { s -> conn.bssid?.takeIf { it != "02:00:00:00:00:00" }?.let { s to it } }
            EvilTwin.find(
                fresh.mapNotNull { r ->
                    @Suppress("DEPRECATION")
                    val s = r.SSID?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                    r.BSSID?.let { EvilTwin.Ap(it, s, EvilTwin.secure(r.capabilities)) }
                },
                connected, DeviceGuesser::ouiVendor,
            )
        }.getOrDefault(emptyMap())
        for (r in fresh) {
            @Suppress("DEPRECATION")
            val ssid = r.SSID?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            val bssid = r.BSSID ?: continue
            WifiBands.record(bssid, r.frequency)
            var drone = false
            if (Build.VERSION.SDK_INT >= 30) {
                // Drones can also send Remote ID inside Wi-Fi beacons, as a vendor-specific element.
                for (e in r.informationElements) {
                    if (e.id != 221) continue
                    val bytes = ByteArray(e.bytes.remaining()).also { e.bytes.duplicate().get(it) }
                    if (RemoteId.fromWifiElement(bytes) == null) continue
                    drone = true
                    onRemoteId(bssid, r.level) { RemoteId.fromWifiElement(bytes, it) }
                }
            }
            // Once flagged, a fake stays flagged for a while, even on scans that miss the network it copies.
            val now = System.currentTimeMillis()
            twins[bssid]?.let { flaggedTwins[bssid] = it to now }
            val twin = flaggedTwins[bssid]?.takeIf { now - it.second < TWIN_MEMORY_MS }?.first
            sighting(Source.WIFI, bssid, ssid, r.level, forced = twin?.let { EvilTwin.SIGNATURE to it }) {
                if (drone) RemoteId.LABEL
                else DeviceGuesser.wifi(bssid, ssid).let { g -> JokeNames.label(ssid, g) ?: g }
            }
        }
        Detections.setStatus { it.copy(wifi = "${fresh.size} networks in range · every ${wifiEverySeconds}s") }
    }

    /** Bluetooth's broadcasts, handled by [receiver]. */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = receiver.onReceive(context, intent)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    // Scanning picks up again by itself when Bluetooth comes back; until then, say so.
                    BluetoothAdapter.STATE_OFF -> notifyBluetoothOff()
                    BluetoothAdapter.STATE_ON -> NotificationManagerCompat.from(this@ScanService).cancel(BT_OFF_ID)
                }
                BluetoothDevice.ACTION_FOUND -> {
                    ScanHealth.classic()
                    val device = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
                    ) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        .toInt().takeIf { it != Short.MIN_VALUE.toInt() } ?: -127
                    val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        ?: runCatching { device.name }.getOrNull()
                    val btClass = IntentCompat.getParcelableExtra(
                        intent, BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java
                    ) ?: runCatching { device.bluetoothClass }.getOrNull()
                    sighting(Source.BT_CLASSIC, device.address, name, rssi) { DeviceGuesser.classic(device.address, btClass) }
                }
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> onWifiResults()
            }
        }
    }

    // --- RF via RTL-SDR ---

    private fun startRf() {
        if (!AutoTune.rfEnabled(this, prefs)) {
            Detections.setStatus {
                it.copy(rf = if (prefs.autoOptimize && (prefs.frequenciesHz().isNotEmpty() || prefs.bands().isNotEmpty())) "off (no SDR dongle plugged in)" else "off")
            }
            return
        }
        val monitor = RtlTcpMonitor(
            host = prefs.rtlHost,
            port = prefs.rtlPort,
            freqsHz = prefs.frequenciesHz(),
            gainTenthsDb = prefs.rfGainTenthsDb,
            thresholdDb = prefs.rfThresholdDb,
            onStatus = { s -> Detections.setStatus { it.copy(rf = s) } },
            onCarrier = { hz, level, above ->
                val id = "%.4f MHz".format(Locale.US, hz / 1e6)
                val detail = "carrier +%.1f dB over noise floor".format(Locale.US, above)
                if (!Hidden.rules.value.hides(Source.RF, id, null)) {
                    Detections.record(
                        Source.RF, id, detail, level.roundToInt(), Signatures.RF_CARRIER,
                        alertEligible = true, location = currentLocation(),
                        reason = "Signal rose %.1f dB above the background on a frequency you're watching".format(Locale.US, above),
                    )?.let(::alert)
                }
            },
            bands = prefs.bands(),
            bandThresholdDb = prefs.bandThresholdDb,
            tireSensors = prefs.tireSensors,
            onTireFrame = ::onTireFrame,
            // The built-in driver serves a plugged-in dongle on localhost (else the driver app does).
            // Checked before every connection, so a dongle that dropped out (a loose cable) comes back.
            beforeConnect = {
                if (prefs.isLocalSdr && SdrDriver.available && SdrDriver.findDongle(this) != null) SdrDriver.start(this, prefs.rtlPort)
                else null
            },
            handshake = { if (prefs.isLocalSdr && SdrDriver.running) SdrDriver.token else null },
            onBandHit = { band, hit ->
                // One entry per band: trunked systems hop channels between calls, so per-channel
                // entries would turn one conversation into a stream of separate alerts.
                val mhz = "%.4f MHz".format(Locale.US, hit.channelHz / 1e6)
                if (!Hidden.rules.value.hides(Source.RF, band.label, null)) {
                    Detections.record(
                        Source.RF, band.label, "$mhz · +%.0f dB".format(Locale.US, hit.aboveDb),
                        hit.levelDb.roundToInt(), band.signature,
                        alertEligible = true, location = currentLocation(),
                        reason = "Transmission on $mhz, %.0f dB above that channel's usual level, in a band only radios transmit on".format(Locale.US, hit.aboveDb),
                    )?.let(::alert)
                }
            },
        )
        scope.launch { monitor.run() }
    }

    // --- Tire sensors (car following) ---

    /** When this scanning session started: tire sensors heard in its first minutes may be your own car's. */
    private val sessionStartedAt = System.currentTimeMillis()
    private val tireWatch by lazy { TireWatch(TireStarts.map(this), sessionStartedAt) }

    /** A tire-sensor message heard on 315 MHz (called from the radio thread). */
    @Synchronized
    private fun onTireFrame(frame: Tpms.Frame) {
        val now = System.currentTimeMillis()
        val loc = currentLocation()
        val before = TireStarts.map(this).toMap()
        val (confirmed, follow) = tireWatch.heard(frame.id, now, loc?.latitude, loc?.longitude, odometer.meters)
        if (TireStarts.map(this) != before) TireStarts.save(this)
        val sensor = tireWatch.sensors[frame.id] ?: return
        if (!sensor.confirmed) return
        val id = "Tire sensor ${frame.id}"
        if (Hidden.rules.value.hides(Source.RF, id, null)) return
        val own = tireWatch.isOwn(frame.id)
        Detections.record(
            Source.RF, id, if (own) "Your car's tire sensor" else "Car tire sensor (315 MHz)", frame.levelDb.roundToInt(), null,
            alertEligible = false, location = loc, guess = "Tire-pressure sensor · 315 MHz", odometerM = odometer.meters,
        )
        if (confirmed != null) AppLog.i("Tire sensor heard: ${frame.id}${if (own) " (your car)" else ""}")
        follow?.let { f ->
            val d = Detections.items.value["RF|$id"] ?: return@let
            val miles = formatShortDistance(f.distanceM)
            notifyFollowing(
                d, "A car may be following you",
                "Its tire sensors were with you for ${f.durationMs / 60_000} min over $miles",
                "The same car's tire-pressure sensors (${f.sensors.joinToString { it.id }}) were heard with you for " +
                    "${f.durationMs / 60_000} minutes over $miles, at ${f.sensors.maxOf { it.cells.size }} different places. " +
                    "Cars driving the same road at the same speed can do this too.",
            )
        }
    }

    // --- Matching and alerts ---

    /** Addresses whose name matched a hidden name, so their nameless packets are dropped too. */
    private val hiddenByName = HashSet<String>()

    private fun isHidden(source: Source, id: String, name: String?): Boolean {
        val key = "${source.name}|$id"
        val rules = Hidden.rules.value
        if (key in hiddenByName) {
            if (name == null || name in rules.names) return true
            hiddenByName.remove(key) // the name was unhidden or the address got reused
        }
        if (name != null && name in rules.names) {
            hiddenByName.add(key)
            Detections.remove(key) // drop any nameless entry recorded before the name showed up
            return true
        }
        return rules.hides(source, id, name)
    }

    private fun sighting(
        source: Source,
        address: String,
        name: String?,
        rssi: Int,
        manufacturerIds: List<Int> = emptyList(),
        serviceUuids: List<String> = emptyList(),
        tracker: Signature? = null,
        adSummary: String? = null,
        /** A finding about this scan as a whole (a fake Wi-Fi copy), which wins over everything else. */
        forced: Pair<Signature, String>? = null,
        /** The raw BLE broadcast, for text hidden in it. */
        payload: ByteArray? = null,
        guess: () -> String?,
    ) {
        // A fake copy of your network uses your network's name, so hiding that name mustn't hide it;
        // hiding its address (Hide > this device) still does.
        if (if (forced != null) Hidden.rules.value.hidesAddress(source, address) else isHidden(source, address, name)) return
        // Your own rules win over built-in signatures, so you can relabel or catch local gear.
        val (sig, reason) = forced ?: tracker?.let { it to Trackers.REASONS[it.id] }
            ?: WatchRules.match(address, name)
            ?: Signatures.matchWithReason(source, address, name, manufacturerIds, serviceUuids, payload)
            ?: (null to null)
        val now = System.currentTimeMillis()
        if (VehicleRouters.isRouter(sig)) VehicleRouters.noteSighting(this, address, currentLocation(), now)
        Detections.record(
            source, address, name, rssi, sig,
            // Trackers pass by constantly in other people's bags; they only alert when they follow you.
            // Vehicle routers are in buses and shops too, so they need backing up (VehicleRouters).
            // Wi-Fi level 0 means Android gave no reading, not a very strong signal.
            alertEligible = sig != null && sig.category != Category.TRACKER && rssi >= prefs.rssiThreshold &&
                !Crowds.crowded(sig, Detections.items.value.values, now) &&
                (source != Source.WIFI || rssi < 0) &&
                VehicleRouters.mayAlert(
                    this, sig, address, name ?: Detections.items.value["${source.name}|$address"]?.name,
                    Detections.items.value.values, now,
                ),
            location = currentLocation(),
            guess = guess(),
            reason = reason,
            odometerM = odometer.meters,
            adSummary = adSummary,
        )?.let(::alert)
        when (sig?.category) {
            Category.POLICE -> checkFollowing(source, address)
            Category.TRACKER -> checkTracker(source, address)
            else -> {}
        }
    }

    // --- "Following you" detection ---

    private lateinit var followDetector: FollowDetector

    private fun checkFollowing(source: Source, address: String) {
        if (!prefs.followAlerts) return
        val d = Detections.items.value["${source.name}|$address"] ?: return
        val follow = followDetector.sighting(
            d.uid, d.firstSeen, d.lastSeen, odometer.meters, (d.spreadM ?: 0.0) >= WITH_YOU_MIN_SPREAD_M,
        ) ?: return
        val minutes = follow.durationMs / 60_000
        notifyFollowing(
            d, "Police equipment is traveling with you",
            "${d.signature?.label} for $minutes min over ${formatDistance(follow.distanceM)}",
            "${d.signature?.label} (${d.name ?: d.id}) has stayed near you for $minutes minutes " +
                "while you traveled ${formatDistance(follow.distanceM)}. Last signal ${d.rssi} dBm.",
        )
    }

    // --- Unknown trackers traveling with you ---

    private lateinit var trackerDetector: FollowDetector
    /** Tracker encounters that raised an alert; only these are kept in history. */
    private val alertedTrackers = HashSet<String>()

    private fun checkTracker(source: Source, address: String) {
        if (!prefs.trackerAlerts) return
        val d = Detections.items.value["${source.name}|$address"] ?: return
        val sigId = d.signature?.id ?: return
        if (TrackerMutes.isMuted(sigId)) return
        val follow = trackerDetector.sighting(
            d.uid, d.firstSeen, d.lastSeen, odometer.meters, (d.spreadM ?: 0.0) >= WITH_YOU_MIN_SPREAD_M,
        ) ?: return
        alertedTrackers += d.uid
        // One alert per tracker type per half hour, however often a device changes its address.
        val now = System.currentTimeMillis()
        if (now - (lastTrackerAlert[sigId] ?: 0L) < TRACKER_TYPE_COOLDOWN_MS) return
        lastTrackerAlert[sigId] = now
        val minutes = follow.durationMs / 60_000
        val what = d.signature?.label ?: "Tracker"
        AppLog.i("Tracker alert: $what for $minutes min")
        notifyFollowing(
            d, "Unknown tracker traveling with you",
            "$what for $minutes min over ${formatDistance(follow.distanceM)}",
            "$what has stayed with you for $minutes minutes while you traveled " +
                "${formatDistance(follow.distanceM)}. If it isn't yours, it may be hidden in your bag or " +
                "vehicle. Tap a tracker in Siren's list for a live signal meter to find it. " +
                "If it's yours, tap \u201cIt's mine\u201d to mute this kind of tracker for 24 hours.",
            action = muteAction(sigId, d, what),
            sound = AlertSound.Kind.TRACKER,
            kind = AlertNotify.Kind.TRACKER,
        )
    }

    private val lastTrackerAlert = HashMap<String, Long>()

    private fun muteAction(sigId: String, d: Detection, what: String): NotificationCompat.Action {
        val intent = Intent(this, ScanService::class.java)
            .setAction(ACTION_MUTE_TRACKER)
            .putExtra(EXTRA_SIGNATURE, sigId)
            .putExtra(EXTRA_NOTIFICATION_ID, followNotificationId(d))
        val pending = PendingIntent.getService(this, sigId.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Action(0, "It's mine (mute ${what.substringBefore(' ')} 24 h)", pending)
    }

    private fun followNotificationId(d: Detection) = FOLLOW_ID_BASE + Math.floorMod(d.key.hashCode(), 1000)

    private fun notifyFollowing(
        d: Detection,
        title: String,
        text: String,
        details: String,
        action: NotificationCompat.Action? = null,
        sound: AlertSound.Kind = AlertSound.Kind.POLICE,
        kind: AlertNotify.Kind = AlertNotify.Kind.FOLLOWING,
    ) {
        AlertSound.play(this, sound)
        CarDisplay.publishAlert(title, text)
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(DeepLink.intent(this, DeepLink.Device(d.key)))
            .setGroup("siren-" + DeepLink.Device(d.key).encode())
            .setAutoCancel(true)
            .apply { action?.let(::addAction) }
        AlertNotify.post(this, kind, followNotificationId(d), n)
    }

    // --- History ---

    /** lastSeen of each encounter as of its last save, so unchanged rows aren't rewritten. */
    private val savedAt = HashMap<String, Long>()

    @Synchronized
    private fun saveHistory() {
        val dirty = Detections.items.value.values.filter { d ->
            // Trackers pass by constantly; keep the ones that alerted, or stayed around long enough to
            // matter (so their "Seen before" history exists), not every AirTag in passing traffic.
            val keep = d.signature != null &&
                (d.signature.category != Category.TRACKER || d.uid in alertedTrackers || d.count >= TRACKER_KEEP_SIGHTINGS)
            keep && savedAt[d.uid] != d.lastSeen
        }.map { d -> Detections.estimate(d)?.let { d.copy(estimate = it) } ?: d }
        History.save(this, dirty)
        dirty.forEach { savedAt[it.uid] = it.lastSeen }
    }

    // --- Location, for placing detections on the map ---

    @Volatile private var lastLocation: Location? = null

    private val odometer = Odometer()

    /** This session's route, when recording drives is on. */
    private var drive: Traces.Recorder? = null

    private val locationListener = LocationListener { loc ->
        if (loc.provider == LocationManager.GPS_PROVIDER) {
            odometer.add(loc.latitude, loc.longitude, loc.accuracy, loc.takeIf { it.hasSpeed() }?.speed, loc.time)
            drive?.add(loc)
        }
        val prev = lastLocation
        // Prefer a fresh GPS fix, but don't let a coarse network fix replace a recent precise one.
        if (prev == null || loc.time - prev.time > 10_000 || loc.accuracy <= prev.accuracy) lastLocation = loc
        lastLocation?.let { SearchTogether.myLat = it.latitude; SearchTogether.myLon = it.longitude }
        checkKnownCameras(loc)
        checkPoliceData(loc)
    }

    // --- Police call data from cities' open data ---

    private val hotspotAlertedAt = HashMap<String, Long>()
    private var lastPoliceRefresh = 0L

    private fun checkPoliceData(loc: Location) {
        if (!prefs.policeData || loc.accuracy > 100f) return
        val now = System.currentTimeMillis()
        if (now - lastPoliceRefresh > 60_000) {
            lastPoliceRefresh = now
            val live = prefs.liveCalls
            scope.launch(Dispatchers.IO) { PoliceData.refreshAround(this@ScanService, loc.latitude, loc.longitude, live) }
        }
        if (!prefs.hotspotAlerts && !prefs.crashAlerts) return
        // Only while driving: "ahead" needs a direction, and walkers don't get pulled over.
        val heading = loc.takeIf { it.hasBearing() && it.speed >= 5f }?.bearing ?: return
        val all = PoliceData.hotspots.value.values.flatten()
            .filter { if (it.isCrashes) prefs.crashAlerts else prefs.hotspotAlerts }
        if (all.isEmpty()) return
        val radius = AutoTune.cameraAlertM(prefs, loc.speed).toDouble().coerceAtLeast(200.0)
        val ahead = PoliceData.hotspotsAhead(all.filter { !it.isCrashes }, loc.latitude, loc.longitude, heading, radius, HOTSPOT_ALERT_MIN) +
            PoliceData.hotspotsAhead(all.filter { it.isCrashes }, loc.latitude, loc.longitude, heading, radius, CRASH_ALERT_MIN)
        val (spot, distance) = ahead.sortedBy { it.second }
            .firstOrNull { now - (hotspotAlertedAt[it.first.id] ?: 0L) > HOTSPOT_REALERT_MS } ?: return
        hotspotAlertedAt[spot.id] = now
        val title = (if (spot.isCrashes) "Crash hotspot ahead · " else "Traffic-stop hotspot ahead · ") + formatShortDistance(distance)
        val text = "${spot.count} ${spot.what} here ${spot.period}" + (spot.peak?.let { ", most around $it" } ?: "")
        CarDisplay.publishAlert(title, text)
        AlertSound.play(this, AlertSound.Kind.SPEED_CAMERA)
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$text. From " + (PoliceData.feeds.value.firstOrNull { it.id == spot.feedId }?.source ?: "published public data") + "."
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(DeepLink.intent(this, DeepLink.Place(spot.lat, spot.lon, if (spot.isCrashes) "Crash hotspot" else "Traffic-stop hotspot")))
            .setGroup("siren-hotspot-" + spot.id)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
        AlertNotify.post(this, if (spot.isCrashes) AlertNotify.Kind.CRASH_HOTSPOT else AlertNotify.Kind.HOTSPOT, HOTSPOT_ID_BASE + Math.floorMod(spot.id.hashCode(), 1000), n)
    }

    // --- Known plate readers, speed and red-light cameras (OpenStreetMap) ---

    private val cameraAlertedAt = HashMap<Long, Long>()
    private var lastCameraRefresh = 0L

    private fun checkKnownCameras(loc: Location) {
        if (!prefs.knownCameras || loc.accuracy > 50f) return
        val now = System.currentTimeMillis()
        if (now - lastCameraRefresh > 60_000) {
            lastCameraRefresh = now
            // On the highway, also load the area ahead (~20 miles on), so cameras are there before
            // you are - even if the map servers are slow or there's a dead zone on the way.
            val ahead = loc.takeIf { it.hasBearing() && it.speed >= LOOKAHEAD_MIN_SPEED_MPS }
                ?.let { pointAhead(it.latitude, it.longitude, it.bearing, LOOKAHEAD_M) }
            scope.launch(Dispatchers.IO) {
                KnownCameras.refreshAround(this@ScanService, loc.latitude, loc.longitude)
                ahead?.let { (lat, lon) -> KnownCameras.refreshAround(this@ScanService, lat, lon) }
            }
        }
        // Only alert while moving: "ahead" means nothing without a direction of travel.
        val heading = loc.takeIf { it.hasBearing() && it.speed >= 2.5f }?.bearing ?: return
        val ahead = camerasAhead(
            KnownCameras.cameras.value.wanted(prefs.speedCameras), loc.latitude, loc.longitude, heading,
            AutoTune.cameraAlertM(prefs, loc.speed).toDouble(),
        )
        for ((cam, distance) in ahead) {
            if (now - (cameraAlertedAt[cam.id] ?: 0L) < CAMERA_REALERT_MS) continue
            cameraAlertedAt[cam.id] = now
            CarDisplay.publishAlert("${cam.label} ahead · ${formatShortDistance(distance)}", facingText(cam, heading))
            AlertSound.play(this, if (cam.kind == CameraKind.PLATE_READER) AlertSound.Kind.CAMERA else AlertSound.Kind.SPEED_CAMERA)
            val n = NotificationCompat.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_siren)
                .setContentTitle("${cam.label} ahead · ${formatShortDistance(distance)}")
                .setContentText(facingText(cam, heading).replaceFirstChar(Char::uppercase))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        facingText(cam, heading).replaceFirstChar(Char::uppercase) +
                            (cam.operator?.let { ". Operated by $it." } ?: ".") +
                            " Location from ${cam.source}."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setContentIntent(DeepLink.intent(this, DeepLink.Place(cam.lat, cam.lon, cam.label)))
            .setGroup("siren-" + DeepLink.Place(cam.lat, cam.lon, cam.label).encode())
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
            AlertNotify.post(
                this, if (cam.kind == CameraKind.PLATE_READER) AlertNotify.Kind.PLATE_READER else AlertNotify.Kind.SPEED_CAMERA,
                CAMERA_ID_BASE + Math.floorMod(cam.id, 1000L).toInt(), n,
            )
        }
    }

    private fun startLocation() {
        val lm = getSystemService(LocationManager::class.java) ?: return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider !in lm.allProviders) continue
            runCatching { lm.getLastKnownLocation(provider)?.let(locationListener::onLocationChanged) }
            runCatching { lm.requestLocationUpdates(provider, 2_000L, 0f, locationListener, Looper.getMainLooper()) }
        }
    }

    // --- Drones and police aircraft ---

    private fun onRemoteId(transmitter: String, rssi: Int, parse: (RemoteId.Drone) -> RemoteId.Drone?) {
        if (!prefs.airDrones) return
        val me = currentLocation()
        AirWatch.onRemoteId(transmitter, rssi, parse, me?.latitude, me?.longitude)?.let { notifyAir(it) }
    }

    /** Asks the ADS-B feed for law-enforcement aircraft around you every so often, while that's on. */
    private fun startAircraft() = scope.launch(Dispatchers.IO) {
        while (isActive) {
            val me = recentLocation()
            val clock = java.time.LocalTime.now().withNano(0).withSecond(0)
            when {
                !prefs.airAircraft -> AirWatch.feedStatus.value = "Police aircraft check is off (Settings > Drones & aircraft)."
                me == null -> AirWatch.feedStatus.value = "Waiting for a location fix before checking for police aircraft."
                // A lookup can stall (a connection opened as Wi-Fi comes up); give up after a minute
                // rather than leave the check stuck for good.
                else -> runCatching {
                    withTimeout(60_000) { runInterruptible { Adsb.fetch(this@ScanService, me.latitude, me.longitude) } }
                }
                    .onSuccess { found ->
                        val police = found.count { it.second?.police == true }
                        val possible = found.count { it.second?.police == false }
                        AirWatch.feedStatus.value = "Checked at $clock: ${found.size} aircraft within about 35 mi - $police law enforcement, $possible government (possible police / first responder)."
                        AirWatch.onAircraft(found, me.latitude, me.longitude, prefs.airAlertMiles * 1609.34, prefs.airShowAll)
                            .forEach { withContext(Dispatchers.Main) { notifyAir(it) } }
                    }
                    .onFailure {
                        AirWatch.feedStatus.value = "Couldn't reach the aircraft feed at $clock (${it.javaClass.simpleName}). Is mobile data on?"
                        AppLog.w("Police aircraft feed: ${it.javaClass.simpleName}")
                    }
            }
            delay(AIRCRAFT_POLL_MS)
        }
    }

    private fun notifyAir(c: AirContact) {
        val away = c.distanceM?.let { formatShortDistance(it) + " away" }
        val title = when (c.kind) {
            AirContact.Kind.DRONE -> "Drone nearby"
            AirContact.Kind.AIRCRAFT, AirContact.Kind.OTHER_AIRCRAFT, AirContact.Kind.TRACKED_FLIGHT -> "Police aircraft nearby: ${c.title}"
            AirContact.Kind.POSSIBLE_AIRCRAFT -> "Possible police / first-responder aircraft: ${c.title}"
        }
        val text = listOfNotNull(away, c.altitudeText, c.headingDeg?.let { "heading ${compass(it.toFloat())}" }).joinToString(" · ")
            .ifEmpty { c.detail }
        AlertSound.play(this, AlertSound.Kind.AIR)
        CarDisplay.publishAlert(title, text)
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${c.detail}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(DeepLink.intent(this, DeepLink.Air(c.key, c.title)))
            .setGroup("siren-" + DeepLink.Air(c.key, c.title).encode())
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
        AlertNotify.post(this, AlertNotify.Kind.AIR, AIR_ID_BASE + Math.floorMod(c.key.hashCode(), 1000), n)
    }

    /**
     * Where you were in the last half hour: enough for the aircraft check, which only sends your
     * area rounded to 10 km anyway. Indoors, GPS can go minutes without a fresh fix.
     */
    private fun recentLocation(): Location? =
        lastLocation?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }

    /** Bluetooth went off while scanning (often the phone's own auto-off): tell the user, tap to turn it back on. */
    private fun notifyBluetoothOff() {
        AppLog.w("Bluetooth was turned off while scanning")
        AutoOff.bluetoothTurnedOff(this)
        val text = "Siren can't hear Bluetooth devices until it's back on. Tap to turn it on."
        val more = " If you didn't switch it off, your phone may do it by itself after a few minutes with nothing " +
            "connected - Siren only listens, so it doesn't count. To stop that (on GrapheneOS and some other phones): " +
            "${AutoOff.BLUETOOTH_SETTING} > Never."
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle("Bluetooth was switched off")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + more))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent()) // opening Siren while scanning asks to turn Bluetooth on
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(BT_OFF_ID, n) }
    }

    private fun currentLocation(): Location? =
        lastLocation?.takeIf { System.currentTimeMillis() - it.time < LOCATION_MAX_AGE_MS }

    private fun alert(d: Detection) {
        val sig = d.signature ?: return
        CarDisplay.publishAlert("${sig.category.label}: ${sig.label}", d.name ?: d.id)
        AlertSound.play(
            this,
            when (sig.category) {
                Category.POLICE -> AlertSound.Kind.POLICE
                Category.RADIO -> AlertSound.Kind.RADIO
                Category.TRACKER -> AlertSound.Kind.TRACKER
                else -> AlertSound.Kind.OTHER
            },
        )
        val text = if (d.source == Source.RF) "${d.id} · ${d.name}"
        else "${d.source.label} · ${d.rssi} dBm · ${away(distanceText(d))}" + (d.name?.let { " · $it" } ?: "")
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle("${sig.category.label}: ${sig.label}")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(DeepLink.intent(this, DeepLink.Device(d.key)))
            .setGroup("siren-" + DeepLink.Device(d.key).encode())
            .setAutoCancel(true)
        AlertNotify.post(
            this, if (sig.id == EvilTwin.SIGNATURE.id) AlertNotify.Kind.FAKE_WIFI else AlertNotify.forCategory(sig.category), ALERT_ID, n,
        )
    }

    private var placeWatchNotifiedAt = 0L

    private fun ongoingNotification() = NotificationCompat.Builder(this, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_siren)
        .setContentTitle("${getString(R.string.app_name)} is scanning")
        .setContentText("Watching Bluetooth, Wi-Fi and RF for emergency vehicle equipment")
        .setOngoing(true)
        .setContentIntent(openAppIntent())
        .addAction(
            0, "Mark this moment",
            PendingIntent.getService(
                this, 2, Intent(this, ScanService::class.java).setAction(ACTION_MARK),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0, "Stop",
            PendingIntent.getService(
                this, 1, Intent(this, ScanService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_STOP = "app.sirenscout.STOP"
        const val ACTION_MARK = "app.sirenscout.MARK"
        const val ACTION_MUTE_TRACKER = "app.sirenscout.MUTE_TRACKER"
        private const val EXTRA_SIGNATURE = "signature"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val TRACKER_TYPE_COOLDOWN_MS = 30 * 60_000L
        private const val CH_SERVICE = "service"
        private const val CH_ALERTS = "alerts_v2"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        /** At most one "new device where you're watching" notification this often. */
        private const val PLACE_WATCH_NOTIFY_MS = 10 * 60_000L
        private const val BT_OFF_ID = 6000
        private const val FOLLOW_ID_BASE = 2000
        /** Moved at least this far with a tracker, so one in a neighbor's car parked beside you doesn't count. */
        private const val TRACKER_MIN_DISTANCE_M = 800.0
        private const val CAMERA_ID_BASE = 3000
        private const val AIR_ID_BASE = 4000
        private const val AIRCRAFT_POLL_MS = 30_000L
        private val REMOTE_ID_UUID: ParcelUuid = ParcelUuid.fromString(RemoteId.BLE_SERVICE_UUID)
        private const val CAMERA_REALERT_MS = 15 * 60_000L
        /** Camera downloads look ahead above ~30 mph, this far along the road. */
        /** How long a fake Wi-Fi flag sticks after the scan that last showed it. */
        private const val TWIN_MEMORY_MS = 30 * 60_000L
        private const val LOOKAHEAD_MIN_SPEED_MPS = 13f
        private const val LOOKAHEAD_M = 30_000.0
        private const val TRACKER_KEEP_SIGHTINGS = 5
        private const val HOTSPOT_REALERT_MS = 30 * 60_000L
        /** Only places with at least this many stops in the period alert; quieter ones are just on the map. */
        private const val HOTSPOT_ALERT_MIN = 10
        /** Crash hotspots cover a year or two, so they need more to count as a dangerous spot. */
        private const val CRASH_ALERT_MIN = 15
        private const val HOTSPOT_ID_BASE = 7000 // clear of BT_OFF_ID and the flight updates (5100-6099)
        private const val BLE_RESTART_MS = 10 * 60_000L
        private const val CLASSIC_INTERVAL_MS = 30_000L
        private const val WIFI_INTERVAL_MS = 31_000L
        private const val FAST_WIFI_INTERVAL_MS = 8_000L
        /** After a refusal, try fast scans again this much later (the Developer option may have changed). */
        private const val FAST_WIFI_RETRY_MS = 10 * 60_000L
        /** Exact advertised names worth catching with the screen off (filters can't do patterns). */
        private val SCREEN_OFF_NAMES = listOf("Flock", "Penguin", "Pigvision", "FS Ext Battery")
        private const val LOCATION_MAX_AGE_MS = 60_000L
        private const val HISTORY_FLUSH_MS = 15_000L
    }
}

/** Miles where the US-style units are used, kilometers elsewhere. */
fun usesMiles() = Locale.getDefault().country in setOf("US", "LR", "MM", "GB")

fun formatDistance(meters: Double) =
    if (usesMiles()) "%.1f mi".format(meters / 1609.34) else "%.1f km".format(meters / 1000)

/** Feet or meters for short distances, falling back to [formatDistance]. */
fun formatShortDistance(meters: Double) = when {
    usesMiles() && meters < 300 -> "${((meters * 3.281) / 10).roundToInt() * 10} ft"
    !usesMiles() && meters < 1000 -> "${(meters / 10).roundToInt() * 10} m"
    else -> formatDistance(meters)
}

/**
 * A rough distance from signal strength: "about 40 ft". Signal falls off as
 * rssi = (strength at 1 m) - 10·n·log10(distance); Wi-Fi access points are much stronger
 * transmitters than Bluetooth gadgets, so they get their own numbers. Walls, bodies and the car
 * itself weaken signals, so things are often closer than they seem.
 */
fun proximity(rssi: Int, source: Source = Source.BLE, wifiMhz: Int? = null): String =
    if (rssi >= 0) "distance unknown" // Android sometimes reports 0 dBm when it has no real reading
    else roughDistance(estimateMeters(rssi, source, wifiMhz))

fun estimateMeters(rssi: Int, source: Source, wifiMhz: Int? = null): Double {
    val (atOneMetre, falloff) = if (source == Source.WIFI) WifiBands.model(wifiMhz) else -59.0 to 2.2
    return 10.0.pow((atOneMetre - rssi) / (10 * falloff)).coerceIn(1.0, 2_000.0)
}

/** "within 5 ft", "about 40 ft", "about 350 m": rounded so it doesn't look more exact than it is. */
fun roughDistance(meters: Double): String {
    fun near(v: Double, step: Int) = ((v / step).roundToInt() * step).coerceAtLeast(step)
    return if (usesMiles()) {
        val ft = meters * 3.281
        when {
            ft < 6 -> "within 5 ft"
            ft < 50 -> "about ${near(ft, 5)} ft"
            ft < 300 -> "about ${near(ft, 10)} ft"
            ft < 1_500 -> "about ${near(ft, 50)} ft"
            else -> "about %.1f mi".format(meters / 1609.34)
        }
    } else when {
        meters < 2 -> "within 2 m"
        meters < 20 -> "about ${near(meters, 1)} m"
        meters < 100 -> "about ${near(meters, 5)} m"
        meters < 1_000 -> "about ${near(meters, 25)} m"
        else -> "about %.1f km".format(meters / 1000)
    }
}
