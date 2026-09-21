# Device checks

The things that can only be confirmed on a phone. Most of them are visual —
install, drive, screenshot, look — because the one thing that could be asserted
automatically already is: `./scripts/test.sh instrumented` runs the five tests in
`PairingHandshakeTest`, which need a real keystore key and a real socket.

Run the JVM suite first (`./scripts/test.sh`), then the instrumented one if a
phone is attached. Between them they catch everything these checks would only
catch slowly. What follows is the residue: behaviour that depends on a real
install, a real network, or a real screen.

## Before you start

```bash
adb devices -l                 # which phone is this?
./scripts/device.sh size       # coordinates are per-device, always
```

**Coordinates from a previous session are wrong.** The two phones in use differ:
the Pixel 6 is 1080x2400 at 420dpi, Ava's Galaxy S24 FE is 1080x2340 at 450dpi.
Derive every tap from a screenshot taken in the current session.

**`--fresh` is the only real first run.** State lives in a file and identity in
the keystore, and an ordinary reinstall keeps both. Every check below marked
*fresh* needs `./scripts/install.sh debug --fresh`.

**A capture taken too early shows the previous screen.** `device.sh` settles
before every capture; raise it with `SETTLE=1.5` when an animation is slower.

---

## 1. First run is empty — *fresh*

`./scripts/install.sh debug --fresh`, then screenshot.

- Lands on **"What should we call you?"** with an empty field.
- Type a name, Continue. The Lists screen has **no lists**, no Ava, no demo
  content of any kind, and no approved networks.
- Staples is empty and reads "The things you buy over and over."

*Why:* the app shipped a seeded demo state until 2026-09-19 — three lists, a
partner called Ava, a staged conflict, three fake networks, nine staples. All of
it is gone from the app and lives only in `SeedFixture.kt`, for tests. A demo
list reappearing on a new install means something called the fixture.

## 2. The name survives a restart — *fresh*

Immediately after check 1: `./scripts/device.sh restart`.

- The app opens on **Lists**, avatar showing the initial. It must **not** return
  to the naming screen.

*Why:* this is the sharp edge of the empty first run. `StateCodec.decode`
rejected any file with no lists as truncated, so an empty state failed to decode
and the app silently started over as a new install — losing the name and the
device identity with it. Fixed by removing that check; this is its regression
test. It only reproduces when the phone has **no lists**, which is exactly the
state a new install is now in.

## 3. Nothing breaks with no lists — *fresh*

From the empty state, visit every tab.

- **Shop**: renders, no crash. Shows "0 of 0 in the trolley" and **no** trailing
  sentence. Specifically not "them is crossing things off too."
- **Share**: "Send a list as a text", "GIVE A LIST TO SOMEONE", "Swap codes once,
  and they're on your lists." No double spaces, no stranded full stop.
- **Staples**: the empty grid text, not "Tap to add to ." 

*Why:* seven strings interpolated the open list's name. With no list they
rendered against a nameless placeholder and produced broken sentences. The whole
class is worth re-reading after any copy change.

## 4. Deleting the last list is allowed

Make one list, then press and hold it and delete it.

- The list goes. The app lands on the same empty Lists screen as check 1, and
  does not crash on the Shop tab afterwards.

*Why:* this used to be refused, on the grounds that the app had no state showing
no list at all. It has one now — it is where every install starts.

## 5. A staple tapped twice asks for two — *verified 2026-09-21*

Make a list, add a staple, open the list, tap the staple tile twice.

- **One** line for that item, quantity **2**. Not two identical rows.
- The tile's own count reads "used 2 times".
- Typing the same name into the field, in any capitalisation, does the same.
- An item already **ticked off** is the exception: tapping the staple again
  starts a **new** line rather than raising the count on a done row.

*Why:* every add path appended unconditionally; the suggestion dropdown only
hid duplicates from the list it offered. Fixed 2026-09-19 with unit cover, seen
on a screen 2026-09-21: two taps gave one `Milk ×2` line, typing `milk` into the
field made it `×3` and kept the original capitalisation, and tapping the staple
again with the row ticked started a second, separate `Milk`.

## 6. Forgetting an approved network — *verified 2026-09-21*

On the Share tab, with at least one network listed, **press and hold** its name.

- An inline question appears: "Forget <name>?" with "Nothing moves here until you
  approve it again. Nobody is unpaired."
- **Keep it** leaves the row exactly as it was, name and switch included.
- **Forget** removes the row. If it was the network you are standing on, the
  banner at the top offers it for approval again.
- The switch still works separately: switching a network **off** keeps the row
  and its name, and only stops it gating discovery.

*Why:* approved networks could only be switched off, never removed, so the list
accumulated every café ever approved. The gesture matches lists and staples —
hold to remove — but a long-press is invisible until tried, so the hint line
under the section has to say so.

All four clauses held on 2026-09-21. Note that forgetting the network you are
standing on takes its **name** with it: the banner comes back offering
`Network <fingerprint>`, not the label you gave it.

## 7. Confetti on finishing a list — *verified 2026-09-21*

Make a list with two or three things on it, then tick them off.

