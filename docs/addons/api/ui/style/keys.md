# hafen.ui: Which Surfaces a Rule Reaches

A [sheet](README.md) key is a [selector](../selectors.md) resolved one of two ways: a site key names a place the client draws and is resolved there. Any other valid selector is a tree key and says which widgets to style.

```lua
local sheet = hafen.ui():sheet()
local body = hafen.font():get("serif"):derive():size(12)
sheet:rule("*"):font(body)                                    -- what kind of surface (site key)
sheet:rule("window[title=Cupboard]"):color{200, 180, 140}     -- which widgets (tree key)
sheet:install()
```

| Rule | Detail |
|---|---|
| Which kind a key is | A bare role is a site key. A role with a refiner, or a role with no site behind it (`window`, `inventory`, `item`), is a tree key. The key's shape decides. |
| Invalid grammar | Raises, exactly as [`session:ui():match(selector)`](../selectors.md) does. |

---

## Site keys

| Key | What it styles |
|---|---|
| `*` | The global fallback: most UI text, and the cascade for every rule you do not write. |
| `window.title` | Window captions, and the [plate](chrome.md#ornaments) they sit on. |
| `window.frame` | The window chrome: the frame, the surface it sits on, and where its [ornaments](chrome.md#ornaments) go. Draws no text: takes `bg`, `border`, `padding` and the ornament properties. |
| `panel` | The window-less framed surfaces ([panels](surfaces.md#panels)). Boxes around lists and info panes, the HUD portrait, party avatars, flower-menu petals, dropdown menus, the AddOns manager's Browse cards and screenshot box. |
| `heading` | In-window section headings, the embossed fraktur ones. |
| `button` | Standard buttons: their captions and the face they are drawn on — fill, frame, and the fill's [state faces](chrome.md#a-face-per-state). |
| `label` | Body text: attribute rows, list items, explicit-foundry labels. |
| `textentry` | Text-entry fields and the console command line: the letters and the field they are typed into. |
| `tooltip` | Every tooltip — items, buffs, meters, craft, minimap, the action menu — and the box it pops up in. |
| `inventory.slot` | The empty square an inventory grid and the equipment window are tiled with. `bg` and `border`. |
| `checkbox` | The box a checkbox ticks (its caption is `label`'s). `bg` and `border`. |
| `checkbox.mark` | The tick inside that box, drawn while ticked. |
| `scrollbar`, `scrollbar.knob` | The rail a scroll thumb runs down, and the thumb. |
| `slider`, `slider.knob` | The rail a slider's thumb runs along, and the thumb. |
| `meter` | A bar the game fills: what its fill is drawn on, and the frame round it. The fill stays the server's. |
| `hud.belt` | The plate under the numbered belt squares — [the HUD's plates](hud.md). |
| `hud.menu.left`, `hud.menu.right` | The plates behind the toggle buttons in the bottom corners. |
| `hud.search` | The plate the action-search button sits on. |
| `minimap.frame` | The frame around the corner minimap. |
| `menu` | Flower-menu petals and the action-menu keybind letters. |
| `menu.slot` | The empty square the action menu's grid is tiled with. `bg` and `border`. |
| `menu.frame` | The frame around that grid, `minimap.frame`'s twin, dressed with [`picture`](chrome.md#picture). |
| `chat` | The chat window — messages, channel tabs, the typed line — and the cascade for the kinds below. |
| `chat.frame` | The chat's own decoration: the field it tiles behind everything and the frame round it — `window.frame`'s counterpart. |
| `chat.log` | The wash one channel lays behind its lines, inside that frame. Draws no text. |
| `chat.system` | The System log's lines. |
| `chat.mine` | Your own line, in any channel. |
| `chat.private` | A private message, received or sent. |
| `chat.party` | A line in the Party channel. |
| `chat.urgent` | The unread indicator: a waiting channel's tab, the glow on the chat button. |
| `chat.speaker` | The colour a speaker is given in a multi-person channel. |
| `world.nick` | Floating kin names over characters. |
| `world.speech` | Speech bubbles. |

| Rule | Detail |
|---|---|
| Stock stays per surface | Each surface keeps its own stock size and colour unless a rule overrides them. `textentry` fronts the serif fields and the mono command line, and both stay native under one rule. |
| The `chat.` keys refine `chat`, not `*` | `chat.system`, `chat.mine`, `chat.private`, `chat.party`, `chat.urgent` and `chat.speaker` fall back to [`chat`](chat.md#each-kind-falls-back-to-chat) first. Nothing else cascades through a dot. `chat.frame`, `chat.log`, `checkbox.mark`, the two knobs, `menu.slot` and `menu.frame` are parts, and take nothing from the whole. Name both, or the one left out stays the client's. |

---

## Tree keys

Any other valid selector — `@Class`, `window[title=…]`, `[text=…]`, `[res=…]`, a chain, or a role classifying a widget (`window`, `inventory`, `item`). Every tree rule matching a widget is folded into one style. [`widget:style()`](README.md#restyle-one-widget) reads the result, `nil` when nothing names it.

```lua
hafen.ui():sheet():rule("window[title=Cupboard]"):color{200, 180, 140}:sheet():install()
local session = hafen.session():current()
session:ui():match("window[title=Cupboard]"):style()    -- { color = {r=200, g=180, b=140, a=255} }
session:ui():inventory():style()                       -- nil
```

| Rule | Detail |
|---|---|
| The whole subtree | Parents draw before children. A tree rule is in force for the matched widget's caption, labels, button captions, rows and widgets created inside it later. Text drawn by the game's own resource code is included. |
| A window's frame | No role names the decoration (a child of the window with no role), so `window.frame` names the site. A `["window…"]` tree rule still reaches both the text inside and the chrome, because the decoration asks the window what it resolved. |
| The more specific rule wins, per property | Specificity is the selector's parts added up — role 1, `@Class` 2, `[title=]`/`[text=]` 4, `[res=]` 8, `[name=]` 16 — a chain summing every step. A specific rule setting only `color` does not take the `font` a broader one set. Equal specificity goes to the rule applied last, addons included. Applied means when the sheet was first installed, so editing one rule never promotes a sheet. |
| A chain reaches everything inside | `window[title=Cupboard] *` matches every widget below that window. `window[title=Cupboard]` names the window alone. A chain follows a rename: a caption change re-resolves everything below it. |
| A site key is not a widget's style | `*` and the site keys resolve where they draw. `widget:style()` never reports one. |
| Tree over site | Where both reach the same text, the tree rule wins for the properties it names and the site rule fills the rest. The example above paints one window's text in `body`, in tan, and every other window in `body` in its own colour. |

---

## What each key accepts

Every drawing property is accepted on every key. What differs is what the surface does with it. Two exceptions raise: `position`, `anchor`, `size` and `margin` on a site key, and a flat `color` on `chat.urgent` or `chat.speaker`, which take a sequence.

### Text

| Key | `font` | `color` | Notes |
|---|---|---|---|
| `*` | Yes | Yes | Cascades to every key you do not write. |
| `window.title` | Yes | With `emboss(false)` | Embossed: a texture is tiled through the glyph mask, so nothing tints until [the relief is dropped](text.md#emboss). |
| `heading` | Yes | With `emboss(false)` | Embossed. Two stock sizes use this key. A size-less rule keeps each. |
| `button` | Yes | Partly | The ordinary caption is embossed (needs `emboss(false)`). A `wrapped` caption, or one the client sets with a colour, follows `color` as it stands. Stock is bold serif 12. |
| `label` | Yes | Yes | A larger `size` clips: row heights were measured at construction. |
| `textentry` | Yes | Yes | A larger `size` clips. A field's height comes from its background, and a `bg` is that background. The art you give is the height of a field built afterwards. |
| `tooltip` | Yes | Yes | `$col[…]` rows keep their colour. `size` is safe, a tip sizing its box around its text. |
| `menu` | Yes | Yes | `size` is safe: a petal re-sizes around its centre. |
| `chat` | Yes | Yes | One rule paints every kind of line alike. |
| `chat.system`, `chat.mine`, `chat.private`, `chat.party` | Yes | Yes | One kind each, [falling back to `chat`](chat.md#each-kind-falls-back-to-chat), then `*`. `$col[…]` in the line wins. |
| `chat.urgent`, `chat.speaker` | Inert | A sequence | Neither draws text nor holds one colour: each takes [the sequence it hands out](chat.md#the-colours-the-client-walks). A flat `color` raises. |
| `world.nick` | Yes | Yes | A `color` rule flattens the kin-group colours. A font-only rule leaves them. |
| `world.speech` | Yes | Inert | The bubble blits its text under a flat black tint. `size` is safe. |
| Any tree key, `widget:rule()` | Yes | Per surface | Resolved per widget over its subtree, carrying the caveats above: a rule on a window covers its caption, where `color` waits on `emboss`. `:style()` reports a colour a surface then throws away. |

### Carved surfaces

[`emboss`](text.md#emboss) reaches the keys the client renders as a mask and fills with a picture. [`glow`](text.md#glow) reaches the halo behind them. Independent: a key may carry either alone.

| Key | `emboss` | `glow` | Notes |
|---|---|---|---|
| `window.title` | Yes | Yes | The caption, in the theme's texture or the rule's flat `color`, on the theme's halo. The plate behind it is [`bg`](chrome.md#bg) on the same key. |
| `heading` | Yes | Yes | Both sizes of section heading. |
| `button` | Yes | Yes | The ordinary caption. A `wrapped` one was never carved. |
| `*` | Cascades | Cascades | Into those three alone: one rule on `*` flattens every carved caption. |
| A tree key, `widget:rule()` | Yes | Yes | Per widget: `["window[title=Inventory]"]` flattens one window's caption. |
| Every other key | Inert | Inert | Readable through `:style()`. |

A window's caption halo differs focused and unfocused. A `glow` rule replaces both.

### Chrome

Honoured by the surfaces that draw a box of their own. A [state face](chrome.md#a-face-per-state) inside a `bg` is used by the surfaces that have that state.

| Key | `bg` | `border` | `padding` | Notes |
|---|---|---|---|---|
| `window.frame` | Yes | Yes | Yes | Every window wearing the stock decoration. The one surface where `padding` and a border's insets move anything, since a window re-lays itself out. |
| `window.title` | Yes | Yes | Inert | Paint the caption plate at the box the client sizes around the caption. The caption's place is `window.frame`'s `caption`. |
| `panel`, boxed | Inert | Yes | Inert | List and info boxes, the HUD portrait, party avatars, the map's view and marker list. A border around content that is not the panel's ([why](surfaces.md#panels)). |
| `panel`, self-painting | Yes | Yes | Inert | Flower-menu petals, dropdown menus, an item-stock box, a Browse card, the screenshot box. |
| `tooltip` | Yes | Yes | Yes | The box a tip pops up in. `padding` is the room between text and edge, the box growing outward. Without `bg` or `border` the client's dark fill and yellow outline stay. |
| `inventory.slot` | Yes | Yes | Inert | One grid square at the client's size and pitch — [the square](surfaces.md#inventoryslot). |
| `button` | Yes | Yes | Inert | The face: `bg` stands in for the fill, `border` for the four edge caps. Either alone leaves the other. Its box was fixed when built — [the face](surfaces.md#button). |
| `textentry` | Yes | Yes | Yes | The field ([the field](surfaces.md#textentry)). `bg` is the stretched middle, `border` the two end caps. `padding` is the room between those and the text, inside a width the caller owns. |
| `menu.slot` | Yes | Yes | Inert | One action-menu cell, the same raster as `inventory.slot` under its own key. |
| `chat.frame` | Yes | Yes | Inert | `bg` the field tiled inside the margin, `border` the frame. The client's frame has no bottom edge, so art wants a transparent bottom slice — [the chat](chat.md#the-chats-own-decoration). |
| `chat.log` | Yes | Yes | Inert | The wash behind a channel's lines. The client paints no frame there, so a `border` adds one. |
| `meter` | Yes | Yes | Inert | `bg` is what the fill is drawn on (the trough of a horizontal bar, the whole box of a vertical one). `border` frames the bar. The fill is the server's, one per meter. |
| `checkbox`, `scrollbar`, `slider` | Yes | Yes | Inert | The box and the two rails, over the rectangle the control was built with. A `checked` face in a `checkbox` `bg` is what a ticked box draws. A checkbox you built and [disabled](../writes.md#enabled-and-disabled) wears `disabled`. A checkbox drawn as one picture (the HUD's map and menu buttons, a dropdown's arrow) is not in this key — [the three controls](surfaces.md#checkbox-scrollbar-and-slider). |
| `checkbox.mark`, `scrollbar.knob`, `slider.knob` | Yes | Yes | Inert | The tick and the two thumbs, each dressed apart from its whole. |
| `world.speech` | Yes | Yes | Inert | The bubble: `bg` its white fill, `border` its frame — [the bubble](surfaces.md#worldspeech-and-worldnick). |
| `*` | Cascades | Cascades | Cascades | Into every row above, each under its own row. Text-only surfaces ignore it. |
| The [HUD plate](hud.md) keys | Inert | Inert | Inert | A plate is a whole picture: [`picture`](chrome.md#picture) dresses it. |
| Every other site key | Inert | Inert | Inert | A text site has no surface to paint. |
| A tree key matching a window | Yes | Yes | Yes | That window's chrome. |
| A tree key matching a panel | Per panel | Yes | Inert | `["@Frame"]` or `["window[title=…] @Frame"]` themes those panels alone. |
| A tree key matching a button or field | Yes | Yes | Per surface | `["@Button"]`, `["window[title=…] @TextEntry"]`. A field's height is read where it is built, from the site half alone. |
| A tree key matching a widget an addon built | Yes | Yes | On a column | Applied over the widget's own [stock](../custom.md#naming-and-dressing-your-own-surfaces): `bg` under the addon's `Draw`, `border` over it. `padding` is the room inside a [column or row](../column.md). |
| A tree key matching anything else | Inert | Inert | Inert | Readable through `widget:style()`. |
| `widget:rule()` | Per surface | Per surface | Per surface | As the rows above, one widget at a time. |

> A site key does not compose with `*` per property. Within the site half a key has a rule of its own or falls back to `*` whole. So `["*"] = {bg = …}` beside `["window.frame"] = {border = …}` gives the border alone. Write both properties in the rule naming the surface. Levels above the site half do [compose per property](README.md#the-cascade).

### Ornaments

| Key | `caption` | `sizer` | `closeButton` | Notes |
|---|---|---|---|---|
| `window.frame` | Yes | Yes | Yes | Where the decoration puts the caption, and the art and corner of the two ornaments. A window that draws no sizer ignores that one. |
| Every other key, `*` included | Inert | Inert | Inert | Readable through `:style()`. |

### Picture

[`picture`](chrome.md#picture) reaches the places of the client's own that blit a picture, and the pictures the server places. They are told apart by where they sit in the tree.

| Key | `picture` | Notes |
|---|---|---|
| The [HUD plate](hud.md) keys | Yes | The rule's picture fills the rectangle the client had. Nothing moves and every button keeps its click. |
| `menu.frame` | Yes | The grid is drawn over it, so art with no hole hides the actions. |
| `meter` | Yes | The frame a bar blits round itself, over the fill on a horizontal bar, under it on a vertical one. On the horizontal one the art is the server's, one per meter, so give a single picture a centre the fill shows through. |
| `*` | Cascades | Into the plates, `menu.frame` and `meter` alone: a `picture` on `*` paints the whole HUD, the action menu's frame and every meter alike. |
| A tree key matching a picture | Yes | `["@Img"]`, or a chain naming its window. A state face inside is used by a surface entering that state. A picture the server re-points keeps following the rule. |
| `widget:rule()` | Yes | One picture at a time. |
| Every other key | Inert | A text site draws no picture. A boxed surface takes `bg` and `border`. |

### Layout

| Key | `position` / `anchor` | `size` | `margin` | Notes |
|---|---|---|---|---|
| Any tree key | Unless a column places it | Unless the widget owns its size | Inside a column | The matched widget is moved for real, the field a drag writes. A child a [column](../column.md) lays out has no place of its own and a column's box is its content's: inert, never an error. A window that packs around its contents re-packs, inert the same way. [`margin`](geometry.md#margin) is honoured on a column's child and inert on a widget placed by hand. |
| Any site key, `*` included | Raises | Raises | Raises | A site is where text is drawn. The error names the fix: select the widget. |
| `widget:rule()` | Raises | Raises | Yes | The hand-named level of a place is [`widget:position(x, y)`](../native.md). No verb spells a margin, so this level is its hand-named one, as for `padding`. A [`:stock`](../custom.md#naming-and-dressing-your-own-surfaces) takes it too. |

| Structural limit | Detail |
|---|---|
| Where `color` is inert | The glyph colour is thrown away before the screen: embossed surfaces until [`emboss(false)`](text.md#emboss). The speech bubble always. `font` still applies. |
| Text rasterised at class-load | A static field's text never follows a rule. The one such row the client ships is reached by carrying a local copy of that resource's code — [surfaces](surfaces.md#tooltip). |
| `$col[…]` markup | Wins over a `color` rule: it is part of the string. |
| A rule flattens meaning | While `["*"] = {color = …}` is on, a red warning is the same colour as everything else. Style one key when that matters. |
| Geometry | Only [`padding`](chrome.md#padding) moves anything, where a surface owns its layout. A larger font `size` clips where a box was measured from the stock font — [surfaces](surfaces.md). |

---

## See Also

- [Style](README.md) — the sheet, the cascade and `widget:rule()`.
- [Surfaces](surfaces.md), [HUD](hud.md), [Chat](chat.md) — what each key is on screen.
- [Text](text.md), [Chrome](chrome.md), [Geometry](geometry.md) — the properties.
- [Selectors](../selectors.md) — the grammar, and the roles this vocabulary shares.
