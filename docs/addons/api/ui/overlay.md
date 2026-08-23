# hafen.ui: overlays

An overlay paints every frame without being a widget: there is nothing to place, nothing to size and
nothing in the tree. Reach for one to draw over what the client has already put on screen — a readout in a
corner, a marker, a whole HUD of your own — without owning any of it.

```lua
hafen.ui():overlay():add("banner"):draw(function(g, w, h)
  g:color(255, 200, 0)
  g:atext("hello", w / 2, 4, 0.5, 0)          -- centred along the top of the screen
end)
```

## One vocabulary, whichever thing is decorated

**`overlay` means keyed decorations bound to a thing**, and the receiver says which thing: on `hafen.ui()`
it is the screen, on [a game object](../overlay.md) it is that object. What follows is true wherever one
hangs, so a reader who has learned one receiver already knows the next.

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

## What else you can paint on

To paint over a **game object** rather than over the screen, the verb is on the object:
[`gob:overlay()`](../overlay.md). You name the gob it hangs on, so nothing is searched per frame and the
decoration follows the object with no projection to do. To stand something **in** the world rather than
over it, use [`hafen.vr`](../vr/README.md) — your own images and models, the game's own props, or
[a window of yours](../vr/widgets.md), out there instead of on the screen. To paint inside a surface you
built, the door is [that widget's own `Draw`](custom.md#subscribing).

## See also

- [custom](custom.md) — the windows and bare rectangles you build, and the callbacks they answer
- [drawing](drawing.md) — what `g` can do, and the cache text goes through
- [the pixel](pixels.md) — the unit `w, h` and every coordinate you draw at are measured in
- [Overlay](../overlay.md) — the same vocabulary at a game object
- [`hafen.vr`](../vr/README.md) — standing a thing in the world instead of drawing over it
