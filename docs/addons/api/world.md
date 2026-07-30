# hafen.world — enumerate game objects

Scan every game object currently loaded. These hand out live [Gob](gob.md) **objects** — read them with
methods, and they stay fresh for as long as you keep them. All take the canonical
[filter](conventions.md#the-filter-argument) (nil / name substring / predicate); a predicate receives a
**Gob**.

`nearest` and `within` measure distance from the player and skip the player's own gob.

| Function | Returns | Description |
|---|---|---|
| `hafen.world.gobs([filter])` | [Gob](gob.md)`[]` | all matching objects |
| `hafen.world.count([filter])` | number | how many match |
| `hafen.world.nearest([filter])` | [Gob](gob.md) \| nil | the closest match to the player |
| `hafen.world.within(radius, [filter])` | [Gob](gob.md)`[]` | matches within `radius` world units of the player |

```lua
local trees = hafen.world.count("tree")

local prey = hafen.world.nearest(function(g)
  local n = g:name()
  return n and n:find("rabbit")
end)
if prey then
  local p = prey:pos()
  hafen.log(string.format("nearest rabbit at %.0f,%.0f", p.x, p.y))
end

for _, g in ipairs(hafen.world.within(50, "gfx/borka/body")) do
  -- players within 50 units; g is a live Gob
end
```

Because Gobs are interned per addon, you can use them as table keys directly — `seen[g] = true`
de-dupes across repeated sweeps without touching ids. See [Identity](gob.md#identity).

> For reacting to objects rather than polling, prefer the `GobAdded`/`GobRemoved`
> [events](events.md#world) over scanning every frame.
