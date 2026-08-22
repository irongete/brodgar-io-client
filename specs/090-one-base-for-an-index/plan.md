# 090 — One base for an index: plan

## Approach

Two rows, one rule, four verbs. `:index()` becomes the 1-based position, `:wire()` is added for the
raw number, and every numeric key takes what `:index()` answers.

**It cannot be caught by `Retired`.** The name stays and the value changes, which is the reshape half
of `CLAUDE.md`'s hard-cut rule: nothing to key on. The teaching had to go somewhere reachable, so it
went into the one call an addon written before this makes — `:get(0)`, now out of range, refusing with
the change rather than with the range.

**`meter:index()` did not move.** It was already the position, and a meter has no wire number: its
index *is* its HUD slot. Adding `:wire()` there would have invented a number.

## Files

`LuaSlot` (`index`, new `wire`, `getMember`, the hint), `LuaSpeed` (`index`, new `wire`, `getMember`,
`demand`, the snapshot, the hint), `LuaDeckCard` (`index`, new `wire`, the hint), `CharApi` (the
`:deck()` refusal's stale words).

Pages: `actionbar.md`, `references.md`, `speed.md`, `fight.md`, `event/bus.md`, `types.md`.

## Gotchas found while doing it

**An em-dash in a Java string is a character, not an escape.** A pattern written as the escape
sequence does not match source holding the character. Two replacements failed on it before the file
was read as bytes.

**`s:speed():set` had to move with `:get`.** `demand(x)` accepted `0..3` and would have disagreed with
a 1-based `:get`, so the write half moved in the same pass.

**`LuaMeter` has no `getMember`** — no numeric key, nothing to change. Checked rather than assumed,
because A-071 names it as the verb that was already right.

## Discarded alternatives

- **Making `:index()` always the raw wire number**, the finding's second option, with a
  `conventions.md` line saying a list position is `i` in your own loop. `:index()` is read far more
  often than it is passed back, so that rule makes the more common read the more dangerous one — the
  finding's own reason for preferring the first.
- **Leaving `s:speed():get(0..3)` alone**, since A-072 names only the action bar. It would have moved
  the trap rather than removed it: a 1-based `sp:index()` beside a 0-based `s:speed():get` is exactly
  the defect, one namespace over.
- **Keeping the Speed snapshot's `index` as the wire number.** A snapshot field named `index` meaning
  something other than `:index()` is the same collision inside one object.
- **Moving DeckCard's snapshot field `slot` to the position.** It is the client's own word for the raw
  number; a snapshot keeps the client's spelling, and `card:wire()` is its live read.
- **A `Retired` row for `slot:index`** — there is no old *name* to key on, and a row that fired on the
  live verb would refuse the very thing the feature ships.