- Nothing happens until the **last** one. That tick bursts confetti in the app's
  own green, peach and brown, over whatever screen you are on.
- It works the same from shopping mode and from the list itself.
- Untick something and tick it again: it fires again, because finishing a list
  twice is still finishing it. The **untick** must not fire it.
- Opening a list that was already finished does nothing.
- With **Settings → Accessibility → Remove animations** on, nothing is drawn at
  all — no burst, no flash, no pause.

*Why:* Ava asked for it. Everything above except the drawing itself has unit
cover; what needs a pair of eyes is whether it reads as a flourish or as a
glitch, and whether it obscures the list underneath at the moment you want to
check your work.

Seen on 2026-09-21, and it reads as a flourish: the burst rises from the row you
just ticked, the pieces are small enough that the item text stays readable
through them, and it is gone before you would look away. Every clause held.

To exercise the last one without touching Settings:

```bash
adb shell settings put global animator_duration_scale 0    # then 1.0 to restore
```

`Confetti` reads `ANIMATOR_DURATION_SCALE` once per burst, not per frame, so the
change takes effect on the next tick without reinstalling — but put the value
back, because it slows every animation on the phone, not just this one.

## 8. Identity survives an ordinary reinstall

`./scripts/install.sh debug` **without** `--fresh`, after check 1.

- The name and lists are still there. No naming screen.

*Why:* the identity is in the Android Keystore and the state in a file; a plain
reinstall keeps both. Verified working on GrapheneOS. If this ever fails, the
keystore entry was cleared, and `ListRepository.load` will deliberately discard
the old state rather than claim authorship of items it cannot attribute.

## 9. Pairing over the network — **one phone only**

`./scripts/test.sh instrumented` covers the handshake against a real keystore
key and a real socket: the happy path, an unpinned key, a missing token, a spent
token and a closed screen. It passes on the Pixel, and it is the check to run
first — a failure there makes everything below moot.

What it cannot cover is a second device. **Two-phone pairing has never been
run.** Needs both phones on the same wifi, both approving that network, and
treat the first attempt as exploratory rather than as a check.

Two things to know before trying it:

- **Identities minted before 2026-09-20 cannot do TLS.** The key must authorise
  `DIGEST_NONE`, because Conscrypt hashes the handshake transcript itself and
  hands the key a raw digest. Authorised digests are fixed at generation and
  there is no migration, so both phones need a fresh identity — and a new key is
  a new `DeviceId`.
- **A list still cannot arrive from a peer.** `GroceryList.id` is a local
  `max+1`, and `AppState.receive` refuses to let a peer introduce a list at all.
  Pairing succeeding does not mean a shared list will appear; that needs global
  list identity and an invitation step first.

*Why:* the failure mode here is silent on both ends. When the digest was wrong,
the server hung up before sending a certificate and each side reported only that
the other had closed.

## 10. Startup timing — Samsung only

```bash
./scripts/benchmark.sh            # timeToInitialDisplayMs, medians
./scripts/benchmark.sh profile    # regenerate the baseline profile
```

- Runs on Ava's S24 FE. **Cannot run on the Pixel**: androidx.benchmark needs
  Perfetto, which needs a populated tracefs, which GrapheneOS removes on purpose.
  The refusal cannot be suppressed and must not be worked around.
- A `SKIPPED` line for the test class belonging to the *other* mode is normal.
- Measure the **release** variant. Debug startup is roughly 2.4x slower and the
  difference between the two means nothing.
- `./scripts/startup.sh` is the Pixel's fallback. It asks ActivityManager via
  `am start -W`, which measures a different thing from `timeToInitialDisplayMs`.
  Never put numbers from the two side by side.

**The benchmark uninstalls the app when it finishes**, taking the keystore
identity with it. Anything paired before a benchmark run is unpaired after.

---

## Gotchas that have cost real time

- **A dozing screen ruins everything.** `am start -W` reports no `TotalTime` at
  all and captures come back black. Check `dumpsys power | grep mWakefulness`
  is `Awake`.
- **A black screenshot is usually timing, not a bug.** A ~15 KB PNG for a 1080p
  screen is all-black; a real capture is 150 KB+. Confirm against
  `uiautomator dump` before concluding the app renders black. This was
  misdiagnosed once as a real rendering bug.
- **The instrumented suite uninstalls the app when it finishes**, exactly as the
  benchmark does, and takes the keystore identity and all state with it. Run
  `./scripts/test.sh instrumented` *before* anything you set up by hand, not
  after. The upside is that the next launch is a genuine first run, so checks 1
  to 3 come free.
- **`./scripts/install.sh release` fails silently.** There is no release signing
  config, so `adb install` rejects the unsigned APK and the script still exits 0
  — leaving whatever was already installed in place. Use
  `./gradlew :app:installBenchmarkRelease` for anything you intend to measure.
- **Samsung's Auto Blocker greys out USB debugging.** Settings → Security and
  privacy → Auto Blocker, off.
- **Do not paste logs anywhere public.** A crash inside sync can carry an item
  id, and an item id begins with a device id.
