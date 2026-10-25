# Release checklist — 0.1.0

What has to be fixed, checked and prepared before PorygonList goes out on F-Droid and the Play
Store. Tick items as they land; add the commit next to anything fixed.

Device procedures live in [device-checks.md](device-checks.md). This file is the list of what must
be true, not how to check it.

## Decisions already made

- **Name stays "PorygonList".** No plan to make money from it; the trademark risk is accepted.
- **One signing key, held by us, for every store.** F-Droid publishes our signed APK through
  reproducible builds, and Play App Signing is set up with that same key ("use my own key"), so an
  install from any store can be updated from any other.
- **Lists are throwaway.** Uninstalling loses them along with the phone's identity. Accepted: this is
  a list app for things you will have bought by Friday.
- **No Android backup, no device-to-device transfer** (2026-10-07). `allowBackup="false"` plus
  `data_extraction_rules.xml` excluding everything; the privacy policy says so.
- **System font** (2026-10-07). The design's Caprasimo and Figtree are not bundled.
- **English only for 0.1.0** (2026-10-07). Translation can come later.
- **Light only** (2026-10-07). The theme is `Theme.Material.Light`; no dark palette.

## Fix before release

- [x] **Removing someone from a list does not stick.** Now a stamped tombstone, held until everyone
      on the list and the removed person have confirmed it; the removed phone keeps a private copy.
- [x] **Unpairing had the same bug** — a third phone on the list handed the person back. Now a
      tombstone on every list too, without waiting on the unpaired phone.
- [x] **"In step with Ava" is claimed without evidence.** List cards, the banner and the list header
      follow delivery receipts: "in step with", "waiting for", or "not handed over yet".
- [x] **Two edits of the same item at once resolve silently.** Now a "keep which?" card on the list
      it concerns, saying what each person did, including a removal against an edit. The answer
      travels and closes the card on the other phone. (`be31359`)
- [x] **The sync banner's "a moment ago" is hard-coded.** It gives the last confirmed handover.
- [x] **Copy that assumes exactly two people.** Everyone on the list is named now.
- [x] **Dragging a row past the edge of the screen does not scroll.** Written (`512c68d`). It
      scrolls on the Pixel (session 3, by script); by hand, 2026-10-25, Hugo: "feels nice".
- [x] **Backups.** Off, by decision (above).
- [x] **README is out of date.** Updated in `63b366a`.
- [x] **A shared list arrives silently.** Its card reads "new, from Ava" until first opened.
- [x] **No About screen.** An About section at the bottom of Settings.
- [x] **Accessibility, in code.** Checkboxes named for their item, Shop rows state ticked, tabs state
      selected. The TalkBack pass on a phone is in the session below.

## Until the next session with Ava's phone

Planned 2026-10-25. The details of each item are further down; this is the order to take them in.

### Needs her phone

Her S24 FE is the only stock-Android phone, and the only peer that sleeps and reboots like a real
one. Everything here is a rerun, on the next build, of a check that failed or was left half-done.

- [ ] Before anything: her lists backed up read-only (the scratch backup script, counts only), the
      new build installed over hers with `install-all.sh release --no-launch`, both phones on USB.
- [ ] Her app sent Home, then her next natural quarter-hour window: it exchanges with the Pixel
      without being opened (fix `8c3b4c5`, failed 2026-10-24). Use a natural window — a forced
      `jobscheduler run -f` does not lift her firewall.
- [ ] Alarms allowed on both, both apps closed (`am kill`), both in deep Doze: they exchange at
      the next quarter hour (failed 2026-10-24; both fixes in `8c3b4c5` bear on it).
- [ ] Both rebooted with alarms allowed, unlocked, never opened: the first window runs on both and
      an edit made before the reboot crosses (the Pixel stood aside in 25 ms on 2026-10-24).
- [ ] Alarms never allowed on her phone: her window still runs around the quarter hour.
- [ ] Overnight battery, both phones: one night with background sync, one without.
- [ ] Anything from *Decisions* below that changes how two phones merge — several cards, an order
      for unarranged items — checked on both phones, since the fake phone shares the Pixel's code.
- [ ] Put back: her lists compared with the backup, test lists deleted, her settings as before.

### Meanwhile: the Pixel and the laptop fake phone

The Pixel is firewalled in the background just as hers is (`blocked=APP_BACKGROUND`, seen
2026-10-21), and `./scripts/fakephone.sh` never sleeps, so these prove the Pixel's half now and
leave her phone only what is hers. Needs the next build on the Pixel.

**On the next build** (`2ec442b`…`833d0c9`, none of it on a phone yet), Pixel and Sam, no Hugo
needed except to install. Proton stays on, with PorygonList excluded:

- [ ] Sent Home: no PorygonList client in `dumpsys servicediscovery`; Sam's call refused until the
      next window, which then exchanges.
- [ ] The same port in two windows in a row (`/proc/net/tcp6`), and a fresh process's first call
      going straight to Sam's kept address.
- [ ] Alarms allowed: the alarm and a quarter-hour job both booked; the job's runs end in
      milliseconds; after an alarm window from a closed app, no job 20576 left pending.
- [ ] Revoke alarms: a quarter-hour job is booked without anything else happening.
- [ ] Paste 200 items: "Add to …" in reach.
- [ ] Two clashes at once against Sam: two cards on Trio, answered one at a time; one left
      unanswered survives an app restart.
- [ ] Items added on the Pixel and on Sam to a fresh list: the same order on both.

- [x] The Pixel's app sent Home, the fake phone edits, the Pixel's next natural window collects it.
      *2026-10-24, build of 20:27, alarms allowed: sent Home at 21:17; Sam added Home item 7 at
      21:18 and its calls timed out every minute (the Home'd app still advertised port 36507 — the
      behaviour `debf796` removes); at 21:30:00 the firewall lifted, the Pixel called Sam at
      21:30:08 and 21:30:16, and the item crossed. Worth a look: the first call came 8 s into the
      window, the length of the connect timeout, as if a stale address for Sam was tried first.*
- [x] Alarms allowed, the Pixel's app closed and in deep Doze: its window reads a usable network
      and calls the fake phone (the 5 s settle in `8c3b4c5`). *2026-10-24, build of 20:27: app
      killed, screen off, `dumpsys battery unplug`, `deviceidle force-idle deep` (IDLE) at 22:07,
      Sam added Doze item 8. At 22:15:00.8 the firewall lifted, Sam reached the Pixel on its new
      port at 22:15:02 and the Pixel called Sam back; the item crossed, and 22:30 was armed while
      still in Doze. Noticed: each fresh process registers the wifi listener, which fires at once
      and books a "sync now" — held by Doze here, it ran at 22:16:56 once idle was lifted, a
      second window after every window that started a fresh process. Fixed in code: no "sync now"
      while a window is under way. To see on the next build: after an alarm window from a closed
      app, no job 20576 pending.*
