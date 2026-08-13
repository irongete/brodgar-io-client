# hafen.ui: which surfaces a rule reaches

A [sheet](README.md) key is [a selector](../selectors.md), and it resolves one of two ways. A **site key**
names a place the client *draws* and is resolved there. Any other valid selector is a **tree key** and says
*which widgets* to style. This page says which is which, how they compete, and what each one does with each
property.

**Which kind a key is, in one line:** a **bare role** is a site key; a **role with a refiner** — or a role
with no site behind it (`window`, `inventory`) — is a tree key. Nothing is ambiguous and nothing has to be
declared: the key's own shape decides. A key that is not valid *grammar* is an error, and exactly the error
[`hafen.ui():find(selector)`](../selectors.md) gives.

## Site keys

These are the sites the client draws at:

| Key | What it styles |
|---|---|
| `*` | the global fallback — most UI text, and the cascade for every rule you do not write |
| `window.title` | window captions, and the [**plate**](chrome.md#ornaments) they sit on |
| `window.frame` | the window **chrome**: the frame drawn around a window, the surface it sits on, and where its [ornaments](chrome.md#ornaments) go. Draws no text, so it takes `bg`, `border`, `padding` and the ornament properties, not `font` or `color` |
| `panel` | the window-**less** framed surfaces — the boxes around lists and info panes, the HUD portrait, party avatars, flower-menu petals, dropdown menus. Draws no text either; see [what a panel does with a rule](chrome.md#panels) |
| `heading` | in-window section headings, the embossed fraktur ones |
| `button` | button captions |
| `label` | body text — attribute rows, list items, explicit-foundry labels |
| `textentry` | text-entry fields **and** the console command line |
| `tooltip` | every tooltip — items, buffs, meters, craft, minimap, the action menu — and the **box** the client pops one up in |
| `menu` | flower-menu petals and the action-menu keybind letters |
| `chat` | the chat window — messages, channel tabs, the typed line |
| `world.nick` | floating kin names over characters |
| `world.speech` | speech bubbles |

Each surface keeps **its own stock size and colour** unless your rule overrides them. One key can front two
sites with different stocks — `textentry` covers the serif fields *and* the mono command line — and both
stay native under one rule. [surfaces](surfaces.md) describes each one and its geometry caveats.

## Tree keys

Any other valid selector — `@Class`, `window[title=…]`, `[text=…]`, `[res=…]`, a chain of steps, or a role
that classifies a *widget* rather than a site (`window`, `inventory`) — is a **tree key**. Every tree rule
that matches a widget is folded into one style, and
[`widget:style()`](README.md#restyle-one-widget) reads the result back, `nil` when nothing names it.

```lua
hafen.ui():sheet():rule("window[title=Cupboard]"):color(200, 180, 140):sheet():install()
hafen.ui():find("window[title=Cupboard]"):style()     --> { color = {r=200, g=180, b=140, a=255} }
hafen.ui():inventory():style()                   --> nil
```

**A tree rule covers the widget it names *and everything drawn inside it*.** The client draws parents
before children, so the rule is in force for the whole subtree — a window's caption, its labels, its button
captions, its rows, and any widget created inside it *later*. That is the same mechanism
[`widget:rule()`](README.md#restyle-one-widget) uses, so a tree rule reaches every surface a site key does,
including text drawn by the game's own resource code.

> **No role names a window's frame, but a rule that names the window still dresses it.** The chrome — the
> border and the title bar's background — is drawn by a **child** of the window rather than by the window
> itself, and no role classifies that child, so `window.frame` names the **site** instead. What a
> `["window…"]` tree rule reaches is both halves: its text, because the caption and everything else is
> drawn inside the window's subtree, **and** its chrome, because a window's decoration asks *the window*
> what style it resolved. That is how you theme one window rather than all of them.

Three rules decide what one widget resolves to:

- **The more specific rule wins, per property.** Specificity is the [selector](../selectors.md)'s parts
  added up — role 1, `@Class` 2, `[title=]`/`[text=]` 4, `[res=]` 8 — and a chain adds up every step, so
  `window[title=Cupboard]` outranks `window` on the window it names while `window` still answers everywhere
  else. It is folded property by property: a
  specific rule that sets only `color` does not take the `font` a broader one set. Equal specificity goes to
  the rule applied last, addons included.
- **A chain reaches everything *inside* that window.** `window[title=Cupboard] *` matches every widget below
  a window captioned `Cupboard` — the same
  [descendant combinator](../selectors.md#the-grammar) every selector uses —
  while `window[title=Cupboard]` matches only the window itself. **A chain follows a rename**: when a
  window's caption changes, everything below it is resolved again, so a chain starts styling a window that
  has just been given the caption it names, and stops styling one that no longer carries it.
- **A site key is not a widget's style.** `*` and the other site keys resolve where they *draw*, so
  `widget:style()` never reports one: a window contains buttons, labels and chat, each drawn at its own
  site, and answering with one of them would be a guess.

**Where both reach the same text, the tree rule wins, property by property.** A tree rule is nearer the
draw than a site rule, so inside the widgets it covers it takes precedence — but only for the properties it
actually names, and the site rule still fills the rest. So the pair below paints that one window's text in
`body`, in tan, and leaves every other window in `body` in its own colour:

```lua
local s = hafen.ui():sheet()
s:rule("*"):font(body)                                       -- what KIND of surface (site key)
s:rule("window[title=Cupboard]"):color(200, 180, 140)        -- WHICH widgets       (tree key)
s:install()
```

## What each key accepts

Every *drawing* property is accepted on **every** key — a sheet never errors because a surface cannot use
one — and the exception is the pair that lays widgets out, which only a tree key may carry. What differs is
what the surface *does* with a property. First the two that write text:

| Key | `font` | `color` | Worth knowing |
|---|---|---|---|
| `*` | yes | yes | cascades to every key you do not write, including the colour |
| `window.title` | yes | **inert** | an *embossed* surface: a texture is tiled through the glyph mask, so there is nothing left to tint |
| `heading` | yes | **inert** | embossed the same way. Two stock sizes ride this key and a size-less rule keeps each |
| `button` | yes | **partly** | the ordinary caption is embossed, so inert; a `wrapped` multi-line caption, or one the client sets *with* a colour, follows the rule. Stock is bold serif 12, so a serif 12 rule installs correctly and looks like nothing happened |
| `label` | yes | yes | a larger `size=` clips: row heights were measured at construction |
| `textentry` | yes | yes | a larger `size=` clips: a field's height comes from its background texture, not the font |
| `tooltip` | yes | yes | `$col[…]` rows keep their own colour; `size=` is safe, since a tip sizes its box around its text |
| `menu` | yes | yes | `size=` is safe — a petal re-sizes around its own centre |
| `chat` | yes | yes | colour is how you tell area from party from private: one rule paints them alike |
| `world.nick` | yes | yes | a `color` rule flattens the kin-**group** colours; a font-only rule leaves them |
| `world.speech` | yes | yes | `size=` is safe — the bubble measures its frame around the text every frame |
| any tree key | yes | **per surface** | [resolved per widget](#tree-keys) and drawn over that widget's whole subtree. It reaches the same surfaces as the rows above and carries their caveats unchanged: a rule on a window covers the window's own caption, where `font` works and `color` is inert. `:style()` reports the colour a rule set even where the surface then throws it away |
| `widget:rule()` | yes | **per surface** | the same, one widget at a time and named by hand rather than matched. Being the top of the cascade changes *who wins*, never *what a surface can do* |

And the three that draw the chrome. The surfaces that wear them are the window decoration, the caption
plate inside it, the window-less panels, and the box a tooltip is popped up in:

| Key | `bg` | `border` | `padding` | Worth knowing |
|---|---|---|---|---|
| `window.frame` | yes | yes | yes | every window whose chrome is the client's own stock decoration. The only surface where `padding` and a border's insets actually **move** anything, because a window re-lays itself out |
| `window.title` | yes | yes | **inert** | the two paint the caption **plate**, at the box the client sizes around the caption — [the ornaments](chrome.md#ornaments). The caption's own place is `window.frame`'s `caption`, so `padding` has nothing to move here |
| `panel`, on a **boxed** panel | **inert** | yes | **inert** | the list and info boxes, the HUD portrait, party avatars, the map's view and marker list. A border drawn *around* content that is not the panel's, so a fill would bury it — [why](chrome.md#panels) |
| `panel`, on a **self-painting** panel | yes | yes | **inert** | flower-menu petals, dropdown menus, an item-stock box: each paints its own surface before its contents, so a `bg` lands on it |
| `tooltip` | yes | yes | yes | the box a tip is popped up in. The client sizes it around the tip's own text, so `padding` is the room between that text and the edge — and the box grows outward, leaving the text where it was. With neither `bg` nor `border` the client's own dark fill and yellow outline stay |
| `*` | **cascades** | **cascades** | **cascades** | `*` is the fallback for a key you did not write, so a chrome property on it reaches `window.frame`, `window.title`, `panel` **and** `tooltip`, each subject to its own row here. Text surfaces ignore it entirely and stay stock |
| every other site key | **inert** | **inert** | **inert** | a text site has no surface of its own to paint and no layout of its own to move. Nothing is refused and nothing warns |
| a tree key **matching a window** | yes | yes | yes | it reaches *that* window's chrome, because a window's decoration resolves through the window it belongs to |
| a tree key **matching a panel** | per panel | yes | **inert** | likewise: a panel asks *itself* what style it resolves, so `["@Frame"]` or `["window[title=…] @Frame"]` themes those panels alone. `bg` follows the same two panel rows above |
| a tree key matching anything else | **inert** | **inert** | **inert** | readable back through `widget:style()`, but nothing else in the client wears chrome |
| `widget:rule()` | per surface | per surface | per surface | exactly as the rows above, one widget at a time: on a window it dresses that window's frame, on a panel that panel's box, anywhere else it is inert |

And the ones that dress a window's [ornaments](chrome.md#ornaments).
One surface draws them, so this table is one row.

| Key | `caption` | `sizer` | `close` | Worth knowing |
|---|---|---|---|---|
| `window.frame` | yes | yes | yes | where the decoration puts the caption, and the art and corner of the two ornaments that have one. A window that draws no sizer — which is nearly all of them — ignores that one |
| every other key, `*` included | **inert** | **inert** | **inert** | nothing else in the client draws a window's ornaments. Readable back through `:style()`, and inert everywhere it lands |

> **A site key does not compose with `*` per property.** Within the site half of the cascade a key either
> has a rule of its own or falls back to `*`; it does not take half of each. So `["*"] = {bg = …}` beside
> `["window.frame"] = {border = …}` gives you the border **alone** — write both properties in the rule that
> names the surface. Levels *above* the site half, a tree rule or a `widget:rule()`, do
> [compose per property](README.md#the-cascade).

And the three that lay widgets out. This table is short because the answer is: a widget, or an error.

| Key | `position` / `anchor` | `size` | Worth knowing |
|---|---|---|---|
| any tree key | yes | **unless the widget owns its size** | the widget it matches is moved for real — the field a drag writes — so what you place is what you click. A window that packs around its contents re-packs itself: inert, never an error |
| any site key, `*` included | **error** | **error** | a site is where the client draws text, and text has no position. The error names the fix: select the widget |
| `widget:rule()` | **error** | **error** | the hand-named level is the verb, [`w:position(x, y)`](../native.md) — the error says so |

**Where `color` is inert, it is the same reason every time**: the surface is *embossed*. The client renders
the text as a mask, tiles a texture through it and blurs a shadow behind, so the glyph colour is discarded
before anything reaches the screen and there is nothing for a rule to override. Those surfaces still follow
a `font` rule perfectly. Nothing is refused and nothing warns.

Three more limits are structural rather than per-key, and none of them is a bug to report:

- **Text the client rasterised into a static field at class-load** can never follow a rule, because the JVM
  does not re-run a static initialiser. The one such row the client still ships is reached by carrying a
  local copy of that resource's code; see [surfaces](surfaces.md#tooltip).
- **`$col[…]` markup wins over a `color` rule**, everywhere. It is part of the *string*, not the site's
  choice of colour.
- **A rule flattens colour that carried meaning.** While `["*"] = {color = …}` is on, a red warning is the
  same colour as everything else. Style one key rather than `*` when that matters.

The only *drawing* property that changes geometry is [`padding`](chrome.md#padding), and only where a surface owns
its own layout. A `font` rule never moves anything, but a larger `size=` can still *clip* where a surface's
box was measured from the stock font; [surfaces](surfaces.md) says which ones, and why.

## See also

- [style](README.md) — the sheet, the cascade and `widget:rule()`
- [surfaces](surfaces.md) — what each of these keys actually is on screen
- [text](text.md) · [chrome](chrome.md) · [geometry](geometry.md) — the properties themselves
- [selectors](../selectors.md) — the grammar, and the roles this vocabulary shares
