# hafen.map: the map database

The map you have **explored**. The client keeps it on disk and it outlives the session: your markers, and
the minimap icon settings that decide what is drawn on it. Reach for it to read or drop a pin, and to ask
or change which kinds of thing show up on the minimap.

```lua
local p = hafen.player():gob():pos()
local ref = hafen.map.markers.add("Camp", p.x, p.y, { color = {0, 200, 0}, onmap = true })
hafen.map.markers.remove(ref)
```

> **Recorded, not live.** Nothing on this page reads the terrain streamed around you — that is
> [`hafen.world`](world.md), which owns `tile`, `height`, `grid`, `gridPos` and the rest of the
> coordinate space. `hafen.map` is the database behind the map window and the corner minimap.
>
> **`hafen.markers` and `hafen.radar` are gone.** A marker lives in the map database, so it is
> `hafen.map.markers` with the surface it always had; the icon registry is `hafen.map.icons`, re-shaped
> (below) — the engine has no "radar", it has icon settings. Both old names read plain `nil`.

## Markers

Read, add and remove markers in the map database — the same markers the map window shows. Two kinds
exist: **player** markers, your own pins, which carry a name and a colour, and **system** markers, the
server and quest pins, which carry a name and an icon.

| Function | Returns | Description |
|---|---|---|
| `hafen.map.markers.list(filter)` | [`Marker`](types.md#marker)`[]` | every marker matching the [filter](conventions.md#the-filter-argument) |
| `hafen.map.markers.nearest(filter)` | [`Marker`](types.md#marker) \| nil | the closest match to the player, within your current segment |

Both answer an empty array or `nil` before the map database is ready, and neither throws.

### Write (ungated)

| Function | Returns | Description |
|---|---|---|
| `hafen.map.markers.add(name, x, y, opts)` | ref (number) \| nil | create a **player** marker at world `x, y`; `nil` when the map is not ready |
| `hafen.map.markers.remove(ref)` | bool | remove a marker by the ref `list` or `add` gave you; whether one was removed |

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

> **A marker's position is persistent, not session-local.** Its stable anchor is `seg`, the segment id,
> a string, plus `tc`, the segment tile coord — those survive a relog. The `x`, `y` and `dist` fields
> are session-local conveniences, present only while the marker is in your current segment.

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

## See also

- [`hafen.world`](world.md) — the live half: terrain, the coordinate spaces, and the grid anchor
- [`Marker`](types.md#marker) — the snapshot shape both marker readers return
- [`IconCategory`](types.md#iconcategory) — what `cat:info()` hands back
- [coordinates](conventions.md#coordinates) — why `seg` and `tc` are a marker's anchor
- [`hafen.gob`](gob.md) — `gob:icon()`, the category name on a live object
- [events](events.md#roster-quests-markers) — `MarkersChanged`
