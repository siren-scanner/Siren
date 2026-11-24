package app.sirenscout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private enum class SettingsPage(val title: String, val summary: String) {
    ALERTS("Alerts", "Sounds, signal strength, police following you, unknown trackers"),
    CAMERAS("Cameras", "Plate readers, speed and red-light cameras on the map and alert distance"),
    POLICE("Police call data", "Traffic-stop hotspots and live police calls from cities' open data"),
    AIR("Drones & aircraft", "Drones' Remote ID, police helicopters and planes"),
    SCANNING("Scanning", "Auto-optimize, Wi-Fi, screen off, turning things on"),
    MAP("Map", "Your location marker, map text size"),
    RADIO("Radio (RF dongle)", "Band watch, frequencies, receiver"),
    RULES("Watch rules & hidden", "Your own signatures, hidden devices, muted trackers"),
    CAR("Car display", "Show Siren in your car's browser"),
    DATA("Backup, sharing & updates", "Back up and restore, share data or the app, updates"),
    AI("On-device AI", "Optional Gemini Nano guesses for devices Siren can't identify (off until you turn it on)"),
    HELP("Help & diagnostics", "User manual, what's new, scan health, logs and crash reports"),
}

/** Settings as a menu of sections, each opening its own page, instead of one long list. */
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    var showManual by remember { mutableStateOf(false) }
    if (showManual) ManualDialog(onClose = { showManual = false })
    var showNotes by remember { mutableStateOf(false) }
    if (showNotes) ReleaseNotesDialog(onClose = { showNotes = false })

    FullScreenPage(onDismiss = onClose) {
        // Back goes from a section to the menu, and from the menu out of Settings.
        BackHandler { if (page != null) page = null else onClose() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page != null) TextButton(onClick = { page = null }) { Text("←", style = MaterialTheme.typography.titleLarge) }
                    Text(
                        page?.title ?: "Settings",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).padding(start = if (page == null) 12.dp else 0.dp),
                    )
                    TextButton(onClick = onClose) { Text("Done") }
                }
                HorizontalDivider()
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (page) {
                        null -> SettingsPage.entries.filter { it != SettingsPage.AI || AiEngine.AVAILABLE_IN_BUILD }.forEach { p ->
                            Column(
                                Modifier.fillMaxWidth().clickable { page = p }.padding(vertical = 10.dp),
                            ) {
                                Text(p.title, style = MaterialTheme.typography.titleMedium)
                                Text(p.summary, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            HorizontalDivider()
                        }
                        SettingsPage.ALERTS -> AlertSettings()
                        SettingsPage.CAMERAS -> CameraSettings()
                        SettingsPage.POLICE -> PoliceDataSettings()
                        SettingsPage.AIR -> AirSettings()
                        SettingsPage.SCANNING -> {
                            val prefs = Prefs(LocalContext.current)
                            var auto by remember { mutableStateOf(prefs.autoOptimize) }
                            AutoOffWarning()
                            ScanningSettings(prefs, auto) { auto = it; prefs.autoOptimize = it }
                            AutoEnableSettings(prefs)
                        }
                        SettingsPage.MAP -> {
                            LocationColorSetting()
                            HorizontalDivider()
                            MapTextSetting()
                        }
                        SettingsPage.RADIO -> RadioSettings()
                        SettingsPage.RULES -> {
                            WatchRuleList()
                            HorizontalDivider()
                            HiddenList()
                        }
                        SettingsPage.CAR -> CarDisplaySettings(Prefs(LocalContext.current))
                        SettingsPage.DATA -> {
                            val context = LocalContext.current
                            Text("Backup", style = MaterialTheme.typography.titleSmall)
                            BackupButtons()
                            HorizontalDivider()
                            Text("Share data with another phone", style = MaterialTheme.typography.titleSmall)
                            DataShareButtons()
                            HorizontalDivider()
                            Text("Sync automatically", style = MaterialTheme.typography.titleSmall)
                            PhoneSyncSettings()
                            HorizontalDivider()
                            Text("Share Siren", style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = { AppShare.share(context) }, modifier = Modifier.fillMaxWidth()) { Text("Share Siren") }
                            Text(
                                "Sends this installed copy (${appVersion(context)}) through Android's share menu - Quick Share, Bluetooth, a messaging app. The other phone installs it from the file.",
                                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            )
                            if (SELF_UPDATES) {
                                HorizontalDivider()
                                UpdateSettings(Prefs(context))
                            }
                        }
                        SettingsPage.AI -> AiSettings()
                        SettingsPage.HELP -> {
                            OutlinedButton(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) { Text("User manual") }
                            OutlinedButton(onClick = { showNotes = true }, modifier = Modifier.fillMaxWidth()) { Text("What's new") }
                            SuggestButtons()
                            Credits()
                            DiagnosticsButtons()
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingRow(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    // The whole row flips the switch, not just the switch itself.
    Row(Modifier.clickable(enabled = enabled) { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun AlertSettings() {
    val prefs = Prefs(LocalContext.current)
    var rssi by remember { mutableFloatStateOf(prefs.rssiThreshold.toFloat()) }
    var followAlerts by remember { mutableStateOf(prefs.followAlerts) }
    var followMinutes by remember { mutableFloatStateOf(prefs.followMinutes.toFloat()) }
    val unit = if (usesMiles()) 1609.34f else 1000f
    var followDistance by remember { mutableFloatStateOf(prefs.followDistanceM / unit) }
    var trackerAlerts by remember { mutableStateOf(prefs.trackerAlerts) }
    var trackerMinutes by remember { mutableFloatStateOf(prefs.trackerMinutes.toFloat()) }

    AlertSoundSettings()
    HorizontalDivider()
    AlertNotifySettings()
    HorizontalDivider()

    Text("Signal strength", style = MaterialTheme.typography.titleSmall)
    Text("Alert when signal is stronger than ${rssi.roundToInt()} dBm")
    Slider(
        value = rssi, valueRange = -100f..-40f,
        onValueChange = { rssi = it },
        onValueChangeFinished = { prefs.rssiThreshold = rssi.roundToInt() },
    )
    Text("Weaker matches are still listed, just without an alert. Applies the next time you press Start.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    HorizontalDivider()

    var routersAlone by remember { mutableStateOf(prefs.routersAlone) }
    SettingRow(
        "Vehicle routers on their own",
        "Cradlepoint, Sierra AirLink, Peplink and Inseego routers are in police cars, but also in buses, shops and ATMs. Off " +
            "(recommended): they only alert when their name is a vehicle model's (like IBR900) or police or fire / EMS gear is " +
            "also nearby, and never once they've been seen in the same place on an earlier day. On: every one alerts.",
        routersAlone,
    ) { routersAlone = it; prefs.routersAlone = it }
    HorizontalDivider()

    SettingRow(
        "Police equipment traveling with me",
        "Police signatures seen in most minutes while you're moving.",
        followAlerts,
    ) { followAlerts = it; prefs.followAlerts = it }
    if (followAlerts) {
        Text("After ${followMinutes.roundToInt()} minutes nearby")
        Slider(
            value = followMinutes, valueRange = 2f..30f,
            onValueChange = { followMinutes = it },
            onValueChangeFinished = { prefs.followMinutes = followMinutes.roundToInt() },
        )
        Text("…while I've traveled at least %.1f %s".format(followDistance, if (usesMiles()) "mi" else "km"))
        Slider(
            value = followDistance, valueRange = 0.5f..10f,
            onValueChange = { followDistance = (it * 2).roundToInt() / 2f },
            onValueChangeFinished = { prefs.followDistanceM = followDistance * unit },
        )
    }
    HorizontalDivider()

    SettingRow(
        "Unknown tracker traveling with me",
        "AirTags away from their owner, Tile, SmartTag and Google Find Hub tags that stay with you while you move.",
        trackerAlerts,
    ) { trackerAlerts = it; prefs.trackerAlerts = it }
    if (trackerAlerts) {
        Text("After ${trackerMinutes.roundToInt()} minutes")
        Slider(
            value = trackerMinutes, valueRange = 5f..60f,
            onValueChange = { trackerMinutes = it },
            onValueChangeFinished = { prefs.trackerMinutes = trackerMinutes.roundToInt() },
        )
    }
}

@Composable
private fun CameraSettings() {
    val prefs = Prefs(LocalContext.current)
    var knownCameras by remember { mutableStateOf(prefs.knownCameras) }
    var cameraAlert by remember { mutableFloatStateOf(prefs.cameraAlertM) }
    var speedCameras by remember { mutableStateOf(prefs.speedCameras) }
    SettingRow(
        "Known cameras (online)",
        "Shows mapped Flock and other plate readers from OpenStreetMap / DeFlock, and alerts when you're driving toward one. " +
            "Downloads camera locations for your area (rounded to about 10 km) from OpenStreetMap, so it needs a data connection.",
        knownCameras,
    ) { knownCameras = it; prefs.knownCameras = it }
    if (!knownCameras) return
    SettingRow(
        "Speed and red-light cameras",
        "Also shows (in amber) and alerts for speed cameras and red-light cameras mapped in OpenStreetMap - part of the same " +
            "download. Coverage depends on volunteers mapping them, so some are missing, especially mobile and new ones.",
        speedCameras,
    ) { speedCameras = it; prefs.speedCameras = it }
    if (prefs.autoOptimize) {
        Text(
            "Alert distance is set by Auto-optimize (Scanning): about 15 seconds before you reach a camera at your current speed " +
                "(${formatShortDistance(150.0)} to ${formatShortDistance(500.0)}).",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
    } else {
        Text("Alert ${formatShortDistance(cameraAlert.toDouble())} before a camera")
        Slider(
            value = cameraAlert, valueRange = 50f..800f,
            onValueChange = { cameraAlert = (it / 25).roundToInt() * 25f },
            onValueChangeFinished = { prefs.cameraAlertM = cameraAlert },
        )
    }
}

@Composable
private fun RadioSettings() {
    val context = LocalContext.current
    val prefs = Prefs(context)
    val auto = prefs.autoOptimize
    var rfEnabled by remember { mutableStateOf(prefs.rfEnabled) }
    var host by remember { mutableStateOf(prefs.rtlHost) }
    var port by remember { mutableStateOf(prefs.rtlPort.toString()) }
    var freqs by remember { mutableStateOf(prefs.rfFrequencies) }
    var threshold by remember { mutableFloatStateOf(prefs.rfThresholdDb) }
    var gain by remember { mutableFloatStateOf(prefs.rfGainTenthsDb / 10f) }
    var band700 by remember { mutableStateOf(prefs.band700) }
    var bandNpspac by remember { mutableStateOf(prefs.bandNpspac) }
    var band800 by remember { mutableStateOf(prefs.band800Shared) }
    var bandThreshold by remember { mutableFloatStateOf(prefs.bandThresholdDb) }

    RfBetaNote()
    SettingRow(
        "RF monitoring (RTL-SDR)",
        if (auto) "Turned on by Auto-optimize whenever an RTL-SDR dongle is plugged in (or a remote receiver is set)."
        else "Needs a USB RTL-SDR dongle and the \"RTL2832U driver\" app, or rtl_tcp on another device.",
        checked = if (auto) AutoTune.rfEnabled(context, prefs) else rfEnabled,
        enabled = !auto,
    ) { rfEnabled = it; prefs.rfEnabled = it }
    Text("Changes apply the next time you press Start.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    HorizontalDivider()

    Text("Band watch", style = MaterialTheme.typography.titleSmall)
    Text(
        "Sweeps the frequencies police and fire radios transmit on - not the towers - and alerts on a strong " +
            "transmitter nearby. Works anywhere in the US without entering frequencies.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    BandSwitch("700 MHz public safety (799-805 MHz)", "Public safety only. Many statewide and county digital systems.", band700) {
        band700 = it; prefs.band700 = it
    }
    BandSwitch("800 MHz public-safety channels (821-824 MHz)", "Interoperability channels, public safety only.", bandNpspac) {
        bandNpspac = it; prefs.bandNpspac = it
    }
    BandSwitch("800 MHz shared (806-821 MHz)", "Many county systems, but also utilities and businesses - more false alarms.", band800) {
        band800 = it; prefs.band800Shared = it
    }
    Text("Alert at %.0f dB above a channel's usual level".format(bandThreshold))
    Slider(
        value = bandThreshold, valueRange = 8f..35f,
        onValueChange = { bandThreshold = it },
        onValueChangeFinished = { prefs.bandThresholdDb = bandThreshold },
    )
    Text(
        "Higher means only transmitters very close to you. Start around 15 dB and raise it if distant traffic sets it off.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    HorizontalDivider()

    Text("Specific frequencies (optional)", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = freqs, onValueChange = { freqs = it; prefs.rfFrequencies = it },
        label = { Text("Frequencies to watch (MHz)") },
        placeholder = { Text("e.g. 155.475, 460.250") },
        supportingText = { Text("Use the frequencies radios transmit on (\"input\"), from RadioReference. ${prefs.frequenciesHz().size} valid.") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Trigger at %.0f dB above the noise floor".format(threshold))
    Slider(
        value = threshold, valueRange = 3f..30f,
        onValueChange = { threshold = it },
        onValueChangeFinished = { prefs.rfThresholdDb = threshold },
    )
    HorizontalDivider()

    Text("Receiver", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = host, onValueChange = { host = it.trim(); prefs.rtlHost = host },
            label = { Text("rtl_tcp host") }, singleLine = true, modifier = Modifier.weight(2f),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { v -> port = v.filter(Char::isDigit); port.toIntOrNull()?.let { prefs.rtlPort = it } },
            label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
    Text("Leave as 127.0.0.1 for a dongle plugged into this phone.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    Text("Tuner gain %.1f dB".format(gain))
    Slider(
        value = gain, valueRange = 0f..49.6f,
        onValueChange = { gain = it },
        onValueChangeFinished = { prefs.rfGainTenthsDb = (gain * 10).roundToInt() },
    )
}

@Composable
private fun AiSettings() {
    val context = LocalContext.current
    val prefs = Prefs(context)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.aiEnabled) }
    val activity by AiGuesser.activity.collectAsStateWithLifecycle()
    val known by AiGuesser.guesses.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<AiStatus?>(null) }
    var download by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(download) { status = AiEngine.status(context) }

    Text(
        "$APP_NAME asks Google's Gemini Nano, running entirely on this phone, what unidentified devices probably are. " +
            "Nothing is sent anywhere. Answers are marked ${AiGuesser.MARK} and are only suggestions - they never trigger alerts.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    SettingRow("Use on-device AI", "Off until you turn it on. Only works while $APP_NAME is open on screen - Android doesn't allow it in the background.", enabled) {
        enabled = it; prefs.aiEnabled = it
    }
    HorizontalDivider()
    Text("Status: ${status?.text ?: "checking…"}")
    if (activity.isNotEmpty()) Text(activity, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    // Only offer the download while the model is actually missing (or a download is under way).
    if (status == AiStatus.NeedsDownload || (status != AiStatus.Ready && download.isNotEmpty())) {
        OutlinedButton(
            enabled = download.isEmpty() || download.startsWith("Download failed"),
            onClick = {
                scope.launch { AiEngine.download(context).collect { download = it } }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Download the AI model") }
        if (download.isNotEmpty()) Text(download, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
    HorizontalDivider()
    Text("${known.size} kinds of device identified so far. Each kind is only asked about once.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = { AiGuesser.clear(context) }, modifier = Modifier.fillMaxWidth()) { Text("Forget AI guesses") }
}

@Composable
private fun AlertSoundSettings() {
    val context = LocalContext.current
    val prefs = Prefs(context)
    Text("Sounds", style = MaterialTheme.typography.titleSmall)
    Text(
        "Siren's own sounds, played from the phone's speaker. Music in the car is lowered briefly so you can hear them.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    AlertSound.Kind.entries.forEach { kind ->
        var on by remember { mutableStateOf(AlertSound.enabled(prefs, kind)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { AlertSound.play(context, kind, force = true) }) { Text("Test") }
            Switch(checked = on, onCheckedChange = {
                on = it
                when (kind) {
                    AlertSound.Kind.POLICE -> prefs.soundPolice = it
                    AlertSound.Kind.RADIO -> prefs.soundRadio = it
                    AlertSound.Kind.TRACKER -> prefs.soundTracker = it
                    AlertSound.Kind.CAMERA -> prefs.soundCamera = it
                    AlertSound.Kind.SPEED_CAMERA -> prefs.soundSpeedCamera = it
                    AlertSound.Kind.AIR -> prefs.soundAir = it
                    AlertSound.Kind.OTHER -> prefs.soundOther = it
                }
            })
        }
    }
    var throughCar by remember { mutableStateOf(prefs.soundThroughCar) }
    SettingRow(
        "Play through the car when connected",
        "Off: always from the phone's own speaker. On: through the car or headphones, like navigation directions.",
        throughCar,
    ) { throughCar = it; prefs.soundThroughCar = it }
    var whenSilent by remember { mutableStateOf(prefs.soundWhenSilent) }
    SettingRow(
        "Play even when the phone is on silent",
        "Uses the alarm volume instead of the navigation volume.",
        whenSilent,
    ) { whenSilent = it; prefs.soundWhenSilent = it }
}


/** Siren is open source: links to suggest features, report problems and read the code. */
@Composable
private fun SuggestButtons() {
    if (BuildConfig.REPO_URL.isEmpty()) return
    val context = LocalContext.current
    fun open(url: String) = runCatching {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
    HorizontalDivider()
    Text("Suggestions", style = MaterialTheme.typography.titleSmall)
    OutlinedButton(onClick = { open("${BuildConfig.REPO_URL}/issues/new/choose") }, modifier = Modifier.fillMaxWidth()) {
        Text("Suggest a feature or report a problem")
    }
    OutlinedButton(onClick = { open(BuildConfig.REPO_URL) }, modifier = Modifier.fillMaxWidth()) { Text("Source code") }
    Text(
        "Siren is open source (GPL-3.0). Suggestions, bug reports and new device signatures are welcome on its project page; " +
            "you can also change it for your own needs and share your version under the same license.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
}

/** Radio monitoring hasn't been tried with a real dongle yet: say so, and ask for feedback. */
@Composable
private fun RfBetaNote() = BetaFeedbackNote(
    "Beta: not yet tested with a real dongle",
    "Radio monitoring is built but hasn't been tried with an actual RTL-SDR dongle yet. If you try it, " +
        "you're a beta tester - thank you! Feedback on what works, what doesn't and what could be better " +
        "is very welcome and helps fix problems and improve it.",
)

/** A beta feature's note asking for feedback, with a link to the project page. Public edition only. */
@Composable
internal fun BetaFeedbackNote(title: String, text: String) {
    if (BuildConfig.REPO_URL.isEmpty()) return // only the public edition asks testers for feedback
    val context = LocalContext.current
    androidx.compose.material3.Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("${BuildConfig.REPO_URL}/issues/new/choose"))
                    )
                }
            }) { Text("Send feedback") }
        }
    }
}

@Composable
private fun Credits() {
    HorizontalDivider()
    Text("About", style = MaterialTheme.typography.titleSmall)
    Text(
        (if (BuildConfig.AUTHOR.isNotEmpty()) "Siren was created by ${BuildConfig.AUTHOR} and built with Claude, Anthropic's AI assistant."
        else "Siren was built with Claude, Anthropic's AI assistant.") +
            " Maps © OpenStreetMap contributors.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
}

@Composable
private fun AirSettings() {
    val context = LocalContext.current
    val prefs = Prefs(context)
    var drones by remember { mutableStateOf(prefs.airDrones) }
    var aircraft by remember { mutableStateOf(prefs.airAircraft) }
    var showAll by remember { mutableStateOf(prefs.airShowAll) }
    val unit = if (usesMiles()) 1f else 1.609f
    var miles by remember { mutableFloatStateOf(prefs.airAlertMiles) }
    SettingRow(
        "Drones (Remote ID)",
        "Most drones must broadcast their position, height and often their operator's position over Bluetooth and Wi-Fi. " +
            "Siren listens for it while scanning - no internet needed. Alerts once per drone, then again after 30 minutes.",
        drones,
    ) { drones = it; prefs.airDrones = it }
    HorizontalDivider()
    SettingRow(
        "Police aircraft (online)",
        "Checks the free community ADS-B feed at adsb.lol every 30 seconds for helicopters and planes registered to police, " +
            "sheriffs, state patrols and federal law enforcement, plus city, county and state government aircraft (shown as " +
            "possible police or first responder). Needs a data connection; the feed is only told your area, " +
            "rounded to about 10 km. Aircraft can switch ADS-B off, so no alert doesn't mean none overhead.",
        aircraft,
    ) { aircraft = it; prefs.airAircraft = it }
    if (aircraft) {
        Text("Alert within %.0f %s".format(miles * unit, if (usesMiles()) "mi" else "km"))
        Slider(
            value = miles, valueRange = 1f..20f,
            onValueChange = { miles = it.roundToInt().toFloat() },
            onValueChangeFinished = { prefs.airAlertMiles = miles },
        )
        Text(
            "Police aircraft further away still show in the Air tab, up to about 35 miles (55 km).",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
        )
        SettingRow(
            "Show all aircraft",
            "Also list every other aircraft in the area, in grey and without alerts - handy to check the feed is working. Off: only law-enforcement aircraft.",
            showAll,
        ) { showAll = it; prefs.airShowAll = it }
    }
    HorizontalDivider()
    AircraftDetailsSettings()
}

/** The optional US aircraft registry download, and online lookups at hexdb.io. */
@Composable
private fun AircraftDetailsSettings() {
    val context = LocalContext.current
    val prefs = Prefs(context)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var installed by remember { mutableStateOf(AircraftRegistry.installed(context)) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableFloatStateOf(-1f) }
    var message by remember { mutableStateOf<String?>(null) }
    var hexdb by remember { mutableStateOf(prefs.hexdbLookups) }
    Text("Aircraft details", style = MaterialTheme.typography.titleMedium)
    Text(
        "Make, model, year built, engine, seats, owner and home base for every US-registered aircraft, from the FAA's public " +
            "registry. It's a separate download (about 8 MB, 21 MB on the phone) so Siren itself stays small; after that, " +
            "looking aircraft up needs no internet. Used in flight details and for aircraft on the map.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    if (installed) {
        Text(
            "Downloaded · FAA data from ${AircraftRegistry.dataDate(context) ?: "unknown date"} · %.0f MB".format(AircraftRegistry.sizeBytes(context) / 1e6),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    if (busy) {
        if (done >= 0f) androidx.compose.material3.LinearProgressIndicator(progress = { done }, modifier = Modifier.fillMaxWidth())
        else androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            busy = true; done = -1f; message = null
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        AircraftRegistry.download(context) { read, total -> if (total > 0) done = (read.toFloat() / total).coerceIn(0f, 1f) }
                    }
                }
                busy = false
                installed = AircraftRegistry.installed(context)
                message = result.fold({ "Done." }, { "Download failed: ${it.message ?: it.javaClass.simpleName}. Check the internet connection and try again." })
            }
        }) { Text(if (installed) "Update" else "Download (8 MB)") }
        if (installed) TextButton(enabled = !busy, onClick = {
            AircraftRegistry.delete(context); installed = false; message = "Deleted."
        }) { Text("Delete") }
    }
    SettingRow(
        "Online details (hexdb.io)",
        "Also asks hexdb.io, a free online aircraft database, for friendlier type names, the airline actually flying a " +
            "leased plane, and aircraft from outside the US. It's told the aircraft's ID code when you open its details. " +
            "hexdb.io isn't open-source or open data" + (if (BuildConfig.PUBLIC_EDITION) ", so this is off unless you turn it on." else "."),
        hexdb,
    ) { hexdb = it; prefs.hexdbLookups = it }
}

/** Warns when the phone switches Bluetooth off by itself, which stops Bluetooth scanning. */
@Composable
private fun AutoOffWarning() {
    val context = LocalContext.current
    var times by remember { mutableStateOf(AutoOff.recentBluetoothOffs(context)) }
    if (times == 0) return
    androidx.compose.material3.Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Bluetooth was switched off while scanning ($times time${if (times == 1) "" else "s"} this week)",
                style = MaterialTheme.typography.titleSmall, color = Color(0xFFFFB300),
            )
            Text(
                "If that wasn't you, your phone is probably turning Bluetooth off by itself when nothing is connected - Siren " +
                    "only listens, so it doesn't count, and Bluetooth scanning stops until it's back on. To keep it on (GrapheneOS " +
                    "and some other phones): ${AutoOff.BLUETOOTH_SETTING} > Never.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { AutoOff.dismiss(context); times = 0 }) { Text("Dismiss") }
        }
    }
}

/** Per-alert notification switches. */
@Composable
private fun AlertNotifySettings() {
    val context = LocalContext.current
    Text("Notifications", style = MaterialTheme.typography.titleSmall)
    Text(
        "Turn off notifications for alerts you don't need - they still show in Siren's lists, on the car display and " +
            "with their sound (if on).",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    AlertNotify.Kind.entries.forEach { kind ->
        var on by remember { mutableStateOf(AlertNotify.enabled(context, kind)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = on, onCheckedChange = { on = it; AlertNotify.setEnabled(context, kind, it) })
        }
    }
}

/** Bigger map text for people who find street names hard to read. */
@Composable
private fun MapTextSetting() {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(Prefs(context).mapTextScale) }
    Text("Map text size", style = MaterialTheme.typography.titleSmall)
    Text(
        "Street names are part of the map pictures, so zooming in doesn't make them bigger - this does. Larger sizes " +
            "show less of the area at once and look a little softer.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        MapText.SIZES.forEach { (value, label) ->
            androidx.compose.material3.FilterChip(
                selected = scale == value,
                onClick = { scale = value; MapText.set(context, value) },
                label = { Text(label) },
            )
        }
    }
}

/** Police call data that cities publish, downloaded automatically near each covered city. */
@Composable
private fun PoliceDataSettings() {
    val context = LocalContext.current
    val prefs = Prefs(context)
    var on by remember { mutableStateOf(prefs.policeData) }
    var alerts by remember { mutableStateOf(prefs.hotspotAlerts) }
    var live by remember { mutableStateOf(prefs.liveCalls) }
    var crashAlerts by remember { mutableStateOf(prefs.crashAlerts) }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PoliceData.ensureLoaded(context) } }
    val feeds by PoliceData.feeds.collectAsStateWithLifecycle()
    val status by PoliceData.status.collectAsStateWithLifecycle()
    SettingRow(
        "Police call data (online)",
        "Some cities publish their police calls as open data. Near one, Siren downloads it automatically: past " +
            "traffic stops become hotspots on the map (red), and live calls show where police are now (blue). " +
            "It downloads each city's whole data set, so the city isn't told where you are. More cities are " +
            "added to the list on GitHub over time - no app update needed.",
        on,
    ) { on = it; prefs.policeData = it }
    if (!on) return
    SettingRow(
        "Hotspot alerts",
        "While driving, alerts (with the camera chime) when you head toward a place with frequent traffic stops - " +
            "at least 10 in the last 3 months - at most every 30 minutes per place.",
        alerts,
    ) { alerts = it; prefs.hotspotAlerts = it }
    SettingRow(
        "Crash hotspot alerts",
        "Where a state publishes its crash reports (Utah so far), places with many crashes show in orange. This alerts " +
            "when you head toward one with at least 15 - handy on unfamiliar roads. Off by default, since busy highways have many.",
        crashAlerts,
    ) { crashAlerts = it; prefs.crashAlerts = it }
    SettingRow(
        "Live police calls",
        "Where a city publishes active calls, shows them on the map, refreshed every 2 minutes. Their addresses are " +
            "placed on the map using OpenStreetMap's address search.",
        live,
    ) { live = it; prefs.liveCalls = it }
    HorizontalDivider()
    Text("Cities", style = MaterialTheme.typography.titleSmall)
    feeds.forEach { f ->
        Column(Modifier.padding(vertical = 4.dp)) {
            Text(f.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                (if (f.live) "Live calls" else if (f.kind == "crashes") "Crash hotspots" else "Traffic-stop hotspots") +
                    " · updated ${f.updated} · " +
                    (status[f.id] ?: if (f.bounds != null) "downloads the area around you while you're in the state"
                    else "downloads when you're within ${f.radiusKm.toInt()} km"),
                style = MaterialTheme.typography.bodySmall, color = Color.Gray,
            )
        }
    }
    Text(
        "Hotspots show where stops happened, not where police are right now. Data comes from each city as published; " +
            "some calls are left out or delayed by the city.",
        style = MaterialTheme.typography.bodySmall, color = Color.Gray,
    )
}
