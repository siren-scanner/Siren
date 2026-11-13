package app.sirenscout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun ManualDialog(onClose: () -> Unit) {
    FullScreenPage(onDismiss = onClose) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("User manual", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("Close") }
                }
                val scroll = rememberScrollState()
                val positions = remember { mutableStateMapOf<String, Int>() }
                val scope = rememberCoroutineScope()
                val density = LocalDensity.current
                // A tapped tab stays highlighted even when its topic is too near the end to reach the top.
                var tapped by remember { mutableStateOf<Int?>(null) }
                var jumping by remember { mutableStateOf(false) }
                LaunchedEffect(scroll.isScrollInProgress) { if (scroll.isScrollInProgress && !jumping) tapped = null }
                val reading by remember {
                    derivedStateOf {
                        val y = scroll.value + with(density) { 48.dp.roundToPx() }
                        TOPICS.indexOfLast { (title, _) -> (positions[title] ?: Int.MAX_VALUE) <= y }.coerceAtLeast(0)
                    }
                }
                val selected = tapped ?: reading

                ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp) {
                    TOPICS.forEachIndexed { i, (title, label) ->
                        Tab(selected = i == selected, text = { Text(label) }, onClick = {
                            tapped = i
                            scope.launch {
                                jumping = true
                                scroll.animateScrollTo(positions[title] ?: 0)
                                jumping = false
                            }
                        })
                    }
                }
                CompositionLocalProvider(LocalSectionPositions provides positions) {
                    Column(
                        Modifier.verticalScroll(scroll).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ManualContent()
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualContent() {
    val distanceUnit = if (usesMiles()) "mile" else "km"

    Section("What Siren does")
    Para(
        "Siren listens for the radio signals that equipment in police, fire and EMS vehicles gives " +
            "off, and warns you when it picks one up. It only listens - it never transmits, connects to " +
            "or decodes anything."
    )

    Section("Getting started")
    Bullet("Tap Start. Allow Nearby devices, Location (precise) and Notifications when asked.")
    Bullet("Siren then asks to turn on Bluetooth and Location, to allow Wi-Fi scanning, and to be exempt from battery optimization - one tap each (for Location, some phones open the Location settings page instead; switch it on there and come back). Android doesn't let apps switch these on silently. It asks again whenever you return to the app while scanning and something has been switched off.")
    Bullet("Declined a prompt? It won't ask again until you press Start. To stop it asking about something altogether, turn that switch off under Settings > Scanning > Turn on automatically.")
    Bullet("Wi-Fi itself can stay off: Android's \"Wi-Fi scanning\" option (Location settings) is enough for Siren to see networks.")
    Bullet("The screen stays on while scanning, because Android pauses normal Bluetooth scanning when it's off. A car mount and charger work best. You can turn this off in Settings > Scanning.")
    Bullet("With the screen off, a targeted Bluetooth scan keeps watching for identifiers Android can filter on: the Flock manufacturer ID, Flock and Raven service IDs, and exact names like \"Penguin\". Everything else needs the screen on.")
    Bullet("Stop scanning with the Stop button, or the Stop action on the \"Siren is scanning\" notification.")
    Bullet("The status lines under the title show what each scanner is doing (BLE, Bluetooth, Wi-Fi, RF).")

    Section("What it detects")
    Para("Four scanners run at the same time:")
    Bullet("BLE (Bluetooth Low Energy) - continuous. Catches body cameras, Axon Signal units, radios, light bar controllers and medical gear.")
    Bullet("Bluetooth (classic) - a discovery pass every 30 seconds. Same kinds of equipment, plus car kits and headsets.")
    Bullet("Wi-Fi - a scan every 31 seconds (Android's limit), or every 8 seconds if you turn off Developer options > Wi-Fi scan throttling. To find Developer options, tap Settings > About phone > Build number seven times. If a phone refuses fast scans anyway, Siren drops back to 31 seconds by itself. The Wi-Fi status line shows the current interval.")
    Bullet("RF - optional, needs extra hardware. See \"RF monitoring\" below.")
    Para("Built-in signatures, with how sure a match is:")
    Bullet("Axon body cameras, Axon Signal and Fleet - Police, high confidence. Axon Signal units start broadcasting when the light bar or siren is switched on.")
    Bullet("Motorola / WatchGuard cameras, Motorola APX radios, Digital Ally cameras - Police, medium.")
    Bullet("Whelen, Federal Signal and Code 3 light bar and siren controllers - Fleet gear, medium.")
    Bullet("LIFEPAK, ZOLL and Stryker EMS equipment - Fire / EMS, medium.")
    Bullet("Cradlepoint, Sierra Wireless AirLink and Peplink vehicle routers, rugged in-car laptops - Fleet gear, low. Buses, utility trucks and businesses use the same hardware.")
    Bullet("Inseego (Skyus, MiFi) vehicle routers - Fleet gear, low. Also sold as personal hotspots.")
    Bullet("Flock Safety cameras and battery packs (\"Penguin\"), and Raven gunshot detectors - Plate reader / surveillance, medium. These come from community research, not the vendors. Most Flock cameras only talk over cellular and give off nothing a phone can see - the known-camera map below is more reliable.")

    Section("Colors")
    ColorKey(Category.POLICE.color(), "Police")
    ColorKey(Category.FIRE_EMS.color(), "Fire / EMS")
    ColorKey(Category.FLEET.color(), "First-responder fleet gear (light bars, routers, laptops)")
    ColorKey(Category.RADIO.color(), "RF transmission on a watched frequency")
    ColorKey(Category.SURVEILLANCE.color(), "Plate readers and surveillance sensors (also the camera icons and cones on the map)")
    ColorKey(Category.TRACKER.color(), "Item trackers - AirTag, Tile, SmartTag, Google Find Hub tags")
    ColorKey(null.color(), "Everything else (All nearby only)")
    Para("These colors are used for the stripe on each card and for map pins.")

    Section("Alerts")
    Bullet("Each kind of alert has its own sound: a short siren-style yelp for police equipment (nearby or following you), two low beeps for a public-safety radio transmitting nearby, three quick high beeps for an unknown tracker traveling with you, a rising chime for a plate reader ahead, and a falling whoosh for a drone or police aircraft. Other matches (fleet, fire / EMS) are silent unless you turn them on. The same sound won't repeat within 8 seconds.")
    Bullet("Sounds come out of the phone's own speaker, even when it's connected to the car, and music in the car is lowered briefly so you can hear them. To have them play through the car or headphones instead, turn on Play through the car when connected. With the phone on silent they don't play, unless you turn on Play even when the phone is on silent (alarm volume). Change them, or try each one with Test, in Settings > Alerts > Sounds. Alert notifications still vibrate.")
    Bullet("Teslas broadcast a Bluetooth signal for the phone key, named \"S\", 16 letters and numbers worked out from the car's VIN, and a \"C\". That name pattern is unique to Teslas, so Siren shows these as Tesla car key, without (UNK). If you drive a Tesla, yours will show up in With me on every drive; hold it and hide it by name to clear it. Other Teslas stay visible, since some police departments drive them.")
    Bullet("The banner at the top turns red while any match has been seen in the last 30 seconds, and shows the strongest one.")
    Bullet("A notification with vibration fires when a match is first seen. The same device alerts again only if it's been 2 minutes since its last alert.")
    Bullet("Signal strength is in dBm: closer to 0 is stronger. As a rough guide, -60 or stronger is very close, -75 close, -88 nearby, weaker is far.")
    Bullet("Settings > Alerts > \"Alert when signal is stronger than\" sets how strong a match must be to alert (default -90 dBm). Weaker matches are still listed, just silently.")

    Section("Police traveling with you")
    Para(
        "A separate notification warns when police equipment seems to be following you. It only " +
            "fires when all of these are true:"
    )
    Bullet("The equipment matches a Police signature (body cameras, Axon Signal, Motorola/WatchGuard, Digital Ally, APX radios).")
    Bullet("It's been near you for at least the set time (default 5 minutes).")
    Bullet("You've traveled at least the set distance in that time (default 1 $distanceUnit). Sitting next to a parked cruiser doesn't count.")
    Bullet("It was picked up in most of those minutes, not just at the start and end.")
    Para(
        "It alerts once per encounter. Routers are left out because buses and work trucks share roads " +
            "with you for a long time. Turn it off or change the time and distance in Settings > Alerts."
    )

    Section("Unknown trackers traveling with you")
    Para(
        "Siren watches for item trackers that could be used to follow you: AirTags and other " +
            "Find My devices that are away from their owner, Tile, Samsung SmartTag, Google Find Hub tags " +
            "(Chipolo, Pebblebee and others) and trackers using the new cross-platform standard."
    )
    Bullet("You get a notification when one has stayed with you for the set time (default 10 minutes) while you moved at least half a mile, and was picked up in most of those minutes. Trackers in strangers' bags that you pass don't count.")
    Bullet("An AirTag right next to its owner's iPhone sends a different signal and is ignored, so friends' AirTags won't set it off. An AirTag whose owner isn't nearby will.")
    Bullet("Trackers show in the lists in pink but never trigger the red banner or instant alerts. Only trackers that set off an alert are saved to history, as a record.")
    Bullet("To find one: tap it in the list to open its live signal meter (and turn on the beep if you like), then move slowly around your bag or vehicle - the closer you get, the closer to 0 the number goes. Common hiding spots on cars: wheel wells, bumpers, under seats, the trunk lining and the OBD port.")
    Bullet("Google Find Hub tags (and AirTags) only count when they're in their \"away from owner\" mode. Your own tag next to the phone it belongs to is ignored, even though it changes its address every few minutes.")
    Bullet("You get at most one alert per tracker type every 30 minutes, however often a tracker changes its address.")
    Bullet("If an alert is about your own tracker (for example its owner phone isn't with you), tap \"It's mine\" on the notification, or press and hold the tracker in the list and choose It's mine. That mutes alerts for that type of tracker for 24 hours. Muted types are listed in Settings > Watch rules & hidden, with Unmute.")
    Bullet("AirTags can only be made to play a sound by Apple devices, or by Apple's \"Tracker Detect\" app on Android.")

    Section("Tabs and filters")
    Bullet("Matches - equipment that matched a signature during this session.")
    Bullet("Hide everyday (All nearby, on by default) leaves out devices Siren's guesses identify as ordinary: home routers, phones, headphones, watches, TVs, speakers, printers, laptops, fitness gear, beacons, and Apple or Google tags near their owner. Matches, your watch rules and devices Siren can't identify are always shown, and vehicle hotspots stay visible. The count line says how many were hidden; tap the chip to see everything.")
    Bullet("All nearby - every device currently in range, matches first. Unmatched devices drop off 2 minutes after they're last seen.")
    Bullet("With me - devices that have stayed with you for 5+ minutes while you moved at least 500 m (about a third of a mile), longest first. Each shows \"With you 2 h 10 min · 34 mi\". Your own phone, watch, headphones and car will be here (hold one and hide it to clear it); anything you don't recognise that keeps turning up is worth a closer look. Devices that change their Bluetooth address (most phones and AirPods, about every 15 minutes) restart their count when they do.")
    Bullet("Air - drones and police aircraft around you right now (see \"Drones and police aircraft\" below).")
    Bullet("History - saved encounters from all past sessions (see below).")
    Bullet("The type buttons (All types, BLE, Bluetooth, Wi-Fi, RF) filter whichever tab you're on. Each shows how many of that type there are.")
    Bullet("Clear empties the current session's lists. On the History tab the button is Delete instead.")
    Bullet("Each card shows the source, address, signal strength, how long ago it was last seen and how many times it's been picked up. Tap a card for full details and a live signal meter.")

    Section("Details and finding a device")
    Bullet("Tap any detection - in the list, Matches or History - to open its details: what it is, why it matched (for example \"Address starts with 00:25:DF, registered to Axon Enterprise\" or \"Name matches penguin\" - useful for judging how far to trust it), the maker and type guess, address, first and last seen, how many times it was picked up, its strongest signal and where that was.")
    Bullet("While it's in range and you're scanning, a big live signal meter shows its strength (closer to 0 = closer to you), whether it's getting stronger or weaker as you move, and a graph of the last 2 minutes. Readings bounce around, so move slowly and pause a few seconds at each spot.")
    Bullet("Turn on Beep as I get closer for a Geiger-counter sound that speeds up as you approach, so you can search without watching the screen.")
    Bullet("While the details are open, the screen stays on and Bluetooth scans at full speed, even on battery, so the meter updates as fast as possible.")
    Bullet("Siren also estimates where a stationary device actually is - a parked car, a camera battery pack - from all the signal readings taken as you drove past, not just the strongest one. Its details show \"Estimated location\" with an uncertainty (for example ±35 m from 42 readings), and on the map its pin moves there with a translucent circle showing the uncertainty. One straight pass can't tell which side of the road something is on, so the circle is wide; a turn or a second pass narrows it. Devices traveling with you aren't estimated, since they don't stay in one place.")
    Bullet("Tap the coordinates under \"Where it was strongest\" to jump to the map, zoomed to street level on that spot with the device's pin open. Following pauses so the map stays there; tap Follow me to go back to your position.")
    Bullet("Hide (top right, or at the bottom) hides the device. You can also press and hold a card in the list to hide it without opening the details.")

    Section("Your own watch rules")
    Bullet("Built-in signatures can't cover every agency. If you find equipment Siren doesn't recognize - for example in All nearby while parked near a patrol car - tap it, then Watch.")
    Bullet("Choose what to match: its exact address, the start of its address (the maker's prefix), its exact name, or text its name contains. Give it a name and a category (Police, Fire / EMS, Fleet gear, or Plate reader / surveillance).")
    Bullet("Matching devices then show up and alert like built-in ones, and their details say \"Your watch rule\". Your rules take priority over built-in signatures; hidden devices still stay hidden.")
    Bullet("Many Bluetooth devices change their address every few minutes, so a name rule is usually better for Bluetooth. Wi-Fi routers keep their address.")
    Bullet("Settings > Watch rules & hidden lists them, with Remove and Add watch rule. Rules are included in backups.")

    Section("Device guesses (UNK)")
    Para(
        "When a device doesn't match a signature, Siren guesses what it is. Anything ending in " +
            "(UNK) is a guess, not something the device said about itself."
    )
    Bullet("The maker comes from the Bluetooth company ID in the broadcast, or from the first half of the address (only for fixed addresses, like routers and classic Bluetooth).")
    Bullet("The type comes from known broadcast formats (AirPods, AirTag, iPhone, Windows PC), Bluetooth service and device-type codes, and Wi-Fi network-name patterns.")
    Bullet("If a device has a name, the name stays as the title and the guess appears on a line below it.")
    Bullet("Phones and watches change their Bluetooth address every few minutes, so the same phone can appear several times, and some devices can't be guessed at all.")

    if (AiEngine.AVAILABLE_IN_BUILD) AiSection()

    Section("Hiding devices")
    Bullet("Press and hold any card to hide it, tap it and use Hide at the top of its details, or on the map tap its pin and then Hide. Hidden emissions don't appear anywhere, never alert and aren't saved.")
    Bullet("\"Hide this device\" hides one address.")
    Bullet("\"Hide everything with an address starting…\" (Wi-Fi and classic Bluetooth) hides all of one maker's or one router's addresses - handy for a router that broadcasts several networks, including unnamed ones.")
    Bullet("\"Hide everything named …\" hides every device with that exact name on any radio. Use this for your own Wi-Fi (routers broadcast several access points) and your watch or phone (their addresses keep changing).")
    Bullet("Some routers also broadcast unnamed networks from a nearly identical address. Hide those with \"Hide this device\".")
    Bullet("To undo, open Settings > Watch rules & hidden and tap Unhide.")

    Section("Map")
    Bullet("Tap Map to switch views, and List to go back. The tabs and type filters apply to the map too.")
    Bullet("Each pin is placed where your phone was when that signal was strongest - roughly your closest approach. Tap a pin for a card with Details and Hide; Hide works just like pressing and holding a row in the list. Tap anywhere else on the map to close the card.")
    Bullet("Detections made without a GPS fix in the last minute have no pin; a note above the map says how many.")
    Bullet("Your position is a dot (an arrow while you're moving). Pick its color in Settings > Map if it's hard to see against the map.")
    Bullet("The map follows you and, once you're moving faster than about 6 mph (10 km/h), turns so your direction of travel points up. It keeps its direction while you're stopped.")
    Bullet("Dragging the map pauses following. Tap Follow me to resume.")
    Bullet("Show all zooms to fit every pin and turns the map back to north-up.")
    Bullet("Map tiles come from OpenStreetMap and need a data connection the first time you view an area. Viewed areas are cached.")

    Section("Known plate-reader cameras")
    Para(
        "Siren can show license plate readers that volunteers have mapped in OpenStreetMap, " +
            "mostly through the DeFlock project. This works whether or not the camera gives off any signal."
    )
    Bullet("Cameras appear on the map as teal camera icons. A translucent teal cone shows which way each one faces, as mapped. Where the mapper recorded the camera's exact field of view, the cone is that wide; otherwise it's drawn 50° wide.")
    Bullet("Tap a camera for its maker, the compass direction(s) it faces and who operates it, where known.")
    Bullet("Camera locations are downloaded for about 15 miles (25 km) around you, or around wherever you look on the map, and kept on the phone for a week. Moving to a new area downloads that area. The map-data servers are only told the area, rounded to about 6 miles (10 km), not your exact position.")
    Bullet("While scanning and driving, you get a notification when a camera is ahead of you within the alert distance (default about 800 ft / 250 m). It says how far, and whether the camera faces toward you (it can read your front plate), the way you're going (your rear plate as you pass) or across your path. Each camera alerts at most once every 15 minutes.")
    Bullet("No alerts while you're stopped, since \"ahead\" needs a direction of travel.")
    Bullet("The map is only as complete as volunteers have made it. Unmapped cameras won't show, and directions can be off. You can add missing cameras at deflock.me.")
    Bullet("Turn the layer off or change the alert distance in Settings > Plate readers.")

    Section("Drones and police aircraft (beta)")
    Para(
        "New and not yet tested in the field - if you try it, you're a beta tester, and feedback is very welcome " +
            "(Settings > Drones & aircraft > Send feedback). Both show in the Air tab and on the map as purple arrows " +
            "pointing where they're heading. Tap one in the Air tab to see it on the map."
    )
    Bullet("Drones: most drones must broadcast Remote ID - their serial number, position, height, speed and often where their operator is standing - over Bluetooth and Wi-Fi. Siren hears it directly, with no internet, typically within a few hundred metres to a mile. The operator's broadcast position shows on the map as a grey dot. The drone's own Bluetooth or Wi-Fi signal also appears in All nearby, labelled Drone (Remote ID) - identified, not a guess, so the on-device AI doesn't look at it.")
    Bullet("Remote ID doesn't say who owns a drone, so every drone is listed. The serial number and model are your best clue: Skydio, BRINC and \"drone as first responder\" programs are mostly police and fire. Small drones under 250 g and some agencies with FAA waivers don't broadcast.")
    Bullet("Police aircraft: with Police aircraft (online) on, Siren checks the free community ADS-B feed at adsb.lol every 30 seconds while scanning, and picks out helicopters and planes registered to police departments, sheriffs, state patrols and federal law enforcement in the FAA's public aircraft registry. Aircraft owned by a city, county or state government (for example City of Los Angeles, whose police helicopters are registered that way) show in lighter purple as possible police or first responder - often police, fire or rescue, sometimes something else, like a state transport plane. It needs a data connection, and the feed is only told your area, rounded to about 10 km. Airliners and other aircraft aren't listed - turn on Show all aircraft to see them in grey (no alerts). The top of the Air tab says when the feed was last checked and how many aircraft and law-enforcement aircraft it found.")
    Bullet("You get an alert (with its own falling \"whoosh\" sound) when a drone is first heard and when a police aircraft comes within the alert distance (default 5 miles, Settings > Drones & aircraft), then again only after 30 minutes. Aircraft further away still show in the Air tab.")
    Bullet("Police can switch ADS-B off on sensitive flights, and some aircraft are registered to companies rather than the agency flying them, so no alert doesn't mean nothing overhead. The aircraft list comes from the FAA registry at the time of the app version you have.")

    Section("Flights")
    Bullet("Air tab > Search & track flights looks up any flight in the world by flight number (AA100), callsign (AAL100) or tail number (N12345), wherever it is - it doesn't need to be near you.")
    Bullet("It shows the airline, whether it's on the ground, climbing, cruising or descending, altitude and climb rate, the altitude it's heading for, speed, heading, position and distance from you, squawk code and any emergency, the registration, aircraft type and owner. It refreshes every 30 seconds while open. Tap the position to see it on the map.")
    Bullet("Aircraft don't broadcast where they're flying from or to, so enter From and To airport codes (DFW, JFK…) if you know them: Siren then shows distance and time remaining.")
    Bullet("Track & notify follows the flight in the background every minute, even when Siren isn't scanning, and notifies you when it's found, takes off, reaches cruising altitude, starts descending, is about to arrive, lands, comes near you, squawks an emergency, or goes quiet for 10 minutes. It shows on the map in orange. Stop removes it; flights are dropped after 36 hours.")
    Bullet("Coverage comes from volunteer receivers, so flights over oceans and remote areas can disappear for a while. A flight that hasn't departed yet shows as not on the feed - you'll be told when it appears.")

    Section("History and export")
    Bullet("Matched detections are saved automatically to the phone every 15 seconds while scanning, and when you stop. Unmatched devices are not saved.")
    Bullet("Each saved row is an encounter. If a device goes 10 minutes without being seen, its next sighting starts a new encounter with its own map pin.")
    Bullet("History survives restarts and app updates, but is deleted if you uninstall the app.")
    Bullet("Export (History tab) asks Safe or Full. Safe export is for sharing: addresses keep only the maker part, device and network names are left out, and locations are rounded to about 1 km. Full export keeps everything, for your own records.")
    Bullet("Either way it saves all history as a CSV file wherever you choose. Columns: first and last seen time, source, address, name, signature, category, confidence, number of sightings, last and strongest signal, latitude, longitude, guess and why it matched. It opens in Excel, Google Sheets or Google My Maps.")
    Bullet("Delete (History tab) permanently erases all saved encounters after you confirm.")

    Section("Backing up and moving phones")
    Bullet("Settings > Backup, sharing & updates > Back up saves your settings, hidden devices, ${if (AiEngine.AVAILABLE_IN_BUILD) "AI identifications " else ""}and history to one file wherever you choose (Downloads, Google Drive, a USB stick).")
    Bullet("Settings > Backup, sharing & updates > Restore loads a backup: settings and the hidden list are replaced, and history is added to what's already on the phone.")
    Bullet("Uninstalling Siren deletes its data, so back up first. To move to a new phone, back up, send the file over, install Siren there and restore.")
    Bullet("Known camera locations aren't included - they download again by themselves.")
    Bullet("Codes and connections aren't included either (the car display and sync codes, the phone you sync with, and where updates come from), and restoring never changes them. So a backup from someone else can't point your phone at their device.")

    Section("Sharing and updates")
    Bullet("Share data passes what one phone has learned to another: AI identifications, hidden devices and names, watch rules, and history if you tick it. It goes through Android's share menu (Quick Share, Bluetooth, a message). On the other phone, open Settings > Backup, sharing & updates > Add shared data and pick the file (Quick Share puts it in Downloads).")
    Bullet("Adding shared data only adds: nothing on the receiving phone is removed or replaced, and its settings stay as they are. AI identifications made by an edition of Siren with on-device AI show on any phone, even one that can't run the AI itself.")
    Bullet("History includes where things were seen, which shows where you've been. It's off unless you tick it.")
    Bullet("To sync automatically instead, use Tailscale, a free private network between your own devices (install the Tailscale app on each phone). On one phone, turn on Settings > Backup, sharing & updates > Let my other phones sync with this one; it shows its address and a code. On the other phone, enter them under Sync with another phone. From then on, every 10 minutes while both are scanning, the second phone sends its data to the first and adds the first one's. Sync now does it right away, and the line under it shows how the last sync went.")
    Bullet("Syncing, like Add shared data, only adds. Un-hiding a device on one phone doesn't un-hide it on the other. Siren only syncs over Tailscale: it listens on the phone's Tailscale address alone, only sends to Tailscale addresses (100.x.x.x or .ts.net names), and only devices that know the code can connect; New code locks out a phone you've stopped using. History is only synced if Include history is on.")
    Bullet("To give Siren to someone else, tap Settings > Backup, sharing & updates > Share Siren. It sends the copy installed on your phone through Android's share menu (Quick Share, Bluetooth, a messaging app). They open the file to install it, allowing installs from that app when Android asks. ${if (SELF_UPDATES) "Their copy won't update itself unless you share your laptop with them in Tailscale." else "Their copy won't update itself; installing Siren from F-Droid instead keeps it up to date."}")
    Bullet("Settings > Help & diagnostics > What's new lists what changed in every version, newest first.")
    Bullet("Siren is open source. Settings > Help & diagnostics > Suggest a feature or report a problem opens its project page, where you can suggest features, report bugs or propose new device signatures. Anyone may change Siren for their own needs and share their version under the same license (GPL-3.0).")
    if (SELF_UPDATES) {
        Bullet("New versions are published from your laptop over Tailscale, a private network between your own devices. Keep the Tailscale app installed and connected on your phone.")
        Bullet("Siren checks for a new version when you open it (at most every 6 hours), and offers Update. Settings > Backup, sharing & updates > Check for updates checks right away.")
        Bullet("The first time, Android asks you to allow Siren to install apps - tap Allow, switch it on, and come back. After that, each update is one tap plus Android's own confirmation.")
        Bullet("Updates keep all your data. Android only accepts an update signed with the same key as the installed app, and Siren also checks the download hasn't been altered.")
        Bullet("Can't reach the update server? Check Tailscale is connected on the phone, and that the laptop is on and awake.")
    } else {
        Bullet("This edition is updated by the app store you installed it from, such as F-Droid.")
    }

    Section("Crash reports and diagnostics")
    Bullet("If Siren crashes, it saves a report on the phone. Next time you open it, you're offered Share report, which opens Android's share menu so you can send it however you like - for example attached to a bug report (Settings > Help & diagnostics > Suggest a feature or report a problem).")
    Bullet("While scanning, Settings > Help & diagnostics shows live scan health: Bluetooth broadcasts heard in the last minute, devices found by classic Bluetooth, how long ago Wi-Fi results last arrived, and any scan failures. Zero Bluetooth broadcasts or stale Wi-Fi results come with a hint about what to check.")
    Bullet("Settings > Help & diagnostics > Share last crash sends the newest report again. Share activity log sends a record of what the app has been doing (scanners starting and stopping, download failures, declined prompts) - useful when something isn't working but nothing crashed.")
    Bullet("Reports include the app version, Android version and phone model. They don't include locations or history, and any device address in them is cut to its maker part (00:25:DF:••:••:••).")
    Bullet("The last 10 crash reports are kept. Delete clears them.")

    Section("RF monitoring (optional)")
    Para(
        "Beta: radio monitoring is built but hasn't been tested with a real RTL-SDR dongle yet. If you try it, " +
            "you're a beta tester - thank you. Feedback on what works, what doesn't and what could be better is very " +
            "welcome: Settings > Radio > Send feedback, or Settings > Help & diagnostics > Suggest a feature or report a problem. " +
            "Sharing the activity log (Settings > Help & diagnostics) with your report helps a lot."
    )
    Para(
        "A phone can't tune to police radio frequencies on its own. With a USB RTL-SDR dongle, Siren " +
            "can tell when a radio is transmitting near you. It only measures signal strength - it doesn't " +
            "play or decode audio."
    )
    Bullet("You need an RTL-SDR dongle (RTL-SDR Blog V3 or V4), a USB-C OTG adapter if the dongle has a regular USB plug, and the free \"RTL2832U driver\" app from the Play Store.")
    Bullet("Turn on RF monitoring in Settings > Radio (Auto-optimize turns it on by itself whenever the dongle is plugged in). Press Start; Siren opens the driver app, which asks for USB permission.")
    Bullet("Band watch is the main mode and needs no setup. Police and fire radios transmit on different frequencies from their towers, and some of those are reserved for public safety nationwide. Siren sweeps them and alerts when one channel suddenly gets strong - a radio transmitting close to you - wherever you are in the US.")
    Bullet("700 MHz public safety (799-805 MHz, on by default) is public safety only. The 800 MHz public-safety channels (821-824 MHz, on) are too. 800 MHz shared (806-821 MHz, off by default) covers many county systems but also utilities and businesses, so it gives more false alarms.")
    Bullet("Alerts say which band and channel, for example \"Public-safety radio transmitting nearby · 801.2063 MHz · +22 dB\". A whole conversation on a trunked system counts as one alert, even though it hops between channels.")
    Bullet("It only catches officers while they're transmitting, and it can't see agencies still on older VHF/UHF radios (common for rural sheriffs). For those, enter their frequencies under Specific frequencies.")
    Bullet("For the first few sweeps it learns each channel's usual level. The alert level (default 15 dB above usual) decides how close a transmitter must be - raise it if distant traffic sets it off, lower it if nothing ever does. Transmitters that are always on fade into the background and don't alert.")
    Bullet("Specific frequencies: optional extra channels in MHz, separated by commas (e.g. 155.475, 460.250), from radioreference.com. Pick the frequencies radios transmit on (\"input\" frequencies) for nearby detection; tower frequencies tell you the area is busy, not that someone is close.")
    Bullet("Tuner gain: raise it if nothing ever registers, lower it if everything does.")
    Bullet("To use a receiver elsewhere (e.g. a Raspberry Pi in the car running rtl_tcp), change the host and port.")

    Section("Car display (experimental)")
    Para("Siren can show a live page in another screen's web browser - for example a Tesla's - with the alert banner, matches with signal strength, scanner status and the latest alert.")
    Bullet("Turn it on in Settings > Car display, then press Start.")
    Bullet("Connect the car to the phone: turn on the phone's hotspot and join it from the car's Wi-Fi settings. Or plug the phone in and turn on USB tethering (Settings > Network & internet > Hotspot & tethering), if your car accepts it.")
    Bullet("Settings > Car display lists the phone's addresses for each connection. Type the one for the connection you're using into the car's browser, for example http://192.168.43.1:8080/abc234.")
    Bullet("The page updates every second. Tap Enable sound on it once to get a beep with each new alert (browsers only allow sound after a tap).")
    Bullet("The last part of the address is a secret code, so other devices on your hotspot can't open the page. New code changes it. After 10 wrong tries a device is ignored until the page restarts.")
    Bullet("The page only answers on the phone's hotspot and USB tethering. For a car or screen on the same Wi-Fi as the phone (at home, say), turn on Also on Wi-Fi networks - only on networks you trust, since anyone on them could try the address.")
    Bullet("While the car is on your hotspot, it may use your phone's mobile data for its own internet.")

    Section("Auto-optimize")
    Para("On by default (Settings > Scanning). It chooses these settings from what the phone is doing:")
    Bullet("Charging: the screen stays on and Bluetooth scans at full speed, so nothing is missed.")
    Bullet("On battery: the screen may sleep and Bluetooth scans at a balanced rate. The targeted screen-off scan keeps watching for Flock and Raven IDs. Plugging in or unplugging changes the screen within seconds and the Bluetooth scan speed within 10 minutes.")
    Bullet("Wi-Fi: as fast as Android allows (see Fast Wi-Fi above).")
    Bullet("Plate-reader alerts: timed to give about 15 seconds of warning at your current speed - closer in town, further out on the highway.")
    Bullet("RF monitoring: on whenever an RTL-SDR dongle is plugged in (or a remote rtl_tcp host is set).")
    Bullet("While it's on, the controls it manages are greyed out and show its choice. Turn it off to use your own settings, which are kept.")

    Section("Settings reference")
    Bullet("Settings opens a menu of sections: Alerts, Plate readers, Drones & aircraft, Scanning, Map, Radio, Watch rules & hidden, Car display, Backup sharing & updates, ${if (AiEngine.AVAILABLE_IN_BUILD) "On-device AI, " else ""}and Help & diagnostics. Tap one to open it; the back arrow (or your phone's back gesture) returns to the menu, and Done closes Settings.")
    Bullet("Turn on automatically (Bluetooth, Location, Wi-Fi scanning, battery optimization) - all on by default; changes apply from the next prompt.")
    Bullet("Scanning: fast Wi-Fi scans, targeted screen-off scan and keep screen on - all on by default. Fast Wi-Fi and the targeted scan take effect right away; keep screen on applies the next time you press Start.")
    Bullet("Alert signal strength, RF options and the traveling-with-you time and distance apply the next time you press Start.")
    Bullet("The traveling-with-you on/off switch and the Hidden list apply right away.")

    Section("Limits and tips")
    Bullet("Many emergency vehicles carry nothing these scanners can see. No alert doesn't mean no police.")
    Bullet("Matches are educated guesses based on names and makers. Low-confidence router matches are often buses, utility trucks or businesses.")
    Bullet("Walls, other cars and your phone's position all weaken signals. A phone in a mount near the windshield picks up the most.")
    Bullet("Scanning continuously uses battery, so keep the phone charging while driving.")
    Bullet("Some places restrict scanners in vehicles. Check your local laws before using RF monitoring while driving.")
}

@Composable
private fun AiSection() {
    Section("On-device AI")
    Para("Siren can ask Gemini Nano - Google's AI model that runs entirely on the phone, on supported phones like the Pixel 10 - what an unidentified device probably is. Nothing leaves the phone. It's off until you turn on Settings > On-device AI > Use on-device AI.")
    Bullet("It only works while Siren is open on screen; Android doesn't allow apps to use it in the background. In your pocket you get the regular guesses.")
    Bullet("Every 15 seconds it looks at up to 6 devices Siren couldn't match, using their names, makers and what they advertise. Its answer appears on the card and in the details, marked (AI), with a category, a confidence and its reasoning.")
    Bullet("AI guesses never trigger alerts. A device the AI calls police, fire, fleet or surveillance is highlighted in orange; one it confidently calls consumer gear is treated as everyday.")
    Bullet("Each kind of device is only asked about once - the answer is remembered, even when the device changes its address. Settings > On-device AI shows the status, lets you download the model the first time, and can forget all AI guesses.")
    Bullet("Android limits how much AI each app can use per day. If Siren hits that limit it pauses for an hour.")
}

/** Tab label for each section, in the order they appear. Titles must match the Section() calls. */
private val TOPICS = listOfNotNull(
    "What Siren does" to "About",
    "Getting started" to "Start",
    "What it detects" to "Detects",
    "Colors" to "Colors",
    "Alerts" to "Alerts",
    "Police traveling with you" to "Followed",
    "Unknown trackers traveling with you" to "Trackers",
    "Tabs and filters" to "Lists",
    "Details and finding a device" to "Finder",
    "Your own watch rules" to "Watch",
    "Device guesses (UNK)" to "Guesses",
    if (AiEngine.AVAILABLE_IN_BUILD) "On-device AI" to "AI" else null,
    "Hiding devices" to "Hiding",
    "Map" to "Map",
    "Known plate-reader cameras" to "Cameras",
    "Drones and police aircraft (beta)" to "Air",
    "Flights" to "Flights",
    "History and export" to "History",
    "Backing up and moving phones" to "Backup",
    "Sharing and updates" to "Sharing",
    "Crash reports and diagnostics" to "Diagnostics",
    "RF monitoring (optional)" to "RF",
    "Car display (experimental)" to "Car",
    "Auto-optimize" to "Auto",
    "Settings reference" to "Settings",
    "Limits and tips" to "Tips",
)

/** Where each section starts in the scrolling column, for the tabs to jump to. */
private val LocalSectionPositions = staticCompositionLocalOf<MutableMap<String, Int>> { mutableMapOf() }

@Composable
private fun Section(title: String) {
    val positions = LocalSectionPositions.current
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(top = 12.dp)
            .onGloballyPositioned { positions[title] = it.positionInParent().y.toInt() },
    )
}

@Composable
private fun Para(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ColorKey(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
    }
}
