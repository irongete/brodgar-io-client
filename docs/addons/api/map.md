# hafen.map: terrain and coordinates

Read the terrain and convert between coordinate spaces. Reach for it to ask what is under a point, to
turn a screen pixel into a ground position, or to save a place so it still means something next login.

```lua
local p = hafen.player():gob():pos()
local t = hafen.map.tile(p.x, p.y)
if t then hafen.log("standing on " .. (t.name or t.id)) end
```

Positional arguments are **world units**, the same space [`gob:pos()`](gob.md) returns. Terrain reads
return `nil` when the map for that spot has not streamed in yet; the conversions are pure arithmetic
and always answer. Nothing on this page is gated, and nothing throws on a coordinate that is simply
off-map.

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.map.tile(x, y)` | [`Tile`](types.md#tile) \| nil | tileset id and resource name at a world point |
| `hafen.map.height(x, y)` | number \| nil | terrain height at a world point |
| `hafen.map.grid(x, y)` | `{id, gc}` \| nil | the map grid at a point: `id` is the stable global grid id (a string), `gc` the session-local grid coord `{x, y}` |
| `hafen.map.gridPos(x, y)` | `{gridId, x, y}` \| nil | the **shareable, persistent** position; with no arguments, the player's |
| `hafen.map.fromGridPos(anchor)` | `{x, y}` \| nil | the inverse of `gridPos`: a saved anchor becomes a world coord in this session, or `nil` if that grid is not loaded |
| `hafen.map.worldToTile(x, y)` | `{x, y}` | world to tile coord, flooring |
| `hafen.map.tileToWorld(tx, ty)` | `{x, y}` | tile coord to world, at its upper-left corner |
| `hafen.map.tileToGrid(tx, ty)` | `{x, y}` | tile coord to grid coord |
| `hafen.map.screenToWorld(sx, sy, fn)` | nothing, calls `fn` | raycast the ground under a screen pixel; asynchronous |
| `hafen.map.snapPlace(x, y, fine)` | `{x, y}` | snap a world coord to the client's placement grid |
| `hafen.map.placeGrid()` | number | the current placement-grid setting: sub-tile divisions, `0` for free |
| `hafen.map.snapAngle(a, fine)` | number | snap a facing in radians to the client's placement-angle grid |
| `hafen.map.placeAngle()` | number | the current placement-angle setting: the fine rotation divisions |

## Screen to world, and placement snapping

These are the inverse of [`hafen.player():worldToScreen`](player.md) plus the client's own placement
snapper — the primitives a [ghost](ghost.md) gizmo, or any drag-on-the-ground tool, is built from.

**`screenToWorld` is asynchronous.** It reads the true terrain point from the GPU, the same pass the
client uses to place a building, so the answer cannot come back inline: it arrives a frame later
through `fn`.

```lua
hafen.map.screenToWorld(sx, sy, function(w)
  if w then hafen.log(("ground under cursor: %.1f, %.1f"):format(w.x, w.y)) end
  -- w is nil if the pixel hit no terrain (sky, or off-map)
end)
```

`(sx, sy)` are game-window pixels, the space `worldToScreen` returns. During a drag, feed it the cursor
coords from [`hafen.hook.grab`](hooks.md#hafenhookgrab) and coalesce — issue the next raycast only
after the previous `fn` fired — so at most one is in flight per frame.

**`snapPlace(x, y, fine)`** snaps a world coord exactly as placing a building does, honouring the live
placement-grid setting: without `fine`, the tile centre; with `fine = true`, the sub-tile grid
(`placeGrid()` divisions, or free when that is `0`). It is the engine's own snapper, so a ghost dropped
through it lands where a real building would.

```lua
local s = hafen.map.snapPlace(w.x, w.y, mods.shift)   -- SHIFT picks the fine grid
ghost:move(s.x, s.y)
```

**`snapAngle(a, fine)`** is the rotation counterpart: without `fine`, 45° steps; with `fine = true`,
the finer placement-angle grid (`placeAngle()` divisions). It honours the live setting just as
`snapPlace` does, so a ghost rotate feels identical to rotating a real building. The result is
normalized to `(-π, π]`.

```lua
local a = hafen.map.snapAngle(math.atan2(w.y - c.y, w.x - c.x), mods.shift)
ghost:rotate(a)
```

## Saving a world position across sessions

Raw world coords reset each login, so persist a position as a **grid anchor** from `gridPos` and
re-resolve it with `fromGridPos` on load. `fromGridPos` takes the exact table `gridPos` returns, so the
round trip needs no reshaping.

```lua
-- save a stable anchor, not raw x,y ("spot" is a saved variable this addon declared)
hafen.store.spot.anchor = hafen.map.gridPos(wx, wy)   -- {gridId, x, y}

-- load, next session: back to a world coord in THIS session
local a = hafen.store.spot.anchor
local w = a and hafen.map.fromGridPos(a)              -- {x, y}, or nil if not streamed in
if w then hafen.ghost.new{ res = "…", x = w.x, y = w.y } end
```

`fromGridPos` returns `nil` until the anchored grid is loaded, and a grid loads when you are near where
it was saved — so re-resolve at `OnEnterWorld` and retry for a few seconds as the map streams in. The
`planner` example addon does exactly this for a whole layout of [ghosts](ghost.md).

> **There is no global position, and grid ids are strings.** Raw world coordinates are session-local:
> they reset each login and are not comparable across players, so anything you save or share goes
> through `gridPos`. A grid id is a 64-bit number and Lua numbers are doubles, so it comes back as an
> exact decimal **string** — the only form safe to store and compare across sessions.

## See also

- [`hafen.gob`](gob.md) — the world coordinates every read here shares
- [`hafen.ghost`](ghost.md) — what placement snapping is usually for
- [`hafen.store`](store.md) — where a grid anchor is saved
- [coordinates](conventions.md#coordinates) — the spaces, side by side
