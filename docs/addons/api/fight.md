# hafen.fight — combat schools

Read the out-of-combat maneuver-deck builder (the Martial Arts & Combat Schools tab). Read-only.

| Function | Returns | Description |
|---|---|---|
| `hafen.fight.maneuvers([filter])` | [`Maneuver`](types.md#maneuver--deckcard--fightsummary)`[]` | every known maneuver/attack, matching the [filter](conventions.md#the-filter-argument) |
| `hafen.fight.deck()` | [`DeckCard`](types.md#maneuver--deckcard--fightsummary)`[]` | the current school's card layout (filled hotkey slots) |
| `hafen.fight.summary()` | [`FightSummary`](types.md#maneuver--deckcard--fightsummary) \| nil | action-point budget and active saved-school slot |

```lua
for _, c in ipairs(hafen.fight.deck()) do
  hafen.log(c.key .. ": " .. (c.name or c.res))
end

local s = hafen.fight.summary()
if s then hafen.log("used " .. s.used .. "/" .. s.maxact .. " action points") end
```

> This is the configuration editor, distinct from a live in-combat view. A `Maneuver`'s `avail`/`used`
> are how many you can slot / have slotted; a `DeckCard`'s `slot` is the raw 0-based deck index and
> `key` its hotkey label. There is no `FightChanged` event — read on demand.
