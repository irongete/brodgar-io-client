# Owned vs. Borrowed Widgets

Permissions and capabilities when modifying widgets created by your addon versus native engine widgets.

---

## 1. Owned Widgets (`widget:owned() == true`)

Any widget constructed by your addon (e.g. `hafen.ui():window()`, `hafen.ui():button()`) is an **owned widget**:
* You have full control over lifecycle, layout, contents, and destruction (`:destroy()`).
* Changing text, sizes, positions, or styles requires **no permissions**.

---

## 2. Borrowed Widgets (`widget:owned() == false`)

Widgets retrieved from the client tree (e.g. via `session:ui():match(...)`) are **borrowed widgets**:

### Unprotected Operations
Client-local modifications that affect only your screen do not require permissions:
* Repositioning: `widget:position(x, y)`
* Resizing: `widget:size(w, h)`
* Toggling visibility: `widget:visible(false)`
* Overriding display labels: `widget:title("New Title")`, `widget:text("Custom Label")`

### Protected Operations
Actions that simulate user input sent to the game server require permissions in `manifest.json`:
* Setting control input values: `widget:value(...)` (requires `widget.value`)
* Dispatching action messages: `widget:send(...)` (requires `widget.send`)
