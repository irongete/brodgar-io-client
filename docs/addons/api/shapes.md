# Shapes: what a value looks like

The plain tables this API hands back and takes, and what the numbers inside one mean. Every table with a
name of its own is in [data types](types.md); this page is the anonymous ones, plus the rules that hold
wherever a value of that kind turns up — places, colours, ids too big for a number, and units.

```lua
local s = hafen.session():current()
local p = s:player():gob():position()

p:tileCoord()                             -- {x = …, y = …}: a place in the tile lattice
s:player():worldToScreen(p)               -- {x = …, y = …}: a screen point, the same two keys
p:info().gridId                           -- a 64-bit grid id, as a decimal STRING
hafen.map():grid():get(p:info().gridId)   -- which is what the recorded map is keyed on
```

## The anonymous shapes

A table with no name of its own carries one of the shapes below. **A place and a screen pixel wear the same
two keys**, and only the verb you called tells them apart: the shape says where the numbers go, never which
space they are in.

| Shape | What it is | Read from |
|---|---|---|
| `{x=, y=}` | a place in a lattice: a tile, a grid, a segment cell | `p:tileCoord()`, `item:cell()`, `grid:segmentCoord()`, `marker:segmentTile()` |
| `{x=, y=}` | a screen point, in [design pixels](ui/pixels.md) | `widget:position()`, `widget:rootPos()`, `widget:size()`, `s:player():worldToScreen(p)`, `ev:pixel(i)` |
| `{w=, h=}` | a size, in design pixels | `widget:cell()`, `img:size()`, `mapImg:size()` |
| `{cur=, max=}` | a pair of counts | `item:durability()`, `contents:level()` |
| `{x=, y=, z=}` | a point or a span in world units | the `min`, `max` and `size` of `mdl:bounds()` |

A **[Position](position.md)** is not on this list and is not a pair of numbers at all. Neither is a
[colour](#colours) or a [Grid](map/grids.md#the-grid-object): a value with verbs on it is an object, and
[conventions](conventions.md#objects-and-the-snapshot-hatch) says what one costs you.

## Coordinates

A place in the world is a **[Position](position.md)**, not a pair of numbers: one type,
carried by every spatial verb, and the only thing `position()` ever answers. It is computable —
`p:offset(dx, dy)` moves it in world units and the engine crosses grid boundaries for you — and durable,
so it goes into [`hafen.store`](store.md) and comes back unchanged. Everything else that counts is a
**lattice** and keeps its own name: tile, grid and segment coords are indices, not places, and screen
pixels are plain `{x, y}` numbers.

> **There is no global position.** A world coordinate is this session's answer, re-based whenever the
> server drops the map — a login, a walk into a cave. What a Position saves is a **grid id** plus an
> offset inside it, and that id comes from the **server**, so it means the same thing to everyone. A map
> marker records its place differently, in ids this client invented and a map merge rewrites, so one you
> want to keep is stored as its [`marker:position()`](map/markers.md#the-marker-object); see
> [storing a place](map/grids.md#storing-a-place).

**An id too big for a number is a decimal string.** A grid id and a segment id are 64-bit values and a Lua
number is a double, so each one crosses as its exact decimal spelling and goes back in the same form:
`p:info().gridId`, [`grid:id()`](map/grids.md#the-grid-object),
[`seg:id()`](map/grids.md#the-segment-object) and the `seg` of a
[marker snapshot](map/markers.md#the-marker-object) are all strings. That is what makes
`hafen.map():grid():get(p:info().gridId)` a round trip. A number where one of them goes is refused rather
than looked up, because the neighbouring id it rounds to is a real grid somewhere else on the map.

## Colours

A colour is a table of **0..255 components**, written either way:

```lua
{ 200, 210, 220 }                                   -- positional: r, g, b, and a if you want it
{ r = 200, g = 210, b = 220, a = 255 }              -- keyed — what every reader hands back
```

Both are accepted everywhere a colour goes in: `rule:color(…)`, `g:text{color=…}`, `marker:color(…)`,
a ghost or sprite `:tint(…)`, `font:color(…)`. So a colour you *read* — `kin:color()`, `meter:color()` —
passes straight back. Alpha defaults to `255`, and a component outside `0..255` is clamped.

The same table is what a snapshot carries under a `color` key — a [party member](types.md#partymember), a
[kin](types.md#kinentry), a [meter](types.md#meter) and its `segments`, a [marker](types.md#marker) — so a
colour lifted out of one goes straight into a write.

## Units

**A number the client draws as a meter is a `0..1` fraction, never seconds.** The server sends proportions
rather than countdowns, so a read whose name sounds like a timer answers how much is left, not how long:

| Read | What the number is |
|---|---|
| `hafen.time():dayFraction()`, `:yearFraction()`, `:moon()` | how far through the day, the year, the lunar cycle |
| `buff:amount()`, `buff:duration()` | the buff's own meter, and how much of its run is left |
| `slot:cooldown()` | an ability's cooldown meter |
| `meter:value()`, `slot:progress()` | a HUD bar's fill, a curiosity's progress |

A name ending **`Fraction`** says so in the name. The rest do not, which is why they are listed here.

Two reads that sound like time carry no unit of time at all, and each is stated where you read it:
[`slot:time()`](study.md#a-slot) is a total rather than a countdown, and
[`w:severity()`](wound.md#a-wound) is a magnitude the content chose.

## See also

- [conventions](conventions.md) — how the API is spelled, and what a read hands back
- [data types](types.md) — every named snapshot shape, field by field
- [the Position type](position.md) — the one place type every spatial verb takes
- [the pixel](ui/pixels.md) — the unit a screen point and a size are counted in
- [segments and grids](map/grids.md) — where a 64-bit id is the key you look one up by
