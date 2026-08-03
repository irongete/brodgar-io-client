# hafen.world: enumerating game objects

Scan every game object the client currently has loaded. Reach for it to find the gobs you want; to
read or act on one you already have, use [`hafen.gob`](gob.md).

```lua
local prey = hafen.world.nearest(function(g)
  local n = g:name()
  return n and n:find("rabbit")
end)
if prey then
  local p = prey:pos()
  hafen.log(string.format("nearest rabbit at %.0f,%.0f", p.x, p.y))
end
```

These hand out live [Gob](gob.md) **objects**, not snapshots: read them with methods, and they stay
fresh for as long as you keep them. All four take the canonical
[filter](conventions.md#the-filter-argument) — `nil`, a name substring, or a predicate — and a
predicate receives a **Gob**.

## Read

`nearest` and `within` measure distance from the player and skip the player's own gob. Before you are
in the world there is nothing loaded: `gobs` and `within` return empty arrays, `count` returns `0` and
`nearest` returns `nil`. None of them throws.

| Function | Returns | Description |
|---|---|---|
| `hafen.world.gobs(filter)` | [Gob](gob.md)`[]` | every matching object |
| `hafen.world.count(filter)` | number | how many match |
| `hafen.world.nearest(filter)` | [Gob](gob.md) \| nil | the closest match to the player |
| `hafen.world.within(radius, filter)` | [Gob](gob.md)`[]` | matches within `radius` world units of the player |

```lua
for _, g in ipairs(hafen.world.within(50, "gfx/borka/body")) do
  -- players within 50 units; g is a live Gob
end
```

Because Gobs are interned per addon you can use them as table keys directly — `seen[g] = true` de-dupes
across repeated sweeps without touching ids. See [identity](gob.md#identity).

> For reacting to objects rather than polling, prefer the `GobAdded` and `GobRemoved`
> [events](events.md#world) over scanning every frame.

## See also

- [`hafen.gob`](gob.md) — what every function here hands back
- [the `filter` argument](conventions.md#the-filter-argument) — the three forms all four accept
- [events](events.md#world) — `GobAdded` and `GobRemoved`
- [`hafen.map`](map.md) — the terrain those objects stand on
