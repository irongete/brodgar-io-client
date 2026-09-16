# hafen.ui: The Client's Own Surfaces

What each [site key](keys.md#site-keys) is on screen, how a rule takes effect on it, and the caveats that follow from what the surface is. [Keys](keys.md) says which properties each honours; [chat](chat.md) and [hud](hud.md) cover two families of their own.

```lua
local sheet = hafen.ui():sheet()
sheet:rule("*"):font(body)                 -- everything routed
sheet:install()
sheet:rule("button"):font(heading_face)    -- buttons in another face; an installed sheet is live
sheet:rule("button"):release()             -- buttons fall back to the cascade again
```

| Rule | Detail |
|---|---|
| Stock size is inherited | `["label"] = { font = h }` swaps the family everywhere while an 18 px row stays 18 px, unless the handle carries a size. |
| Re-rendering is lazy | Each surface picks a rule up the next time it draws, so a mass restyle never stalls a frame. |
| `*` | Cascades to every routed surface with no more-specific rule. |

---

## `window.title`

The caption in a window's title bar, drawn by the window's decoration (a site, not a role: `window[title=…]` names the window). Embossed, so it follows `font` as it stands and `color` waits on [`emboss(false)`](text.md#emboss). Each visible window re-renders its caption the frame after the rule moves. The key also carries the plate the caption sits on: a `bg` or `border` fills the box the client sizes around the caption; the caption's place is [`window.frame`'s `caption`](chrome.md#ornaments).

## `window.frame` and `panel`

Neither draws text. `window.frame` is the decoration around a window: [`bg`](chrome.md#bg), [`border`](chrome.md#border), [`padding`](chrome.md#padding) and the [ornament](chrome.md#ornaments) properties, and the one surface where padding and a border's insets move anything, since a window re-lays itself out.

### Panels

Framed surfaces that are not windows: the boxes around lists and info panes in the character sheet, skills, quests, wounds, fight and buddy windows; the HUD portrait; party avatars; the map's view and marker list; flower-menu petals; dropdown menus; the AddOns manager's Browse cards and screenshot box.

| Rule | Detail |
|---|---|
| `border` reaches every panel; `bg` per kind | A rule's `bg` replaces a surface the client already paints. Petals, dropdown menus, an item-stock box, a Browse card (`@AddonCard`, with a `hover` face) and the screenshot box paint their own surface, so a `bg` lands. The boxed panels are a border around content that is not theirs — the character sheet's rows belong to the window — so `bg` is inert there. |
| A panel never moves | Its size and its contents' places were decided when built: `padding` and a border's own insets are inert, the art drawn into the room the stock frame had (about 5 design px of edge). A much fatter image overlaps the contents. |
| The change is small | A border rule swaps a few pixels of edge inside geometry that stays put. |

## `inventory.slot`

The empty square an inventory is paved with — your own, every container you open, the equipment window's slots. Takes [`bg`](chrome.md#bg) and [`border`](chrome.md#border) only; the client's own square is a translucent dark-green fill inside a one-pixel darker outline, drawn in code.

```lua
sheet:rule("inventory.slot"):bg{ color = {40, 20, 60, 255} }:border{ color = {255, 140, 40}, width = 1 }
```

| Rule | Detail |
|---|---|
| Inside the client's rectangle | The square's size is the grid's pitch, measured when the inventory was built: a rule says what fills it, never how big; art of another size is scaled in; `padding` moves nothing. |
| Painted before the items | A `bg` never buries an item. |
| Masked-off cells | Dimmed by the client; the dimming multiplies through picture art and leaves a flat `bg` colour as written. |
| Not in this key | The belt, the action-menu grid (`menu.slot`), the craft window's slots and the combat-manoeuvre row blit the same raster and keep the client's own. |

## `menu.slot`

The empty square the action menu's grid is paved with, every page of it: the same raster as [`inventory.slot`](#inventoryslot) under a key of its own, so a theme may dress the two alike or apart. The keybind letters over it are [`menu`](#menu)'s; the frame round the grid is `menu.frame`, dressed with [`picture`](chrome.md#picture) like [`minimap.frame`](hud.md), and drawn under the grid, so art with no hole hides the actions. The rule paints inside the client's cell at the client's pitch; every click lands; `padding` moves nothing; a `bg` under a `border` is a near restatement, the stock outline being one device pixel with transparent corners.

```lua
local cell = { bg = {color = {20, 30, 50, 140}}, border = {color = {34, 232, 245}, width = 1} }
sheet:rule("inventory.slot"):bg(cell.bg):border(cell.border)
sheet:rule("menu.slot"):bg(cell.bg):border(cell.border)
```

## `button`

The client's standard buttons — Options, character sheet, craft and build, tabs, key binds, `wrapped` multi-line buttons — caption and face both: the letters through [`font`](text.md#font), the surface through [`bg`](chrome.md#bg) and [`border`](chrome.md#border). Each visible button re-renders the frame after the rule moves.

```lua
sheet:rule("button"):bg{ color = {70, 40, 100}, pressed = { color = {220, 130, 40} } }
                    :border{ color = {255, 220, 40}, width = 2 }
```

| Rule | Detail |
|---|---|
| A fill inside a frame, replaced apart | `bg` stands in for the centre texture, `border` for the four edge caps; name one and the other stays the client's. The caption is drawn between them. |
| Three states | `pressed`, `hover` and `disabled`, [inside the `bg`](chrome.md#a-face-per-state). The stock face has no hover of its own. `checked` is never asked for. |
| A face never resizes | The box is the width it was built with and the height of the client's art; a heavier frame overlaps the caption. `padding` is inert. |
| A disabled button | Greyed by the client as always (the rasterised picture monochromised); the fill is the `disabled` face you named. One you [disabled](../writes.md#enabled-and-disabled) is dimmed on top. |
| Stock caption font | Bold serif 12: a serif 12 rule installs and looks like nothing happened. |
| `color` | Reaches the ordinary caption with [`emboss(false)`](text.md#emboss); a `wrapped` caption, and one the client sets with a colour, follow it as it stands. |
| Not in this key | A picture button (icon buttons, character-selection entries) and button-shaped widgets that are not buttons (checkboxes, radio labels). |

## `textentry`

Every editable field — the chat input, search boxes, the login name and password fields, name-a-save fields — and the console command line (`:` prompt), letters and field both. Each field drops its cached line when the rule moves; caret and selection follow the new glyph advances.

```lua
sheet:rule("textentry"):bg{ asset = "img/field.png" }:border{ color = {255, 190, 60}, width = 2 }:padding(8, 3, 8, 3)
```

| Rule | Detail |
|---|---|
| A stretched middle between two end caps | `bg` stands in for the middle, `border` for the caps; the caret is drawn over whichever painted. |
| `padding` | The room between field and text, out of a width the caller owns: the text moves inward and the visible run shortens; clicking, caret placement and drag-selection read the same offset. |
| A `bg` decides the height a field is built at | A field's height comes from its background, so a much larger `size` clips (stay near the stock serif 12, or the command line's mono 12). A picture `bg` is that background: a field built while the rule is installed is as tall as the art; a flat `color` leaves the stock height. |
| Only a field built afterwards, only from the site key | Nothing re-measures an existing field, and the height is read before a [tree key](keys.md#tree-keys) has a widget to match: `["@TextEntry"]` paints and never resizes. Install the rule before building your own [entries](../controls/interactive.md#text-entry), and `:size(w)` gives them the art's height. |

## `checkbox`, `scrollbar` and `slider`

The controls the client draws out of blitted pictures, each two keys: the whole and the part that moves over it. All of them take [`bg`](chrome.md#bg) and [`border`](chrome.md#border) and no text (a checkbox's caption is [`label`](#label)'s).

| Key | What it is | Where |
|---|---|---|
| `checkbox` | The square a tick sits in. | Options rows, radio buttons. |
| `checkbox.mark` | The tick, drawn while ticked. | The same boxes. |
| `scrollbar` | The vertical rail. | Any list long enough to scroll. |
| `scrollbar.knob` | The thumb running down it. | The same bars. |
| `slider` | The horizontal rail. | The volume and interface sliders in Options. |
| `slider.knob` | The thumb running along it. | The same sliders. |

```lua
sheet:rule("checkbox"):bg{ color = {200, 40, 40}, checked = { color = {40, 40, 200} } }
sheet:rule("checkbox.mark"):bg{ asset = "img/tick.png" }
sheet:rule("scrollbar"):bg{ color = {30, 60, 120} }:border{ color = {255, 255, 255}, width = 1 }
```

| Rule | Detail |
|---|---|
| Two arts, neither implying the other | Name only `scrollbar` and the client's thumb runs down your rail; name only `scrollbar.knob` and it runs down the client's chain. |
| Inside the control's rectangle | A rail fills the control, a thumb the client's thumb box, a tick the client's tick box, wherever the control puts it; the grab shape never moves; `padding` is inert. |
| States | `checked` is the one state a checkbox enters on its own: on `checkbox` the ticked box, on `checkbox.mark` the face the tick wears. `disabled` is asked for by a checkbox you [disabled](../writes.md#enabled-and-disabled) alone; `hover` and `pressed` never. A flat `color` on `checkbox.mark` fills the whole box and buries the face: give the mark a picture with transparency. |
| Not in this key | A picture checkbox — the HUD's map and menu buttons, a dropdown's arrow — where the image is the meaning and the click is routed by its transparency; and a scrollbar whose list fits, which draws nothing. |

## `meter`

A bar the game fills: the HUD's hunger and stamina meters, a container's fill gauge, a quality or progress bar in a window. A horizontal bar paints a trough, fills it and blits a frame over the lot; a vertical one blits its frame first and fills up over it.

| Property | Reaches |
|---|---|
| [`bg`](chrome.md#bg) | What the fill is drawn on: the trough of a horizontal bar, the whole box of a vertical one. |
| [`picture`](chrome.md#picture) | The frame the bar blits, replaced where the bar draws its own. On a horizontal bar the frame is the server's, one per meter, so one picture makes every bar alike; give it a transparent centre. |
| [`border`](chrome.md#border) | A frame around the whole bar, painted last; the client draws none, so this adds one. |

```lua
sheet:rule("meter"):bg{ color = {8, 12, 20, 255} }:border{ color = {34, 232, 245}, width = 1 }
```

The fill is never a rule's: its colour is the server's, one per meter, and tells a hunger bar from a stamina one; `color` is inert. This key declares no stock look — the two shapes are made of different things — so [`sheet:stock()`](README.md#the-clients-own-look) leaves it out, as it does `panel`, `scrollbar` and `slider`. A meter's tooltip is [`tooltip`](#tooltip)'s.

## `heading`

The embossed fraktur captions inside a window ("Base Attributes", "Quest Log", "Kin", the credo group captions, a village name, the quest-completed banner), a key of its own so a heading is restyled apart from the title bar and body text. Two stock sizes ride it; a rule with no `size` keeps each. Headings are baked into an image and re-rendered the frame after the rule moves; embossed, so `color` wants [`emboss(false)`](text.md#emboss).

## `label`

The client's body text rendered with its own hand-picked foundry (default labels belong to `*`): attribute rows in the character sheet's Base and Study tabs; list items in Skills & Lore, Quests, Wounds, combat manoeuvres, Icon settings; menu-search results; explicit-foundry labels (credo `Level:` and `Quest:` lines, wound quality, the combat-schools counter, the login screen, a village name). Each keeps its colour and wrap width unless a rule sets them.

> With `size`: list and attribute row heights were computed from the stock font at construction, so taller glyphs clip; a `Label` resizes to its text while its container does not re-lay around it.

Not in this key: caller-supplied pre-rendered text, and text a widget rasterises into its own face.

## `tooltip`

Every tooltip the client pops up, and the box it pops up in. The tooltip engine composes the tip of an inventory item, a buff, a HUD meter, a craft input or output, a minimap marker, an attribute row and an action-menu icon; plain string tips re-render at display time (the tip already under the cursor included); widget tips carry their `Keyboard shortcut: …` tail; resource pagina, food and study, terrain, minimap, keybind-help and combat tips follow; equipment empty-slot names and skill and credo list tips re-render on the change.

| Rule | Detail |
|---|---|
| Markup keeps working | `$b`, `$col`, `$img`: the rule swaps family and size, not the whole font attribute, so a `$col` row keeps its colour under a `color` rule. Each cached tooltip image re-composes on the next hover. `size` is safe: a tip sizes its box around its text. |
| Resource-shipped rows | `Quality:`, `Wear`, `Armor class`, `Gilding`, the attribute deltas are drawn by code inside the game resources and reached anyway: a tooltip composition is declared tooltip text, so text rendered inside it follows the key, keeping its own size. A family swap can still move a row by a pixel through ascent and descent. |
| One row rasterised at class-load | The `Gilding:` heading is a static field no font system can restyle; the client carries a version-matched local copy of that resource's code, which renders it on demand. If the game ships a new version, the copy steps aside (noted in the client log) until refreshed. |
| The box | [`bg`](chrome.md#bg) fills it, [`border`](chrome.md#border) outlines it (the client's own is a line), [`padding`](chrome.md#padding) is the room between text and edge; the box grows outward and never re-wraps. With neither `bg` nor `border` the client's dark fill and yellow outline stay. Painted outside the tip's cached image, so it lands on the tip already under the cursor. |

## `menu`

The petal captions of a flower menu and the keybind letters the action-menu grid paints over its buttons. A petal re-renders and re-sizes around its own centre, so an open menu restyles in place and a bigger `size` is safe.

## `world.speech` and `world.nick`

`world.speech` is the speech bubble over a character's head in area chat, your own included; the bubble measures its frame around the text every frame, so a large size is safe. Its box is this key too: [`bg`](chrome.md#bg) stands in for the white field, [`border`](chrome.md#border) for the frame, either alone leaving the other; a heavier frame draws into the stock frame's room; the tail stays the client's. The text is black whatever you write ([`color`](keys.md#what-each-key-accepts) is inert), so give it a light fill.

`world.nick` is the floating name over characters on your kin list, in their kin-group colour: a font-only rule leaves the colour and re-centres the label; a `color` rule flattens the groups. Any other world label composed by the same mechanism follows it. Neither world key follows a tooltip composition; both fall back to `*`.

---

## See Also

- [Keys](keys.md) — which properties each key honours.
- [Chat](chat.md), [HUD](hud.md) — the chat's kinds of line and the plates blitted whole.
- [Style](README.md) — installing the sheet.
- [Text](text.md), [Chrome](chrome.md) — the properties and the handles they take.
