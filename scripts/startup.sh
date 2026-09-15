#!/usr/bin/env bash
# Measure cold start using ActivityManager, for devices where macrobenchmark
# cannot run.
#
#   ./scripts/startup.sh        12 iterations (default)
#   ./scripts/startup.sh 25     25 iterations
#
# Why this exists: androidx.benchmark needs Perfetto, which needs a populated
# tracefs. Hardened distributions such as GrapheneOS deliberately remove that
# attack surface, so macrobenchmark refuses to run and the refusal cannot be
# suppressed. `am start -W` asks ActivityManager instead and needs no tracing.
#
# What this is NOT: as precise as macrobenchmark. TotalTime is ActivityManager's
# view of launch, not timeToInitialDisplay, and force-stop leaves the page cache
# warm, so a real first-ever launch is slower. Use this for relative comparisons
# on one device, not as an absolute figure to quote.
#
# Measure the release build, never debug. Debug builds are far slower and the
# difference is meaningless.

source "$(dirname "${BASH_SOURCE[0]}")/_common.sh"

readonly PACKAGE="io.github.molleware.porygonlist"
readonly ACTIVITY=".MainActivity"

iterations="${1:-12}"
[[ "$iterations" =~ ^[0-9]+$ ]] || die "Iterations must be a number, got '$iterations'"

require_device

"$ADB" shell pm list packages | grep -q "$PACKAGE" \
  || die "$PACKAGE is not installed. Run: ./gradlew :app:installBenchmarkRelease"

info "Measuring $iterations cold starts of $PACKAGE"

# The loop runs entirely on the device so that USB round-trips do not land
# inside the measured window.
readings="$("$ADB" shell "
  for i in \$(seq 1 $iterations); do
    am force-stop $PACKAGE
    sleep 1
    am start -W -n $PACKAGE/$ACTIVITY 2>/dev/null | grep '^TotalTime'
  done
" | grep -oE '[0-9]+$')"

[[ -n "$readings" ]] || die "No timings captured. Is the activity name still $ACTIVITY?"

printf '%s\n' "$readings" | python3 -c '
import statistics, sys
vals = sorted(int(line) for line in sys.stdin if line.strip())
print()
print(f"  samples  {len(vals)}")
print(f"  min      {vals[0]} ms")
print(f"  median   {statistics.median(vals):.0f} ms")
print(f"  max      {vals[-1]} ms")
print()
print("  all:", " ".join(str(v) for v in vals))
'
