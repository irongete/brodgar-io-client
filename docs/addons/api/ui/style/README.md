# hafen.ui: the stylesheet

One **sheet** says what the client looks like: a [selector](../selectors.md) names a **rule**, the rule's
properties are setters, and installing the sheet applies the lot — live, and owned by the addon that
installed it. Reach for it to restyle the client's own surfaces — text, chrome, or where its windows sit.
To draw your *own* pixels, use [custom](../custom.md) and [drawing](../drawing.md) instead; nothing here is
protected.

```lua
local body = hafen.asset():get("fonts/Inter.ttf"):derive():size(12)
local s = hafen.ui():sheet()
s:rule("*"):font(body)                                       -- the global fallback
s:rule("window.title"):font(body:derive():size(14):bold(true))
s:rule("chat"):font(hafen.font():get("mono"):derive():size(13)):color{200, 210, 200}
s:rule("tooltip"):color{255, 150, 90}                        -- colour alone: the font stays stock
s:install()
```

## Reading order

[keys](keys.md) says **which** surfaces a rule reaches and which properties each one honours.
[surfaces](surfaces.md) says what each of the client's own surfaces *is*, and how it behaves when a rule
lands on it, with [the chat](chat.md) and [the HUD's plates](hud.md) on pages of their own. Then the
property pages:
[text](text.md) for what the letters look like, [chrome](chrome.md) for
everything that paints, [geometry](geometry.md) for `position`, `size` and `anchor`. This page holds the sheet
itself, the per-widget level, the cascade they all resolve through, and the edge of the system.

## The sheet (unprotected)

| Call | Returns | Description |
|---|---|---|
| `hafen.ui():sheet()` | Sheet | your addon's one sheet, the same object every time |
| `sheet:rule(selector)` | Rule | the rule for that key, minted on first use and the same object after |
| `sheet:load(rules)` | self | a whole sheet from **data** — see [below](#a-sheet-from-data) |
| `sheet:install()` | self | apply what the sheet says, **replacing** whatever this addon had installed |
| `sheet:release()` | self | give it back; every surface it styled falls back |
| `sheet:stock()` | table | the **client's own** look as data — see [below](#the-clients-own-look) |
| `sheet:stock(key)` | table \| nil | one site's own look; `nil` when this client has offered none |
| `sheet:info()` | table | `{installed = …, rules = {selector, …}}` |

**An addon owns exactly one sheet**, so the document is the thing you keep and `:install()` is the moment it
becomes what the client looks like. Installing again replaces the applied sheet *whole*, not rule by rule: a
surface the sheet no longer names falls back on the spot.

**An edit to an installed sheet applies at once.** There is no re-apply verb, because there is nothing to
re-apply: a sheet is either in force, in which case what it says is what you see, or it is not.

```lua
local s = hafen.ui():sheet()
s:rule("chat"):color{200, 210, 200}
s:install()                        -- from here the sheet IS the client's look
s:rule("tooltip"):color{255, 150, 90}   -- ...so this lands on the spot
s:rule("chat"):release()                -- ...and so does giving one rule back
s:release()                             -- everything it styled falls back
```

The change is **live** — existing text re-renders on the spot — and the sheet is **owned**: it is dropped
automatically on your addon's `:reload` or disable, so the stock client is always restorable.

## A sheet from data

`sheet:load(rules)` takes a **whole sheet at once**, as a table of `["selector"] = {property = value}`. It is
the door a look that lives in a *file* comes through, and it replaces whatever the sheet said:

```lua
local doc = hafen.json():parse(hafen.asset():get("theme.json"):text())
hafen.ui():sheet():load(doc.rules):install()
```

That is a look whose Lua never names a surface, a font, a size, a colour or a pixel. **Every value a rule
takes has a spelling a file can carry**: a colour is a table of numbers, a border is a slice of four numbers
or a colour and a width, a `padding` is one or four, an [anchor](geometry.md#anchor) is a corner and an
offset — as is the place a window's [caption](chrome.md#ornaments) is drawn at — a picture is named by its
[path or its resource](chrome.md#naming-a-picture), and a face is named the
[same two ways](text.md#font) — `{builtin = "mono"}` or `{asset = "fonts/Inter.ttf"}`. Nothing in the
document is a handle, so a whole client look, windows and typography included, is a file and one command.

Inside a loaded table the properties are the setter names: `font`, `color`, `emboss`, `glow`, `bg`,
`border`, `padding`, `picture`, `caption`, `sizer`, `close`, `position`, `anchor`, `size`. An unknown one is an **error**
naming the ones that exist, and so is a rule
that says both `position` and `anchor` — two spellings of [one property](geometry.md#anchor), and in a table
there is no *later* to pick the winner.

**Saving a layout is your addon's business, not the engine's**, and it is small: a layout you can read back
with [`widget:position()`](../widget.md#read) is a table of numbers, and [`hafen.store`](../../store.md)
already
persists tables. One command reads where your windows currently are and keeps them account-wide, another
re-applies them over the file's own placement, a third drops them. There is no profile system here because
a sheet is data and an addon already has a store.

## The client's own look

`sheet:stock()` hands back **what this client draws with when no rule says anything**, keyed by
[site](keys.md#site-keys), in the very shape `sheet:load(rules)` takes. Art comes back named by its
resource, faces by their built-in, colours as the [keyed table](../../shapes.md#colours) every reader in the
API hands back — so the answer is a document, and the shortest way to start a theme is to write it to a file
and edit it:

```lua
local look = hafen.ui():sheet():stock()
hafen.log():write(hafen.json():encode(look))   -- the whole catalogue, ready to paste into a theme file
hafen.ui():sheet():load(look):install()        -- ...and the client looks exactly as it did
```

`sheet:stock(key)` is one site out of it, `nil` when this client has offered none. A key that names no site
raises, and so does a [tree key](keys.md#tree-keys): a widget has no look of its own to read back, only the
sites inside it.

- **A site that has not drawn declares nothing.** The catalogue is what the client has *offered*, so a
  surface belonging to a window nobody has opened is absent until it is opened. Open what you mean to read.
- **A key answers only what it can say whole.** Some of this client's surfaces are made of things this
  vocabulary has no word for — a fill inset a fixed margin inside its own end caps, a chain of links spread
  evenly down a bar, a corner whose width follows the caption inside it. Those properties are left out
  rather than approximated, so what you load back paints the client you were looking at. What is missing is
  therefore worth reading as a limit of the grammar, not as a gap in the answer.
- **Art with no name is left out too.** A picture the client composed in code rather than decoding from a
  resource has nothing to name it by, and the catalogue never invents one. Where such a surface is *also* a
  flat colour and an outline — the inventory square, a tooltip's box — it comes back as exactly that.
- **A surface built more than one way reports the last one drawn.** A checkbox is built large or small and
  the two wear different art; the same key covers both, so the catalogue carries whichever was on screen
  most recently.
- **A colour the client hands out one at a time comes back as the sequence it is**, never flattened —
  `chat.speaker` as the walk it does, `chat.urgent` as the colours it cycles. See
  [the chat](chat.md#the-two-colours-the-client-walks).

The table holds nothing but strings, numbers, booleans and tables — no handle anywhere in it — so
[`hafen.json`](../../json.md) encodes it as it stands, and a
[saved variable](../../store.md) keeps it across sessions.

## Properties

Each is a setter that returns the rule, and each reads back with no argument.

| Call | Value | Notes |
|---|---|---|
| `rule:font(face)` | a [font handle](../../font.md), or the same face **named** | `hafen.font():get(name)` / `hafen.asset():get(path)`, optionally through `:derive()`, or `{builtin = …}` / `{asset = …}` with `size`, `bold`, `italic` and `aa` — see [text](text.md#font) |
| `rule:color(c)` | a [colour](../../shapes.md#colours) | `{200, 210, 220}` or the `{r = …, g = …}` table every reader hands back; on the two keys whose colour the client [walks](chat.md#the-two-colours-the-client-walks) it takes the sequence instead — see [text](text.md#color) |
| `rule:emboss(v)` | `false`, or `{texture = <art>}` | whether the client's own relief is cut through a surface's letters, and with what. `false` is what lets `color` reach a caption at all — see [`emboss`](text.md#emboss) |
| `rule:glow(t)` | `{color = {r,g,b[,a]}, radius = n}` | the blurred halo behind a carved surface's letters; a radius of `0` is no halo at all — see [`glow`](text.md#glow) |
| `rule:bg(t)` | one [surface](chrome.md#naming-a-picture), or an array of them | what something is painted on, one layer or several — see [chrome](chrome.md) |
| `rule:border(t)` | `{<art>, slice = {l, t, r, b}}`, `{box = "gfx/hud/wnd"}` or `{color = {r,g,b[,a]}, width = n}` | your own 9-slice frame, one of the client's own, or a plain line — see [chrome](chrome.md#border) |
| `rule:padding(n)` | [design px](../pixels.md), `>= 0` | the room a surface keeps between its frame and its content, one number for all four sides or `(l, t, r, b)` — see [`padding`](chrome.md#padding) |
| `rule:picture(t)` | one [surface](chrome.md#naming-a-picture), with a face per state | the whole plate a surface **is**, where the client blits a picture — see [`picture`](chrome.md#picture) |
| `rule:caption(t)` | `{at =, offset =}` | which corner of a window's frame its title is measured from, and how far — see [ornaments](chrome.md#ornaments) |
| `rule:sizer(t)` | a [surface](chrome.md#naming-a-picture) with an `at` | the corner grip a resizable window draws, and where — see [ornaments](chrome.md#ornaments) |
| `rule:close(t)` | a [surface](chrome.md#naming-a-picture) with `hover`, `pressed`, `at` and `offset` | the button that closes a window, and which corner it sits in — see [ornaments](chrome.md#ornaments) |
| `rule:position(x, y)` | [design px](../pixels.md) | where the widget sits inside its parent — **tree keys only**, see [geometry](geometry.md) |
| `rule:anchor(t)` | `{to =, at =, offset =}` | the same place said as a relationship — see [`anchor`](geometry.md#anchor) |
| `rule:size(w, h)` | [design px](../pixels.md) | how big it is; a window's *content* size — **tree keys only**, see [geometry](geometry.md) |

A rule also carries `rule:selector()` (the key it was named by), `rule:sheet()` (the sheet it belongs to, so
a whole sheet can be one expression), `rule:info()` (everything it says, or `nil` when it says nothing) and
`rule:release()` (it stops saying anything; the handle goes on working, and setting a property says the level
again).

**The properties are independent.** A rule may carry any one alone: a colour-only rule leaves the surface's
own font exactly as it is, a `border`-only rule leaves its background. A rule carrying none styles nothing.

An **unknown property is an error** naming the ones that exist — unlike an unresolved key, a misspelt
property has no later meaning to wait for. So is `:position()`, `:anchor()` or `:size()` on a key that names
a render **site** rather than a widget: those three lay out a *widget*, and a site is where the client draws.

## Restyle one widget

A [site key](keys.md#site-keys) restyles a *family* of surfaces across the whole client; a
[tree key](keys.md#tree-keys) restyles the widgets a selector *matches*. To restyle **one** widget you
already hold, ask it for its own rule — the same Rule object a sheet's selectors hand back:

```lua
local n = hafen.ui():mouse():over()      -- the widget under the cursor
n:rule():font(h):color{200, 180, 140}   -- this widget and all inside it; SIBLINGS untouched
n:rule():info()                         --> { font = h, color = {r=200, g=180, b=140, a=255} }
n:rule():release()                      -- give the level back
```

| Call | Returns | Description |
|---|---|---|
| `widget:rule()` | Rule | **your** level on that widget, carrying the same properties a sheet rule does |
| `widget:rule():info()` | table \| nil | read **your own** level back, exactly as you wrote it; `nil` if you have none |
| `widget:rule():release()` | nothing | give **your** level back; another addon's on the same widget is untouched |
| `widget:style()` | table \| nil | what the widget **resolves to**; `nil` when nothing names it |

- **It covers the whole subtree.** The client draws parents before children, so a style on a window reaches
  its caption, its labels, its button captions, its list rows, and any widget created inside it *later*. A
  child with a style of its own wins inside itself.
- **It is the top of the cascade**, so inside a styled widget it wins whatever surface the text belongs
  to — including text drawn by the game's **own resource code**, the one place a site rule could never
  reach. It wins the properties it names and no others.
- **`:style()` is the read-back for the *result*.** `:rule():info()` hands back what *you* wrote;
  `:style()` hands back your level folded over every tree rule that matches the widget. Both answer for that
  widget alone: a style inherited from an enclosing widget is applied at the *draw*, not resolved onto the
  child, so a child inside a styled window still reads `nil`.
- **`widget:rule():position(…)` is an error**, and so are `:size()` and `:anchor()`: the hand-named level of
  the layout cascade is the **verb**, [`w:position(x, y)`](../native.md). One way per operation.
- **On a window, it dresses that window's chrome**, and one level down, a [panel's](surfaces.md#panels) box,
  a [button's](surfaces.md#button) face or a [field's](surfaces.md#textentry). On anything that wears no
  chrome the three chrome properties are inert, still readable through `:style()`.
- **Owned and short-lived.** The level is tagged with your addon and reverted on `:reload` or disable, and
  it is held **weakly against the widget**: when that window closes it goes with it, and a stashed object
  reads `nil` from every accessor while a write becomes a no-op rather than an error.
- **Some windows have no text to restyle.** An Inventory or Equipment window contains item *icons*; its
  only text is the caption, so a style there shows up on the title bar alone. Pick a text-rich window when
  you want to see the effect.
- `s:ui():root():rule()` works and covers that character's whole tree, but a sheet's `["*"]` rule is what
  covers the client, in every session at once.

## The cascade

Resolution is **most-specific first**: `widget:rule()` → the matching [tree rule](keys.md#tree-keys) → the
matching [site rule](keys.md#site-keys) → the `*` rule → the client's stock. So `["*"]` alone changes
everything, and any other key refines one surface, or one widget, out of that cascade.

**Every level composes per property, never wholesale.** A level takes the properties it *names* and leaves
the rest to the level beneath, which is what makes this a cascade rather than a series of replacements: a
`w:rule():color(…)` on a window whose sheet says `s:rule("*"):font(body)` recolours it **in `body`**, not
in the client's stock font. Read the result for any one widget with [`widget:style()`](#restyle-one-widget).

**Layout is the same cascade with a different top.** [`position`, `size` and `anchor`](geometry.md) resolve
through the very same fold — most-specific tree rule wins, per property — but the level above every rule is
the **verb**, [`w:position(x, y)`](../native.md), not `widget:rule()`. So `w:position(nil)` removes one
level and
lands on the rule beneath, and dropping the sheet removes the last one and lands on what the user had.

Two addons styling the same surface is shared client state, resolved the same way as
[`widget:replace`](../replace.md): each surface holds a **stack of rules tagged with their owning addon,
and the last applied wins**. Disabling that addon pulls its entries and the surface falls back to the next
owner beneath, or to stock when there is none. Deterministic, and reversible per owner.

## Where the skinning system ends

The sheet is finished, and this is its edge. Everything below is a **decision**, not a gap waiting for a
patch.

**What one sheet reaches.** *Which* — any render site the client draws text or chrome at (the
[site keys](keys.md#site-keys)), any widget a [selector](../selectors.md) names, and any single widget you
point at with `widget:rule()`. *What* — the text (`font`, `color`, the `emboss` that decides which of
the two a carved surface listens to, and the `glow` behind it), the surfaces that paint (`bg`,
`border`), the room around content (`padding`), the whole plate a surface is where the client blits one
(`picture`), what a window's decoration draws its ornaments as and where
it puts them (`caption`, `sizer`, `close`), and where a widget is and how big (`position`, `size`, `anchor`). *How* — resolved [per property](#the-cascade), applied live, owned by your addon and reversible
to the pixel; and since a rule is only values, a whole look can [come from a **file**](#a-sheet-from-data)
rather than from code.

**What it does not reach, and why each one is a different chapter:**

- **The inside of a client window.** A rule places a *widget*; it does not re-flow what a window puts within
  itself — rows, columns, tabs, the order of a list. Those places are computed by that window's own code
  when it is built, and nothing re-runs that construction, which is the same fact `padding` and a border's
  insets meet on a [panel](surfaces.md#panels). Rearranging a window's insides is
  [**replacing**](../replace.md) it, not styling it.
- **A state in the *selector*.** There is no hover, pressed, focused or disabled key. A rule matches what a
  widget *is* — its role, class, caption, resource — not what it is momentarily doing, and a per-state
  selector would make every site publish its state to the cascade. A state rides inside the **value**
  instead, where the surface drawing itself already knows which one it is in: a
  [`bg`](chrome.md#a-face-per-state) or a [`picture`](chrome.md#picture) names a face per state, and the
  surfaces that have one wear it. Your
  own widgets draw themselves differently in `onDraw`.
- **Every relationship except containment.** A key may be a [chain](keys.md#tree-keys) — a space is the
  descendant combinator, so `window[title=Cupboard] label` names the labels in one window and nowhere else
  — but there is no `window > button` (direct child), no pseudo-class, no sibling combinator and no
  comma-separated list of keys. What a rule reaches *without* saying so is the whole **subtree** of the
  widget it matches: that one comes from the draw pass rather than from the grammar, which is why it also
  covers a widget built inside that window later.
- **Motion.** A rule is a state, not a transition: nothing tweens, eases or animates, and installing a sheet
  moves things in one frame. Animation is a per-frame job, and the reason this system costs nothing per
  frame is that it does not have one.
- **A configuration UI.** No drag-to-arrange editor, no docking, no profile manager. The engine ships the
  mechanism — a layout is data, `widget:position()` reads it back and [`hafen.store`](../../store.md)
  persists
  tables — and an addon ships the experience.
- **The 3D world.** The sheet is the UI. Terrain, objects, animations and their materials are game
  resources; what an addon adds there is [`hafen.vr`](../../vr/README.md), not a rule.
- **Text the client baked at class-load**, and `$col[…]` markup inside a string — both
  [in the key table](keys.md#what-each-key-accepts), and both structural rather than missing.

## See also

- [keys](keys.md) — which surfaces a key reaches, and what each honours
- [surfaces](surfaces.md) — what each client surface is, and how it behaves under a rule
- [the chat](chat.md) — the chat window, its kinds of line, and the two colours it hands out one at a time
- [the HUD's plates](hud.md) — the five the client blits whole, and the one property that dresses them
- [`hafen.font`](../../font.md) — the handles a `font` property takes
- [selectors](../selectors.md) — the grammar every key is written in
- [`hafen.asset`](../../asset.md) — the fonts and images a rule points at
