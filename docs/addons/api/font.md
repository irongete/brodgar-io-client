# hafen.font: Fonts & Text Measurement

Load custom TrueType fonts shipped with your addon and measure text rendering bounds.

## Quick Example

```lua
-- Load custom TrueType font from addon assets/ directory
local custom_font = hafen.font():load("assets/fonts/Inter-Regular.ttf", "Inter")

-- Set size and measure text dimensions
local font_face = custom_font:size(14)
local text_bounds = font_face:measure("Hello Haven")

hafen.log():write(string.format("Rendered size: %dpx wide, %dpx tall", text_bounds.w, text_bounds.h))
```

---

## Methods on `hafen.font()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:load(asset_path, [family_name])` | `string, [string]` | `Font` | Loads a `.ttf` font from your addon directory and registers `family_name`. |
| `:get(family_name)` | `string` | `Font \| nil` | Finds a registered font family by name. |
| `:stock()` | None | `Font` | Returns the client's default system font. |

---

## Methods on `Font` / `FontFace`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:size(pixel_size)` | `number` | `FontFace` | Returns a face instance configured for `pixel_size` design pixels. |
| `:measure(text_string)` | `string` | `{w, h}` | Calculates rendered width and height for `text_string` in pixels. |
| `:height()` | None | `number` | Line height of the font face in pixels. |
