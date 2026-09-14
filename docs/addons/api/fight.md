# session:fight: Combat Schools & Targets

Inspect combat schools, known maneuvers, hotkey combat cards, and active fight targets.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local combat_subsystem = session:fight()

-- Check active opponent target
local active_target = combat_subsystem:target()
if active_target then
  local target_gob = active_target:gob()
  local target_name = target_gob and target_gob:name() or "Unknown Opponent"
  hafen.log():write("Currently engaging in combat with: " .. target_name)
end

-- Inspect loaded combat deck cards
for _, combat_card in ipairs(combat_subsystem:deck():list()) do
  hafen.log():write(string.format("Slot %s: %s", combat_card:key() or "?", combat_card:name() or "Empty"))
end
```

---

## Methods on `session:fight()`

| Method | Returns | Description |
|---|---|---|
| `:target()` | `Opponent \| nil` | The opponent currently targeted in combat (`nil` if not in combat). |
| `:deck()` | `DeckCollection` | Currently loaded combat school slots and assigned cards. |
| `:maneuver()` | `ManeuverCollection` | All martial arts maneuvers and attacks unlocked by this character. |
| `:summary()` | `FightSummary \| nil` | Action point budgets and school slot totals. |

---

## Methods on `Opponent`

| Method | Returns | Description |
|---|---|---|
| `:gob()` | `Gob \| nil` | Game Object handle of the opponent entity. |
| `:id()` | `number` | Server entity ID of the opponent. |
| `:info()` | `table` | Plain table snapshot `{ id, gob }`. |

---

## Methods on `DeckCard`

| Method | Returns | Description |
|---|---|---|
| `:key()` | `string \| nil` | Hotkey trigger label assigned to this card slot. |
| `:name()` | `string \| nil` | Display name of the maneuver dealt into this slot. |
| `:maneuver()` | `Maneuver \| nil`| Maneuver handle for the card. |
| `:exists()` | `boolean` | `true` if this card slot is populated. |
