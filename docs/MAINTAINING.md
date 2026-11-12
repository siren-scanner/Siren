## Build

Debug build (installs as a separate signature from release builds):

```
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

There are two apps, built as product flavors, that install side by side:

| Flavor | App | Package | Update channel |
|---|---|---|---|
| `stable` | Siren | `app.sirenscout` | `updates/` |
| `beta` | Siren Test | `app.sirenscout.beta` | `updates/beta/` |

New features go to Siren Test first. Release builds (signed, shrunk):

```
./gradlew assembleStableRelease assembleBetaRelease
tools/publish-update.sh "notes"          # Siren
tools/publish-update.sh --test "notes"   # Siren Test
```

The release signing key is `~/.android/sirenscout-release.jks`, with its passwords in
`~/.android/sirenscout-keystore.properties`. Both stay outside the project. **Back them up**:
installed copies only accept updates signed with this key.

Needs JDK 17 and the Android SDK (platform 35). You can also open the folder in Android Studio.

## Open-source edition and F-Droid

The `foss` flavor is the public edition. It must build without Google libraries: those are only
in the `stable` and `beta` flavors (`src/google`, `stableImplementation` / `betaImplementation`).

Private settings stay out of the shared code:
- `local.properties`: `siren.updateUrl` and `siren.updateFallbackUrl`, the self-update server for
  Siren and Siren Test.
- `private/res/xml/network_security_config.xml`: allows plain HTTP to that server.
- `private/assets/RELEASE_NOTES.md`: Siren and Siren Test's release notes. The open-source
  edition's are in `app/src/foss/assets/RELEASE_NOTES.md`; add a section there for each public
  release, plus `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` for F-Droid.
- Both are ignored by git. Without them, Siren and Siren Test build but don't update themselves.

`gradle.properties`: `siren.repoUrl` is the project page that the in-app *Suggest a feature* and
*Source code* links open.

To release on F-Droid:
1. Tag the release `v<versionName>` and push the tag.
2. The first time, submit `docs/fdroid/app.sirenscout.foss.yml` to
   https://gitlab.com/fdroid/fdroiddata as `metadata/app.sirenscout.foss.yml` (merge request).
   After that, F-Droid picks up new tags by itself.
3. Store text and screenshots come from `fastlane/metadata/android/en-US`; add a
   `changelogs/<versionCode>.txt` for each release.
