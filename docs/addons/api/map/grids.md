# hafen.map: segments and grids

The database is made of **segments** — one contiguous explored area, what the map window draws as one
map — each made of **grids**, the 100×100-tile squares the server hands out. Reach for these to walk the
ground the client wrote down, and to ask what it says about a place.

| Call | Returns | Description |
|---|---|---|
| `hafen.map():segment():current()` | [`Segment`](#the-segment-object) \| nil | the segment the character **on screen** is standing in; `nil` until the map has streamed in |
| `hafen.map():segment():get(id)` | [`Segment`](#the-segment-object) \| nil | one segment by its id; `nil` if the database has no such segment |
| `hafen.map():segment():list()` | `Segment[]` | every segment [the database](README.md#one-map-for-the-client) holds, in id order |
| `hafen.map():grid():get(gridId)` | [`Grid`](#the-grid-object) \| nil | one grid by the **server's** grid id; `nil` if the database never recorded it |

`hafen.map():grid()` does not enumerate, and says so rather than answering an empty list: the database
holds every grid you have ever walked over. Address one by id, or walk a rectangle of one segment.

> **A 64-bit id is a decimal string, and a number is refused.** Segment and grid ids do not survive a
> Lua number, so `hafen.map():segment():get(1234)` is an error rather than a lookup of some neighbouring
> segment. Pass `seg:id()` / `grid:id()` and the `gridId` out of a stored position — all of them strings.

## One Grid, two doors

A grid is **one entity** whichever half of the world you reach it from. The live world publishes the
server's grid id and the database keys on the same number, so
[`s:world():grid():at(p)`](../world.md#terrain-and-coordinates) — read on the
[session](../session.md) whose character you mean — and `hafen.map():grid():get(id)` hand back the *same
object*, and each door answers `nil` for what its own half does not have.

| Method | Asks |
|---|---|
| `grid:live()` | is it streamed in right now? |
| `grid:exists()` | has the client written it down? |

Ground under your feet the client has not saved yet is `:live()` true and `:exists()` false, and every
recorded read on it is `nil`. Ground you explored last year is the mirror. That is also why `grid:tile(c)`
and [`s:world():tile(p)`](../world.md#terrain-and-coordinates) agree where both answer: they are two
reads of one thing rather than two subsystems you have to reconcile.

## The Segment object

| Method | Returns | Description |
|---|---|---|
| `seg:id()` | string | the segment id, a 64-bit value as a decimal string — the identity |
| `seg:exists()` | bool | does the database still carry it? (a merge can fold one into another) |
| `seg:grid():get(sc)` | [`Grid`](#the-grid-object) \| nil | the grid at segment grid coord `{x, y}` — `nil` for no grid there *and* for one still loading |
| `seg:grid():list(area)` | `Grid[]` | every **loaded** grid in `{x, y, w, h}` of segment grid coords |
| `seg:markers(filter)` | [`Marker`](markers.md#the-marker-object)`[]` | the markers recorded in this segment |
| `seg:info()` | table | `{ id, current, markers }` — the snapshot escape hatch |

**You ask a segment for an area, never for a list.** There is no "every grid in this segment", and
`seg:grid():count()` refuses rather than pretending: the client's own minimap does not enumerate either —
it walks the grid coords of the rectangle it is drawing, and so do you. `seg:grid():list` walks at most
**1024** grid coords per call and refuses a bigger rectangle rather than reading a thousand files behind
your back.

```lua
local s = hafen.session():current()                   -- the character on screen
local seg = hafen.map():segment():current()
local here = s:world():grid():at(s:player():gob():position())
local sc = here:segmentCoord()
for _, g in ipairs(seg:grid():list{ x = sc.x - 2, y = sc.y - 2, w = 5, h = 5 }) do
  local at = g:position()                            -- a Position: durable, and locatable here
  if at:x() then draw(g, at) end
end
```

## The Grid object

| Method | Returns | Description |
|---|---|---|
| `grid:id()` | string | the **server's** grid id — the identity, and what a stored position anchors on |
| `grid:live()` | bool | is this grid streamed in right now? |
| `grid:exists()` | bool | does the database carry this grid? |
| `grid:segmentCoord()` | `{x, y}` \| nil | its coord inside its segment |
| `grid:position()` | [Position](../position.md) \| nil | its upper-left corner |
| `grid:segment()` | [`Segment`](#the-segment-object) \| nil | the segment it belongs to |
| `grid:tile(c)` | `{name, prio}` \| nil | the recorded tile at within-grid tile coord `{x, y}`, `0..99` |
| `grid:height(c)` | number \| nil | the recorded height there |
| `grid:modified()` | number \| nil | when the client last recorded this grid, in milliseconds |
| `grid:overlay()` | [mask collection](overlays.md#the-recorded-masks) | which claims and provinces covered it |
| `grid:image(level)` | image \| nil | its [minimap drawing](drawings.md) at zoom `level` (default `0`) |
| `grid:overlayImage(tag)` | image \| nil | one recorded [overlay mask](drawings.md) drawn in its own colour |
| `grid:info()` | table | `{ id, seg, sc, pos?, live, mtime?, loaded, size }` — the snapshot escape hatch |

`grid:tile` gives you the tileset **resource name**, not a tile id: the live
[`s:world():tile`](../world.md#terrain-and-coordinates) `id` is a number one session made up, so the name is
the thing the two halves can be compared on — and they agree.

```lua
local s  = hafen.session():current()                 -- the character on screen
local p  = s:player():gob():position()
local gp = p:info()                                  -- where it stands, in the durable form
local g  = hafen.map():grid():get(gp.gridId)
local c  = { x = math.floor(gp.x / 11), y = math.floor(gp.y / 11) }
print(g:tile(c).name, s:world():tile(p).name)        -- the same tileset
```

A within-grid tile coord is `0..99`; anything else is refused rather than read as a segment coord.

> **What you read is what the client wrote down, not what is there.** For the ground under a character of
> yours it is current — the client re-records the grids around each of them as they move — and for somewhere
> explored a year ago it is a year old. `grid:modified()` is the honest answer to "how old is this".

## Storing a place

**Store the Position, never the segment coordinate.** A segment id is bookkeeping this client invented,
and when two explored areas turn out to touch, the merge **rewrites** the loser's grid coords and every
marker inside it. A stored `seg` + tile coord would not go `nil` after that — it would point at the *wrong
place*, which is worse. A [Position](../position.md) anchors on a grid id, which comes from
the server, means the same thing to every player, and no merge ever moves.

| Read it as | From | Then |
|---|---|---|
| a Position | `gob:position()`, `marker:position()`, `grid:position()` | **store this, send this** |
| segment id + lattice coord | `seg:id()`, `marker:segmentTile()`, `grid:segmentCoord()` | look at it, compare it this session, never store it |

`hafen.store` and `hafen.json` marshal a Position by themselves, so there is nothing to convert; the
`{gridId, x, y}` table `p:info()` hands out is for a shape that has to leave the client some other way,
and [`s:world():position(saved)`](../position.md) brings it back.

## See also

- [the map database](README.md) — the `nil`-until-loaded rule these reads follow, and interning
- [`session:world`](../world.md) — the live half, and the other door onto this same Grid
- [drawings](drawings.md) — what `grid:image` hands back
- [overlays](overlays.md) — what `grid:overlay()` hands back
- [coordinates](../conventions.md#coordinates) — the coordinate spaces, and which one you may store
