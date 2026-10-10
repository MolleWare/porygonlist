#!/usr/bin/env bash
# Shared helpers for the scripts in this directory. Meant to be sourced.
#
# This repository is public. Nothing machine-specific belongs in here or in any
# tracked script: no home directories, no usernames, no keystore paths. Local
# values live in scripts/env.local.sh, which is gitignored.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPO_ROOT

if [[ -f "$REPO_ROOT/scripts/env.local.sh" ]]; then
  # shellcheck source=/dev/null
  source "$REPO_ROOT/scripts/env.local.sh"
fi

info() { printf '\n==> %s\n' "$*"; }
warn() { printf '\nwarning: %s\n' "$*" >&2; }
die()  { printf '\nerror: %s\n' "$*" >&2; exit 1; }

# The Android Gradle Plugin supports a specific range of JDKs. Newer is not
# better here: a JDK the plugin does not know about fails in confusing ways.
readonly REQUIRED_JDK=21

require_java() {
  [[ -n "${JAVA_HOME:-}" ]] || die \
    "JAVA_HOME is not set. Copy scripts/env.local.sh.example to scripts/env.local.sh and fill it in."
  [[ -x "$JAVA_HOME/bin/java" ]] || die \
    "JAVA_HOME has no bin/java, so it is not a JDK: $JAVA_HOME"

  local version
  version="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')"
  [[ "$version" == "$REQUIRED_JDK" ]] || die \
    "JDK $REQUIRED_JDK required, found $version at $JAVA_HOME"

  export JAVA_HOME
}

require_android_sdk() {
  [[ -n "${ANDROID_HOME:-}" ]] || die \
    "ANDROID_HOME is not set. Copy scripts/env.local.sh.example to scripts/env.local.sh and fill it in."
  [[ -d "$ANDROID_HOME/platform-tools" ]] || die \
    "No platform-tools under ANDROID_HOME: $ANDROID_HOME"

  export ANDROID_HOME
  ADB="$ANDROID_HOME/platform-tools/adb"
  readonly ADB
}

# Fails with an actionable message rather than letting Gradle report a generic
# "no connected devices" several minutes into a build.
require_device() {
  require_android_sdk

  local listing
  listing="$("$ADB" devices)"

  if grep -qw "unauthorized" <<<"$listing"; then
    die "Device is unauthorized. Accept the USB debugging prompt on the phone."
  fi
  if grep -qw "no permissions" <<<"$listing"; then
    die "No permission to access the device. Install android-udev-rules, then unplug and replug."
  fi

  local count
  count="$(grep -cw "device" <<<"$listing" || true)"
  case "$count" in
    0) die "No device connected. Enable USB debugging and plug the phone in." ;;
    1) : ;;
    *) die "$count devices connected. Benchmarks need exactly one; disconnect the others." ;;
  esac

  info "Device: $("$ADB" shell getprop ro.product.model | tr -d '\r') (Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r'))"
}

gradlew() {
  require_java
  ( cd "$REPO_ROOT" && ./gradlew "$@" )
}

# Runs Gradle with the release key unlocked, asking for its password unless
# keystore.properties already holds one. The password lives only in this
# command's environment, never on disk: the configuration cache is off for the
# run, because it would otherwise store the signing config, password included.
# Without keystore.properties the build is unsigned, as F-Droid builds it.
gradlew_signed() {
  local props="$REPO_ROOT/keystore.properties"
  if [[ ! -f "$props" ]]; then
    warn "No keystore.properties: the release build will be unsigned."
    gradlew "$@"
    return
  fi
  if grep -qE '^storePassword=.+' "$props"; then
    gradlew --no-configuration-cache "$@"
    return
  fi

  local password
  read -rsp "Release key password: " password </dev/tty
  echo
  [[ -n "$password" ]] || die "No password given."
  PORYGONLIST_KEYSTORE_PASSWORD="$password" gradlew --no-configuration-cache "$@"
}
