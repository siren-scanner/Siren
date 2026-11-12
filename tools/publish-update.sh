#!/usr/bin/env bash
# Publishes a release APK to the update folder Tailscale serves to the phones.
# Usage: tools/publish-update.sh [--test] "What changed"
#   default  -> Siren (stable):    updates/latest.json
#   --test   -> Siren Test (beta): updates/beta/latest.json
# Build first: ./gradlew assembleStableRelease / assembleBetaRelease
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
flavor=stable; dir="$root/updates"; prefix="Siren"; mapprefix="mapping"
if [ "${1:-}" = "--test" ]; then flavor=beta; dir="$root/updates/beta"; prefix="Siren-Test"; mapprefix="mapping-test"; shift; fi
notes=${1:-}
apk_in="$root/app/build/outputs/apk/$flavor/release/app-$flavor-release.apk"
badging=$("$HOME/Android/Sdk/build-tools/35.0.0/aapt2" dump badging "$apk_in")
code=$(grep -o "versionCode='[0-9]*'" <<<"$badging" | grep -o '[0-9]*')
name=$(grep -o "versionName='[^']*'" <<<"$badging" | cut -d"'" -f2)
apk="$prefix-$name.apk"

# Every published version needs an entry in the in-app release notes (built into the APK).
if ! grep -qE "^## ${name//./\\.} " "$root/private/assets/RELEASE_NOTES.md"; then
  echo "No release notes for $name - add a '## $name · date' section to private/assets/RELEASE_NOTES.md and rebuild." >&2
  exit 1
fi

mkdir -p "$dir"
# Refuse to republish: a failed build leaves the previous APK behind, and phones only update on a higher versionCode.
if [ -f "$dir/latest.json" ]; then
  published=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["versionCode"])' "$dir/latest.json")
  if [ "$code" -le "$published" ]; then
    echo "Version $name ($code) is not newer than the published one ($published) - did the build fail or the version not get bumped?" >&2
    exit 1
  fi
fi

cp "$apk_in" "$dir/$apk"
cp "$apk_in" "$root/release/$apk"
cp "$root/app/build/outputs/mapping/${flavor}Release/mapping.txt" "$root/release/$mapprefix-$name.txt"
sha=$(sha256sum "$dir/$apk" | cut -d' ' -f1)
python3 -I -c 'import json,sys; print(json.dumps({"versionCode": int(sys.argv[1]), "versionName": sys.argv[2], "apk": sys.argv[3], "sha256": sys.argv[4], "notes": sys.argv[5]}, indent=2))' \
  "$code" "$name" "$apk" "$sha" "$notes" > "$dir/latest.json.tmp"
mv "$dir/latest.json.tmp" "$dir/latest.json"   # atomic, so phones never see half a file
# Keep the three newest APKs in this channel.
ls -1t "$dir"/Siren*-*.apk | tail -n +4 | xargs -r rm --
echo "Published $prefix $name ($code): $apk"
