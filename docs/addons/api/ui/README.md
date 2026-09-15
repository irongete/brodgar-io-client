# hafen.ui: User Interface Subsystem

Create custom windows, compose native control layouts, inspect the client's widget tree, and paint custom 2D graphics.

## Quick Example

```lua
-- Create a custom window with layout and button
local custom_window = hafen.ui():window()
  :title("My Control Panel")
  :position(100, 100)

local root_column = hafen.ui():column()
  :gap(6)
  :parent(custom_window)

local action_button = hafen.ui():button()
  :text("Trigger Action")
  :parent(root_column)

action_button:on("Click", function()
  hafen.log():write("Action button clicked!")
end)

custom_window:pack()
```

---

## UI Subsystems Navigation

### Core Widget Model
| Subsystem | Reference Page | Description |
|---|---|---|
| **Widget Object** | **[widget.md](widget.md)** | Core `Widget` methods, dimensions, hierarchy, and visibility. |
| **Owned vs. Borrowed** | **[writes.md](writes.md)** | Unprotected local writes vs. protected server-bound actions on widgets. |

### Custom UI & Graphics
| Subsystem | Reference Page | Description |
|---|---|---|
| **Custom Windows** | **[custom.md](custom.md)** | Creating draggable windows and custom canvas surfaces. |
| **Layout Containers**| **[column.md](column.md)** | Auto-flowing vertical columns and horizontal rows (`:pack()`). |
| **Standard Controls**| **[controls/README.md](controls/README.md)** | Buttons, checkboxes, labels, text entries, and images. |
| **Listboxes** | **[lists.md](lists.md)** | Scrollable list selection widgets (`hafen.ui():listbox()`). |
| **2D Drawing** | **[drawing.md](drawing.md)** | Direct 2D canvas drawing surface (`graphics:text`, `:rect`, `:frect`, `:image`). |
| **HUD Overlays** | **[overlay.md](overlay.md)** | Full-screen click-through 2D rendering overlay planes over the screen. |
| **Pixel Buffers** | **[pixels.md](pixels.md)** | Direct byte buffer manipulation for procedural images and textures. |
| **Stylesheets** | **[style/README.md](style/README.md)**| Styling rules, colors, borders, and window chrome. |

### Native Window Integration
| Subsystem | Reference Page | Description |
|---|---|---|
| **Selectors** | **[selectors.md](selectors.md)** | CSS-like queries to find widgets in the client tree (`s:ui():match(...)`). |
| **Native Widgets** | **[native.md](native.md)** | Repositioning, resizing, hiding, and remembering native window layouts. |
| **Driving Controls** | **[edit.md](edit.md)** | Modifying captions and simulating input (`widget.value`, `widget.send`). |
| **Replacing Windows** | **[replace.md](replace.md)** | Hiding native client windows and substituting custom UI implementations. |

### Items & Containers
| Subsystem | Reference Page | Description |
|---|---|---|
| **Items & Inventory**| **[items.md](items.md)** | Backpack grids, inventory slots, dragging, and transferring items. |
| **Item Contents** | **[contents.md](contents.md)** | Nested containers (bags, stacks) and fluid volumes (buckets, barrels). |
| **Container Events** | **[container.md](container.md)** | Subscribing to `"ItemAdded"`, `"ItemRemoved"`, and container close events. |

### Input & Interaction
| Subsystem | Reference Page | Description |
|---|---|---|
| **Mouse Pointer** | **[mouse.md](mouse.md)** | Query cursor coordinates, mouse button states, and widget hit-testing. |

