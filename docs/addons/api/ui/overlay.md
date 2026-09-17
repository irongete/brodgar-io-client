# hafen.ui: Overlays

An overlay paints every frame without being a widget. It is a keyed painter or label bound to the screen (`hafen.ui():overlay()`) or to one widget (`widget:overlay()`). There is nothing to place, size or put in the tree.

```lua
hafen.ui():overlay():add("banner"):draw(function(graphics, width, height)
  graphics:color(255, 200, 0)
  graphics:atext("hello", width / 2, 4, 0.5, 0)          -- centred along the top of the screen
end)
```

---

## One vocabulary, whichever thing is decorated

`overlay` means keyed decorations bound to a thing. The receiver says which thing: `hafen.ui()` the screen, a [widget](widget.md) that widget, [a game object](../overlay.md) that object.

| Rule | Detail |
|---|---|
| A [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) | `:add(key)`, `:get(key)`, `:remove(key)`, `:list(filter)`, `:count(filter)`, `:find(filter)`. A string filter matches the key as a substring. |
| Keys | Your own names, per addon: two addons using `"tag"` neither collide nor see each other. `:add` on a key that already names one replaces it. A key that is not a string raises. |
| Draw order | `:list()` is the draw order. A member added later paints over one added earlier, and re-adding a key moves it to the end. |
| Bare on `:add` | A bare overlay paints nothing, so one configured across several statements never paints half-dressed. |
| `g` | A painter is handed the [`g` wrapper](drawing.md) a widget's `Draw` gets, in [design pixels](pixels.md), valid for the callback only. |
| Permission | Unprotected: your own drawing over a picture already drawn. |
| Input | None. An overlay is not in the tree. A press passes through to what is underneath. |
| Lifetime | Every overlay you attached is removed on `:reload` and disable. |
| Identity | An overlay is interned on its key: `:get(key)` is the same object every call, so `==` is identity. `tostring(ov)` is `Overlay("<key>")`. |

---

## Over the whole HUD

`hafen.ui():overlay()` is the collection of your addon's painters over the screen. It takes no argument (an argument raises, naming the collection) and needs no session: there is one screen.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():overlay():add(key)` | `Overlay` | Unprotected | Attaches a bare painter under `key`. The same key again replaces it. |
| `hafen.ui():overlay():get(key)` | `Overlay \| nil` | Unprotected | The one under that key. |
| `hafen.ui():overlay():remove(key)` | the collection | Unprotected | Ends it. A key naming nothing is inert. |
| `hafen.ui():overlay():list([filter])` | `Overlay[]` | Unprotected | Every one of yours, in draw order. Empty, never `nil`. |
| `hafen.ui():overlay():count([filter])` | `number` | Unprotected | How many. |
| `hafen.ui():overlay():find(filter)` | `Overlay \| nil` | Unprotected | The first whose key matches. |

### One painter

| Method | Returns | Permission | Description |
|---|---|---|---|
| `ov:key()` | `string` | Unprotected | The key it answers to, after removal too. |
| `ov:draw(fn)` | `self` | Unprotected | Paints `fn(graphics, width, height)` over the HUD every frame. `width, height` is the screen. On a live overlay it replaces the painter. Anything but a function raises. |
| `ov:draw()` | `function \| nil` | Unprotected | The painter. `nil` while bare. |
| `ov:exists()` | `boolean` | Unprotected | Whether it is still painting. |
| `ov:info()` | `table` | Unprotected | `{key, exists, drawn}`. `drawn` is `false` while it is bare. |

```lua
local painters = hafen.ui():overlay()
painters:add("meters"):draw(function(graphics, width, height) graphics:frect(4, 4, 40, 6) end)
assert(painters:get("meters") == painters:get("meters"))   -- one object per key
painters:add("meters")                             -- the same key again: one member, now bare
local meters_count = painters:count("meters")              -- 1
painters:remove("meters")                          -- gone: :get("meters") is nil
```

