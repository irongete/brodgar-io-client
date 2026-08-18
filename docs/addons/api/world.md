# session:world: one character's world

Everything one of your characters has loaded **right now**: the game objects around it, the terrain under
them, the coordinate spaces they live in. Reach for it to find the gobs you want, to ask what is under a
point, or to build a place you can save. You reach it through the [session](session.md) whose character you
mean, and what a single object answers is the [Gob](gob.md) it hands you.

```lua
local s = hafen.session():current()                  -- the character on screen
local prey = s and s:world():gob():nearest(function(g)
  local n = g:name()
  return n and n:find("rabbit")
end)
if prey then
  local p = prey:position()
  hafen.log():write(string.format("nearest rabbit at grid %s", p:info().gridId))
end
```

> **Live, not recorded.** Everything on this page reads the world as it is streamed around that character: it
> is `nil` off-stream and gone at logout. The map you have *explored* — segments, grids, your markers, the
> minimap icons — is on disk and persistent, and that is [`hafen.map`](map/README.md).

## Whose world it is

There is no such thing as *the* world. `s:world()` is the world **that character** is standing in, and two
characters apart disagree about all of it — not because there are two worlds, but because each looks out of
its own eyes. So the read says which one it is about:

```lua
hafen.session():current():world():gob():count("terobjs/tree")   -- trees the drawn character can see
hafen.session():get("alt"):world():gob():count("terobjs/tree")  -- trees that character can see
```

