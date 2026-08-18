# hafen.fight: combat schools and the fight you are in

Read the maneuver-deck builder — the Martial Arts and Combat Schools tab — and who you are currently
fighting. The deck side is the configuration editor: what you know, what the loaded school has dealt to
each hotkey, and what it costs.

```lua
for _, card in ipairs(hafen.fight():deck()) do
  hafen.log():write(card:key() .. ": " .. (card:name() or card:res()))
end

local s = hafen.fight():summary()
if s then hafen.log():write("used " .. s:used() .. "/" .. s:maxActions() .. " action points") end
```

## Read

| Call | Returns | Description |
|---|---|---|
| `hafen.fight():maneuver():list(filter)` | `Maneuver[]` | every maneuver and attack you know |
| `hafen.fight():maneuver():count(filter)` | number | how many match |
| `hafen.fight():maneuver():find(filter)` | `Maneuver` \| nil | the first that matches |
| `hafen.fight():deck()` | `DeckCard[]` | the loaded school's layout: the filled hotkey slots |
| `hafen.fight():summary()` | `FightSummary` \| nil | the action-point budget and the saved-school slots |
| `hafen.fight():target()` | `Opponent` \| nil | who you are fighting |

`:maneuver():list()` and `:deck()` answer empty and `:summary()` answers `nil` until the tab has built;
`:target()` is `nil` whenever you are not in a fight. Nothing throws and nothing is protected; there is no
write side, and no combat event — read on demand.

A string [filter](conventions.md#the-filter-argument) over the maneuvers matches the resource name **and**
the display name. **There is no `:get`**: a maneuver is addressed by nothing you have, so a string is a
*search* and a position is `:list()[n]`.

The deck is a **plain array**, not a collection: it is a layout, ordered by hotkey, and there is nothing
to search it by that the maneuvers do not already answer. Empty slots are left out — each card carries
its own `:slot()` and `:key()`, so the gap is never ambiguous.

## A maneuver

| Method | Returns | Description |
|---|---|---|
| `man:res()` | string \| nil | the maneuver's resource name, its identity |
| `man:name()` | string \| nil | the display name |
| `man:available()` | number | how many copies of it you may deal into a deck |
| `man:used()` | number | how many the loaded school has dealt |
| `man:exists()` | boolean | whether you still know it — always answers |
| `man:info()` | [`Maneuver`](types.md#maneuver-deckcard-fightsummary) \| nil | a plain-table **snapshot** |

## A deck card

A card is a **place** in the layout, not the maneuver in it. It keeps answering `:slot()` and `:key()`
when the hotkey is emptied, while the maneuver half goes `nil` and `:exists()` goes `false`.

| Method | Returns | Description |
|---|---|---|
| `card:slot()` | number | the raw 0-based deck index — always answers |
| `card:key()` | string | the hotkey label the window paints — always answers |
| `card:maneuver()` | `Maneuver` \| nil | the maneuver dealt here |
| `card:res()` | string \| nil | that maneuver's resource name |
| `card:name()` | string \| nil | that maneuver's display name |
| `card:used()` | number \| nil | how many copies the deck holds |
| `card:exists()` | boolean | whether the slot is filled — always answers |
| `card:info()` | [`DeckCard`](types.md#maneuver-deckcard-fightsummary) \| nil | a plain-table **snapshot** |

## The summary

| Method | Returns | Description |
|---|---|---|
| `sum:used()` | number \| nil | action points the loaded school spends |
| `sum:maxActions()` | number \| nil | the action-point budget it spends them against |
| `sum:deckSize()` | number \| nil | how many hotkey slots the deck has |
| `sum:saveCount()` | number \| nil | how many saved-school slots you keep |
| `sum:activeSave()` | number \| nil | which of them is loaded, 0-based |
| `sum:exists()` | boolean | whether the tab is still the one this was read from |
| `sum:info()` | [`FightSummary`](types.md#maneuver-deckcard-fightsummary) \| nil | a plain-table **snapshot** |

`sum:used()` is the same total the window paints beside the cap, and it is the sum of `man:used()` over
every maneuver you know.

## The target

| Method | Returns | Description |
|---|---|---|
| `target:id()` | number | the creature's gob id — always answers |
| `target:gob()` | [Gob](gob.md) | the creature itself — never `nil` |
| `target:exists()` | boolean | whether you are still fighting them — always answers |
| `target:info()` | `{ id }` \| nil | a plain-table **snapshot** |

> The target says **who**, and nothing else. Everything readable about the creature — its name, its
> health, where it is — belongs to the gob and is read there. There is no per-moment combat state here:
> the client's own fight numbers are drawn from state it is not asked to publish.

`target:gob()` is never `nil`, exactly like [`s:world():gob():get(id)`](gob.md) — ask
`target:gob():exists()` rather than testing for `nil`. The target is interned on the gob id, so
`hafen.fight():target() == hafen.fight():target()` and `seen[target] = true` work, and a stashed one goes
`:exists() == false` when the fight ends.

## See also

- [Gob](gob.md) — what `target:gob()` hands back, and every read on it
- [types](types.md#maneuver-deckcard-fightsummary) — the snapshot shapes `:info()` returns
- [`session:actionbar`](actionbar.md) — the other hotkey surface, which is writable
- [`session:char`](char.md) — the skills that unlock maneuvers
