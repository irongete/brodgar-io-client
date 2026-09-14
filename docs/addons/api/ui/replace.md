# hafen.ui: Replacing Client Windows

Completely hide native client windows and substitute them with custom addon UI implementations.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- Intercept the native character sheet window when opened
session:ui():on("window[title=Character]", "Added", function(native_window)
  -- Hide the native window
  native_window:visible(false)

  -- Create custom substitute window
  local replacement_window = hafen.ui():window()
    :title("Custom Character Sheet")
    :size(240, 300)
    :position(native_window:position().x, native_window:position().y)

  -- Restore native window when replacement is closed
  replacement_window:on("Close", function()
    if native_window:exists() then
      native_window:visible(true)
    end
  end)
end)
```

---

## Best Practices

* Always monitor `:exists()` on the underlying native window.
* When your addon unloads or is disabled, restore `:visible(true)` on any native windows you hid so the user is never left without essential game interfaces.
