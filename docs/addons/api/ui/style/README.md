# hafen.ui: the stylesheet

One table says what the client looks like: a [selector](../selectors.md) as the key, a table of style
properties as the value, applied live and owned by the addon that installed it. Reach for it to restyle
the client's own surfaces — text, chrome, or where its windows sit. To draw your *own* pixels, use
[custom](../custom.md) and [drawing](../drawing.md) instead; nothing here is gated.

```lua
local body = hafen.asset("fonts/Inter.ttf"):derive{ size = 12 }
hafen.ui.skin{
  ["*"]            = { font = body },                                -- the global fallback
  ["window.title"] = { font = body:derive{ size = 14, bold = true } },
  ["chat"]         = { font = hafen.font("mono"):derive{ size = 13 }, color = {200, 210, 200} },
  ["tooltip"]      = { color = {255, 150, 90} },                     -- colour alone: the font stays stock
}
```

## Reading order

[keys](keys.md) says **which** surfaces a rule reaches and which properties each one honours.
[surfaces](surfaces.md) says what each of the client's own surfaces *is*, and how it behaves when a rule
lands on it. Then the property pages: [text](text.md) for `font` and `color`, [chrome](chrome.md) for `bg`,
`border` and `pad`, [geometry](geometry.md) for `pos`, `size` and `anchor`. This page holds the call, the
per-widget verb, the cascade they all resolve through, and the edge of the system.

## Install a sheet (ungated)

| Call | Description |
|---|---|
| `hafen.ui.skin{…}` | install this addon's stylesheet, **replacing** whatever it had |
| `hafen.ui.skin(nil)` | drop it; every surface it styled falls back |

**An addon owns exactly one sheet.** A second `skin{…}` replaces the first *whole*, not rule by rule: a
surface the new sheet no longer names falls back on the spot. So to turn one rule off, re-apply the sheet
without it — keep your rules in a table and pass that:

```lua
local rules = {}
local function restyle(key, props)
  rules[key] = props                                     -- props = nil removes the rule
  if next(rules) == nil then hafen.ui.skin(nil) else hafen.ui.skin(rules) end
end
```

The change is **live** — existing text re-renders on the spot — and the sheet is **owned**: it is dropped
automatically on your addon's `:reload` or disable, so the stock client is always restorable.

