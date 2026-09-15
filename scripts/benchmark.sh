#!/usr/bin/env bash
# Measure app startup, and regenerate the baseline profile.
#
#   ./scripts/benchmark.sh           measure cold start (default)
#   ./scripts/benchmark.sh profile   regenerate the baseline profile
#   ./scripts/benchmark.sh all       regenerate the profile, then measure
#
# All of these need a real device connected. Emulator results are dominated by
# host scheduling noise and will mislead you.
#
# Results are medians over several iterations. Treat a single run as noisy:
# compare medians across runs before believing a change helped.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

mode="${1:-measure}"

show_results() {
  info "Results"
  local out="$REPO_ROOT/benchmark/build/outputs"
  local json
  json="$(find "$out" -name '*.json' -newermt '-10 minutes' 2>/dev/null | head -1)"

  if [[ -z "$json" ]]; then
    warn "No result JSON found under ${out#"$REPO_ROOT"/}"
    return
  fi

  # timeToInitialDisplayMs is the number that matters: process fork to first
  # frame on screen. Everything else is diagnostic.
  if command -v python3 >/dev/null 2>&1; then
    python3 - "$json" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    data = json.load(f)
for bench in data.get("benchmarks", []):
    name = bench.get("name", "?")
    metrics = bench.get("metrics", {})
    ttid = metrics.get("timeToInitialDisplayMs", {})
    if ttid:
        print(f"  {name:<28} median {ttid.get('median', '?'):>8.1f} ms"
              f"   min {ttid.get('minimum', '?'):>8.1f}"
              f"   max {ttid.get('maximum', '?'):>8.1f}")
PY
  else
    printf '  raw: %s\n' "${json#"$REPO_ROOT"/}"
  fi
  printf '\n  full JSON: %s\n' "${json#"$REPO_ROOT"/}"
}

case "$mode" in
  measure)
    require_device
    info "Measuring cold start (this takes a few minutes)"
    gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
    show_results
    ;;
  profile)
    require_device
    info "Regenerating baseline profile"
    gradlew :app:generateBaselineProfile
    info "Profile written to app/src/release/generated/baselineProfiles/"
    ;;
  all)
    require_device
    info "Regenerating baseline profile"
    gradlew :app:generateBaselineProfile
    info "Measuring cold start"
    gradlew :benchmark:connectedBenchmarkReleaseAndroidTest
    show_results
    ;;
  *)
    die "Unknown mode '$mode'. Use: measure | profile | all"
    ;;
esac
