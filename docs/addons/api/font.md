# hafen.font: typography

Get a font into a **private handle** your addon holds — one of the client's [built-ins](#the-built-ins) by
name, or your own `.ttf` as an [asset](asset.md) — and then do one of exactly two things with it: draw with
it [yourself](#draw-with-it), or name it in a [stylesheet](ui/style/README.md) rule to restyle one of the
client's own surfaces.

```lua
local body = hafen.font("serif"):derive{ size = 12 }
hafen.ui.window{ title = "Mine", size = {200, 120}, font = body, onDraw = function(g, w, h)
  g:text("this text is in my font", 6, 6)
end }
```

There is **no shared cross-addon registry**: a handle is a value your addon keeps, and another addon cannot
look it up. No name collisions, no coupling. Everything here is client-side, cosmetic and **ungated**.

## Where a font comes from

A handle comes from exactly one of two places, and which one you use is decided by **who owns the file**:

```lua
local h = hafen.font("serif")                     -- a BUILT-IN: the client already owns it
local h = hafen.asset("fonts/Inter.ttf")          -- a FILE THIS ADDON SHIPS
```

Neither takes options. Size and style are a [`:derive{…}`](#the-variant) away, never part of the load.

### The built-ins

`hafen.font` is **callable**: `hafen.font(name)` is the handle for one of the client's built-in fonts —
`"sans"`, `"serif"`, `"mono"`, `"fraktur"`. They are engine-owned, so they are *addressed by name* rather
than loaded: interned (`hafen.font("mono") == hafen.font("mono")`), no file, no path, and **no lifetime**, so
a built-in carries none of the [asset verbs](asset.md#every-asset). A typo, a number or a path raises an
error listing the four names and pointing paths at `hafen.asset`.

### Your own ttf

A font file your addon ships is an [**asset**](asset.md), loaded through the same door as an image, a model
or a data file: `hafen.asset("fonts/Inter.ttf")`. It is sandboxed — absolute paths and `..` escapes are
rejected — **interned per path**, so one parse per file however many times you call it, and disposed
automatically on reload or disable. Loading also registers the family into the JVM, so `h:family()` resolves
in a [`$font[…]` tag](#mix-fonts-on-one-line). Being an asset, it also answers `:type()`, `:path()` and
`:dispose()`.

### The variant

`h:derive(opts)` makes a cheap variant and never mutates `h`. Every key is optional:

| Key | Meaning |
|---|---|
| `size` | logical px, passed through the client's UI scale. Omit to use the stock size of whatever surface it is applied to |
| `aa` | antialias on or off. Omit to inherit the surface's stock setting |
| `bold` / `italic` | style, baked into the font |
| `color` | text colour `{r, g, b, a}`, `0..255` — **for your own drawing only**, see below |

Either way you hold an opaque **`FontHandle`**; no AWT font object crosses into Lua.

| Method | Returns | Description |
|---|---|---|
| `h:derive(opts)` | `FontHandle` | a variant with a different `size`, `aa`, `bold`, `italic` or `color` |
| `h:family()` | string | the family name — feed it to a `$font[family, size]{…}` tag for per-run mixing |
| `h:size()` | number \| nil | the handle's logical px size, `nil` if unset |

A derived handle is a **variant of a font, not a file**: like a built-in it carries no `:type`, `:path` or
`:dispose`, even when the handle it came from was an asset.

> **`color` is the one option that does not travel.** It applies wherever *you* draw with the handle, and is
> **ignored** when the handle is installed on a client surface through
> [a sheet rule](ui/style/text.md#font) or [`widget:skin`](ui/style/README.md#restyle-one-widget). A
> surface's colour is a [sheet property](ui/style/text.md#color), stated where you can read it, not a value
> hidden inside a font handle. `size`, `aa`, `bold` and `italic` travel everywhere.

## Draw with it

Applying a font to your **own** drawing is fully isolated: it touches only your widgets' pixels, so there is
no conflict and nothing to revert. The stock UI and every other addon are untouched.

### The widget default

```lua
local h = hafen.font("serif"):derive{ size = 12 }
hafen.ui.window{ title = "Mine", size = {200, 120}, font = h, onDraw = function(g, w, h)
  g:text("this text is in my font", 6, 6)   -- no per-call opts, so it uses the widget's font=
end }
hafen.ui.widget{ size = {80, 20}, font = h } -- same, for a bare widget
```

`font =` sets the **default font** for every `g:text` and `g:atext` the widget draws that gives no per-call
font. It does not restyle the window's *title bar* — that is the `window.title`
[site key](ui/style/keys.md#site-keys).

### The per-call option

```lua
g:text(str, x, y, { font = h, color = {r, g, b, a} })
g:atext(str, x, y, ax, ay, { font = h, color = {r, g, b, a} })
```

`font` renders this one call in that handle, overriding the widget default; omit it and you get the widget
default, else the client stock. `color` tints the glyphs and composes with `g:color` exactly as a `g:color`
call around it would; omit it and the glyphs are white, tinted by the current `g:color`. Coordinates stay
positional, the same as every other [`g:` call](ui/drawing.md).

The rendered text is [cached per addon](ui/drawing.md#text-is-cached-across-frames), keyed by the string
*and* the handle, so redrawing the same string in the same font every frame rasterises it once.

### Mix fonts on one line

`g:text` and `g:atext` interpret **rich-text markup**, so you can mix fonts, styles and colours inside a
single string. Feed a handle's `h:family()` to the `$font[family, size]{…}` tag:

```lua
g:text(("$font[%s,16]{Fancy} normal"):format(h:family()), 6, 6)   -- two fonts, one line
g:text("$col[235,180,80]{$b{bold} orange} plain", 6, 26)          -- $col, $b, $i, $u, $size too
```

This works because loading a `.ttf` [asset](asset.md) registers its family into the JVM. Plain text with no
`$` and no `font=` takes the stock render path; malformed markup falls back to drawing the literal string
and never throws.

## Restyle a client surface

```lua
hafen.ui.skin{ ["window.title"] = { font = h } }   -- install THIS addon's sheet
hafen.ui.skin(nil)                                 -- drop it; every surface it styled falls back
```

A font is **one property of a rule**, and the key is a [selector](ui/selectors.md), so there is one
vocabulary for "which part of the UI" rather than a font-specific one beside it. The call, the
one-sheet-per-addon rule, the properties and the cascade are documented under
[the stylesheet](ui/style/README.md); which keys honour a `font` is [the key table](ui/style/keys.md#what-each-key-accepts);
what each surface *is*, and what it does with a size, is [surfaces](ui/style/surfaces.md).

To restyle **one** widget you already hold rather than a family of surfaces, use
[`widget:skin{font = h}`](ui/style/README.md#restyle-one-widget).

## Example

```lua
local h
hafen.event():on("OnLoad", function()
  h = hafen.asset("fonts/Inter.ttf"):derive{ size = 11 }   -- or hafen.font("serif"):derive{ size = 11 }
end)

hafen.slash():register("bigserif", function()
  hafen.ui.skin{ ["*"] = { font = h } }   -- most UI text becomes serif, live
end)
-- reverted automatically when the addon is reloaded or disabled, or explicitly with hafen.ui.skin(nil)
```

The bundled **`theme`** example addon goes one step further: its whole look, text and
[chrome](ui/style/chrome.md) alike, is a `theme.json` read through [`hafen.asset`](asset.md#data) and
[`hafen.json`](json.md), so its Lua never names a font, a size, a colour, a surface or a pixel.

## See also

- [`hafen.asset`](asset.md) — the one door for a `.ttf` or `.otf` your addon ships
- [the stylesheet](ui/style/README.md) — installing a handle on the client's own surfaces
- [drawing](ui/drawing.md) — `g:text`, `g:atext` and the raster cache behind them
- [`hafen.client`](client/profiling/counters.md#textcache) — what that cache is holding
- [conventions](conventions.md) — owned resources and teardown
