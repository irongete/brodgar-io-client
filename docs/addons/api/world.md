# session:world: World Subsystem

Query loaded game objects, inspect terrain tiles, convert between world and screen coordinates, and perform protected world interactions.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

local world = session:world()

-- Find all nearby animals within 30 tiles
local nearby_animals = world:gob():within(30, function(game_object)
  local resource_name = game_object:name() or ""
  return resource_name:find("gfx/arch/animals") ~= nil
end)

for _, animal_gob in ipairs(nearby_animals) do
  local position = animal_gob:position()
  hafen.log():write(string.format("Animal at X=%.1f, Y=%.1f", position:x(), position:y()))
end
```

---

## Object Queries (`session:world():gob()`)

All queries measure distance relative to the active character.

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:get(gob_id)` | `number` | `Gob` | Returns a `Gob` handle for the given ID (never `nil`; use `:exists()` to verify presence). |
| `:list(filter?)` | `[string \| function]` | `Gob[]` | Array of all loaded objects matching the filter. |
| `:count(filter?)` | `[string \| function]` | `number` | Total number of loaded objects matching the filter. |
| `:find(filter)` | `string \| function` | `Gob \| nil` | First object matching the filter, or `nil`. |
| `:nearest(filter?)` | `[string \| function]` | `Gob \| nil` | The single closest matching object to this character. |
| `:within(radius, filter?)` | `number, [string \| function]` | `Gob[]` | All matching objects within `radius` world units. |

---

## Terrain & Coordinates

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:id()` | None | `string \| nil` | Server world genus ID (unique per server world). |
| `:terrain(position)` | `Position` | `TerrainTile \| nil` | Returns the ground tile under the given position. |
| `:worldToScreen(position)`| `Position` | `{x, y} \| nil` | Projects a 3D world coordinate to 2D screen design pixels. |
| `:screenToWorld(x, y)` | `number, number` | `Position \| nil` | Projects screen pixels back onto the terrain surface. |

---

## Protected Actions

These actions simulate player gestures and require permissions in `manifest.json`.

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `:click(game_object, [btn], [mods])` | `Gob, [number], [number]` | `gob.click` | Clicks or interacts with an object in the world. |
| `:place(position, angle, [btn], [mods])`| `Position, number, [number], [number]` | `world.place` | Deploys a building or construction site. |
| `:select(pos_a, pos_b)` | `Position, Position` | `world.select` | Drags an area selection on the ground. |
