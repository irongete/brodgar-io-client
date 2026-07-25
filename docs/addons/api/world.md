# hafen.world — enumerate game objects

Scan every game object currently loaded. These return [`Gob`](types.md#gob) **snapshots**
(point-in-time copies); to read one object live, use [`hafen.gob`](gob.md). All take the canonical
[filter](conventions.md#the-filter-argument) (nil / name substring / predicate).

`nearest` and `within` measure distance from the player and skip the player's own gob.

| Function | Returns | Description |
|---|---|---|
| `hafen.world.gobs([filter])` | [`Gob`](types.md#gob)`[]` | all matching objects |
| `hafen.world.count([filter])` | number | how many match |
| `hafen.world.nearest([filter])` | [`Gob`](types.md#gob) \| nil | the closest match to the player |
| `hafen.world.within(radius, [filter])` | [`Gob`](types.md#gob)`[]` | matches within `radius` world units of the player |

```lua
local trees = hafen.world.count("tree")

local prey = hafen.world.nearest(function(g)
  return g.name and g.name:find("rabbit")
end)
if prey then hafen.log("nearest rabbit at " .. prey.x .. "," .. prey.y) end

for _, g in ipairs(hafen.world.within(50, "gfx/borka/body")) do
  -- players within 50 units
end
```

> For reacting to objects rather than polling, prefer the `GobAdded`/`GobRemoved`
> [events](events.md#world) over scanning every frame.