- [x] The Pixel rebooted, with alarms allowed and then without, unlocked, never opened: the
      listener and the schedule come back, the first window runs, an edit from the fake phone lands.
      *2026-10-24, build of 20:27 (`2270e83`), Proton always-on with PorygonList excluded. Alarms
      not allowed, twice (20:48, 21:00): the boot broadcast started the app about a minute after
      the unlock, the listener fired once, its "sync now" window exchanged with Sam within seconds
      both ways, and the item Sam added while the Pixel was down landed. Alarms allowed (21:02):
      the same, and the alarm re-armed itself for 21:15. The Pixel came back on a new address
      each time (.110, .171, .219), and Sam found it each time. Only the per-uid grant survives a
      reboot: `appops set --uid`, which is what the Settings switch does.*
- [ ] Alarms not allowed on the Pixel: a window still runs around the quarter hour (rerun of the
      item under *App closed*, from a schedule booked by opening the app after revoking).
      ***Failed on timing, 2026-10-24, build of 20:27.** Alarms revoked at 22:16:54 (the app
      killed); a leftover "sync now" restarted it and booked the quarter-hour job for 22:30. Sam
      added Job item 9 at 22:17 (refused: port closed). The job turned READY at 22:30, every
      constraint satisfied, app in the ACTIVE bucket, phone awake and on USB — and JobScheduler
      did not start it for 35 minutes. Asked to run it without forcing at 23:05, it ran at once,
      the firewall lifted and it exchanged with Sam both ways. So the window works; the schedule
      without alarms can be over half an hour late even in the best case. **For Hugo:** accept for
      0.1.0 — the Settings card already says "Syncing around the quarter hour … may wake late",
      and the store's "Good to know" says the same, though its feature line ("on every quarter
      hour") does not — or ask for alarms more firmly, or bring "Sync is one switch" (alarms
      required) forward.**
      *Decided (Hugo, 2026-10-25): a closed app syncs within 15–30 minutes; after a reboot an hour
      is fine. Both levers, in code: the Lists screen asks for alarms while they are not allowed
      ("Not now" puts it away until the next start), and without alarms the quarter-hour job gets
      a deadline 5 minutes after its quarter hour, which Android has to honour outside Doze; a
      backstop booked without one is rebooked once alarms go. The store's feature line now says
      "on the dot with alarms allowed", and "Good to know" mentions the VPN. To see on the next
      build: alarms not allowed, the Lists card; a job window started by 5 past the quarter hour.*
- [ ] Store screenshots on the final build, with the fake phone as the other person: demo mode,
      throwaway lists, 3–6 into `fastlane/…/phoneScreenshots/`.

### Meanwhile: by hand (Hugo)

- [x] Drag a row of the 30-item list "Thirty" end to end: does the edge scroll feel right? *Yes,
      2026-10-25.*
- [x] ~~TalkBack on, once through every screen by ear.~~ *Not for 0.1.0 (Hugo, 2026-10-25); moved
      to After 0.1.0.*
- [ ] The release key and its password backed up off this machine.
- [ ] Play Console: account, app entry, Play App Signing with our key, data safety form, location
      declaration, content rating.

### Meanwhile: decisions (Hugo), then code (Claude)

Each is written up under *Found in the session*. Once decided, the fix and its tests land in a
day; the merge ones then go on the two-phone list above.

Decided by Hugo, 2026-10-25. Each is a fix for 0.1.0 unless it says otherwise.

- [ ] Two clashes at once → **several cards**: every clash its own card, on its own list, nothing
      settled silently. A state-format change, so a migration and its tests. *In code: the state
      holds a list of cards; a merge keeps the open ones, refreshes one whose question comes round
      again, and adds the new ones; each answer names its card; a list shows all of its cards,
      oldest first. No new file version was needed: a card was already a `conflict` line and its
      two sides, so a file holding several is read in sequence and an old file is a list of one —
      and no older build is made to refuse a newer file, which would start the phone over
      (`EditClashTest`, `SyncSessionTest`, `CodecsTest`, `PorygonViewModelTest`). To see on both
      phones: two clashes on two items at once, two cards, each answered on its own; then a
      restart, and the unanswered one is still there.*
- [ ] Unarranged items order differently → **a position when added**: every new item gets a shared
      position at once. Also fixes "an item added before the first tick jumps to the top". *In
      code: adding no longer skips the key in a list nobody has arranged; the first add places the
      list in the order it is shown, as a first move does, without making the adder the author
      (`ReorderingTest`, three new tests that fail on the old code). Lists from before keep their
      unplaced items until something is added or moved. To see on both phones: items added on
      each to a new list, same order on both.*
- [ ] Revoking alarms stops sync → **a backup job**: the quarter-hour job stays booked beside the
      alarm and stands aside while the alarm is armed, so there are never two windows. *In code:
      `BackgroundSync.ensureScheduled` books the job in both modes; a quarter-hour job returns at
      once while alarms are allowed and the alarm is armed (`QuarterHourTest`). To see on the next
      build: alarms allowed, both the alarm and job 20569 or 20578 pending, the job's runs ending
      in milliseconds; then revoke, and a window still runs near the next quarter hour.*
- [ ] Two phones in Doze miss each other → **remember addresses**: each peer's last address kept
      across process death, so a fresh window calls at once instead of waiting on mDNS. *In code,
      three parts: the last good address of each peer kept in a file beside the state for a day
      (`PeerAddressBook`), and not dropped when a call fails, since the peer may only be asleep;
      the socket asks for its last port again, since a remembered address was useless while every
      window listened on a new port (41389 at 21:04, 37667 at 21:15); and the window looks again
      at 10 s and 20 s, for two phones woken by the same quarter hour. Tests in `SyncCoordinatorTest`
      and `PeerAddressBookTest`. To see on the next build: the Pixel's port the same in two
      windows; then the two-phone Doze test with Ava's phone.*
- [ ] The app sent Home goes on announcing → **stop when it leaves the screen**: discovery stops
      when the app is no longer visible, and the windows do the rest. *In code: the screen tells
      the view model on start and stop, and off screen the gate lets the socket go and stops the
      advert — unless a window is under way, which then stops it as it ends
      (`PorygonViewModelTest`). To see on the next build: Home, then `dumpsys servicediscovery`
      shows no PorygonList client and Sam's call is refused until the next window.*
- [ ] Small ones → **only the paste sheet**: cap the field so "Add to …" stays reachable. The rest
      moved to After 0.1.0. *In code: the field is capped at 190 dp, as the export sheet's text
      is, and scrolls inside. To see on the next build: paste 200 items, "Add to …" in reach.*

### Meanwhile: release preparation

- [ ] Push `main` from the host.
- [ ] `./scripts/fdroid-check.sh` on the pushed HEAD now, before any tag: lint, F-Droid's own
      build and its comparison with our APK — a difference found now costs nothing.
- [ ] Reread the store text once the decisions above have landed.
- [ ] When every box above the store section is ticked: the release steps under *F-Droid*,
      `release.sh` first.

## The two-phone session

