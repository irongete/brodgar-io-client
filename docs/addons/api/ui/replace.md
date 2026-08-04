# hafen.ui: watching for a widget, and replacing it

Two verbs, and they are meant to be used together: `hafen.ui.on` waits for a part of the client's UI to
appear, and `widget:replace` stands your own window in its place. Both are ungated, and both are undone
when your addon goes away. The bundled **`bags`** addon is this pair end to end.

```lua
hafen.ui():on("inventory[title=Inventory]", "appear", function(inv)
  inv:replace(hafen.ui.window{
    title = "Bags", size = {200, 120},
    onDraw = function(g) g:text(#inv:items() .. " items", 6, 6) end,
  })
end)
```

| Call | Returns | Description |
|---|---|---|
| `hafen.ui():on(selector, event, fn)` | [handle](custom.md#overlay-and-observer-handles) | `fn(widget)` when a widget matching a [selector](selectors.md) appears or disappears |
| `widget:replace(view)` | the widget, chains | put your own window in place of the native one around it |

## Watching for a widget

`hafen.ui.on` names what it waits for with the same [selector](selectors.md) a lookup uses, and hands the
match back as the same interned [Widget](widget.md), so `==` and a Lua table keyed by it work across both
events. `event` is one of two strings, and a subscription carries exactly one — subscribe twice to watch
both:

| Event | Fires when |
|---|---|
| `"appear"` | a matching widget is placed into the tree, **or is already in it when you subscribe** |
| `"disappear"` | a widget that had matched is destroyed |

```lua
hafen.ui():on("window[title=Cupboard]", "appear", function(w)
  hafen.log():write(("cupboard open: %d item(s)"):format(#w:items()))
end)
```

Three things are worth knowing:

- **`appear` covers what is already open.** Registering scans the live tree once, so an addon reloaded with
  a window open still sees it. You never have to handle "was it there before me?" yourself.
- **Neither event is about visibility.** They track the *tree*: a window the client merely hides — the
  inventory's Tab toggle — never left, so it fires neither.
- **At `disappear`, treat the widget as a key, not as something to read.** It fires when the widget stops
  being *real*, which is not when it stops being *drawn*: a window plays a fade-out on close, so it lingers
  in the tree, readable, for the length of that animation. Match it against what you kept at `appear`, and
  keep the data you need from there.

A `[title=]` or `[res=]` selector still fires exactly once for a window whose caption arrives a tick after
the window itself — such a candidate is re-checked for a short while rather than dropped.

These are widget subscriptions rather than bus events: there is no `WidgetCreated` on
[`hafen.event()`](../event.md), because you say *which* widget you care about.

## Replacing a native window (ungated)

Replacing is a **verb on the widget**. The read has a name of its own, because putting a view in place
is an *act* and the thing standing there is a *replacement*:

| Call | Does |
|---|---|
| `w:replacement()` | reads the view standing in for this window, or `nil` |
| `w:replace(view)` | hides the native window and puts `view` in its place; chains |
| `w:replace(nil)` | undoes it there and then — the window comes back, the view is destroyed; chains |

**It hides the *enclosing* window, not the widget you point at.** That one line is why the verb exists.
Point it at the inventory **grid** and the whole stock window goes, frame and caption and all, because a
frame left standing around a hole is not a replacement. This is exactly where it differs from
[`w:visible(false)`](native.md#hiding-a-native-widget-carries-a-restore), which hides precisely what you
point at and nothing more. Two operations, two rules; pick by what you want left on screen.

**Waiting is not part of it.** `hafen.ui():on(sel, "appear", fn)` already waits for anything and already
fires for what is open, so the whole pattern is the two together — the example at the top of this page is
the complete shape.

`inv` stays an ordinary [Widget](widget.md) throughout: the widget you replaced is **hidden, not
destroyed**, so it is still bound to its server id, still filling with items, and `inv:items()`,
`inv:onItemAdded(…)` and every other verb keep answering while your view is up. That is "wrap, don't
reimplement" — you draw, the client keeps doing the work.

**The client's own toggle comes with the window.** Hiding it means you
[own it](native.md#hiding-a-native-window-takes-its-toggle), so Tab — or the menu button, or whichever key
that window uses — opens and closes **your view**, and the menu tick reads your view's visibility rather
than the hidden window's.

**The view's fate follows the substitution.** When the replacement ends the view is destroyed: by
`w:replace(nil)`, by `:reload` or disabling your addon, or by the server destroying the window — close a
replaced chest and your view goes with it. A stand-in that no longer stands for anything is an orphan
window over a container that is gone, so it is not left behind for you to clean up. Every ending also
leaves the stock window **as the user was seeing it**: your view was open, so the stock window is open;
nothing was on screen, so it stays closed.

**One window, one view.** Installing a *different* view ends the previous substitution and destroys that
view; installing the same one again is a no-op. Four things are refused outright, each naming what to do
instead: a view your addon did not create, a widget with **no enclosing window** (there is nothing to stand
in for), one of your *own* windows, and a window another addon already holds.

## Where replacing ends

A widget's own state is otherwise read-only — mutating it would desync the client from the server. So
`:text()` is a best-effort read rather than a write, and which child of a native window is a price and
which is a spacer is knowledge your Lua supplies, not something the tree declares.

The line between the three verbs on this page and the sheet is worth stating once. **Restyling** a native
widget — its text, its background, its border, a window's whole chrome — is
[the stylesheet's](style/README.md) job. **Placing** one is a write,
[`:position(x, y)`/`:size(w, h)`](native.md), which the sheet can also
[say as a rule](style/geometry.md). **Rearranging what a window puts inside itself** is neither: that is
replacing it.

## See also

- [native](native.md) — hiding a widget without standing anything in its place
- [widget](widget.md) — the object both verbs work on
- [selectors](selectors.md) — naming the window you want to wait for
- [items](items.md) — reading the container you replaced, while your view is up
- [custom](custom.md) — building the view you hand to `:replace`
