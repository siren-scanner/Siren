package app.sirenscout

import android.app.Activity
import android.content.Intent
import android.location.Location

/**
 * Made-up detections in a made-up place, for store screenshots: start a debug build with
 * `adb shell am start -n <package>/app.sirenscout.MainActivity --ez demo true`.
 * Nothing here is real - no real network names, addresses or locations.
 */
object DemoMode {
    /** Downtown Kansas City, a public place far from anyone's home. */
    const val LAT = 39.0997
    const val LON = -94.5786

    /** True once demo data is showing: the map then never touches the real location. */
    @Volatile var active = false

    fun maybeStart(activity: Activity, intent: Intent?) {
        if (intent?.getBooleanExtra("demo", false) != true) return
        active = true
        val now = System.currentTimeMillis()
        // Demo data only pretends to scan: don't ask to turn Bluetooth, location or battery settings on.
        Prefs(activity).apply { autoBluetooth = false; autoLocation = false; autoWifi = false; autoBattery = false }
        fun at(dLat: Double, dLon: Double) = Location("demo").apply { latitude = LAT + dLat; longitude = LON + dLon; accuracy = 5f }
        fun sig(id: String) = Signatures.byId(id)

        Detections.setStatus {
            ScanStatus(running = true, ble = "scanning", classic = "discovering", wifi = "23 networks in range · every 31s", rf = "off (no SDR dongle plugged in)")
        }
        // Police and fleet equipment, seen just now.
        Detections.record(Source.BLE, "00:25:DF:3A:7C:11", "Axon Body 3", -63, sig("axon"), false, at(0.0004, 0.0003), reason = "Address starts with 00:25:DF, registered to Axon Enterprise", now = now)
        Detections.record(Source.BLE, "00:25:DF:3A:7C:52", "Axon Signal", -66, sig("axon"), false, at(0.0005, 0.0002), reason = "Address starts with 00:25:DF, registered to Axon Enterprise", now = now - 4_000)
        Detections.record(Source.WIFI, "00:30:44:5B:21:9E", "IBR900-21e", -71, sig("cradlepoint"), false, at(0.0006, 0.0004), guess = "Cradlepoint · vehicle router", reason = "Default Cradlepoint network name", now = now - 9_000)
        Detections.record(Source.BLE, "C4:7F:51:0A:33:8D", "Whelen CenCom", -74, sig("warning-controls"), false, at(0.0003, 0.0006), now = now - 12_000)
        Detections.record(Source.BLE, "58:2B:0A:91:C4:02", "Penguin-4417", -82, sig("flock"), false, at(-0.0021, 0.0035), reason = "Name matches penguin", now = now - 40_000)
        // A tracker that has been traveling with you for 20 minutes.
        val tag = "6C:1A:44:E2:90:5B"
        for (minutesAgo in 20 downTo 0 step 2) {
            val f = (20 - minutesAgo) / 20.0
            Detections.record(
                Source.BLE, tag, null, -80 + (f * 4).toInt(), Trackers.APPLE, false, at(-0.03 * (1 - f), -0.05 * (1 - f)),
                odometerM = 11_800.0 * f, now = now - minutesAgo * 60_000L - 3_000,
            )
        }
        // Ordinary devices around (hidden by "Hide everyday").
        Detections.record(Source.WIFI, "02:11:22:33:44:01", "Corner Coffee Guest", -68, null, false, at(0.0008, -0.0004), guess = "Commscope · home router", now = now - 15_000)
        Detections.record(Source.BLE, "4E:21:9A:77:10:C3", "Pixel Buds Pro", -58, null, false, at(0.0, 0.0), guess = "Google · earbuds", now = now - 2_000)
        Detections.record(Source.BLE, "71:0B:2D:45:EE:19", null, -85, null, false, at(0.0007, 0.0009), guess = "Apple · iPhone / iPad / Mac / Watch", now = now - 20_000)

        // A drone overhead and a police helicopter a few miles out.
        AirWatch.onRemoteId("DE:MO:00:00:00:01", -72, {
            RemoteId.Drone(
                id = "1581F5DEMO0042", idType = "Serial number", aircraftType = "Multirotor / helicopter",
                lat = LAT + 0.0012, lon = LON - 0.0009, heightM = 61.0, speedMs = 4.5, headingDeg = 135.0,
                operatorLat = LAT + 0.0002, operatorLon = LON - 0.0015,
            )
        }, LAT, LON, now)
        AirWatch.onAircraft(
            listOf(
                Adsb.Aircraft("a0beef", "N123PD", LAT + 0.04, LON + 0.03, 1_100, 95.0, 220.0, onGround = false) to
                    Adsb.Registration("N123PD", "Example County Sheriff's Office", "Bell 407"),
            ),
            LAT, LON, alertWithinM = 0.0, now = now,
        )
        AirWatch.feedStatus.value = "Checked just now: 41 aircraft within about 35 mi - 1 law enforcement, 0 government (possible police / first responder)."
    }
}
