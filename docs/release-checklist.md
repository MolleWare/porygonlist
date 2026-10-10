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
- [ ] **Dragging a row past the edge of the screen does not scroll.** Written (`512c68d`), not yet
      tried by hand — see the phone session.
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

### One phone at a time

- [ ] First run on a clean install: naming, first list, nothing left over from development.
- [ ] About: the version reads 0.1.0, both links open the browser.
- [ ] Edit order on a 30-item list: drag end to end, the screen scrolls along at the edges, the
      speed feels right, and the bottom zone sits above the tab bar.
- [ ] Pin and unpin lists; list order stays as arranged after a restart.
- [ ] The list card's avatars sit against the right edge (`d679f81`).
- [ ] TalkBack through every screen, then the largest font size: nothing unlabelled, nothing clipped.
- [ ] `adb shell bmgr backupnow io.github.molleware.porygonlist` reports the app as not eligible.

### Both phones

- [ ] Pair from scratch, both directions.
- [ ] Share a list: it arrives reading "new, from …"; edits both ways; tick and untick; remove;
      rename.
- [ ] A shared list named like one the other phone already has: two cards, told apart.
- [ ] Item order travels; list order and pins stay on each phone.
- [ ] "In step with" appears only after a handover; "waiting for" while one phone is away.
- [ ] Same item changed on both phones while one is off the wifi: "keep which?" on both, the answer
      on one closes the other's card. Then the same with a removal against a rename.
- [ ] Remove the other person from a list: it sticks, and their phone keeps a private copy.
- [ ] Leave a list, and be added back.
- [ ] Unpair: sync stops, and a third copy of the list does not bring them back.
- [ ] Off the wifi and back: edits from both sides merge.
- [ ] A 200-item list: how long the handover takes, and whether either phone stutters.

### App closed

The procedure is in the notes for `BackgroundSync`, `SyncJob` and `WifiWatch`.

- [ ] A forced background window advertises and exchanges, then goes quiet.
- [ ] On a wifi that is not approved, nothing is announced.
- [ ] Joining the home wifi starts the schedule and runs a window; joining another stops it.
- [ ] Reboot: the phone comes back on the approved wifi and catches up without being opened.
- [ ] Alarms allowed on both: two closed phones, in Doze, exchange at the next :00/:15/:30/:45.
- [ ] Allowing alarms in system settings, app closed, moves the schedule onto the quarter hour.
- [ ] Alarms not allowed: a window still runs at the quarter hour while awake or charging, and
      Settings offers "Allow alarms".
- [ ] Updating from a build with the old periodic job replaces it rather than running both.

### Overnight

- [ ] One night with background sync, one without: battery used by PorygonList on both phones.

### To finish

- [ ] Phone screenshots, 3–6, into `fastlane/metadata/android/en-US/images/phoneScreenshots/`.

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
