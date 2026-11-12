#!/usr/bin/env bash
# Build the app.
#
#   ./scripts/build.sh            debug (default)
#   ./scripts/build.sh release    release, R8 enabled, from clean
#   ./scripts/build.sh all        both
#   ./scripts/build.sh bundle     release app bundle, for the Play Store, from clean
#   ./scripts/build.sh signing    the release key's certificate fingerprints
#   ./scripts/build.sh clean      wipe build outputs first, then debug
#
# Anything release-signed asks for the key's password, unless
# keystore.properties holds it. See gradlew_signed in _common.sh.
#
# Release and bundle builds start from clean, because they are built to ship or to be compared
# with F-Droid's build, which is always clean. Built on top of earlier builds, the Compose
# compiler can leave a stability flag unknown that a clean build knows: on 2026-11-11 that made
# classes.dex differ from F-Droid's (`0` where a clean build passes 384), and fdroid-check failed.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

variant="${1:-debug}"

case "$variant" in
  clean)
    info "Cleaning"
    gradlew clean
    variant=debug
    ;;
esac

case "$variant" in
  debug)   tasks=(assembleDebug) ;;
  release) tasks=(clean assembleRelease) ;;
  all)     tasks=(clean assembleDebug assembleRelease) ;;
  bundle)  tasks=(clean bundleRelease) ;;
  signing)
    # Only the release key's lines: the debug key's are noise here.
    info "Release signing certificate"
    gradlew_signed -q :app:signingReport | awk '/^Variant: release$/,/^-+$/'
    exit
    ;;
  *)       die "Unknown variant '$variant'. Use: debug | release | all | bundle | signing | clean" ;;
esac

info "Building: ${tasks[*]}"
if [[ "$variant" == debug ]]; then gradlew "${tasks[@]}"; else gradlew_signed "${tasks[@]}"; fi

info "APKs"
# Sizes matter here: the release APK is the one users download, and a sudden
# jump usually means R8 stopped shrinking something it used to.
find "$REPO_ROOT/app/build/outputs" -name '*.apk' -printf '%-60p  %s bytes\n' 2>/dev/null \
  | sed "s|$REPO_ROOT/||" \
  || warn "No APKs found"
