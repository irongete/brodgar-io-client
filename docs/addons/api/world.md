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

> **What belongs to the screen says so** rather than aiming at a scene nobody is looking at:
> [`screenToWorld`](#screen-to-world-and-placement-snapping) reads a pixel, and
> [`click`](#write-protected), `place` and `select` are gestures with the pointer. Asked of a session that is
> not on screen, each raises naming `hafen.session():current()`. Walking is the whole of what a character you
> are not looking at will take — see [`move`](player.md#write-protected).

## Objects

`s:world():gob()` is the collection of the game objects that character has loaded, and the only way to
address one. It is read-only: you can look, you cannot add or remove. Every verb hands out live
[Gob](gob.md) **objects**, not snapshots, and they stay fresh for as long as you keep them.

`:list`, `:count`, `:find`, `:nearest` and `:within` take the canonical
[filter](conventions.md#the-filter-argument) — `nil`, a name substring, or a predicate — and a predicate
receives a **Gob**. `nearest` and `within` measure from **that** character and skip its own gob. None of them
throws.

| Call | Returns | Description |
|---|---|---|
| `s:world():gob():get(id)` | [Gob](gob.md) | the object with that id — **never `nil`** |
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

Because a Gob is the object itself you can use one as a table key directly — `seen[g] = true` de-dupes across
repeated sweeps without touching ids, and two of your characters looking at one tree find the same value; see
[identity](gob.md#identity).

> For reacting to objects rather than polling, prefer the `GobAdded`/`GobRemoved`
> [events](event/bus.md#world) over scanning every frame.

## The Position type

A place is a **Position**, and it has a page of its own: [Position](position.md). Everything spatial here
takes one or hands one back — `s:world():position(x, y)` builds one, `s:world():tile(p)` reads under one,
`gob:position()` is where most of them come from.


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

The verbs below change the world rather than read it, and each sends exactly the message the matching mouse
gesture sends. Each hands the section back, so a run of writes chains. Each also needs its own
[permission key](../guides/permissions.md) declared in your manifest — `gob.click`, `world.place` and
`world.select`, or the group `world.*` for the last two — and raises an error naming that key when it was not
declared. Each is the **drawn** character's: clicking and placing need the pointer and selecting is a drag
with it, so each raises for a session that is not on screen, naming `hafen.session():current()`.

### `s:world():click(gob, button, mods)`

Click a game object — exactly the click a left- or right-click on it sends, so the server sees what it would
have seen from the player. The **session acts and the object is the target**, because a click is something a
character does and a [Gob](gob.md) names the object rather than one character's view of it.

`gob` is required and must be a Gob. `button` is optional and defaults to `1` (left: select, interact); `3` is
right, the one that opens the [radial menu](flowermenu.md). `mods` is optional and defaults to `0`: Shift = 1,
Ctrl = 2, Alt = 4, added together. It aims at the **whole object** rather than at a part of it, so a composite
body part or a specific sub-mesh is not addressable.

```lua
local w = hafen.session():current():world()
local tree = w:gob():nearest("terobjs/tree")
if tree then w:click(tree, 3) end        -- right-click it: the radial menu
```

Its key is `gob.click` — a key names the action, and the action is clicking an object.

Unlike a read on a Gob, an object **this character cannot see raises**: it left view, despawned, or is one
only another of your characters has loaded — [`gob:sessions()`](gob.md#gobsessions) says which. So does one
that has no position yet, and a call made before that session is in the world. A click is a message about one
specific object, and there is nothing honest to send about an object this character is not looking at;
nothing goes out in any of those cases.

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

- [Gob](gob.md) — what the object readers hand back, and which character does the reading
- [`hafen.session`](session.md) — the address every verb here hangs off
- [`hafen.map`](map/README.md) — the recorded map: its segments and grids, your markers, the icon categories
- [the `filter` argument](conventions.md#the-filter-argument) — the three forms the object readers accept
- [`hafen.store`](store.md) — where a Position is saved
- [coordinates](conventions.md#coordinates) — the spaces, side by side
