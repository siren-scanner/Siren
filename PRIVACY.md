# Privacy

Siren has no accounts, analytics, ads or tracking, and no server of its own.

**What stays on your phone:** everything Siren detects, your history (including where things were
seen), hidden devices, watch rules, settings, crash reports and the activity log. Uninstalling
Siren deletes all of it.

**What goes over the network:**
- **Map tiles** from OpenStreetMap's tile servers, for the area you're looking at.
- **Plate-reader locations** (Settings > Plate readers, if on - it's off until you turn it on) from
  OpenStreetMap's Overpass API, for the area around you, rounded to about 10 km.
- **Police aircraft** (Settings > Drones & aircraft > Police aircraft, if on) from the free ADS-B feed
  at adsb.lol, every 30 seconds while scanning, for your area rounded to about 10 km.
- **Flights you look up or track:** the flight number, callsign or tail number goes to adsb.lol. Your
  location isn't sent; distance from you is worked out on the phone.
- Only if you turn them on yourself:
  - **Car display:** a web page served by your phone to devices on the same local network
    (your car on the phone's hotspot, for example), protected by a code.
  - **Phone sync:** your phones exchange AI identifications, hidden devices, watch rules and,
    if you choose, history, directly with each other over your own Tailscale network.
  - **Radio monitoring:** a connection to the RTL-SDR driver on the phone, or to an `rtl_tcp`
    receiver you name.

**What you choose to share:** backups, shared-data files, CSV exports, crash reports and the
activity log only leave the phone when you send them. Safe export, crash reports and the activity
log leave out or shorten device addresses and exact locations.

**Permissions:** Bluetooth and Wi-Fi scanning (to hear devices), location (Android requires it for
scan results, and for the map and history), notifications (alerts), and running in the background
while scanning.
