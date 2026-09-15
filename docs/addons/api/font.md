# hafen.font: Built-in Fonts & Typography

Access the client's built-in typography faces and configure font handles for custom UI rendering and sheet styling.

```lua
-- Obtain a built-in font face and derive a styled variant
local base_font = hafen.font():get("serif")
local header_font = base_font:derive():size(14):bold(true):color({255, 230, 180})

local window = hafen.ui():window():title("Font Demo"):size(240, 120):font(header_font)
window:on("Draw", function(draw_event)
  local graphics = draw_event:g()
  graphics:text("Styled text output", 10, 10)
end)
```

---

## The Built-in Font Collection (`hafen.font()`)

The section object `hafen.font()` is a collection holding the client's four built-in typography faces: `"sans"`, `"serif"`, `"mono"`, and `"fraktur"`. Built-in fonts have engine lifetime, are interned per addon, and carry no file paths.

> Custom `.ttf` and `.otf` fonts shipped with your addon are loaded through [`hafen.asset():get("fonts/Inter.ttf")`](asset/README.md).

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:get(name)` | `string` | `FontHandle` | Interned handle for `"sans"`, `"serif"`, `"mono"`, or `"fraktur"`. |
| `:list()` | None | `FontHandle[]` | Array of the client's four built-in font handles. |
| `:count()` | None | `number` | Total number of built-in fonts (`4`). |
| `:find(name)` | `string` | `FontHandle \| nil` | Finds a built-in font by name, or `nil`. |

---

## Methods on `FontHandle`

A font handle represents an immutable, interned typography face. Calling `:derive()` creates a writable draft variant whose properties can be chained before being handed to a widget, stylesheet, or draw call. Once handed over, the variant is sealed.

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:derive()` | None | `FontHandle` | Creates a fresh draft variant derived from this font. |
| `:family()` | None | `string` | AWT font family name (e.g. `"SansSerif"`, `"Serif"`, `"Inter"`). |
| `:type()` | None | `string` | Kind of handle: `"font"` for built-ins and variants, or `"font_asset"` for loaded files. |
| `:size([px])` | `[number]` | `number \| self` | Reads or writes design pixel size (1 to 512 px). `nil` resets to surface default. |
| `:bold([flag])` | `[boolean]` | `boolean \| self` | Reads or writes bold face style. |
| `:italic([flag])` | `[boolean]` | `boolean \| self` | Reads or writes italic face style. |
| `:aa([flag])` | `[boolean]` | `boolean \| self` | Reads or writes antialiasing flag. `nil` resets to surface default. |
| `:color([rgb])` | `[table]` | `table \| self` | Reads or writes text color (`{r, g, b, a}`). Own drawing only. |
| `:outline([rgb])` | `[table]` | `table \| self` | Reads or writes 1px raster outline color. `nil` removes outline. |
| `:info()` | None | `table` | Plain table snapshot `{ type, family, size, bold, italic, aa, color, outline, path? }`. |

> **Note on Color and Outline:** `:color()` and `:outline()` apply only to your own drawing (`g:text`, `widget:font`). They are refused when handed to a client stylesheet rule (`rule:font()`) or `widget:rule()`, as client surfaces define color on the rule itself.

---

## Text Measurement

Text measurement is performed on `hafen.ui()` using the font handle:

```lua
local text_box = hafen.ui():measure("Sample Label", { font = header_font })
hafen.log():write(string.format("Box: %d x %d px", text_box.w, text_box.h))
```

---

## See Also

- [`hafen.asset`](asset/README.md) — Loading custom TrueType font assets.
- [Stylesheet Typography](ui/style/text.md) — Applying fonts to client surfaces via stylesheet rules.
- [2D Drawing](ui/drawing.md) — Rendering text with `g:text` and `g:atext`.
