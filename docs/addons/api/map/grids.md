# hafen.map: segments and grids

The database is made of **segments** — one contiguous explored area, what the map window draws as one
map — each made of **grids**, the 100×100-tile squares the server hands out. Reach for these to walk the
ground the client wrote down, and to turn a position into something you can store.

| Call | Returns | Description |
|---|---|---|
| `hafen.map.segment()` | [`Segment`](#the-segment-object) \| nil | the segment the player is standing in; `nil` until the map has streamed in |
| `hafen.map.segment(id)` | [`Segment`](#the-segment-object) \| nil | one segment by its id; `nil` if the database has no such segment |
| `hafen.map.segments()` | `Segment[]` | every segment this character has explored, in id order |
| `hafen.map.grid(gridId)` | [`Grid`](#the-grid-object) \| nil | one grid by the **server's** grid id — the door in from an anchor |

`hafen.map.grid` is the only call that crosses from the live world into the database, because a grid id
is the only thing the two halves share: hand it the `gridId` out of a
[`gob:position():info()`](../world.md#the-position-type) and you get the recorded
ground under that spot.

> **A 64-bit id is a decimal string, and a number is refused.** Segment and grid ids do not survive a
> Lua number, so `hafen.map.segment(1234)` is an error rather than a lookup of some neighbouring
> segment. Pass `seg:id()` / `grid:id()` and the `gridId` out of an anchor — all of them strings.

## The Segment object

| Method | Returns | Description |
|---|---|---|
| `seg:id()` | string | the segment id, a 64-bit value as a decimal string — the identity |
| `seg:exists()` | bool | does the database still carry it? (a merge can fold one into another) |
| `seg:grid(sc)` | [`Grid`](#the-grid-object) \| nil | the grid at segment grid coord `{x, y}` — `nil` for no grid there *and* for one still loading |
| `seg:grids(area)` | `Grid[]` | every **loaded** grid in `{x, y, w, h}` of segment grid coords |
| `seg:markers(filter)` | [`Marker`](markers.md#the-marker-object)`[]` | the markers recorded in this segment |
| `seg:info()` | table | `{ id, current, markers }` — the snapshot escape hatch |

**You ask a segment for an area, never for a list.** There is no "every grid in this segment": the
client's own minimap does not enumerate either — it walks the grid coords of the rectangle it is
drawing, and so do you. `seg:grids` walks at most **1024** grid coords per call and refuses a bigger
rectangle rather than reading a thousand files behind your back.

```lua
local seg = hafen.map.segment()
local here = hafen.map.grid(hafen.player():gob():position():info().gridId)
local sc = here:sc()
for _, g in ipairs(seg:grids{ x = sc.x - 2, y = sc.y - 2, w = 5, h = 5 }) do
  local at = g:pos()                                 -- where to draw it, this session
  if at then draw(g, at) end
end
```

## The Grid object

| Method | Returns | Description |
|---|---|---|
| `grid:id()` | string | the **server's** grid id — the identity, and the anchor you may save |
| `grid:exists()` | bool | does the database carry this grid? |
| `grid:sc()` | `{x, y}` | its coord inside its segment |
| `grid:pos()` | `{x, y}` \| nil | its upper-left corner in **this session's** world coords; `nil` outside the player's current segment |
| `grid:segment()` | [`Segment`](#the-segment-object) | the segment it belongs to |
| `grid:tile(c)` | `{name, prio}` \| nil | the recorded tile at within-grid tile coord `{x, y}`, `0..99` |
| `grid:height(c)` | number \| nil | the recorded height there |
| `grid:mtime()` | number \| nil | when the client last recorded this grid, in milliseconds |
| `grid:image(lvl)` | image \| nil | its [minimap drawing](drawings.md) at zoom level `lvl` (default `0`) |
| `grid:overlayImage(tag)` | image \| nil | one recorded [overlay mask](drawings.md) drawn in its own colour |
| `grid:info()` | table | `{ id, seg, sc, pos?, mtime?, loaded, size }` — the snapshot escape hatch |

`grid:tile` gives you the tileset **resource name**, not a tile id: the live
[`hafen.world():tile`](../world.md#terrain-and-coordinates) `id` is a session-local number, so the name is
the thing the two halves can be compared on — and they agree.

```lua
local p  = hafen.player():gob():position()
local gp = p:info()                                  -- where the player is, anchored
local g  = hafen.map.grid(gp.gridId)
local c  = { x = math.floor(gp.x / 11), y = math.floor(gp.y / 11) }
print(g:tile(c).name, hafen.world():tile(p.x, p.y).name)   -- the same tileset
```

A within-grid tile coord is `0..99`; anything else is refused rather than read as a segment coord.

> **What you read is what the client wrote down, not what is there.** For the ground under the player
> it is current — the client re-records the grids around you as they change — and for somewhere you
> explored a year ago it is a year old. `grid:mtime()` is the honest answer to "how old is this".

## Saving a position

**Save the anchor, never the segment coordinate.** A segment id is bookkeeping this client invented,
and when two explored areas turn out to touch, the merge **rewrites** the loser's grid coords and every
marker inside it. A stored `seg` + `tc` would not go `nil` after that — it would point at the *wrong
place*, which is worse. A grid id comes from the server, means the same thing to every player, and no
merge ever moves it.

| Read it as | From | Then |
|---|---|---|
| `{gridId, x, y}` | [`p:info()`](../world.md#the-position-type), [`marker:anchor()`](markers.md#the-marker-object) | **save this, send this** |
| segment id + tile coord | `seg:id()`, `marker:tc()`, `grid:sc()` | look at it, compare it this session, never store it |

An anchor goes back to a world position with
[`hafen.world():position(saved)`](../world.md#the-position-type), and back into the
database with `hafen.map.grid(anchor.gridId)`.

## See also

- [the map database](README.md) — the `nil`-until-loaded rule these reads follow, and interning
- [`hafen.world`](../world.md) — the live half, and where a `gridId` comes from
- [drawings](drawings.md) — what `grid:image` hands back
- [overlays](overlays.md) — what `grid:overlay` hands back
- [coordinates](../conventions.md#coordinates) — the coordinate spaces, and which one you may store
