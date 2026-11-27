#!/usr/bin/env bash
# Blocks personal info from reaching the public repo. Checks EVERY commit in a range (default:
# everything not yet on GitHub), not just the end result, since an intermediate commit that adds
# and later removes something still publishes it.
#
#   tools/pii-check.sh [rev-list args]   default origin/main..HEAD; e.g. abc123 --not --remotes=origin
#
# Blocks on: anything in private/pii-patterns.txt (names, emails, home networks, device names),
# any email address other than GitHub noreply / example ones, Tailscale hostnames, and commits
# whose author or committer isn't the public identity. Warns (doesn't block) on full device
# addresses and precise coordinates in added lines, which are usually made-up test values.
set -euo pipefail
cd "$(dirname "$0")/.."
if (( $# )); then RANGE=("$@"); else RANGE=(origin/main..HEAD); fi
PATTERNS=private/pii-patterns.txt
IDENTITY="308563261+siren-scanner@users.noreply.github.com"
red() { printf '\033[31m%s\033[0m\n' "$*"; }
yellow() { printf '\033[33m%s\033[0m\n' "$*"; }

if [[ ! -s $PATTERNS ]]; then red "BLOCKED: $PATTERNS is missing, so personal info can't be checked."; exit 1; fi
commits=$(git rev-list "${RANGE[@]}")
if [[ -z $commits ]]; then echo "pii-check: nothing to check in ${RANGE[*]}"; exit 0; fi
n=$(wc -l <<<"$commits")
private_re=$(grep -v '^\s*#' "$PATTERNS" | grep -v '^\s*$' | paste -sd'|')
email_re='[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}'
email_ok='(users\.noreply\.github\.com|noreply@anthropic\.com|@example\.(com|org|net)|@localhost|@[^ ]*\.invalid)'
block=0

# 1. Who made the commits.
while IFS='|' read -r sha ae ce; do
    if [[ $ae != "$IDENTITY" || $ce != "$IDENTITY" ]]; then
        red "BLOCKED: commit ${sha:0:9} is by $ae (committer $ce), not the public identity"; block=1
    fi
done < <(git log --format='%H|%ae|%ce' "${RANGE[@]}")

# 2. What every commit adds, plus its message and file names.
added=$(git log -p --no-color --format='@@commit %h%n%B' "${RANGE[@]}" | awk '
    /^@@commit / { c = $2; print "commit " c ": message"; next }
    /^diff --git/ { f = $NF; sub(/^b\//, "", f); print c " " f ": (file name)"; next }
    /^\+\+\+|^---/ { next }
    /^\+/ { print c " " f ": " substr($0, 2); next }
    !/^[-+ @\\]/ && !/^(index|new file|deleted file|similarity|rename|old mode|new mode|Binary)/ { print c " message: " $0 }')

# Bundled third-party source keeps its authors' copyright notices (their licences require it), so
# email addresses are allowed there; the private values that aren't email patterns still apply.
vendored=' app/src/main/jni/(libusb|rtl-sdr)/'
private_noemail=$(grep -v '^\s*#' "$PATTERNS" | grep -v '^\s*$' | grep -v '@' | paste -sd'|')
hits=$( { grep -vE "$vendored" <<<"$added" | grep -iE "$private_re"; grep -E "$vendored" <<<"$added" | grep -iE "$private_noemail"; } || true)
if [[ -n $hits ]]; then red "BLOCKED: private values (from $PATTERNS):"; cut -c1-200 <<<"$hits" | head -20; block=1; fi

emails=$(grep -oE "^[0-9a-f]+ [^:]*:.*" <<<"$added" | grep -vE "$vendored" | grep -E "$email_re" | grep -vE "$email_ok" || true)
if [[ -n $emails ]]; then red "BLOCKED: email addresses:"; cut -c1-200 <<<"$emails" | head -20; block=1; fi

# (100.64.0.0/10 is the range itself and 100.101.102.103 the made-up example in the app.)
ts=$(grep -iE '[a-z0-9-]+\.ts\.net|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]+\.[0-9]+' <<<"$added" |
    grep -vE '100\.64\.0\.0/10|100\.101\.102\.103' || true)
if [[ -n $ts ]]; then red "BLOCKED: Tailscale names or addresses:"; cut -c1-200 <<<"$ts" | head -20; block=1; fi

# 3. Worth a look, but usually fine (made-up test values, vendor prefixes).
macs=$(grep -iE '([0-9a-f]{2}:){5}[0-9a-f]{2}' <<<"$added" | grep -viE '/test/|(00|11|22|aa|bb|cc|dd|ee|ff|10|20|30|12|da):(00|11|22|33|aa|bb):' || true)
[[ -n $macs ]] && { yellow "CHECK: full device addresses (fine if made up):"; cut -c1-160 <<<"$macs" | head -10; }
coords=$(grep -E '[-]?[0-9]{1,3}\.[0-9]{5,}' <<<"$added" | grep -vE '/test/|\.json:|\.csv:' || true)
[[ -n $coords ]] && { yellow "CHECK: precise numbers that could be coordinates:"; cut -c1-160 <<<"$coords" | head -10; }

if (( block )); then
    red "pii-check: BLOCKED ($n commits in ${RANGE[*]}). Remove it, then squash unpushed commits so no commit still contains it."
    exit 1
fi
echo "pii-check: OK ($n commits in ${RANGE[*]})"
