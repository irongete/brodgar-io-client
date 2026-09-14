# Reading the World

Everything in the game environment—trees, rocks, animals, buildings, and players—is a **Game Object (Gob)**. Reading world data does not require any special permissions.

## 1. Finding Game Objects

All world queries originate from a [`Session`](../api/session.md). The currently active character is accessed via `hafen.session():current()`.

```lua
local session = hafen.session():current()
if not session then return end

local world = session:world()

-- Count objects matching a resource substring
local tree_count = world:gob():count("terobjs/tree")

-- Find the single closest object
local nearest_tree = world:gob():nearest("terobjs/tree")

-- Find all objects matching a predicate within a radius (in tiles)
local nearby_players = world:gob():within(50, function(game_object)
  return game_object:player()
end)
```

### Resource Names vs Display Names
Objects are identified by engine resource paths (e.g. `"gfx/borka/body"` for characters or `"terobjs/tree"` for trees). String filters perform a substring match.

To inspect the resource names of objects currently around you:

```lua
hafen.console():on("scan_nearby", function()
  local session = hafen.session():current()
  if not session then return end

  for _, game_object in ipairs(session:world():gob():within(20)) do
    local resource_name = game_object:name() or "unnamed"
    hafen.log():write("Found object: " .. resource_name)
  end
end)
```

## 2. Inspecting a Game Object

A `Gob` handle re-resolves dynamically. If an object moves, its handle reflects the new location; if it despawns, methods return `nil`:

```lua
local session = hafen.session():current()
local nearest_animal = session and session:world():gob():nearest("gfx/arch/animals")

if nearest_animal and nearest_animal:exists() then
  local position = nearest_animal:position()
  local distance = nearest_animal:distance()
  local resource_name = nearest_animal:name() or "Unknown"

  hafen.log():write(string.format(
    "%s located at (%.1f, %.1f), distance: %.1f tiles",
    resource_name, position:x(), position:y(), distance
  ))
end
```

## 3. Tracking Objects Efficiently with Events

Avoid scanning all loaded objects every frame. Instead, subscribe to the [`GobAdded`](../api/event/bus/world.md) and `GobRemoved` events to maintain an index:

```lua
local tracked_boars = {}

hafen.event():on("GobAdded", function(game_object)
  local resource_name = game_object:name() or ""
  if resource_name:find("boar") then
    tracked_boars[game_object:id()] = game_object
    hafen.log():write("Boar appeared: ID " .. game_object:id())
  end
end)

hafen.event():on("GobRemoved", function(game_object)
  if tracked_boars[game_object:id()] then
    tracked_boars[game_object:id()] = nil
    hafen.log():write("Boar despawned: ID " .. game_object:id())
  end
end)
```

## 4. Inspecting the Player Character

Access your own character's `Gob` via `session:player():gob()`:

```lua
local session = hafen.session():current()
local player_gob = session and session:player():gob()

if player_gob then
  local position = player_gob:position()
  hafen.log():write(string.format("Current player position: X=%.1f, Y=%.1f", position:x(), position:y()))
end
```

## 5. Terrain & Map Coordinates

Use [`session:world()`](../api/world.md) to query terrain types and convert between coordinate spaces:

```lua
local session = hafen.session():current()
if not session then return end

local player_position = session:player():gob():position()
local terrain_tile = session:world():terrain(player_position)

if terrain_tile then
  hafen.log():write("Standing on terrain: " .. terrain_tile:name())
end
```
