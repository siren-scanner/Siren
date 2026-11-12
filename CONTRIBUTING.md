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
