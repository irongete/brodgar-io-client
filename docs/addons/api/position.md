# Position: A Place in the World

A Position is the one place type in the API: a point you can do arithmetic on and a value you can save. Every spatial verb takes it (`gob:position()`, `session:player():move(position)`, `session:world():tile(position)`). You get one from a [world](world.md) you name, or off anything that stands in it.

```lua
local session = hafen.session():current()
local position = session:player():gob():position()
local south = position:offset(0, 22)                         -- a new Position two tiles south
hafen.log():write(string.format("x=%s tile=%s durable=%s",
  tostring(position:x()), tostring(position:tileCoord().x), tostring(position:durable())))
hafen.store():var("spot").home = position                    -- saved and reloaded as a Position, no conversion
```

---

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `position:x()`, `position:y()` | `number \| nil` | Unprotected | Session world components. |
| `position:offset(dx, dy)` | `Position \| nil` | Unprotected | A new Position `dx` east and `dy` south, in world units. |
| `position:distance(other)` | `number \| nil` | Unprotected | World distance to another Position. `other` defaults to that place's own character. |
| `position:tileCoord()` | `{x, y} \| nil` | Unprotected | The session tile coordinate it sits in. |
| `position:durable()` | `boolean` | Unprotected | Whether it has a durable form. |
| `position:info()` | `{gridId, x, y} \| nil` | Unprotected | The durable form: a grid id, and the offset within that grid. |
| `session:world():position(x, y)` | Position | Unprotected | Build one from that session's world components. |
| `session:world():position(saved)` | Position | Unprotected | Build one from the `{gridId, x, y}` table `:info()` gives. |

| Rule | Detail |
|---|---|
| `nil`, not raise | A read answers `nil` where it has no answer. What raises is a bad argument: `position:offset(dx, dy)` takes two numbers, `position:distance(other)` another Position, each refusing anything else naming the verb and the parameter. A numeric string is [still a string](conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number): `position:offset("1", 2)` is refused. |
| A durable Position carries no session | A grid id is the server's naming of a patch of ground, so an anchored place is answerable in whichever session you ask. The anchor is worked out where the reader knows its session (`gob:position()` uses the character that read it). The grid id travels. |
| A place with no anchor keeps its login | A world coordinate is relative to where that session logged in. Its own verbs (`:x()`, `:y()`, `:tileCoord()`, `:offset()`, a bare `:distance()`) answer in that login whichever character is on screen. Handed to a verb addressed at a different character it raises: `hafen.map():marker():add: this place was read in another login; anchor it (position.md) to carry it across`. |
| Anchor it and it crosses | Ground the character has walked is anchored already. The refusal is met only where `position:durable()` is `false`. Walk that ground, or read the place from a character that has. |
| `:info()` and `:x()`/`:y()` are different numbers | `:x()`/`:y()` are session world components. `:info()` is a grid id plus the offset inside that grid, `0` to `1100`. |
| The arithmetic is the client's | A grid is 100 tiles across and a tile 11 world units, so a grid is 1100 units wide. `position:offset(dx, dy)` crosses the boundary and re-derives the grid: `position:offset(0, 1100)` names a different `gridId`, not a number past the end of this one. |
| Durability is explored, not loaded | The grid id comes from the terrain streamed around the character when loaded, otherwise from what the client recorded. It is durable anywhere that character has been, failing only on ground never visited. A non-durable Position cannot be saved: [`hafen.json`](json.md) refuses it and the store writes nothing. |
| Recorded in another part of the world | No coordinate in that session. `:info()` and `:durable()` answer. `:x()`, `:y()` and `:tileCoord()` answer `nil`. Verbs acting on the world refuse it naming the character it is out of reach for. Another character may reach it. |
| Off streamed ground, a proved base | The client keeps per session where that session's coordinates sit in the map database and checks the base against a live grid's id every frame. When the server re-bases mid-play (a cave, a house) the base names the ground just left for a moment. In that moment a place off the streamed terrain answers `nil`, as does one asked of a character whose base cannot be proved. Streamed ground answers throughout: read off the terrain, no base needed. |
| Equality | Two Positions are never equal unless the same object. Compare what they name: `:info()` for the durable form, `:tileCoord()` for the tile, `:distance()` for nearness. |

## Saving a position

A Position goes into [`hafen.store`](store/README.md) as-is and comes back as a Position. [`hafen.json`](json.md) writes its durable form and reads it back as a Position, so a place survives a file, a message or another player.

```lua
hafen.store():var("spot").home = hafen.session():current():player():gob():position()

-- next session
local home = hafen.store():var("spot").home
local session = hafen.session():current()
if home and home:x() then session:player():move(home) end
```

| Rule | Detail |
|---|---|
| No global position | A Position saves as a grid id plus an offset. That is the one anchor that means the same to another character and another player, because it comes from the server. The id crosses as [a decimal string](shapes.md#coordinates). Why a raw coordinate cannot be saved is in [coordinates](shapes.md#coordinates). |
| The recorded map | `hafen.map():grid():get(position:info().gridId)` hands back the same [`Grid`](map/grids.md#the-grid-object) the live query does, streamed in or not. [Storing a place](map/grids.md#storing-a-place) says why a marker's segment coordinates are not this shape. |

## What is not a Position

| Value | Detail |
|---|---|
| A lattice cell | An index with its own name: `grid:segmentCoord()` counts grids, `marker:segmentTile()` counts tiles, the argument of `grid:tile(cell)` is a within-grid tile coordinate `0..99`. |
| Screen pixels | A widget's `:position()`, `:rootPos()` and [`worldToScreen`](world.md#the-screen-and-the-world) answer a plain `{x, y}` of [design pixels](ui/pixels.md). Handing one to `session:player():move()` raises. |

## The place's login, and the addressed twins

The verbs on a Position resolve in the login the place belongs to. For an unanchored place that is the one it was read in. For an anchored one it is the character on screen. With two logins, the same questions asked about a named character are [`session:world():components(position)`](world.md#terrain-and-coordinates), `session:world():tileCoord(position)` and `session:world():distance(position [, other])`. `position:offset(dx, dy)` needs no twin: arithmetic in world units in the place's own login, answering a Position in the same one.

---

## See Also

- [`session:world`](world.md) — where a Position is built, the terrain reads that take one, and the addressed twins.
- [Gob](gob.md) — `gob:position()`, the commonest way to get one.
- [`session:player`](player.md#write-protected) — walking a character to one.
- [`hafen.store`](store/README.md) — saving one, as-is.
- [`hafen.map`](map/README.md) — the recorded map a durable form also reaches.
- [Coordinates](shapes.md#coordinates) — the spaces, side by side.
