---
name: performance
description: PorygonList's performance budget and the rules that keep it — cold start, Compose recomposition, dependency weight, APK size, and how to measure any of it on a device where macrobenchmark cannot run. Use when adding a dependency, touching app startup or MainActivity, writing or reviewing Compose that renders a list, changing how state is read or written, or when asked to make something faster or explain why something is slow.
---

# Performance in PorygonList

The budget that matters is **cold start**. This is a grocery list: it gets opened
in a shop, one-handed, for eight seconds. Everything below follows from that.

Second is **scroll smoothness** on the list screens. Nothing else has ever been
the problem, so do not go looking for wins elsewhere without a measurement
saying there is one.

## Rules that are already load-bearing

These are not suggestions. Each is holding the startup number down, and each is
easy to break without noticing.

**No `Application` subclass.** There is none, deliberately. Nothing runs before
the activity. If you find yourself wanting one, you want lazy initialisation
instead.

**Manual dependency injection.** No Hilt, no Dagger. An annotation processor and
a component graph cost startup time and build time to solve a problem this app
does not have.

**State is read lazily, after the first frame.** The state file is not touched
during composition of the first screen. Work that is not needed to draw the
first frame does not happen before it.

**Sync starts after the first frame.** Discovery, pairing, network work — all of
it waits. The UI is never blocked on a peer.

**Writes are coalesced and atomic.** A burst of taps becomes one save, written
via rename. Do not add a write path that fsyncs per interaction.

**No new dependencies unless they earn it.** Currently zero beyond AndroidX and
Compose, and that is a feature: it keeps the F-Droid build simple and the dex
small. The state and wire formats are parsed by hand
(`sync/Records.kt`, ~line-based `type|field|field`) rather than pulling in a
serialization runtime — at this data volume a parser that small is the right
size of tool. Adding a library to replace it is a regression, not a cleanup.

Before proposing any dependency, state what it costs in startup and APK size and
what it replaces. "It's more idiomatic" is not an argument that wins here.

## Compose, specifically

Only the failures that actually bite this app:

- **Lazy lists need stable keys.** `items(list, key = { it.id })`. Without a key,
  every insert rebuilds the rows below it.
- **Read state as late as possible.** Passing a `State<T>` down and reading it in
  the leaf keeps recomposition in the leaf. Reading it high and passing the value
  down recomposes the whole subtree.
- **Unstable parameters defeat skipping.** A `List<Item>` parameter makes a
  composable non-skippable; the immutable-collection or `@Immutable`-annotated
  route keeps it skippable. This matters on the list screens and almost nowhere
  else.
- **`derivedStateOf` for values computed from state** that change less often than
  the state does — a filtered list, a count, an enabled flag.
- **Do not read the state file, hit the keystore, or touch the network inside a
  composable.** Composition runs often and at unpredictable times.

Do not reach for `key()`, `movableContentOf`, or manual `remember` caching as a
first move. They are fixes for a measured problem.

## Measuring, on this phone

The development phone runs **GrapheneOS**, which means:

- `./scripts/benchmark.sh` **does not work here.** androidx.benchmark requires
  Perfetto, which requires a populated tracefs, which GrapheneOS removes on
  purpose. The refusal cannot be suppressed and should not be worked around.
- `:app:generateBaselineProfile` **does not work here** either, for the same
  reason. The profile in the repo is generated elsewhere.

Use this instead:

```bash
./scripts/install.sh release --no-launch
./scripts/startup.sh 25
```

Read `scripts/startup.sh`'s header before quoting the output. It reports
ActivityManager's `TotalTime`, which is not `timeToInitialDisplay`, and
force-stop leaves the page cache warm, so a genuine first-ever launch is slower
than what it prints. **It is a relative measure on one device.** Compare medians
before and after a change; never quote a single run, and never quote it as an
absolute figure.

**Measure the release build.** Debug Compose is far slower — no R8, no baseline
profile, and the composer instrumented. A debug timing tells you nothing about
what a user gets.

For APK size, `./scripts/build.sh release` prints the sizes at the end. A sudden
jump usually means R8 stopped shrinking something it used to.

## The discipline

Measure, change, measure. Two medians or it did not happen.

An optimisation that makes the code harder to read buys its complexity with a
number. If there is no number, revert it — this codebase's comments explain
*why*, and "it seemed faster" is not a why that survives.
