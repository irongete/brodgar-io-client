# HUD Styling

Restyle the visual appearance of native HUD vital meters, stamina bars, and buff indicators.

## Quick Example

```lua
local style_engine = hafen.ui():style()

-- Restyle native meter gauges
style_engine:rule("meter[name=stamina]", {
  background_color = { 20, 20, 20, 200 },
  border_color = { 80, 160, 220, 255 },
  border_width = 1
})
```

---

## Selectable HUD Targets

* `meter[name=health]`
* `meter[name=stamina]`
* `meter[name=energy]`
* `meter[name=water]`
* `buff` (individual status effect icon boxes)
