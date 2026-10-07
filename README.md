# PorygonList

Shared lists that sync directly between phones, with no server in the middle.

Build a list with one or more other people and have every change show up on
everyone's device, the way Syncthing keeps a folder in step across machines.

There is no account and no backend. Lists hand over directly between devices
when they are together on a local network you have approved, and nothing leaves
the phone anywhere else. Away from a shared network, a list can be sent as a
single line of text that the other person pastes straight back in.

**Status: working towards a first release, 0.1.0.** Two phones pair by
scanning a code and keep shared lists in step over an approved wifi, including
for a moment every quarter of an hour with the app closed. There is no release
to install yet; what stands between here and one is in
[docs/release-checklist.md](docs/release-checklist.md).

What works: multiple lists shared with whoever you choose, adding, ticking,
editing and removing items with changes merging from both sides, a shared item
order, your own list order and pins, staples, shopping mode, leaving a shared
list, and handing a list over as a text message you can paste into the other
person's app.

Lists are throwaway by design. They live only on the phones that share them,
and uninstalling the app takes that phone's lists with it.

## Goals

- **Fast to open.** A list app that takes a second to start is a list app you
  stop reaching for. Cold start is treated as a feature with a measured budget,
  not as something to look at later.
- **Serverless by design.** Lists sync peer to peer between the people who hold
  them, over a local network, on devices the owner has approved. There is no
  server to run, to trust, or to be breached — and no account to create. This
  is the constraint the rest of the design answers to, not an optimisation.
- **Free software, no Google dependencies.** No Play Services, no Firebase, no
  analytics. Intended for F-Droid first.

## Building

Requires JDK 21 and the Android SDK. `ANDROID_HOME` must point at your SDK, or
`local.properties` must set `sdk.dir`.

Copy `scripts/env.local.sh.example` to `scripts/env.local.sh` and point it at
your JDK 21 and Android SDK. That file is gitignored; nothing machine-specific
belongs in a tracked file.

```sh
./scripts/build.sh              # debug APK
./scripts/build.sh release      # release APK (unsigned, R8 enabled)
./scripts/build.sh all          # both

./scripts/test.sh               # unit tests, no device needed
./scripts/test.sh instrumented  # on-device tests, phone must be connected
./scripts/test.sh lint          # Android lint
```

The scripts are thin wrappers over Gradle that check the toolchain first, so a
wrong JDK or a missing device fails immediately with a useful message instead
of part-way through a build. `./gradlew` directly works fine too.

The build pins `jvmToolchain(21)` and deliberately does not use Gradle's
toolchain auto-provisioning, so a missing JDK 21 fails the build rather than
silently downloading one. Install JDK 21 and point `JAVA_HOME` at it.

### Fonts

The app draws in the phone's own system font, by decision for 0.1.0: nothing to
bundle, nothing to license, and it reads the way the rest of the phone does.

The design was drawn in Caprasimo (headings) and Figtree (body), both SIL OFL
1.1. Should that change, `./scripts/fetch-fonts.sh` pulls the TTFs into
`app/src/main/res/font/`; commit the result, so the build itself never needs the
network, and credit the fonts in the About section.

## Design

The interface comes from a [Claude Design](https://claude.ai/design) handoff.
The prototype is HTML/CSS; the Compose implementation matches its visual output
rather than its structure. Two things are worth knowing when comparing them:

- The design file's inline `<style>` block overrides every colour token in the
  design system it imports. The amber palette in `theme/Color.kt` is the
  override — the design system's own orange/sage is not what renders.
- Provenance ("you, 9:12") is stored as an author plus a timestamp and rendered
  at display time, rather than kept as the literal strings the prototype uses.
  A prototype has no yesterday.

## Measuring startup

Cold start is a tracked number, not an afterthought. Two ways to measure it:

```sh
./scripts/benchmark.sh          # macrobenchmark: precise, needs Perfetto
./scripts/startup.sh            # ActivityManager: coarser, works anywhere
```

`benchmark.sh` uses `androidx.benchmark` and reports timeToInitialDisplay, the
real metric. It requires a populated `tracefs`, so it does **not** work on
hardened distributions such as GrapheneOS, which remove that attack surface
deliberately. The resulting `DEVICE-TRACING-MISCONFIGURED` error cannot be
suppressed.

`startup.sh` falls back to `am start -W`, which asks ActivityManager and needs
no tracing. It is less precise and its absolute numbers are not comparable to
macrobenchmark's, but it is consistent enough to compare one build against
another on the same device.

Measure the release build, never debug.

## Contributing

The project is too young for its shape to be settled, so large unsolicited
changes are likely to collide with work in progress. Opening an issue to
discuss an idea first will save you effort.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

This is copyleft: you may use, modify and redistribute this code, but anything
you distribute that is derived from it must also be free software under the
same terms, with source available.
