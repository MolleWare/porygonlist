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
- [ ] **Dragging a row past the edge of the screen does not scroll.** Written (`512c68d`). It
      scrolls on the Pixel (session 3, by script); the feel by hand is still to try.
- [x] **Backups.** Off, by decision (above).
- [x] **README is out of date.** Updated in `63b366a`.
- [x] **A shared list arrives silently.** Its card reads "new, from Ava" until first opened.
- [x] **No About screen.** An About section at the bottom of Settings.
- [x] **Accessibility, in code.** Checkboxes named for their item, Shop rows state ticked, tabs state
      selected. The TalkBack pass on a phone is in the session below.

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
- [ ] Edit order on a 30-item list: drag end to end, the screen scrolls along at the edges, the
      speed feels right, and the bottom zone sits above the tab bar. *Session 3, Pixel, by script
      (`device.sh drag`): Row01 held just above the tab bar scrolled the list to the end and dropped
      below Row30 — about 22 rows in 3 s. Whether that speed feels right is left for a hand; the
      list "Thirty" is on the Pixel for it.*
- [x] Pin and unpin lists; list order stays as arranged after a restart. *Pixel, session 3: stuff
      pinned to the top, still pinned after a cold restart; unpinned, it went back below Spare;
      dragged above Spare by its handle, still there after another restart.*
- [x] The list card's avatars sit against the right edge (`d679f81`). *Pixel, 2026-10-21: "Fake shop"
      shared from the laptop fake phone, L and H flush right on the card.*
- [ ] TalkBack through every screen, then the largest font size: nothing unlabelled, nothing clipped.
      *Pixel, 2026-10-21: every screen's controls dumped (lists, a list, its edit sheet, Edit
      order, Shop, Staples, Share, Settings, pairing) — one unlabelled, the sheet's backdrop; and
      every screen at font 2 — avatars and "Change" broke. All three fixed below; recheck on the
      next build, and listen to TalkBack itself once by hand.*
- [x] `adb shell bmgr backupnow io.github.molleware.porygonlist` reports the app as not eligible.
      Both phones: "Backup is not allowed", session 1.

### Both phones

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
- [ ] **At the largest font, more of the same.** Found 2026-10-21 on the Pixel at font 2. Fixed in
      code, not yet committed; *needs a rebuild*:
      - the initial in a list card's avatars spilled out of its circle — the letter now keeps to
        the circle's size, not the font setting;
      - a list's "Change" button broke into "Cha / nge" — the status beside it wraps instead.
- [ ] **The Shop screen marked every item from someone else with the same person's initial** — the
      first other person on the list, not whoever added it. On Trio, Jam added by the unpaired
      laptop read "someone added this" beside Sam's S. Now the adder's own initial, and no mark
      when the adder is unknown. Fixed in code, not yet committed. *Needs a rebuild.*
- [ ] **Revoking alarms stops background sync until the app is opened.** With alarms allowed the
      quarter-hour jobs are cancelled and the alarm alone carries the schedule. Revoking the
      permission makes Android cancel the app's exact alarms and kill the process ("schedule_exact_alarm
      revoked", 23:30:36) — and the state-changed broadcast is only sent on a grant, so nothing books
      the jobs again. From 23:30 to 23:48 the Pixel had no job and no alarm; opening the app at
      23:48 restored both. A wifi join would too, through WifiWatch. **Not fixed — for Hugo:** keep a
      job booked beside the alarm as a backstop (and have it stand aside while the alarm is armed),
      or a slow watchdog job that only re-checks the schedule, or accept it until "Sync is one
      switch" lands after 0.1.0.
- [ ] **The sheet's backdrop was an unlabelled button to a screen reader**, the size of the screen
      (found by a dump of every screen's controls, 2026-10-21; every other screen had none). Now
      labelled "Close". Fixed in code, not yet committed. *Needs a rebuild.*
- [ ] **Open: background sync was off after a reboot.** On 2026-10-16 the Pixel had no job, no
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
- [ ] Updating from a build with the old periodic job replaces it rather than running both.

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
      `./scripts/icon.sh store`).
- [ ] Reread the store text after the phone session; it describes the app as that leaves it.
      *Reread 2026-10-21 against the session: accurate. "Run at startup, so background sync carries
      on after a restart" holds only once the wifi-listener workaround is built and seen after a
      reboot. Read again before the tag.*
- [x] Merge `list-identity` into `main` (fast-forward, pushed `63b366a`). Work continues on `main`.
- [ ] Tag `v0.1.0` on `main` once everything above is ticked.

### F-Droid

- [ ] Two clean release builds here came out byte-identical (2026-10-06). Confirm the same on
      F-Droid's build server, which uses different paths and its own JDK.
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
