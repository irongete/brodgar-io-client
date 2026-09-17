# Shapes: What a Value Looks Like

The plain tables this API hands back and takes, and what the numbers inside mean. The anonymous shapes, places, colours, ids too big for a number, and units. Every table with a name of its own is in [data types](types/README.md).

```lua
local session = hafen.session():current()
local position = session:player():gob():position()
local tile = position:tileCoord()                             -- {x = …, y = …}: a place in the tile lattice
local pixel = session:world():worldToScreen(position)         -- {x = …, y = …}: a screen point, the same two keys
local grid_id = position:info().gridId                        -- a 64-bit grid id, as a decimal string
local grid = hafen.map():grid():get(grid_id)                  -- which is what the recorded map is keyed on
```

---

## The anonymous shapes

A place and a screen pixel share the same two keys. Only the verb you called tells them apart.

| Shape | What it is | Read from |
|---|---|---|
| `{x=, y=}` | A place in a lattice: a tile, a grid, a segment cell. | `position:tileCoord()`, `item:cell()`, `grid:segmentCoord()`, `marker:segmentTile()`. |
| `{x=, y=}` | A screen point, in [design pixels](ui/pixels.md). | `widget:position()`, `widget:rootPos()`, `session:world():worldToScreen(position)`, `event:pixel(index)`. |
| `{w=, h=}` | A size, in design pixels. | `widget:size()`, `widget:cellSize()`, `image:size()`, a map drawing's `:size()` and `:info().size`, `rule:size()`. |
| `{cur=, max=}` | A pair of counts. | `item:durability()`, `contents:fill()`. |
| `{l=, t=, r=, b=}` | Four insets in design pixels: left, top, right, bottom. | `rule:padding()`, `rule:margin()`, the `padding` and `margin` of `widget:style()`, a border's `slice`. |
| `{x=, y=, z=}` | A point or a span in world units. | The `min`, `max` and `extent` of `mesh:bounds()`. |

| Rule | Detail |
|---|---|
| A size is `{w=, h=}`, a place is `{x=, y=}` | Neither answers the other's keys: `widget:size().x` raises naming `.w`, and so do `widget:info().size` and a [stylesheet](ui/style/geometry.md) snapshot. A span keeps three numbers under `mesh:bounds().extent`. |
| Objects are not shapes | A [Position](position.md), a [colour](#colours) and a [Grid](map/grids.md#the-grid-object) have verbs. [conventions](conventions.md#objects-and-the-snapshot-hatch) says what one costs. |

## Coordinates

A place in the world is a [Position](position.md), the one type every spatial verb carries and the only thing `position()` answers. It is computable: `position:offset(dx, dy)` moves it in world units, crossing grid boundaries for you. It is durable: into [`hafen.store`](store/README.md) and back unchanged. Tile, grid and segment coordinates are lattice indices with their own names. Screen pixels are plain `{x, y}` numbers.

| Rule | Detail |
|---|---|
| No global position | A world coordinate is this session's answer, re-based whenever the server drops the map (a login, a cave). A Position saves a grid id plus an offset, and the id comes from the server, so it means the same to everyone. A map marker records its place in ids this client invented and a merge rewrites, so store [`marker:position()`](map/markers.md#the-marker-object) ([storing a place](map/grids.md#storing-a-place)). |
| An id too big for a number is a decimal string | A grid id and a segment id are 64-bit values and a Lua number is a double. So `position:info().gridId`, [`grid:id()`](map/grids.md#the-grid-object), [`segment:id()`](map/grids.md#the-segment-object) and the `seg` of a [marker snapshot](map/markers.md#the-marker-object) are strings. `hafen.map():grid():get(position:info().gridId)` is a round trip. A number where one goes is refused: the id it rounds to is a real grid elsewhere. |

## Colours

A colour is a table of `0..255` components: one shape comes out, two spellings go in.

```lua
local keyed = { r = 200, g = 210, b = 220, a = 255 }        -- what every reader hands back
local positional = { 200, 210, 220 }                        -- r, g, b, and a if you want it
```

| Rule | Detail |
|---|---|
| Every read is keyed | `kin:color()`, `segment:color()`, `marker:color()`, `overlay:color()`, `handle:color()`, `entity:tint()`, `gob:tint()`, `rule:color()`: `.r` answers on all, `[1]` on none. A snapshot carries the same table under `color`, so a colour lifted out goes straight into a write. That is a [party member](types/world.md#partymember), a [kin](types/world.md#kinentry), a [meter](types/ui.md#meter) and its `segments`, a [marker](types/map.md#marker). |
| Both spellings go in | `rule:color(color)`, `marker:color(color)`, `overlay:color(color)`, `handle:color(color)`, an entity's `:tint(color)`, `gob:tint(color)`, `graphics:text{color = color}`. `overlay:color(kin:color())` is one expression. Alpha defaults to `255`. A component outside `0..255` is clamped. |
| A stylesheet document | `sheet:stock()` answers a whole document whose every colour is the keyed table, and `sheet:load` takes it back as it came ([the client's own look](ui/style/README.md#the-clients-own-look)). |
| Loose components are not a colour | `overlay:color(200, 210, 220)` raises naming the table. The exception is the draw context: [`graphics:color(r, g, b, a)`](ui/drawing.md#draw-methods) takes loose components, the language of every `graphics:` verb, and the table too. |

## Units

A number the client draws as a meter is a `0..1` fraction, never seconds. The server sends proportions, so a read whose name sounds like a timer answers how much is left.

| Read | The number is |
|---|---|
| `hafen.time():dayFraction()`, `:yearFraction()`, `:moon()` | How far through the day, the year, the lunar cycle. |
| `buff:amount()`, `buff:remaining()` | The buff's own meter, and how much of its run is left. |
| `slot:cooldown()` | An ability's cooldown meter. |
| `meter:segment():list()[n]:value()`, `slot:progress()` | A HUD bar's fill, a curiosity's progress. |

A name ending `Fraction` says so. The rest are listed here. Two reads that sound like time carry no unit of time. [`slot:time()`](study.md#a-slot) is a total, not a countdown. [`wound:label()`](wound.md#a-wound) is a magnitude the content chose.

---

## See Also

- [Conventions](conventions.md) — how the API is spelled, and what a read hands back.
- [Data types](types/README.md) — every named snapshot shape, field by field.
- [The Position type](position.md) — the one place type every spatial verb takes.
- [The pixel](ui/pixels.md) — the unit a screen point and a size are counted in.
- [Segments and grids](map/grids.md) — where a 64-bit id is the key you look one up by.
