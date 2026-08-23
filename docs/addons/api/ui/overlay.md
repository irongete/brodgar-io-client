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

`widget:overlay()` is the collection of what your addon draws over **one widget** — an item icon, a
button, a container's grid, a window, or a surface of your own. You name the widget, so nothing is searched
per frame and there is no rectangle to re-derive: a painter is handed that widget's own box, and a label is
placed in it by a fraction. Two kinds, and one overlay is exactly one of them.

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

### One overlay over a widget

| Method | Returns | Description |
|---|---|---|
| `ov:key()` | string | the key it answers to; it answers after the overlay is gone as well |
| `ov:kind()` | string \| nil | what it draws — `"draw"` or `"text"`; `nil` while it is bare |
| `ov:draw(fn)` | the overlay | paint `fn(g, w, h)` over the widget every frame; `w, h` is that widget's box |
| `ov:draw()` | function \| nil | the painter it carries; `nil` on a label and while it is bare |
| `ov:text(s)` | the overlay | put the line `s` over the widget, drawn by the client — [see below](#a-label-the-client-draws) |
| `ov:text()` | string \| nil | the label it carries; `nil` on a painter and while it is bare |
| `ov:exists()` | bool | is it still painting |
| `ov:info()` | table | the snapshot `{key, kind}`, and `text` on a label; `kind` is absent while it is bare |

**An overlay says exactly one thing.** `ov:text` on a painter raises naming `draw`, `ov:draw` on a label
raises naming `text`, and `:add(key)` again is how you change your mind: it replaces the record with a bare
one. On its own kind either verb is a *change* rather than a second thing — `ov:text("120")` relabels a live
label, `ov:draw(fn)` swaps a painter's function — and the key never changes hands.

Like its two siblings, an overlay here is **interned on its key**: `:get(key)` hands back the same object
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

**An item icon is a new widget every time the item moves.** The client builds one icon per item the server
puts in a container and destroys it when the server takes that item out, so moving an item to another slot
— or onto the cursor — is a destroy and a build, not a widget that moved. What you hung on the old icon
goes with it. So a decoration that has to survive a move is written as *decorate every icon there is*:
[`s:ui():on("item", "Added", fn)`](selectors.md#roles) hands you each one as it appears — the ones already
open when you subscribe, the ones built by a move, and the one the cursor carries — and
[`widget:item()`](widget.md#read) is the item it draws.

```lua
local mark = pack:overlay():get("frame")
mark:exists()                                 -- true while the backpack is open
pack:overlay():remove("frame")                -- ...or take it off yourself
```

### A label the client draws

A number on an item icon is a line of text and nothing else, and writing it as a painter means a Lua call
every frame for a string that changes once an hour. `ov:text(s)` is that decoration said instead of drawn:
the client puts the line up itself, and your addon is not called at all while it is there.

```lua
local face = hafen.font():get("serif"):derive():size(11)

hafen.session():current():ui():on("item", "Added", function(icon)
  local item = icon:item()                              -- the item that icon draws
  if item == nil then return end
  local ov = icon:overlay():add("q")
      :anchor(0.5, 1):offset(0, -1)                     -- centred on the slot's bottom edge
      :color{255, 230, 140}:background{0, 0, 0, 200}:font(face)

  local function show()
    local q = item:quality()
    if q then ov:text(tostring(math.floor(q + 0.5))) end
  end
  show()
  item:on("Changed", show)                              -- the quality lands after the icon does
end)
```

> **The number is not known when the icon is.** An item's tooltip arrives after the item, so `:quality()`
> answers `nil` for a moment on an icon that has only just appeared — which is why the label is written
> twice, once now and once from [`item:on("Changed")`](items.md#an-item-arrives-before-it-can-be-described).

| Setter | Meaning |
|---|---|
| `ov:anchor(ax, ay)` | `0..1` each: the point of the widget's box the label sits at, **and** the point of the label that lands on it. `0, 0` — the top-left of both — until you say otherwise |
| `ov:offset(x, y)` | [design pixels](pixels.md), added after the anchor has placed it |
| `ov:color(c)` | the glyphs' [colour](../shapes.md#colours); the client's white when you set none |
| `ov:background(c)` | a [colour](../shapes.md#colours) filled behind the label, its own box and not a pixel wider; nothing behind it when you set none |
| `ov:font(h)` | a [font handle](../font.md); the client's stock font when you set none |

Each has a bare read of the same name, so what you wrote is what you read back: `ov:anchor()` and
`ov:offset()` give `{x=, y=}`, `ov:color()` and `ov:background()` a colour, `ov:font()` the very handle you
passed, and an unset colour, background or font reads `nil`. **The order does not matter** — a property may
be set before the label or after it, and one set on a painter is simply never read, since a painter is
handed the whole box and draws its own text where it likes.

**One pair of fractions places the label**, read twice: `anchor(1, 1)` puts the label's bottom-right corner
on the widget's, which is what makes it read as *in that corner* with no width to measure and subtract.
`anchor(0.5, 0.5)` centres it. A label is clipped at the widget's edge like everything else here, so a
number wider than the slot it sits on is cut rather than spilling over the slot beside it; `offset` is how
you move it clear of a border.

Both fractions are the verb, so `anchor(0.5)` raises naming the pair rather than assuming an axis. A label
that is not a string raises, so does a font that is not a handle — naming where one comes from — and so
does a colour that is not [one of the two spellings](../shapes.md#colours). A setter that raises leaves the
overlay drawing exactly what it drew before.

**A label costs one rasterisation for its lifetime.** It is drawn through the same
[text cache](drawing.md#text-is-cached-across-frames) `g:text` goes through, so the line is laid out and
uploaded once and every later frame is a lookup and a blit — with no Lua call at all, where a painter
drawing the same words pays one every frame. The colour and the background are applied over the finished
raster and are **not** part of what is cached, so either may change every frame for nothing; the string and
the font are, so changing either rasterises once more and then settles again.

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
