# hafen.fight: combat schools

Read the out-of-combat maneuver-deck builder, the Martial Arts and Combat Schools tab. This is the
configuration editor, not a live in-combat view: there is nothing here about a fight in progress.

```lua
for _, c in ipairs(hafen.fight.deck()) do
  hafen.log():write(c.key .. ": " .. (c.name or c.res))
end

local s = hafen.fight.summary()
if s then hafen.log():write("used " .. s.used .. "/" .. s.maxact .. " action points") end
```

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.fight.maneuvers(filter)` | [`Maneuver`](types.md#maneuver-deckcard-fightsummary)`[]` | every known maneuver and attack matching the [filter](conventions.md#the-filter-argument) |
| `hafen.fight.deck()` | [`DeckCard`](types.md#maneuver-deckcard-fightsummary)`[]` | the current school's card layout: the filled hotkey slots |
| `hafen.fight.summary()` | [`FightSummary`](types.md#maneuver-deckcard-fightsummary) \| nil | the action-point budget and the active saved-school slot |

The array readers answer empty and `summary` answers `nil` until the tab has built. Nothing throws and
nothing is gated; there is no write side, and no `FightChanged` event — read on demand.

A `Maneuver`'s `avail` and `used` are how many you can slot and have slotted. A `DeckCard`'s `slot` is
the raw 0-based deck index and its `key` the hotkey label.

## See also

- [types](types.md#maneuver-deckcard-fightsummary) — `Maneuver`, `DeckCard` and `FightSummary`
- [`hafen.actionbar`](actionbar.md) — the other hotkey surface, which is writable
- [`hafen.char`](char.md) — the skills that unlock maneuvers
