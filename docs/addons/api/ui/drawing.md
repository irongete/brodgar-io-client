# hafen.ui: the g draw wrapper

Every draw callback — a widget's [`Draw`](custom.md), an [overlay over the screen or over one
widget](overlay.md), a [`gob:overlay()`](../overlay.md) `draw` callback — receives `g`, a drawing surface.
Its coordinates are the callback's own local pixel space: widget-local for a widget and for an overlay hung
on one, screen for a HUD overlay, and for a gob overlay the `sx, sy` you were handed is that gob's
projected screen point. Every method is a colon call.

**Every length here is a [design pixel](pixels.md)** — a coordinate, a width, a height, a line's stroke,
a wedge's radius. It is the same unit `:size(w, h)` and `ev:w()` speak, so the rectangle you laid out is
the rectangle you draw into, with nothing to convert and nothing to multiply by
[`hafen.ui():scale()`](pixels.md#read).

```lua
local win = hafen.ui():window():title("My addon"):size(120, 60)
win:on("Draw", function(ev)
  local g, w = ev:g(), ev:w()
  g:color(255, 200, 0)
  g:frect(0, 0, w, 3)
  g:text("mine", 6, 8)
end)
```

## Draw

| Method | Description |
|---|---|
| `g:text(str, x, y, opts)` | draw text with its top-left at `(x, y)`; `opts` is an optional `{font, color, width}` |
| `g:atext(str, x, y, ax, ay, opts)` | anchored text; `ax`/`ay` `0..1` pick which point of the text sits at `(x, y)` |
| `g:rect(x, y, w, h)` | a one-pixel outline rectangle |
| `g:frect(x, y, w, h)` | a filled rectangle |
| `g:line(x1, y1, x2, y2, width)` | a line; `width` defaults to 1 |
| `g:poly(x1, y1, x2, y2, x3, y3, ...)` | a **filled** convex polygon of three points or more, in the current colour |
| `g:prect(cx, cy, radius, fraction)` | a clockwise pie or progress wedge, `fraction` `0..1` — for cooldowns and meters |
| `g:image(img, x, y, w, h)` | draw an [image asset](../asset.md) at native size, or scaled into `w × h` |
| `g:aimage(img, x, y, ax, ay)` | draw an [image asset](../asset.md) anchored, like `g:atext` |
| `g:color(r, g, b, a)` / `g:color(c)` | set the draw colour, `0..255`; `g:color()` resets to white — see [below](#colour-here-is-also-loose-numbers) |
| `g:resource(name, x, y, w, h)` | draw an engine `.res` image **by name**, at native size or scaled |

`g` is valid **only during the draw callback**. Stashing it and drawing later does nothing — it goes inert
rather than throwing. Nothing on this page is protected: painting your own pixels changes nothing the client
or the server owns.

`g:rect`'s outline is one **screen** pixel whatever the scale, the way the client's own hairlines are — the
box it traces is design pixels like everything else. `g:line`'s `width` is a design pixel, so a `4` px rule
keeps the weight of the chrome beside it on a scaled client.

### Colour here is also loose numbers

`g:color` is the one place in this API where a [colour](../shapes.md#colours) is loose components as well as
a table. Loose numbers are the language of every verb on `g` — `g:line(x1, y1, x2, y2)`,
`g:frect(x, y, w, h)` — so a colour written the same way reads like its neighbours. Everywhere else a colour
write takes the table and nothing else.

```lua
hafen.ui():overlay():add("meters"):draw(function(g, w, h)
  local s = hafen.session():current()
  local hp = s and s:meter():find("hp")
  g:color(255, 200, 0)                 -- components, like every other g: call
  g:frect(4, 4, 40, 6)
  g:color{255, 200, 0}                 -- the same colour, as a table
  g:frect(4, 14, 40, 6)
  if hp then
    g:color(hp:color())                -- ...and a colour read back from the API, unchanged
    g:frect(4, 24, 40, 6)
  end
  g:color()                            -- back to white
end)
```

A table that is not a colour raises, naming what one is. A colour a *read* handed you is always one, so a
value out of [`seg:color()`](../meter.md#a-segment), [`kin:color()`](../kin.md) or a
[snapshot's `color` key](../types/README.md) goes straight in.

## Images

To draw your own PNGs, load them with [`hafen.asset`](../asset.md) and blit the handle:

```lua
local icon                                     -- upvalue for the draw callbacks below

hafen.event():on("Load", function()
  icon = hafen.asset():get("icon.png")         -- load once from addons/<me>/icon.png
end)

hafen.ui():overlay():add("icons"):draw(function(g, w, h)
  if icon then
    g:image(icon, 4, 4)                        -- native size
    g:image(icon, 4, 40, 16, 16)               -- the same image scaled to 16x16
    g:aimage(icon, w - 6, 6, 1.0, 0.0)         -- anchored to the screen's top-right corner
  end
end)
```

A `nil`, wrong-type or disposed handle **draws nothing**: the draw verbs are forgiving and never throw. A
PNG's transparency is preserved, so an icon with a transparent background composites over whatever is
behind it, the same as the client's own art.

**Your PNG's own pixels are design pixels.** A 32×32 file is 32×32 to
[`img:size()`](../asset.md#image) and covers 32×32 in the box you drew it in, on every client — so it sits
beside the client's own art at the same size at every interface scale, and `g:image(icon, x, y)` covers
exactly `icon:size()` from `x, y`. Authoring for a scaled client is authoring a bigger PNG and drawing it
into the same box.

`g:resource(name, …)` draws the **client's own `.res` art** — action icons, HUD pieces, the icon of an
action a widget received from [`Drop`](custom.md#drop-makes-a-widget-a-drop-target). Its native size is
whatever the client draws that resource at, which is already the user's scale; the `w, h` box is design
pixels like every other box here, so give one when you want a resource at a size you chose. It resolves the
resource asynchronously and caches it, and it is load-guarded: it draws nothing until the texture is ready,
then blits the resource's default image layer. It draws the **static icon only**, with no live sprite or
cooldown sweep, and a bad name simply draws nothing.

To stand an image in the 3D world rather than on screen, use [`hafen.virtual`](../virtual/sprites.md).

## Text

`g:text` and `g:atext` take an optional trailing `{ font = h, color = {r, g, b, a}, width = n }`, so one
call can be rendered in a [loaded font](../font.md), tinted, and wrapped to a box:

- **`font`** — a [`FontHandle`](../font.md). It overrides the widget's `font =` default for this call;
  omit it and you get the widget default, else the client's stock font.
- **`color`** — a [colour](../shapes.md#colours), either spelling. It composes with `g:color` exactly as a
  `g:color` call around it would. Omit it and the glyphs are white, tinted by the current `g:color`.
- **`width`** — design pixels to **wrap** at. Omit it and the string is one line however long it is; give
  it and the string is broken at the spaces that fit, top-left still at `(x, y)` and the box growing
  downwards. A `width` of `0` or less is refused rather than read as "one line", because zero is what your
  own arithmetic produces when you subtract a padding from a width you have not measured yet.

The string may also carry rich-text markup — `$font[family,size]{…}`, `$col`, `$b`, `$i`, `$u`, `$size` —
so several fonts can share one line. Feed a handle's `h:family()` to the `$font` tag; see
[mixing fonts on one line](../font.md#mix-fonts-on-one-line). Plain text with no markup and no font takes
the stock render path unchanged, and malformed markup falls back to drawing the literal string rather than
throwing.

### Measuring a line before you draw it

`hafen.ui():measure(s, opts)` answers `{w =, h =}` for the box `g:text(s, x, y, opts)` would occupy, in
design pixels. `opts` is the very same table — pass the `font` and the `width` you are about to draw with
and the box you get back is the box you will fill. Unprotected, and callable from anywhere: it is not a
draw, so it needs no callback and no `g`.

```lua
local line = "a long line of your own that will not fit across the plate you want to put it on"
local opts = { width = 160 }                                -- one table, measured and drawn

hafen.ui():overlay():add("panel"):draw(function(g, sw, sh)
  local box = hafen.ui():measure(line, opts)
  g:color(0, 0, 0, 160)
  g:frect(4, 4, box.w + 8, box.h + 8)                       -- a plate exactly around the wrapped text
  g:color()
  g:text(line, 8, 8, opts)
end)
```

Worth knowing before you lay anything out with it:

- **It measures the raster, not the characters.** Markup is read exactly as the draw reads it, so
  `$col[255,0,0]{hello} there` measures as `hello there` and not as the twenty-odd characters it is spelled
  with. That is the whole reason this verb exists rather than a string length and a guess.
- **It costs a rasterisation the first time and nothing after**, because it renders through the same
  [cache](#text-is-cached-across-frames) under the same key the draw uses. Measuring a line and then drawing
  it rasterises it **once between them**, so measuring every frame in a draw callback is free once the text
  settles.
- **Without `opts.font` it measures the client's stock font.** A widget's own [`:font(h)`](custom.md)
  default is not this verb's to know, so pass the handle you set on the widget if you set one.

`measure` refuses a `width` of `0` or less exactly as the draw verbs do, and refuses an `opts` that is not
a table.

**A wrapped box is a shade shorter per line than an unwrapped one.** Wrapping is rich text, and rich text
measures a line by the glyphs' own bounds where a plain single line takes the font's full line height — so
the same string at a `width` wide enough to hold it on one line can answer an `h` a pixel or two under what
it answers with no `width` at all. Both are the raster that gets drawn. And where the `width` is narrower
than a single character, that character is kept anyway and the box comes back **wider than the width you
gave**: a box too narrow to hold one glyph cannot be honoured, and one glyph per line is what it does
instead.

### Text is cached across frames

Rasterising a line of text is far more expensive than any geometry call here: it is a font layout, a glyph
raster and a GPU texture upload. `g:text` and `g:atext` therefore **keep the rendered text and reuse it**,
so drawing the same string in the same font every frame rasterises it once. You do not opt in and there is
nothing to hold: the cache is per addon, invisible, and dropped with its textures when you `:reload` or
disable.

What that means when you write a draw callback:

- **The cache key is the string, the font and the `width`.** Same text, same font, same width is a hit,
  however many draw sites or frames apart — and the same string at two widths is two entries, because the
  wrap *is* the raster. **Colour is not in the key** — it is applied as a tint over the same raster, so
  drawing one string in two colours in one frame is *one* entry, and animating a colour costs nothing.
- **A string that changes every frame is re-rasterised every frame.** A clock, an FPS readout or a
  coordinate line whose digits move can never hit, and a miss costs exactly what every draw cost before the
  cache existed. **Budget a live readout by how often its *text* changes, not by how many lines it has**:
  `"HP: 100/100"` redrawn every frame is free, `"HP: 100/100 (12.483 s)"` is a rasterisation per frame.
- **Font overrides still take effect immediately.** The key carries the font generation, so installing,
  moving or dropping a [style](style/README.md) restyles on the next frame — the old entries simply stop
  being looked up and age out.
- **It is bounded, not a leak.** An LRU of at most **512 entries or 8 MiB** of texture; the least recently
  used entries are evicted and their textures disposed. An addon that draws thousands of distinct strings
  settles at the cap instead of growing.
- **A label an overlay puts up shares it.** [`ov:text(s)`](overlay.md#a-label-the-client-draws) over a
  widget, and [the same verb at a gob](../overlay.md), are drawn by the client rather than by a callback of
  yours — through this very cache, on the same key. So one string in one font is one entry whether your
  painter drew it or the client did, and a label costs no Lua at all per frame.

[`hafen.client():profiling():textcache()`](../client/profiling/counters.md#textcache) reports what your
addon's cache holds and its hit rate. Rich-text markup is cached on the same terms as plain text.

## See also

- [the pixel](pixels.md) — the unit every coordinate here is in
- [custom](custom.md) — the callbacks `g` arrives in
- [overlays](overlay.md) — the painters over the HUD and over one widget, each handed one too
- [`hafen.font`](../font.md) — getting a handle to pass as `font`
- [`hafen.asset`](../asset.md) — loading the images `g:image` draws
- [`hafen.client`](../client/profiling/counters.md#textcache) — the cache's own counters
- [`hafen.virtual`](../virtual/sprites.md) — standing an image in the world instead of on screen