`s:world()` is the same object every call, and so is `s:world():gob()` — minted once for that session — so a
draw callback that reads them at 60 fps allocates nothing. A session the client no longer holds answers
`nil`-shaped rather than raising, which is the same shape as before entering the world;
[`s:exists()`](session.md#read) tells the two apart.

> **Three verbs belong to the screen, not to the session**, and they say so rather than aiming at a scene
> nobody is looking at: [`screenToWorld`](#screen-to-world-and-placement-snapping) reads a pixel, and
> [`place`](#write-protected) and `select` are gestures with the pointer. Asked of a session that is not on
> screen, each raises naming `hafen.session():current()`. Walking is the whole of what a character you are
> not looking at will take — see [`move`](player.md#write-protected).

## Objects

`s:world():gob()` is the collection of the game objects that character has loaded, and the only way to
address one. It is read-only: you can look, you cannot add or remove. Every verb hands out live
[Gob](gob.md) **objects**, not snapshots, and they stay fresh for as long as you keep them.

`:list`, `:count`, `:find`, `:nearest` and `:within` take the canonical
[filter](conventions.md#the-filter-argument) — `nil`, a name substring, or a predicate — and a predicate
receives a **Gob** read through the same session. `nearest` and `within` measure from **that** character and
skip its own gob. None of them throws.

| Call | Returns | Description |
|---|---|---|
| `s:world():gob():get(id)` | [Gob](gob.md) | the object with that id, as that character sees it — **never `nil`** |
| `s:world():gob():list(filter)` | [Gob](gob.md)`[]` | every matching object |
| `s:world():gob():count(filter)` | number | how many match |
| `s:world():gob():find(filter)` | [Gob](gob.md) \| nil | the first match, in load order |
| `s:world():gob():nearest(filter)` | [Gob](gob.md) \| nil | the closest match to that character |
| `s:world():gob():within(radius, filter)` | [Gob](gob.md)`[]` | matches within `radius` world units of it |

```lua
for _, g in ipairs(s:world():gob():within(50, "gfx/borka/body")) do
  -- players within 50 units of that character; g is a live Gob
end
```

**`:get(id)` never answers `nil`**, even for an id that character has not loaded or that never existed. That
is what lets you anchor to a gob before it streams in; `:exists()` is the liveness test. The section a verb
lives under says where the thing is, not whether it is there.

Because Gobs are interned per session you can use them as table keys directly — `seen[g] = true` de-dupes
across repeated sweeps without touching ids. Across two sessions the same id is two Gobs and `:id()` is what
crosses them; see [identity](gob.md#identity).

> For reacting to objects rather than polling, prefer the `GobAdded`/`GobRemoved`
> [events](event/bus.md#world) over scanning every frame.

## The Position type

A **Position** is a place. It is the one position type in the API, and every spatial verb takes one:
`gob:position()`, `s:player():move(p)`, `s:world():tile(p)`. It exists because a place has to be two things at
once — a point you can do arithmetic on, and something you can save — and a plain `{x, y}` table can only ever
be one of them.

```lua
local p = s:player():gob():position()

p:x()  p:y()                            -- session world components, when you need numbers
p:offset(0, 22)                         -- a NEW Position two tiles south
p:distance(other)                       -- world distance; with no argument, to the drawn character
p:tileCoord()                           -- the tile it sits in, {x, y}
p:durable()                             -- can it be saved?
p:info()                                -- {gridId, x, y} — the durable form

hafen.store():get("spot").home = p        -- saved and reloaded as a Position, no conversion
```

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
session — `gob:position()` uses the one you read the gob through — and it is the grid id that travels. The
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

### Saving a position

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
> means the same thing to another character and another player, because it comes from the **server**. A grid
> id is 64-bit and Lua numbers are doubles, so it appears as an exact decimal **string**, the only form safe
> to store and compare. Why a raw coordinate cannot be saved is in [coordinates](conventions.md#coordinates).

A durable form also reaches the **recorded** map: `hafen.map():grid():get(p:info().gridId)` hands back the
very same [`Grid`](map/grids.md#the-grid-object) the live query does, whether or not that ground is streamed
in. See [storing a place](map/grids.md#storing-a-place) for why a marker's own segment coordinates are not
that shape.

### What is not a Position

A **lattice cell** is an index, not a place, and keeps its own name: `grid:segmentCoord()` counts grids,
`marker:segmentTile()` counts tiles, and the argument of `grid:tile(c)` is a within-grid tile coord `0..99`.
**Screen pixels are not Positions** either: a widget's `:position()`, `:rootPos()` and
[`worldToScreen`](player.md) answer a plain `{x, y}` of [design pixels](ui/pixels.md), and handing one to
`s:player():move()` raises rather than walking a character somewhere wrong.

## Terrain and coordinates

Terrain reads take a Position and return `nil` when the map for that spot has not streamed in for **that**
character yet; the lattice conversions are pure arithmetic and always answer. Nothing here is protected and
nothing throws on a point that is simply off-map.

> **Ground you can see is not proof the live map has it.** The client also draws ground it merely
> *remembers* — read back off the disk, greyed, wherever the camera looks past what is streaming — and these
> reads never touch that record: over remembered ground `:tile` and `:height` answer `nil` exactly as they do
> over the void beside it. What the client wrote down is [`hafen.map`](map/README.md)'s to read, by grid.

```lua
local p = s:player():gob():position()
local t = s:world():tile(p)
if t then hafen.log():write("standing on " .. (t.name or t.id)) end
```

| Call | Returns | Description |
|---|---|---|
| `s:world():position(x, y)` | Position | a place from that session's world components |
| `s:world():position(saved)` | Position | a place rebuilt from a `{gridId, x, y}` table |
| `s:world():tile(p)` | [`Tile`](types.md#tile) \| nil | tileset id and resource name at a Position |
| `s:world():height(p)` | number \| nil | terrain height there |
| `s:world():grid():at(p)` | [`Grid`](map/grids.md#the-grid-object) \| nil | the map grid covering a Position |
| `s:world():grid():get(id)` | [`Grid`](map/grids.md#the-grid-object) \| nil | that same grid by the server's id, if it is streamed in |
| `s:world():grid():list()` | `Grid[]` | every grid that character has streamed in right now |
| `s:world():tileToWorld(tx, ty)` | `{x, y}` | tile coord to world, at its upper-left corner |
| `s:world():tileToGrid(tx, ty)` | `{x, y}` | tile coord to grid coord |
| `s:world():screenToWorld(sx, sy, fn)` | nothing, calls `fn` | raycast the ground under a root [design pixel](ui/pixels.md); asynchronous, and the drawn session's only |
| `s:world():snapPlace(p, fine)` | Position | snap a Position to the client's placement grid |
| `s:world():snapAngle(a, fine)` | number | snap a facing in radians to the client's placement-angle grid |

The two placement *settings* — how many sub-tile divisions, how many rotation steps — are read and written
through [`hafen.client():options():interface()`](client/README.md#interface): `posGran()` and `angGran()`.

## Screen to world, and placement snapping

These are the inverse of [`s:player():worldToScreen`](player.md) plus the client's own placement snapper —
the primitives any drag-on-the-ground tool is built from.

**`screenToWorld` is asynchronous.** It reads the true terrain point from the GPU, the same pass the
client uses to place a building, so the answer cannot come back inline: it arrives a frame later
through `fn`, as a Position.

```lua
hafen.session():current():world():screenToWorld(sx, sy, function(p)
  if p then hafen.log():write(("ground under cursor: %.1f, %.1f"):format(p:x(), p:y())) end
  -- p is nil if the pixel hit no terrain (sky, or off-map)
end)
```

`(sx, sy)` are **root [design pixels](ui/pixels.md)** — the space `worldToScreen` answers, the space
[`m:x()`/`m:y()`](ui/mouse.md#read) reports, and the space a grab's `ev:x()`/`ev:y()` carries, so the cursor
feeds this door with no arithmetic in between. During a drag, hand it the coords from
[the mouse's grab](ui/mouse.md#the-grab) as they arrive and coalesce — issue the next raycast only after the
previous `fn` fired — so at most one is in flight per frame.

It is the **drawn** session's verb: a pixel is a point on the screen, and there is one screen however many
characters are logged in, so asked of any other it raises naming `hafen.session():current()`. Before that
session is in the world there is no scene to read and `fn` is never called.

**`snapPlace(p, fine)`** snaps a Position exactly as placing a building does, honouring the live
placement-grid setting: without `fine`, the tile centre; with `fine = true`, the sub-tile grid
(`posGran()` divisions, or free when that is `0`). It is the engine's own snapper, so a ghost dropped
through it lands where a real building would.

```lua
-- from inside a grab's "Move" handler, with p the Position screenToWorld handed back:
local snapped = hafen.session():current():world():snapPlace(p, ev:shift())   -- SHIFT picks the fine grid
ghost:position(snapped)                                                      -- a Position in, a Position out
```

**`snapAngle(a, fine)`** is the rotation counterpart: without `fine`, 45° steps; with `fine = true`, the
finer placement-angle grid (`angGran()` steps). It honours the live setting just as `snapPlace` does, so a
ghost rotate feels identical to rotating a real building, and the result is normalized to `(-π, π]`. Both
snappers are pure arithmetic over the client's own settings, so both answer for any session.

## Write (protected)

Two verbs change the world rather than read it, and both send exactly the message the matching mouse
gesture sends. Each hands the section back, so a run of writes chains. Each also needs its own
[permission key](../guides/permissions.md) declared in your manifest — `world.place` and `world.select`, or
the group `world.*` for both — and raises an error naming that key when it was not declared. Both are the
**drawn** character's: placing needs what is on the pointer and selecting is a drag with it, so each raises
for a session that is not on screen, naming `hafen.session():current()`.

### `s:world():place(p, angle, button, mods)`

Place the object **currently on the pointer** at a [Position](#the-position-type), rotated by `angle`
**radians**. `button` is optional and defaults to `1` (confirm); `mods` is optional and defaults to `0`
(Shift = 1, Ctrl = 2, Alt = 4, added together).

`p` and `angle` are both required — a missing or non-number `angle` raises, and so does a `p` that is not a
Position. To land where a real building would, prepare both with
[`snapPlace`](#screen-to-world-and-placement-snapping) and `snapAngle` above.

```lua
local w = hafen.session():current():world()
local p = w:snapPlace(hafen.session():current():player():gob():position())
w:place(p, w:snapAngle(0))
```

> **With nothing on the pointer the server ignores it, and nothing comes back to say so.** Placement is
> started by the server, so the client has no reader that could tell you whether something is being
> placed; there is no verb here that answers it either.

### `s:world():select(p1, p2, mods)`

Area-select the tile rectangle spanned by two Positions — what drives the tile-area tools. Each corner is
floored to the **tile** it falls in, the same conversion [`p:tileCoord()`](#the-position-type) exposes, so the
two Positions name whole tiles rather than a sub-tile rectangle. `mods` is optional, `0` by default. Both
verbs raise before that session is in the world, and for a Position it cannot locate.

## See also

- [Gob](gob.md) — what the object readers hand back, and the identity that crosses two characters
- [`hafen.session`](session.md) — the address every verb here hangs off
- [`hafen.map`](map/README.md) — the recorded map: its segments and grids, your markers, the icon categories
- [the `filter` argument](conventions.md#the-filter-argument) — the three forms the object readers accept
- [`hafen.store`](store.md) — where a Position is saved
- [coordinates](conventions.md#coordinates) — the spaces, side by side
