# hafen.ui: Mouse Pointer

Query mouse pointer coordinates, hover hit-testing, 3D object picking, cursor overrides, and modal input grabs.

```lua
local mouse_pointer = hafen.ui():mouse()

local screen_x = mouse_pointer:x()
local screen_y = mouse_pointer:y()
hafen.log():write(string.format("Cursor at (%d, %d)", screen_x, screen_y))

-- Deepest UI widget currently under the cursor
local hovered_widget = mouse_pointer:over()
if hovered_widget then
  hafen.log():write("Hovering over widget: " .. hovered_widget:type())
end

-- Subscribe to 3D world object picking
local pick_subscription = mouse_pointer:on("PickChanged", function(hovered_gob)
  if hovered_gob then
    hafen.log():write("Pointing at: " .. (hovered_gob:name() or "Gob " .. hovered_gob:id()))
  end
end)
```

---

## Methods on `Mouse` (`hafen.ui():mouse()`)

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:x()` | None | `number` | Pointer X coordinate in root design pixels. |
| `:y()` | None | `number` | Pointer Y coordinate in root design pixels. |
| `:over()` | None | `Widget \| nil` | The deepest UI widget currently under the pointer. |
| `:pick()` | None | `Gob \| nil` | The world object under the pointer (`nil` if armed pass is off, or over UI/sky). |
| `:ground()` | None | `Position \| nil` | World ground coordinate under pointer. |
| `:on("PickChanged", fn)` | `string, function` | `Subscription` | Arms ID-buffer GPU picking and fires `fn(gob)` when the hovered object changes. |
| `:cursor([res_or_name])` | `[string]` | `string \| nil` | Overrides the mouse cursor (`"hand"`, `"flag"`, `"atk"`, `"wrench"`, or resource path). Pass `nil` to clear. |
| `:grab()` | None | `Grab` | Begins modal pointer capture (suppresses map panning and click-through). |
| `:shift()` | None | `boolean` | `true` if the Shift modifier key is pressed. |
| `:ctrl()` | None | `boolean` | `true` if the Ctrl modifier key is pressed. |
| `:alt()` | None | `boolean` | `true` if the Alt modifier key is pressed. |

---

## Pointer Grab (`mouse:grab()`)

Modal capture for dragging or ground targeting tools:

```lua
local grab_handle = hafen.ui():mouse():grab()

grab_handle:on("Move", function(grab_event)
  hafen.log():write(string.format("Dragged to (%d, %d)", grab_event:x(), grab_event:y()))
end)

grab_handle:on("Up", function(grab_event)
  hafen.log():write("Released mouse button: " .. grab_event:button())
end)

-- To release early:
grab_handle:release()
```

---

## Hit-Testing Methods on `hafen.ui()`

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `hafen.ui():hit(x, y)` | `number, number` | `Widget \| nil` | Deepest visible widget containing screen coordinates `(x, y)`. |
| `hafen.ui():tipAt(x, y)`| `number, number` | `Widget \| nil` | Widget whose tooltip is active at screen coordinates `(x, y)`. |
| `hafen.ui():scale()` | None | `number` | The running UI scaling factor in force. |
| `hafen.ui():measure(text, [opts])` | `string, [table]` | `{w, h}` | Measures rendered text dimensions in design pixels. |

---

## See Also

- [2D Drawing](drawing.md) — Coordinates and design pixel measurements.
- [Widgets](widget.md) — UI hierarchy and widget hit boundaries.
