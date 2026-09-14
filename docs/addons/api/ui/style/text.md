# Typography & Text Styling

Configure fonts, text colors, sizes, and font families in style rules.

## Quick Example

```lua
hafen.ui():style():rule("label.header_text", {
  font_family = "Inter",
  font_size = 16,
  text_color = { 255, 215, 0, 255 }
})
```

---

## Text Properties

| Property | Value Type | Description |
|---|---|---|
| `font_family` | `string` | Name of a registered font family loaded via `hafen.font`. |
| `font_size` | `number` | Point size in pixels. |
| `text_color` | `{r, g, b, [a]}` | Text foreground color. |
| `text_align` | `string` | Horizontal alignment (`"left"`, `"center"`, `"right"`). |
