# Siren

Siren is an Android app that listens for the radio signals police, fire and EMS equipment gives
off, and tells you when some is nearby. It also warns you about plate-reader cameras (Flock,
Raven and others) and unknown trackers traveling with you.

Everything happens on your phone. Siren only listens: it never connects to the devices it hears,
and nothing you see is sent anywhere.

Siren is open source under the GPL-3.0. Change it for your own needs, and share your
improvements back if you can. **Suggestions, bug reports and new device signatures are welcome**:
see [CONTRIBUTING.md](CONTRIBUTING.md), or tap *Settings > Help & diagnostics > Suggest a
feature or report a problem* in the app.

## What it detects

| Scanner | How | Looks for |
|---|---|---|
| Bluetooth LE | continuous scan | Axon body cameras and Axon Signal, Motorola/WatchGuard cameras, APX radios, Whelen / Federal Signal / Code 3 light-bar controllers, LIFEPAK / ZOLL / Stryker EMS gear, Flock and Raven units, AirTags, Tiles, SmartTags and Google Find Hub tags away from their owner |
| Classic Bluetooth | discovery every 30 s | the same device names |
| Wi-Fi | scan every 31 s (Android's limit) | Cradlepoint, Sierra Wireless AirLink, Peplink and Inseego vehicle routers |
| Drones (beta) | Bluetooth / Wi-Fi Remote ID | any drone broadcasting FAA Remote ID: serial, position, height, operator position |
| Police aircraft (beta, online) | adsb.lol ADS-B feed | helicopters and planes registered to police, sheriffs, state patrols and federal law enforcement (FAA registry) |
| Flights | adsb.lol ADS-B feed | look up and track any flight by number, callsign or tail number, with status notifications; optional US aircraft registry download (FAA) for make, model, year and owner |
| Radio (optional, beta) | RTL-SDR dongle | transmissions on police / fire bands (Band watch) or on channels you choose |

Axon Signal is the most telling: those units start broadcasting when an officer turns on the
light bar or siren, so the cameras start recording. Routers are low-confidence, because buses,
utility trucks and businesses use the same hardware.

Other features: a map of where things were seen, with estimated locations for parked devices;
mapped plate-reader cameras from OpenStreetMap / DeFlock, and speed and red-light cameras, with the direction they face; alerts
when police equipment or a tracker travels with you; your own watch rules; hiding your own
devices; history with CSV export; alert sounds; a page for your car's browser; and sharing what
one phone has learned with another.

Signatures live in [`Signatures.kt`](app/src/main/java/app/sirenscout/Signatures.kt). Use the
**All nearby** tab near a parked emergency vehicle to see what it gives off.

## Editions

| Edition | Package | Notes |
|---|---|---|
| `foss` | `app.sirenscout.foss` | The open-source edition, for F-Droid. No Google libraries. |
| `stable` / `beta` | `app.sirenscout` / `.beta` | The maintainer's own builds: add Google Play services (in-app "turn on location" prompt) and on-device AI guesses (Gemini Nano through ML Kit, closed source), and update themselves from a private server. |

Build the open-source edition with:

```
./gradlew assembleFossDebug          # or assembleFossRelease
```

Needs JDK 17 and the Android SDK (platform 35), or open the folder in Android Studio. Unit tests:
`./gradlew testFossDebugUnitTest`.

## Radio monitoring

> **Beta:** radio monitoring is built but hasn't been tested with a real RTL-SDR dongle yet. If
> you try it, you're a beta tester - thank you! Please
> [open an issue](https://github.com/siren-scanner/Siren/issues/new/choose) with what worked,
> what didn't and what could be better, ideally with the activity log (Settings > Help &
> diagnostics > Share activity log).

A phone can't receive arbitrary frequencies by itself, so radio needs extra hardware: a USB
RTL-SDR dongle (RTL-SDR Blog V3/V4) with a USB-C OTG adapter, and the **RTL2832U driver** app
(`marto.rtl_tcp_andro`). Siren only notices that someone is transmitting; it doesn't decode
audio.

## Limits and the law

- Detection depends on what a vehicle carries. Many emit nothing these scanners can see, so no
  alert doesn't mean no police.
- Matches are educated guesses from names and makers.
- Android needs **Location on** to report Bluetooth and Wi-Fi scan results.
- Some places restrict radio scanners in vehicles. Check your local laws before using radio
  monitoring while driving. Don't use the app while driving; let it alert you instead.

## Privacy

See [PRIVACY.md](PRIVACY.md). In short: no accounts, no analytics, no ads. The only network
requests are map tiles from OpenStreetMap and, if you turn them on, camera locations from
OpenStreetMap's Overpass API and police aircraft from the adsb.lol ADS-B feed. Lookups only send your
area, rounded to about 10 km. The optional aircraft registry is a one-off download from this
project's releases; optional online aircraft details come from hexdb.io.

## Credits

Siren was created by [siren-scanner](https://github.com/siren-scanner) and built with
[Claude](https://claude.ai), Anthropic's AI assistant: the creator directed and tested it, and
Claude wrote the code. Contributions from anyone are welcome.

- Maps © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, shown with
  [osmdroid](https://github.com/osmdroid/osmdroid). Plate-reader locations from OpenStreetMap,
  mapped largely by the [DeFlock](https://deflock.me) community.
- Device-maker lists: see [NOTICE.md](NOTICE.md).

## License

Copyright © 2026 siren-scanner and the Siren contributors. Licensed under the
[GNU General Public License v3.0 or later](LICENSE).
