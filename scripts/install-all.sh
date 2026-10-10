#!/usr/bin/env bash
# Build once, install on every connected phone.
#
#   ./scripts/install-all.sh                debug, install and launch everywhere
#   ./scripts/install-all.sh release        release build instead
#   ./scripts/install-all.sh --no-launch    install without launching
#
# install.sh is the single-phone version and refuses to run when several are
# attached. This is for the two-phone work: one build, the same APK pushed to
# both, so a difference in behaviour between the phones cannot be a difference
# in what was installed.
#
# There is deliberately no --fresh. Uninstalling is how a keystore identity goes
# missing, and with it every list the state file attributed to that identity —
# doing that to every attached phone on one typo is not a mistake worth making
# available. To uninstall a single phone, unplug the others and use
# ./scripts/install.sh debug --fresh, which says what it is destroying.
#
# Unlike install.sh, this checks whether each install actually succeeded. A
# rejected APK leaves the phone with whatever it already had, which is easy to
# mistake for a successful deploy when the script says nothing.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

variant="debug"
launch=1

for arg in "$@"; do
  case "$arg" in
    debug|release) variant="$arg" ;;
    --no-launch)   launch=0 ;;
    *)             die "Unknown argument '$arg'. Use: [debug|release] [--no-launch]" ;;
  esac
done

readonly APPLICATION_ID="io.github.molleware.porygonlist"
readonly LAUNCH_ACTIVITY="$APPLICATION_ID/.MainActivity"

require_android_sdk

label_for() {
  local model
  model="$("$ADB" -s "$1" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  printf '%s' "${model:-unknown model}"
}

# adb separates the serial from the state with a tab, and the state itself can
# contain spaces ("no permissions; see <url>"), so split on the tab rather than
# on whitespace.
ready=()
skipped=0
while IFS= read -r line; do
  [[ -n "${line//[[:space:]]/}" ]] || continue
  serial="$(cut -f1 <<<"$line")"
  state="$(cut -f2- <<<"$line")"

  case "$state" in
    device)
      ready+=("$serial")
      ;;
    unauthorized)
      warn "$serial is unauthorized — accept the USB debugging prompt on it. Skipping."
      skipped=$((skipped + 1))
      ;;
    offline)
      warn "$serial is offline — replug it. Skipping."
      skipped=$((skipped + 1))
      ;;
    *)
      warn "$serial is in state '$state'. Skipping."
      skipped=$((skipped + 1))
      ;;
  esac
done < <("$ADB" devices | tail -n +2)

(( ${#ready[@]} > 0 )) || die "No device is ready to install to. Plug a phone in and enable USB debugging."

info "Deploying to ${#ready[@]} device(s)"
for serial in "${ready[@]}"; do
  printf '    %s  %s\n' "$serial" "$(label_for "$serial")"
done

info "Building: $variant"
case "$variant" in
  debug)   gradlew assembleDebug ;;
  release) gradlew_signed assembleRelease ;;
esac

# Newest matching APK rather than a hardcoded path, so a change to the output
# layout cannot silently install yesterday's build.
apk="$(find "$REPO_ROOT/app/build/outputs/apk/$variant" -name '*.apk' -printf '%T@ %p\n' 2>/dev/null \
  | sort -rn | head -1 | cut -d' ' -f2-)"
[[ -n "$apk" ]] || die "No $variant APK was produced."

if [[ "$apk" == *-unsigned.apk ]]; then
  warn "This APK is unsigned (no keystore.properties), so every install below will be rejected. For something measurable use: ./gradlew :app:installBenchmarkRelease"
fi

installed=0
failed=0
for serial in "${ready[@]}"; do
  label="$(label_for "$serial")"
  info "$label ($serial)"

  # -r reinstalls in place, keeping the state file and the keystore identity.
  # -d allows a downgrade, so a local build older than what is on the phone
  # installs instead of failing with a version message.
  if "$ADB" -s "$serial" install -r -d "$apk"; then
    installed=$((installed + 1))
    if (( launch )); then
      "$ADB" -s "$serial" shell am start -n "$LAUNCH_ACTIVITY" >/dev/null
    fi
  else
    # Most often a signature mismatch: a debug APK cannot replace a release-signed
    # install. Nothing was lost — the phone still has what it had.
    warn "Install failed on $label ($serial). It keeps whatever was already there."
    failed=$((failed + 1))
  fi
done

info "Installed on $installed of $((installed + failed)) device(s)"
(( skipped == 0 )) || warn "$skipped device(s) were skipped and did not get this build."
(( failed == 0 )) || die "$failed install(s) failed."
