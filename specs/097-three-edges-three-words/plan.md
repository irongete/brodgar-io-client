# 097 — three edges, three words: plan

## Approach

Eleven spellings, one unit, one suite. They are one change because they are one rule: doing four of the six
"went away" spellings and leaving `Destroy` and `disappear` would leave the finding alive and the next
audit reporting it again.

The mechanical half is a rename across `src/io/brodgar/addon/**`, `docs/addons/**` and the tools, with
`Retired.java` **excluded** — its rows name the old spellings on purpose. The half that needed thought is
`QuestDone`, which is not a rename at all.

`Retired.eventKey` grew a `why` per row, because 097 retires keys for three different reasons — the `On`
prefix going (041), the three edges being three words, and one word having named two levels — and a reader
who hits one is owed the one that applies. The three existing rows now pass their own reason instead of
inheriting a hardcoded clause about `:on` already saying "on".

## Gotchas found while doing it

**A blanket `APPEAR` → `ADDED` ate a word of prose.** `THE LINE THAT APPEARS WHEN THE POINTER RESTS ON IT`
became `THE LINE THAT ADDEDS`. Renaming a short upper-case token across a tree needs a damage scan
afterwards, not just a leftover scan.

**Escaped quotes hid two occurrences.** `\"appear\"` inside a Java string literal does not contain the
substring `"appear"` — the backslash sits between the quote and the word — so the refusal in
`UiApi.selectorOn` survived a pass that looked clean everywhere else. The check for leftovers has to search
the escaped form as well as the bare one.

**The emitter string is a map key, not prose.** `Retired.eventKey(emitter, key)` looks up
`emitter + "|" + key`, so the rows and the door have to spell the emitter identically. `UIS` is
`"session:ui()"`, and rows written against `"s:ui()"` would have compiled, run, and never matched.

**`onMarkersChanged` is haven's seam, not ours.** Five call sites in `haven/MapFile.java` carry it. A
regex with no lookbehind renames those too, which is five gratuitous core edits for a Lua key nobody reads
from Java. The Lua key is singular; the seam keeps its name and its javadoc says why.

**A `[manual]` line was unavoidable exactly once.** Everything else here is observable — keys accepted,
retirements refused, messages checked for the words they must carry, both frame edges timed and compared.
A quest *outcome* is the server's, so which of the two keys a real completion fires is the one thing a
program cannot cause.

## Discarded alternatives

- **Keeping `FlowerMenuOpened`/`Closed`** and writing on `conventions.md` that a *window-shaped* thing opens
  and closes while a *fact* is added and removed. The audit offers this branch. It was rejected because the
  rule it needs indicts the widget's own `Destroy` in the same breath, so the cost is a permanent exception
  to describe rather than a word to change.
- **`QuestDone` narrowed to success, plus a new `QuestFailed`** — what the audit's own finding proposes.
  Rejected on the project's own rule: a key that still resolves and silently stops firing for half its
  cases is exactly what a hard cut exists to prevent, and `Retired` has nothing to key on.
- **`QuestChanged`, with `q:status()` carrying the outcome.** The audit's other branch. It moves the bug
  rather than killing it: the handler still has to check a field, and the name no longer even hints that
  something finished.
- **Renaming the addon's `Update` to `Frame`** instead of the widget's `Tick` to `Update`. Defensible —
  `Load`/`Update`/`Disable` reads as a lifecycle trio and a frame is not lifecycle. Rejected on blast
  radius: `Update` is the key every addon already uses, it carries a retirement row from 041, and moving
  the surface's word touches one key set, one fire site and four pages.
- **Renaming `SessionEnteredWorld`.** The audit flags it as the only key that is a sentence and proposes
  nothing. It is not one of the three edges, and no shorter name says "the HUD exists now".
- **Renaming `LuaSelectorWatch.APPEAR`/`DISAPPEAR` and leaving it there.** They are renamed to
  `ADDED`/`REMOVED` because a constant that decodes `"Added"` while being called `APPEAR` is the same drift
  one level down.
- **Leaving the event-key check out of `tools/docverbs.py`.** The checkers held docs to src for *verbs*
  only, and a subscription names its key with a string — so a page still writing `QuestDone` would read
  perfectly and fail only when someone ran it. That is precisely the rot this feature creates the
  opportunity for, so the guard ships with it.
