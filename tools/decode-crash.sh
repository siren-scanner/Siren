#!/usr/bin/env bash
# Turns the scrambled class/method names in a release crash report back into real ones.
# Usage: tools/decode-crash.sh report.txt   (the report's "Version: x.y.z" picks the mapping file)
set -euo pipefail
report=${1:?usage: decode-crash.sh report.txt}
version=$(grep -m1 -o 'Version: [0-9.]*' "$report" | cut -d' ' -f2)
# Siren Test reports say e.g. "Version: 1.0.25-test" and have their own mapping files.
if grep -q -m1 'Version: [0-9.]*-test' "$report"; then
  mapping="$(dirname "$0")/../release/mapping-test-$version.txt"
else
  mapping="$(dirname "$0")/../release/mapping-$version.txt"
fi
[ -f "$mapping" ] || { echo "No mapping file for version $version ($mapping)" >&2; exit 1; }
export JAVA_HOME=${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.21+9}
"$HOME/Android/Sdk/cmdline-tools/latest/bin/retrace" "$mapping" "$report"
