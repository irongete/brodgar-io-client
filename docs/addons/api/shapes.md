# Data Shapes

Standard table shapes and structures used across the `hafen.*` API for coordinates, dimensions, bounds, colors, and margins.

---

<a id="coordinates"></a>
## 1. Coordinates and Dimensions

### Point / Coordinate (`{x, y}`)
Represents a 2D position in pixels or grid coordinates:
```lua
local position_shape = { x = 120, y = 80 }
```

### Dimensions (`{w, h}`)
Represents width and height in design pixels:
```lua
local size_shape = { w = 240, h = 160 }
```

### Rectangle / Box (`{x, y, w, h}`)
Combines position and size into a bounding box:
```lua
local bounding_box = { x = 10, y = 10, w = 180, h = 40 }
```

---

<a id="colours"></a>
## 2. Colors (`{r, g, b, [a]}`)

Colors are represented as numerical arrays with values between `0` and `255`:
* Red, Green, Blue: `0..255`
* Alpha (optional): `0..255` (defaults to `255` fully opaque if omitted).

```lua
local solid_red = { 255, 0, 0 }
local translucent_dark = { 20, 20, 20, 180 }
local gold_accent = { 255, 215, 0, 255 }
```

---

## 3. Padding and Margins

In stylesheet definitions and layout containers, padding can be specified as:
* A single number: applied equally to all four edges (`padding = 8`).
* A 4-element array: `[top, right, bottom, left]` (CSS order).

```lua
local custom_padding = { 10, 15, 10, 15 } -- 10px vertical, 15px horizontal
```