Release build (`assembleRelease`, R8 on), not debug — minification has to be proven, not assumed.
Both phones on an approved wifi unless a step says otherwise. Use throwaway lists, never anyone's real
ones; the screenshots at the end come from these too.

**Before:** sign a release build (needs the key, below), uninstall the debug build from both phones
— this wipes their lists, so export anything worth keeping first — and install the release build.

**Session 1, 2026-10-09:** release 0.1.0 signed with our key (`3f6ec695…`), on the Pixel and Ava's
S24 FE. Stopped at sync: see *Found in the session* below.
**Session 2, 2026-10-09:** release rebuilt with the fixes (`aa0755c`), installed over session 1's,
pairing and lists kept. Two-phone checks continue from here.
**Session 3, 2026-10-16:** the Pixel alone (Ava's phone away). Release rebuilt from `7e07c67` plus
the uncommitted Delete-button and "Added by" fixes, installed over session 2's, pairing kept.
**Session 4, 2026-10-21:** the Pixel, on session 3's build, against the laptop standing in as the
other phone — `./scripts/fakephone.sh`, the app's own sync code on the JVM. It proves the protocol
and the Pixel's half; the S24 FE's own discovery and screens still need her phone.

### One phone at a time

- [x] First run on a clean install: naming, first list, nothing left over from development. Both
      phones, session 1.
- [x] About: the version reads 0.1.0, both links open the browser. *Pixel, session 3: Source code
      and Report a problem both open in Brave.*
- [x] Edit order on a 30-item list: drag end to end, the screen scrolls along at the edges, the
      speed feels right, and the bottom zone sits above the tab bar. *Session 3, Pixel, by script
      (`device.sh drag`): Row01 held just above the tab bar scrolled the list to the end and dropped
      below Row30 — about 22 rows in 3 s. Whether that speed feels right is left for a hand; the
      list "Thirty" is on the Pixel for it.*
- [x] Pin and unpin lists; list order stays as arranged after a restart. *Pixel, session 3: stuff
      pinned to the top, still pinned after a cold restart; unpinned, it went back below Spare;
      dragged above Spare by its handle, still there after another restart.*
- [x] The list card's avatars sit against the right edge (`d679f81`). *Pixel, 2026-10-21: "Fake shop"
      shared from the laptop fake phone, L and H flush right on the card.*
- [x] TalkBack through every screen, then the largest font size: nothing unlabelled, nothing clipped.
      *For 0.1.0: the label dumps and the largest font, both clean on both phones (below). Listening
      with TalkBack itself is left for after 0.1.0 (Hugo, 2026-10-25).*
      *Pixel, 2026-10-21: every screen's controls dumped (lists, a list, its edit sheet, Edit
      order, Shop, Staples, Share, Settings, pairing) — one unlabelled, the sheet's backdrop; and
      every screen at font 2 — avatars and "Change" broke. All three fixed below; recheck on the
      next build, and listen to TalkBack itself once by hand.*
- [x] `adb shell bmgr backupnow io.github.molleware.porygonlist` reports the app as not eligible.
      Both phones: "Backup is not allowed", session 1.

### Both phones

Ticks dated 2026-10-21 came from the Pixel against the laptop fake phone, not from Ava's S24 FE.
They prove the protocol and the Pixel's side; the reruns on her phone are under *Session 5* below.

- [x] Pair from scratch, both directions. One scan each way, session 1 — Ava's new key carries TLS.
- [x] Share a list: it arrives reading "new, from …"; edits both ways; tick and untick; remove;
      rename. *Session 1: arrives "new, from Hugo" in under 5 s. Session 2: add, tick, untick
      (Ava), remove and rename (Pixel), each on the other phone in under 5 s.*
- [x] A shared list named like one the other phone already has: two cards, told apart. *Pixel and
      the laptop fake phone, 2026-10-21: "groceries · 3 items · just you" (H) beside "groceries ·
      1 item · new, from Laptop" (L H).*
- [x] Item order travels; list order and pins stay on each phone. *Session 2: Jam dragged to the
      top on Ava's phone, same order on the Pixel in under 5 s; "Weekend shop" pinned on the Pixel
      shows no pin on Ava's, and the Pixel's private "Spare" never reached her.*
- [x] "In step with" appears only after a handover; "waiting for" while one phone is away.
      *Session 2: Ava in airplane mode — the Pixel said "waiting for Ava", Ava "waiting for Hugo";
      both "in step" within 20 s of her coming back.* (Turning wifi off alone does not hold on the
      S24 FE: it turned itself back on within seconds. Use airplane mode.)
- [x] Same item changed on both phones while one is off the wifi: "keep which?" on both, the answer
      on one closes the other's card. Then the same with a removal against a rename. *Session 2:
      Milk ×3 (Pixel) against Milk ×1 (Ava, offline) — the card on both, worded from each side;
      "Keep Hugo's" on Ava closed the Pixel's card in under 5 s. Removal (Ava, offline) against
      rename to Sourdough (Pixel): the card on both, worded right; "Keep yours" on the Pixel only
      reached Ava once the Pixel's screen woke — fixed below, rerun on the next build to tick.*
      *2026-10-21, Pixel and the laptop fake phone: removal (Pixel) against rename to Salted butter
      (laptop, offline) — the card on both, worded right; "Keep yours" on the Pixel went out at
      once, but the laptop's card never closed. Found below; rerun on the next build.*
      *Rerun on the 22:48 build: removal (Sam, offline) against rename to Brown rice (Pixel), a
      minute for both to confirm the removal, then "Keep Sam's" on the Pixel — Sam's card closed
      within 3 s and Rice was gone on both.*
- [x] Remove the other person from a list: it sticks, and their phone keeps a private copy. *Pixel
      and the laptop fake phone, 2026-10-21: the laptop took Hugo off its "groceries"; the Pixel
      kept it as "1 item · just you", and Ham added on the laptop afterwards never reached it.*
- [x] Leave a list, and be added back. *Pixel and the laptop fake phone, 2026-10-21: Delete on the
      Pixel took Hugo off the laptop's "Fake shop"; shared again from the laptop, it came back in
      2 s as "new, from Laptop" — with its rename card still open, found below.*
- [x] Unpair: sync stops, and a third copy of the list does not bring them back. *Pixel and two
      laptop fake phones, Laptop and Sam, all three on "Trio", 2026-10-21: the Pixel unpaired
      Laptop; Laptop's calls then came back "no exchange", about one a minute until they stopped;
      Sam, still syncing with Laptop, took it off "Trio" from the Pixel's tombstone and never
      handed it back; Laptop kept its own copy, and Toast added there stayed there. The Pixel's
      card then reads "new, from someone" — the sender is no longer anyone it knows. Wording to
      consider, not a fault.*
- [x] Off the wifi and back: edits from both sides merge. *Session 2: Oil added on the Pixel and
      Tea on Ava's phone while she was offline; both on both phones after she came back.*
