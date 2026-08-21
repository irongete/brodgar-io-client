# hafen.map: the map database

The map you have **explored**. The client keeps it on disk and it outlives the session: the ground you
have walked over, cut into segments and grids, the claims and provinces that covered it, your markers, and
the minimap icon settings that decide what is drawn on it. Reach for it to read or drop a pin, to ask what
the client wrote down about a piece of ground, and to draw a map of your own.

```lua
local p = hafen.session():current():player():gob():position()
local pin = hafen.map():marker():add("Camp", p):color{0, 200, 0}:onMap(true)
hafen.store():get("cfg").camp = pin:position()       -- durable: it survives the relog
hafen.map():marker():remove(pin)
```

> **Recorded, not live.** Nothing under `hafen.map` reads the terrain streamed around you — that is
> [`session:world`](../world.md), which owns `tile`, `height` and the rest of the coordinate space,
> and hands back a [Position](../position.md). `hafen.map` is the database behind the map window and the corner minimap.

## One map for the client

There is **one recorded map per world you play in**, and every character you log in there reads and writes
it. Two of your own characters exploring in different places fill in one database: a pin the first drops is
on the second's map, ground either of them walks over is explored for both, and one write lock covers the
whole of it. Which character is on screen decides nothing about what this namespace answers — what it
decides is where a [Position](../position.md) can be resolved, and that is a question about
the live world.

The map window and the corner minimap are one character's windows **over** that database, so closing a map,
or ending that character's session altogether, takes nothing out of it.

A character you have given a map file of its own with the client's `:chrmap` command is the exception: it
explores a database nobody else writes.

## Five collections

The section is called, and everything after it is a collection of one kind of thing:

| Collection | Holds | Page |
|---|---|---|
| `hafen.map():segment()` | the contiguous explored areas | [segments and grids](grids.md) |
| `hafen.map():grid()` | the 100×100-tile squares, by the **server's** id | [segments and grids](grids.md) |
| `hafen.map():marker()` | your pins and the server's | [markers](markers.md) |
| `hafen.map():icon()` | the minimap icon registry | [icons](icons.md) |
| `hafen.map():overlay()` | the client's display switches for claims and provinces | [overlays](overlays.md) |

Each is handed back by identity, so calling one every frame costs nothing. A collection owned by an
**entity** is the other case: `grid:overlay()` is a *view*, re-derived on each call and holding nothing, so
it cannot outlive its grid — two calls are two objects on purpose. What is interned either way is the
**members**, and that is the identity worth testing.

## Reads answer nil until the disk answers

The database is on disk. **A read that needs a grid the client has not loaded starts the load and
returns `nil`; call again next tick and it answers.** Nothing blocks, and nothing ever throws a loading
error at you — the same rule
[a Position](../position.md) already follows. So a
panel that draws the map simply re-asks every frame and fills in as the ground arrives; there is no
callback to register and no "ready" event to wait for.

Which reads can be `nil` for that reason is worth knowing, because the rest never are: where a grid
*sits* (`:id`, `:segmentCoord`, `:position`, `:segment`) comes from a small index the client keeps in
memory, while what it *contains* (`:tile`, `:height`, `:modified`) is the file itself.

## Identity

Segments, grids, masks, markers and icon categories are **interned objects**: `hafen.map():grid():get(id)`
hands back the same object every time, `seg:grid():get(sc)` hands back that same grid, and any of them
works as a table key. Each holds only its id and re-reads the database on every call, so a stashed handle
never goes stale — it simply starts answering `nil` (and `:exists() == false`) if what it names goes away.

## Reading order

**The ground itself** — [segments and grids](grids.md) is the shape of the database and the handles every
other page hands you, and it is where a position becomes something you can store.

**What covered it** — [overlays](overlays.md) is the claims and provinces, both the masks on disk and the
switches that draw them.

**What it looks like** — [drawings](drawings.md) turns a grid into an image handle, which is a minimap.

**What you and the server put on it** — [markers](markers.md) is the pins, [icons](icons.md) the registry
that decides which gob icons the minimap draws.

## See also

- [`session:world`](../world.md) — the live half: terrain, the coordinate spaces, and the same Grid entity
- [coordinates](../shapes.md#coordinates) — why a Position is the only place worth storing
- [Gob](../gob.md) — `gob:icon()`, the category name on a live object
