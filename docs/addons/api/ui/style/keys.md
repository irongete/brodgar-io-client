# Stylesheet Property Keys

Complete dictionary of supported CSS-like styling properties accepted by `hafen.ui():style():rule(selector, properties)`.

---

## 1. Backgrounds & Surfaces

| Key | Accepted Value | Description |
|---|---|---|
| `background_color` | `{r, g, b, [a]}` | Solid background fill color (`0..255`). |
| `background_image` | `AssetHandle \| string` | Image asset texture applied to background. |
| `opacity` | `number` | Alpha transparency multiplier `0.0..1.0`. |

---

## 2. Borders

| Key | Accepted Value | Description |
|---|---|---|
| `border_color` | `{r, g, b, [a]}` | Border outline color. |
| `border_width` | `number` | Border thickness in design pixels. |
| `border_radius`| `number` | Corner rounding radius in pixels. |

---

## 3. Spacing & Dimensions

| Key | Accepted Value | Description |
|---|---|---|
| `padding` | `number \| table` | Inner padding in pixels (single number or `[top, right, bottom, left]`). |
| `margin` | `number \| table` | Outer margin spacing around widget. |
| `min_width` | `number` | Minimum width constraint in design pixels. |
| `min_height`| `number` | Minimum height constraint in design pixels. |

---

## 4. Typography

| Key | Accepted Value | Description |
|---|---|---|
| `font_family` | `string` | Registered font family name (loaded via `hafen.font`). |
| `font_size` | `number` | Font point size in pixels. |
| `text_color` | `{r, g, b, [a]}` | Text rendering color. |

---

## 5. State Overrides (`hover`, `active`, `disabled`)

Define interactive pseudo-state styling rules:

```lua
hafen.ui():style():rule("button.action_btn", {
  background_color = { 50, 70, 100 },
  text_color = { 255, 255, 255 },
  hover = {
    background_color = { 70, 100, 150 }
  },
  active = {
    background_color = { 30, 50, 80 }
  },
  disabled = {
    background_color = { 40, 40, 40 },
    text_color = { 120, 120, 120 }
  }
})
```