- [x] A 200-item list: how long the handover takes, and whether either phone stutters. *Pixel and
      the laptop fake phone, 2026-10-21, release build: shared, it read "200 items" on the Pixel
      about 2 s later, in one exchange. Scrolling it: 1 janky frame of 577, 99th percentile 13 ms;
      scrolling while 50 more arrived: 3 of 527, 14 ms. The S24 FE's side is still unmeasured.*

### Session 5: both real phones

Both phones on USB to the laptop, reached through the host's adb, stay-awake on. Throwaway lists
for every test; Ava's real lists only come into it for the unpairing test, which runs last.

**Before**

- [x] Ava's lists backed up, read-only, from the screen: 8 lists, every title and item count
      checked against its card, with screenshots and a paste-able PL1 line each. Kept outside the
      repo, in the dev container's `~/porygonlist-backups/ava-2026-10-24/` (2026-10-24).
- [x] New release build installed over the current one on both phones, lists and pairing kept.
      *`install-all.sh release` from `d784102`, 2026-10-24 16:58, both still paired to each other.
      The Pixel's wifi was found switched off and turned back on for the session.*

**What the fake phone ticked, rerun with her phone**

- [x] A same-named list arriving on the S24 FE: two cards, told apart, on her narrower screen.
      *Her "S5 Twin · A · 1 item · just you" beside the Pixel's "S5 Twin · H A · 1 item · new,
      from Hugo".*
- [x] Remove the other person, both directions: the removed phone keeps a private copy, and an edit
      made afterwards on the remaining phone never reaches it. *Pixel took Ava off its S5 Twin: she
      kept "His item", never "After removal". Ava took Hugo off her S5 Beta: the Pixel kept Cheese
      and Grapes, never "Late item". Both confirmations sit below the tab bar until scrolled to,
      and the copy kept reads exactly like her own same-named list — found below.*
- [x] Leave a list and be added back, both directions: it returns as a plain card, rename card
      closed. *Ava left S5 Alpha, the Pixel added her back: "new, from Hugo", no editor open. The
      Pixel left S5 Beta, Ava added Hugo back: "new, from Ava", no editor open.*
- [x] "Keep which?" with a removal against a rename, both directions, a minute for both phones to
      confirm the removal, then keep the removal: the other card closes within 5 s. Then the same
      with two quantities. *Bread: removed by Ava (airplane mode), renamed Rye bread on the Pixel;
      the card on both, worded from each side; "Keep Ava's" on the Pixel, her card gone in 4.8 s.
      Eggs: removed on the Pixel (wifi off), Duck eggs on hers; "Keep Hugo's" on hers, the Pixel's
      gone in 2.6 s. Tea ×2 (her) against ×3 (Pixel): "Keep Hugo's" on hers, gone in 2.6 s, ×3 on
      both. The first attempt, Milk, never asked on the Pixel at all — found below.*
- [x] A 200-item list from the Pixel: how long until her phone shows it, and her phone's frame
      stats while scrolling it and while 50 more arrive. *"200 items · new, from Hugo" on her card
      7.9 s after Add (screen reads included). Her S24 FE scrolling it: 0 janky of 125 frames,
      99th percentile 5 ms; scrolling while 50 more arrived: 29 janky of 1014 (2.9%), 99th
      percentile 10 ms; 250 on both, in step. Made with Paste a list — which is where the next
      finding came from.*

**Only her phone can prove**

- [x] Her edits go out on their own, with no edit on the Pixel to prompt them (the S24 FE's mDNS
      flicker, `aa0755c`). *Tea added on her phone, on the Pixel 3.6 s later.*
- [x] A stale address: her phone off the wifi and back, so her port changes; the Pixel reaches the
      new port without waiting for her to call (`a304778`). *Airplane mode 75 s: her listening
      port 34803 → none → 36303 (read from `/proc/net/tcp6`); Honey, added on the Pixel while she
      was away, on her phone 7.8 s after airplane mode went off, both "in step". Which phone made
      the call is not visible — the app logs nothing — but nothing waited.*
- [x] After an app restart, the first exchange does not wait for an edit — restart the app on her
      phone, then on the Pixel. *Her app force-stopped, Rice added on the Pixel, 70 s, her app
      started: Rice there 8.2 s later. The same the other way with Oats: 8.4 s. Nothing edited to
      prompt either.*
- [ ] A forced background window on her phone exchanges, then goes quiet. *Failed 2026-10-24 —
      found below: with her app sent Home, neither a forced window (17:51) nor her own at 18:01
      exchanged, though the Pixel had Window item waiting. Fixed in code; rerun on the next build.*
- [ ] Alarms allowed on both: two closed phones, both in Doze, exchange at the next quarter hour.
      *2026-10-24: alarms allowed on both (each armed 18:45:00 exact), both apps closed by `am kill`
      (no process), Doze item added on hers first, both unplugged by `dumpsys` and forced into deep
      IDLE. At 18:45:00 exactly both processes started and both listened (Pixel 37913, hers
      37011) until 18:45:30, then closed. Whether they exchanged is not yet seen: the socket watcher
      caught no connection (it samples about once a second), and both phones were locked after.
      To finish: unlock, open S5 Alpha on the Pixel, look for Doze item.* **Failed:** opened at 19:00
      on the Pixel, S5 Alpha had no Doze item. Both woke and listened on time, but each process
      started fresh, with no remembered address, so each had to find the other by mDNS inside 30 s
      while in Doze: her log shows the Pixel's record, the Pixel's shows none of hers, and her
      call — she owed the Pixel Doze item — did not land. The Pixel against the fake phone passed
      in session 4 only because the fake phone never sleeps. **For Hugo:** this is the overnight
      case the alarms exist for. Ideas: keep last-known peer addresses across process death, a
      longer window when alarms align both phones, or a retry inside the window.
- [ ] Her phone rebooted, unlocked, never opened: the wifi listener is back after one window, and it
      catches up (needs Ava to unlock it). *Run it with alarms still allowed: exact alarms do not
      survive a reboot, and with alarms allowed the quarter-hour jobs are cancelled, so after a
      reboot the wifi listener is the only thing that can start the schedule again — the very
      listener that went missing on the Pixel. Worth seeing whether anything runs at all.*
      *2026-10-24 19:20, both rebooted with alarms allowed, unlocked, never opened: the boot
      broadcast reached WifiWatch on both (19:22:07 Pixel, 19:22:38 hers — Samsung logs the
      `registerNetworkCallback`), the listener fired at once and armed an exact 19:30:00 alarm on
      both. So it came back by itself this time — the Pixel's listener too. At 19:30 her window ran
      (port 33327, 30 s); **the Pixel's stood aside in 25 ms** — found below — so Doze item, still
      waiting on her phone from the Doze test, did not cross. Rerun on the next build.*

**The new build, on both phones**

- [x] Largest font: avatars inside their circles, "Change" whole, on both screens. *Pixel: both
      fixed — "Shared with Ava · waiting for Ava" wraps beside a whole "Change", H/A/S inside their
      circles. Her S24 FE at font 2: the same, on her narrower screen; her font put back to 0.801.*
