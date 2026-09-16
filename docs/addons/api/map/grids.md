# hafen.map: Segments and Grids

The database is made of segments (one contiguous explored area, what the map window draws as one map), each made of grids (the 100×100-tile squares the server hands out): walk the ground the client wrote down, and ask what it says about a place.

```lua
local session = hafen.session():current()                 -- the character on screen
local position = session:player():gob():position()
local durable = position:info()                           -- where it stands, in the durable form
local grid = hafen.map():grid():get(durable.gridId)
local cell = { x = math.floor(durable.x / 11), y = math.floor(durable.y / 11) }
hafen.log():write(grid:tile(cell).name .. " == " .. session:world():tile(position).name)   -- the same tileset
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.map():segment():current()` | [`Segment`](#the-segment-object) `\| nil` | Unprotected | The segment the character on screen stands in; `nil` until the map has streamed in. |
| `hafen.map():segment():get(id)` | [`Segment`](#the-segment-object) `\| nil` | Unprotected | One segment by id; `nil` if the database has none. |
| `hafen.map():segment():list()` | `Segment[]` | Unprotected | Every segment [the database](README.md) holds, in id order. |
| `hafen.map():grid():get(grid_id)` | [`Grid`](#the-grid-object) `\| nil` | Unprotected | One grid by the server's id; `nil` if never recorded. |

| Rule | Detail |
|---|---|
| `hafen.map():grid()` does not enumerate | It says so rather than answering an empty list: the database holds every grid you have walked over. Address one by id, or walk a rectangle of one segment. |
| An id is [a decimal string](../shapes.md#coordinates) | A number is refused: `hafen.map():segment():get(1234)` raises. Pass `segment:id()`, `grid:id()` or the `gridId` out of a stored position. |
| One Grid, two doors | The live world publishes the server's grid id and the database keys on the same number, so [`session:world():grid():at(position)`](../world.md#terrain-and-coordinates) and `hafen.map():grid():get(id)` hand back the same object; each door answers `nil` for what its half lacks. Ground under your feet not yet saved is `:live()` true and `:exists()` false with every recorded read `nil`; ground explored last year is the mirror. `grid:tile(cell)` and [`session:world():tile(position)`](../world.md#terrain-and-coordinates) agree where both answer. |

## The Segment object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `segment:id()` | `string` | Unprotected | The segment id, a 64-bit value as a decimal string: the identity. |
| `segment:exists()` | `boolean` | Unprotected | Whether the database still carries it (a merge can fold one into another). |
| `segment:grid():get(coordinate)` | [`Grid`](#the-grid-object) `\| nil` | Unprotected | The grid at segment grid coordinate `{x, y}`; `nil` for no grid there and for one still loading. |
| `segment:grid():list(area)` | `Grid[]` | Unprotected | Every loaded grid in `{x, y, w, h}` of segment grid coordinates; at most 1024 coordinates per call, a bigger rectangle refused. |
| `segment:markers(filter)` | [`Marker`](markers.md#the-marker-object)`[]` | Unprotected | The markers recorded in this segment. |
| `segment:info()` | `table` | Unprotected | `{ id, current, markers }`. |

You ask a segment for an area, never for a list: `segment:grid():count()` refuses. The client's own minimap walks the grid coordinates of the rectangle it draws, and so do you.

```lua
local session = hafen.session():current()
local segment = hafen.map():segment():current()
local here = session:world():grid():at(session:player():gob():position())
local centre = here:segmentCoord()
for _, grid in ipairs(segment:grid():list{ x = centre.x - 2, y = centre.y - 2, w = 5, h = 5 }) do
  local corner = grid:position()                           -- a Position: durable, and locatable here
  if corner:x() then drawGrid(grid, corner) end
end
```

## The Grid object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `grid:id()` | `string` | Unprotected | The server's grid id: the identity, what a stored position anchors on. |
| `grid:live()` | `boolean` | Unprotected | Whether it is streamed in right now. |
| `grid:exists()` | `boolean` | Unprotected | Whether the database carries it. |
| `grid:segmentCoord()` | `{x, y} \| nil` | Unprotected | Its coordinate inside its segment. |
| `grid:position()` | [Position](../position.md) `\| nil` | Unprotected | Its upper-left corner. |
| `grid:segment()` | [`Segment`](#the-segment-object) `\| nil` | Unprotected | The segment it belongs to. |
| `grid:tile(cell)` | `{name?, prio} \| nil` | Unprotected | The recorded tile at within-grid tile coordinate `{x, y}`, `0..99`; `name` absent where the recorded tileset carries none. Anything outside `0..99` is refused. |
| `grid:height(cell)` | `number \| nil` | Unprotected | The recorded height there. |
| `grid:modified()` | `number \| nil` | Unprotected | When the client last recorded this grid, in milliseconds. |
| `grid:mask()` | [mask collection](overlays.md#the-recorded-masks) | Unprotected | Which claims and provinces covered it. |
| `grid:image(level)` | image `\| nil` | Unprotected | Its [minimap drawing](drawings.md) at zoom `level` (default `0`). |
| `grid:overlayImage(tag)` | image `\| nil` | Unprotected | One recorded [mask](drawings.md) drawn in the overlay's own colour. |
| `grid:info()` | `table` | Unprotected | `{ id, seg, sc, pos?, live, mtime?, loaded, size }`; `size` is the grid's span in tiles, `{w, h}`. |

| Rule | Detail |
|---|---|
| `grid:tile` names the tileset | A resource name, not a tile id: the live [`session:world():tile`](../world.md#terrain-and-coordinates) `id` is a number one session made up, so the name is what the two halves compare on. |
| What the client wrote down | Current for the ground under a character of yours (re-recorded as they move); a year old for ground explored a year ago. `grid:modified()` says how old. |

## Storing a place

Store the Position, never the segment coordinate: a segment id is bookkeeping this client invented, and when two explored areas turn out to touch, the merge rewrites the loser's grid coordinates and every marker inside it, so a stored `seg` + tile coordinate would point at the wrong place. A [Position](../position.md) anchors on a grid id from the server, meaning the same to every player, which no merge moves.

| Read it as | From | Then |
|---|---|---|
| A Position | `gob:position()`, `marker:position()`, `grid:position()` | Store this, send this. `hafen.store` and `hafen.json` marshal it; the `{gridId, x, y}` table `position:info()` hands out is for a shape leaving the client another way, and [`session:world():position(saved)`](../position.md) brings it back. |
| Segment id + lattice coordinate | `segment:id()`, `marker:segmentTile()`, `grid:segmentCoord()` | Look at it, compare it this session, never store it. |

---

## See Also

- [The map database](README.md) — the `nil`-until-loaded rule these reads follow, and interning.
- [`session:world`](../world.md) — the live half, and the other door onto this same Grid.
- [Drawings](drawings.md) — what `grid:image` hands back.
- [Overlays](overlays.md) — what `grid:mask()` hands back.
- [Coordinates](../shapes.md#coordinates) — the coordinate spaces, and which one you may store.