**A sheet is an ordinary table, so it can come from anywhere, including a file.** The bundled **`theme`**
example addon reads a `theme.json` through [`hafen.asset`](../../asset.md#data) and
[`hafen.json`](../../json.md); its Lua never names a surface, a font, a size, a colour or a pixel. Exactly
two values in a rule are things JSON cannot carry, and both because they are **handles**: a font's face and
an [image](chrome.md). Map those two and everything else — a colour array, a border's four slice insets, a
`pad`, an [anchor's corner and offset](geometry.md#anchor) — is already the sheet's own shape and travels
verbatim. A whole client look, windows included, with no code of its own, is one command away.

**Saving a layout is your addon's business, not the engine's**, and it is small: a layout you can read back
with [`widget:position()`](../widget.md#read) is a table of numbers, and [`hafen.store`](../../store.md)
already
persists tables. `theme` demonstrates the whole of it — one command reads where its windows currently are
and keeps them account-wide, another re-applies them over the file's own placement, a third drops them.
There is no profile system here because a sheet is data and an addon already has a store.

## Properties

| Property | Value | Notes |
|---|---|---|
| `font` | a [font handle](../../font.md) | `hafen.font(name)` or `hafen.asset(path)`, optionally `:derive{size=, bold=, …}` — see [text](text.md) |
| `color` | `{r, g, b, a}`, `0..255` | also spelled `{r = …, g = …, b = …}`, the shape every reader hands back — see [text](text.md#color) |
| `bg` | `{color = {r,g,b,a}}` **or** `{image = hafen.asset(…)}` | the surface something is painted on — see [chrome](chrome.md) |
| `border` | `{image = hafen.asset(…), slice = {l, t, r, b}}` | a 9-slice frame — see [chrome](chrome.md) |
| `pad` | a number of pixels, `>= 0` | the space a surface keeps between its frame and its content — see [`pad`](chrome.md#pad) |
| `pos` | `{x, y}`, also `{x = …, y = …}` | where the widget sits inside its parent, in raw px — **tree keys only**, see [geometry](geometry.md) |
| `anchor` | `{to =, at =, offset =}` | the same place said as a relationship — see [`anchor`](geometry.md#anchor) |
| `size` | `{w, h}`, also `{x = …, y = …}` | how big it is; a window's *content* size — **tree keys only**, see [geometry](geometry.md) |

**The properties are independent.** A rule may carry any one alone: a colour-only rule leaves the surface's
own font exactly as it is, a `border`-only rule leaves its background. A rule carrying none styles nothing.

An **unknown property is an error** naming the ones that exist — unlike an unresolved key, a misspelt
property has no later meaning to wait for. So is `pos`, `anchor` or `size` on a key that names a render
**site** rather than a widget: those three lay out a *widget*, and a site is where the client draws.

## Restyle one widget

A [site key](keys.md#site-keys) restyles a *family* of surfaces across the whole client; a
[tree key](keys.md#tree-keys) restyles the widgets a selector *matches*. To restyle **one** widget you
already hold, call `skin` on its [Widget object](../widget.md):

```lua
local n = hafen.ui():at(hafen.ui():mouse().x, hafen.ui():mouse().y)   -- the widget under the cursor
n:skin{ font = h, color = {200, 180, 140} }   -- this widget and everything inside it; its SIBLINGS untouched
n:skin()                                      --> { font = h, color = {r=200, g=180, b=140, a=255} }
n:skin(nil)                                   -- drop it again
```

| Call | Returns | Description |
|---|---|---|
| `widget:skin{…}` | self | install **your** style on that widget — the same properties a sheet rule carries |
| `widget:skin()` | table \| nil | read **your own** entry back, exactly as you wrote it; `nil` if you have none |
| `widget:skin(nil)` | self | drop **your** entry; another addon's on the same widget is untouched |
| `widget:style()` | table \| nil | what the widget **resolves to**; `nil` when nothing names it |

- **It covers the whole subtree.** The client draws parents before children, so a style on a window reaches
  its caption, its labels, its button captions, its list rows, and any widget created inside it *later*. A
  child with a style of its own wins inside itself.
- **It is the top of the cascade**, so inside a skinned widget it wins whatever surface the text belongs
  to — including text drawn by the game's **own resource code**, the one place a site rule could never
  reach. It wins the properties it names and no others.
- **`:style()` is the read-back for the *result*.** `:skin()` hands back what *you* wrote; `:style()` hands
  back your entry folded over every tree rule that matches the widget. Both answer for that widget alone: a
  style inherited from an enclosing widget is applied at the *draw*, not resolved onto the child, so a child
  inside a skinned window still reads `nil`.
- **`widget:skin{pos = …}` is an error**, and so are `size` and `anchor`: the hand-named level of the layout
  cascade is the **verb**, [`w:position(x, y)`](../native.md). One way per operation.
- **On a window, it dresses that window's chrome**, and one level down, a [panel's](chrome.md#panels) box.
  On anything that wears no chrome the three chrome properties are inert, still readable through `:style()`.
- **Owned and short-lived.** The entry is tagged with your addon and reverted on `:reload` or disable, and
  it is held **weakly against the widget**: when that window closes it goes with it, and a stashed object
  reports `nil` from every accessor while `:skin(nil)` becomes a no-op rather than an error.
- **Some windows have no text to restyle.** An Inventory or Equipment window contains item *icons*; its
  only text is the caption, so a style there shows up on the title bar alone. Pick a text-rich window when
  you want to see the effect.
- `hafen.ui():root():skin{…}` works and covers the entire client, but that is what a sheet's `["*"]` rule
  is for.

## The cascade

Resolution is **most-specific first**: `widget:skin{…}` → the matching [tree rule](keys.md#tree-keys) → the
matching [site rule](keys.md#site-keys) → the `*` rule → the client's stock. So `["*"]` alone changes
everything, and any other key refines one surface, or one widget, out of that cascade.

**Every level composes per property, never wholesale.** A level takes the properties it *names* and leaves
the rest to the level beneath, which is what makes this a cascade rather than a series of replacements: a
`widget:skin{color=…}` on a window whose sheet says `["*"] = {font = body}` recolours it **in `body`**, not
in the client's stock font. Read the result for any one widget with [`widget:style()`](#restyle-one-widget).

**Layout is the same cascade with a different top.** [`pos`, `size` and `anchor`](geometry.md) resolve
through the very same fold — most-specific tree rule wins, per property — but the level above every rule is
the **verb**, [`w:position(x, y)`](../native.md), not `widget:skin{…}`. So `w:position(nil)` removes one
level and
lands on the rule beneath, and dropping the sheet removes the last one and lands on what the user had.

Two addons styling the same surface is shared client state, resolved the same way as
[`widget:replace`](../replace.md): each surface holds a **stack of rules tagged with their owning addon,
and the last applied wins**. Disabling that addon pulls its entries and the surface falls back to the next
owner beneath, or to stock when there is none. Deterministic, and reversible per owner.

## Where the skinning system ends

The sheet is finished, and this is its edge. Everything below is a **decision**, not a gap waiting for a
patch.

**What one table reaches.** *Which* — any render site the client draws text or chrome at (the
[site keys](keys.md#site-keys)), any widget a [selector](../selectors.md) names, and any single widget you
point at with `widget:skin{…}`. *What* — the text (`font`, `color`), the surfaces that paint (`bg`,
`border`), the room around content (`pad`), and where a widget is and how big (`pos`, `size`, `anchor`).
*How* — plain data, resolved [per property](#the-cascade), applied live, owned by your addon and reversible
to the pixel; and since it is plain data, a whole look can be a **file** rather than code.

**What it does not reach, and why each one is a different chapter:**

- **The inside of a client window.** A rule places a *widget*; it does not re-flow what a window puts within
  itself — rows, columns, tabs, the order of a list. Those places are computed by that window's own code
  when it is built, and nothing re-runs that construction, which is the same fact `pad` and a border's
  insets meet on a [panel](chrome.md#panels). Rearranging a window's insides is
  [**replacing**](../replace.md) it, not styling it.
- **State-dependent looks.** There is no hover, pressed, focused or disabled selector. A rule matches what a
  widget *is* — its role, class, caption, resource — not what it is momentarily doing, and per-state styling
  would need the client to publish those states at every site. Your own widgets can of course draw
  themselves differently in `onDraw`.
- **Relationships between widgets in the grammar.** No descendant selectors, no `window > button`, no
  pseudo-classes, no specificity arithmetic beyond [the four parts](keys.md#tree-keys). What a rule does
  reach without saying so is the whole **subtree** of the widget it matches — the one containment
  relationship the sheet has, and it comes from the draw pass rather than from the grammar.
- **Motion.** A rule is a state, not a transition: nothing tweens, eases or animates, and installing a sheet
  moves things in one frame. Animation is a per-frame job, and the reason this system costs nothing per
  frame is that it does not have one.
- **A configuration UI.** No drag-to-arrange editor, no docking, no profile manager. The engine ships the
  mechanism — a layout is data, `widget:position()` reads it back and [`hafen.store`](../../store.md)
  persists
  tables — and an addon ships the experience, as `theme` does.
- **The 3D world.** The sheet is the UI. Terrain, objects, animations and their materials are game
  resources; what an addon adds there is [`hafen.render`](../../render/README.md) and
  [`hafen.ghost`](../../ghost.md), not a rule.
- **Text the client baked at class-load**, and `$col[…]` markup inside a string — both
  [in the key table](keys.md#what-each-key-accepts), and both structural rather than missing.

## See also

- [keys](keys.md) — which surfaces a key reaches, and what each honours
- [surfaces](surfaces.md) — what each client surface is, and how it behaves under a rule
- [`hafen.font`](../../font.md) — the handles a `font` property takes
- [selectors](../selectors.md) — the grammar every key is written in
- [`hafen.asset`](../../asset.md) — the fonts and images a rule points at