- [x] The Shop view marks each item with whoever added it, and nothing for an unknown adder.
      *Pixel: on S5 Alpha only Tea and Oats carry A; on Trio, Jam from the unpaired laptop reads
      "someone added this" with no mark, Honey carries S.*
- [x] Every screen's controls dumped: nothing unlabelled, the sheet's backdrop reads "Close".
      *Pixel: the edit sheet now 0 unlabelled, backdrop labelled Close. Her phone: a list, its edit
      sheet, Shop, Staples, Share, Lists — 0; Settings showed one only while a button was cut off
      by the tab bar, 0 once scrolled to.*
- [ ] The Pixel rebooted, unlocked, never opened: the wifi listener is back after one window.

**Last, then put back**

- [x] Unpair Ava and Hugo: sync stops both ways; pair again, share the lists again, and everything
      comes back. *None of her 8 real lists was shared with Hugo (counted from the backup), so only
      S5 lists were in it. The Pixel unpaired Ava: its S5 Alpha read "Just you"; an edit on each
      side stayed there 45 s ("waiting for Hugo" on hers). Paired again with her written code on the
      Pixel ("Add Ava"; her phone had never stopped trusting it): within 20 s every shared S5 list
      had both people again, in step, with no sharing again needed — S5 Alpha had all 8 items on
      both, both after-unpair edits and the stuck Window item included. Note for Hugo: pairing
      again undoes an unpairing entirely, lists and all.*
- [x] Ava's lists match the backup — the backup script run again, and the two compared; anything
      missing pasted back from the PL1 lines. *2026-10-25, after the unpair test and again after
      the cleanup: the same 8 lists, same order, same names, items, quantities and ticks. Nothing
      needed pasting back.*
- [x] Throwaway lists deleted on both phones; stay-awake, font size and alarm permission as they
      were before the session. *No S5 list left on either. Hers: font 0.801, timeout 5 min,
      alarms default, stay-awake and airplane mode off. Pixel: font 1.0, alarms default, stay-awake
      off; its wifi left on, as Hugo asked. Both with their quarter-hour job booked.*

**Still to run, on the next build:** her app sent Home, then a natural window — it should now
exchange; two closed phones in Doze at a quarter hour; both rebooted with alarms allowed, then the
first window, and Doze item (or any edit) crossing without either app opened.

### Found in the session

- [x] **Ava's phone never discovers the Pixel, so her edits never go out on their own.** Android's
      mDNS logs on both phones show every service found and expired in the same millisecond, and
      the S24 FE never sees the Pixel's service at all. Fixed (`aa0755c`): a caller tells the phone
      it calls where to call back, and a peer seen recently stays callable through the flicker.
      **Verified, session 2:** a tick on Ava's phone alone reached the Pixel in under 5 s.
- [x] **A stale discovered address shadowed the one a peer had just given.** Ava's port changes each
      time she comes back onto the wifi (40821 → 33323 → 44291); the Pixel kept dialling the old one.
      Fixed (`a304778`): the peer's own address and discovery's are tried in turn, freshest first.
      *Needs the next release build to see on phones.*
- [ ] **Open: after an app restart, the first exchange waited for an edit.** The memory is in
      process, so after the update Ava knew nowhere to call, and the Pixel — which saw Ava at
      23:23:45 — had not called her five minutes later. An edit on the Pixel started it at once.
      A closed app's background window starts with no memory the same way.
      *2026-10-21, Pixel and the laptop fake phone: does not happen between them — restarting
      either one, it called the other within 1–3 s with no edit. Both discover each other fine,
      which the S24 FE did not; still to see with her phone.*
- [x] **Keeping a removal never closed the other phone's "keep which?" card.** Both phones confirm
      the removal while the card waits, so its tombstone is collected; keeping the removal then had
      nothing to rewrite, nothing travelled, and the other card stayed open for good — offering to
      bring the item back against the answer already given. Found with the laptop fake phone,
      2026-10-21. Fixed in code, not yet committed: a tombstone an open card is about is held until
      the card is answered (`EditClashTest`). *Verified on the 22:48 build, see the rerun above.*
- [x] **A list shared again came back with its rename card open.** Delete is pressed from the rename
      card, and leaving the list never closed it, so the card was still waiting on that list's id.
      Fixed in code, not yet committed: deleting closes it (`PorygonViewModelTest`). *22:48 build:
      left Trio by its card's Delete, Sam shared it again — back as a plain card, "new, from Sam".*
- [ ] **A closed app went on announcing itself.** The open app's discovery belongs to the graph and
      was turned off only by its gate, which dies with the view model; so after the app closed the
      phone kept advertising a port that timed out, until the process died, and background windows,
      finding it "already listening", left it. Fixed in code, not yet committed: `onCleared` stops
      discovery as it already stopped pairing (`PorygonViewModelTest`). *Not seen on the phone:
      swiping the app away kills its whole process on the Pixel, which ends the advert either way,
      and "Don't keep activities" did not take effect, so the screen-gone-process-alive case could
      not be made by hand.* **Open question for Hugo:** with
      the app merely sent Home (view model alive) it still announces — keep that, or stop discovery
      whenever the app leaves the screen and let the background windows do the rest?
- [x] **Ticking an item reattributes every item in the list.** In a never-arranged list the tick
      places every item, and the sub-label followed the newest stamp, position included, so all
      four of Hugo's items read "Ava, just now". Fixed: the sub-label ignores position (`94d6ace`).
- [x] **Something added after a tick lands below the trolley.** Jam, added after Milk was ticked,
      sat under Milk on both phones: adding keyed after the highest position, which is the
      trolley's. Fixed: new items go above the first ticked one (`645010e`). *Session 3, Pixel:
      Parmesan ticked, then Jam added — Jam lands above it.*
- [ ] **An item added before the other phone's first tick arrives jumps to the top.** Butter was
      added on the Pixel while its copy was still unarranged, so it had no position, and unplaced
      items always sort first. Only happens in the window around a list's first arrangement.
- [x] A flaky view-model test (a different one failed each run): pairing work outlived the test.
      Fixed with an injectable IO dispatcher (`3c199c1`); five full runs green.
- [ ] Copy: Share with no list says "Nobody else has this list yet."; pairing an already-paired
      phone asks "Someone new, or a phone replacing one?"; times are always 24-hour.
- [ ] **The Delete button disappeared from a list's edit card.** Two ways: the card hid it with
      only one list left, though the view model has deleted the last list since `26ea4c3`; and four
      buttons in one row overran the card at a large font size or on the narrower S24 FE, pushing
      Delete off the end. Fixed (`825b6f9`): Delete is always offered and the buttons wrap.
      *Session 3, Pixel: at font scale 2, Delete wraps onto a second row; again on the 2026-10-21
      build. The one-list case is still to see — it means deleting every other list on a phone.*
