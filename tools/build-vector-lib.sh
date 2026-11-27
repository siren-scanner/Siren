#!/usr/bin/env bash
# Makes the vector map's optional download files (MapLibre's native library, gzipped) from the
# MapLibre release Gradle downloaded, into updates/data/ (the maintainer's update server), and
# checks their SHA-256 against VectorLib.kt. The same files go on the GitHub release
# "maplibre-<version>" (tools/release-github.sh adds it when missing).
set -euo pipefail
cd "$(dirname "$0")/.."
VER=$(sed -n 's/.*MAPLIBRE_VERSION = "\(.*\)"/\1/p' app/src/main/java/app/sirenscout/VectorLib.kt)
AAR=$(find ~/.gradle/caches/modules-2/files-2.1/org.maplibre.gl/android-sdk/"$VER" -name "android-sdk-$VER.aar" | head -1)
[[ -n $AAR ]] || { echo "MapLibre $VER isn't in the Gradle cache - build the app once first"; exit 1; }
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
OUT=${1:-updates/data}; mkdir -p "$OUT"
for abi in arm64-v8a armeabi-v7a; do
    unzip -q -o "$AAR" "jni/$abi/libmaplibre.so" -d "$TMP"
    sum=$(sha256sum "$TMP/jni/$abi/libmaplibre.so" | cut -d' ' -f1)
    grep -q "\"$abi\" to \"$sum\"" app/src/main/java/app/sirenscout/VectorLib.kt || { echo "VectorLib.kt has a different SHA-256 for $abi: $sum"; exit 1; }
    gzip -9 -n -c "$TMP/jni/$abi/libmaplibre.so" > "$OUT/maplibre-$VER-$abi.so.gz"
    echo "$OUT/maplibre-$VER-$abi.so.gz  $(du -h "$OUT/maplibre-$VER-$abi.so.gz" | cut -f1)  sha256(so)=$sum"
done
