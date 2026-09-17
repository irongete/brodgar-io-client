# Data Types: The Fight

The snapshot shapes off the maneuver-deck builder: one maneuver, one card in the deck, and the deck's totals. Each is what `:info()` copies out of a live object on [`session:fight`](../fight.md); the model is on [the catalogue](README.md).

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
| `DeckCard` | `{ slot = number, key = string?, res?, name?, used? }`: `slot` the raw 0-based deck index (`card:wire()`; `card:index()` is the 1-based position), `key` the hotkey label such as `"1"` or `"⇧1"`, absent past the labels the window paints. The maneuver half is absent for an empty slot, where the place itself still reads. |
| `FightSummary` | `{ maxact, used, nact, nsave, usesave }` in the window's spelling; the live reads are `summary:maxActions()`, `:used()`, `:deckSize()`, `:saveCount()`, `:activeSave()`. |

The combat target has no shape of its own: `target:info()` is `{ id }`, and everything else about the creature is read off its [Gob](../gob.md).

---

## See Also

- [The catalogue](README.md) — every snapshot shape, and what a snapshot is.
- [`session:fight`](../fight.md) — the live objects these copy, and the deck's own verbs.
- [Gob](../gob.md) — everything about the creature you are fighting.
