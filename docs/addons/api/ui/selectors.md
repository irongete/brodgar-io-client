# UI Selectors

CSS-like query grammar for finding and inspecting widgets in the client's UI tree via `session:ui():match(selector)`.

## Quick Example

```lua
local session = hafen.session():current()
if not session then return end

-- Find the inventory backpack window
local inventory_window = session:ui():match("window[title=Inventory]")

-- Find a specific button inside a craft window
local craft_button = session:ui():match("window[title=Craft] button[text=Craft]")

-- Find all open windows on screen
local open_windows = session:ui():matchAll("window")
hafen.log():write("Total open windows: " .. #open_windows)
```

---

## Selector Syntax Reference

| Selector Pattern | Matches | Example |
|---|---|---|
| `class_name` | Matches widgets by internal class name (case-insensitive). | `"window"`, `"button"`, `"label"`, `"checkbox"` |
| `[attribute=value]`| Filters widgets matching an attribute property. | `"[title=Equipment]"`, `"[text=Done]"` |
| `class[attr=val]` | Combines class and attribute filters. | `"window[title=Inventory]"` |
| `parent child` | Descendant combinator (matches any child inside parent). | `"window[title=Craft] button"` |
| `@role` | Matches widgets by assigned UI semantic role. | `"@close"` (window close button) |

---

## Testing Selectors In-Game

You can discover and test selectors interactively using the maintainer's inspector tool:
* Type `:widgetstack` in the console.
* Hover over any UI element.
* Press the `freeze` key to lock inspection and copy the generated selector path directly.
