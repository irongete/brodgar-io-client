# hafen.ui: 2D Canvas Drawing

Methods available on the 2D graphics canvas context (`event:g()`) inside `Draw` event callbacks.

## Quick Example

```lua
window_handle:on("Draw", function(draw_event)
  local graphics = draw_event:g()
  local width = draw_event:w()
  local height = draw_event:h()

  -- Fill background
  graphics:color(20, 20, 20, 220)
  graphics:frect(0, 0, width, height)

  -- Draw border outline
  graphics:color(180, 150, 90)
  graphics:rect(0, 0, width, height)

  -- Render text
  graphics:color(255, 255, 255)
  graphics:text("Coordinates:", 8, 8)
end)
```

---

## Canvas Methods (`Graphics`)

| Method | Parameters | Description |
|---|---|---|
| `:color(r, g, b, [a])` | `number, number, number, [number]` | Sets the active paint color (`0..255`). Alpha defaults to `255` if omitted. |
| `:text(content, x, y)` | `string, number, number` | Renders cached text at widget-local pixel coordinates `(x, y)`. |
| `:line(x1, y1, x2, y2)` | `number, number, number, number` | Draws a 1-pixel line from `(x1, y1)` to `(x2, y2)`. |
| `:rect(x, y, w, h)` | `number, number, number, number` | Draws an unfilled rectangle border. |
| `:frect(x, y, w, h)` | `number, number, number, number` | Draws a solid filled rectangle. |
| `:image(asset_handle, x, y)` | `AssetHandle, number, number` | Draws an image asset loaded via `hafen.asset`. |
| `:resource(res_name, x, y)` | `string, number, number` | Draws a native game engine icon resource by path. |

---

## Text Rendering & Caching

### Text is cached across frames

Text rendered via `:text()` and measured via `hafen.ui():measure()` is cached across frames by `(string, font, width)`. Drawing identical text in the same font and dimensions reuses the existing raster texture, avoiding per-frame layout recalculations. Color tinting is applied dynamically over the cached raster without invalidating cache entries.