- [x] **At the largest font the sync banner breaks.** The headline took the row and the detail was
      squeezed into a sliver beside it, broken mid-word ("noth/ing", "han/ded"), and the pill radius
      turned the tall banner into an oval that clipped it. Fixed (`825b6f9`): the detail wraps under
      the headline, and the corners stop at the panel radius. *Pixel, 2026-10-21 build, font 2:
      "In step with Sam" over "Home · last handover at 23:02", nothing broken or clipped.*
- [x] **Back with a sheet open popped the screen behind it.** On a list, the edit sheet open, Back
      went to All lists and left the sheet up over it. The sheet's back handler was composed before
      NavDisplay's, which therefore took the press. Fixed (`825b6f9`): the handler sits after
      NavDisplay. *Pixel, 2026-10-21 build: Back closed Honey's sheet and stayed on Trio.*
- [x] **"Approved just now" under Home, a week after approving it.** The label was stored as a
      sentence. Fixed (`825b6f9`): it is stored as a date ("Approved 16 Oct"), and the stored
      "Approved just now" reads back as "Approved". *Pixel, 2026-10-21 build: "Home · Approved".*
- [x] **At the largest font, more of the same.** Found 2026-10-21 on the Pixel at font 2. Fixed
      (`7c620b8`):
      - the initial in a list card's avatars spilled out of its circle — the letter now keeps to
        the circle's size, not the font setting;
      - a list's "Change" button broke into "Cha / nge" — the status beside it wraps instead.
      *Seen fixed on both phones, 2026-10-24 build.*
- [x] **The Shop screen marked every item from someone else with the same person's initial** — the
      first other person on the list, not whoever added it. On Trio, Jam added by the unpaired
      laptop read "someone added this" beside Sam's S. Now the adder's own initial, and no mark
      when the adder is unknown. Fixed (`7c620b8`). *Seen on the Pixel, 2026-10-24 build.*
- [x] **A loop of windows, from registering the wifi listener in every window (`18b0973`).** A
      registration fires at once for the wifi already joined; that asked for "sync now", and
      booking "sync now" again cancelled the window under way, whose replacement registered again.
      Pixel, 2026-10-24, after an install and an app open: 864 listener broadcasts in one minute,
      1,792 the next, 38 "sync now" starts, the quarter-hour window cut to 20 ms. The S24 FE has
      the same build. Fixed in code: the listener is registered once per process, and "sync now"
      is not booked again while one is booked or running (`2270e83`). *Pixel, build of 20:27: one
      broadcast in the 2½ minutes after an app open, no window started. The VPN below was on, so
      no window decided to sync; recount with it off.* *Recounted over three reboots with sync
      allowed: one broadcast per boot, one "sync now" window, which ran its full 30 s.*
- [ ] **The Pixel was behind an always-on VPN all evening, so its windows were never a test.**
      Proton VPN, always-on, came up at 19:21, with the reboot. The gate holds behind a VPN, as it
      should, so from then the open app neither listened nor advertised, and every window stood
      aside: the 19:30 window after the reboot, the 20:16 Home'd window, and Sam never finding the
      Pixel. Seen 2026-10-24, 20:30, from the Share tab ("Network 10ca69 — VPN is on") and
      `dumpsys connectivity`. Those three go back to be rerun with the VPN off.
- [ ] *Decided (Hugo, 2026-10-25): the Lists banner says "VPN is on", and a tap opens the exclude
      fix. In code: `syncHeadline` puts the VPN first whenever there is someone to sync with
      (`FormatTest`), and the banner opens a sheet on split tunnelling in exclude mode. To see on
      the next build: Proton on with PorygonList inside the tunnel, the banner and the sheet.*
- [ ] **With a VPN on, nothing syncs, and only the Share tab says why.** The Lists screen reads
      "Waiting for Sam" as though the other phone were the problem. Anyone who keeps a VPN always
      on — as Hugo does — gets no sync and no reason. **For Hugo:** say "VPN is on" in the status
      card, and/or mention in the store text that a VPN stops sync and how to get round it.
      *Split tunnelling works, no code change: Proton (always-on) → Split tunnelling → Exclude
      apps → PorygonList. Android then gives the app the plain wifi as its default network, and
      the Pixel listened and was found by Sam with the VPN connected (2026-10-24, 20:48). Watch the
      mode: in Proton's "include" mode the same list puts the app inside the tunnel instead.*
- [x] **A window started from the background can read "offline" and do nothing.** Pixel, 19:30:00
      after the reboot: the alarm started SyncWindowService, which stopped 25 ms later and armed
      19:45. The app was still firewalled as a background app (`blocked=APP_BACKGROUND`), and
      `ConnectivityManager.activeNetwork` is null for a blocked app, which the window read as
      offline; the firewall allowed the app only from 19:30:51. Fixed in code, not yet committed:
      the window waits up to 5 s for a usable network before deciding (`SettledSnapshotTest`).
      *Needs a rebuild.* Probably also why the Pixel never answered in the 18:45 Doze test.
      *Later: the always-on VPN (below the loop entry) was up from 19:21, so this window would
      have stood aside anyway; the 5 s settle still matters for the firewall, rerun with no VPN.*
      *Rerun 2026-10-24, build of 20:27 (`8c3b4c5` in it), Proton on with the app excluded, app
      never opened since the boot: the 21:15 alarm fired at 21:15:00.000, the firewall lifted
      20 ms later, the window called Sam at once and exchanged both ways (Alarm item 6 landed),
      and armed 21:30. Between windows Sam's call was refused: the port was closed.*
- [ ] **A second clash, while a card is still open, is settled silently.** The state holds one card,
      and an unanswered one is not replaced (`SyncSession.receive`: `openConflict ?: edited…`).
      The comment expects the question to come back on the next handover — true of a duplicate,
      whose two items both stay, but an edit clash has already been merged by clock and never comes
      back. Seen 2026-10-24: the Pixel still had session 4's Parmesan card open on Weekend shop,
      so Milk removed on Ava's phone against "Oat milk" on the Pixel asked only on hers; the Pixel
      dropped Milk without a word. Breaks the "keep which?" promise whenever two clashes overlap —
      on different lists, or two items on one. **Not fixed — for Hugo:** cards become a list
      (a state-format change, so the migration rule applies), or the second edit clash wins the
      slot over an older one, or the one-card limit is accepted for 0.1.0.
      *Decided: several cards (Hugo, 2026-10-25). Fixed in code — see the decision above.*
- [ ] **With the app sent Home, her phone never synced until it was opened.** Android firewalls a
      backgrounded app (`blockedReasons=40`, APP_BACKGROUND) except while a job runs. Her app,
      merely sent Home, kept its discovery and socket, so the Pixel kept finding her and its calls
      timed out, each failure dropping her address. In her window — the natural one at 18:01, when
      the firewall did lift; a forced `run -f` does not lift it — she owed the Pixel nothing and had
      never lost sight of it, so she did not call either. Nothing moved until the app was opened.
      Fixed in code, not yet committed: every background window calls each reachable peer once,
      whatever it last acknowledged, and an exchange carries both ways (`SyncCoordinatorTest`).
      *Needs a rebuild.* It also settles the open question below in practice: on the S24 FE an
      app sent Home announces a phone nobody can reach.
