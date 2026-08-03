# hafen.radar: minimap icon categories

Read and toggle the minimap icon registry — the same categories the client's Icon settings window
edits. A **category** is one kind of gob icon (a boar, a fir tree, a player) with two flags: `show`,
draw it on the minimap, and `notify`, play a sound and a chat message when one appears.

```lua
-- notify me whenever a player-type icon appears
local n = hafen.radar.setNotify(function(c) return c.res:find("borka") ~= nil end, true)
hafen.log("armed notify on " .. n .. " categories")
```

The registry is empty until the HUD is up, and it grows as the character sees new icon types. It
changes rarely, so there is no `*Changed` event — read it on demand.

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.radar.categories(filter)` | [`RadarCategory`](types.md#radarcategory)`[]` | every category matching the [filter](conventions.md#the-filter-argument) |

Answers an empty array before the HUD exists. It does not throw.

## Write (ungated)

| Function | Returns | Description |
|---|---|---|
| `hafen.radar.setVisible(filter, on)` | number | set `show = on` on every matching category; how many matched |
| `hafen.radar.setNotify(filter, on)` | number | set `notify = on` on every matching category; how many matched |

Both return `0` rather than throwing when nothing matches, or when the registry is still empty.

> **These two verbs write, and they need no permission.** Unlike the [`hafen.act`](act.md) tier, they
> are not gated: they change a client-local display setting, nothing the server sees. They are
> persisted per character and take effect immediately, exactly as the settings window's checkboxes do —
> so a broad filter rewrites configuration the user set by hand.

## See also

- [`RadarCategory`](types.md#radarcategory) — the snapshot shape `categories` returns
- [the `filter` argument](conventions.md#the-filter-argument) — what all three functions accept
- [`hafen.markers`](markers.md) — the other client-local surface an addon may write ungated
- [`hafen.gob`](gob.md) — `gob:icon()`, the category name on a live object
