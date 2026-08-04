# hafen.world: the live world

Everything the client has loaded **right now**: the game objects around you, the terrain under them, and
the coordinate spaces they live in. Reach for it to find the gobs you want, to ask what is under a point,
to turn a screen pixel into a ground position, or to save a place so it still means something next login.
To read or act on one object you already have, use [`hafen.gob`](gob.md).

```lua
local prey = hafen.world.nearest(function(g)
  local n = g:name()
  return n and n:find("rabbit")
end)
if prey then
  local p = prey:pos()
  hafen.log():write(string.format("nearest rabbit at %.0f,%.0f", p.x, p.y))
end
```

> **Live, not recorded.** Everything on this page reads the world as it is streamed around you: it is
> `nil` off-stream and gone at logout. The map you have *explored* — segments, grids, your markers, the
> minimap icons — is on disk and persistent, and that is [`hafen.map`](map/README.md).

## Objects

These hand out live [Gob](gob.md) **objects**, not snapshots: read them with methods, and they stay fresh
for as long as you keep them. All four take the canonical
[filter](conventions.md#the-filter-argument) — `nil`, a name substring, or a predicate — and a
predicate receives a **Gob**.

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
> [events](event.md#world) over scanning every frame.

## Terrain and coordinates

Positional arguments are **world units**, the same space [`gob:pos()`](gob.md) returns; convert with
`worldToTile` / `tileToWorld` / `tileToGrid`. Terrain reads return `nil` when the map for that spot has
not streamed in yet; the conversions are pure arithmetic and always answer. Nothing here is gated, and
nothing throws on a coordinate that is simply off-map.

```lua
local p = hafen.player():gob():pos()
local t = hafen.world.tile(p.x, p.y)
if t then hafen.log():write("standing on " .. (t.name or t.id)) end
```

| Function | Returns | Description |
|---|---|---|
| `hafen.world.tile(x, y)` | [`Tile`](types.md#tile) \| nil | tileset id and resource name at a world point |
| `hafen.world.height(x, y)` | number \| nil | terrain height at a world point |
| `hafen.world.grid(x, y)` | `{id, gc}` \| nil | the map grid at a point: `id` is the stable global grid id (a string), `gc` the session-local grid coord `{x, y}` |
| `hafen.world.gridPos(x, y)` | `{gridId, x, y}` \| nil | the **shareable, persistent** position; with no arguments, the player's |
| `hafen.world.fromGridPos(anchor)` | `{x, y}` \| nil | the inverse of `gridPos`: a saved anchor becomes a world coord in this session, or `nil` if that grid is not loaded |
| `hafen.world.worldToTile(x, y)` | `{x, y}` | world to tile coord, flooring |
| `hafen.world.tileToWorld(tx, ty)` | `{x, y}` | tile coord to world, at its upper-left corner |
| `hafen.world.tileToGrid(tx, ty)` | `{x, y}` | tile coord to grid coord |
| `hafen.world.screenToWorld(sx, sy, fn)` | nothing, calls `fn` | raycast the ground under a screen pixel; asynchronous |
| `hafen.world.snapPlace(x, y, fine)` | `{x, y}` | snap a world coord to the client's placement grid |
| `hafen.world.placeGrid()` | number | the current placement-grid setting: sub-tile divisions, `0` for free |
| `hafen.world.snapAngle(a, fine)` | number | snap a facing in radians to the client's placement-angle grid |
| `hafen.world.placeAngle()` | number | the current placement-angle setting: the fine rotation divisions |

## Screen to world, and placement snapping

These are the inverse of [`hafen.player():worldToScreen`](player.md) plus the client's own placement
snapper — the primitives a [ghost](ghost.md) gizmo, or any drag-on-the-ground tool, is built from.

**`screenToWorld` is asynchronous.** It reads the true terrain point from the GPU, the same pass the
client uses to place a building, so the answer cannot come back inline: it arrives a frame later
through `fn`.

```lua
hafen.world.screenToWorld(sx, sy, function(w)
  if w then hafen.log():write(("ground under cursor: %.1f, %.1f"):format(w.x, w.y)) end
  -- w is nil if the pixel hit no terrain (sky, or off-map)
end)
```

`(sx, sy)` are game-window pixels, the space `worldToScreen` returns. During a drag, feed it the cursor
coords from [`hafen.hook():grab`](hook.md#hafenhookgrabmove-up) and coalesce — issue the next raycast only
after the previous `fn` fired — so at most one is in flight per frame.

**`snapPlace(x, y, fine)`** snaps a world coord exactly as placing a building does, honouring the live
placement-grid setting: without `fine`, the tile centre; with `fine = true`, the sub-tile grid
(`placeGrid()` divisions, or free when that is `0`). It is the engine's own snapper, so a ghost dropped
through it lands where a real building would.

```lua
local s = hafen.world.snapPlace(w.x, w.y, mods.shift)   -- SHIFT picks the fine grid
ghost:move(s.x, s.y)
```

**`snapAngle(a, fine)`** is the rotation counterpart: without `fine`, 45° steps; with `fine = true`,
the finer placement-angle grid (`placeAngle()` divisions). It honours the live setting just as
`snapPlace` does, so a ghost rotate feels identical to rotating a real building. The result is
normalized to `(-π, π]`.

```lua
local a = hafen.world.snapAngle(math.atan2(w.y - c.y, w.x - c.x), mods.shift)
ghost:rotate(a)
```

## Saving a world position across sessions

Raw world coords reset each login, so persist a position as a **grid anchor** from `gridPos` and
re-resolve it with `fromGridPos` on load. `fromGridPos` takes the exact table `gridPos` returns, so the
round trip needs no reshaping.

```lua
-- save a stable anchor, not raw x,y ("spot" is a saved variable this addon declared)
hafen.store.spot.anchor = hafen.world.gridPos(wx, wy)   -- {gridId, x, y}

-- load, next session: back to a world coord in THIS session
local a = hafen.store.spot.anchor
local w = a and hafen.world.fromGridPos(a)              -- {x, y}, or nil if not streamed in
if w then hafen.ghost.new{ res = "…", x = w.x, y = w.y } end
```

`fromGridPos` returns `nil` until the anchored grid is loaded, and a grid loads when you are near where
it was saved — so re-resolve at `OnEnterWorld` and retry for a few seconds as the map streams in. The
`planner` example addon does exactly this for a whole layout of [ghosts](ghost.md).

> **There is no global position, and grid ids are strings.** Raw world coordinates are session-local:
> they reset each login and are not comparable across players, so anything you save or share goes
> through `gridPos`. A grid id is a 64-bit number and Lua numbers are doubles, so it comes back as an
> exact decimal **string** — the only form safe to store and compare across sessions. It is also the
> only anchor that means the same thing to another player: a grid id comes from the **server**.

An anchor also reaches the **recorded** map: `hafen.map.grid(anchor.gridId)` finds what the client wrote
down about that ground, whether or not it is streamed in right now, and
[`marker:anchor()`](map/markers.md#the-marker-object) converts a map marker into the same shape. See
[saving a position](map/grids.md#saving-a-position) for why a marker's own `seg` + `tc` is not that shape.

## See also

- [`hafen.gob`](gob.md) — what the object readers hand back, and the world coordinates they share
- [`hafen.map`](map/README.md) — the recorded map: its segments and grids, your markers, the icon categories
- [the `filter` argument](conventions.md#the-filter-argument) — the three forms the object readers accept
- [events](event.md#world) — `GobAdded` and `GobRemoved`
- [`hafen.ghost`](ghost.md) — what placement snapping is usually for
- [`hafen.store`](store.md) — where a grid anchor is saved
- [coordinates](conventions.md#coordinates) — the spaces, side by side
