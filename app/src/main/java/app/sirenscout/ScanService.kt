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
import java.util.Locale
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
        Hidden.init(this)
        TrackerMutes.init(this)
        WatchRules.init(this)
        followDetector = FollowDetector(prefs.followMinutes * 60_000L, prefs.followDistanceM.toDouble())
        trackerDetector = FollowDetector(prefs.trackerMinutes * 60_000L, TRACKER_MIN_DISTANCE_M)
        btAdapter = getSystemService(BluetoothManager::class.java)?.adapter
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        Detections.setStatus { ScanStatus(running = true) }
        ScanHealth.started()

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
        CarDisplay.stop()
        PhoneSync.stop()
        scope.cancel()
        if (started) {
            runCatching { saveHistory() }
            unregisterReceiver(receiver)
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
        sighting(Source.BLE, r.device.address, name, r.rssi, manufacturer.keys.toList(), services, tracker, DeviceGuesser.summary(r)) {
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
                Intent.ACTION_SCREEN_ON -> stopScreenOffScan()
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

    /** Set once Android refuses fast scans this session (some OEMs throttle regardless). */
    private var fastWifiRefused = false
    private var wifiEverySeconds = WIFI_INTERVAL_MS / 1000

    private fun startWifi() = scope.launch {
        val w = wifi ?: return@launch
        var failures = 0
        while (isActive) {
            // Re-checked every pass, so flipping the Developer option takes effect without a restart.
            val fast = AutoTune.fastWifi(prefs) && !fastWifiRefused && wifiThrottlingOff(this@ScanService)
            @Suppress("DEPRECATION") // still the only way to request a scan
            val requested = w.startScan()
            failures = if (requested) 0 else failures + 1
            if (fast && failures >= 2) fastWifiRefused = true
            wifiEverySeconds = (if (fast && !fastWifiRefused) FAST_WIFI_INTERVAL_MS else WIFI_INTERVAL_MS) / 1000
            if (!requested) {
                ScanHealth.wifiRefused()
                Detections.setStatus { it.copy(wifi = "scan throttled; using passive results") }
            }
            // Android allows 4 scans per 2 minutes for a foreground app unless throttling is off.
            delay(wifiEverySeconds * 1000)
        }
    }

    private fun onWifiResults() {
        val results = runCatching { wifi?.scanResults }.getOrNull() ?: return
        ScanHealth.wifiResults()
        val nowMicros = SystemClock.elapsedRealtime() * 1000
        val fresh = results.filter { nowMicros - it.timestamp < 60_000_000 }
        for (r in fresh) {
            @Suppress("DEPRECATION")
            val ssid = r.SSID?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            val bssid = r.BSSID ?: continue
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
            sighting(Source.WIFI, bssid, ssid, r.level) { if (drone) RemoteId.LABEL else DeviceGuesser.wifi(bssid, ssid) }
        }
        Detections.setStatus { it.copy(wifi = "${fresh.size} networks in range · every ${wifiEverySeconds}s") }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
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
        guess: () -> String?,
    ) {
        if (isHidden(source, address, name)) return
        // Your own rules win over built-in signatures, so you can relabel or catch local gear.
        val (sig, reason) = tracker?.let { it to Trackers.REASONS[it.id] }
            ?: WatchRules.match(address, name)
            ?: Signatures.matchWithReason(source, address, name, manufacturerIds, serviceUuids)
            ?: (null to null)
        Detections.record(
            source, address, name, rssi, sig,
            // Trackers pass by constantly in other people's bags; they only alert when they follow you.
            alertEligible = sig != null && sig.category != Category.TRACKER && rssi >= prefs.rssiThreshold,
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
        val follow = followDetector.sighting(d.uid, d.firstSeen, d.lastSeen, odometer.meters) ?: return
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
        val follow = trackerDetector.sighting(d.uid, d.firstSeen, d.lastSeen, odometer.meters) ?: return
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
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .apply { action?.let(::addAction) }
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(followNotificationId(d), n) }
    }

    // --- History ---

    /** lastSeen of each encounter as of its last save, so unchanged rows aren't rewritten. */
    private val savedAt = HashMap<String, Long>()

    @Synchronized
    private fun saveHistory() {
        val dirty = Detections.items.value.values.filter { d ->
            val keep = d.signature != null &&
                (d.signature.category != Category.TRACKER || d.uid in alertedTrackers)
            keep && savedAt[d.uid] != d.lastSeen
        }.map { d -> Detections.estimate(d)?.let { d.copy(estimate = it) } ?: d }
        History.save(this, dirty)
        dirty.forEach { savedAt[it.uid] = it.lastSeen }
    }

    // --- Location, for placing detections on the map ---

    @Volatile private var lastLocation: Location? = null

    private val odometer = Odometer()

    private val locationListener = LocationListener { loc ->
        if (loc.provider == LocationManager.GPS_PROVIDER) odometer.add(loc.latitude, loc.longitude, loc.accuracy)
        val prev = lastLocation
        // Prefer a fresh GPS fix, but don't let a coarse network fix replace a recent precise one.
        if (prev == null || loc.time - prev.time > 10_000 || loc.accuracy <= prev.accuracy) lastLocation = loc
        checkKnownCameras(loc)
    }

    // --- Known plate readers (OpenStreetMap) ---

    private val cameraAlertedAt = HashMap<Long, Long>()
    private var lastCameraRefresh = 0L

    private fun checkKnownCameras(loc: Location) {
        if (!prefs.knownCameras || loc.accuracy > 50f) return
        val now = System.currentTimeMillis()
        if (now - lastCameraRefresh > 60_000) {
            lastCameraRefresh = now
            scope.launch(Dispatchers.IO) { KnownCameras.refreshAround(this@ScanService, loc.latitude, loc.longitude) }
        }
        // Only alert while moving: "ahead" means nothing without a direction of travel.
        val heading = loc.takeIf { it.hasBearing() && it.speed >= 2.5f }?.bearing ?: return
        val ahead = camerasAhead(
            KnownCameras.cameras.value, loc.latitude, loc.longitude, heading,
            AutoTune.cameraAlertM(prefs, loc.speed).toDouble(),
        )
        for ((cam, distance) in ahead) {
            if (now - (cameraAlertedAt[cam.id] ?: 0L) < CAMERA_REALERT_MS) continue
            cameraAlertedAt[cam.id] = now
            CarDisplay.publishAlert("${cam.label} ahead · ${formatShortDistance(distance)}", facing(cam, heading).text)
            AlertSound.play(this, AlertSound.Kind.CAMERA)
            val n = NotificationCompat.Builder(this, CH_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_siren)
                .setContentTitle("${cam.label} ahead · ${formatShortDistance(distance)}")
                .setContentText(facing(cam, heading).text.replaceFirstChar(Char::uppercase))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        facing(cam, heading).text.replaceFirstChar(Char::uppercase) +
                            (cam.operator?.let { ". Operated by $it." } ?: ".") +
                            " Location from OpenStreetMap / DeFlock."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .build()
            runCatching { NotificationManagerCompat.from(this).notify(CAMERA_ID_BASE + Math.floorMod(cam.id, 1000L).toInt(), n) }
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
            val me = currentLocation()
            val clock = java.time.LocalTime.now().withNano(0).withSecond(0)
            when {
                !prefs.airAircraft -> AirWatch.feedStatus.value = "Police aircraft check is off (Settings > Drones & aircraft)."
                me == null -> AirWatch.feedStatus.value = "Waiting for a location fix before checking for police aircraft."
                else -> runCatching { Adsb.fetch(this@ScanService, me.latitude, me.longitude) }
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
            AirContact.Kind.AIRCRAFT, AirContact.Kind.OTHER_AIRCRAFT -> "Police aircraft nearby: ${c.title}"
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
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(AIR_ID_BASE + Math.floorMod(c.key.hashCode(), 1000), n) }
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
        else "${d.source.label} · ${d.rssi} dBm · ${proximity(d.rssi)}" + (d.name?.let { " · $it" } ?: "")
        val n = NotificationCompat.Builder(this, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_siren)
            .setContentTitle("${sig.category.label}: ${sig.label}")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(ALERT_ID, n) }
    }

    private fun ongoingNotification() = NotificationCompat.Builder(this, CH_SERVICE)
        .setSmallIcon(R.drawable.ic_stat_siren)
        .setContentTitle("${getString(R.string.app_name)} is scanning")
        .setContentText("Watching Bluetooth, Wi-Fi and RF for emergency vehicle equipment")
        .setOngoing(true)
        .setContentIntent(openAppIntent())
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
        const val ACTION_MUTE_TRACKER = "app.sirenscout.MUTE_TRACKER"
        private const val EXTRA_SIGNATURE = "signature"
        private const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val TRACKER_TYPE_COOLDOWN_MS = 30 * 60_000L
        private const val CH_SERVICE = "service"
        private const val CH_ALERTS = "alerts_v2"
        private const val ONGOING_ID = 1
        private const val ALERT_ID = 2
        private const val FOLLOW_ID_BASE = 2000
        /** Moved at least this far with a tracker, so one in a neighbor's car parked beside you doesn't count. */
        private const val TRACKER_MIN_DISTANCE_M = 800.0
        private const val CAMERA_ID_BASE = 3000
        private const val AIR_ID_BASE = 4000
        private const val AIRCRAFT_POLL_MS = 30_000L
        private val REMOTE_ID_UUID: ParcelUuid = ParcelUuid.fromString(RemoteId.BLE_SERVICE_UUID)
        private const val CAMERA_REALERT_MS = 15 * 60_000L
        private const val BLE_RESTART_MS = 10 * 60_000L
        private const val CLASSIC_INTERVAL_MS = 30_000L
        private const val WIFI_INTERVAL_MS = 31_000L
        private const val FAST_WIFI_INTERVAL_MS = 8_000L
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

fun proximity(rssi: Int) = when {
    rssi >= -60 -> "very close"
    rssi >= -75 -> "close"
    rssi >= -88 -> "nearby"
    else -> "far"
}