- [ ] **Items never arranged show in a different order on each phone.** After the unpair test S5
      Alpha held the same 8 items on both, but the last three in a different order (Pixel: Window
      item, His after unpair, Hers after unpair; hers: Hers, Window, His). `orderedItems` puts
      unplaced items first in the phone's own storage order — arrival order, which differs — and
      sorts only placed ones by key. Same root as the open "jumps to the top" item above. **Not
      fixed — for Hugo:** give every new item a position when it is added, or order unplaced items
      by something both phones share.
- [ ] **A long paste pushes "Add to …" off the screen.** The Paste a list field grows with its text
      and the sheet does not scroll, so with 200 items pasted (Pixel, 2026-10-24) neither "Add to
      S5 Big" nor Cancel could be reached; Back was the only way out. Batches of 50 fitted. A long
      list sent as text is exactly what the sheet is for. Not fixed: cap the field's height and let
      it scroll inside, or let the sheet scroll.
- [ ] **Two small things from the removal tests.** "Stop sharing this list with …?" opens with its
      Remove and Keep sharing buttons below the tab bar, until the screen is scrolled by hand. And
      the copy a removed phone keeps carries the same name, so with a same-named list of its own
      the two cards read identically ("S5 Twin · A · 1 item · just you", twice).
- [ ] **Revoking alarms stops background sync until the app is opened.** With alarms allowed the
      quarter-hour jobs are cancelled and the alarm alone carries the schedule. Revoking the
      permission makes Android cancel the app's exact alarms and kill the process ("schedule_exact_alarm
      revoked", 23:30:36) — and the state-changed broadcast is only sent on a grant, so nothing books
      the jobs again. From 23:30 to 23:48 the Pixel had no job and no alarm; opening the app at
      23:48 restored both. A wifi join would too, through WifiWatch. **Not fixed — for Hugo:** keep a
      job booked beside the alarm as a backstop (and have it stand aside while the alarm is armed),
      or a slow watchdog job that only re-checks the schedule, or accept it until "Sync is one
      switch" lands after 0.1.0.
