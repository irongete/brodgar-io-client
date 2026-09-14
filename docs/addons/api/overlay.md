# Gob Overlays

Attach custom floating text labels, indicators, and canvas drawings directly to game objects in the 3D world. Overlays are purely visual, client-side, and **unprotected**.

## Quick Example

```lua
local session = hafen.session():current()
local player_gob = session and session:player():gob()

if player_gob then
  -- Attach a floating overhead text label
  player_gob:overlay():add("status_label")
    :text("VIP Player")
    :color({ 255, 215, 0 })
    :height(20) -- world units above ground

  -- Attach custom canvas graphics
  player_gob:overlay():add("target_ring"):draw(function(graphics, gob, screen_x, screen_y)
    graphics:color(255, 50, 50, 180)
    graphics:frect(screen_x - 4, screen_y - 4, 8, 8)
  end)
end
```

---

## Methods on `gob:overlay()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:add(overlay_key)` | `string` | `Overlay` | Attaches a new overlay (or updates an existing one with `overlay_key`). |
| `:remove(overlay_key)`| `string` | `self` | Removes the overlay with the given key. |
| `:get(overlay_key)` | `string` | `Overlay \| nil`| Retrieves an active overlay by key. |
| `:list()` | None | `Overlay[]` | Array of all active overlays on this object. |

---

## Methods on `Overlay`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:text(label_string)` | `string` | `self` | Renders a fast engine-cached text label above the object. |
| `:color(color_table)` | `{r, g, b, [a]}` | `self` | Sets the text color. |
| `:height(z_offset)` | `number` | `self` | Vertical height in world units above object base (default `15`). |
| `:offset(dx, dy)` | `number, number` | `self` | 2D pixel offset from the projected screen coordinate. |
| `:draw(render_fn)` | `function(graphics, gob, sx, sy)` | `self` | Custom per-frame drawing function. |
| `:remove()` | None | `self` | Detaches and destroys this overlay. |
