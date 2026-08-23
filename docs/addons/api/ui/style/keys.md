# hafen.ui: which surfaces a rule reaches

A [sheet](README.md) key is [a selector](../selectors.md), and it resolves one of two ways. A **site key**
names a place the client *draws* and is resolved there. Any other valid selector is a **tree key** and says
*which widgets* to style. This page says which is which, how they compete, and what each one does with each
property.

**Which kind a key is, in one line:** a **bare role** is a site key; a **role with a refiner** — or a role
with no site behind it (`window`, `inventory`, `item`) — is a tree key. Nothing is ambiguous and nothing has to be
declared: the key's own shape decides. A key that is not valid *grammar* is an error, and exactly the error
[`s:ui():match(selector)`](../selectors.md) gives.

## Site keys

These are the sites the client draws at:

| Key | What it styles |
|---|---|
| `*` | the global fallback — most UI text, and the cascade for every rule you do not write |
| `window.title` | window captions, and the [**plate**](chrome.md#ornaments) they sit on |
| `window.frame` | the window **chrome**: the frame drawn around a window, the surface it sits on, and where its [ornaments](chrome.md#ornaments) go. Draws no text, so it takes `bg`, `border`, `padding` and the ornament properties, not `font` or `color` |
| `panel` | the window-**less** framed surfaces — the boxes around lists and info panes, the HUD portrait, party avatars, flower-menu petals, dropdown menus. Draws no text either; see [what a panel does with a rule](surfaces.md#panels) |
| `heading` | in-window section headings, the embossed fraktur ones |
| `button` | the client's standard buttons: their captions, and the **face** those are drawn on — its fill, its frame, and the fill's own [state faces](chrome.md#a-face-per-state) |
| `label` | body text — attribute rows, list items, explicit-foundry labels |
| `textentry` | text-entry fields **and** the console command line: the letters, and the **field** they are typed into |
| `tooltip` | every tooltip — items, buffs, meters, craft, minimap, the action menu — and the **box** the client pops one up in |
| `inventory.slot` | the empty **square** an inventory grid and the equipment window are paved with. Draws no text, so it takes `bg` and `border` |
| `checkbox` | the **box** a checkbox ticks. Draws no text — the caption beside it is `label`'s — so it takes `bg` and `border` |
| `checkbox.mark` | the **tick** inside that box, drawn only while it is ticked |
| `scrollbar` | the **rail** a scroll thumb runs down |
| `scrollbar.knob` | the **thumb** itself, wherever along that rail it currently sits |
| `slider` | the **rail** a slider's thumb runs along |
| `slider.knob` | that slider's own thumb |
| `hud.belt` | the plate the numbered belt squares are laid on, across the bottom of the screen — [the HUD's plates](hud.md) |
| `hud.menu.left`, `hud.menu.right` | the two plates behind the toggle buttons in the bottom corners |
| `hud.search` | the plate the action-search button sits on |
| `minimap.frame` | the frame drawn around the corner minimap |
| `menu` | flower-menu petals and the action-menu keybind letters |
| `chat` | the chat window — messages, channel tabs, the typed line — and the cascade for the five kinds below |
| `chat.system` | the **System** log's lines: what the client tells you rather than what anyone said |
| `chat.mine` | your **own** line, in whichever channel you said it in |
| `chat.private` | a private message, received or sent |
| `chat.party` | a line in the Party channel |
| `chat.urgent` | the **unread** indicator: a waiting channel's tab, and the glow on the chat button |
| `chat.speaker` | the colour a **speaker** is given in a multi-person channel |
| `world.nick` | floating kin names over characters |
| `world.speech` | speech bubbles |

Each surface keeps **its own stock size and colour** unless your rule overrides them. One key can front two
sites with different stocks — `textentry` covers the serif fields *and* the mono command line — and both
stay native under one rule. [surfaces](surfaces.md) describes each one and its geometry caveats,
[the chat](chat.md) the window and its kinds of line, and [the HUD's plates](hud.md) the five the client
blits whole.

**Six keys refine another key rather than `*`.** The five `chat.` kinds and `chat.speaker` fall back to
[`chat`](chat.md#each-kind-falls-back-to-chat) before they fall back to `*`, because a kind of chat line is
a chat line. Nothing else does: `checkbox.mark` is a **part** of a checkbox rather than a kind of one, so it
takes nothing from `checkbox`, and neither do the two knobs — name both or the one you leave out stays the
client's.

## Tree keys

Any other valid selector — `@Class`, `window[title=…]`, `[text=…]`, `[res=…]`, a chain of steps, or a role
that classifies a *widget* rather than a site (`window`, `inventory`, `item`) — is a **tree key**. Every tree rule
that matches a widget is folded into one style, and
[`widget:style()`](README.md#restyle-one-widget) reads the result back, `nil` when nothing names it.

```lua
hafen.ui():sheet():rule("window[title=Cupboard]"):color{200, 180, 140}:sheet():install()
local s = hafen.session():current()
s:ui():match("window[title=Cupboard]"):style()    --> { color = {r=200, g=180, b=140, a=255} }
s:ui():inventory():style()                       --> nil
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
s:rule("window[title=Cupboard]"):color{200, 180, 140}        -- WHICH widgets       (tree key)
s:install()
```

## What each key accepts

Every *drawing* property is accepted on **every** key — a sheet never errors because a surface cannot use
one — and what differs is what the surface *does* with it. Two exceptions, and both are places where the
value itself would have to mean something different rather than merely land on nothing: the three that lay
widgets out, which only a tree key may carry, and `color` on the two keys whose colour the client
[walks rather than holds](chat.md#the-two-colours-the-client-walks), which take a sequence and refuse a
colour. First the two that write text:

| Key | `font` | `color` | Worth knowing |
|---|---|---|---|
| `*` | yes | yes | cascades to every key you do not write, including the colour |
| `window.title` | yes | **with `emboss(false)`** | an *embossed* surface: a texture is tiled through the glyph mask, so there is nothing left to tint until [the relief is dropped](text.md#emboss) |
| `heading` | yes | **with `emboss(false)`** | embossed the same way. Two stock sizes ride this key and a size-less rule keeps each |
| `button` | yes | **partly** | the ordinary caption is embossed, so it needs `emboss(false)` too; a `wrapped` multi-line caption, or one the client sets *with* a colour, follows a `color` rule as it stands. Stock is bold serif 12, so a serif 12 rule installs correctly and looks like nothing happened |
| `label` | yes | yes | a larger `size=` clips: row heights were measured at construction |
| `textentry` | yes | yes | a larger `size=` clips: a field's height comes from its background, not the font — and a `bg` **is** a background, so the art you give it is what a field built afterwards is as tall as |
| `tooltip` | yes | yes | `$col[…]` rows keep their own colour; `size=` is safe, since a tip sizes its box around its text |
| `menu` | yes | yes | `size=` is safe — a petal re-sizes around its own centre |
| `chat` | yes | yes | one rule paints every kind of line alike — the five keys below are how you keep them apart |
| `chat.system`, `chat.mine`, `chat.private`, `chat.party` | yes | yes | one kind of line each, [falling back to `chat`](chat.md#each-kind-falls-back-to-chat) and then to `*`. `$col[…]` in the line still wins |
| `chat.urgent`, `chat.speaker` | **inert** | **a sequence** | neither draws text, and neither holds one colour: each takes [the sequence it hands out](chat.md#the-two-colours-the-client-walks), per level and per speaker. A flat `color` on either is an **error** |
| `world.nick` | yes | yes | a `color` rule flattens the kin-**group** colours; a font-only rule leaves them |
| `world.speech` | yes | **inert** | the bubble blits its finished text under a flat black tint, so the glyph colour is thrown away on the way to the screen. `size=` is safe — the bubble measures its frame around the text every frame |
| any tree key | yes | **per surface** | [resolved per widget](#tree-keys) and drawn over that widget's whole subtree. It reaches the same surfaces as the rows above and carries their caveats unchanged: a rule on a window covers the window's own caption, where `font` works and `color` waits on an `emboss`. `:style()` reports the colour a rule set even where the surface then throws it away |
| `widget:rule()` | yes | **per surface** | the same, one widget at a time and named by hand rather than matched. Being the top of the cascade changes *who wins*, never *what a surface can do* |

And the two that dress a **carved** surface — what its letters are filled with, and what sits behind them.
[`emboss`](text.md#emboss) reaches the keys the client renders as a **mask** and fills with a picture, which
is exactly the set where `color` is inert until it is dropped, and [`glow`](text.md#glow) the halo those same
keys are blurred behind. The two are independent: a key may carry either alone.

| Key | `emboss` | `glow` | Worth knowing |
|---|---|---|---|
| `window.title` | yes | yes | the caption, in the theme's texture or in the rule's flat `color`, on the theme's halo. The plate behind the lot is [`bg`](chrome.md#bg) on the same key |
| `heading` | yes | yes | both sizes of in-window section heading, the big fraktur ones and the smaller group captions above a grid |
| `button` | yes | yes | the ordinary button caption. A `wrapped` one was never embossed or blurred, so it is unaffected either way |
| `*` | **cascades** | **cascades** | into those three and nowhere else, they being the only surfaces the client carves. So one rule on `*` flattens every carved caption in the client at once |
| a tree key, and `widget:rule()` | yes | yes | resolved per widget and drawn over its whole subtree, so `["window[title=Inventory]"]` flattens one window's caption and leaves every other window carved |
| every other key | **inert** | **inert** | nothing else in the client draws its text through a mask or blurs a halo behind it. Readable back through `:style()`, and inert everywhere it lands |

A window's caption is drawn one way when the window has focus and another when it does not, and the
difference is the colour of that halo. A `glow` rule replaces both, so a themed client tells a focused
window from an unfocused one by whatever else the theme says, not by its caption's shadow.

And the three that draw the chrome. The surfaces that wear them are the ones that draw a box of their own:
the window decoration, the caption plate inside it, the window-less panels, the box a tooltip is popped up
in, an inventory square, a button's face, a text field, the boxes, rails and thumbs of the three
controls the client blits, and the speech bubble over a talking character.
A [state face](chrome.md#a-face-per-state) inside a `bg` is
worn by the surfaces that *have* that state, and ignored by the rest, exactly as the rows below say:

| Key | `bg` | `border` | `padding` | Worth knowing |
|---|---|---|---|---|
| `window.frame` | yes | yes | yes | every window whose chrome is the client's own stock decoration. The only surface where `padding` and a border's insets actually **move** anything, because a window re-lays itself out |
| `window.title` | yes | yes | **inert** | the two paint the caption **plate**, at the box the client sizes around the caption — [the ornaments](chrome.md#ornaments). The caption's own place is `window.frame`'s `caption`, so `padding` has nothing to move here |
| `panel`, on a **boxed** panel | **inert** | yes | **inert** | the list and info boxes, the HUD portrait, party avatars, the map's view and marker list. A border drawn *around* content that is not the panel's, so a fill would bury it — [why](surfaces.md#panels) |
| `panel`, on a **self-painting** panel | yes | yes | **inert** | flower-menu petals, dropdown menus, an item-stock box: each paints its own surface before its contents, so a `bg` lands on it |
| `tooltip` | yes | yes | yes | the box a tip is popped up in. The client sizes it around the tip's own text, so `padding` is the room between that text and the edge — and the box grows outward, leaving the text where it was. With neither `bg` nor `border` the client's own dark fill and yellow outline stay |
| `inventory.slot` | yes | yes | **inert** | one square of an inventory grid, drawn at the size and pitch the client's own square has, so `padding` has nothing to move — [what the square is](surfaces.md#inventoryslot) |
| `button` | yes | yes | **inert** | the **face** of every standard button: the `bg` stands in for the fill its caption is set on, the `border` for the four edge caps around it, and either alone leaves the other the client's own. Its box was fixed when it was built, so `padding` has nothing to move — [what a button's face is](surfaces.md#button) |
| `textentry` | yes | yes | yes | the **field** every line is typed into: the `bg` stands in for its stretched middle, the `border` for the two end caps, and `padding` is the room between those and the text, which moves inside a width the caller still owns — [what a field is](surfaces.md#textentry) |
| `checkbox`, `scrollbar`, `slider` | yes | yes | **inert** | the box a checkbox ticks and the two **rails** a thumb runs along, each painted over the rectangle the control was built with, so `padding` has nothing to move. A [`checked` face](chrome.md#a-face-per-state) inside a `checkbox` `bg` is what a ticked box wears, and it is the only state any of the three enters. A checkbox drawn as a single **picture** — the HUD's map and menu buttons, a dropdown's arrow — is not in this key, for the reason an icon button is not in `button` — [the three controls](surfaces.md#checkbox-scrollbar-and-slider) |
| `checkbox.mark`, `scrollbar.knob`, `slider.knob` | yes | yes | **inert** | the tick and the two **thumbs**, each a key of its own so the part is dressed apart from the whole it sits on. A part never takes the whole's art: name both or the one you leave out stays the client's |
| `world.speech` | yes | yes | **inert** | the **bubble** over a talking character: the `bg` stands in for its white fill, the `border` for the frame around it, and either alone leaves the other the client's own. The bubble measures itself around the sentence every frame, so `padding` has nothing to move — [what the bubble is](surfaces.md#worldspeech-and-worldnick) |
| `*` | **cascades** | **cascades** | **cascades** | `*` is the fallback for a key you did not write, so a chrome property on it reaches every key in the rows above, each subject to its own row. Text-only surfaces ignore it entirely and stay stock |
| the five [HUD plate](hud.md) keys | **inert** | **inert** | **inert** | a plate is a whole picture rather than a fill inside a frame, so what dresses one is [`picture`](chrome.md#picture) and these three land on it and do nothing |
| every other site key | **inert** | **inert** | **inert** | a text site has no surface of its own to paint and no layout of its own to move. Nothing is refused and nothing warns |
| a tree key **matching a window** | yes | yes | yes | it reaches *that* window's chrome, because a window's decoration resolves through the window it belongs to |
| a tree key **matching a panel** | per panel | yes | **inert** | likewise: a panel asks *itself* what style it resolves, so `["@Frame"]` or `["window[title=…] @Frame"]` themes those panels alone. `bg` follows the same two panel rows above |
| a tree key **matching a button or a field** | yes | yes | per surface | likewise again: each asks itself, so `["@Button"]` or `["window[title=…] @TextEntry"]` dresses those alone. `padding` follows the two rows above; a field's **height** does not, being read where a field is built and so from the site half alone |
| a tree key matching anything else | **inert** | **inert** | **inert** | readable back through `widget:style()`, but nothing else in the client wears chrome |
| `widget:rule()` | per surface | per surface | per surface | exactly as the rows above, one widget at a time: on a window it dresses that window's frame, on a panel that panel's box, on a button its face, anywhere else it is inert |

And the ones that dress a window's [ornaments](chrome.md#ornaments).
One surface draws them, so this table is one row.

| Key | `caption` | `sizer` | `closeButton` | Worth knowing |
|---|---|---|---|---|
| `window.frame` | yes | yes | yes | where the decoration puts the caption, and the art and corner of the two ornaments that have one. A window that draws no sizer — which is nearly all of them — ignores that one |
| every other key, `*` included | **inert** | **inert** | **inert** | nothing else in the client draws a window's ornaments. Readable back through `:style()`, and inert everywhere it lands |

> **A site key does not compose with `*` per property.** Within the site half of the cascade a key either
> has a rule of its own or falls back to `*`; it does not take half of each. So `["*"] = {bg = …}` beside
> `["window.frame"] = {border = …}` gives you the border **alone** — write both properties in the rule that
> names the surface. Levels *above* the site half, a tree rule or a `widget:rule()`, do
> [compose per property](README.md#the-cascade).

And the one that replaces a surface outright. [`picture`](chrome.md#picture) reaches two kinds of key,
because the client shows a picture two ways: at a handful of **places of its own**, which have names, and
through pictures the **server** places, which are told apart by nothing but where they sit in the tree.

| Key | `picture` | Worth knowing |
|---|---|---|
| the five [HUD plate](hud.md) keys | yes | `hud.belt`, `hud.menu.left`, `hud.menu.right`, `hud.search` and `minimap.frame`: the plates the client blits at fixed places of its own. The rule's picture fills the rectangle the client already had, so nothing moves and every button on a plate still takes its click |
| `*` | **cascades** | into those five and nowhere else, they being the only sites that blit a plate. So a `picture` on `*` paints the whole HUD alike — name the key you mean |
| a tree key **matching a picture** | yes | `["@Img"]`, or a chain naming the window it sits in. A [state face](chrome.md#a-face-per-state) inside the value is worn by a surface that enters that state, and a picture the server re-points keeps following the rule |
| `widget:rule()` | yes | the same, one picture at a time and named by hand |
| every other site key | **inert** | a text site draws no picture, and a surface that draws a box wears [`bg`](chrome.md#bg) and [`border`](chrome.md#border) instead. Readable back through `:style()`, and inert everywhere it lands |
| a tree key matching anything else | **inert** | nothing else in the client shows a picture of its own |

And the three that lay widgets out. This table is short because the answer is: a widget, or an error.

| Key | `position` / `anchor` | `size` | Worth knowing |
|---|---|---|---|
| any tree key | yes | **unless the widget owns its size** | the widget it matches is moved for real — the field a drag writes — so what you place is what you click. A window that packs around its contents re-packs itself: inert, never an error |
| any site key, `*` included | **error** | **error** | a site is where the client draws text, and text has no position. The error names the fix: select the widget |
| `widget:rule()` | **error** | **error** | the hand-named level is the verb, [`w:position(x, y)`](../native.md) — the error says so |

**Where `color` is inert, the glyph colour is thrown away before anything reaches the screen.** Most of
those surfaces are *embossed*: the client renders the text as a mask, tiles a texture through it and blurs
a [halo](text.md#glow) behind, so there is nothing left for a rule to override until
[`emboss(false)`](text.md#emboss)
stops the tiling — which is what that property is for. The speech bubble is the one that stays inert
whatever you write: it blits its finished text under a flat black tint, and no rule reaches inside that.
All of them still follow a `font` rule perfectly. Nothing is refused and nothing warns.

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
- [the HUD's plates](hud.md) — the five whose whole surface is one picture
- [text](text.md) · [chrome](chrome.md) · [geometry](geometry.md) — the properties themselves
- [selectors](../selectors.md) — the grammar, and the roles this vocabulary shares
