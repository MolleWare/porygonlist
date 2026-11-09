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
- **Built.** A phone keeps, per peer, the exact line of everything that peer acknowledged, and sends
  each list partial: its line, its people, the items that changed, the ids of the rest (`held`).
  Chosen by content, not stamps. The flag is a run token on `from`, new each process; 0.1.0 never
  sends one and gets whole lists. Whole again after a peer's restart, and every tenth payload.
- **Measured.** 200 items, one tick: 75 kB whole, 1.7 kB partial (971 B between fake phones).
- **The limit.** What each peer confirmed is in memory only (Hugo, 2026-11-02: not saved for
  0.2.0), so a background window that wakes a new process starts with one whole exchange each way.
- **Found on the way, fixed.** With three people, a removal heard of through another phone was
  collected on a receipt from before it arrived, so the third phone never heard. Collection now
  waits for a receipt from after this phone heard, kept in the save, never sent.

### 7. List history

Asked for and decided by Hugo, 2026-11-06. Local to this phone: nothing about history travels, and
no sync format changes.

**What is recorded**

- One press of Clear ticked on this phone is one run: the time it was pressed, and each cleared
  item's name and quantity. A press that clears nothing records nothing.
- Only this phone's own clears. A clear that arrives from another phone is not recorded, nor is an
  item removed by hand.
- The newest 50 runs per list; the oldest drops off when a 51st is recorded.
- History starts empty on update: earlier clears were never kept, and tombstones are no record of
  them, since they are collected once delivered.
- Saved as a new record type, which 0.1.0 skips. It is read with the state file, so the cap is what
  keeps the startup budget.
- It belongs to the list by id. Delete takes it away. Merge moves the other list's runs into the
  target, newest first, capped at 50, because its items now live there. A kept private copy, which
  gets a new id, keeps the history.

**The history page**

- A History button on the list screen opens a page for that list, reached and left the way Share
  is, not a new tab.
- Runs newest first, each titled with its date and time, its items beneath. Empty, it says what
  will appear there.
- Tapping an item selects it; tapping a run's title selects or unselects the whole run. A selection
  can span runs. Selected items that fold to the same name count once, with the newest run's
  quantity.
- While anything is selected, three actions and a count: **Save as recipe** (asks for a name; a
  name already taken is refused the way the recipe form refuses it), **Add to recipe** (pick one; an
  ingredient it already has keeps its quantity), and **Put back on the list** (adds as adding does
  today, so an unticked item of that name goes up by the quantity). The selection clears after any
  of them.
- History items are not editable and cannot be deleted one by one.

**Using a recipe changes: checklist first**

- Tapping a recipe no longer adds straight away. It opens its ingredients, all ticked, and an "Add N
  to list" button. Untick what is not wanted this time; nothing is remembered about that next time.
  With nothing ticked the button does nothing and says so. This replaces the one-tap add from
  feature 3, and it is also how a group of things always needed is used, so there is no second kind.
- A long press still edits the recipe. Quantities, the "for …" tag and the merge into an unticked
  item work as in feature 3, for the ticked ingredients only.

**Built.** As above, with these left to the build: the back link and back gesture return to the
list by adding the page to the back stack, as Settings does, since Share's replacing it would make
the back gesture leave the app. The same name twice in one run counts as it comes first. A selection
lasts one visit to the page. Add to recipe with no recipes says so in its sheet. The three actions
say nothing afterwards beyond the selection clearing.

### 8. Learned staples

Decided by Hugo, 2026-11-09. Local to this phone, like everything about staples: nothing travels,
and no sync format changes. Built on feature 7's runs.

**Two kinds, one grid**

- **Hand-made staples** are today's: made on the Staples tab, shared by every list, never removed
  except by hand, in the order they were made.
- **Learned staples** belong to one list. The Staples tab shows the hand-made ones first, then the
  open list's learned ones, most recently added first. Tapping either adds to the open list, as
  today. A learned tile looks and reads like any other tile.
- Nothing announces a learned staple, its arrival or its removal. People find it on their own.

**Learning**

- Checked at each press of Clear ticked on this phone, right after the run is recorded, never at
  startup. An item is learned for that list when it is in at least 3 of the list's last 4 runs,
  matched by folded name (`WordTrie.fold`), shown as the newest run has it.
- Not learned when it is already a hand-made staple or one of this list's learned staples, or when
  it was removed from this list's staples by hand (below).
- Its "last added" starts as the time of the newest run that holds it.

**Forgetting**

- A learned staple keeps when its name was last added to that list on this phone: typed, a
  suggestion, a staple tap, a recipe, Put back, anything that goes through adding. Adds that arrive
  from another phone do not count. Hand-made staples keep no such date and are never forgotten.
- Unused for 3 calendar months, it is gone: hidden from the grid from then on, and dropped from the
  save at the next Clear ticked on that list.
- Removed by hand (the same gesture and confirm as a hand-made tile), it is remembered as unwanted
  for that list and not learned there again for the next 50 presses of Clear ticked on it (Hugo,
  2026-11-09), after which it can be learned again by the usual rule. Making a hand-made staple
  of that name lifts it at once.

**Belongs to its list**

- Kept by list id on `AppState`, like history, never on the `GroceryList`. Delete takes the list's
  learned staples and unwanted names with it; merge moves them to the target (a name the target
  already has keeps the target's); a kept private copy keeps them.
- New record types in the state file, which 0.1.0 skips. Still `PLSTATE11`.
- The add field's suggestions rank the open list's learned staples with the hand-made ones.

**Built.** As above, with these left to the build: a paste is not an add for "last added", since
what it brings is someone else's message. A name is not learned when the newest run holding it is
already three months old, or it would be learned only to be hidden. On a merge, a name the target
has marked unwanted stays unwanted, and the source's learned staple of that name is dropped.

## Needs Ava's phone, when it is back

The two-phone checks of 2, 3, 4 (a merged list arriving on hers), 5 and 6, and an update of hers
from 0.1.0 to 0.2.0 with her lists backed up first.