- [x] **The sheet's backdrop was an unlabelled button to a screen reader**, the size of the screen
      (found by a dump of every screen's controls, 2026-10-21; every other screen had none). Now
      labelled "Close" (`7c620b8`). *Seen on both phones, 2026-10-24 build.*
- [x] **Open: background sync was off after a reboot.** On 2026-10-16 the Pixel had no job, no
      alarm and no wifi callback registered, after a reboot on 2026-10-13 with the app unopened since
      2026-10-10. Opening the app armed all three at once. The boot path is meant to do it without
      the app; to rerun with a controlled reboot.
      *Rerun 2026-10-21, after Hugo rebooted the Pixel and unlocked it without opening the app: the
      boot broadcast reached WifiWatch (22:52:45, process started for it), the persisted sync job
      was there and ran at 23:02 — exchanging with the fake phone Sam, unopened — but **no wifi
      listener**. Opening the app registered it at once (`LISTEN … VALIDATED → PendingIntent`). Why
      the boot registration is missing is not known: `register` swallows its exception and nothing
      logs listens. Worked round, not yet committed: every background window registers it again.
      To verify: rebuild, reboot, unlock without opening, wait one window, check the listener.*
      *Verified 2026-10-24, three reboots on the build of 20:27: the listener came back from the
      boot broadcast itself, before any window ran (one broadcast, about a second after the
      process started), and its window exchanged with Sam. See the reboot item under "Meanwhile".*

### App closed

The procedure is in the notes for `BackgroundSync`, `SyncJob` and `WifiWatch`.

- [ ] A forced background window advertises and exchanges, then goes quiet. *2026-10-21, Pixel
      with the app sent Home, and the laptop fake phone Sam: Sam's call timed out while the app was
      away; the forced job (id now 20578, not 20569) exchanged with Sam within 1 s, and its port
      was closed after the 30 s window. But the advert the open app had started at 22:20 was still
      registered at 22:43 — found below. Rerun on the next build with the app swiped away.*
      *22:48 build: swiped away, the process went entirely and the advert with it; the forced
      window itself was already seen to exchange and close its port. What is left is the
      mid-case in "a closed app went on announcing itself" below.*
- [x] On a wifi that is not approved, nothing is announced. *Pixel, by hand, Hugo, 2026-10-21.*
- [x] Joining the home wifi starts the schedule and runs a window; joining another stops it.
      *Pixel, by hand, Hugo, 2026-10-21.*
- [x] Reboot: the phone comes back on the approved wifi and catches up without being opened.
      *Pixel, 2026-10-21: rebooted, unlocked, never opened; the persisted job's window at 23:02
      exchanged with the fake phone Sam, which had added Rice while the Pixel was closed. The wifi
      listener did not come back with it — see "background sync was off after a reboot" above.*
- [ ] Alarms allowed on both: two closed phones, in Doze, exchange at the next :00/:15/:30/:45.
      *Pixel side, 2026-10-21: app closed, screen off, `dumpsys battery unplug` and `deviceidle
      force-idle` (deep IDLE) at 23:20; at 23:30:00 exactly it called the fake phone Sam. Sam never
      sleeps, so "two closed phones" waits for Ava's phone.*
- [x] Allowing alarms in system settings, app closed, moves the schedule onto the quarter hour.
      *Pixel, 2026-10-21, 23:19: app sent Home, alarms allowed by `appops` (the same switch as the
      system setting) — within 5 s an exact RTC_WAKEUP for 23:30:00, `window=0`,
      `exactAllowReason=permission`; before, no alarm at all.*
- [ ] Alarms not allowed: a window still runs at the quarter hour while awake or charging, and
      *Pixel, 2026-10-21: Settings offers "Allow alarms" (seen at 23:07). But revoking alarms at
      23:30 left no window at all — see "revoking alarms stops background sync" below. Rerun from a
      phone that never had alarms, once that is decided.*
      Settings offers "Allow alarms".

### Overnight

- [ ] One night with background sync, one without: battery used by PorygonList on both phones.

### To finish

- [ ] Phone screenshots, 3–6, into `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
      *Drafts, 2026-10-21, in `captures/store-draft/` (git-ignored): a shared list, the Shop view, the
      Share screen, a "keep which?" card — "Weekend shop" with the fake phone Sam, SystemUI demo
      mode. Not copied into fastlane yet: GrapheneOS kept the notification icons in demo mode, and
      the next build changes the avatars. Hugo to choose; retake on the final build.*

## Store preparation

### Both stores

- [x] Generate the signing key (2026-10-09). SHA-256
      `3f6ec695199d89ca0509070f68320262c9c3ad3cf29a662defe4aaf414b01d81`, already in the F-Droid
      draft. The password is asked for at build time, never stored. It never goes in the repo.
- [ ] Back the key and its password up somewhere that is not this machine. Losing either means
      never updating the app again. **Owner's job.**
- [x] Release signing config that reads the key from outside the repo: `keystore.properties`
      (git-ignored). Without it the release build is unsigned, as F-Droid expects; tried both ways
      with a throwaway key, and the unsigned build is still byte-identical across clean builds.
- [x] Version `0.1.0`, version code 1. Bump both for every release, never reuse a code.
- [x] Store text, changelog and icon in `fastlane/metadata/android/en-US/` (icon from
      `./scripts/icon.sh store`). *Lengths 2026-10-25: title 11/50, short 76/80, full 2152/4000,
      changelog 342/500. `images/phoneScreenshots/` still empty — see screenshots above.*
- [ ] Reread the store text after the phone session; it describes the app as that leaves it.
      *Reread 2026-10-21 against the session: accurate. "Run at startup, so background sync carries
      on after a restart" holds only once the wifi-listener workaround is built and seen after a
      reboot. Read again before the tag.*
- [x] Merge `list-identity` into `main` (fast-forward, pushed `63b366a`). Work continues on `main`.
- [ ] Tag `v0.1.0` on `main` once everything above is ticked.

### F-Droid

- [ ] Two clean release builds here came out byte-identical (2026-10-06). Confirm the same on
      F-Droid's build server, which uses different paths and its own JDK.
      *2026-10-25: an unsigned build of `d784102` in the dev container, from a `git archive` in
      another directory with Debian's JDK, against Hugo's signed host build of the same commit:
      97 entries, same order, every entry byte-identical but one —
      `META-INF/version-control-info.textproto`, where AGP records the commit (`revision:
      "d784102…"`), absent from an export with no `.git`. F-Droid builds from a git checkout, so it
      matches only when the signed APK is built from exactly the tagged commit, with a clean tree:
      `./scripts/release.sh` enforces that. The signed APK's certificate is the draft's
      `3f6ec695…1d81`, v2 scheme, 0.1.0 (1), target SDK 36; the Gradle wrapper's checksum is
      pinned.*
- [x] Confirm the build server has JDK 21, or add an install step to the recipe. Its default in
      `buildserver-trixie`; `./scripts/fdroid-check.sh` built `d1994a0` there with no extra step
      (2026-10-10).
- [x] `fdroid lint` on the draft metadata (fdroidserver 2.4.5, 2026-10-06): clean apart from the
      placeholder signing key; "Shopping List" is a valid category; field order matches
      `fdroid rewritemeta`. Linted again with the real key, 2026-10-10: clean.
- [ ] `fdroid build` locally, in F-Droid's build image — needs the tag and Docker.
- [ ] Merge request to `fdroid/fdroiddata` adding `metadata/io.github.molleware.porygonlist.yml`,
      with our signing certificate's hash so F-Droid ships our APK. Draft:
      [docs/fdroid/io.github.molleware.porygonlist.yml](fdroid/io.github.molleware.porygonlist.yml).
- [ ] A GitHub release for `v0.1.0` with our signed APK attached, under the name the draft's
      `Binaries` line expects. F-Droid compares against it.

**The release, in order, once everything above is ticked** (host only — the key and push stay
there):

1. `git tag -a v0.1.0 -m "PorygonList 0.1.0"` on the commit to release, working tree clean.
2. `./scripts/release.sh` — builds the signed APK from that commit, checks its version, its
   certificate against the draft and the commit it records, and leaves
   `build/release/porygonlist-0.1.0.apk`.
3. `git push origin main v0.1.0`.
4. `./scripts/fdroid-check.sh v0.1.0` — lint, `fdroid build` of the tag in F-Droid's image, and
   F-Droid's comparison with our APK. Stop here if it differs: a new tag costs a version code.
5. `gh release create v0.1.0 build/release/porygonlist-0.1.0.apk --title "PorygonList 0.1.0"
   --notes-file fastlane/metadata/android/en-US/changelogs/1.txt` (release.sh prints this line).
6. The merge request to fdroiddata with the draft.

### Play Store

- [ ] Play Console account and app entry; enrol in Play App Signing with **our own** key.
- [x] Privacy policy: [docs/privacy.md](privacy.md). Its GitHub page is the URL to give Play.
- [ ] Data safety form: no data collected or shared; no backup.
- [ ] Location permission declaration. Fine location is requested only to show the wifi's name and
      the app works without it. The Share screen already explains this beside the "Use the wifi's
      own name" button, before Android's prompt — probably enough as the in-app disclosure, but
      check it against Play's current wording when filling in the form.
- [ ] Content rating questionnaire, target audience, store category.
- [ ] Upload an app bundle (`bundleRelease`) to internal testing first, then production. The
      bundle builds (2026-10-06, 3.5 MB unsigned); it signs itself once `keystore.properties` exists.

## After 0.1.0

Features for the next release. Nothing here holds up the `v0.1.0` tag.

- [ ] **Clock and date up top.** The current time and date shown at the top of the screen.
- [ ] **Gamification, with a geo option.** The geo option is switched on and off in Settings. The
      privacy policy and Play's location declaration say location only reads the wifi's name; a geo
      option means updating both.
- [ ] **List history.**
- [ ] **Recipes beside staples.** A recipe is a sublist of its ingredients. One tap on it adds
      them all to the list, tagged, and each added item says which recipe it is an ingredient for.
- [ ] **Sync is one switch in Settings.** Turning it on needs alarms allowed; without them it stays
      off. This replaces today's two modes, "on the quarter hour" with alarms and "around the
      quarter hour" without, so sync is simple and predictable: on and on time, or off.
- [ ] **Small wording and layout from session 5** (left out of 0.1.0, 2026-10-25): "Stop sharing
      …?" opens under the tab bar; a kept private copy reads exactly like a same-named list of
      one's own; an already-paired code still asks "Someone new, or a phone replacing one?"; "new,
      from someone" once the sender is unpaired.
- [ ] **A TalkBack pass by ear.** Every screen, listening for anything unclear or said twice. The
      labels themselves are checked for 0.1.0 by dumps on both phones.
- [ ] **Sync in a store, phone to phone.** Bluetooth LE between paired phones, so lists sync where
      there is no shared wifi — in a large store, catching up whenever the phones come back within
      range (about 20–40 m indoors). Wi-Fi Aware could back it up on phones that have it. No Google
      Play Services. Open: whether a direct link needs only pairing, since there is no network to
      approve. LoRa (Meshtastic radios) is the later option if the gaps across a whole store bother
      us (Hugo, 2026-10-25).
- [ ] **Updating from a build with the old periodic job replaces it rather than running both.**
      Moved out of 0.1.0 (2026-10-24): 0.1.0 is the first release, so only the two test phones ever
      had that job, and both have updated past it — the Pixel shows only the one-off jobs. Testing
      it means installing a 9 October build over today's state file, which may not read it. The
      code that replaces it (`SyncJob.ensureScheduled`) can go once no phone could still have it.
