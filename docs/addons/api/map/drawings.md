# hafen.map: Minimap Drawings

Render square 100×100 minimap images of recorded ground tiles at various zoom levels.

## Quick Example

```lua
local session = hafen.session():current()
local player_position = session and session:player():gob():position()

if player_position then
  local current_grid = hafen.map():grid():at(player_position)
  if current_grid then
    -- Request rendered 100x100 tile image (renders asynchronously off-thread)
    local grid_image = current_grid:image(0)
    if grid_image then
      hafen.log():write("Grid map image is ready to render.")
    else
      hafen.log():write("Grid map image is generating in background...")
    end
  end
end
```

---

## Methods on `MapGrid`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:image([zoom_level])` | `[number]` | `AssetHandle \| nil` | Renders a 100×100 pixel image of the ground. `zoom_level` ranges from `0` (1 tile/pixel) to `8`. Returns `nil` while generating. |
| `:overlayImage(tag)` | `string` | `AssetHandle \| nil` | Returns an image mask of recorded claim/village boundaries on this grid. |

> Rendering occurs asynchronously off the render thread. On the first call, `:image()` initiates rendering and returns `nil`; subsequent calls return the cached image handle once available.
