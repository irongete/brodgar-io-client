# Chat Window Styling

Restyle chat window backgrounds, message text fonts, colors, and channel tabs.

## Quick Example

```lua
local style_engine = hafen.ui():style()

-- Restyle main chat window frame
style_engine:rule("window.chat", {
  background_color = { 10, 10, 15, 210 },
  border_color = { 60, 60, 80, 255 }
})

-- Restyle chat message font
style_engine:rule("chat_message", {
  font_family = "Inter",
  font_size = 12
})
```
