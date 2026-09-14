# Window Chrome Styling

Customize title bars, borders, close buttons, and window frames.

## Quick Example

```lua
local style_engine = hafen.ui():style()

-- Style all window frames
style_engine:rule("window", {
  chrome = {
    title_bar_color = { 35, 35, 45, 255 },
    border_color = { 90, 90, 110, 255 },
    close_button = {
      image = "assets/icons/close.png"
    }
  }
})
```

---

## Chrome Properties

| Property | Value Type | Description |
|---|---|---|
| `title_bar_color` | `{r, g, b, [a]}` | Background color of the top draggable title bar. |
| `title_color` | `{r, g, b, [a]}` | Text color of the window title caption. |
| `border_color` | `{r, g, b, [a]}` | Outline color around the window frame. |
| `close_button` | `table` | Configuration dictionary for the window dismiss button (`@close`). |
