# Surface Fills & Textures

Configure background fills, textures, opacity, and borders.

## Quick Example

```lua
hafen.ui():style():rule("window.dark_theme", {
  background_color = { 15, 15, 20, 240 },
  border_color = { 120, 100, 60, 255 },
  border_width = 1,
  border_radius = 4
})
```

---

## Surface Properties

| Property | Value Type | Description |
|---|---|---|
| `background_color` | `{r, g, b, [a]}` | Solid color fill. |
| `background_image` | `string \| AssetHandle` | Repeating or stretched background image. |
| `border_color` | `{r, g, b, [a]}` | Outline stroke color. |
| `border_width` | `number` | Stroke thickness in design pixels. |
| `border_radius`| `number` | Corner curve radius. |
