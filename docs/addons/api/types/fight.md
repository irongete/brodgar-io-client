# Data Types: The Fight

The snapshot shapes off the manoeuvre-deck builder and the fight in progress: one manoeuvre, one card in the deck, the deck's totals, and an opponent. Each is what `:info()` copies out of a live object on [`session:fight`](../fight.md). The model is on [the catalogue](README.md).

```lua
local summary = hafen.session():current():fight():summary()
local snapshot = summary and summary:info()
if snapshot then hafen.log():write(snapshot.used .. "/" .. snapshot.maxact .. " action points") end
```

---

## Maneuver, DeckCard, FightSummary

| Shape | Fields |
|---|---|
| `Maneuver` | `{ res?, name?, avail = number, used = number }`: `avail` dealable (`maneuver:dealable()`) against `used` dealt. |
| `DeckCard` | `{ slot = number, key = string?, res?, name?, used? }`. `slot` is the raw 0-based deck index, `card:wire()`. `card:index()` is the 1-based position. `key` is the hotkey label such as `"1"` or `"⇧1"`, absent past the labels the window paints. The manoeuvre half is absent for an empty slot, where the place itself still reads. |
| `FightSummary` | `{ maxact, used, nact, nsave, usesave }` in the window's spelling. The live reads are `summary:maxActions()`, `:used()`, `:deckSize()`, `:saveCount()`, `:activeSave()`. |

## Opponent

From [`opponent:info()`](../fight.md#an-opponent). `{ id = number, ip = { mine = number, theirs = number }?, give = { mine = boolean, theirs = boolean }? }`. `ip` and `give` are absent once the fight with that opponent has ended, and the snapshot is then `{ id }`. Everything about the creature itself is read off its [Gob](../gob.md).

---

## See Also

- [The catalogue](README.md) — every snapshot shape, and what a snapshot is.
- [`session:fight`](../fight.md) — the live objects these copy, and the deck's own verbs.
- [Gob](../gob.md) — everything about the creature you are fighting.
