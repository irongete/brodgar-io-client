# hafen.ui: The Widget Object

The `Widget` object represents any visual element in the game client—including windows, buttons, text fields, containers, and custom canvas surfaces.

## Quick Example

```lua
local session = hafen.session():current()
local inventory_window = session and session:ui():match("window[title=Inventory]")

if inventory_window and inventory_window:exists() then
  local window_size = inventory_window:size()
  hafen.log():write(string.format("Inventory window dimensions: %dx%d", window_size.w, window_size.h))

  -- Check if inventory has focus
  if inventory_window:focused() then
    hafen.log():write("Inventory window is currently focused.")
  end
end
```

---

<a id="methods-on-widget"></a>
## Read Methods

| Method | Returns | Description |
|---|---|---|
| `:type()` | `string` | Internal class name (e.g. `"Window"`, `"Label"`, `"Button"`, `"Inventory"`). |
| `:role()` | `string \| nil` | Semantic selector role classification. |
| `:id()` | `number \| nil` | Server widget ID (or `nil` for client-only widgets). |
| `:res()` | `string \| nil` | Resource path associated with this widget. |
| `:owned()` | `boolean` | `true` if this widget was created by your addon. |
| `:exists()` | `boolean` | `true` if this widget is currently attached to the UI tree. |
| `:parent()` | `Widget \| nil` | The enclosing parent widget (`nil` if root). |
| `:children()` | `WidgetCollection` | The child widgets in tree order, as a collection (`:list()`, `:count()`, `:find()`); empty for a leaf. |
| `:position()` | `{x, y}` | Position within parent in design pixels. |
| `:size()` | `{w, h}` | The box `:size(w, h)` writes, in design pixels: a window's content area, any other widget's whole box. |
| `:chrome()` | `table \| nil` | On a window wearing the client's decoration: `frame` (`{w, h}`, the outer box), `content` (`{x, y, w, h}`, the content area inside it), and `caption`, `plate`, `sizer`, `close` once each ornament has drawn. `nil` elsewhere. |
| `:rootPos()` | `{x, y}` | The widget's top-left in root design pixels; with `:chrome().frame` it boxes a whole window. |
| `:visible()` | `boolean` | Visibility state. |
| `:enabled()` | `boolean` | Input enabled state. |
| `:text()` | `string \| nil` | Text content (on labels, buttons, windows, text entries). |
| `:tooltip()` | `string \| nil` | Hover tooltip text. |
| `:focused()` | `boolean` | `true` if this widget currently holds keyboard focus. |

---

## Write Methods

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:position(x, y)` | `number, number` | `self` | Repositions the widget within its parent. |
| `:size(w, h)` | `number, number` | `self` | Resizes the widget; on a window, its content area, with the frame refitted around it. |
| `:visible(is_visible)` | `boolean` | `self` | Shows or hides the widget. |
| `:enabled(is_enabled)` | `boolean` | `self` | Enables or disables input interaction. |
| `:text(new_text)` | `string` | `self` | Updates the text label (on owned controls or editable client widgets). |
| `:tooltip(text)` | `string` | `self` | Sets hover tooltip text on owned controls. |
| `:destroy()` | None | None | Closes and destroys this widget and its subtree. |

---

## Search & Hierarchy

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `:match(selector)` | `string` | `Widget \| nil` | Searches descendants for the single widget matching `selector`. |
| `:matchAll(selector)` | `string` | `Widget[]` | Returns all descendants matching `selector`. |
| `:is(selector)` | `string` | `boolean` | Tests if this specific widget matches `selector`. |

---

## Event Subscriptions

```lua
-- Subscribe to widget-local UI events
custom_button:on("Pressed", function()
  hafen.log():write("Button pressed.")
end)

custom_window:on("Close", function()
  hafen.log():write("Window closed.")
end)
```
