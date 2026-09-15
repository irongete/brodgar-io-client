# session:player: Player Character

Access character entity state, cursor hand items, and character movement.

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
local player_hand = player:hand()
local held_item = player_hand and player_hand:item()
if held_item then
  hafen.log():write("Cursor holding item: " .. (held_item:name() or "Unknown"))
end
```

---

## Methods on `session:player()`

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:gob()` | None | `Gob \| nil` | Unprotected | The player's own character `Gob` in the world (`nil` before entering world). |
| `:hand()` | None | `Hand \| nil` | Unprotected | The cursor hand object carrying a held item, or `nil` if the cursor is empty. |
| `:move(target_position)` | `Position` | `self` | `player.move` | Commands the character to walk towards `target_position`. Chains. |

---

<a id="the-hand"></a>
## Methods on `Hand` (`player:hand()`)

| Method | Parameters | Returns | Permission | Description |
|---|---|---|---|---|
| `:item()` | None | `Item \| nil` | Unprotected | The item currently held on the cursor. |
| `:use(target, mods?)` | `Gob \| Item \| Position, [number]` | `self` | `player.hand.use` | Applies the held item to a target entity, item, or ground position. |
| `:info()` | None | `table` | Unprotected | Snapshot `{ item }`. |
