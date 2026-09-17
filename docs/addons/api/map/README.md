# hafen.map: The Map Database

The map you have explored, kept on disk and outliving the session. It holds the ground walked over, cut into segments and grids, the claims and provinces that covered it, your markers, and the minimap icon settings. Read or drop a pin, ask what the client recorded about a piece of ground, draw a map of your own.

```lua
local position = hafen.session():current():player():gob():position()
local pin = hafen.map():marker():add("Camp", position):color{0, 200, 0}:onMap(true)
hafen.store():var("cfg").camp = pin:position()       -- durable: it survives the relog
hafen.map():marker():remove(pin)
```

---

> **Recorded, not live.** Nothing under `hafen.map` reads the terrain streamed around you. That is [`session:world`](../world.md), which owns `tile`, `height` and the coordinate space and hands back a [Position](../position.md). `hafen.map` is the database behind the map window and the corner minimap.

## The collections

| Collection | Holds | Page |
|---|---|---|
| `hafen.map():segment()` | The contiguous explored areas. | [Segments and grids](grids.md) |
| `hafen.map():grid()` | The 100×100-tile squares, by the server's id. | [Segments and grids](grids.md) |
| `hafen.map():marker()` | Your pins and the server's. | [Markers](markers.md) |
| `hafen.map():icon()` | The minimap icon registry. | [Icons](icons.md) |
| `hafen.map():display()` | The client's display switches for claims and provinces. | [Overlays](overlays.md) |

| Rule | Detail |
|---|---|
| One map per world | Every character you log in there reads and writes it. A pin the first drops is on the second's map. Ground either walks over is explored for both. One write lock covers it. Which character is on screen decides only where a [Position](../position.md) can be resolved, a question about the live world. Closing a map window, or ending a session, takes nothing out. A character given its own map file with `:chrmap` explores a database nobody else writes. |
| Handed back by identity | Calling a collection every frame costs nothing. `grid:mask()` is a view, re-derived on each call and holding nothing, so two calls are two objects. The members are interned either way. |
| Reads answer `nil` until the disk answers | A read needing a grid the client has not loaded starts the load and returns `nil`. Call again next tick. Nothing blocks, nothing throws a loading error, the rule [a Position](../position.md) follows. A map panel re-asks every frame and fills in as the ground arrives. |
| Where a grid sits is in memory | `:id`, `:segmentCoord`, `:position` and `:segment` come from an in-memory index and are never `nil` for that reason. What a grid contains (`:tile`, `:height`, `:modified`) is the file. |
| Identity | Segments, grids, masks, markers and icon categories are interned. `hafen.map():grid():get(id)` hands back the same object every time, `segment:grid():get(coordinate)` the same grid. Any works as a table key. Each holds its id and re-reads the database on every call, so a stashed handle never goes stale. It answers `nil` (and `:exists() == false`) if what it names goes away. |

## Pages

| Page | Covers |
|---|---|
| [Segments and grids](grids.md) | The shape of the database, the handles every other page hands you, and where a position becomes something you can store. |
| [Overlays](overlays.md) | The claims and provinces: the masks on disk and the switches that draw them. |
| [Drawings](drawings.md) | A grid as an image handle, which is a minimap. |
| [Markers](markers.md) | The pins. |
| [Icons](icons.md) | The registry that decides which gob icons the minimap draws. |

---

## See Also

- [`session:world`](../world.md) — the live half: terrain, the coordinate spaces, and the same Grid entity.
- [Coordinates](../shapes.md#coordinates) — why a Position is the only place to store.
- [Gob](../gob.md) — `gob:icon()`, the category name on a live object.
