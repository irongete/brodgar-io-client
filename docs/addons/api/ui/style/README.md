# hafen.ui: The Stylesheet

One sheet per addon says what the client looks like: a [selector](../selectors.md) names a rule, the rule's properties are setters, and installing the sheet applies them live. To draw your own pixels, use [custom](../custom.md) and [drawing](../drawing.md); nothing here is protected.

```lua
local body = hafen.asset():get("fonts/Inter.ttf"):derive():size(12)
local sheet = hafen.ui():sheet()
sheet:rule("*"):font(body)                                        -- the global fallback
sheet:rule("window.title"):font(body:derive():size(14):bold(true))
sheet:rule("chat"):font(hafen.font():get("mono"):derive():size(13)):color{200, 210, 200}
sheet:rule("tooltip"):color{255, 150, 90}                         -- colour alone: the font stays stock
sheet:install()
```

[Keys](keys.md) says which surfaces a rule reaches and what each honours; [surfaces](surfaces.md), [chat](chat.md) and [hud](hud.md) describe the client's surfaces; [text](text.md), [chrome](chrome.md) and [geometry](geometry.md) are the property pages.

---

## The sheet (unprotected)

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():sheet()` | `Sheet` | Unprotected | Your addon's one sheet, the same object every call. An argument raises. |
| `sheet:rule(selector)` | `Rule` | Unprotected | The rule for that key, minted on first use and the same object after. A malformed selector raises as `session:ui():match` does. |
| `sheet:load(rules)` | `self` | Unprotected | A whole sheet from data, replacing what the sheet said — [below](#a-sheet-from-data). |
| `sheet:install()` | `self` | Unprotected | Applies what the sheet says, replacing whatever this addon had installed, whole: a surface the sheet no longer names falls back at once. |
| `sheet:installed()` | `boolean` | Unprotected | Whether it is applied. |
| `sheet:release()` | `self` | Unprotected | Stops applying it; every surface it styled falls back to another addon's sheet, else to stock. Inert when nothing was installed. |
| `sheet:rules()` | `Rule[]` | Unprotected | The rules it names, in the order it named them. |
| `sheet:stock()` | `table` | Unprotected | The client's own look as data — [below](#the-clients-own-look). |
| `sheet:stock(key)` | `table \| nil` | Unprotected | One site's own look; `nil` when this client has offered none. |
| `sheet:info()` | `table` | Unprotected | `{installed = …, rules = {selector, …}}`, in the order the tie-break uses. |

| Rule | Detail |
|---|---|
| An edit to an installed sheet applies at once | There is no re-apply verb: a sheet is in force or it is not. `sheet:rule("chat"):release()` gives one rule back on the spot. |
| Live and owned | Existing text re-renders on the spot; the sheet is dropped on `:reload` and disable, so the stock client is always restorable. |

```lua
sheet:rule("chat"):color{200, 210, 200}
sheet:install()                                -- from here the sheet is the client's look
sheet:rule("tooltip"):color{255, 150, 90}      -- lands on the spot
sheet:rule("chat"):release()                   -- so does giving one rule back
sheet:release()                                -- everything it styled falls back
```

---

## A sheet from data

`sheet:load(rules)` takes a table of `["selector"] = {property = value}`, the door a look that lives in a file comes through.

```lua
local theme = hafen.json():parse(hafen.asset():get("theme.json"):text())
hafen.ui():sheet():load(theme.rules):install()
```

| Rule | Detail |
|---|---|
| Every value has a file spelling | A colour is a table of numbers; a border a slice of four numbers or a colour and a width; a `padding` one or four numbers; an [anchor](geometry.md#anchor) a corner and an offset; a picture a [path or a resource](chrome.md#naming-a-picture); a face `{builtin = "mono"}` or `{asset = "fonts/Inter.ttf"}` — [text](text.md#font). No handle anywhere. |
| Rules are in selector order | A keyed Lua table has no order, so a loaded sheet's rules are sorted by selector: the order `sheet:info()` reports and the [equal-specificity tie-break](keys.md) uses, the same on every run. Rules that must be ordered are written with `sheet:rule(sel)`, applied in call order. |
| Property names | The setter names: `font`, `color`, `emboss`, `glow`, `bg`, `border`, `padding`, `margin`, `picture`, `caption`, `sizer`, `closeButton`, `position`, `anchor`, `size`. An unknown one raises naming those that exist; so does a rule saying both `position` and `anchor`. The table is parsed whole before anything is committed, so a malformed rule leaves the sheet as it was. |
| Saving a layout | Your addon's: [`widget:position()`](../widget.md#read-methods) reads a table of numbers and [`hafen.store`](../../store/README.md) persists tables. |

---

## The client's own look

`sheet:stock()` is what this client draws with when no rule says anything, keyed by [site](keys.md#site-keys), in the shape `sheet:load(rules)` takes: art named by resource, faces by built-in, colours as the [keyed table](../../shapes.md#colours) every reader hands back.

```lua
local look = hafen.ui():sheet():stock()
hafen.log():write(hafen.json():encode(look))   -- the whole catalogue, ready for a theme file
hafen.ui():sheet():load(look):install()        -- and the client looks exactly as it did
```

| Rule | Detail |
|---|---|
| `sheet:stock(key)` | One site; `nil` when the client offered none. A key naming no site raises, and so does a [tree key](keys.md#tree-keys): a widget has no look of its own to read back. |
| A site that has not drawn declares nothing | A surface in a window nobody opened is absent until it is opened. |
| Only what it can say whole | A surface made of things the vocabulary has no word for — a fill inset inside its end caps, a chain of links spread down a bar, a corner following its caption — is left out, so what you load back paints the client you saw. |
| Art with no name | A picture composed in code is left out; where such a surface is also a flat colour and an outline (the inventory square, a tooltip's box) it comes back as that. |
| One key, several builds | A checkbox is built large or small with different art; the catalogue carries whichever drew last. |
| Walked colours | `chat.speaker` and `chat.urgent` come back as the sequences they are — [the chat](chat.md#the-two-colours-the-client-walks). |

The table holds strings, numbers, booleans and tables only, so [`hafen.json`](../../json.md) encodes it and a [var](../../store/vars.md) keeps it.

---

## Properties

Each setter returns the rule; each reads back with no argument.

| Method | Value | Description |
|---|---|---|
| `rule:font(face)` | A [font handle](../../font.md), or the face named: `{builtin = …}` / `{asset = …}` with `size`, `bold`, `italic`, `aa` | [text](text.md#font). |
| `rule:color(c)` | A [colour](../../shapes.md#colours), `{200, 210, 220}` or `{r=, g=, b=[, a=]}`; on `chat.speaker` and `chat.urgent` the sequence instead | [text](text.md#color). |
| `rule:emboss(v)` | `false`, or `{texture = <art>}` | Whether the client's relief is cut through a surface's letters, and with what; `false` lets `color` reach a caption — [text](text.md#emboss). |
| `rule:glow(t)` | `{color = {r,g,b[,a]}, radius = n}` | The halo behind a carved surface's letters; radius `0` is none — [text](text.md#glow). |
| `rule:bg(t)` | One [surface](chrome.md#naming-a-picture), or an array | What something is painted on — [chrome](chrome.md). |
| `rule:border(t)` | `{<art>, slice = {l, t, r, b}}`, `{box = "gfx/hud/wnd"}`, or `{color = …, width = n}` | A 9-slice frame, one of the client's, or a line — [chrome](chrome.md#border). |
| `rule:padding(n)`, `rule:padding(l, t, r, b)` | Design pixels, `>= 0` | The room between a frame and its content — [chrome](chrome.md#padding). |
| `rule:margin(n)`, `rule:margin(l, t, r, b)` | Design pixels, `>= 0` | The room a [column](../column.md) keeps around the matched widget; tree keys and `widget:rule()` — [geometry](geometry.md#margin). |
| `rule:picture(t)` | One surface, a face per state | The whole plate a surface is — [chrome](chrome.md#picture). |
| `rule:caption(t)` | `{at =, offset =}` | Where a window's title is drawn — [ornaments](chrome.md#ornaments). |
| `rule:sizer(t)` | A surface with `at` | The corner grip a resizable window draws — [ornaments](chrome.md#ornaments). |
| `rule:closeButton(t)` | A surface with `hover`, `pressed`, `at`, `offset` | The button that closes a window — [ornaments](chrome.md#ornaments). |
| `rule:position(x, y)` | Design pixels | Where the widget sits in its parent; tree keys only — [geometry](geometry.md). |
| `rule:anchor(t)` | `{to =, at =, offset =}` | The same place as a relationship — [geometry](geometry.md#anchor). |
| `rule:size(w, h)` | Design pixels | How big; a window's content box; tree keys only — [geometry](geometry.md). |
| `rule:selector()` | `string \| nil` | The key it was named by; `nil` on a widget's own level. |
| `rule:sheet()` | `Sheet \| nil` | The sheet it belongs to; `nil` on a widget's own level. |
| `rule:release()` | `self` | The level stops saying anything; the handle stays usable, and a setter says the level again. |
| `rule:info()` | `table \| nil` | Every property it sets plus its `selector`, or `nil` when it says nothing. The `selector` is not itself a load value: take the key out before `sheet:load`. |

| Rule | Detail |
|---|---|
| Independent | A rule may carry any one property alone; a colour-only rule keeps the surface's font. A rule carrying none styles nothing. |
| Refusals | An unknown property raises naming those that exist. `position`, `anchor`, `size` and `margin` raise on a key that names a render site: they are about a widget. |

---

## Restyle one widget

| Method | Returns | Permission | Description |
|---|---|---|---|
| `widget:rule()` | `Rule` | Unprotected | Your level on that widget and everything drawn inside it, the same Rule object a sheet's selectors hand back. |
| `widget:rule():info()` | `table \| nil` | Unprotected | Your own level, as written; `nil` if you have none. |
| `widget:rule():release()` | `Rule` | Unprotected | Gives your level back; another addon's stays. |
| `widget:style()` | `table \| nil` | Unprotected | What the widget resolves to — `{ font = <handle>, color = {r=, g=, b=, a=} }`, each field present where a level set it — folding your level over every tree rule matching it; `nil` when nothing names it. |

```lua
local hovered = hafen.ui():mouse():over()
hovered:rule():font(body):color{200, 180, 140}   -- this widget and all inside it; siblings untouched
hovered:rule():info()                            -- { font = body, color = {r=200, g=180, b=140, a=255} }
hovered:rule():release()
```

| Rule | Detail |
|---|---|
| The whole subtree | Parents draw before children, so a style on a window reaches its caption, labels, button captions, list rows and widgets created inside it later. A child with a style of its own wins inside itself. |
| The top of the cascade | It wins the properties it names, text drawn by the game's own resource code included. |
| `:style()` is the result | A style inherited from an enclosing widget is applied at the draw, not resolved onto the child, so a child inside a styled window reads `nil`. Site keys are not folded in either. |
| Layout is the verb | `widget:rule():position(…)`, `:size(…)` and `:anchor(…)` raise, naming [`widget:position(x, y)`](../native.md); `:margin(…)` is legal, no verb spelling it. |
| Chrome | On a window it dresses that window's chrome; one level down, a [panel's](surfaces.md#panels) box, a [button's](surfaces.md#button) face or a [field's](surfaces.md#textentry). Inert on a widget wearing none, still readable through `:style()`. |
| Owned and weak | Tagged with your addon, reverted on `:reload` and disable, held weakly against the widget: it goes when the window closes, and a stashed rule reads `nil` and writes as a no-op. |
| Scope | `session:ui():root():rule()` covers one character's tree; a sheet's `["*"]` covers the client in every session. An Inventory window has only its caption as text. |

---

## The cascade

Resolution is most-specific first: `widget:rule()` → the matching [tree rule](keys.md#tree-keys) → the matching [site rule](keys.md#site-keys) → the `*` rule → [the widget's own stock](../custom.md#naming-and-dressing-your-own-surfaces) → the client's stock.

| Rule | Detail |
|---|---|
| Per property | A level takes the properties it names and leaves the rest to the level beneath: `widget:rule():color(…)` on a window whose sheet says `rule("*"):font(body)` recolours it in `body`. |
| The bottom two levels | The client's surfaces have a stock look a rule beats; a widget an addon built may declare one the same way, which is why a default look is a [stock](../custom.md#naming-and-dressing-your-own-surfaces) and not a rule of the addon's own. |
| Layout | `position`, `size` and `anchor` resolve through the same fold, with the verb [`widget:position(x, y)`](../native.md) as the level above every rule: `:position(nil)` lands on the rule beneath, dropping the sheet lands on what the user had. |
| Two addons | Each surface holds a stack of rules tagged by owner; the last applied wins; disabling an addon pulls its entries and the surface falls back to the next owner, else stock. |

---

## Where the skinning system ends

The sheet reaches any render site ([site keys](keys.md#site-keys)), any widget a [selector](../selectors.md) names, and any single widget through `widget:rule()`; it says the text, the surfaces that paint, the room inside a frame and around a row, the plate a surface is, a window's ornaments and where a widget is and how big — per property, live, owned, reversible, loadable from a file. What it does not reach:

| Not a rule | Why, and what is |
|---|---|
| The inside of a client window | Rows, columns, tabs and list order are computed by the window's own code when built; nothing re-runs that. Rearranging a window's insides is [replacing](../replace.md) it. |
| A state in the selector | No hover, pressed, focused or disabled key: a rule matches what a widget is. A state rides inside the value — a [`bg`](chrome.md#a-face-per-state) or [`picture`](chrome.md#picture) names a face per state. Your own widgets draw states in `Draw`. |
| Relationships beyond containment | A key may be a [chain](keys.md#tree-keys) (the space, descendant); there is no `>`, no pseudo-class, no sibling combinator, no comma list. A rule reaches the matched widget's whole subtree through the draw pass. |
| Motion | A rule is a state, not a transition; installing a sheet moves things in one frame. |
| A decoration over a widget | A badge, a count, a bar is [`widget:overlay()`](../overlay.md#over-one-widget): a callback, keyed and clipped, where a rule is a value. |
| A configuration UI | The engine ships the mechanism (layout is data, `widget:position()` reads it, [`hafen.store`](../../store/README.md) keeps it); an addon ships the editor. |
| The 3D world | [`hafen.virtual`](../../virtual/README.md). |
| Text baked at class-load, and `$col[…]` markup inside a string | Structural — [the key table](keys.md#what-each-key-accepts). |

---

## See Also

- [Keys](keys.md) — which surfaces a key reaches, and what each honours.
- [Surfaces](surfaces.md), [Chat](chat.md), [HUD](hud.md) — the client's surfaces under a rule.
- [`hafen.font`](../../font.md) — the handles a `font` property takes.
- [Selectors](../selectors.md) — the grammar every key is written in.
- [`hafen.asset`](../../asset/README.md) — the fonts and images a rule points at.
