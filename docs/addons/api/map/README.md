# hafen.map: the map database

The map you have **explored**. The client keeps it on disk and it outlives the session: the ground you
have walked over, cut into segments and grids, the claims and provinces that covered it, your markers, and
the minimap icon settings that decide what is drawn on it. Reach for it to read or drop a pin, to ask what
the client wrote down about a piece of ground, and to turn a position into something you can save or send.

```lua
local p = hafen.player():gob():pos()
local pin = hafen.map.markers.add("Camp", p.x, p.y, { color = {0, 200, 0}, onmap = true })
local anchor = pin:anchor()                          -- {gridId, x, y} — safe to save or share
hafen.map.markers.remove(pin)
```

> **Recorded, not live.** Nothing under `hafen.map` reads the terrain streamed around you — that is
> [`hafen.world`](../world.md), which owns `tile`, `height`, `grid`, `gridPos` and the rest of the
> coordinate space. `hafen.map` is the database behind the map window and the corner minimap.

## Reads answer nil until the disk answers

The database is on disk. **A read that needs a grid the client has not loaded starts the load and
returns `nil`; call again next tick and it answers.** Nothing blocks, and nothing ever throws a loading
error at you — the same rule
[`hafen.world.fromGridPos`](../world.md#saving-a-world-position-across-sessions) already follows. So a
panel that draws the map simply re-asks every frame and fills in as the ground arrives; there is no
callback to register and no "ready" event to wait for.

Which reads can be `nil` for that reason is worth knowing, because the rest never are: where a grid
*sits* (`:id`, `:sc`, `:pos`, `:segment`) comes from a small index the client keeps in memory, while
what it *contains* (`:tile`, `:height`, `:mtime`) is the file itself.

## Identity

Segments, grids, masks, markers and icon categories are **interned objects**: `hafen.map.grid(id) ==
hafen.map.grid(id)`, `seg:grid(sc)` hands back that same grid, and any of them works as a table key.
Each holds only its id and re-reads the database on every call, so a stashed handle never goes stale —
it simply starts answering `nil` (and `:exists() == false`) if what it names goes away.

## Reading order

**The ground itself** — [segments and grids](grids.md) is the shape of the database and the handles every
other page hands you, and it is where a position becomes something you can store.

**What covered it** — [overlays](overlays.md) is the claims and provinces, both the masks on disk and the
switches that draw them.

**What it looks like** — [drawings](drawings.md) turns a grid into an image handle, which is a minimap.

**What you and the server put on it** — [markers](markers.md) is the pins, [icons](icons.md) the registry
that decides which gob icons the minimap draws.

## Pages

| Page | What it covers |
|---|---|
| [segments and grids](grids.md) | the database's shape, the Segment and Grid objects, and saving a position |
| [overlays](overlays.md) | the recorded claim and province masks, and the display toggles over them |
| [drawings](drawings.md) | `grid:image` and `grid:overlayImage`: the minimap picture as an image handle |
| [markers](markers.md) | reading, adding and removing map pins, and the Marker object |
| [icons](icons.md) | the minimap icon registry, and the IconCat object |

## See also

- [`hafen.world`](../world.md) — the live half: terrain, the coordinate spaces, and the grid anchor
- [coordinates](../conventions.md#coordinates) — why the anchor is the only position worth storing
- [`hafen.gob`](../gob.md) — `gob:icon()`, the category name on a live object
- [`atlas`](../../examples.md#atlas) — the example addon: a live minimap panel out of these pages alone
