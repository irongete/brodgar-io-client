# hafen.ui: what text looks like

The [sheet](README.md) properties that change what text *looks* like. None of them moves anything; a rule
that carries one leaves the others exactly as the surface had them.

```lua
local s = hafen.ui():sheet()
s:rule("*"):font(hafen.asset():get("fonts/Inter.ttf"):derive():size(12))
s:rule("chat"):color(200, 210, 200)
s:rule("tooltip"):font{ builtin = "serif", size = 13 }:color(255, 150, 90)   -- the same face, named
s:rule("window.title"):emboss(false):color(220, 205, 170)   -- a flat caption instead of a carved one
s:install()
```

## font

`rule:font(face)` says what the text is set in. A face is written one of three ways, and the last two are
what let a whole look live in a [file](README.md#a-sheet-from-data):

| Written | Is |
|---|---|
| `hafen.font():get("serif")` | a [handle](../../font.md) — one of the client's built-ins, or `hafen.asset():get(path)` for a `.ttf` your addon ships, either through a `:derive()` variant |
| `{builtin = "mono"}` | one of the client's built-ins **named**: `sans`, `serif`, `mono` or `fraktur` |
| `{asset = "fonts/Inter.ttf"}` | a file your addon ships, **named** by its path within your addon's folder |

A named face carries the variant's own properties beside the name, so the size and the weight are said in
the same value. Each is optional.

| Field | Value |
|---|---|
| `size` | [design px](../pixels.md), `> 0` |
| `bold`, `italic` | `true` or `false` |
| `aa` | `true` or `false` — antialias |

```lua
s:rule("*"):font{ builtin = "mono", size = 11 }
s:rule("window.title"):font{ asset = "fonts/Inter.ttf", size = 15, bold = true }
```

`rule:font()` reads a **handle** back whichever way the face was written, so `:family()`, `:size()` and the
rest of the [handle's own reads](../../font.md#the-variant) answer for it.

- **A name and a load are one door.** `{asset = …}` resolves through the same interning
  `hafen.asset():get(path)` does — one face and one parse per file, however many rules name it — and
  `{builtin = …}` interns exactly as `hafen.font():get(name)` does. With no field beside the name, the name
  **is** that handle, and `rule:font()` hands back the very same object.
- **A name plus a field is a variant**, derived once and **sealed** the moment the rule reads it: writing to
  the handle you read back is refused, exactly as it is for a variant you handed over yourself. Derive
  another from it instead.
- **A face names no colour.** `color` inside one is an error pointing at the rule's own
  [`color`](#color) — see the callout below.
- A name that is not one of the four built-ins, a file that is not a font, and a face naming neither a
  `builtin` nor an `asset` are each an error saying which.

**Omit `size` and each surface keeps its own.** That is the safe way to restyle: `s:rule("label"):font(h)`
swaps the *family* everywhere while every row keeps the height it was built at. If you do pass `size`,
read the geometry caveats for the surfaces it covers in [surfaces](surfaces.md) — a text field's height
comes from its background texture, and list-row heights were measured at construction, so both clip.

Every key honours `font`. Nothing else about a face travels except its family, size, weight and
antialiasing.

> **A font handle's own `color` does not style a surface.**
> `hafen.font():get("serif"):derive():color(255, 0, 0)` installed through a rule, or through
> [`widget:rule()`](README.md#restyle-one-widget), contributes its family, size and antialiasing — its
> **colour is ignored**, and `widget:style()` reports no colour for it. A handle's colour is for
> [your own drawing](../../font.md#draw-with-it): `g:text`, your own widgets. One question, "what colour is
> this surface", has exactly one answer, and it is written as a `color` where you can see it.

## color

`rule:color(r, g, b)` or `rule:color(r, g, b, a)`, `0..255` each. It also takes a colour **value** — the
`{r = …, g = …, b = …, a = …}` table every reader in this API hands back, `rule:color()` included — so one
surface's colour passes straight into another's setter.

Where a rule sets a colour, the surface **draws in it even when the client itself asks for another**. That
is what a stylesheet is for, and it is worth knowing what it costs: while the rule is on, text that carries
*meaning* in its colour is flattened with the rest — a red warning under `["*"] = {color = …}` goes the
same colour as everything else. Style one site rather than `*` when that matters.

Two things still win over a rule, and one kind of surface ignores it until you say otherwise:

- **`$col[…]` markup inside the text.** It is part of the string, not the site's choice of colour, so a
  tooltip's green and red attribute deltas survive a `["tooltip"]` colour rule.
- **A [tree key](keys.md#tree-keys) covering that widget**, and above it
  [`widget:rule()`](README.md#restyle-one-widget) — both sit nearer the draw than a site rule.
- **An embossed surface** — a window caption, a section heading, an ordinary button caption — takes its
  colour from a *texture* tiled through the glyph mask rather than from the font, so a `color` rule alone
  lands on nothing. [`emboss(false)`](#emboss) is what hands those letters back to the font, and to the
  colour beside it. See [what each key accepts](keys.md#what-each-key-accepts).

`widget:style()` reports the colour a rule set even on a surface that then throws it away: it is honest
about the rule, not about the pixels.

## emboss

`rule:emboss(v)` says whether the client's own **relief** is cut through a surface's letters, and with
what. A window caption, a section heading and an ordinary button caption are all drawn that way, and it is
why a `color` on those keys does nothing on its own: the glyphs are a *mask*, and what you see through
them is a picture.

| Written | Is |
|---|---|
| `false` | no relief at all. The letters are drawn in the font, in the rule's own [`color`](#color), and the [halo](#glow) behind them stays |
| `{texture = <art>}` | the theme's own picture tiled through the letters, in place of the client's |

The texture is a picture named the [same four ways](chrome.md#naming-a-picture) as every other art in a
rule, minus the flat colour: a colour has no pixels to tile, and one flat caption is `emboss(false)` plus a
`color`. It is tiled through the shape of the letters rather than painted into a box, so it takes none of
the `at`, `offset` and `mode` that place a picture — there is no rectangle for them to speak about.

```lua
local s = hafen.ui():sheet()
s:rule("window.title"):emboss(false):color(230, 220, 190)          -- flat captions, in one colour
s:rule("heading"):emboss{ texture = { res = "gfx/hud/fontred" } }  -- the client's red leaf, on headings
s:rule("button"):emboss{ texture = { asset = "img/brass.png" } }   -- ...and your own, on button captions
s:install()
```

- **Leaving the property out is how you keep the client's relief**, to the pixel — which is why `true` is
  an error rather than a synonym for it. `rule:emboss()` reads back `false` where a rule dropped the
  relief and `nil` where it says nothing, so the two are not the same answer.
- **It reaches the keys that are embossed and no others.** On any other key it is accepted and does
  nothing, exactly as a `bg` on a text site does — [the key table](keys.md#what-each-key-accepts) is the
  list.
- **The halo is a different property.** `emboss` says what fills the letters; the blurred shadow behind
  them is [`glow`](#glow), and neither implies the other.
- **A texture is tiled at the weight it was authored at.** A file your addon ships is in
  [design pixels](../pixels.md) and is scaled with the interface; the client's own art carries its own
  scale, exactly as it does everywhere a picture is named.

## glow

`rule:glow{color = …, radius = n}` is the blurred **halo** behind a surface's letters — the shadow every
carved caption in this client already sits on, said as a value. It reaches the same keys
[`emboss`](#emboss) does, those being the surfaces the client blurs.

| Field | Value |
|---|---|
| `color` | `{r, g, b}` or `{r, g, b, a}`, `0..255` each — also the `{r = …, g = …}` table every reader hands back |
| `radius` | [design px](../pixels.md), `>= 0` — how far the halo reaches on every side |

```lua
local s = hafen.ui():sheet()
s:rule("window.title"):glow{ color = {40, 200, 255}, radius = 4 }   -- a cyan halo behind every caption
s:rule("heading"):glow{ color = {0, 0, 0}, radius = 0 }             -- ...and none at all behind a heading
s:install()
```

- **Both fields are required.** A colour says nothing about how far it reaches and a radius nothing about
  what is drawn, so a value carrying one of the two is an error naming both. A negative radius is an error
  too: it is a distance.
- **`radius = 0` is a value, and it is the only way to say *no halo*.** Leaving the property out is the
  other answer, and it is the one that keeps the client's own blur to the pixel — the same division
  [`emboss`](#emboss) draws between `false` and silence. `rule:glow()` reads back the halo a rule set and
  `nil` where it says nothing, so the two are not the same answer.
- **One radius, and the client draws with two.** A blur has a gradient radius and a blur radius, and this
  client's pairs differ by a fraction of a pixel where they differ at all; a rule says one number and both
  take it.
- **It is independent of the relief.** Dropping the emboss leaves the halo where it was, and naming a halo
  leaves the letters filled with whatever fills them. A caption with `emboss(false)`, a `color` and a `glow`
  is three properties saying three things.
- **A halo grows the letters' own raster**, by the radius on every side, and anything the client sizes
  *around* that raster grows with it: a window's caption [plate](chrome.md#ornaments) is measured from the
  rendered caption, so a wide radius widens the plate and insets the glyphs within it. The client's own halo
  already does this at its own radius — a rule changes the number, not the behaviour.
- **It reaches the keys the client blurs and no others.** On any other key it is accepted and does nothing,
  exactly as a `bg` on a text site does — [the key table](keys.md#what-each-key-accepts) is the list.

## See also

- [keys](keys.md#what-each-key-accepts) — which surfaces honour `color`, and which are embossed
- [surfaces](surfaces.md) — the per-surface notes and geometry caveats
- [`hafen.font`](../../font.md) — where a handle comes from, and what `:derive` does
- [chrome](chrome.md) — the properties that paint rather than write
