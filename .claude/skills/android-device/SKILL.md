---
name: android-device
description: Build, install, and drive PorygonList on a connected Android phone — deploy a change, take screenshots, tap through the UI, read crash logs, measure cold start. Use whenever the task is to see a change working on the device, reproduce a bug on hardware, verify a UI change visually, or check startup timing. Triggers on "install", "deploy", "run it on my phone", "screenshot", "tap", "does this look right", "crash", "logcat", "cold start".
---

# Driving PorygonList on the phone

Everything goes through `scripts/`. Do not write raw `adb` invocations — the
scripts already source the environment, check the device, and handle the timing
gotchas below, and each one is a single permission prompt instead of one per
command.

**`docs/device-checks.md` is the list of things to confirm on hardware**, each
with what broke to put it there. Read it whenever a phone is plugged in and
there is no narrower task — and add to it whenever a bug turns out to have been
invisible to the JVM suite.

## The loop

```bash
./scripts/install.sh debug          # build, install, launch
./scripts/device.sh shot lists      # look at it
./scripts/device.sh tap 125 618 ticked   # tap, settle, look again
```

`device.sh` captures after every interaction, because the point of tapping is
almost always to see what happened. Screenshots land in `captures/`, which is
gitignored; read them with the Read tool.

| Need | Command |
| --- | --- |
| Deploy and launch | `./scripts/install.sh debug` |
| Real first run | `./scripts/install.sh debug --fresh` |
| Install without launching | `./scripts/install.sh debug --no-launch` |
| Screenshot | `./scripts/device.sh shot <name>` |
| Tap, then screenshot | `./scripts/device.sh tap <x> <y> [name]` |
| Swipe | `./scripts/device.sh swipe <x1> <y1> <x2> <y2> [ms]` |
| Type into focused field | `./scripts/device.sh type <text>` |
| Back / enter / etc. | `./scripts/device.sh key back` |
| Relaunch cold | `./scripts/device.sh restart` |
| Screen size and density | `./scripts/device.sh size` |
| Follow logs | `./scripts/logs.sh` |
| Crash trace | `./scripts/logs.sh crash` |
| Cold start timing | `./scripts/startup.sh` |

## Things that will waste your time if you do not know them

**There are two phones, and coordinates do not transfer between them.** The
Pixel 6 is 1080x2400 at 420dpi; Ava's Galaxy S24 FE is 1080x2340 at 450dpi. Run
`adb devices -l` to see which is attached and `./scripts/device.sh size` before
trusting any coordinate from an earlier session. Derive taps from a screenshot
you took this session, not from memory.

**Captures need a settle.** `screencap` is faster than a Compose transition, so a
capture taken the instant a tap returns shows the previous screen. `device.sh`
waits before every capture. When an animation is slower than the default, raise
it rather than adding your own `sleep`:

```bash
SETTLE=1.5 ./scripts/device.sh tap 405 2235 shop
```

**`--fresh` is the only real first run.** State lives in a file and identity in
the keystore, and an ordinary reinstall keeps both. Anything that happens once —
first-run naming, seeding, a `PLSTATE` format upgrade — needs
`./scripts/install.sh debug --fresh`.

**The app logs nothing on purpose.** `logs.sh` surfaces what *Android* says:
crashes, ANRs, framework warnings. If you are looking for app-level tracing, it
does not exist and should not be added. A stack trace is what these are for.

**Do not paste log output anywhere public.** A crash inside sync can carry an
item id, and an item id begins with a device id.

**There are no instrumented tests.** `./scripts/test.sh instrumented` exists but
the suite is empty. Verification on device is visual: screenshot, look, compare.
Logic belongs in the unit suite (`./scripts/test.sh`), which needs no phone.

## Which phone can measure what

**Ava's Galaxy S24 FE (stock Android) can run the benchmarks.**
`./scripts/benchmark.sh` gives real `timeToInitialDisplayMs` medians and
`./scripts/benchmark.sh profile` regenerates the baseline profile. A `SKIPPED`
line for the test class belonging to the other mode is normal, not a failure.
Note that a benchmark run **uninstalls the app when it finishes**, taking the
keystore identity with it.

**The Pixel 6 is GrapheneOS and cannot.** `./scripts/benchmark.sh` and
`./gradlew :app:generateBaselineProfile` **cannot run there.** androidx.benchmark
requires Perfetto, which requires a populated tracefs, which GrapheneOS removes
deliberately. The refusal cannot be suppressed. Do not try to work around it and
do not suggest the user disable the protection.

Use `./scripts/startup.sh` on the Pixel instead — it asks ActivityManager via
`am start -W` and needs no tracing. Read its header before quoting a number: it
measures ActivityManager's view of launch, not time to initial display, and
force-stop leaves the page cache warm, so a genuine first-ever launch is slower.
Numbers from the two tools measure different things and must never be put side
by side. It is for relative comparison on one device, never an absolute figure.

Measure the **release** build. Debug builds are far slower and the difference
between them means nothing.
