# Style Geometry & Alignment

Configure layout dimensions, sizing constraints, padding, and alignment properties in style rules.

## Quick Example

```lua
hafen.ui():style():rule("column.sidebar", {
  min_width = 180,
  max_width = 300,
  padding = { 12, 8, 12, 8 }, -- top, right, bottom, left
  gap = 6
})
```

---

## Geometry Properties

| Property | Value Type | Description |
|---|---|---|
| `width` / `height` | `number` | Explicit dimensions in design pixels. |
| `min_width` / `min_height`| `number` | Minimum sizing constraint. |
| `max_width` / `max_height`| `number` | Maximum sizing constraint. |
| `padding` | `number \| table` | Inner padding spacing. |
| `margin` | `number \| table` | Outer margin spacing. |
| `gap` | `number` | Child spacing inside layout containers. |
