# hafen.ui: The g Draw Wrapper

Every draw callback receives `g`, a drawing surface whose coordinates are the callback's own local pixel space. That is a widget's [`Draw`](custom.md), an [overlay over the screen or a widget](overlay.md), and a [`gob:overlay()`](../overlay.md) painter.

```lua
local badge_window = hafen.ui():window():title("My addon"):size(120, 60)
badge_window:on("Draw", function(draw_event)
  local graphics, width = draw_event:g(), draw_event:w()
  graphics:color(255, 200, 0)
  graphics:frect(0, 0, width, 3)
  graphics:text("mine", 6, 8)
end)
```

| Fact | Rule |
|---|---|
| Coordinate space | Widget-local for a widget and for an overlay hung on one. The screen for a HUD overlay. For a gob overlay, `sx, sy` is the gob's projected screen point. |
| Unit | Every length is a [design pixel](pixels.md): coordinates, widths, strokes, radii. The rectangle you laid out with `:size(w, h)` is the rectangle you draw into. Nothing is multiplied by [`hafen.ui():scale()`](pixels.md#read). |
| Lifetime | `g` is valid during the callback only. Stashed and used later it goes inert without throwing. |
| Permission | Unprotected: painting your own pixels changes nothing the client or the server owns. |

---

## Draw Methods

| Method | Returns | Permission | Description |
|---|---|---|---|
| `g:text(str, x, y [, opts])` | nothing | Unprotected | Text with its top-left at `(x, y)`. `opts` is `{font, color, width}` — [text](#text). |
| `g:atext(str, x, y, ax, ay [, opts])` | nothing | Unprotected | Anchored text: `ax`/`ay` in `0..1` pick which point of the text sits at `(x, y)`. |
| `g:rect(x, y, w, h)` | nothing | Unprotected | A rectangle outline one screen pixel wide, whatever the scale, tracing a design-pixel box. |
| `g:frect(x, y, w, h)` | nothing | Unprotected | A filled rectangle. |
| `g:line(x1, y1, x2, y2 [, width])` | nothing | Unprotected | A line. `width` in design pixels, default `1`, so a `4` px rule keeps the chrome's weight on a scaled client. |
| `g:poly(x1, y1, x2, y2, x3, y3, ...)` | nothing | Unprotected | A filled convex polygon of three or more points in the current colour. The one verb not clipped to the widget's box — [below](#clipping). |
| `g:prect(cx, cy, radius, fraction)` | nothing | Unprotected | A clockwise pie or progress wedge, `fraction` in `0..1`. |
| `g:image(img, x, y [, w, h])` | nothing | Unprotected | An [image asset](../asset/README.md) at native size, or scaled into `w × h`. |
| `g:aimage(img, x, y, ax, ay)` | nothing | Unprotected | An image asset anchored, as `g:atext`. |
| `g:resource(name, x, y [, w, h])` | nothing | Unprotected | A client `.res` image by name, at native size or scaled — [images](#images). |
| `g:color(r, g, b [, a])`, `g:color(c)` | nothing | Unprotected | The draw colour, `0..255` components or a [colour table](../shapes.md#colours). `g:color()` resets to white. |

| Rule | Detail |
|---|---|
| Fractional coordinates | Scaled, then rounded once to the nearest device pixel, so two things drawn a fixed distance apart stay a fixed distance apart as they move. On an unscaled client a whole number lands where it says. |
| A painter over a gob moves sub-pixel | The point handed to a [gob overlay](../overlay.md) is the object's projected point to the whole pixel. The fraction is carried under everything drawn, so the painter moves sub-pixel with the object. Text and images are sampled linearly, so at a fraction they are a fraction soft, as the object beside them is. |
| Colour is also loose numbers | `g:color` is the one place a [colour](../shapes.md#colours) is components as well as a table. Every other colour write takes the table. A table that is not a colour raises. A colour a read handed you — [`seg:color()`](../meter.md#a-segment), [`kin:color()`](../kin.md), a [snapshot's `color`](../types/README.md) — goes straight in. |

### Clipping

`g:poly` paints outside the widget it is called on: points past the box land on whatever is there, the client's HUD or another addon's window. That makes it the verb for a full-screen overlay and the wrong one for decorating a small widget. Keep its points inside the box yourself. Every other verb stops at the widget's box.

```lua
hafen.ui():overlay():add("meters"):draw(function(graphics, width, height)
  local session = hafen.session():current()
  local health = session and session:meter():find("hp")
  graphics:color(255, 200, 0)                  -- components
  graphics:frect(4, 4, 40, 6)
  graphics:color{255, 200, 0}                  -- the same colour, as a table
  graphics:frect(4, 14, 40, 6)
  if health then
    graphics:color(health:color())             -- a colour read back from the API
    graphics:frect(4, 24, 40, 6)
  end
  graphics:color()                             -- back to white
end)
```

---

## Images

| Source | Verb | Rule |
|---|---|---|
| Your own PNG | `g:image(handle, x, y [, w, h])`, `g:aimage(handle, x, y, ax, ay)` | Load it with [`hafen.asset`](../asset/README.md) and blit the handle. Transparency is preserved. The PNG's pixels are design pixels: a 32×32 file is 32×32 to [`img:size()`](../asset/handles.md#image) and covers 32×32 at every interface scale. A `nil`, wrong-type or disposed handle draws nothing. The verbs never throw. |
| The client's `.res` art | `g:resource(name, x, y [, w, h])` | Action icons, HUD pieces, the icon of an action received from [`Drop`](custom.md#drop-makes-a-widget-a-drop-target). Native size is what the client draws that resource at. `w, h` is a design-pixel box. Resolved asynchronously and cached. Draws nothing until the texture is ready, then the resource's default image layer, static, with no live sprite or cooldown sweep. A bad name draws nothing. |

```lua
local icon                                         -- loaded once, drawn every frame
hafen.event():on("Load", function() icon = hafen.asset():get("icon.png") end)

hafen.ui():overlay():add("icons"):draw(function(graphics, width, height)
  if icon then
    graphics:image(icon, 4, 4)                     -- native size
    graphics:image(icon, 4, 40, 16, 16)            -- scaled to 16x16
    graphics:aimage(icon, width - 6, 6, 1.0, 0.0)  -- anchored to the top-right corner
  end
end)
```

To stand an image in the 3D world, use [`hafen.virtual`](../virtual/sprites.md).

---

## Text

`g:text` and `g:atext` take an optional trailing `opts` table:

| Key | Type | Description |
|---|---|---|
| `font` | [`FontHandle`](../font.md) | Overrides the widget's `:font(h)` default for this call. Absent, the widget default, else the client's stock font. |
| `color` | [colour](../shapes.md#colours) | Tints the glyphs. Composes with `g:color` as a call around it would. Absent, white glyphs tinted by the current `g:color`. |
| `width` | number | Design pixels to wrap at, broken at the spaces that fit, top-left at `(x, y)`, the box growing downwards. `0` or less is refused rather than read as one line. |

| Rule | Detail |
|---|---|
| Markup | `$font[family,size]{…}`, `$col[r,g,b,a]{…}`, `$b{…}`, `$i{…}`, `$u{…}`, `$size[n]{…}`. Feed a handle's `h:family()` to `$font` — [mixing fonts on one line](../font.md#mix-fonts-on-one-line). Plain text with no markup and no font takes the stock render path. Malformed markup draws the literal string. |
| Length | One call rasterises one line, bounded at 4096 characters. `g:text`, `g:atext` and `hafen.ui():measure` raise past it, naming the ceiling. Draw a paragraph a line at a time or wrap it with `width`. |

### Measuring a line before you draw it

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():measure(str, [opts])` | `{w=, h=}` | Unprotected | The box `g:text(str, x, y, opts)` would occupy, in design pixels. `opts` is the same table. Pass the `font` and `width` you will draw with. Callable from anywhere: not a draw, no `g` needed. Refuses a `width` of `0` or less and an `opts` that is not a table. |

```lua
local line = "a long line of your own that will not fit across the plate you want to put it on"
local layout = { width = 160 }                            -- one table, measured and drawn

hafen.ui():overlay():add("panel"):draw(function(graphics, screen_width, screen_height)
  local box = hafen.ui():measure(line, layout)
  graphics:color(0, 0, 0, 160)
  graphics:frect(4, 4, box.w + 8, box.h + 8)              -- a plate exactly around the wrapped text
  graphics:color()
  graphics:text(line, 8, 8, layout)
end)
```

| Rule | Detail |
|---|---|
| It measures the raster | Markup is read as the draw reads it: `$col[255,0,0]{hello} there` measures as `hello there`. |
| Cost | One rasterisation the first time, through the same [cache](#text-is-cached-across-frames) under the same key. Measuring and then drawing rasterises once between them. |
| Font | Without `opts.font` it measures the client's stock font. Pass the handle you set with [`:font(h)`](custom.md). |
| Wrapped vs unwrapped height | Wrapping is rich text, measured by the glyphs' bounds, where a plain line takes the font's full line height. The same string at a `width` that fits it on one line can answer an `h` a pixel or two under the unwrapped one. A `width` narrower than one glyph keeps the glyph and answers a box wider than the width given. |

### Text is cached across frames

`g:text` and `g:atext` keep the rendered raster and reuse it, so the same string in the same font every frame rasterises once. The cache is per addon and dropped with its textures on `:reload` or disable.

| Rule | Detail |
|---|---|
| The key | The string, the font and the `width`. The same string at two widths is two entries. Colour is not in the key. It is a tint over the raster, so two colours in one frame are one entry and an animated colour costs nothing. |
| Outlines | An [outline](../font.md#an-outline-round-every-glyph) is part of the face. A variant carrying one and the face it derives from are two entries, each costing one entry and one blit. |
| A string that changes every frame | Re-rasterises every frame: `"HP: 100/100"` is free, `"HP: 100/100 (12.483 s)"` is a rasterisation per frame. Budget a live readout by how often its text changes. |
| Font overrides | The key carries the font generation, so installing, moving or dropping a [style](style/README.md) restyles on the next frame. Old entries age out. |
| Bounds | An LRU of at most 512 entries or 8 MiB of texture. The least recently used are evicted and disposed. One entry is bounded at 1 MiB: a bigger raster is drawn and dropped, not kept. |
| Overlay labels | [`ov:text(s)`](overlay.md#a-label-the-client-draws) over a widget and [the same verb at a gob](../overlay.md) are drawn by the client through this cache on the same key. They cost no Lua per frame. |

[`hafen.client():profiling():textcache()`](../client/profiling/counters.md#textcache) reports what your addon's cache holds and its hit rate.

---

## See Also

- [Pixels](pixels.md) — the unit every coordinate is in.
- [Custom](custom.md) — the callbacks `g` arrives in.
- [Overlays](overlay.md) — the painters over the HUD and over one widget.
- [`hafen.font`](../font.md) — the handle `font` takes.
- [`hafen.asset`](../asset/README.md) — the images `g:image` draws.
- [`hafen.client`](../client/profiling/counters.md#textcache) — the cache's counters.
- [`hafen.virtual`](../virtual/sprites.md) — an image standing in the world.
