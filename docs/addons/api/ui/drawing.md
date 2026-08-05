# hafen.ui: the g draw wrapper

Every draw callback — [`onDraw`](custom.md), [a HUD overlay](custom.md#overlays), a
[`gob:overlay()`](../gob.md#overlays) `draw` callback — receives `g`, a drawing surface. Its coordinates are the
callback's own local pixel space: widget-local for a widget, screen for a HUD overlay, and for a gob
overlay the `sx, sy` you were handed is that gob's projected screen point. Every method is a colon call.

```lua
hafen.ui():window()
  :title("My addon")
  :size(120, 60)
  :onDraw(function(g, w, h)
    g:color(255, 200, 0)
    g:frect(0, 0, w, 3)
    g:text("mine", 6, 8)
  end)
```

## Draw

| Method | Description |
|---|---|
| `g:text(str, x, y, opts)` | draw text with its top-left at `(x, y)`; `opts` is an optional `{font, color}` |
| `g:atext(str, x, y, ax, ay, opts)` | anchored text; `ax`/`ay` `0..1` pick which point of the text sits at `(x, y)` |
| `g:rect(x, y, w, h)` | a one-pixel outline rectangle |
| `g:frect(x, y, w, h)` | a filled rectangle |
| `g:line(x1, y1, x2, y2, width)` | a line; `width` defaults to 1 |
| `g:poly(x1, y1, x2, y2, x3, y3, ...)` | a **filled** convex polygon of three points or more, in the current colour |
| `g:prect(cx, cy, radius, fraction)` | a clockwise pie or progress wedge, `fraction` `0..1` — for cooldowns and meters |
| `g:image(img, x, y, w, h)` | draw an [image asset](../asset.md) at native size, or scaled into `w × h` |
| `g:aimage(img, x, y, ax, ay)` | draw an [image asset](../asset.md) anchored, like `g:atext` |
| `g:color(r, g, b, a)` | set the draw colour, `0..255`; `g:color()` resets to white |
| `g:resource(name, x, y, w, h)` | draw an engine `.res` image **by name**, at native size or scaled |

`g` is valid **only during the draw callback**. Stashing it and drawing later does nothing — it goes inert
rather than throwing.

## Images

To draw your own PNGs, load them with [`hafen.asset`](../asset.md) and blit the handle:

```lua
local icon                                     -- upvalue for the draw callbacks below

hafen.event():on("OnLoad", function()
  icon = hafen.asset():get("icon.png")         -- load once from addons/<me>/icon.png
end)

hafen.ui():overlay():onDraw(function(g, w, h)
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

`g:resource(name, …)` draws the **client's own `.res` art** — action icons, HUD pieces, the icon of an
action a widget received from [`onDrop`](custom.md#ondrop-makes-a-widget-a-drop-target). It resolves the
resource asynchronously and caches it, and it is load-guarded: it draws nothing until the texture is ready,
then blits the resource's default image layer. It draws the **static icon only**, with no live sprite or
cooldown sweep, and a bad name simply draws nothing.

To stand an image in the 3D world rather than on screen, use [`hafen.render`](../render/sprites.md).

## Text

`g:text` and `g:atext` take an optional trailing `{ font = h, color = {r, g, b, a} }`, so one call can be
rendered in a [loaded font](../font.md) and tinted:

- **`font`** — a [`FontHandle`](../font.md). It overrides the widget's `font =` default for this call;
  omit it and you get the widget default, else the client's stock font.
- **`color`** — `{r, g, b, a}`, `0..255`. It composes with `g:color` exactly as a `g:color` call around it
  would. Omit it and the glyphs are white, tinted by the current `g:color`.

The string may also carry rich-text markup — `$font[family,size]{…}`, `$col`, `$b`, `$i`, `$u`, `$size` —
so several fonts can share one line. Feed a handle's `h:family()` to the `$font` tag; see
[mixing fonts on one line](../font.md#mix-fonts-on-one-line). Plain text with no markup and no font takes
the stock render path unchanged, and malformed markup falls back to drawing the literal string rather than
throwing.

### Text is cached across frames

Rasterising a line of text is far more expensive than any geometry call here: it is a font layout, a glyph
raster and a GPU texture upload. `g:text` and `g:atext` therefore **keep the rendered text and reuse it**,
so drawing the same string in the same font every frame rasterises it once. You do not opt in and there is
nothing to hold: the cache is per addon, invisible, and dropped with its textures when you `:reload` or
disable.

What that means when you write a draw callback:

- **The cache key is the string plus the font.** Same text, same font is a hit, however many draw sites or
  frames apart. **Colour is not in the key** — it is applied as a tint over the same raster, so drawing one
  string in two colours in one frame is *one* entry, and animating a colour costs nothing.
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

[`hafen.client():profiling():textcache()`](../client/profiling/counters.md#textcache) reports what your
addon's cache holds and its hit rate. Rich-text markup is cached on the same terms as plain text.

## See also

- [custom](custom.md) — the callbacks `g` arrives in
- [`hafen.font`](../font.md) — getting a handle to pass as `font`
- [`hafen.asset`](../asset.md) — loading the images `g:image` draws
- [`hafen.client`](../client/profiling/counters.md#textcache) — the cache's own counters
- [`hafen.render`](../render/sprites.md) — standing an image in the world instead of on screen
