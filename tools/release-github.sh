#!/usr/bin/env bash
# Publishes the current version to GitHub (and readies F-Droid), replacing the manual checklist.
# Run only when the maintainer says "publish".
#
#   tools/release-github.sh            checks and builds, then asks before anything public
#   tools/release-github.sh --yes      no question (the maintainer already said publish)
#
# Steps: clean tree on main -> notes present -> personal-info check of every unpushed commit ->
# tests + signed public build (signing key checked) -> tag -> F-Droid metadata -> [fdroid lint] ->
# push (the pre-push hook checks again) -> GitHub release with the APK and aircraft registry ->
# download both back and compare checksums.
set -euo pipefail
cd "$(dirname "$0")/.."
YES=0; [[ ${1:-} == --yes ]] && YES=1
# A JDK 17: set JAVA_HOME (or have java 17 on the PATH).
if [[ -n ${JAVA_HOME:-} ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
CERT=fd0c6a60323b7eddeb464690dd9ef104b9d1ae448fb28b09480a15c89f746403
REGISTRY=updates/data/aircraft-registry.txt.gz
YML=docs/fdroid/app.sirenscout.foss.yml
# Optional: an fdroidserver data folder (with ../venv) to lint the F-Droid metadata; skipped if unset.
FDROID_DATA=${FDROID_DATA:-}
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }
die() { printf '\033[31mSTOPPED: %s\033[0m\n' "$*"; exit 1; }

step "Preconditions"
[[ $(git branch --show-current) == main ]] || die "not on main"
[[ -z $(git status --porcelain) ]] || die "uncommitted changes"
gh auth status >/dev/null 2>&1 || die "gh isn't logged in"
NAME=$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' app/build.gradle.kts)
CODE=$(sed -n 's/^ *versionCode = \([0-9]*\)$/\1/p' app/build.gradle.kts)
TAG=v$NAME
echo "Version $NAME ($CODE)"
git fetch -q origin --tags
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "$TAG already exists"
[[ -n $(git rev-list HEAD --not --remotes=origin) ]] || die "nothing new to publish"
grep -q "^## $NAME " app/src/foss/assets/RELEASE_NOTES.md || die "no '## $NAME' section in app/src/foss/assets/RELEASE_NOTES.md"
[[ -s fastlane/metadata/android/en-US/changelogs/$CODE.txt ]] || die "no fastlane changelog $CODE.txt"
[[ -s $REGISTRY ]] || die "$REGISTRY missing"

step "Personal-info check of every unpushed commit"
tools/pii-check.sh HEAD --not --remotes=origin

step "Tests and signed public build"
./gradlew -q testFossDebugUnitTest testStableDebugUnitTest assembleFossRelease
APK=app/build/outputs/apk/foss/release/app-foss-release.apk
APKSIGNER=$(ls -d "$HOME"/Android/Sdk/build-tools/*/apksigner | sort -V | tail -1)
"$APKSIGNER" verify --print-certs "$APK" | grep -qi "SHA-256 digest: $CERT" || die "APK isn't signed with the release key"
OUT=$(mktemp -d); cp "$APK" "$OUT/Siren-$NAME.apk"; cp "$REGISTRY" "$OUT/"
awk -v v="## $NAME " 'index($0, v) == 1 { on = 1; next } on && /^## / { exit } on' app/src/foss/assets/RELEASE_NOTES.md > "$OUT/notes.md"
echo "Built $OUT/Siren-$NAME.apk"

step "Tag and F-Droid metadata"
RELEASE_COMMIT=$(git rev-parse HEAD)
python3 - "$YML" "$NAME" "$CODE" "$RELEASE_COMMIT" <<'PY'
import re, sys
p, name, code, commit = sys.argv[1:]
s = open(p).read()
s = re.sub(r"(  - versionName: ).*\n(    versionCode: ).*\n(    commit: ).*\n", rf"\g<1>{name}\n\g<2>{code}\n\g<3>{commit}\n", s, count=1)
s = re.sub(r"(?m)^CurrentVersion: .*$", f"CurrentVersion: {name}", s)
s = re.sub(r"(?m)^CurrentVersionCode: .*$", f"CurrentVersionCode: {code}", s)
open(p, "w").write(s)
PY
git diff --stat -- "$YML"
if [[ -n $FDROID_DATA && -x $FDROID_DATA/../venv/bin/fdroid ]]; then
    grep -v '^#' "$YML" | sed '/./,$!d' | sed "s|^Repo: .*|Repo: $PWD|" > "$FDROID_DATA/metadata/app.sirenscout.foss.yml"
    (cd "$FDROID_DATA" && ../venv/bin/fdroid lint app.sirenscout.foss) || die "fdroid lint failed"
else
    echo "(FDROID_DATA not set to an fdroidserver data folder; skipping fdroid lint)"
fi

step "Ready to publish $NAME to https://github.com/siren-scanner/Siren"
cat "$OUT/notes.md"
if (( ! YES )); then read -r -p "Type 'publish' to push and release: " a; [[ $a == publish ]] || { git checkout -q -- "$YML"; die "not published"; }; fi
git tag -a "$TAG" -m "Siren $NAME" "$RELEASE_COMMIT"
git commit -q -m "F-Droid metadata for $NAME" -- "$YML"
git push origin main "$TAG"
gh release create "$TAG" "$OUT/Siren-$NAME.apk" "$OUT/aircraft-registry.txt.gz" --title "Siren $NAME" --notes-file "$OUT/notes.md" --latest

# The vector map's engine, downloaded on request by the app: one release per MapLibre version.
VTAG=maplibre-$(sed -n 's/.*MAPLIBRE_VERSION = "\(.*\)"/\1/p' app/src/main/java/app/sirenscout/VectorLib.kt)
if ! gh release view "$VTAG" >/dev/null 2>&1; then
    step "Vector map engine release $VTAG"
    tools/build-vector-lib.sh "$OUT/vector"
    gh release create "$VTAG" "$OUT"/vector/*.so.gz --title "Vector map engine (${VTAG#maplibre-})" --latest=false \
        --notes "MapLibre's native library, gzipped, for Siren's optional vector map. The app checks each file's SHA-256 before using it; you don't need to download these yourself."
fi

step "Verify"
CHECK=$(mktemp -d)
gh release download "$TAG" -D "$CHECK" -p '*'
for f in "Siren-$NAME.apk" aircraft-registry.txt.gz; do
    [[ $(sha256sum < "$OUT/$f") == $(sha256sum < "$CHECK/$f") ]] || die "$f on GitHub doesn't match the build"
done
LATEST=$(gh release view --json tagName -q .tagName)
[[ $LATEST == "$TAG" ]] || die "GitHub's latest release is $LATEST, not $TAG"
echo "Published: https://github.com/siren-scanner/Siren/releases/tag/$TAG"
rm -rf "$CHECK"
