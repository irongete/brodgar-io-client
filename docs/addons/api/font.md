# hafen.font: Typography

A font as a private handle your addon holds: one of the client's [built-ins](#the-built-ins) by name, or your own `.ttf` as an [asset](asset/README.md). Draw with it [yourself](#draw-with-it), or name it in a [stylesheet](ui/style/README.md) rule that restyles a client surface. Client-side, cosmetic, unprotected.

```lua
local body_font = hafen.font():get("serif"):derive():size(12)
local window = hafen.ui():window():title("Mine"):size(200, 120):font(body_font)
window:on("Draw", function(draw_event) draw_event:g():text("this text is in my font", 6, 6) end)
```

---

| Rule | Detail |
|---|---|
| No cross-addon registry of handles | A handle is a value your addon keeps. Another addon cannot look it up. What a `.ttf` load puts somewhere shared is its [family name](#mix-fonts-on-one-line). |
| Two sources, no options | `hafen.font():get("serif")` for a built-in the client owns. `hafen.asset():get("fonts/Inter.ttf")` for a file this addon ships. Size and style are a [`:derive()`](#the-variant) away. |

## Where a font comes from

### The built-ins

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.font():get(name)` | `FontHandle` | Unprotected | One of `"sans"`, `"serif"`, `"mono"`, `"fraktur"`. A typo, a number or a path raises listing the names and pointing paths at `hafen.asset`. |
| `hafen.font():list(filter)` | `FontHandle[]` | Unprotected | All of the client's built-ins, always, not your addon's history of asking. |

The client's own, addressed by name: interned, no file, no path, no lifetime, so a built-in carries none of the [asset verbs](asset/handles.md#every-asset). No `:add`, no `:remove`.

### Your own ttf

A font file your addon ships is an [asset](asset/README.md): `hafen.asset():get("fonts/Inter.ttf")`. It is sandboxed, interned per path (one parse per file), and freed on reload or disable. It answers `:type()` and `:path()`, and is dropped early with [`hafen.asset():remove(handle)`](asset/collection.md#the-collection). Loading registers the family with the JVM, so `handle:family()` resolves in a [`$font[…]` tag](#mix-fonts-on-one-line).

> **The family registration is the client's for the rest of its run.** No un-register. A family your file registered stays resolvable in a `$font[…]` tag after your handle is freed and after your addon is disabled. Any other addon's markup resolves it too. The face's memory stays with it. Two files claiming one family name is first-come: the tag draws whichever registered first, while each handle draws its own file. A restart clears it.

### The variant

`handle:derive()` makes a variant, with no file re-read, and hands it back to configure. It never changes `handle`, and a property not set is the one it came with. You hold an opaque `FontHandle`. No AWT object crosses into Lua.

```lua
local small_font = hafen.font():get("serif"):derive():size(11)
local loud_font  = small_font:derive():size(16):bold(true):color{235, 180, 80}
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `handle:type()` | `string` | Unprotected | `"builtin"`, `"font"` for one loaded from a file, `"variant"` for one `:derive()` made. |
| `handle:derive()` | `FontHandle` | Unprotected | A fresh variant of this font. |
| `handle:family()` | `string` | Unprotected | The family name, for a `$font[family, size]{…}` tag. |
| `handle:info()` | `table` | Unprotected | `{ type, family, size, bold, italic, aa, color, outline, path }`, a key absent where its verb answers `nil`. |
| `handle:size()` / `handle:size(pixels)` | `number \| nil` | Unprotected | [Design px](ui/pixels.md), a whole number `1..512`. `nil` is the stock size of the surface it is applied to. Writing `nil` [undoes](conventions.md#nil-is-an-error-unless-it-means-something) the size this variant carries. |
| `handle:aa()` / `handle:aa(flag)` | `boolean \| nil` | Unprotected | Antialias. `nil` inherits the surface's stock setting. Writing `nil` undoes the flag. |
| `handle:bold()` / `handle:bold(flag)` | `boolean` | Unprotected | Style, baked into the font. |
| `handle:italic()` / `handle:italic(flag)` | `boolean` | Unprotected | Style, baked into the font. |
| `handle:color()` / `handle:color(color)` | [colour](shapes.md#colours) `\| nil` | Unprotected | Text colour, for your own drawing only. |
| `handle:outline()` / `handle:outline(color)` | [colour](shapes.md#colours) `\| nil` | Unprotected | An [edge](#an-outline-round-every-glyph) baked one pixel round every glyph, for your own drawing only. `nil` is no edge. Writing `nil` undoes it. |

| Rule | Detail |
|---|---|
| Rasterised at the size asked | On the thread that draws it, so a size above 512 raises. No screen shows a 20000 px glyph, and deriving one is seconds of CPU and hundreds of megabytes. |
| A variant is not a file | Like a built-in it carries no `:path`, even derived from an asset, and `hafen.asset():remove(it)` refuses it. `handle:type()` says which you hold. Ask before `:path()`. `tostring(hafen.font():get("serif"))` is `Font(Serif)`. `tostring(hafen.asset():get("fonts/Inter.ttf"))` is `Asset(font, fonts/Inter.ttf)`. A face is an [object, not a table](asset/handles.md#every-asset). |
| Only a fresh variant is writable, and only until used | A built-in and a loaded `.ttf` are shared values and refuse a setter naming `:derive()`. Once a variant is handed to a rule, a widget, an overlay or a draw call, a later write is refused too. Derive another. A face a rule refuses is not used and stays writable. |
| `color` and `outline` do not travel | They apply where you draw with the handle (the widget default, the per-call option). `size`, `aa`, `bold` and `italic` travel everywhere. |
| A decorated handle in a rule | Refused by [a sheet rule](ui/style/text.md#font) or [`widget:rule()`](ui/style/README.md#restyle-one-widget). The refusal names `rule:color(color)` for the colour, since a surface's colour is a [sheet property](ui/style/text.md#color). For the outline it names your own drawing: no client surface is drawn with a decorated face. |

## Draw with it

Applying a font to your own drawing touches only your widgets' pixels: nothing to revert.

| Where | Detail |
|---|---|
| The widget default | `hafen.ui():window():font(handle)` or `hafen.ui():widget():font(handle)` sets the default for every `graphics:text` and `graphics:atext` the widget draws with no per-call font. It does not restyle the title bar, which is the `window.title` [site key](ui/style/keys.md#site-keys). |
| The per-call option | `graphics:text(text, x, y, { font = handle, color = {r, g, b, a} })`, `graphics:atext(text, x, y, ax, ay, { font = handle, color = {r, g, b, a} })`. `font` overrides the widget default (omitted: the widget default, else the client stock). `color` tints the glyphs and composes with `graphics:color` (omitted: white, tinted by the current `graphics:color`). |
| Coordinates stay positional | As in every [`graphics:` call](ui/drawing.md). `graphics:color` is [the one place](shapes.md#colours) a colour is loose components as well as a table. |
| Cached | Per addon with the handle in the key ([text is cached across frames](ui/drawing.md#text-is-cached-across-frames)): the same string in the same font every frame rasterises once. |

### An outline round every glyph

`handle:outline(color)` bakes an edge one pixel wide round every glyph. It keeps a number readable over ground, an item icon, anything whose colour you do not control.

```lua
local tag_font = hafen.font():get("sans"):derive():size(11):bold(true):outline{0, 0, 0}
hafen.ui():overlay():add("count"):draw(function(graphics, width, height)
  graphics:text("12", 40, 40, { font = tag_font })      -- white digits, a black edge, one blit
end)
```

| Rule | Detail |
|---|---|
| A property of the face | Drawn into the cached image once. The label costs the single blit an unoutlined one costs. Drawing the edge yourself is the string in black four times, one step out each way, then white. That is five draws and five [cache entries](ui/drawing.md#text-is-cached-across-frames) every frame. |
| The raster grows by one pixel each side | [`hafen.ui():measure`](ui/drawing.md#measuring-a-line-before-you-draw-it) answers a box two pixels wider and taller. |
| A colour tints the whole raster, edge included | `color` (per-call, the handle's own, or a `graphics:color` around the call) multiplies glyphs and edge alike. Black comes through every tint unchanged. An edge of `{255, 0, 0}` under a green tint is black. |

### Mix fonts on one line

`graphics:text` and `graphics:atext` interpret rich-text markup: `$font[family, size]{…}` with a handle's `handle:family()`, and `$col`, `$b`, `$i`, `$u`, `$size`. Plain text with no `$` and no `font=` takes the stock render path. Malformed markup draws the literal string and never throws.

```lua
local fancy_font = hafen.font():get("fraktur"):derive():size(16)
window:on("Draw", function(draw_event)
  local graphics = draw_event:g()
  graphics:text(("$font[%s,16]{Fancy} normal"):format(fancy_font:family()), 6, 6)   -- two fonts, one line
  graphics:text("$col[235,180,80]{$b{bold} orange} plain", 6, 26)
end)
```

## Restyle a client surface

```lua
hafen.ui():sheet():rule("window.title"):font(body_font)  -- name the rule...
hafen.ui():sheet():install()                            -- ...and install this addon's sheet
hafen.ui():sheet():release()                            -- give it back; every surface it styled falls back
```

| Rule | Detail |
|---|---|
| A rule takes the handle, or the face named | `{builtin = "mono", size = 11}` for a built-in, `{asset = "fonts/Inter.ttf", size = 12, bold = true}` for a file you ship. Each resolves to the handle the loaders hand back, which lets [a whole theme be a file](ui/style/README.md#a-sheet-from-data). The fields are in [text](ui/style/text.md#font). |
| One property of a rule | The key is a [selector](ui/selectors.md). The call, the one-sheet-per-addon rule, the properties and the cascade are [the stylesheet](ui/style/README.md). Which keys honour a `font` is [the key table](ui/style/keys.md#what-each-key-accepts). What each surface does with a size is [surfaces](ui/style/surfaces.md). One widget you hold is [`widget:rule():font(handle)`](ui/style/README.md#restyle-one-widget). |

## Example

```lua
local body_font
hafen.event():on("Load", function()
  body_font = hafen.asset():get("fonts/Inter.ttf"):derive():size(11)   -- or a built-in face, derived
end)
hafen.console():on("bigserif", function()
  hafen.ui():sheet():rule("*"):font(body_font):sheet():install()   -- most UI text becomes serif, live
end)
-- reverted automatically when the addon is reloaded or disabled, or explicitly with sheet:release()
```

A whole look: the face, the [chrome](ui/style/chrome.md) and every colour live in a data file read through [`hafen.asset`](asset/handles.md#data) and [`hafen.json`](json.md). The installing Lua names no font, size, colour, surface or pixel ([theming](../guides/theming.md)).

---

## See Also

- [`hafen.asset`](asset/README.md) — the one door for a `.ttf` or `.otf` your addon ships.
- [The stylesheet](ui/style/README.md) — installing a handle on the client's own surfaces.
- [Drawing](ui/drawing.md) — `graphics:text`, `graphics:atext` and the raster cache behind them.
- [`hafen.client`](client/profiling/counters.md#textcache) — what that cache is holding.
- [Conventions](conventions.md) — owned resources and teardown.
