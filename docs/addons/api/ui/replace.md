# hafen.ui: watching for a widget, and replacing it

Two verbs, and they are meant to be used together: `s:ui():on` waits for a part of the client's UI to
appear, and `widget:replace` stands your own window in its place. Both are unprotected, and both are undone
when your addon goes away.

```lua
local s = hafen.session():current()                    -- the character on screen
local sub = s:ui():on("window[title=Inventory] inventory", "Added", function(inv)
  local view = hafen.ui():window():title("Bags"):size(200, 120)
  view:on("Draw", function(ev) ev:g():text(inv:items():count() .. " items", 6, 6) end)
  inv:replace(view)
end)
-- later:  sub:off()
```

| Call | Returns | Description |
|---|---|---|
| `s:ui():on(selector, event, fn)` | a [subscription](../event/README.md#subscribe) | `fn(widget)` when a widget matching a [selector](selectors.md) appears or disappears in that character's tree |
| `widget:replace(view)` | the widget, chains | put your own window in place of the native one around it |

## Watching for a widget

`s:ui():on` names what it waits for with the same [selector](selectors.md) a lookup uses, and hands the
match back as the same interned [Widget](widget.md), so `==` and a Lua table keyed by it work across both
events. `event` is one of two strings, and a subscription carries exactly one — subscribe twice to watch
both:

| Event | Fires when |
|---|---|
| `"Added"` | a matching widget is **up** in the tree, or was already up when you subscribe |
| `"Removed"` | a widget that had matched is destroyed |

```lua
s:ui():on("window[title=Cupboard]", "Added", function(w)
  hafen.log():write(("cupboard open: %d item(s)"):format(w:items():count()))
end)
```

What is worth knowing:

- **The callback runs on the [step](../threading.md)**, after the widget has arrived or gone — not inside
  the client's own placing of it. So it holds no character's UI: it may build a window, write the widget it
  was handed, and reach any other login the client has.
- **The subscription watches one character's tree**, the one `s` names — so watching two characters is two
  subscriptions, and each callback knows whose window it was handed.
- **`Added` covers what is already open.** Registering scans that character's live tree once, so an addon
  reloaded with a window open still sees it, and a subscription made on a character nobody is looking at
  fires at once for what that character has open. You never have to handle "was it there before me?"
  yourself.
- **Search inside the widget you were handed**, with [`w:match(sel)`](widget.md#searching-inside-one-widget),
  not from the root. Two cupboards can be open at once, and only the callback knows which one this is.
- **It covers what the client builds for itself**, not only what the server sends: the icon per item a
  container mints, the one under the cursor, a stack's own window. Every widget announces itself the same
  way, so the [`item`](selectors.md#roles) role is a subscription like any other.
- **`Added` means up, not merely parented.** A subtree is routinely built before it is hung — the server
  fills a chest's window and hangs the window afterwards — and until it is hung, nothing in it is in any
  tree: `widget:exists()` is false and every verb refuses. So the event waits for the ancestor that was
  missing, and then fires for the whole subtree at once, in tree order. The widget you are handed is always
  one you can act on.
- **Neither event is about visibility.** They track the *tree*: a window the client merely hides — the
  inventory's Tab toggle — never left, so it fires neither.
- **At `Removed`, treat the widget as a key, not as something to read.** It fires when the widget stops
  being *real*, which is not when it stops being *drawn*: a window plays a fade-out on close, so it lingers
  in the tree, readable, for the length of that animation. Match it against what you kept at `Added`, and
  keep the data you need from there.

**A caption that arrives late fires, and so does one that changes.** `[title=]` matches a window whose
caption lands after the window itself, and it matches from the moment a window's caption *becomes* the one
you named — a title your own addon writes included. On a chain the caption lands on an **ancestor** step
rather than on the widget you asked for, so the widgets under that window are offered again and
`window[title=Cupboard] inventory` fires for the grid. However it was reached, a match fires **once**: a
widget already handed to you is never handed over twice. `[res=]` has no such moment, because a resource
resolves on its own schedule, so a `[res=]` candidate is re-checked for a short while after placement.

What you get back is a `Sub`, like every other `:on` in the API: `sub:key()` is the event it carries and
`sub:off()` stops it, which is also done for you on reload or disable. These are widget subscriptions
rather than bus events: there is no `WidgetCreated` on [`hafen.event()`](../event/README.md), because you
say *which* widget you care about.

## Replacing a native window (unprotected)

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

**Waiting is not part of it.** `s:ui():on(sel, "Added", fn)` already waits for anything and already
fires for what is open, so the whole pattern is the two together — the example at the top of this page is
the complete shape.

`inv` stays an ordinary [Widget](widget.md) throughout: the widget you replaced is **hidden, not
destroyed**, so it is still bound to its server id, still filling with items, and `inv:items()`,
`inv:on("ItemAdded", …)` and every other verb keep answering while your view is up. That is "wrap, don't
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

What a native window *holds* stays the client's and the server's: which child of it is a price and which is
a spacer is knowledge your Lua supplies, not something the tree declares, so a view of your own is how you
present a container differently.

The line between the three verbs on this page and the sheet is worth stating once. **Restyling** a native
widget — its font and colour, its background, its border, a window's whole chrome — is
[the stylesheet's](style/README.md) job. **Placing** one is a write,
[`:position(x, y)`/`:size(w, h)`](native.md), which the sheet can also
[say as a rule](style/geometry.md). **Changing one part of a window** — its caption, or what one of its
buttons does — is [editing](edit.md), the page beside this one. **Rearranging what a window puts inside
itself** is none of those: that is replacing it.

## See also

- [edit](edit.md) — changing one part of a window instead of standing in for the whole of it
- [native](native.md) — hiding a widget without standing anything in its place
- [widget](widget.md) — the object both verbs work on
- [selectors](selectors.md) — naming the window you want to wait for
- [items](items.md) — reading the container you replaced, while your view is up
- [custom](custom.md) — building the view you hand to `:replace`
- [widgets in the world](../virtual/widgets.md) — where the view is drawn, which is the other question
