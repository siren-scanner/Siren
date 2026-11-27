# Siren release notes

Newest first. Each release adds a "## <version> · <date>" section with "- " bullets.

## 1.1.8 · Nov 27, 2026
- Fixes from a code and security review: police gear gathered in one spot (an incident, a precinct) is never mistaken for a business and silenced; with the map dragged shut, the red alert banner shows above the list; "Possible police vehicle" no longer repeats every few seconds; a single radio blip no longer counts as a tire sensor; "Forget my car's tire sensors" works while scanning; the built-in dongle driver comes back by itself after a loose cable, stops cleanly, only serves Siren (other apps can't listen to the dongle) and can't be crashed by another app; the vector map stops using GPS when you leave Siren.

## 1.1.7 · Nov 27, 2026
- Map view: the map and list get most of the screen (one slim row on top; filters scroll with the list); a red alert bar on the map (also in full screen) says what was detected and how close, flashing when new, and tapping it goes there; map buttons are round icons - compass rose for north up / heading up, corner brackets for full screen, magnifier, layers, camera, crosshair to follow you.

## 1.1.6 · Nov 27, 2026
- Map and list together: the map on top, the list below, with a handle to drag between them. Tapping a device can show it on the map or open its details (a toggle above the list); tapping a pin scrolls the list to it. On the smaller map, the pin card is one line and the buttons scroll.

## 1.1.5 · Nov 27, 2026
- Fewer false matches: Zebra printers only count when still named with their factory serial (store printers like "WMP04.1766" no longer match); five or more of the same kind of gear in one spot counts as a business and stays out of Matches and alerts; passing AirTags and Tiles only show under Matches once they have been with you.

## 1.1.4 · Nov 27, 2026
- Wi-Fi: after the screen had been off, fast scans (every 8 s) could stay off for 10 minutes or more once you turned it back on; they now come back as soon as Siren is on screen.

## 1.1.3 · Nov 27, 2026
- Radio (RTL-SDR dongle): Siren drives the dongle itself - no separate driver app (Android asks once to allow USB access). New: tire sensors - every car's tires broadcast fixed IDs on 315 MHz, so the same car heard with you for 10+ minutes over 5+ km at several places gives "A car may be following you", even an unmarked car; your own car is learned after two drives. Mark this moment (under the scan status or on the notification) saves the time and place; History opens the log at that moment with what was around outlined. Fixes: a stale first GPS fix no longer counts as miles traveled; clearer radio connection messages.

## 1.1.2 · Nov 27, 2026
- Find its direction (chest sweep), in a live device's details: hold the phone upright against your chest and turn slowly in a circle; your body blocks the signal from behind, so an arrow locks onto the direction it's strongest from and keeps pointing there as you turn. After RF Sentinel's Locate.

## 1.1.1 · Nov 26, 2026
- Vector map: opens at street level where you are (it could open on the whole US, also after switching full screen), Follow me from a zoomed-out view comes in to street level, it follows from the start, and it keeps up with you while driving (it used to trail a second or two behind).

## 1.1.0 · Nov 26, 2026
- Vector map: Follow me now keeps following (it stopped as soon as the map turned), in heading-up, north-up and hand-turned modes; the map draws right away when opened.
- Vector map: pins now show their colors (they were green), and Show all and recorded drives leave room for the buttons and the card at the bottom.

## 1.0.99 · Nov 26, 2026
- Map: Layers button with a choice of map - the new Vector map (sharp street names at any zoom, from OpenFreeMap) or the classic OpenStreetMap map - a history heatmap of where flagged equipment showed up, and recorded drives you can look at again with the flagged equipment along them. The vector map is an optional one-time download of about 4 MB, so the app stays small.

## 1.0.98 · Nov 26, 2026
- New, from ideas in the open-source RF Sentinel app: Watch this place (learns a spot, then shows what is new, gone or back); possible police vehicle when different police gear moves together; devices that switch Bluetooth address are followed as the same device; many more identifiers (Axon/TASER body cam IDs, camera glasses, card skimmers, Motorola radios, e-ticket printers, breath testers, ambulance gear, ShotSpotter, Cellebrite/GrayKey, more Flock and SmartTag IDs).

## 1.0.97 · Nov 26, 2026
- Fixes from a code review: fake Wi-Fi warnings no longer flag neighbours sharing a default name like "linksys", mesh systems and extenders, or Enhanced Open networks; a flagged network stays flagged instead of re-alerting, and Hide on it works. Something that really traveled with you stays in With me after a long stop. A possible crash on the With me tab is fixed. Grouped pins regroup on every zoom. The scanned join screen no longer comes back after rotating. Wi-Fi readings of 0 (no reading) no longer move pins.

## 1.0.96 · Nov 26, 2026
- Search together: the search window shows a QR code; others scan it with their phone camera and Siren opens the join screen with the code filled in.

## 1.0.95 · Nov 26, 2026
- Map: the pin you picked (tapped, or Show on map from the list) is bigger and bright blue, on top of the others; crowded everyday pins are grouped into a bubble with a count (tap to zoom in); Wi-Fi networks without a signal reading no longer show "strongest 0 dBm".

## 1.0.94 · Nov 26, 2026
- Fixed: devices around you showed "With you" while you were parked (for example at a charger): GPS position wobble was counted as miles traveled. Distance now only counts while the GPS says you are moving, and "with you" - plus the police-following and tracker alerts - also needs the device to have been heard at places along the way.

## 1.0.93 · Nov 26, 2026
- Fake Wi-Fi warnings (evil twins): Siren flags a network copying another one's name - an open copy of a password-protected network, a copy of the network you're connected to from different hardware, or Wi-Fi attack gear - with its own notification switch.
- Report camera: a button on the map saves the spot of a camera that isn't mapped; Settings > Cameras lists them with links to add each one to DeFlock or OpenStreetMap.
- Cameras load ahead of you on the highway (about 20 miles), and camera downloads back off properly when the map servers are down instead of retrying every minute.
- Cleaner map and Follow me keeps your zoom.

## 1.0.92 · Nov 26, 2026
- Follow me keeps your zoom: it re-centres the map on you instead of zooming back in.

## 1.0.91 · Nov 26, 2026
- Cleaner map: estimate circles only show for the device you tap, and for matched devices once their location is narrowed down - no more pile of big circles around you on All nearby.

## 1.0.90 · Nov 25, 2026
- Fixed: after the screen had been off, Wi-Fi scanning stayed at every 31 seconds instead of going back to every 8. Scans Android refuses while the screen is off no longer count against fast scanning, and a fast scan is tried as soon as the screen comes on.

## 1.0.89 · Nov 25, 2026
- Updates in the public version: Settings > Backup, sharing & updates > Check for updates asks GitHub for the newest version and opens its release page. Copies installed from F-Droid say to update there instead.

## 1.0.88 · Nov 25, 2026
- Fixed: the Cameras button in the user manual jumped to the top instead of the cameras section, and the Police call data and Search together sections now have buttons too.

## 1.0.87 · Nov 25, 2026
- Settings > Search together has one list of mesh radios: tick the one you are carrying (or a MeshCore and a Meshtastic radio to bridge them). Siren works out which kind each is.

## 1.0.86 · Nov 25, 2026
- Settings > Search together: each radio list only shows its own kind (MeshCore or Meshtastic), and the search card shows the full name of what you are looking for. Meshtastic verified on a T-Echo: Siren connects and sets up its search channel.

## 1.0.85 · Nov 25, 2026
- Search together over Meshtastic: pick your Meshtastic radio in Settings > Search together, and searches reach other Meshtastic searchers miles away. A phone with both a MeshCore and a Meshtastic radio (or a radio and the hotspot) passes messages between them, so mixed teams hear each other.

## 1.0.84 · Nov 24, 2026
- The mesh radio picker in Settings > Search together now only lists mesh radios (MeshCore, Meshtastic and common radio boards), with Show all paired devices for anything else. Search together over MeshCore verified radio to radio between two T-Echos.

## 1.0.83 · Nov 24, 2026
- Search together over MeshCore: pick your paired MeshCore companion radio in Settings > Search together, and searches also reach other searchers miles away without phone signal (updates about every 20 seconds). Siren connects to the radio itself during a search, adds a private "Siren search" channel and removes it afterwards.

## 1.0.82 · Nov 24, 2026
- Search together: several people can look for the same device at once. Tap a device > Search together… to get a code; others tap Join a search on the map. Phones on the same Wi-Fi or hotspot share positions and readings every 2 seconds, encrypted with the code, and every map shows the other searchers and one combined estimate.

## 1.0.81 · Nov 24, 2026
- Seen before: a device's details now list every other time Siren saw it - when, for how long, how strong, and where (Map). Trackers seen 5+ times are now kept in history so they have one too.

## 1.0.79 · Nov 24, 2026
- Suggest a place: Settings > Police call data > Suggest a place to add (and a link on the Search a place card) opens a short form on the project page to ask for a city, county or state to be added.

## 1.0.78 · Nov 24, 2026
- Fixed: classic Bluetooth devices were never listed. Android found them, but its Bluetooth app's results didn't reach Siren; the Bluetooth tab now fills in with discoverable devices (in-car computers, radios and anything in pairing mode).

## 1.0.77 · Nov 24, 2026
- Settings > Storage: choose how much space saved map pictures may use (50-500 MB, 100 MB by default), clear them, and see or clear the downloaded police, crash and aircraft data.

## 1.0.76 · Nov 24, 2026
- Search a place: find any town or address from the map; Siren loads what it has for that area (cameras, police and crash data) and sums it up in a card.

## 1.0.75 · Nov 24, 2026
- Crash hotspots: Utah's crash reports (UDOT) show as orange discs on the map - places with many crashes, with how many and when - plus optional alerts (Settings > Police call data > Crash hotspot alerts). Police call data also shows its source in each alert.

## 1.0.74 · Nov 24, 2026
- Turn the map by hand with two fingers; the orientation button then offers Reset to north.

## 1.0.73 · Nov 24, 2026
- Police call data (Settings > Police call data): near cities that publish their police calls, Siren downloads them automatically. New Orleans: traffic-stop hotspots from the last 3 months (red discs, tap for how many and the usual time), with an alert when you drive toward a busy one. Dallas: live police calls on the map (blue dots). More cities can be added to the list on GitHub without an app update.

## 1.0.72 · Nov 24, 2026
- Readable map text: street names are now drawn at the right size for the screen (they were tiny on sharp phone screens), and Settings > Map > Map text size makes them larger still - up to Largest.
- Map orientation button: switch between Heading up (the map turns with your direction of travel) and North up. Siren remembers your choice.
- Full screen map: the Full screen button shows the map on its own; Exit full screen or Back returns.
- Fixed the map sometimes staying turned after rotating the phone.

## 1.0.70 · Nov 23, 2026
- Fewer false vehicle-router alerts: Cradlepoint routers named after shop and office models (E300, E3000, AER2200, CBA850…) aren't matched any more, and Cradlepoint, Sierra, Peplink and Inseego routers only alert when their name is a vehicle model's (like IBR900) or police or fire / EMS gear is also nearby. A router seen in the same place on an earlier day is treated as fixed and stays quiet. Settings > Alerts > Vehicle routers on their own brings back the old behavior.

## 1.0.69 · Nov 20, 2026
- Fast Wi-Fi scans: with Developer options > Wi-Fi scan throttling turned off, Wi-Fi is now scanned every 8 s. Newer Android versions hide that setting from apps, so Siren used to stay at 31 s; it now tries fast scans itself and drops back to 31 s only if the phone refuses them.

## 1.0.68 · Nov 20, 2026
- Notification switches: Settings > Alerts > Notifications lets you turn notifications on or off for each kind of alert. For example, quiet the Flock, Raven and other surveillance sensors that can be frequent while driving. Alerts you turn off still show in Siren's lists, on the car display and with their sound, if that's on.

## 1.0.66 · Nov 18, 2026
- Speed and red-light cameras: mapped ones from OpenStreetMap show on the map as amber speedometer icons (with the limit, where mapped) and alert you when one is ahead with their own three-note chime. Settings > Plate readers is now Settings > Cameras.
- Aircraft details: download the US aircraft registry (about 8 MB, from the FAA's public records) in Settings > Drones & aircraft to see the make and model, year built, engine, seats, owner and home base of US aircraft in flight details, offline. Delete it there any time.
- Optional online details from hexdb.io (off unless you turn it on) add friendlier type names, the airline flying leased planes, and aircraft from outside the US.

## 1.0.62 · Nov 16, 2026
- Distances read naturally: "within 5 ft" instead of "within 5 ft away" in the banner and alerts.
- Store screenshots added.

## 1.0.61 · Nov 16, 2026
- Flight details no longer look up aircraft type and owner from hexdb.io, so Siren only uses open data services. The aircraft type code from the flight feed still shows.

## 1.0.60 · Nov 16, 2026
First public release.
- Listens for Bluetooth, Bluetooth LE and Wi-Fi signals from police, fire and EMS equipment (body cameras, Axon Signal, radios, light-bar controllers, vehicle routers) and alerts you, with a rough distance.
- Flock and Raven plate readers, plus mapped plate-reader cameras from OpenStreetMap / DeFlock with the direction they face (opt-in, downloads from the internet).
- Unknown trackers that travel with you (AirTag, Tile, SmartTag, Google Find Hub tags), and police equipment that follows you.
- Drones, from their Remote ID broadcasts, and police aircraft from the free adsb.lol feed (opt-in), in the Air tab and on the map.
- Look up and track any flight, with notifications on the days you choose.
- Map, history with CSV export, your own watch rules, hiding your own devices, alert sounds, sharing with your other phones, a page for your car's browser (beta), and radio monitoring with an RTL-SDR dongle (beta).
