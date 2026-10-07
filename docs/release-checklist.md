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
- **Lists are throwaway.** Uninstalling, or restoring a backup onto a new phone, loses them along with
  the phone's identity. Accepted: this is a list app for things you will have bought by Friday.

## Fix before release

- [x] **Removing someone from a list does not stick.** Now a stamped tombstone, held until everyone
      on the list and the removed person have confirmed it; the removed phone keeps a private copy.
      Needs the phone check below.
- [x] **Unpairing had the same bug** — a third phone on the list handed the person back. Now a
      tombstone on every list too, without waiting on the unpaired phone.
- [x] **"In step with Ava" is claimed without evidence.** The list card and banner now follow the
      delivery receipts: "in step with", "waiting for", or "not handed over yet".
- [ ] **Two edits of the same item at once resolve silently.** The merge finds them and throws the
      finding away. The agreed rule is automatic merge with genuine conflicts surfaced, so this needs
      the "keep which?" card — or an explicit decision that silence is fine for a throwaway list.
- [x] **The sync banner's "a moment ago" is hard-coded.** It now gives the time of the last
      confirmed handover.
- [x] **Copy that assumes exactly two people** ("both phones", "Laptop sees this the moment you save")
      on lists shared with three or more. Everyone on the list is named now; the Shop screen no
      longer claims somebody is shopping right now.
- [ ] **Dragging a row past the edge of the screen does not scroll**, so a long list can only be
      rearranged a screenful at a time.
- [ ] **The design's fonts are not in the app.** `scripts/fetch-fonts.sh` was never run, so it draws in
      the system face. Run it and commit the TTFs (SIL OFL — note them in the README), or decide the
      system font is the look.
- [ ] **Backups.** `allowBackup="true"` copies the lists to the owner's cloud backup, and a restore then
      throws them away (the identity key never travels). Either turn backup off, which is honest about
      throwaway lists and keeps list contents off Google's servers, or leave it and accept the copy.
- [x] **README is out of date.** Updated in `63b366a`.
- [ ] **A shared list arrives silently.** Nothing says who shared it, and one that has the same name
      as a list you already have shows up as a second, identical-looking card. Show "from Ava" on the
      card until it is first opened. (Offering to combine two same-named lists is a possible later
      step, not needed for 0.1.0.)
- [ ] **No About screen.** Version, the GPL, where to report a bug, and credits for the open-source
      libraries the app ships.
- [ ] **Accessibility.** Content descriptions on every control that has no text; then a TalkBack and
      largest-font pass on a phone (below).
- [ ] **Language.** English only. A French translation is the owner's call.

## Check on real phones

Release build (`assembleRelease`, R8 on), not debug — minification has to be proven, not assumed.

- [ ] First run on a clean install: naming, first list, nothing left over from development.
- [ ] Pairing two phones from scratch, both directions.
- [ ] A shared list arriving, edits both ways, tick and untick, remove, rename.
- [ ] Leaving a list, and being added back.
- [ ] Someone removing you (after the fix above).
- [ ] Unpairing cuts sync off.
- [ ] The same item edited on both phones at once.
- [ ] Reboot: comes back on the approved wifi and catches up without being opened.
- [ ] Off the wifi and back: edits from both sides merge.
- [ ] A 200-item list: how long the handover takes, and whether the phone stutters.
- [ ] Item order travelling between two phones; list order and pins staying on one.
- [ ] Background windows with the app closed — the procedure is in the notes for `SyncJob`/`WifiWatch`:
      a forced window advertises and exchanges, an unapproved wifi announces nothing, joining home
      wifi starts the schedule, joining another stops it, a reboot re-registers.
- [ ] One night with background sync, one without: battery used by PorygonList, both phones.
- [ ] The list card's avatars sit against the right edge (fixed in `d679f81`, not yet looked at).
- [ ] TalkBack through every screen, and the largest font size: nothing unlabelled, nothing clipped.
- [ ] Dark mode, if the theme has one; otherwise decide that it does not.

## Store preparation

### Both stores

- [ ] Generate the signing key and back it up somewhere that is not this machine. Losing it means
      never updating the app again. It never goes in the repo.
- [ ] Release signing config that reads the key from outside the repo (environment or a local file
      that is git-ignored).
- [x] Version `0.1.0`, version code 1. Bump both for every release, never reuse a code.
- [x] Store text, changelog and icon in `fastlane/metadata/android/en-US/` (icon from
      `./scripts/icon.sh store`).
- [ ] Phone screenshots in `fastlane/metadata/android/en-US/images/phoneScreenshots/`, 3–6, taken
      from the release build with lists that are not anyone's real ones.
- [ ] Reread the store text once the "Fix before release" list is done; it describes the app as
      that list leaves it.
- [ ] Merge `list-identity` into `main`, then tag `v0.1.0` on `main`.

### F-Droid

- [ ] Two clean release builds here came out byte-identical (2026-10-06). Confirm the same on
      F-Droid's build server, which uses different paths and its own JDK.
- [ ] Confirm the build server has JDK 21, or add an install step to the recipe.
- [ ] `fdroid lint` and `fdroid build` locally, in F-Droid's build image.
- [ ] Merge request to `fdroid/fdroiddata` adding `metadata/io.github.molleware.porygonlist.yml`,
      with our signing certificate's hash so F-Droid ships our APK.

### Play Store

- [ ] Play Console account and app entry; enrol in Play App Signing with **our own** key.
- [ ] Privacy policy page. Short and true: nothing is collected, there is no server, lists travel only
      between paired phones on approved wifi.
- [ ] Data safety form: no data collected or shared.
- [ ] Location permission declaration. Fine location is requested only to show the wifi's name and
      the app works without it; Play may want an in-app disclosure before the prompt.
- [ ] Content rating questionnaire, target audience, store category.
- [ ] Upload an app bundle (`bundleRelease`) to internal testing first, then production.
