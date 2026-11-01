#!/usr/bin/env bash
# Build and install onto the connected phone.
#
#   ./scripts/install.sh              debug, install and launch
#   ./scripts/install.sh release      release build instead
#   ./scripts/install.sh debug --no-launch    install only
#   ./scripts/install.sh debug --fresh        uninstall first, so the next run
#                                             is a real first run
#   ./scripts/install.sh beside               debug, as io.github.molleware.porygonlist.next,
#                                             installed beside the release app
#
# `beside` is the way to test on a phone someone uses: the release app, its lists and its pairing
# are left alone, and the copy has its own identity. Point the other scripts at it with
# PORYGONLIST_BESIDE=1, e.g. `PORYGONLIST_BESIDE=1 ./scripts/device.sh read`; --fresh here removes
# only the copy. It answers porygonlist:// links too, so Android asks which app opens one.
#
# --fresh is the one worth knowing about: the app keeps its state in a file and
# its identity in the keystore, and an ordinary reinstall keeps both. Testing
# anything that only happens once — first run, seeding, a format upgrade — needs
# the uninstall.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

variant="debug"
beside=0
launch=1
fresh=0

for arg in "$@"; do
  case "$arg" in
    debug|release) variant="$arg" ;;
    beside)        variant="debug"; beside=1 ;;
    --no-launch)   launch=0 ;;
    --fresh)       fresh=1 ;;
    *)             die "Unknown argument '$arg'. Use: [debug|release|beside] [--no-launch] [--fresh]" ;;
  esac
done

if (( beside )); then
  app_id="io.github.molleware.porygonlist.next"
else
  app_id="$APPLICATION_ID"
fi
# The class keeps its name when the package gets a suffix, so it is written out in full.
readonly LAUNCH_ACTIVITY="$app_id/io.github.molleware.porygonlist.MainActivity"

require_device

info "Building: $variant$( (( beside )) && echo ", beside the release app as $app_id" )"
case "$variant" in
  debug)   if (( beside )); then gradlew assembleDebug -Pbeside; else gradlew assembleDebug; fi ;;
  release) gradlew_signed assembleRelease ;;
esac

# Take the newest matching APK rather than a hardcoded path, so a change to the
# output layout does not silently install yesterday's build.
apk="$(find "$REPO_ROOT/app/build/outputs/apk/$variant" -name '*.apk' -printf '%T@ %p\n' 2>/dev/null \
  | sort -rn | head -1 | cut -d' ' -f2-)"
[[ -n "$apk" ]] || die "No $variant APK was produced."

if (( fresh )); then
  info "Uninstalling (state file and keystore identity go with it)"
  "$ADB" uninstall "$app_id" >/dev/null 2>&1 || warn "Nothing was installed."
fi

info "Installing ${apk#"$REPO_ROOT"/}"
# -r reinstalls in place, -d allows a downgrade; without -d a local build older
# than what is on the phone fails with a version message rather than installing.
"$ADB" install -r -d "$apk"

if (( launch )); then
  info "Launching"
  "$ADB" shell am start -n "$LAUNCH_ACTIVITY" >/dev/null
fi

info "Installed. Logs: ./scripts/logs.sh"
