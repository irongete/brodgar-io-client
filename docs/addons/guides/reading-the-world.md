# Reading the World

Everything around your character is a game object: a tree, a boulder, an animal, a player's body. Reading them is unprotected: no permission, no declaration. This guide finds objects, reads one, and asks what the ground under them is.

```lua
local session = hafen.session():current()                                -- the character on screen
local tree_count = session:world():gob():count("terobjs/tree")            -- how many, by name
local nearest_tree = session:world():gob():nearest("terobjs/tree")        -- the closest one, or nil
local players = session:world():gob():within(50, function(gob)            -- matching, in a radius
  return gob:player()
end)
```

---

## Find the objects you want

A world belongs to a character: [`hafen.session():current()`](../api/session.md) is the one on screen, `hafen.session():get(user)` any other. [`session:world()`](../api/world.md) scans what that character has loaded, and every verb takes the same [filter](../api/conventions.md#the-filter-argument): nothing, a substring of the resource name, or a predicate. A name is a resource path, not a display name: `"gfx/borka/body"` is any player body and `"terobjs/tree"` every kind of tree. When you do not know the name, log what is around you:

```lua
hafen.console():on("what", function()
  for _, gob in ipairs(hafen.session():current():world():gob():within(15)) do
    hafen.log():write(gob:name() or "?")
  end
end)
```

## Read one

A [Gob](../api/gob.md) is a live object holding an id and re-resolving on every call. One kept in a variable tracks its object as it moves, and answers `nil` once the object is gone.

```lua
local tree = hafen.session():current():world():gob():nearest("terobjs/tree")
if tree and tree:exists() then
  local position = tree:position()
  local distance = math.floor(tree:distance() * 10 + 0.5) / 10                -- one decimal by hand: %.1f prints the raw double
  hafen.log():write(("tree at %d, %d, %s away"):format(position:x(), position:y(), distance))
end
```

Two Gobs for one object are the same value, so `==` compares them. A table keyed by one remembers what you have seen without ids. Two of your characters looking at one tree find the same Gob ([identity](../api/gob.md#identity)).

## Do not scan every frame

A sweep of every loaded object is cheap once and expensive sixty times a second. Prefer [`GobAdded` and `GobRemoved`](../api/event/bus/world.md#world) and keep your own index. When you must poll a value with no event, poll on a [timer](events-and-timers.md), not in `Update`.

```lua
local boars = {}
hafen.event():on("GobAdded", function(gob)
  if (gob:name() or ""):find("boar") then boars[gob] = true end
end)
hafen.event():on("GobRemoved", function(gob)
  boars[gob] = nil                     -- the gob is already gone here: only :id() answers
end)
```

`boars` counts boars, not viewings: an object fires once whichever character sees it first, and is reported gone when the last loses sight of it.

## The character itself

[`session:player()`](../api/player.md) is the anchor. Everything positional about the character is read on its own Gob.

```lua
local session = hafen.session():current()
local my_gob = session:player():gob()              -- nil until that session is in the world
if my_gob then
  local position = my_gob:position()
  hafen.log():write(("standing at %d, %d"):format(position:x(), position:y()))
end
```

`gob == session:player():gob()` tells "is this the character I am reading" without ids, for gobs read through that session.

## The ground

[`session:world()`](../api/world.md#terrain-and-coordinates) answers for terrain at a world point and converts between world units, tiles, grids and screen pixels. Terrain reads answer `nil` while that part of the map streams in.

```lua
local session = hafen.session():current()
local position = session:player():gob():position()
local tile = session:world():tile(position)
hafen.log():write(tile and (tile.name or tile.id) or "not loaded yet")
```

> **World coordinates do not hold.** The client re-bases them whenever the server drops the map: every login, a walk into a cave. They mean nothing to another player. A place is a [Position](../api/position.md), anchored to a map grid. It goes into [`hafen.store`](../api/store/README.md) and comes back the same place.

## The map you explored

Everything above is the world streamed around that character. The ground already explored is on disk and outlives the session: [`hafen.map`](../api/map/README.md). It holds segments and grids, the claims and provinces that covered them, your markers and the corner minimap's drawings. A [Position](../api/position.md) is the value the two halves share.

```lua
local session = hafen.session():current()
local durable = session:player():gob():position():info()     -- where it is, as {gridId, x, y}
local grid = hafen.map():grid():get(durable.gridId)          -- the same Grid, from the recorded side
local tile = grid and grid:tile({ x = 0, y = 0 })            -- nil until the grid is read off the disk
hafen.log():write(tile and tile.name or "not loaded yet, ask again next tick")
```

A read needing a grid not yet loaded off the disk starts the load and answers `nil`. Call again next tick. No callback, no ready event: a minimap panel is a few-times-a-second timer that is both the retry and the "did the picture change" test.

## What the client does not know

A gob's name is its type, so there is no display name for an arbitrary player. `gob:player()` is the test, and [`gob:kin()`](../api/kin.md) names the ones on that character's roster. Reading tells you what the client has been told: an object outside your view has not been loaded and does not exist to your addon.

**Next:** [events and timers](events-and-timers.md) — when your code runs, and what to hang it off.
