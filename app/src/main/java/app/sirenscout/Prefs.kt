package app.sirenscout

import android.content.Context
import kotlin.math.roundToLong

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Matches weaker than this (dBm) are listed but don't alert. */
    var rssiThreshold: Int
        get() = sp.getInt("rssi_threshold", -90)
        set(v) = sp.edit().putInt("rssi_threshold", v).apply()

    var rfEnabled: Boolean
        get() = sp.getBoolean("rf_enabled", false)
        set(v) = sp.edit().putBoolean("rf_enabled", v).apply()

    var rtlHost: String
        get() = sp.getString("rtl_host", "127.0.0.1") ?: "127.0.0.1"
        set(v) = sp.edit().putString("rtl_host", v).apply()

    var rtlPort: Int
        get() = sp.getInt("rtl_port", 1234)
        set(v) = sp.edit().putInt("rtl_port", v).apply()

    /** Frequencies in MHz, separated by commas or whitespace. */
    var rfFrequencies: String
        get() = sp.getString("rf_frequencies", "") ?: ""
        set(v) = sp.edit().putString("rf_frequencies", v).apply()

    /** How far above the learned noise floor (dB) a channel must rise to count as a transmission. */
    var rfThresholdDb: Float
        get() = sp.getFloat("rf_threshold_db", 10f)
        set(v) = sp.edit().putFloat("rf_threshold_db", v).apply()

    /** Tuner gain in tenths of a dB, as rtl_tcp expects. */
    var rfGainTenthsDb: Int
        get() = sp.getInt("rf_gain", 297)
        set(v) = sp.edit().putInt("rf_gain", v).apply()

    /** Alert when police equipment keeps traveling with you. */
    var followAlerts: Boolean
        get() = sp.getBoolean("follow_alerts", true)
        set(v) = sp.edit().putBoolean("follow_alerts", v).apply()

    var followMinutes: Int
        get() = sp.getInt("follow_minutes", 5)
        set(v) = sp.edit().putInt("follow_minutes", v).apply()

    /** Stored in meters; the UI shows miles or km depending on locale. */
    var followDistanceM: Float
        get() = sp.getFloat("follow_distance_m", 1609f)
        set(v) = sp.edit().putFloat("follow_distance_m", v).apply()

    /** Show plate readers mapped in OpenStreetMap and alert when approaching one. */
    var knownCameras: Boolean
        get() = sp.getBoolean("known_cameras", true)
        set(v) = sp.edit().putBoolean("known_cameras", v).apply()

    var cameraAlertM: Float
        get() = sp.getFloat("camera_alert_m", 250f)
        set(v) = sp.edit().putFloat("camera_alert_m", v).apply()

    // Turn on what scanning needs, asking via Android's own prompts. All on by default.
    var autoBluetooth: Boolean
        get() = sp.getBoolean("auto_bluetooth", true)
        set(v) = sp.edit().putBoolean("auto_bluetooth", v).apply()

    var autoLocation: Boolean
        get() = sp.getBoolean("auto_location", true)
        set(v) = sp.edit().putBoolean("auto_location", v).apply()

    var autoWifi: Boolean
        get() = sp.getBoolean("auto_wifi", true)
        set(v) = sp.edit().putBoolean("auto_wifi", v).apply()

    var autoBattery: Boolean
        get() = sp.getBoolean("auto_battery", true)
        set(v) = sp.edit().putBoolean("auto_battery", v).apply()

    /** Alert when an item tracker (AirTag, Tile…) that isn't yours travels with you. */
    var trackerAlerts: Boolean
        get() = sp.getBoolean("tracker_alerts", true)
        set(v) = sp.edit().putBoolean("tracker_alerts", v).apply()

    var trackerMinutes: Int
        get() = sp.getInt("tracker_minutes", 10)
        set(v) = sp.edit().putInt("tracker_minutes", v).apply()

    /** Choose scanning settings from the phone's state (charging, SDR plugged in, speed). */
    var autoOptimize: Boolean
        get() = sp.getBoolean("auto_optimize", true)
        set(v) = sp.edit().putBoolean("auto_optimize", v).apply()

    /** Scan Wi-Fi every 8 s when Android's scan throttling is off; otherwise every 31 s. */
    var fastWifi: Boolean
        get() = sp.getBoolean("fast_wifi", true)
        set(v) = sp.edit().putBoolean("fast_wifi", v).apply()

    /** With the screen off, keep a filtered BLE scan for the identifiers Android can filter on. */
    var screenOffScan: Boolean
        get() = sp.getBoolean("screen_off_scan", true)
        set(v) = sp.edit().putBoolean("screen_off_scan", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    /** Where the laptop publishes updates (over Tailscale). Blank turns update checks off. */
    var updateUrl: String
        get() = sp.getString("update_url", DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
        set(v) = sp.edit().putString("update_url", v.trim()).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    /** Band watch: sweep the bands radios transmit on, for nearby transmitters anywhere. */
    var band700: Boolean
        get() = sp.getBoolean("band_700", true)
        set(v) = sp.edit().putBoolean("band_700", v).apply()

    var bandNpspac: Boolean
        get() = sp.getBoolean("band_npspac", true)
        set(v) = sp.edit().putBoolean("band_npspac", v).apply()

    var band800Shared: Boolean
        get() = sp.getBoolean("band_800_shared", false)
        set(v) = sp.edit().putBoolean("band_800_shared", v).apply()

    /** How far above a channel's usual level counts as "nearby"; higher = closer transmitters only. */
    var bandThresholdDb: Float
        get() = sp.getFloat("band_threshold_db", 15f)
        set(v) = sp.edit().putFloat("band_threshold_db", v).apply()

    fun bands(): List<RadioBand> = buildList {
        if (band700) add(Bands.PS_700)
        if (bandNpspac) add(Bands.NPSPAC)
        if (band800Shared) add(Bands.SHARED_800)
    }

    /** Serve a live page for another screen on the local network (e.g. a car's browser). */
    var carDisplay: Boolean
        get() = sp.getBoolean("car_display", false)
        set(v) = sp.edit().putBoolean("car_display", v).apply()

    // Drones and police aircraft (AirWatch).
    /** Drones' Remote ID broadcasts, heard directly. */
    var airDrones: Boolean
        get() = sp.getBoolean("air_drones", true)
        set(v) = sp.edit().putBoolean("air_drones", v).apply()
    /**
     * Law-enforcement aircraft from the online ADS-B feed (adsb.lol). On by default in the
     * maintainer's own builds (Siren, Siren Test); opt-in in the public edition, which links to its
     * project page (REPO_URL), since it fetches from the internet.
     */
    var airAircraft: Boolean
        get() = sp.getBoolean("air_aircraft", BuildConfig.REPO_URL.isEmpty())
        set(v) = sp.edit().putBoolean("air_aircraft", v).apply()
    /** Also list every other aircraft (no alerts), e.g. to check the feed is working. */
    var airShowAll: Boolean
        get() = sp.getBoolean("air_show_all", false)
        set(v) = sp.edit().putBoolean("air_show_all", v).apply()
    /** Alert when a police aircraft is within this many miles. */
    var airAlertMiles: Float
        get() = sp.getFloat("air_alert_miles", 5f)
        set(v) = sp.edit().putFloat("air_alert_miles", v).apply()

    /** Also serve the car display on Wi-Fi networks the phone has joined, not just its hotspot / USB. */
    var carOnWifi: Boolean
        get() = sp.getBoolean("car_on_wifi", false)
        set(v) = sp.edit().putBoolean("car_on_wifi", v).apply()

    /** Secret part of the car display address, so other devices on the hotspot can't open it. */
    var carCode: String
        get() = sp.getString("car_code", null) ?: CarDisplay.newCode().also { carCode = it }
        set(v) = sp.edit().putString("car_code", v).apply()

    /** Colour of your own position marker on the map. */
    var locationColor: Int
        get() = sp.getInt("location_color", LocationMarker.COLORS.first().second)
        set(v) = sp.edit().putInt("location_color", v).apply()

    /** All nearby leaves out ordinary consumer devices (routers, phones, headphones…). */
    var hideEveryday: Boolean
        get() = sp.getBoolean("hide_everyday", true)
        set(v) = sp.edit().putBoolean("hide_everyday", v).apply()

    /** Ask the on-device AI about unidentified devices while the app is on screen. */
    /** Off until the user opts in. */
    var aiEnabled: Boolean
        get() = sp.getBoolean("ai_enabled", false)
        set(v) = sp.edit().putBoolean("ai_enabled", v).apply()

    // Alert sounds, per kind. Police, radio, trackers and plate readers on by default.
    var soundPolice: Boolean
        get() = sp.getBoolean("sound_police", true)
        set(v) = sp.edit().putBoolean("sound_police", v).apply()
    var soundRadio: Boolean
        get() = sp.getBoolean("sound_radio", true)
        set(v) = sp.edit().putBoolean("sound_radio", v).apply()
    var soundTracker: Boolean
        get() = sp.getBoolean("sound_tracker", true)
        set(v) = sp.edit().putBoolean("sound_tracker", v).apply()
    var soundCamera: Boolean
        get() = sp.getBoolean("sound_camera", true)
        set(v) = sp.edit().putBoolean("sound_camera", v).apply()
    var soundAir: Boolean
        get() = sp.getBoolean("sound_air", true)
        set(v) = sp.edit().putBoolean("sound_air", v).apply()
    var soundOther: Boolean
        get() = sp.getBoolean("sound_other", false)
        set(v) = sp.edit().putBoolean("sound_other", v).apply()
    // Syncing with the user's other phones (PhoneSync).
    /** Let other phones sync with this one. */
    var syncServe: Boolean
        get() = sp.getBoolean("sync_serve", false)
        set(v) = sp.edit().putBoolean("sync_serve", v).apply()
    /** This phone's code, which the other phone enters. */
    val syncCode: String
        get() = sp.getString("sync_code", null) ?: PhoneSync.newCode().also { newSyncCode(it) }
    fun newSyncCode(code: String = PhoneSync.newCode()) = sp.edit().putString("sync_code", code).apply()
    /** The other phone's Tailscale address, and its code. Blank: don't sync with another phone. */
    var syncPartner: String
        get() = sp.getString("sync_partner", "") ?: ""
        set(v) = sp.edit().putString("sync_partner", v).apply()
    var syncPartnerCode: String
        get() = sp.getString("sync_partner_code", "") ?: ""
        set(v) = sp.edit().putString("sync_partner_code", v).apply()
    /** Also sync history, which includes where things were seen. */
    var syncHistory: Boolean
        get() = sp.getBoolean("sync_history", false)
        set(v) = sp.edit().putBoolean("sync_history", v).apply()

    /** Off: sounds always come out of the phone's own speaker, even when the car is connected. */
    var soundThroughCar: Boolean
        get() = sp.getBoolean("sound_through_car", false)
        set(v) = sp.edit().putBoolean("sound_through_car", v).apply()
    /** Play on the alarm volume, so sounds are heard even with the phone on silent. */
    var soundWhenSilent: Boolean
        get() = sp.getBoolean("sound_when_silent", false)
        set(v) = sp.edit().putBoolean("sound_when_silent", v).apply()

    val isLocalSdr get() = rtlHost == "127.0.0.1" || rtlHost.equals("localhost", ignoreCase = true)

    fun frequenciesHz(): List<Long> = rfFrequencies
        .split(Regex("[,\\s]+"))
        .mapNotNull { it.toDoubleOrNull() }
        .filter { it in 24.0..1766.0 } // typical RTL-SDR tuning range
        .map { (it * 1e6).roundToLong() }
        .distinct()
}

/**
 * True when "Wi-Fi scan throttling" is switched off in Developer options. The setting isn't
 * public API, so anything unexpected (missing key, OEM restriction) counts as throttled.
 */
fun wifiThrottlingOff(context: Context): Boolean = runCatching {
    android.provider.Settings.Global.getInt(context.contentResolver, "wifi_scan_throttle_enabled", 1) == 0
}.getOrDefault(false)

/** Where this build updates itself from (set in local.properties); blank when it doesn't. */
const val DEFAULT_UPDATE_URL = BuildConfig.UPDATE_URL

/** The same server by IP, for phones whose DNS can't see Tailscale names. */
const val FALLBACK_UPDATE_URL = BuildConfig.UPDATE_FALLBACK_URL

/** False for builds that are updated by an app store (F-Droid) rather than by Siren itself. */
const val SELF_UPDATES = DEFAULT_UPDATE_URL != ""
