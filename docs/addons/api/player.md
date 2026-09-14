# session:player: Player Character

Access character entity state, inventory grids, hand cursor items, and character movement.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local player = session:player()
local player_gob = player:gob()

if player_gob then
  local position = player_gob:position()
  hafen.log():write(string.format("Player standing at (%.1f, %.1f)", position:x(), position:y()))
end

-- Inspect item on the mouse cursor (hand)
local held_item = player:hand():item()
if held_item then
  hafen.log():write("Cursor holding item: " .. (held_item:name() or "Unknown"))
end
```

---

## Read Methods

| Method | Returns | Description |
|---|---|---|
| `:gob()` | `Gob \| nil` | The player's own Game Object in the world (`nil` before entering world). |
| `:hand()` | `Hand` | The mouse cursor hand subsystem. |
| `:inventory()` | `Widget \| nil` | The player's main backpack inventory widget. |
| `:equipment()` | `Widget \| nil` | The player's worn equipment window. |
| `:action()` | `string \| nil` | The current action string or progress bar status. |

---

## Cursor Hand Subsystem (`player:hand()`)

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:item()` | None | `Item \| nil` | `-` | The item currently held on the cursor. |
| `:use(target_gob)` | `Gob` | `self` | `player.hand.use` | Uses the held item on a target game object. |

---

## Protected Movement

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:move(target_position)` | `Position` | `self` | `player.move` | Commands the character to walk towards `target_position`. |
| `:stop()` | None | `self` | `player.move` | Stops character movement. |
