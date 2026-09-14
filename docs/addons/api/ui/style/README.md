# hafen.ui: Stylesheet Engine

Define and apply CSS-like visual rules to restyle native game windows and custom addon widgets.

## Quick Example

```lua
local style_engine = hafen.ui():style()

-- Apply dark theme to all Inventory windows
style_engine:rule("window[title=Inventory]", {
  background_color = { 20, 20, 25, 230 },
  border_color = { 180, 150, 90, 255 },
  padding = 8
})
```

---

## Styling Reference Pages

| Subsystem | Reference Page | Description |
|---|---|---|
| **Property Keys** | **[keys.md](keys.md)** | Full dictionary of supported stylesheet property keys and values. |
| **Window Chrome** | **[chrome.md](chrome.md)** | Styling window frames, title bars, borders, and close buttons. |
| **Geometry** | **[geometry.md](geometry.md)** | Margins, padding, sizing constraints, and layout alignment. |
| **Surfaces** | **[surfaces.md](surfaces.md)** | Solid fills, alpha blending, textures, and gradient surfaces. |
| **Text Styling** | **[text.md](text.md)** | Fonts, text colors, font sizes, and line wrapping. |
| **HUD & Chat** | **[hud.md](hud.md)**, **[chat.md](chat.md)** | Restyling native HUD bars, meters, and chat windows. |
