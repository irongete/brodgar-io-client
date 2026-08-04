# hafen.world: the live world

Everything the client has loaded **right now**: the game objects around you, the terrain under them, and
the coordinate spaces they live in. Reach for it to find the gobs you want, to ask what is under a point,
to turn a screen pixel into a ground position, or to build a place you can save. What a single object
answers is the [Gob](gob.md) it hands you.

```lua
local prey = hafen.world():gob():nearest(function(g)
  local n = g:name()
  return n and n:find("rabbit")
end)
if prey then
  local p = prey:position()
  hafen.log():write(string.format("nearest rabbit at %.0f,%.0f", p:x(), p:y()))
end
```

> **Live, not recorded.** Everything on this page reads the world as it is streamed around you: it is
> `nil` off-stream and gone at logout. The map you have *explored* — segments, grids, your markers, the
> minimap icons — is on disk and persistent, and that is [`hafen.map`](map/README.md).

## Objects

`hafen.world():gob()` is the collection of loaded game objects, and the only way to address one. It is
read-only: you can look, you cannot add or remove. Every verb hands out live [Gob](gob.md) **objects**,
not snapshots, and they stay fresh for as long as you keep them.

`:list`, `:count`, `:find`, `:nearest` and `:within` take the canonical
[filter](conventions.md#the-filter-argument) — `nil`, a name substring, or a predicate — and a predicate
receives a **Gob**. `nearest` and `within` measure from the player and skip the player's own gob. Before
you are in the world nothing is loaded: the array verbs answer empty, `count` answers `0`, and
`nearest`/`find` answer `nil`. None of them throws.

| Call | Returns | Description |
|---|---|---|
| `hafen.world():gob():get(id)` | [Gob](gob.md) | the object with that id — **never `nil`** |
| `hafen.world():gob():list(filter)` | [Gob](gob.md)`[]` | every matching object |
| `hafen.world():gob():count(filter)` | number | how many match |
| `hafen.world():gob():find(filter)` | [Gob](gob.md) \| nil | the first match, in load order |
| `hafen.world():gob():nearest(filter)` | [Gob](gob.md) \| nil | the closest match to the player |
| `hafen.world():gob():within(radius, filter)` | [Gob](gob.md)`[]` | matches within `radius` world units of the player |

```lua
for _, g in ipairs(hafen.world():gob():within(50, "gfx/borka/body")) do
  -- players within 50 units; g is a live Gob
end
```

**`:get(id)` never answers `nil`**, even for an id that is not loaded or never existed. That is what lets
you anchor to a gob before it streams in; `:exists()` is the liveness test. Living under *the live world*
invites the opposite reading, so it is worth saying plainly: the section a verb lives under says where the
thing is, not whether it is there.

Because Gobs are interned per addon you can use them as table keys directly — `seen[g] = true` de-dupes
across repeated sweeps without touching ids. See [identity](gob.md#identity).

> For reacting to objects rather than polling, prefer the `GobAdded` and `GobRemoved`
> [events](event.md#world) over scanning every frame.

## The Position type

A **Position** is a place. It is the one position type in the API, and every spatial verb takes one:
`gob:position()`, `hafen.act():moveTo(p)`, `hafen.world():tile(p)`. It exists because a place has to be
two things at once — a point you can do arithmetic on, and something you can save — and a plain `{x, y}`
table can only ever be one of them.

```lua
local p = hafen.player():gob():position()

p:x()  p:y()                            -- session world components, when you need numbers
p:offset(0, 22)                         -- a NEW Position two tiles south
p:distance(other)                       -- world distance; with no argument, to the player
p:tileCoord()                           -- the tile it sits in, {x, y}
p:durable()                             -- can it be saved?
p:info()                                -- {gridId, x, y} — the durable form

hafen.store.spot.home = p               -- saved and reloaded as a Position: no conversion, either way
```

| Method | Returns | Description |
|---|---|---|
| `p:x()` `p:y()` | number \| nil | session world components |
| `p:offset(dx, dy)` | Position \| nil | a new Position `dx` east and `dy` south, in world units |
| `p:distance(other)` | number \| nil | world distance to another Position; defaults to the player |
| `p:tileCoord()` | `{x, y}` \| nil | the session tile coord it sits in |
| `p:durable()` | bool | whether it has a durable form |
| `p:info()` | `{gridId, x, y}` \| nil | the durable form: a grid id, and the offset **within** that grid |

You build one with `hafen.world():position(x, y)` from session world components, or
`hafen.world():position(saved)` from the `{gridId, x, y}` table `:info()` gives you.

> **`:info()` and `:x()`/`:y()` are not the same numbers.** `:x()`/`:y()` are session world components;
> `:info()` carries a grid id plus the offset *inside that grid*, `0` to `1100`.

**The arithmetic belongs to the engine, and that is the point.** A grid is 100 tiles across and a tile is
11 world units, so a grid is **1100 units** wide. Adding to a grid-relative number is right until it is
not: past the edge the honest answer is a different grid. `p:offset(dx, dy)` crosses the boundary and
re-derives the grid, which is what lets one Position be computable *and* durable.

```lua
local edge = p:offset(0, 1100)
edge:info().gridId ~= p:info().gridId    -- a different grid, not a number past the end of this one
```

**Durability is explored, not loaded.** A Position gets its grid id from the terrain streamed around you
if that ground is loaded, and otherwise from what the client wrote down about it. So a Position is durable
**anywhere you have been**, whether or not it is on screen, and fails only on ground you have never
visited — which is also ground you cannot act on. `:durable()` reports it, and a position that is not
durable cannot be saved: [`hafen.json`](json.md) refuses it, and the store writes nothing for it.

Going the other way has its own boundary: a Position rebuilt from a place recorded in a *different* part
of the world has no coordinate in this session at all. It keeps its durable form — `:info()` and
`:durable()` answer — while `:x()`, `:y()`, `:offset()` and `:tileCoord()` answer `nil`, and the verbs
that act on the world refuse it saying so.

**Two Positions are never equal** unless they are literally the same object. A Position is a value, so
compare what it *names*: `:info()` for the durable form, `:tileCoord()` for the tile, `:distance()` for
nearness.

### Saving a position

A Position goes into [`hafen.store`](store.md) as-is and comes back as a Position — no conversion in
either direction. [`hafen.json`](json.md) writes it as its durable form and reads that form back as a
Position, so a place survives a file, a message, or another player.

```lua
hafen.store.spot.home = hafen.player():gob():position()   -- "spot" is a saved variable this addon declared

-- next session
local home = hafen.store.spot.home
if home and home:x() then hafen.act():moveTo(home) end
```

> **There is no global position, and grid ids are strings.** Raw world coordinates are session-local: they
> reset each login and are not comparable across players, which is why a Position saves as a grid id plus
> an offset. A grid id is a 64-bit number and Lua numbers are doubles, so it appears as an exact decimal
> **string** — the only form safe to store and compare. It is also the only anchor that means the same
> thing to another player: a grid id comes from the **server**.

A durable form also reaches the **recorded** map: `hafen.map.grid(p:info().gridId)` finds what the client
wrote down about that ground, whether or not it is streamed in right now. See
[saving a position](map/grids.md#saving-a-position) for why a marker's own `seg` + `tc` is not that shape.

### What is not a Position

A **lattice cell** is an index, not a place, and keeps its own name: `grid:sc()` counts grids,
`marker:tc()` counts tiles, and the argument of `grid:tile(c)` is a within-grid tile coord `0..99`.

**Screen pixels are not Positions** either. A widget's `:pos()`, `:rootpos()` and
[`worldToScreen`](player.md) answer plain `{x, y}` **pixels**. A screen point has no durable form because
the screen is not a place — and handing one to `hafen.act():moveTo()` raises, rather than walking you
somewhere wrong.

## Terrain and coordinates

Terrain reads take a Position and return `nil` when the map for that spot has not streamed in yet; the
lattice conversions are pure arithmetic and always answer. Nothing here is gated, and nothing throws on a
point that is simply off-map.

```lua
local p = hafen.player():gob():position()
local t = hafen.world():tile(p)
if t then hafen.log():write("standing on " .. (t.name or t.id)) end
```

| Call | Returns | Description |
|---|---|---|
| `hafen.world():position(x, y)` | Position | a place from session world components |
| `hafen.world():position(saved)` | Position | a place rebuilt from a `{gridId, x, y}` table |
| `hafen.world():tile(p)` | [`Tile`](types.md#tile) \| nil | tileset id and resource name at a Position |
| `hafen.world():height(p)` | number \| nil | terrain height there |
| `hafen.world():grid():at(p)` | `{id, gc}` \| nil | the map grid covering a Position: `id` is the stable global grid id (a string), `gc` the session-local grid coord `{x, y}` |
| `hafen.world():grid():list()` | `{id, gc}[]` | every grid streamed in right now |
| `hafen.world():tileToWorld(tx, ty)` | `{x, y}` | tile coord to world, at its upper-left corner |
| `hafen.world():tileToGrid(tx, ty)` | `{x, y}` | tile coord to grid coord |
| `hafen.world():screenToWorld(sx, sy, fn)` | nothing, calls `fn` | raycast the ground under a screen pixel; asynchronous |
| `hafen.world():snapPlace(p, fine)` | Position | snap a Position to the client's placement grid |
| `hafen.world():snapAngle(a, fine)` | number | snap a facing in radians to the client's placement-angle grid |

The two placement *settings* — how many sub-tile divisions, how many rotation steps — are read and written
through [`hafen.client:options():interface()`](client/README.md#interface): `posGran()` and `angGran()`.

## Screen to world, and placement snapping

These are the inverse of [`hafen.player():worldToScreen`](player.md) plus the client's own placement
snapper — the primitives a [ghost](ghost.md) gizmo, or any drag-on-the-ground tool, is built from.

**`screenToWorld` is asynchronous.** It reads the true terrain point from the GPU, the same pass the
client uses to place a building, so the answer cannot come back inline: it arrives a frame later
through `fn`, as a Position.

```lua
hafen.world():screenToWorld(sx, sy, function(p)
  if p then hafen.log():write(("ground under cursor: %.1f, %.1f"):format(p:x(), p:y())) end
  -- p is nil if the pixel hit no terrain (sky, or off-map)
end)
```

`(sx, sy)` are game-window pixels, the space `worldToScreen` returns. During a drag, feed it the cursor
coords from [`hafen.hook():grab`](hook.md#hafenhookgrabmove-up) and coalesce — issue the next raycast only
after the previous `fn` fired — so at most one is in flight per frame.

**`snapPlace(p, fine)`** snaps a Position exactly as placing a building does, honouring the live
placement-grid setting: without `fine`, the tile centre; with `fine = true`, the sub-tile grid
(`posGran()` divisions, or free when that is `0`). It is the engine's own snapper, so a ghost dropped
through it lands where a real building would.

```lua
local s = hafen.world():snapPlace(p, mods.shift)   -- SHIFT picks the fine grid
ghost:move(s:x(), s:y())
```

**`snapAngle(a, fine)`** is the rotation counterpart: without `fine`, 45° steps; with `fine = true`, the
finer placement-angle grid (`angGran()` steps). It honours the live setting just as `snapPlace` does, so a
ghost rotate feels identical to rotating a real building. The result is normalized to `(-π, π]`.

```lua
local a = hafen.world():snapAngle(math.atan2(p:y() - c.y, p:x() - c.x), mods.shift)
ghost:rotate(a)
```

## See also

- [Gob](gob.md) — what the object readers hand back, and the world coordinates they share
- [`hafen.map`](map/README.md) — the recorded map: its segments and grids, your markers, the icon categories
- [the `filter` argument](conventions.md#the-filter-argument) — the three forms the object readers accept
- [events](event.md#world) — `GobAdded` and `GobRemoved`
- [`hafen.ghost`](ghost.md) — what placement snapping is usually for
- [`hafen.store`](store.md) — where a Position is saved
- [coordinates](conventions.md#coordinates) — the spaces, side by side
