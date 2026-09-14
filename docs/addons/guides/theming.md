# Theming and UI Styling

Addons can customize the visual styling of both native client windows and custom addon widgets using the client's stylesheet engine.

---

## 1. Defining Style Rules

Apply style rules targeting specific widget classes, roles, or custom addon elements using [`hafen.ui():style()`](../api/ui/style/README.md):

```lua
local style_manager = hafen.ui():style()

-- Style all Inventory windows
style_manager:rule("window[title=Inventory]", {
  background_color = {25, 25, 30, 220},
  border_color = {180, 150, 90, 255},
  padding = 8
})

-- Style all buttons inside your custom addon
style_manager:rule("button.my_addon_button", {
  background_color = {40, 60, 90, 255},
  text_color = {255, 255, 255, 255},
  hover = {
    background_color = {60, 90, 140, 255}
  }
})
```

---

## 2. Applying Custom Fonts

Load custom TTF fonts shipped with your addon using [`hafen.font()`](../api/font.md):

```lua
-- Register font family from addon assets/ folder
local custom_font = hafen.font():load("assets/fonts/Inter-Regular.ttf", "Inter")

-- Apply custom font to UI labels
hafen.ui():style():rule("label", {
  font_family = "Inter",
  font_size = 13
})
```

---

## 3. Styling Custom Addon Widgets

Assign classes or names to your custom widgets so style rules can target them cleanly:

```lua
local window_handle = hafen.ui():window()
  :title("Styled Window")
  :name("my_addon/main_window")

local action_button = hafen.ui():button()
  :text("Confirm")
  :parent(window_handle)
  :name("my_addon_button")
```

All stylesheet modifications revert cleanly when your addon is reloaded or disabled.
