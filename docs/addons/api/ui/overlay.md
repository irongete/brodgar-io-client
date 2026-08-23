# hafen.ui: overlays

An overlay paints every frame without being a widget: there is nothing to place, nothing to size and
nothing in the tree. Reach for one to draw over what the client has already put on screen — a readout in a
corner, a marker, a whole HUD of your own, a number on an item icon — without owning any of it.

```lua
hafen.ui():overlay():add("banner"):draw(function(g, w, h)
  g:color(255, 200, 0)
  g:atext("hello", w / 2, 4, 0.5, 0)          -- centred along the top of the screen
end)
```

## One vocabulary, whichever thing is decorated

**`overlay` means keyed decorations bound to a thing**, and the receiver says which thing: on `hafen.ui()`
it is the screen, on a [widget](widget.md) it is that widget, on [a game object](../overlay.md) it is that
object. What follows is true wherever one hangs, so a reader who has learned one receiver already knows the
next.

- **It is a [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many)** —
  `:add(key)`, `:get(key)`, `:remove(key)`, `:list(filter)`, `:count(filter)`, `:find(filter)` — and a
  string `filter` matches the key as a substring.
- **The key is your own name for one, and keys are per addon.** Two addons using `"tag"` do not collide
  and neither can see the other's. `:add` on a key that already names one **replaces** it, leaving one.
- **`:list()` is the draw order.** A member added later paints over one added earlier, and re-adding a key
  moves it to the end.
- **`:add(key)` gives you a bare one**, and a bare one paints nothing. So an overlay you configure across
  several statements never paints half-dressed, and there is no commit verb to forget.
- **A painter is handed [`g`](drawing.md)**, the same drawing surface a widget's `Draw` gets, in the same
  [design pixels](pixels.md). It is valid only for the length of the callback: stash it and draw later and
  nothing happens.
- **It is unprotected.** What you paint is your own drawing and changes nothing the server, the client or
  another addon owns, so no permission key reaches it.
