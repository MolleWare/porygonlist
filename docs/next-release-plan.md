# Next release (0.2.0): plan

Built while Ava's phone is away (from 2026-11-01, one to two weeks). Work happens on the `next`
branch, in the worktree `.worktrees/next`, so the main checkout stays on `main` for the `v0.1.0`
screenshots and tag. `next` is merged into `main` once `v0.1.0` exists.

Each feature is coded by one agent at a time, in the order below, then reviewed and checked on the
Pixel before the next starts. One at a time because several of them touch the same save and sync
formats.

## Ground rules for every feature

- **0.1.0 phones must keep syncing with 0.2.0 phones.** Ava may still be on 0.1.0 when Hugo updates.
  `PLSYNC2` is not bumped. New data goes in fields appended at the end of an existing record, or in
  a new record type; 0.1.0's reader ignores both. A field a 0.1.0 phone leaves out must never
  overwrite one a 0.2.0 phone has: give it a stamp, and read "absent" as the oldest possible stamp.
- **Every save written so far must still read.** The state file stays `PLSTATE11`, with additions
  made the same way. `PLSTATE10` (both test phones) and the 0.1.0 `PLSTATE11` must read; test with
  `asVersion10` and with a fixture written by `ef88918`.
- **The startup budget holds** (see the `performance` skill): nothing new in `Application.onCreate`,
  nothing read before the first frame that was not read before.
- **Tests:** logic in JVM tests, each new test seen failing without the change; `./scripts/test.sh`
  green before a commit.
- **Commits:** on `next` only, explicit paths staged, no AI attribution lines. Nothing pushed.
- **Copy:** plain English in the voice already in the app. Light theme only, system font.

## How each feature is checked on the Pixel

The Pixel's release app (Hugo's daily lists, paired with Ava) is never touched. Feature builds are
debug builds installed beside it under `io.github.molleware.porygonlist.next`, with their own
identity, and paired with the laptop fake phone for anything that syncs. Interop with 0.1.0 is
checked by running the fake phone from a worktree at `ef88918`.

## The features, in order

### 1. Small polish

- **"Stop sharing … with …?" opens under the tab bar.** Its Remove and Keep sharing buttons sit
  below the tab bar until scrolled to. Bring them into view when it opens.
- **A kept private copy reads exactly like a same-named list of one's own.** After someone is
  removed from a list, their phone keeps a copy; beside their own list of the same name the two
  cards are identical. Tell the kept copy apart on its card.
- **An already-paired code still asks "Someone new, or a phone replacing one?"** — also asked when
  nobody is paired at all. Ask only when the code could really be a replacement; otherwise go
  straight to adding.
- **"new, from someone" once the sender is unpaired.** Use the sender's name from the list's own
  people where it is known.
- **Clock and date at the top of the Lists screen.** Follows the phone's 12/24-hour setting, as
  times already do; updates on the minute without recomposing the list.

### 2. Avatar choice

- Six food emoji to choose from: 🍎 🥕 🥑 🍋 🍄 🥦. Chosen in Settings, beside the name; the
  initial stays the default until one is chosen.
- Stored and sent **by id** (a short stable key per avatar, not the emoji itself), so drawn avatars
  can replace the emoji later and more can be added. A catalog with every avatar marked free or
  locked; all six free. Later avatars will be unlocked by usage points from gamification, which is
  **not** built now — only the catalog's room for it.
- Shown wherever an initial is drawn today (the `Avatar` circle): list cards, the Shop marks, the
  Share and Settings screens. Travels to paired phones the way a name change does, as a trailing
  field on the `person` record. A 0.1.0 phone keeps showing the initial; an unknown id shows the
  initial.

### 3. Recipes beside staples

- A recipe is a name and a list of ingredients (name and quantity), kept on this phone like
  staples, and edited on the Staples tab.
- One tap on a recipe adds its ingredients to the open list. Each added item says which recipe it
  is for ("for Lasagne"); an item can be for several.
- An ingredient already on the list and unticked: its quantity goes up by the recipe's, and it
  gains the recipe's tag. Otherwise it is added as a new item.
- The "for …" tag travels with the item to everyone on the list (a stamped field, trailing on the
  `item` record). Recipes themselves do not travel.

### 4. Merge two lists

- From a list's edit card, "Merge into…", then the list to merge into.
- Items with the same name (ignoring case) that are unticked in both become one, quantities added;
  everything else is copied across as it is, keeping who added it.
- The merged list keeps the people it was already shared with. Nobody is added to a share
  without being shared with.
- The other list is then deleted the way Delete does today: anyone it was shared with keeps their
  copy.

### 5. Sync is one switch

- One switch in Settings replaces "on the quarter hour" / "around the quarter hour". On needs
  alarms allowed; without them it cannot be turned on and says why, with the way to allow them.
- **Off stops background sync only.** With the app open, lists still sync with paired phones on an
  approved wifi.
- Revoking alarms turns it off. The backstop job, the "Allow alarms" card on the Lists screen and the
  15–30 minute promise in the store text are revisited to match.

### 6. Send only what changed

- Every exchange sends every shared list whole: 13 MB each way on the battery night. Send a peer
  only what it has not confirmed (the delivery receipts already say how far each peer has got).
- A phone says it understands partial payloads with a trailing flag on its `from` record. A peer
  that has not said so — every 0.1.0 phone — keeps getting whole lists.
- Check first what an absent list or item means to the receiver's merge today; a partial payload
  must never read as "removed".
- Last, because it is the riskiest: checked against the fake phone at `next` and at `ef88918`, and
  with Ava's phone when it is back.

## Needs Ava's phone, when it is back

The two-phone checks of 2, 3, 4 (a merged list arriving on hers), 5 and 6, and an update of hers
from 0.1.0 to 0.2.0 with her lists backed up first.
