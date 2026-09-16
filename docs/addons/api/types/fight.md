# Data types: the fight

The snapshot shapes off the maneuver-deck builder: one maneuver, one card in the deck, and the deck's own
totals. Each is what `:info()` copies out of a live object, so it never updates — the live reads are verbs
on that object. The model is on [the catalogue](README.md).

## Maneuver, DeckCard, FightSummary

From the `:info()` escape hatch on each of [`session:fight`](../fight.md)'s objects; the reads themselves hand
you the live objects.

- **Maneuver** — `{ res?, name?, avail = number, used = number }`, `avail` dealable against `used`
  dealt. The live reads are `man:res()`, `:name()`, `:dealable()` and `:used()`.
- **DeckCard** — `{ slot = number, key = string?, res?, name?, used? }`, `slot` the raw 0-based deck
  index, which `card:wire()` reads — `card:index()` is the 1-based position — and `key` the hotkey
  label such as `"1"` or `"⇧1"`, absent for a slot past the labels the window paints. The maneuver half
  is absent for an empty slot, where the place itself still reads.
- **FightSummary** — `{ maxact, used, nact, nsave, usesave }`, in the window's own spelling; the live
  reads spell them out as `sum:maxActions()`, `:used()`, `:deckSize()`, `:saveCount()` and
  `:activeSave()`.

The combat target has no shape of its own: `target:info()` is `{ id }`, and everything else about the
creature is read off its [Gob](../gob.md).

## See also

- [the catalogue](README.md) — every snapshot shape, and what a snapshot is
- [`session:fight`](../fight.md) — the live objects these three copy, and the deck's own verbs
- [Gob](../gob.md) — everything about the creature you are fighting
