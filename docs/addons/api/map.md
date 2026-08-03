# hafen.map: the map database

The map you have **explored**. The client keeps it on disk and it outlives the session: the ground you
have walked over, cut into segments and grids, your markers, and the minimap icon settings that decide
what is drawn on it. Reach for it to read or drop a pin, to ask what the client wrote down about a piece
of ground, and to turn a position into something you can save or send.

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

Segments, grids, markers and icon categories are **interned objects**: `hafen.map.grid(id) ==
hafen.map.grid(id)`, `seg:grid(sc)` hands back that same grid, and any of them works as a table key.
Each holds only its id and re-reads the database on every call, so a stashed handle never goes stale —
it simply starts answering `nil` (and `:exists() == false`) if what it names goes away.

## See also

- [`hafen.world`](world.md) — the live half: terrain, the coordinate spaces, and the grid anchor
- [`Marker`](types.md#marker) — the snapshot `marker:info()` hands back
- [`IconCategory`](types.md#iconcategory) — what `cat:info()` hands back
- [coordinates](conventions.md#coordinates) — why the anchor is the only position worth storing
- [`hafen.gob`](gob.md) — `gob:icon()`, the category name on a live object
- [events](events.md#roster-quests-markers) — `MarkersChanged`
