# hafen.ui: font and color

The two [sheet](README.md) properties that change what text *looks* like. Neither moves anything; a rule
that carries only one leaves the other exactly as the surface had it.

```lua
local s = hafen.ui():sheet()
s:rule("*"):font(hafen.asset():get("fonts/Inter.ttf"):derive():size(12))
s:rule("chat"):color(200, 210, 200)
s:rule("tooltip"):font{ builtin = "serif", size = 13 }:color(255, 150, 90)   -- the same face, named
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

Two things still win over a rule, and one surface ignores it:

- **`$col[…]` markup inside the text.** It is part of the string, not the site's choice of colour, so a
  tooltip's green and red attribute deltas survive a `["tooltip"]` colour rule.
- **A [tree key](keys.md#tree-keys) covering that widget**, and above it
  [`widget:rule()`](README.md#restyle-one-widget) — both sit nearer the draw than a site rule.
- **An embossed surface** — a window caption, a section heading, an ordinary button caption — takes its
  colour from a *texture* tiled through the glyph mask rather than from the font, so it follows a `font`
  rule and ignores a `color` one. There is nothing there to colour. See
  [what each key accepts](keys.md#what-each-key-accepts).

`widget:style()` reports the colour a rule set even on a surface that then throws it away: it is honest
about the rule, not about the pixels.

## See also

- [keys](keys.md#what-each-key-accepts) — which surfaces honour `color`, and which are embossed
- [surfaces](surfaces.md) — the per-surface notes and geometry caveats
- [`hafen.font`](../../font.md) — where a handle comes from, and what `:derive` does
- [chrome](chrome.md) — the properties that paint rather than write