- **It takes no input.** An overlay is not in the tree, so a press passes straight through it to whatever is
  underneath, which answers its own [`MouseDown`](widget.md#subscribing). There is nothing to make clickable
  and nothing to consume.
- **It is bridge-owned.** A `:reload` or disabling your addon takes every overlay you attached off again.

## Over the whole HUD

`hafen.ui():overlay()` is the collection of the painters your addon has put over the screen. It takes no
argument — it **is** the collection — and it is reached without a [session](../session.md), because there
is one screen however many characters are logged in.

| Call | Returns | Description |
|---|---|---|
| `hafen.ui():overlay():add(key)` | Overlay | attach a painter under `key`, bare; the same key again replaces it |
| `hafen.ui():overlay():get(key)` | Overlay \| nil | the one under that key; `nil` for a key you have not used |
| `hafen.ui():overlay():remove(key)` | the collection | stop it; the member itself is taken too, and a key naming nothing is inert |
| `hafen.ui():overlay():list(filter)` | Overlay[] | every one of yours, **in draw order**; empty, never `nil` |
| `hafen.ui():overlay():count(filter)` | number | how many |
| `hafen.ui():overlay():find(filter)` | Overlay \| nil | the first whose key matches |

A key that is not a string raises, saying what a key is for; so does an argument to `hafen.ui():overlay()`
itself, which names the collection you meant.

### One painter

| Method | Returns | Description |
|---|---|---|
| `ov:key()` | string | the key it answers to; it answers after the overlay is removed as well |
| `ov:draw(fn)` | the overlay | paint `fn(g, w, h)` over the HUD every frame; `w, h` is the screen |
| `ov:draw()` | function \| nil | the painter it carries; `nil` while it is bare |
| `ov:exists()` | bool | is it still painting |

`ov:draw(fn)` on a live overlay replaces its painter rather than adding a second one, so a painter can be
swapped without the key changing hands. Anything that is not a function raises, and so does a name a
painter does not answer, which lists the ones it does.

**An overlay is interned on its key**: `:get(key)` hands back the same object every time, so `==` is the
identity test rather than a comparison of fields. `tostring(ov)` gives `Overlay("<key>")`.

```lua
local ovs = hafen.ui():overlay()
ovs:add("meters"):draw(function(g, w, h) g:frect(4, 4, 40, 6) end)
ovs:get("meters") == ovs:get("meters")        -- true: one object per key
ovs:add("meters")                             -- the same key again: still one member, now bare
ovs:count("meters")                           -- 1
ovs:remove("meters")                          -- ...and gone: :get("meters") is nil
```

**A painter runs once a frame, so what it draws every time is what it costs.** Geometry is nearly free;
text is not, which is why [`g:text` keeps its raster](drawing.md#text-is-cached-across-frames) — the same
words in the same font cost one rasterisation however many frames they are drawn for, and a string whose
characters change every frame costs one per frame.

The bundled **`widgetstack`** addon carries one: a painter that outlines whichever widget the cursor is
over.

## Over one widget

`widget:overlay()` is the collection of the painters your addon has put over **one widget** — an item
icon, a button, a container's grid, a window, or a surface of your own. You name the widget, so nothing is
searched per frame and there is no rectangle to re-derive: the painter is handed that widget's own box.

```lua
local pack = hafen.session():current():ui():inventory()
pack:overlay():add("frame"):draw(function(g, w, h)
  g:color(255, 90, 90)
  g:rect(0, 0, w, h)                          -- a red outline just inside the backpack grid's edge
end)
```

It answers on **any** widget, [owned or borrowed](widget.md#owned-vs-borrowed), and it is unprotected on
both: what you paint over one of the client's own widgets is your own drawing over a picture the client
has already drawn.

| Call | Returns | Description |
|---|---|---|
| `widget:overlay():add(key)` | Overlay | hang a painter on it under `key`, bare; the same key again replaces it |
| `widget:overlay():get(key)` | Overlay \| nil | the one under that key; `nil` for a key you have not used |
| `widget:overlay():remove(key)` | the collection | take it off; the member goes too, and a key naming nothing is inert |
| `widget:overlay():list(filter)` | Overlay[] | every one of yours on that widget, **in draw order**; empty, never `nil` |
| `widget:overlay():count(filter)` | number | how many |
| `widget:overlay():find(filter)` | Overlay \| nil | the first whose key matches |

An argument to `widget:overlay()` itself raises, naming the collection you meant, and so does a key that is
not a string.

### One painter over a widget

| Method | Returns | Description |
|---|---|---|
| `ov:key()` | string | the key it answers to; it answers after the overlay is gone as well |
| `ov:draw(fn)` | the overlay | paint `fn(g, w, h)` over the widget every frame; `w, h` is that widget's box |
| `ov:draw()` | function \| nil | the painter it carries; `nil` while it is bare |
| `ov:kind()` | string \| nil | what it paints — `"draw"`; `nil` while it is bare |
| `ov:exists()` | bool | is it still painting |
| `ov:info()` | table | the snapshot `{key, kind}`, with `kind` absent while it is bare |

Like its two siblings, a painter here is **interned on its key**: `:get(key)` hands back the same object
every time, so `==` is the identity test. The **collection** is not — it is a view read fresh on every
call, so `w:overlay() == w:overlay()` is `false` while `w:overlay():get(k) == w:overlay():get(k)` is
`true`. `tostring(ov)` gives `Overlay("<key>")`.

**A painter draws in the widget's own space, and is cut off at its edge.** `0, 0` is the widget's top-left
and `w, h` is its box in [design pixels](pixels.md) — the pair [`widget:size()`](widget.md#read) answers —
and anything you draw outside that box is clipped away rather than spilling over the widget's neighbours.
It runs **after** the widget has drawn itself, so it lands on top of it, and after everything inside the
widget too. Several painters on one widget run in the order you attached them.

**It paints only while the widget is drawn.** Hide the widget, or any ancestor of it, and the overlay goes
with it; show it again and the overlay is back, with nothing to re-attach. One of the client's windows
takes a moment to fade when it is closed, and an overlay under it goes on painting for that fade — the
window is still there, and so is what you hung on it.

**And it dies with the widget.** When the widget leaves the tree the overlay stops, `ov:exists()` goes
`false`, and the collection is empty — there is nothing to release and no handler to write. `:add(key)` on
a widget that has already left raises, naming the tree it left, where every other verb here answers `nil`
or is inert; [`widget:exists()`](widget.md#read) is the question to ask first.

```lua
local mark = pack:overlay():get("frame")
mark:exists()                                 -- true while the backpack is open
pack:overlay():remove("frame")                -- ...or take it off yourself
```

## What else you can paint on

To paint over a **game object** rather than over the screen or a widget, the verb is on the object:
[`gob:overlay()`](../overlay.md). You name the gob it hangs on, so nothing is searched per frame and the
decoration follows the object with no projection to do. To stand something **in** the world rather than
over it, use [`hafen.vr`](../vr/README.md) — your own images and models, the game's own props, or
[a window of yours](../vr/widgets.md), out there instead of on the screen. To paint inside a surface you
built, the door is [that widget's own `Draw`](custom.md#subscribing) — the callback that draws the widget,
where an overlay draws over it.

## See also

- [the Widget object](widget.md) — the thing `widget:overlay()` hangs on, and everything else it answers
- [custom](custom.md) — the windows and bare rectangles you build, and the callbacks they answer
- [drawing](drawing.md) — what `g` can do, and the cache text goes through
- [Overlay](../overlay.md) — the same vocabulary at a game object
- [`hafen.vr`](../vr/README.md) — standing a thing in the world instead of drawing over it
