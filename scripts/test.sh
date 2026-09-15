#!/usr/bin/env bash
# Run the test suites.
#
#   ./scripts/test.sh                unit tests only (default, no device needed)
#   ./scripts/test.sh instrumented   on-device tests, requires a connected phone
#   ./scripts/test.sh all            both
#   ./scripts/test.sh lint           Android lint only
#
# Unit tests are the default because they are the ones that stay fast. If a
# check can run without a device, it belongs in the unit suite.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

suite="${1:-unit}"

report_paths() {
  info "Reports"
  find "$REPO_ROOT/app/build/reports" -name 'index.html' 2>/dev/null \
    | sed "s|$REPO_ROOT/||" \
    || true
}

case "$suite" in
  unit)
    info "Unit tests"
    gradlew testDebugUnitTest
    ;;
  instrumented)
    require_device
    info "Instrumented tests"
    gradlew connectedDebugAndroidTest
    ;;
  all)
    info "Unit tests"
    gradlew testDebugUnitTest
    require_device
    info "Instrumented tests"
    gradlew connectedDebugAndroidTest
    ;;
  lint)
    info "Lint"
    gradlew lintDebug
    ;;
  *)
    die "Unknown suite '$suite'. Use: unit | instrumented | all | lint"
    ;;
esac

report_paths
