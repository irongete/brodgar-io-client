# hafen.ui: What Text Looks Like

The [sheet](README.md) properties that change what text looks like — `font`, `color`, `emboss`, `glow`. None moves anything, and a rule carrying one leaves the others as the surface had them.

```lua
local sheet = hafen.ui():sheet()
sheet:rule("*"):font(hafen.asset():get("fonts/Inter.ttf"):derive():size(12))
sheet:rule("chat"):color{200, 210, 200}
sheet:rule("tooltip"):font{ builtin = "serif", size = 13 }:color{255, 150, 90}    -- the same face, named
sheet:rule("window.title"):emboss(false):color{220, 205, 170}                     -- a flat caption
sheet:install()
```

---

## font

`rule:font(face)` says what the text is set in. Every key honours it; only family, size, weight and antialiasing travel.

| Written | Is |
|---|---|
| `hafen.font():get("serif")`, `hafen.asset():get(path)`, either through `:derive()` | A [handle](../../font.md): a built-in, or a `.ttf` your addon ships. |
| `{builtin = "mono"}` | A built-in named: `sans`, `serif`, `mono` or `fraktur`. |
| `{asset = "fonts/Inter.ttf"}` | A file your addon ships, named by its path within your addon's folder. |

A named face carries the variant's properties beside the name, each optional: `size` (design pixels, `> 0`), `bold`, `italic`, `aa` (antialias).

```lua
sheet:rule("*"):font{ builtin = "mono", size = 11 }
sheet:rule("window.title"):font{ asset = "fonts/Inter.ttf", size = 15, bold = true }
```

| Rule | Detail |
|---|---|
| Read-back | `rule:font()` reads a handle whichever way the face was written, so [the handle's own reads](../../font.md#the-variant) answer for it. |
| One door | `{asset = …}` interns as `hafen.asset():get(path)` does, `{builtin = …}` as `hafen.font():get(name)` does. With no field beside the name, the name is that handle. |
| A name plus a field is a variant | Derived once and sealed when the rule reads it: writing to the handle read back is refused; derive another from it. |
| No colour, no outline in a face | `color` inside a face raises naming [`rule:color(c)`](#color); a handle carrying a `color` or an [`outline`](../../font.md#an-outline-round-every-glyph) is refused the same two ways, on `widget:rule()` too. A handle's colour is for [your own drawing](../../font.md#draw-with-it). |
| Refusals | A name that is not one of the four built-ins, a file that is not a font, a face naming neither `builtin` nor `asset`. |
| Omit `size` | Each surface keeps its own: `rule("label"):font(h)` swaps the family while every row keeps its height. With `size`, read the caveats in [surfaces](surfaces.md): a field's height comes from its background and list rows were measured at construction, so both clip. |

---

## color

`rule:color(c)` takes a [colour](../../shapes.md#colours): `{200, 210, 200}`, or the `{r=, g=, b=, a=}` table every reader hands back (`rule:color()` included), so one surface's colour passes into another's setter.

| Rule | Detail |
|---|---|
| Two keys take a sequence | `chat.speaker` (a colour per speaker) and `chat.urgent` (a colour per urgency level) are written `{palette = …}` or `{generate = …}` and refuse a flat colour — [the two colours the client walks](chat.md#the-two-colours-the-client-walks). A sequence on any other key raises naming those two. |
| The rule wins over the client's own colour | While it is on, text carrying meaning in its colour is flattened: a red warning under `["*"] = {color = …}` goes the same colour as the rest. Style one site when that matters. |
| Still wins over a rule | `$col[…]` markup inside the text (part of the string, so a tooltip's green and red deltas survive a `["tooltip"]` rule); a [tree key](keys.md#tree-keys) covering the widget, and [`widget:rule()`](README.md#restyle-one-widget) above it. |
| Embossed surfaces ignore it | A window caption, a section heading and an ordinary button caption take their colour from a texture tiled through the glyph mask; [`emboss(false)`](#emboss) hands the letters back to the font and the colour beside it — [what each key accepts](keys.md#what-each-key-accepts). |
| `widget:style()` | Reports the colour a rule set even where the surface throws it away. |

---

## emboss

`rule:emboss(v)` says whether the client's relief is cut through a surface's letters, and with what. Reaches the embossed keys (`window.title`, `heading`, `button`) and is accepted and inert elsewhere.

| Written | Is |
|---|---|
| `false` | No relief: the letters are drawn in the font, in the rule's own [`color`](#color); the [halo](#glow) behind them stays. |
| `{texture = <art>}` | The theme's own picture tiled through the letters, named the [same ways](chrome.md#naming-a-picture) as every art minus the flat colour, and taking no `at`, `offset` or `mode`. |

```lua
sheet:rule("window.title"):emboss(false):color{230, 220, 190}          -- flat captions, one colour
sheet:rule("heading"):emboss{ texture = { res = "gfx/hud/fontred" } }  -- the client's red leaf, on headings
sheet:rule("button"):emboss{ texture = { asset = "img/brass.png" } }   -- your own, on button captions
```

| Rule | Detail |
|---|---|
| Silence keeps the client's relief | To the pixel, which is why `true` raises rather than meaning it. `rule:emboss()` reads `false` where a rule dropped the relief and `nil` where it says nothing. |
| Independent of the halo | `emboss` fills the letters; [`glow`](#glow) is the shadow behind them. |
| Texture weight | A shipped file is design pixels, scaled with the interface; the client's art carries its own scale. |

---

## glow

`rule:glow{color = …, radius = n}` is the blurred halo behind a surface's letters, the shadow every carved caption sits on. Reaches the keys `emboss` does; inert elsewhere.

| Field | Value |
|---|---|
| `color` | A [colour](../../shapes.md#colours), either spelling. |
| `radius` | Design pixels, `>= 0`: how far the halo reaches on every side. |

```lua
sheet:rule("window.title"):glow{ color = {40, 200, 255}, radius = 4 }   -- a cyan halo behind every caption
sheet:rule("heading"):glow{ color = {0, 0, 0}, radius = 0 }             -- none at all behind a heading
```

| Rule | Detail |
|---|---|
| Both fields required | A value carrying one raises naming both; a negative radius raises. |
| `radius = 0` is a value | The only way to say no halo. Leaving the property out keeps the client's own blur to the pixel; `rule:glow()` reads `nil` then. |
| One radius, two in the client | A blur has a gradient radius and a blur radius, differing by a fraction of a pixel; a rule says one number and both take it. |
| Independent of the relief | A caption with `emboss(false)`, a `color` and a `glow` is three properties saying three things. |
| A halo grows the raster | By the radius on every side, and what the client sizes around it grows too: a wide radius widens a window's caption [plate](chrome.md#ornaments). The client's halo already does this at its own radius. |
| Focus | A caption's halo differs focused and unfocused; a rule replaces both. |

---

## See Also

- [Keys](keys.md#what-each-key-accepts) — which surfaces honour `color`, and which are embossed.
- [Surfaces](surfaces.md) — per-surface notes and geometry caveats.
- [`hafen.font`](../../font.md) — where a handle comes from, and what `:derive` does.
- [Chrome](chrome.md) — the properties that paint.