A painter runs once a frame, so what it draws every time is what it costs. Geometry is cheap. [`g:text` keeps its raster](drawing.md#text-is-cached-across-frames), so a string that changes every frame costs a rasterisation per frame.

---

## Over one widget

`widget:overlay()` is what your addon draws over one widget — an item icon, a button, a grid, a window, a surface of yours. It answers on any widget, [owned or borrowed](writes.md#owned-vs-borrowed), unprotected on both.

```lua
local backpack = hafen.session():current():ui():inventory()
backpack:overlay():add("frame"):draw(function(graphics, width, height)
  graphics:color(255, 90, 90)
  graphics:rect(0, 0, width, height)           -- a red outline inside the grid's edge
end)
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:overlay():add(key)` | `Overlay` | Unprotected | Hangs a bare overlay on it under `key`. The same key again replaces it. Raises on a widget that has left the tree, naming the tree. |
| `widget:overlay():get(key)`, `:remove(key)`, `:list([filter])`, `:count([filter])`, `:find(filter)` | as above | Unprotected | The collection verbs. An argument to `widget:overlay()` itself raises. |

| Rule | Detail |
|---|---|
| Where in the frame it lands | Painted straight after that widget and its subtree, before the widgets drawn after it. A painter on the map view covers the world and is covered by the HUD's windows. `hafen.ui():overlay()` is over the finished HUD, windows included. |
| Widget-local and clipped | `0, 0` is the widget's top-left, `w, h` its box ([`widget:size()`](widget.md#read-methods)). Anything outside is cut off. A point in root design pixels ([`worldToScreen`](../world.md#the-screen-and-the-world), [the mouse](mouse.md), [`widget:rootPos()`](widget.md#read-methods)) is moved by that widget's own `:rootPos()` first. Several painters run in the order attached. |
| Paints while the widget is drawn | Hidden with the widget or any ancestor, back when shown, nothing to re-attach. A client window fading out is still there, and so is what you hung on it. |
| Dies with the widget | When the widget leaves the tree the overlay stops, `ov:exists()` is `false`, the collection is empty. |
| The collection is a view | `w:overlay() == w:overlay()` is `false`. `w:overlay():get(k) == w:overlay():get(k)` is `true`. |
| An item icon is a new widget every move | The client builds one icon per item and destroys it when the item leaves the slot, so a move is a destroy and a build. Decorate every icon there is. [`session:ui():on("item", "Added", fn)`](selectors.md#roles) hands you each as it appears: the ones already open, the ones built by a move, the cursor's, a recipe's slots. [`widget:item()`](widget.md#read-methods) is the item it draws. |

```lua
local view = hafen.session():current():ui():match("@MapView")
view:overlay():add("marks"):draw(function(graphics, width, height)
  graphics:color(60, 140, 255)
  graphics:rect(0, 0, width, height)           -- inside the 3D view, under every window
end)
```

### One overlay over a widget

An overlay says exactly one thing: a painter or a label. `ov:text` on a painter raises naming `draw`, `ov:draw` on a label raises naming `text`. `:add(key)` again replaces it with a bare one. On its own kind either verb is a change: `ov:text("120")` relabels, `ov:draw(fn)` swaps the painter.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `ov:key()` | `string` | Unprotected | The key it answers to, after it is gone too. |
| `ov:kind()` | `string \| nil` | Unprotected | `"draw"` or `"text"`. `nil` while bare. |
| `ov:draw(fn)` | `self` | Unprotected | Paints `fn(graphics, width, height)` over the widget every frame. `width, height` is the widget's box. |
| `ov:draw()` | `function \| nil` | Unprotected | The painter. `nil` on a label and while bare. |
| `ov:text(line)` | `self` | Unprotected | A label the client draws over the widget — [below](#a-label-the-client-draws). |
| `ov:text()` | `string \| nil` | Unprotected | The label. `nil` on a painter and while bare. |
| `ov:exists()` | `boolean` | Unprotected | Whether it is still painting. |
| `ov:info()` | `table` | Unprotected | `{key, kind}` plus `text` on a label. `kind` absent while bare. |

### A label the client draws

`ov:text(s)` is a label the client draws itself: your addon is not called while it stands.

```lua
local face = hafen.font():get("serif"):derive():size(11)

hafen.session():current():ui():on("item", "Added", function(icon)
  local item = icon:item()
  if item == nil then return end
  local quality_label = icon:overlay():add("q")
      :anchor(0.5, 1):offset(0, -1)                     -- centred on the slot's bottom edge
      :color{255, 230, 140}:background{0, 0, 0, 200}:font(face)
  local function show()
    local quality = item:quality()
    if quality then quality_label:text(tostring(math.floor(quality + 0.5))) end
  end
  show()
  item:on("Changed", show)                              -- the quality lands after the icon does
end)
```

An item's tooltip arrives after the item, so `:quality()` is `nil` for a moment on a new icon. The label is written now and again from [`item:on("Changed")`](items.md#an-item-arrives-before-it-can-be-described).

| Method | Returns | Permission | Description |
|---|---|---|---|
| `ov:anchor(ax, ay)` | `self` | Unprotected | `0..1` each: the point of the widget's box the label sits at and the point of the label that lands on it. `0, 0` by default. `anchor(1, 1)` puts the label's bottom-right on the widget's. `anchor(0.5, 0.5)` centres it. One fraction raises, naming the pair. |
| `ov:offset(x, y)` | `self` | Unprotected | Design pixels added after the anchor placed it. |
| `ov:color(c)` | `self` | Unprotected | The glyphs' [colour](../shapes.md#colours). The client's white when unset. |
| `ov:background(c)` | `self` | Unprotected | A colour filled behind the label, its own box and not a pixel wider. Nothing when unset. |
| `ov:font(h)` | `self` | Unprotected | A [font handle](../font.md). The client's stock font when unset. |
| `ov:anchor()`, `ov:offset()` | `{x=, y=}` | Unprotected | The values written. |
| `ov:color()`, `ov:background()`, `ov:font()` | as written `\| nil` | Unprotected | `nil` when unset. |

| Rule | Detail |
|---|---|
| Order | A property may be set before or after the label. One set on a painter is never read. |
| Clipping | A label is clipped at the widget's edge. `offset` moves it clear of a border. |
| Refusals | A label that is not a string, a font that is not a handle, a colour in neither [spelling](../shapes.md#colours). Each raises and leaves the overlay drawing what it drew. |
| Cost | One rasterisation for the label's lifetime, through the [text cache](drawing.md#text-is-cached-across-frames). Colour and background are applied over the raster and may change every frame for nothing. The string and the font are in the key. |

---

## What else you can paint on

Over a game object: [`gob:overlay()`](../overlay.md). In the world rather than over it: [`hafen.virtual`](../virtual/README.md), [a window of yours](../virtual/widgets.md) included. Inside a surface you built: [its own `Draw`](custom.md#subscribing).

---

## See Also

- [Widget](widget.md) — what `widget:overlay()` hangs on.
- [Custom](custom.md) — the surfaces you build and their callbacks.
- [Drawing](drawing.md) — what `g` draws, and the cache text goes through.
- [Overlay](../overlay.md) — the same vocabulary at a game object.
- [`hafen.virtual`](../virtual/README.md) — standing a thing in the world.
