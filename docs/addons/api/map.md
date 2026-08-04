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

> **Recorded, not live.** Nothing on this page reads the terrain streamed around you — that is
> [`hafen.world`](world.md), which owns `tile`, `height`, `grid`, `gridPos` and the rest of the
> coordinate space. `hafen.map` is the database behind the map window and the corner minimap.
>
> **`hafen.markers` and `hafen.radar` are gone.** A marker lives in the map database, so it is
> `hafen.map.markers`; the icon registry is `hafen.map.icons` — the engine has no "radar", it has icon
> settings. Both old names read plain `nil`.

## Segments and grids

The database is made of **segments** — one contiguous explored area, what the map window draws as one
map — each made of **grids**, the 100×100-tile squares the server hands out.

| Call | Returns | Description |
|---|---|---|
| `hafen.map.segment()` | [`Segment`](#the-segment-object) \| nil | the segment the player is standing in; `nil` until the map has streamed in |
| `hafen.map.segment(id)` | [`Segment`](#the-segment-object) \| nil | one segment by its id; `nil` if the database has no such segment |
| `hafen.map.segments()` | `Segment[]` | every segment this character has explored, in id order |
| `hafen.map.grid(gridId)` | [`Grid`](#the-grid-object) \| nil | one grid by the **server's** grid id — the door in from an anchor |

`hafen.map.grid` is the only call that crosses from the live world into the database, because a grid id
is the only thing the two halves share: hand it the `gridId` out of a
[`hafen.world.gridPos()`](world.md#saving-a-world-position-across-sessions) and you get the recorded
ground under that spot.

> **A 64-bit id is a decimal string, and a number is refused.** Segment and grid ids do not survive a
> Lua number, so `hafen.map.segment(1234)` is an error rather than a lookup of some neighbouring
> segment. Pass `seg:id()` / `grid:id()` and the `gridId` out of an anchor — all of them strings.

### Reads answer nil until the disk answers

The database is on disk. **A read that needs a grid the client has not loaded starts the load and
returns `nil`; call again next tick and it answers.** Nothing blocks, and nothing ever throws a loading
error at you — the same rule [`hafen.world.fromGridPos`](world.md#saving-a-world-position-across-sessions)
already follows. So a panel that draws the map simply re-asks every frame and fills in as the ground
arrives; there is no callback to register and no "ready" event to wait for.

Which reads can be `nil` for that reason is worth knowing, because the rest never are: where a grid
*sits* (`:id`, `:sc`, `:pos`, `:segment`) comes from a small index the client keeps in memory, while
what it *contains* (`:tile`, `:height`, `:mtime`) is the file itself.

### The Segment object

| Method | Returns | Description |
|---|---|---|
| `seg:id()` | string | the segment id, a 64-bit value as a decimal string — the identity |
| `seg:exists()` | bool | does the database still carry it? (a merge can fold one into another) |
| `seg:grid(sc)` | [`Grid`](#the-grid-object) \| nil | the grid at segment grid coord `{x, y}` — `nil` for no grid there *and* for one still loading |
| `seg:grids(area)` | `Grid[]` | every **loaded** grid in `{x, y, w, h}` of segment grid coords |
| `seg:markers(filter)` | [`Marker`](#the-marker-object)`[]` | the markers recorded in this segment |
| `seg:info()` | table | `{ id, current, markers }` — the snapshot escape hatch |

**You ask a segment for an area, never for a list.** There is no "every grid in this segment": the
client's own minimap does not enumerate either — it walks the grid coords of the rectangle it is
drawing, and so do you. `seg:grids` walks at most **1024** grid coords per call and refuses a bigger
rectangle rather than reading a thousand files behind your back.

```lua
local seg = hafen.map.segment()
local here = hafen.map.grid(hafen.world.gridPos().gridId)
local sc = here:sc()
for _, g in ipairs(seg:grids{ x = sc.x - 2, y = sc.y - 2, w = 5, h = 5 }) do
  local at = g:pos()                                 -- where to draw it, this session
  if at then draw(g, at) end
end
```

### The Grid object

| Method | Returns | Description |
|---|---|---|
| `grid:id()` | string | the **server's** grid id — the identity, and the anchor you may save |
| `grid:exists()` | bool | does the database carry this grid? |
| `grid:sc()` | `{x, y}` | its coord inside its segment |
| `grid:pos()` | `{x, y}` \| nil | its upper-left corner in **this session's** world coords; `nil` outside the player's current segment |
| `grid:segment()` | [`Segment`](#the-segment-object) | the segment it belongs to |
| `grid:tile(c)` | `{name, prio}` \| nil | the recorded tile at within-grid tile coord `{x, y}`, `0..99` |
| `grid:height(c)` | number \| nil | the recorded height there |
| `grid:mtime()` | number \| nil | when the client last recorded this grid, in milliseconds |
| `grid:image(lvl)` | image \| nil | its [minimap drawing](#drawings) at zoom level `lvl` (default `0`) |
| `grid:overlayImage(tag)` | image \| nil | one recorded [overlay mask](#drawings) drawn in its own colour |
| `grid:info()` | table | `{ id, seg, sc, pos?, mtime?, loaded, size }` — the snapshot escape hatch |

`grid:tile` gives you the tileset **resource name**, not a tile id: the live
[`hafen.world.tile`](world.md#terrain-and-coordinates) `id` is a session-local number, so the name is
the thing the two halves can be compared on — and they agree.

```lua
local p  = hafen.player():gob():pos()
local gp = hafen.world.gridPos()                     -- where the player is, anchored
local g  = hafen.map.grid(gp.gridId)
local c  = { x = math.floor(gp.x / 11), y = math.floor(gp.y / 11) }
print(g:tile(c).name, hafen.world.tile(p.x, p.y).name)   -- the same tileset
```

A within-grid tile coord is `0..99`; anything else is refused rather than read as a segment coord.

> **What you read is what the client wrote down, not what is there.** For the ground under the player
> it is current — the client re-records the grids around you as they change — and for somewhere you
> explored a year ago it is a year old. `grid:mtime()` is the honest answer to "how old is this".

### Saving a position

**Save the anchor, never the segment coordinate.** A segment id is bookkeeping this client invented,
and when two explored areas turn out to touch, the merge **rewrites** the loser's grid coords and every
marker inside it. A stored `seg` + `tc` would not go `nil` after that — it would point at the *wrong
place*, which is worse. A grid id comes from the server, means the same thing to every player, and no
merge ever moves it.

| Read it as | From | Then |
|---|---|---|
| `{gridId, x, y}` | [`hafen.world.gridPos()`](world.md#saving-a-world-position-across-sessions), [`marker:anchor()`](#the-marker-object) | **save this, send this** |
| segment id + tile coord | `seg:id()`, `marker:tc()`, `grid:sc()` | look at it, compare it this session, never store it |

An anchor goes back to a world position with
[`hafen.world.fromGridPos`](world.md#saving-a-world-position-across-sessions), and back into the
database with `hafen.map.grid(anchor.gridId)`.

## Overlays

Claims, village claims and provinces. They come in two halves that share only a word, and telling them
apart is most of what this section is for:

- the **recorded masks** — which tiles of a grid on disk an overlay covered when the client wrote that
  grid down. Read them off a [`Grid`](#the-grid-object).
- the **display toggles** — the switches in the client's own map menu that decide whether the overlay is
  drawn. Drive them through `hafen.map.overlay`.

### The recorded masks

| Method | Returns | Description |
|---|---|---|
| `grid:overlays()` | `string[]` \| nil | every overlay **tag** this grid carries, sorted; `nil` until the grid and its overlay resources have loaded |
| `grid:overlay(tag)` | [`Mask`](#the-mask-object) \| nil | which tiles that tag covers here; `nil` for a tag this grid does not carry |

**The tag space is open, so an unknown tag is `nil` and not an error.** An overlay's tags are declared by
its own resource on the server, and no client-side list of them can be complete — which is exactly why
`grid:overlays()` exists: it is the census that makes a `nil` readable. (The display toggles below are the
opposite: that set is the client's own, so a tag it does not own is refused.)

```lua
local g = hafen.map.grid(hafen.world.gridPos().gridId)
for _, tag in ipairs(g:overlays() or {}) do
  local m = g:overlay(tag)
  print(tag, m:count(), "tiles")                     -- e.g. "cplot 812 tiles"
end
```

Several overlay resources may carry the same tag — two neighbouring personal claims, say — and a mask is
the **union** of all of them, which is the same thing the client's own minimap paints for that tag.

### The Mask object

| Method | Returns | Description |
|---|---|---|
| `mask:tag()` | string | the overlay tag — half its identity; answers from the handle alone |
| `mask:grid()` | [`Grid`](#the-grid-object) | the recorded grid it belongs to — the other half |
| `mask:covers(c)` | bool \| nil | is the within-grid tile coord `{x, y}`, `0..99`, inside the overlay? |
| `mask:count()` | number \| nil | how many of the grid's 10 000 tiles it covers |
| `mask:area()` | `{x, y, w, h}` \| nil | the tight bounding box of those tiles, in within-grid tile coords |
| `mask:exists()` | bool | does that grid still carry this tag? |
| `mask:info()` | table | `{ tag, grid, count, area? }` — the snapshot escape hatch |

`mask:covers` takes the same `{x, y}` coord [`grid:tile`](#the-grid-object) does, so the two compose over
one loop. There is deliberately no call that hands you ten thousand booleans: `count` and `area` answer the
whole-mask questions without a table per tile, and `covers` answers the one you actually ask.

```lua
local m = g:overlay("cplot")
local c = { x = 40, y = 40 }
if m and m:covers(c) then print("that tile was inside a personal claim", g:tile(c).name) end
```

### The display toggles

The client's map menu owns exactly these, and the province one is **two different switches**:

| Tag | Where | What it draws |
|---|---|---|
| `cplot` | world | personal claims, on the ground in the 3D world |
| `vlg` | world | village claims, on the ground |
| `prov` | world | provinces, on the ground |
| `realm` | map | provinces, on the **map window** — drawn from the recorded masks |

> **`prov` and `realm` are the same feature and two engine tags.** The world draws provinces under `prov`;
> the map window draws them under `realm`. Writing one does not touch the other, and there is no tag that
> reaches both.

| Call | Returns | Description |
|---|---|---|
| `hafen.map.overlay(tag)` | bool \| nil | is this overlay displayed right now — by anyone? `nil` before the HUD is up |
| `hafen.map.overlay(tag, on)` | bool \| nil | `true` takes your **hold**, `false` releases it; answers the resulting displayed state |
| `hafen.map.overlays()` | table[] | all four: `{ tag, where, what, on, held }` |

A tag the client does not own is an **error**, not a silent no-op — the one failure here that nothing else
would ever report is a typo that quietly does nothing forever.

> **A write is a hold, not a switch.** The engine counts how many things want an overlay drawn: your
> addon, the user's own checkbox, and the server (which flashes claims on for a few seconds when you
> mouse over one). So `overlay(tag, false)` means *stop asking*, never *turn it off* — if the user's
> checkbox is on, it stays on. Your hold is **idempotent**: taking it twice is taking it once, because a
> single release has to be the whole undo.

The hold is an owned resource like a hidden window: **a `:reload`, a disable or a logout releases it for
you**, exactly once. Nothing an addon does can leave an overlay stuck on the screen.

```lua
hafen.map.overlay("cplot", true)                     -- show me the claims while I survey
-- …later
hafen.map.overlay("cplot", false)                    -- and stop asking
```

`held` in `hafen.map.overlays()` is *your* hold; `on` is what the screen is doing. They are different
questions, and only the first one is yours to answer.

## Drawings

The picture a player recognises: the square the corner minimap paints for a piece of ground. A grid can
render itself, and what you get back is an ordinary **image handle** — the same thing
[`hafen.asset`](asset.md) hands you for a PNG of your own.

| Call | Returns | Description |
|---|---|---|
| `grid:image(lvl)` | image \| nil | the recorded ground at zoom level `lvl` — `0` (the default) up to `8` |
| `grid:overlayImage(tag)` | image \| nil | one recorded [mask](#the-recorded-masks) drawn in the overlay's own colour; `nil` for a tag this grid does not carry |

Because it is an image handle, everything that already draws an image draws a map: `g:image` in your own
widget, [`hafen.render.sprite`](render/sprites.md), and a stylesheet's `bg = { image = … }`.

```lua
local g = hafen.map.grid(hafen.world.gridPos().gridId)
hafen.ui.window{ title = "Here", size = { 100, 100 },
                 onDraw = function(gc)
                   local img = g:image(0)              -- nil while it renders; ask again next frame
                   if img then gc:image(img, 0, 0) end
                 end }
```

### The first call renders, and answers nil

Drawing a grid means reading every one of its 10 000 tiles out of the tileset art — milliseconds, not
microseconds. So it happens **off the frame**, exactly where the client renders its own minimap, and the
[nil rule](#reads-answer-nil-until-the-disk-answers) covers it: the first call starts the render and
returns `nil`, a later one returns the handle. A draw callback that re-asks every frame is the intended
shape, and it costs nothing once the picture is there — the same `(grid, level)` hands back the *same*
handle, never a new render.

### A level is a scale, not a size

**Every drawing is 100×100 pixels, at every level.** What the level changes is how much ground fits in
that square:

| `lvl` | One pixel is | The square covers |
|---|---|---|
| `0` | one tile | 100×100 tiles — one grid |
| `1` | 2×2 tiles | 200×200 tiles — four grids |
| `n` | 2ⁿ×2ⁿ tiles | four times the ground of `n-1` |

Which is why neighbouring grids **share** a drawing above level 0: the four grids under one level-1 square
are one picture, and all four hand back the identical handle. Level 0 is drawn through the ground around
it, so tile transitions blend across the grid border just as they do on the corner minimap.

### The picture is yours, and it is bounded

A drawing is an **owned resource** like a loaded image: `img:dispose()` frees its texture now, and a
`:reload`, a disable or a logout frees everything you were holding. A disposed handle stays inert rather
than becoming an error — it still answers `:size()`, drawing it simply draws nothing, and the next
`grid:image(lvl)` renders a fresh one.

You do not have to manage it. The cache keeps the most recently asked-for drawings and **disposes what
falls off the end**, so a panel that scrolls across a continent frees the ground behind it by itself. That
is also why a panel should re-ask each frame rather than stash a handle for later: asking is what keeps a
picture alive.

> **It is not an asset.** A map drawing never appears in `hafen.asset()` and its `:path()` is a
> description, not a file you could load. An asset is a file your addon shipped; this is a picture the
> client drew of the database.

## Markers

Read, add and remove markers in the map database — the same markers the map window shows. Two kinds
exist: **player** markers, your own pins, which carry a name and a colour, and **system** markers, the
server and quest pins, which carry a name and an icon.

| Function | Returns | Description |
|---|---|---|
| `hafen.map.markers.list(filter)` | [`Marker`](#the-marker-object)`[]` | every marker matching the [filter](conventions.md#the-filter-argument) |
| `hafen.map.markers.nearest(filter)` | [`Marker`](#the-marker-object) \| nil | the closest match to the player, within your current segment |

Both answer an empty array or `nil` before the map database is ready, and neither throws. A string
filter matches the marker's name; a function filter is called with the Marker itself.

### The Marker object

| Method | Returns | Description |
|---|---|---|
| `marker:name()` | string \| nil | the label the map shows |
| `marker:type()` | string | `"player"` or `"system"` |
| `marker:anchor()` | `{gridId, x, y}` \| nil | the position you may **save or send**; `nil` while the grid it needs is still loading |
| `marker:tc()` | `{x, y}` | its segment tile coord — where it really lives in the database |
| `marker:segment()` | [`Segment`](#the-segment-object) | the segment it is recorded in |
| `marker:pos()` | `{x, y}` \| nil | its world position this session; `nil` outside your current segment |
| `marker:dist()` | number \| nil | how far the player is from it |
| `marker:color()` | [Color](types.md#color) \| nil | player markers only |
| `marker:onmap()` | bool \| nil | player markers only: also drawn on the main map |
| `marker:icon()` | string \| nil | system markers only: the icon resource name |
| `marker:exists()` | bool | is it still in the database? |
| `marker:info()` | [`Marker` snapshot](types.md#marker) | the snapshot escape hatch |

**`marker:anchor()` is how a marker leaves this client.** It converts the marker's own position — which
is client-local and re-based by a merge, see [above](#saving-a-position) — into the same
`{gridId, x, y}` [`hafen.world.gridPos`](world.md#saving-a-world-position-across-sessions) hands out.
For a marker in your current segment it answers straight away; for one in another explored area it has
to read that grid off the disk, so it answers `nil` and then answers.

```lua
local mark = hafen.map.markers.nearest("Camp")
local a = mark and mark:anchor()
if a then hafen.store.camp = a end                    -- survives the relog; means the same to a friend
```

### Write (ungated)

| Function | Returns | Description |
|---|---|---|
| `hafen.map.markers.add(name, x, y, opts)` | [`Marker`](#the-marker-object) \| nil | create a **player** marker at world `x, y`; `nil` when the map is not ready |
| `hafen.map.markers.remove(marker)` | bool | remove a marker `list`, `nearest` or `add` gave you; whether one was removed |

`opts` for `add`, all optional:

| Key | Type | Default | Meaning |
|---|---|---|---|
| `color` | `{r, g, b, a}`, `0..255` | gold | pin colour |
| `onmap` | bool | `false` | also show it on the main map |

> **These two verbs write, and they need no permission.** Unlike the [`hafen.act`](act.md) tier, adding
> and removing markers is not gated: it edits the user's own on-disk map database, which is
> client-local and reversible by hand. Remove only what your addon added.

The [`MarkersChanged`](events.md#roster-quests-markers) event, payload `{ count }`, fires on any add or
remove, including ones the player makes.

## Icon categories

Read and toggle the minimap icon registry — the same categories the client's Icon settings window edits.
A **category** is one kind of gob icon (a boar, a fir tree, a player) with two flags: `show`, draw it on
the minimap, and `notify`, play a sound and a chat message when one appears.

```lua
-- stop drawing boars, then put it back
local boars = hafen.map.icons("gfx/terobjs/mm/boar")
if boars then boars:show(false) end
-- …
if boars then boars:show(true) end
```

**`hafen.map.icons` is callable, and the argument splits by shape** — the same rule
[`hafen.menugrid`](menugrid.md) uses:

| Call | Returns | Description |
|---|---|---|
| `hafen.map.icons()` | `IconCat[]` | every category, in resource-name order |
| `hafen.map.icons(filter)` | `IconCat[]` | a name substring or a predicate — the canonical [filter](conventions.md#the-filter-argument) |
| `hafen.map.icons(res)` | `IconCat` \| nil | one category, by its icon **resource name** — the string with a `/` in it |

A category's **identity is its icon resource name**, so `hafen.map.icons(res)` hands back the same
interned object every time and `seen[cat] = true` works. There is no addressing by position: the
registry grows as the character sees new icon types, so a number is refused rather than pretended.

### The IconCat object

| Method | Returns | Description |
|---|---|---|
| `cat:res()` | string | the icon resource name — the identity; answers from the handle alone |
| `cat:name()` | string \| nil | the icon's tooltip, falling back to the resource name |
| `cat:exists()` | bool | is the registry still carrying this resource? |
| `cat:show()` / `cat:show(on)` | bool \| nil / self | draw it on the minimap — read, or write and chain |
| `cat:notify()` / `cat:notify(on)` | bool \| nil / self | sound and chat line when one appears |
| `cat:info()` | [`IconCategory`](types.md#iconcategory) \| nil | the snapshot escape hatch |

**Arity is the verb**: no argument reads, an argument writes and returns the category itself, so writes
chain — `cat:show(true):notify(true)`. A write to a resource the registry does not carry is an error,
not a silent no-op; `cat:exists()` is how you ask first.

```lua
for _, c in ipairs(hafen.map.icons(function(c) return c:res():find("borka") end)) do
  c:notify(true)                                   -- announce every player-type icon
end
```

> **These writes need no permission either.** They change a client-local display setting, nothing the
> server sees. They persist per character and take effect immediately, exactly as the settings window's
> checkboxes do — so a broad sweep rewrites configuration the user set by hand.

The registry is empty until the HUD is up, and it grows as the character sees new icon types. It changes
rarely, so there is no `*Changed` event — read it on demand.

> **A category is a resource.** Where an icon resource publishes several variants of itself, they are
> one category here and a write reaches all of them: the engine keys them by resource *plus* an opaque
> sub-id that no name could address, and on the minimap they are one thing to a player anyway.

## Identity

Segments, grids, masks, markers and icon categories are **interned objects**: `hafen.map.grid(id) ==
hafen.map.grid(id)`, `seg:grid(sc)` hands back that same grid, and any of them works as a table key.
Each holds only its id and re-reads the database on every call, so a stashed handle never goes stale —
it simply starts answering `nil` (and `:exists() == false`) if what it names goes away.

## See also

- [`hafen.world`](world.md) — the live half: terrain, the coordinate spaces, and the grid anchor
- [`Marker`](types.md#marker) — the snapshot `marker:info()` hands back
- [`IconCategory`](types.md#iconcategory) — what `cat:info()` hands back
- [coordinates](conventions.md#coordinates) — why the anchor is the only position worth storing
- [`hafen.gob`](gob.md) — `gob:icon()`, the category name on a live object
- [`hafen.asset`](asset.md) — the image handle a grid drawing is one of, and its `:size()`/`:dispose()`
- [events](events.md#roster-quests-markers) — `MarkersChanged`
