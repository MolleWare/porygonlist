#!/usr/bin/env bash
# Build the app.
#
#   ./scripts/build.sh            debug (default)
#   ./scripts/build.sh release    release, R8 enabled
#   ./scripts/build.sh all        both
#   ./scripts/build.sh clean      wipe build outputs first, then debug

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
  release) tasks=(assembleRelease) ;;
  all)     tasks=(assembleDebug assembleRelease) ;;
  *)       die "Unknown variant '$variant'. Use: debug | release | all | clean" ;;
esac

info "Building: ${tasks[*]}"
gradlew "${tasks[@]}"

info "APKs"
# Sizes matter here: the release APK is the one users download, and a sudden
# jump usually means R8 stopped shrinking something it used to.
find "$REPO_ROOT/app/build/outputs" -name '*.apk' -printf '%-60p  %s bytes\n' 2>/dev/null \
  | sed "s|$REPO_ROOT/||" \
  || warn "No APKs found"
