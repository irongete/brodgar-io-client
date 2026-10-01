# Data Types: The Fight

The snapshot shapes off the manoeuvre-deck builder and the fight in progress: one manoeuvre, one card in the deck, the deck's totals, an opponent, an opening, and a combat action. Each is what `:info()` copies out of a live object on [`session:fight`](../fight.md). The model is on [the catalogue](README.md).

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

From [`opponent:info()`](../fight.md#an-opponent). `{ id = number, ip = { mine = number, theirs = number }?, give = { mine = boolean, theirs = boolean }?, last = string? }`. `ip`, `give` and `last` are absent once the fight with that opponent has ended, and the snapshot is then `{ id }`; `last` is absent before their first manoeuvre too. Everything about the creature itself is read off its [Gob](../gob.md).

## Opening

From [`opening:info()`](../fight.md#an-opening). `session:fight():opening():list()`, `opponent:opening():list()` and the opening events hand live [`Opening` objects](../fight.md#an-opening), not this table.

| Field | Type | Notes |
|---|---|---|
| `res`, `name` | `string` | Resource plus display name. Optional. |
| `amount` | `number` | `0..1` fraction, the live `opening:amount()`. Content-defined, often absent. |
| `remaining` | `number` | `0..1` fraction of the run left, the live `opening:remaining()`. Content-defined, often absent, not seconds. |
| `number` | `number` | Integer overlay. Content-defined, often absent. |

## CombatAction

From [`action:info()`](../fight.md#a-combat-action), `nil` for an empty place. `{ res = string?, name = string?, cooldown = number }`: `cooldown` is the `0..1` fraction of its own cooldown still to run, not seconds, `0` when the action can be used.

---

## See Also

- [The catalogue](README.md) — every snapshot shape, and what a snapshot is.
- [`session:fight`](../fight.md) — the live objects these copy, and the deck's own verbs.
- [Gob](../gob.md) — everything about the creature you are fighting.
