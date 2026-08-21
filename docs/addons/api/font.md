# hafen.font: typography

Get a font into a **private handle** your addon holds — one of the client's [built-ins](#the-built-ins) by
name, or your own `.ttf` as an [asset](asset.md) — and then do one of exactly two things with it: draw with
it [yourself](#draw-with-it), or name it in a [stylesheet](ui/style/README.md) rule to restyle one of the
client's own surfaces.

```lua
local body = hafen.font():get("serif"):derive():size(12)
local win = hafen.ui():window():title("Mine"):size(200, 120):font(body)
win:on("Draw", function(ev) ev:g():text("this text is in my font", 6, 6) end)
```

There is **no shared cross-addon registry**: a handle is a value your addon keeps, and another addon cannot
look it up. No name collisions, no coupling. Everything here is client-side, cosmetic and **unprotected**.

## Where a font comes from

A handle comes from exactly one of two places, and which one you use is decided by **who owns the file**:

```lua
local h = hafen.font():get("serif")               -- a BUILT-IN: the client already owns it
local h = hafen.asset():get("fonts/Inter.ttf")    -- a FILE THIS ADDON SHIPS
```

Neither takes options. Size and style are a [`:derive()`](#the-variant) away, never part of the load.

### The built-ins

`hafen.font()` is the collection of the client's built-in fonts your addon has named: `:get(name)` is the
handle for one of `"sans"`, `"serif"`, `"mono"`, `"fraktur"`, and `:list(filter)` is the ones you have asked
for so far. They are engine-owned, so they are *addressed by name* rather than loaded: interned, no file, no
path, and **no lifetime**, so a built-in carries none of the [asset verbs](asset.md#every-asset). There is no
`:add` — you cannot make a built-in — and no `:remove`, since there is no lifetime to end. A typo, a number
or a path raises an error listing the four names and pointing paths at `hafen.asset`.

### Your own ttf

A font file your addon ships is an [**asset**](asset.md), loaded through the same door as an image, a model
or a data file: `hafen.asset():get("fonts/Inter.ttf")`. It is sandboxed — absolute paths and `..` escapes are
rejected — **interned per path**, so one parse per file however many times you call it, and disposed
automatically on reload or disable. Loading also registers the family into the JVM, so `h:family()` resolves
in a [`$font[…]` tag](#mix-fonts-on-one-line). Being an asset, it also answers `:type()`, `:path()` and
`:dispose()`.

### The variant

`h:derive()` makes a cheap variant of `h` and hands it back for you to configure. It never changes `h`: the
variant starts as a copy, so a property you do not set is the one it came with.

```lua
local small = hafen.font():get("serif"):derive():size(11)
local loud  = small:derive():size(16):bold(true):color{235, 180, 80}
```

Either way you hold an opaque **`FontHandle`**; no AWT font object crosses into Lua. Each property is one
name — bare it reads, with a value it writes and hands the handle back, so a variant is one chain.

| Method | Returns | Description |
|---|---|---|
| `h:derive()` | `FontHandle` | a fresh variant of this font, ready to configure |
| `h:family()` | string | the family name — feed it to a `$font[family, size]{…}` tag for per-run mixing |
| `h:size()` / `h:size(px)` | number \| nil | [design px](ui/pixels.md), the same unit every coordinate takes. `nil` means the stock size of whatever surface it is applied to |
| `h:aa()` / `h:aa(b)` | boolean \| nil | antialias. `nil` inherits the surface's stock setting |
| `h:bold()` / `h:bold(b)` | boolean | style, baked into the font |
| `h:italic()` / `h:italic(b)` | boolean | style, baked into the font |
| `h:color()` / `h:color(c)` | [colour](shapes.md#colours) \| nil | text colour — **for your own drawing only**, see below |

A derived handle is a **variant of a font, not a file**: like a built-in it carries no `:type`, `:path` or
`:dispose`, even when the handle it came from was an asset.

> **Only a fresh variant is writable, and only until you use it.** A built-in and a loaded `.ttf` are shared
> values, so writing one would restyle every surface already using it: they refuse a setter, naming
> `:derive()`. And once you have handed a variant to a rule, a widget or a draw call, that surface has read
> it — so a later write is refused too, rather than looking like it took and changing nothing. Derive
> another variant instead; deriving from a handle always works.

**`color` is the one option that does not travel.** It applies wherever *you* draw with the handle — the
widget default and the per-call option below — and a handle carrying one is **refused** where it would style
a client surface, through [a sheet rule](ui/style/text.md#font) or
[`widget:rule()`](ui/style/README.md#restyle-one-widget), naming `rule:color(c)` instead. A surface's
colour is a [sheet property](ui/style/text.md#color), stated where you can read it, not a value hidden inside
a font handle: give those a face carrying no colour of its own, and say the colour beside the font. `size`,
`aa`, `bold` and `italic` travel everywhere.

## Draw with it

Applying a font to your **own** drawing is fully isolated: it touches only your widgets' pixels, so there is
no conflict and nothing to revert. The stock UI and every other addon are untouched.

### The widget default

```lua
local h = hafen.font():get("serif"):derive():size(12)
local win = hafen.ui():window():title("Mine"):size(200, 120):font(h)
win:on("Draw", function(ev)
  ev:g():text("this text is in my font", 6, 6)   -- no per-call opts: the widget's own font
end)
hafen.ui():widget():size(80, 20):font(h)     -- same, for a bare widget
```

`:font(h)` sets the **default font** for every `g:text` and `g:atext` the widget draws that gives no
per-call font. It does not restyle the window's *title bar* — that is the `window.title`
[site key](ui/style/keys.md#site-keys).

### The per-call option

```lua
g:text(str, x, y, { font = h, color = {r, g, b, a} })
g:atext(str, x, y, ax, ay, { font = h, color = {r, g, b, a} })
```

`font` renders this one call in that handle, overriding the widget default; omit it and you get the widget
default, else the client stock. `color` tints the glyphs and composes with `g:color` exactly as a `g:color`
call around it would; omit it and the glyphs are white, tinted by the current `g:color`. Coordinates stay
positional, the same as every other [`g:` call](ui/drawing.md) — and so does `g:color` itself, which is
[the one place](shapes.md#colours) a colour is loose components as well as a table.

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
hafen.ui():sheet():rule("window.title"):font(h)     -- name the rule...
hafen.ui():sheet():install()                       -- ...and install THIS addon's sheet
hafen.ui():sheet():drop()                          -- drop it; every surface it styled falls back
```

A rule takes the handle, or **the same face named**: `{builtin = "mono", size = 11}` for one of the
built-ins, `{asset = "fonts/Inter.ttf", size = 12, bold = true}` for a file you ship. A name resolves to the
very handle the two loaders above hand back, and naming rather than loading is what lets a
[whole theme be a file](ui/style/README.md#a-sheet-from-data) with no Lua in it. The fields a named face
carries are in [text](ui/style/text.md#font).

A font is **one property of a rule**, and the key is a [selector](ui/selectors.md), so there is one
vocabulary for "which part of the UI" rather than a font-specific one beside it. The call, the
one-sheet-per-addon rule, the properties and the cascade are documented under
[the stylesheet](ui/style/README.md); which keys honour a `font` is
[the key table](ui/style/keys.md#what-each-key-accepts);
what each surface *is*, and what it does with a size, is [surfaces](ui/style/surfaces.md).

To restyle **one** widget you already hold rather than a family of surfaces, use
[`widget:rule():font(h)`](ui/style/README.md#restyle-one-widget).

## Example

```lua
local h
hafen.event():on("Load", function()
  h = hafen.asset():get("fonts/Inter.ttf"):derive():size(11)   -- or a built-in face, derived
end)

hafen.slash():register("bigserif", function()
  hafen.ui():sheet():rule("*"):font(h):sheet():install()   -- most UI text becomes serif, live
end)
-- reverted automatically when the addon is reloaded or disabled, or explicitly with sheet:drop()
```

A whole look goes one step further: the face, the [chrome](ui/style/chrome.md) and every colour live in a
data file read through [`hafen.asset`](asset.md#data) and [`hafen.json`](json.md), and the Lua that installs
it never names a font, a size, a colour, a surface or a pixel. See [theming](../guides/theming.md).

## See also

- [`hafen.asset`](asset.md) — the one door for a `.ttf` or `.otf` your addon ships
- [the stylesheet](ui/style/README.md) — installing a handle on the client's own surfaces
- [drawing](ui/drawing.md) — `g:text`, `g:atext` and the raster cache behind them
- [`hafen.client`](client/profiling/counters.md#textcache) — what that cache is holding
- [conventions](conventions.md) — owned resources and teardown
