# hafen.ui: Watching for a Widget, and Replacing It

`session:ui():on(selector, event, fn)` waits for a part of the client's UI to appear or go. `widget:replace(view)` stands a window of yours in place of the native one around a widget. Both are unprotected and undone when your addon goes.

```lua
local session = hafen.session():current()
local subscription = session:ui():on("window[title=Inventory] inventory", "Added", function(inventory)
  local view = hafen.ui():window():title("Bags"):size(200, 120)
  view:on("Draw", function(draw_event) draw_event:g():text(inventory:items():count() .. " items", 6, 6) end)
  inventory:replace(view)
end)
-- later:  subscription:off()
```

---

## Watching for a widget

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:ui():on(selector, "Added", fn)` | [`Sub`](../event/README.md#subscribe) | Unprotected | `fn(widget)` when a widget matching the [selector](selectors.md) is up in that character's tree, and once for each already up when you subscribe. |
| `session:ui():on(selector, "Removed", fn)` | `Sub` | Unprotected | `fn(widget)` when a widget that had matched is destroyed. |
| `sub:key()`, `sub:off()` | `string`, — | Unprotected | The event it carries. Ends it. Ended for you on `:reload` and disable. |

One subscription carries one event. Subscribe twice to watch both. The widget handed over is the same interned [Widget](widget.md) a lookup gives, so `==` and tables keyed by it work across both events.

```lua
session:ui():on("window[title=Cupboard]", "Added", function(cupboard)
  hafen.log():write(("cupboard open: %d item(s)"):format(cupboard:items():count()))
end)
```

| Rule | Detail |
|---|---|
| Runs on the [step](../threading.md) | After the widget arrived or went, holding no character's UI. The callback may build a window, write the widget it holds and reach any other login. |
| One character's tree | The one `session` names. Two characters are two subscriptions. |
| `Added` covers what is already open | Registering scans that tree once, so a reloaded addon sees an open window. A subscription on a character nobody is looking at fires for what that character has open. |
| Search inside the widget you were handed | [`widget:match(sel)`](widget.md#searching-inside-one-widget), not from the root: two cupboards can be open. |
| What the client builds for itself | The icon per item, the cursor's, a stack's window all announce themselves, so [`item`](selectors.md#roles) is a subscription like any other. |
| `Added` means up, not parented | A subtree is built before it is hung (a chest's window is filled, then hung). The event waits for the missing ancestor and fires for the whole subtree in tree order. The widget you are handed can be acted on. |
| Not visibility | A window the client merely hides (the inventory's Tab toggle) never left and fires neither event. |
| At `Removed`, the widget is a key | It fires when the widget stops being real, not drawn: a closing window lingers readable for its fade-out. Match it against what you kept at `Added`. |
| Captions | `[title=]` matches a caption landing after the window, and one that becomes the caption you named, a title your addon wrote included. On a chain the caption lands on an ancestor step and the widgets under it are offered again. A match fires once per widget. A `[res=]` candidate is re-checked for a short while after placement, since a resource resolves on its own schedule. |
| Not a bus event | There is no `WidgetCreated` on [`hafen.event()`](../event/README.md): you say which widget you care about. |

---

## Replacing a native window (unprotected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:replacement()` | `Widget \| nil` | Unprotected | The view standing in for this widget's window. |
| `widget:replace(view)` | `self` | Unprotected | Hides the native window enclosing `widget` and puts `view`, a window your addon built, in its place. |
| `widget:replace(nil)` | `self` | Unprotected | Undoes it: the window comes back, the view is destroyed. |

| Rule | Detail |
|---|---|
| The enclosing window is hidden | Point at the inventory grid and the whole stock window goes, frame and caption included: a frame around a hole is not a replacement. [`widget:visible(false)`](native.md#hiding-a-native-widget-carries-a-restore) hides exactly what you point at. |
| Waiting is `session:ui():on` | The example at the top is the whole pattern. |
| The widget is hidden, not destroyed | Still bound to its server id and filling with items: `inventory:items()`, `inventory:on("ItemAdded", fn)` and every other verb keep answering while your view is up. You draw. The client keeps doing the work. |
| The toggle comes with the window | Hiding it means you [own its toggle](native.md#hiding-a-native-window-takes-its-toggle). Tab, the menu button or whichever key that window uses opens and closes your view. The menu tick reads your view's visibility. |
| The view's fate follows the substitution | The view is destroyed when the replacement ends: `replace(nil)`, `:reload`, disable, or the server destroying the window. Every ending leaves the stock window as the user was seeing it: your view open, the stock window open. Nothing on screen, closed. |
| One window, one view | A different view ends the previous substitution and destroys that view. The same view again is a no-op. |
| Refused, naming what to do | A view your addon did not create, or a lone control rather than a surface. A widget with no enclosing window. One of your own windows (move, resize or destroy it instead). A window another addon already holds. |
| A view that has left the tree | Installs nothing: the native window stays, its toggle stays the client's, the call chains and `:replacement()` reads `nil`. Asked before the refusals above. A value that is not a widget raises. |

---

## Where replacing ends

What a native window holds stays the client's and the server's. A view of your own is how you present a container differently. Restyling a native widget is [the stylesheet](style/README.md). Placing one is [`:position(x, y)`/`:size(w, h)`](native.md), also expressible as a [rule](style/geometry.md). Changing one part of a window is [editing](edit.md). Rearranging what a window puts inside itself is replacing it.

---

## See Also

- [Edit](edit.md) — changing one part of a window instead of standing in for it.
- [Native](native.md) — hiding a widget without standing anything in its place.
- [Widget](widget.md) — the object both verbs work on.
- [Selectors](selectors.md) — naming the window to wait for.
- [Items](items.md) — reading the container you replaced while your view is up.
- [Custom](custom.md) — building the view you hand to `:replace`.
- [Widgets in the world](../virtual/widgets.md) — where the view is drawn.
