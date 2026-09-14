# hafen.map: Minimap Icon Categories

Read and configure minimap icon categories, visibility toggles, and detection audio alerts.

## Quick Example

```lua
local icon_manager = hafen.map():icon()

-- Enable sound alerts for player minimap icons
local player_icon_cat = icon_manager:get("gfx/hud/mmap/borka")
if player_icon_cat and player_icon_cat:exists() then
  player_icon_cat:notify(true)
    :show(true)
end
```

---

## Methods on `hafen.map():icon()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:get(resource_name)` | `string` | `IconCat \| nil` | Finds an icon category by exact resource path. |
| `:list(filter?)` | `[string \| function]` | `IconCat[]` | All discovered icon categories. |
| `:count()` | None | `number` | Total number of icon categories in the registry. |

---

## Methods on `IconCat`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:res()` | None | `string` | Icon resource path identifier. |
| `:name()` | None | `string \| nil` | Display name from the icon's tooltip. |
| `:show()` | None | `boolean \| nil`| `true` if this icon type is rendered on the minimap. |
| `:show(is_shown)` | `boolean` | `self` | Toggles whether this icon appears on the minimap. |
| `:notify()` | None | `boolean \| nil`| `true` if audio/chat alerts fire when this icon spawns. |
| `:notify(should_notify)`| `boolean` | `self` | Toggles detection notifications for this icon. |
| `:exists()` | None | `boolean` | `true` if category is valid in the client registry. |
