# Position: a place in the world

A **Position** is a place. It is the one position type in the API, and every spatial verb takes one:
`gob:position()`, `s:player():move(p)`, `s:world():tile(p)`. It exists because a place has to be two things at
once — a point you can do arithmetic on, and something you can save — and a plain `{x, y}` table can only ever
be one of them. You get one from a [world](world.md) you name, or off anything that stands in it.

```lua
local s = hafen.session():current()
local p = s:player():gob():position()

p:x()  p:y()                            -- session world components, when you need numbers
p:offset(0, 22)                         -- a NEW Position two tiles south
p:distance(other)                       -- world distance; with no argument, to the drawn character
p:tileCoord()                           -- the tile it sits in, {x, y}
p:durable()                             -- can it be saved?
p:info()                                -- {gridId, x, y} — the durable form

hafen.store():get("spot").home = p        -- saved and reloaded as a Position, no conversion
```

## Read

Nothing here is protected. A read answers `nil` where it has no answer rather than raising; what raises is a
bad **argument** — `p:offset(dx, dy)` takes two numbers and `p:distance(other)` another Position, and each
refuses anything else naming the verb and the parameter. A numeric string is
[still a string](conventions.md#a-number-is-not-a-string-and-a-numeric-string-is-not-a-number), so
`p:offset("1", 2)` is refused rather than converted.

| Method | Returns | Description |
|---|---|---|
| `p:x()` `p:y()` | number \| nil | session world components |
| `p:offset(dx, dy)` | Position \| nil | a new Position `dx` east and `dy` south, in world units |
| `p:distance(other)` | number \| nil | world distance to another Position; defaults to the drawn character |
| `p:tileCoord()` | `{x, y}` \| nil | the session tile coord it sits in |
| `p:durable()` | bool | whether it has a durable form |
| `p:info()` | `{gridId, x, y}` \| nil | the durable form: a grid id, and the offset **within** that grid |

You build one with `s:world():position(x, y)` from that session's world components, or
`s:world():position(saved)` from the `{gridId, x, y}` table `:info()` gives you.

**A Position carries no session, and that is deliberate**: a place is answerable in whichever session you
ask, so giving one a session would make two Positions for one patch of ground. A **world coordinate is
relative to where its session logged in**, so the durable form is worked out where the reader knows its
session — `gob:position()` uses the character that read it — and it is the grid id that travels. The
verbs *on* a Position were asked without a session, so `p:x()`, `p:y()`, `p:tileCoord()` and a bare
`p:distance()` answer for the character on screen, while a verb reached through a session resolves the place
in **its** frame.

> **`:info()` and `:x()`/`:y()` are not the same numbers.** `:x()`/`:y()` are session world components;
> `:info()` carries a grid id plus the offset *inside that grid*, `0` to `1100`.

**The arithmetic belongs to the engine, and that is the point.** A grid is 100 tiles across and a tile is
11 world units, so a grid is **1100 units** wide. Adding to a grid-relative number is right until it is
not: past the edge the honest answer is a different grid. `p:offset(dx, dy)` crosses the boundary and
re-derives the grid, which is what lets one Position be computable *and* durable: `p:offset(0, 1100)` comes
back naming a different `gridId`, not a number past the end of this one.

**Durability is explored, not loaded.** A Position gets its grid id from the terrain streamed around that
character if the ground is loaded, and otherwise from what the client wrote down about it — so it is durable
**anywhere that character has been**, and fails only on ground it has never visited, which is also ground it
cannot act on. `:durable()` reports it, and one that is not durable cannot be saved:
[`hafen.json`](json.md) refuses it and the store writes nothing for it.

Going the other way has its own boundary: a place recorded in a *different* part of the world has no
coordinate in a given session at all. It keeps its durable form — `:info()` and `:durable()` answer — while
`:x()`, `:y()` and `:tileCoord()` answer `nil`, and the verbs that act on the world refuse it **naming the
character** it is out of reach for. Another character may well be able to reach it.

**Two Positions are never equal** unless they are literally the same object. A Position is a value, so
compare what it *names*: `:info()` for the durable form, `:tileCoord()` for the tile, `:distance()` for
nearness.

## Saving a position

A Position goes into [`hafen.store`](store.md) as-is and comes back as a Position — no conversion in
either direction. [`hafen.json`](json.md) writes it as its durable form and reads that form back as a
Position, so a place survives a file, a message, or another player.

```lua
hafen.store():get("spot").home = s:player():gob():position()   -- a declared saved variable

-- next session
local home = hafen.store():get("spot").home
local cur = hafen.session():current()
if home and home:x() then cur:player():move(home) end
```

> **There is no global position**, so a Position saves as a grid id plus an offset — the one anchor that
> means the same thing to another character and another player, because it comes from the **server**. That
> id crosses as [a decimal string](shapes.md#coordinates), and why a raw coordinate cannot be saved at all
> is in [coordinates](shapes.md#coordinates).

A durable form also reaches the **recorded** map: `hafen.map():grid():get(p:info().gridId)` hands back the
very same [`Grid`](map/grids.md#the-grid-object) the live query does, whether or not that ground is streamed
in. See [storing a place](map/grids.md#storing-a-place) for why a marker's own segment coordinates are not
that shape.

## What is not a Position

A **lattice cell** is an index, not a place, and keeps its own name: `grid:segmentCoord()` counts grids,
`marker:segmentTile()` counts tiles, and the argument of `grid:tile(c)` is a within-grid tile coord `0..99`.
**Screen pixels are not Positions** either: a widget's `:position()`, `:rootPos()` and
[`worldToScreen`](world.md#the-screen-and-the-world) answer a plain `{x, y}` of
[design pixels](ui/pixels.md), and handing one to `s:player():move()` raises rather than walking a character
somewhere wrong.

## The verbs here answer for the screen; `s:world()` has the addressed three

A Position carries no session, so the verbs *on* one were asked without an address and resolve in the
**drawn** character's frame: `p:x()`, `p:y()`, `p:tileCoord()` and a bare `p:distance()`. With one login
that is the only frame there is. With two, the same three questions asked *about* a named character are
[`s:world():components(p)`](world.md#terrain-and-coordinates), `s:world():tileCoord(p)` and
`s:world():distance(p [, other])` — the section holds the address, so those resolve in its frame.

`p:offset(dx, dy)` needs no twin: it is arithmetic in world units and hands back a Position.

## See also

- [`session:world`](world.md) — where a Position is built, the terrain reads that take one, and the three
  addressed twins of the verbs here
- [Gob](gob.md) — `gob:position()`, the commonest way to get one
- [`session:player`](player.md#write-protected) — walking a character to one
- [`hafen.store`](store.md) — saving one, as-is
- [`hafen.map`](map/README.md) — the recorded map a durable form also reaches
- [coordinates](shapes.md#coordinates) — the spaces, side by side
