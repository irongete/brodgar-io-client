# session:fight: Combat Schools & Targets

Inspect combat schools, known maneuvers, hotkey combat cards, action point budgets, and active fight targets.

```lua
local session = hafen.session():current()
if not session then return end

local combat = session:fight()

-- Check active opponent target
local active_target = combat:target()
if active_target and active_target:exists() then
  local target_gob = active_target:gob()
  local target_name = target_gob and target_gob:name() or "Opponent"
  hafen.log():write(string.format("Fighting %s (Gob ID %d)", target_name, active_target:id()))
end

-- Inspect loaded combat deck cards
for _, combat_card in ipairs(combat:deck():list()) do
  hafen.log():write(string.format("Slot %s: %s", combat_card:key() or "?", combat_card:name() or combat_card:res() or "Empty"))
end

-- Check action point budget
local summary = combat:summary()
if summary then
  hafen.log():write(string.format("Action points: %d / %d", summary:used() or 0, summary:maxActions() or 0))
end
```

---

## Methods on `session:fight()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:target()` | None | `Opponent \| nil` | The creature currently targeted in combat (`nil` if not fighting). |
| `:deck()` | None | `DeckCollection` | The loaded combat school hotkey layout. |
| `:maneuver()` | None | `ManeuverCollection` | All martial arts maneuvers unlocked by this character. |
| `:summary()` | None | `FightSummary \| nil` | Action point budgets and school slot totals (`nil` if tab is closed). |

---

## Methods on `DeckCollection` (`session:fight():deck()`)

Collections have no `:get` verb. Gaps are omitted from `:list()`.

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:list(filter?)` | `[string \| function]` | `DeckCard[]` | Array of filled hotkey slots in the loaded school. |
| `:count(filter?)` | `[string \| function]` | `number` | Total number of filled hotkey slots. |
| `:find(filter)` | `string \| function` | `DeckCard \| nil` | First card matching maneuver resource or display name. |

---

## Methods on `DeckCard`

| Method | Returns | Description |
|---|---|---|
| `:index()` | `number \| nil` | 1-based position in `:deck():list()` (`nil` if emptied). |
| `:wire()` | `number` | Raw 0-based deck slot index. Always answers. |
| `:key()` | `string \| nil` | Hotkey trigger label assigned to this card slot (`"1"`, `"⇧1"`). |
| `:maneuver()` | `Maneuver \| nil` | Maneuver dealt into this slot. |
| `:res()` | `string \| nil` | Maneuver resource path. |
| `:name()` | `string \| nil` | Display name of the maneuver. |
| `:used()` | `number \| nil` | Number of copies the loaded deck holds. |
| `:exists()` | `boolean` | `true` if this card slot is currently populated. |
| `:info()` | `table \| nil` | Plain table snapshot. See [`DeckCard`](types/fight.md#maneuver-deckcard-fightsummary). |

---

## Methods on `FightSummary`

| Method | Returns | Description |
|---|---|---|
| `:used()` | `number \| nil` | Action points spent by the loaded school. |
| `:maxActions()` | `number \| nil` | Total action-point budget available. |
| `:deckSize()` | `number \| nil` | Number of hotkey slots in the deck. |
| `:saveCount()` | `number \| nil` | Number of saved school slots available. |
| `:activeSave()` | `number \| nil` | Active school slot index (0-based). |
| `:exists()` | `boolean` | `true` if the combat window remains open. |
| `:info()` | `table \| nil` | Plain table snapshot. See [`FightSummary`](types/fight.md#maneuver-deckcard-fightsummary). |

---

## Methods on `Maneuver`

| Method | Returns | Description |
|---|---|---|
| `:res()` | `string \| nil` | Resource path identifying the maneuver. |
| `:name()` | `string \| nil` | Display name of the maneuver. |
| `:dealable()` | `number` | Maximum copies that may be dealt into a deck. |
| `:used()` | `number` | Copies currently dealt in the loaded school. |
| `:exists()` | `boolean` | `true` if the character still knows this maneuver. |
| `:info()` | `table \| nil` | Plain table snapshot. See [`Maneuver`](types/fight.md#maneuver-deckcard-fightsummary). |

---

## Methods on `Opponent`

| Method | Returns | Description |
|---|---|---|
| `:id()` | `number` | Server entity ID of the creature being fought. |
| `:gob()` | `Gob` | Game object handle of the opponent entity. |
| `:exists()` | `boolean` | `true` if this combat target is still being fought. |
| `:info()` | `table \| nil` | Plain table snapshot `{ id }`. |

---

## See Also

- [`session:actionbar`](actionbar.md) — Writable action bar slots.
- [Combat Type Snapshots](types/fight.md) — Schemas for `Maneuver`, `DeckCard`, and `FightSummary`.
