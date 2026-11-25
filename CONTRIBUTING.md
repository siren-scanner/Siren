# Contributing to Siren

Thanks for helping. Siren is meant to be changed: fork it and make it fit your area and needs.

## Suggestions and problems

Open an issue: **Issues > New issue**, then pick a form:

- **Feature suggestion** - something Siren should do.
- **Bug report** - something that doesn't work. Attach the activity log (Settings > Help &
  diagnostics > Share activity log) or a crash report if you have one.
- **New device signature** - equipment Siren should recognize. Tell us what the device is and what
  Siren showed for it in the All nearby tab (name, maker, address prefix). Leave out anything that
  identifies a person.

You can do this from the app too: Settings > Help & diagnostics > Suggest a feature or report a
problem.

## Add your city's police call data

If your city or county publishes police calls as open data, Siren can use it. Add an entry to
[`app/src/main/assets/police_feeds.json`](app/src/main/assets/police_feeds.json) and open a pull
request, or use the [Add a place form](https://github.com/siren-scanner/Siren/issues/new?template=add_a_place.yml)
(also in the app: Settings > Police call data > Suggest a place to add). Siren reads the newest copy of that file from
GitHub every day, so a new city works for everyone without an app update.

- `"mode": "history"` - a data set of past calls with a map position (`pointField`) and time
  (`timeField`); `where` picks the traffic stops. Siren turns the last `days` into hotspots.
- `"mode": "live"` - a data set of active calls; give the call type (`typeField`), status and the
  address fields, plus `city` and `state` so the address can be found on the map.
- `lat`, `lon` and `radiusKm`: where the data applies; Siren only downloads it near there.

Only Socrata open-data portals (`"type": "socrata"`) are supported so far.

## Code

- Build and test the open-source edition: `./gradlew assembleFossDebug testFossDebugUnitTest`.
- Signatures are in `app/src/main/java/app/sirenscout/Signatures.kt`; each says why it matches
  and how confident it is.
- Keep Siren passive (it never connects to or interferes with the devices it hears) and keep data
  on the phone.
- The open-source edition must not depend on Google Play services or other closed-source
  libraries. Put anything like that in `app/src/google` (used only by the maintainer's own builds).
- Match the surrounding code's style, and add or update unit tests in `app/src/test`.
- For anything big, open an issue first so we can talk it over.

By contributing you agree your contribution is licensed under the GPL-3.0-or-later, like the rest
of Siren.
