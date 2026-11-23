# Siren release notes

Newest first. Each release adds a "## <version> · <date>" section with "- " bullets.

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
