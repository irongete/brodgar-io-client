# The map database (the map you EXPLORED, as opposed to the world you are standing in)

> **Status:** 🟢 Design closed — shipped as [037-map-database](../037-map-database/spec.md) · **Spec:** AddOns
> **Series:** the map half of the area · **Surface:** `hafen.map.*` (segments, grids, overlays, drawings,
> `map.markers`, `map.icons`) beside `hafen.world.*` (the live half)
> **Decisions:** [D-093](../decisions/architecture-api.md) (an entity's identity is the key a NAME can address),
> [D-094](../decisions/architecture-api.md) (an intern key follows the engine's OWN stability),
> [D-095](../decisions/architecture-api.md) (a read of a STORED world kicks the load and answers nil),
> [D-096](../decisions/architecture-api.md) (a client-local id a MERGE re-bases is a view, never a stored
> position), [D-097](../decisions/architecture-api.md) (a ref-counted client toggle is a HOLD),
> [D-098](../decisions/architecture-api.md) (a DERIVED resource is keyed by what it PRODUCED, and bounded);
> and the standing four this feature only applied — [D-056](../decisions/architecture-api.md) (arity is the
> verb), [D-061](../decisions/architecture-api.md) (the vocabulary comes from the engine),
> [D-063](../decisions/architecture-api.md) (an entity's key is what the engine publishes),
> [D-066](../decisions/architecture-api.md) (a thing that lives inside another is a relation)
> **Related:** [16-virtual-entities.md](16-virtual-entities.md) (the grid anchor `planner` already persisted
> against), [17-custom-rendering.md](17-custom-rendering.md) + [../028-asset-loader/](../028-asset-loader/)
> (the image handle a grid drawing turned out to *be*), [22-ui-selectors.md](22-ui-selectors.md) (the callable
> namespace and the shape-split argument), [../../codebase/mapfile.md](../../codebase/mapfile.md) and
> [../../codebase/minimap.md](../../codebase/minimap.md) (the engine), [the API
> reference](../../../docs/addons/api/map.md)

## The problem this solves

There are **two maps** in this client and the API had a name for only one of them.

`MCache` is the terrain streamed around the player: it exists while you are logged in, it is `nil` a few
grids away, and it is gone at logout. `MapFile` is the map the player has **explored**: segments and grids
on disk, zoom levels, the claim and province masks that covered that ground, the markers, and the drawings
the corner minimap paints. Until 037 the whole of `hafen.map` was the first one, one door reached the second
(`hafen.markers`) without saying so, and nothing at all reached the rest of it.

So the feature is a **split before it is an addition**: `hafen.world` is the live world (gobs, terrain, the
session coordinate spaces, screen→ground, placement snapping — every one of them an `MCache` read) and
`hafen.map` is the recorded map. `hafen.markers` and `hafen.radar` are hard cuts, each for its own reason: a
marker lives in the map file, so it is a **relation on it** (D-066), and the engine has no "radar", it has
`GobIcon.Settings` (D-061).

## The rule the whole feature is built around

**A saved or shared position is `{gridId, x, y}`. A segment id is a view.**

| | Where it comes from | What a merge does to it |
|---|---|---|
| **grid id** | the server, `MCache.Grid.fill`, mapdata layer `"m"` | nothing — it is the same number for every player, forever |
| **segment id** | `rnd.nextLong()` in this client | `MapFile.merge` folds one segment into another, **re-bases the loser's grid coords and rewrites every marker inside it in place** |

The failure mode is the argument. A stored `{seg, tc}` does not go `nil` after a merge — it points at the
**wrong place**, which is strictly worse than a read that fails. Hence D-096, and the sentence worth carrying
out of this feature: *before exposing an identifier, ask not whether it is stable but how it fails.*

Everything follows from that. `seg:id()`, `grid:sc()` and `marker:tc()` are published because they are how
you compare two things *this session* and how the database is actually indexed — but the shape that leaves
the client is the anchor, `marker:anchor()` exists to produce one, and the documentation says which is which
on the page rather than leaving a reader to guess.

## The shape of the surface

Four entities, each interned per addon, each holding only its id and re-reading the database on every call:

```
hafen.map.segment() / segment(id) / segments()      Segment  :id :exists :grid(sc) :grids(area) :markers :info
hafen.map.grid(gridId)                              Grid     :id :sc :pos :segment :tile :height :mtime
                                                             :overlays :overlay(tag) :image(lvl) :overlayImage(tag)
grid:overlay(tag)                                   Mask     :tag :grid :covers(c) :count :area
hafen.map.markers.list/nearest/add/remove           Marker   :name :type :tc :pos :dist :anchor :segment …
hafen.map.icons() / icons(filter) / icons(res)      IconCat  :res :name :show(v) :notify(v)
hafen.map.overlay(tag[, on]) / overlays()           — the client's own display toggles, not a mask
```

Three shapes carried over rather than invented: **arity is the verb** (`cat:show()` reads, `cat:show(v)`
writes and chains), a **callable namespace** whose argument splits by shape (`icons(res)` is the string with
a `/` in it; anything else is the canonical filter), and **`:info()` as the snapshot escape hatch** on every
entity.

**You ask a segment for an AREA, never for a list.** `Segment.map` is private, and that is not a gap to work
around: `MiniMap` never enumerates either — it walks the grid coords of the rectangle it is drawing. So
`seg:grid(sc)` and `seg:grids(area)` are the whole addressing surface, which is also what a panel wants.

## The three rules that make it usable

**1. A read of a stored world kicks the load and answers nil (D-095).** The database is on disk and resolves
through `Defer`/`Indir`. A callback per read was rejected before it was written: a minimap panel walks a
dozen grids per frame and would become a callback tree whose completion order is the disk's. So a read starts
the load and answers `nil`; **the frame is the retry loop**. The lock is `tryLock`, never `lock` — `MapFile`'s
write lock is held across disk I/O by the processor thread, which is `MiniMap.resolve`'s own rule. The one
exception proves it: the marker *list* takes the blocking read lock, because an empty list is a **lie** a
caller cannot tell from "no markers", where a nil grid is a documented "ask again".

**2. An intern key follows the engine's own stability (D-094).** D-063 says the key is what the engine
publishes; it has no answer for an engine object with no id, and the rule underneath it is *ask what the
engine does to the object*. A `Segment` lives in a `BackCache(5)` and a `Grid` in a weak `CacheMap`, so both
key on the published id — a handle interned on Java identity would silently write to an orphan after an
eviction. A `MapFile.Marker` is loaded once and thereafter **mutated in place**, so its Java identity is the
only truth there is, and a per-session `IdentityHashMap` is exact.

**3. A ref-counted client toggle is a HOLD (D-097).** `MapView.oltags` is a **multiset** whose other
counters are the user's own checkbox and the server's `flashol`. "Off" is therefore not a state an addon can
express — only "not by me" — so a write is a hold taken and released like a hidden window (D-069 one
subsystem along), idempotent *by arithmetic* rather than by politeness, and the READ is the client's own
answer (*is it displayed*), never *do I hold it*.

## The two places the vocabulary is not one vocabulary

Both are documented rather than smoothed over, because a reader who is not told gets each of them wrong once.

- **The overlay tag spaces are opposite on purpose (D-072 said both ways).** The *display* toggles are the
  client's own closed set of four, so an unknown tag is **refused** — a typo that silently does nothing
  forever is the one failure here nothing else would ever report. The *recorded* tags are declared by the
  server's overlay resources, so an unknown tag is plain **nil**, and `grid:overlays()` is the census that
  makes that nil readable.
- **`prov` and `realm` are the same feature and two engine tags**: the 3D world draws provinces under `prov`
  (`MapView`), the map window under `realm` (`MapWnd.overlays`). Writing one does not touch the other.

## Drawings: a picture is an ordinary image handle

`grid:image(lvl)` and `grid:overlayImage(tag)` hand back exactly what `hafen.asset("icon.png")` hands back —
same `LuaImage`, same `:size()`/`:dispose()` — so `g:image`, `hafen.render.sprite` and the stylesheet's
`bg = {image = …}` all took them with **no new code at all**. That is what makes a live recorded map cost
**zero draw callbacks**: hand the handle to the sheet and the engine paints it.

**A level is a scale, not a size**: every drawing is 100×100 pixels, one pixel per 2ⁿ tiles. Level 0 goes
through the client's own 3×3 `MapFile.View` (tile transitions blend across the grid border, so a grid
rendered alone would carry a seam the minimap does not have); levels ≥1 are `ZoomGrid`s.

**D-098** is the part that is not an asset's rule: a derived resource is keyed by **what it produced**
(segment, level, level-coord — the four grids under one level-1 zoom grid are literally one picture) and the
per-addon cache is a bounded LRU whose eviction disposes, because an asset has a natural end (N files, N
textures) and a derived resource has none. Consequence worth stating on the page: **asking is what keeps a
picture alive**, so the documented shape is re-ask every frame — which D-095 already required.

## What this deliberately is not

- **Writing the database.** No grid or segment creation, no merges, no export/import. Markers keep the
  add/remove they already had, and that is the only write.
- **Replacing the map window or the corner minimap.** That is `widget:replace` (032) if ever.
- **The 3D overlay geometry** (`MCache.getolcut`): the feature reads the recorded masks and drives the
  client's own toggles; it does not draw in the world.
- **Segment merging as an event.** The anchor rule removes the need — nothing an addon stores is expressed
  in segment space.
- **Sharing markers between players.** `marker:anchor()` ships the coordinate that makes it possible; the
  transport is the addon's business.
- **`Polity`** (the village/realm authority window) — a widget, not the map. It stays on the ROADMAP.

## Where it landed

Zero core edits across all five tasks — every seam (`GameUI.mapfile`, `MapFile.segments`/`gridinfo`/
`knownsegs`, `Segment.grid`, `DataGrid.render`/`olrender`, `MiniMap.sessloc`, `MapView.visol`/`enol`/`disol`,
`MapWnd.overlays`, `MCache.ResOverlay.tags`) was already public. The example addon is
[`atlas`](../../../addons/atlas/main.lua): a live minimap panel out of the database alone, painted by the
engine from an image handle, whose whole per-frame cost is a timer asking whether the picture changed.
