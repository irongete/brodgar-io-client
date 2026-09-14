# hafen.ui: Modifying Client Windows

Edit captions, toggle checkboxes, inspect controls, or intercept interactions on existing native game windows.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- Listen for the native Options window opening
session:ui():on("window[title=Options]", "Added", function(options_window)
  -- Customize window title
  options_window:title("Game Options (Modded)")

  -- Find and rename a button inside the window
  local confirm_button = options_window:match("button[text=Apply]")
  if confirm_button then
    confirm_button:text("Save & Close")
  end
end)
```

---

## Modifying Visual Text (Unprotected)

Changing what client labels, buttons, or window title bars display is client-local and unprotected:

| Method | Parameters | Description |
|---|---|---|
| `widget:title(new_title)` | `string` | Sets the window title bar caption. Pass `nil` to restore original text. |
| `widget:text(new_text)` | `string` | Overrides the label text on buttons, checkboxes, or labels. Pass `nil` to restore. |

---

## Driving Client Controls (Protected)

Modifying the actual input state or values sent to the game server requires permissions:

| Method | Parameters | Permission | Description |
|---|---|---|---|
| `widget:value(new_value)` | `any` | `widget.value` | Sets control values (e.g. toggling a native checkbox or entering text in a native field). |
| `widget:send(msg, ...)` | `string, ...` | `widget.send` | Dispatches action messages directly from a client widget. |
