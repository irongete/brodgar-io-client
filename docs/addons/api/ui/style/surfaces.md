# hafen.ui: the client's own surfaces

What each [site key](keys.md#site-keys) actually is on screen, how to see a rule take effect on it, and the
caveats that only make sense once you know what the surface is. [keys](keys.md) says *which* properties each
one honours; this page says *what it is*.

Two things hold everywhere below. A rule **inherits each site's stock size** unless your handle carries
one, so `["label"] = { font = h }` swaps the family everywhere while an 18 px row stays 18 px — the safe way
to restyle text without moving layouts. And re-rendering is **lazy**: each surface picks the rule up the
next time it draws, so a mass restyle never stalls a frame.

## The global fallback

`*` is the broad hammer: it cascades to every routed surface with no more-specific rule, so `["*"]` alone
changes everything while another key refines any one surface.

```lua
local s = hafen.ui():sheet()
s:rule("*"):font(h)                    s:install()   -- everything routed
s:rule("button"):font(h2)                     -- ...but buttons use h2 (an installed sheet is live)
s:rule("button"):remove()                            -- buttons fall back to the cascade again
```

## `window.title`

The caption in a window's title bar, drawn by the window's **decoration** rather than placed as a widget of
its own — which is why it is a site and not a role. `window[title=…]` names the window that carries the
caption, not the caption itself. It is **embossed**, so it follows a `font` rule and a `color` rule is
inert. Each **visible** window re-renders its caption on the frame after the rule moves.

This key also carries the **plate** the caption sits on: a `bg` or a `border` here fills the box the client
sizes around the caption, so a longer title makes a wider plate and the rule only says what fills it. The
caption's own place is [`window.frame`'s `caption`](chrome.md#ornaments),
because that is the surface doing the drawing.

## `window.frame` and `panel`

Neither draws text. `window.frame` is the decoration the client draws around a window — it takes
[`bg`](chrome.md#bg), [`border`](chrome.md#border), [`padding`](chrome.md#padding) and the
[ornament](chrome.md#ornaments) properties, and it is the one surface where padding and a border's own
insets actually **move** anything, because a window re-lays itself out around its content.

### Panels

A great deal of the client is framed without being a window: the boxes around lists and info panes in the
character sheet, skills, quests, wounds, fight and buddy windows; the HUD portrait; party avatars; the
map's view and marker list; flower-menu petals; dropdown menus. They draw a 9-slice of their own rather
than carrying a window's decoration, and `panel` is the key for all of them.

- **`border` reaches every one of them. `bg` does not, and the split is per kind.** A rule's `bg` replaces
  a surface the client *already paints*; it never invents one. The petals, the dropdown menus and an
  item-stock box each paint their own surface before their contents, so a `bg` lands there. The **boxed
  panels are a border drawn *around* content that is not theirs** — the attribute rows in the character
  sheet belong to the window, not to the box — so a fill would bury the very rows the box is drawn around.
  On those, `bg` is **inert** and `border` is what you style with. Nothing is refused and nothing warns.
- **A panel never moves.** Its size, and where its contents sit, were decided when it was built, and no
  rule re-runs that. So [`padding`](chrome.md#padding) is inert here, and so are your **border's own
  insets**: the art is drawn *into* the room the stock frame had, not around it. The stock boxes are about
  **5 design px** of edge, so author your image to roughly that weight; a much fatter one overlaps the
  panel's contents rather than pushing them aside. This is the opposite of `window.frame`, where the insets
  *are* the margins, because a window re-lays itself out and a panel cannot.
- **The change is deliberately small.** A border rule on a boxed panel swaps a few pixels of edge art inside
  geometry that stays put. If you want a panel to read differently, say it in the border image; there is no
  fill behind it to carry the difference.

## `inventory.slot`

The empty **square** an inventory is paved with: your own, every container you open — a cupboard, a chest, a
cart, a stockpile — and the slots of the equipment window, which are the same square laid out around the
character. It draws no text, so it takes [`bg`](chrome.md#bg) and [`border`](chrome.md#border) and nothing
else. The client's own square is exactly those two — a translucent dark-green fill inside a one-pixel darker
outline, drawn in code rather than loaded from a resource — so a
[line](chrome.md#border) border and a flat `bg` restate it, and any other art replaces it.

```lua
local s = hafen.ui():sheet()
s:rule("inventory.slot"):bg{ color = {40, 20, 60, 255} }
                        :border{ color = {255, 140, 40}, width = 1 }
s:install()
```

Three things follow from where the square is drawn, and none of them is a limit you can lift:

- **A rule paints inside the client's own rectangle.** The square's size *is* the grid's pitch — the cell an
  item icon is placed on was measured from it when the inventory was built — so a rule says what fills that
  rectangle and never how big it is. Art of another size is scaled into it, and `padding` has nothing to
  move here.
- **The squares are painted before the items.** An inventory draws its whole grid and then its icons over
  it, so a `bg` never buries an item however opaque it is, and a hovered or dragged item is unaffected.
- **A masked-off cell is dimmed by the client**, as it always was — a container whose shape leaves some
  cells unusable draws those darker. The dimming multiplies through *picture* art and leaves a flat `bg`
  colour as written, so a themed grid that wants its dead cells to read differently gives them a picture.

Deliberately not in this key: the belt across the bottom of the screen, the action-menu grid, the craft
window's input and output slots and the combat-manoeuvre row. Each of those happens to blit the *same*
raster, but none of them is an inventory square, and each keeps the client's own.

## `button`

The client's standard buttons — the Options window, the character-sheet, craft and build buttons, tab
buttons, key-bind buttons, `wrapped` multi-line buttons — **caption and face both**: the letters through
[`font`](text.md#font), and the surface they are set on through [`bg`](chrome.md#bg) and
[`border`](chrome.md#border). Buttons rasterise themselves into an image, so each **visible** button
re-renders on the frame after the rule moves — open a window with buttons while toggling and you see it live.

```lua
local s = hafen.ui():sheet()
s:rule("button"):bg{ color = {70, 40, 100}, pressed = { color = {220, 130, 40} } }
                :border{ color = {255, 220, 40}, width = 2 }
s:install()
```

**The client's own button is a fill inside a frame, and a rule replaces the two apart.** Its `bg` stands in
for the centre texture the caption is set on, its `border` for the four edge caps around it; name one and
the other stays the client's own, which is why a `bg` alone paints inside the stock frame rather than over
it. The caption is drawn **between** them, as it always was: over the fill, under the frame.

- **Three states reach a button**, and they ride inside the `bg`
  [as a face per state](chrome.md#a-face-per-state): `pressed` while it is held down, `hover` while the
  pointer is on it, `disabled` on a button the client has greyed out. The stock face has no hover of its
  own, so a button follows the pointer only once a rule gives it something to follow it with. `checked`
  belongs to a checkbox and is never asked for here.
- **A face never resizes a button.** Its box is the width it was built with and the height of the client's
  own art, decided before any rule existed, so a picture is drawn into that rectangle. A frame heavier than
  the stock edge overlaps the caption rather than pushing it aside.
- **A disabled button is greyed by the client**, as it always was — it monochromises the picture it
  rasterised, which is the caption and whatever of its own art the rule left in place. The fill beside it is
  the `disabled` face you named, at the colour you named it.
- **`padding` is inert here.** A button's caption is centred in a box it does not own, so there is no room
  to make.

The stock caption font is **bold serif 12**, so overriding `button` with a serif handle at size 12 installs
correctly and looks like nothing happened. Pick a contrasting family when you want to see the change.

`color` reaches only part of this key: the ordinary caption is embossed, so a colour rule is inert on it,
while a `wrapped` multi-line caption and any caption the client sets *with* a colour of its own do follow
it, both rendering through the plain foundry.

Two surfaces are deliberately not in this key: a button that is a **picture** rather than a box with a
caption in it — the icon buttons, the character-selection list entries — and button-shaped widgets that are
not buttons at all (checkboxes, radio labels). Neither draws the fill-inside-a-frame this key dresses, and
neither renders a caption through it. A standard button the client hands a ready-made caption to is one of
these buttons still: its letters are the client's, its face is the rule's.

## `textentry`

Both of the client's text-input surfaces: every editable field — the chat input, search boxes, the login
name and password fields, name-a-save fields — **and** the console command line, the `:` prompt, so `:lua`
and your own [`hafen.slash`](../../slash.md) commands are typed in your font too. Each field drops its
cached line when the rule moves, so the change is live on the next frame, and selection and caret positions
follow the new glyph advances automatically.

> **Geometry caveat.** A field's *height* comes from its background texture, not from the font, so a much
> larger size is drawn and then vertically clipped. Stay near the stock serif 12 — the command line's stock
> is mono 12, wheat-coloured, and a font-only rule inherits that per-site colour — unless you want the
> clipping.

## `heading`

The big embossed fraktur captions **inside** a window: "Base Attributes", "Food Satiations", "Abilities",
"Study Report", "Lore & Skills", "Quest Log", "Health & Wounds", "Kin", the credo group captions, a village
name, the quest-completed banner. It is deliberately its own key — a heading is neither the window's title
bar nor body text, so you can restyle one without the others.

Two stock sizes ride this key, window headings and group captions, and a rule with no `size=` keeps each of
them, so nothing around a heading moves. Headings are baked into an image, so the client rebuilds it and
re-renders each **visible** heading on the frame after the rule moves. Being embossed is also why a `color`
rule is inert here, exactly as on a window caption.

## `label`

The client's **body text**: everything it renders with its own hand-picked foundry. The *default* labels
belong to the `*` cascade; `label` covers the rest.

| Surface | Where you see it |
|---|---|
| Attribute rows, name and value | character sheet, Base and Study tabs |
| List items, text and icon rows | Skills & Lore, Quests, Wounds, combat maneuvers, Icon settings |
| Menu-search results | the search box results list |
| Explicit-foundry labels | credo `Level:` and `Quest:` lines, wound quality, the combat-schools counter, the login screen, a village name |

Each site keeps its own colour unless your rule sets one, and labels also keep their **wrap width**.

> **Two geometry caveats if you pass `size=`.** List and attribute **row heights** were computed from the
> stock font at construction, so taller glyphs clip; and a `Label` resizes itself to its text while its
> container does not re-lay-out around it.

Deliberately not in this key: a caller-supplied pre-rendered text, and text a widget rasterises into its own
face. Those are not body text.

## `tooltip`

Every tooltip the client pops up, and the **box** it is popped up in. The bulk of it is the client's tooltip
**engine**, which composes the tip
of an inventory item, a buff, a HUD meter, a craft recipe input or output, a minimap marker or object, a
character-sheet attribute row and an action-menu icon — hovering an inventory item is the quickest way to
see a rule take effect. On top of that:

| Surface | Where you see it |
|---|---|
| Plain string tips | rendered at *display* time, so even the tip already under your cursor re-renders |
| Widget tips | any widget's own tooltip, including its `Keyboard shortcut: …` tail |
| Resource pagina tips | the long action and item descriptions |
| Food and study tips | food event points and satiations, curiosity study times |
| Terrain, minimap, keybind help, combat action tips | the remaining engine-side tips |
| Equipment empty-slot names, skill and credo list tips | pre-rendered at construction, re-rendered on the change |
| Quality, wear, armour, gilding, attribute rows | drawn by code that ships **inside the game resources**, and reached anyway |

Markup inside a tooltip keeps working over your rule — `$b`, `$col`, `$img` — because the rule swaps the
font family and size rather than the whole font attribute, which is also why a `$col`-coloured row keeps
*its* colour under a `color` rule. Each cached tooltip image is re-composed the next time you hover it. A
tooltip sizes its box around its text, so an explicit `size=` is **safe** here, unlike a text field or a
list row.

Some rows — `Quality:`, `Wear`, `Armor class`, `Gilding`, the attribute deltas — are drawn by code the
server ships inside the game resources rather than by the client. `tooltip` reaches them anyway: while the
client composes a tooltip, that whole composition is *declared* to be tooltip text, so any text rendered
inside it follows the key, even from code that knows nothing about the font system and even when that code
picked its own font. Those rows keep their own **size**, so a small italic line stays small and italic in
your family. Nothing outside a tooltip composition is affected. One nuance: with no `size=` the point size
is preserved exactly, but ascent and descent are per-family metrics, so a family swap can still move a row
by a pixel.

> **One row needs more than that.** A resource-shipped class that rasterises text into a static field when
> the class loads cannot be restyled afterwards by any font system, because the JVM never re-runs a static
> initialiser. That is the `Gilding:` heading, so the client carries a **local copy of that resource's
> code**, version-matched, which renders the heading on demand instead. There is nothing to do on the addon
> side. The one caveat is upstream: if the game ships a new version of that resource the local copy steps
> aside, noting it in the client log, and the heading returns to stock until the copy is refreshed.

**The box is this key too**, so one rule says what a tip is set in *and* what it sits in:
[`bg`](chrome.md#bg) fills it, [`border`](chrome.md#border) outlines it — a
[line](chrome.md#border) is what the client's own outline is — and
[`padding`](chrome.md#padding) is the room between the text and that edge. The client sizes the box around the
composed text and the box grows **outward**, so padding never re-wraps a tip. Name neither `bg` nor `border`
and the client's own dark fill and yellow outline stay, at the padding you asked for. There is no cache to
wait for: the box is painted outside the one the tip's image is kept in, so a rule lands on the tip already
under your cursor.

## `menu`

Two surfaces: the **petal captions** of a flower menu — the ring of options a right-click opens — and the
**keybind letters** the action-menu grid paints over its buttons. A petal re-renders *and* re-sizes around
its own centre when the rule moves, so a menu that is already open restyles in place without drifting off
its ring, and a bigger `size=` is safe.

## `chat`

The whole chat window: every **message** line (area, party, private, system), the **channel tabs** down its
side, and the **quick line** you type over the map. Only the messages currently visible re-render, the
scrollback re-rendering as you scroll it into view, and each message's height is re-measured so the log
re-flows correctly under a bigger font. URLs stay clickable — the rule keeps chat's own link parser.

The typed quick line belongs to this key rather than to `textentry`: it lives in the chat window and is
built from the chat's own recipe. Two things are worth knowing: the channel-tab **truncation width** was
measured from the stock font once, so a much wider font can shorten a long channel name slightly early; and
chat is where a `color` rule costs the most, because **who said it** is carried by colour — one rule paints
area, party, private and system lines alike.

## `world.speech` and `world.nick`

`world.speech` is the speech bubble that pops up over a character's head when they talk in area chat, your
own included, which makes it the easiest key to check: say something and look. The bubble measures its
frame around the text every frame, so a large size is completely safe here.

`world.nick` is the floating name drawn over characters on your **kin list**, in their kin-group colour. A
font-only rule leaves that colour alone and the label re-centres itself over the character at the new size;
a `color` rule **flattens the groups**, painting every name alike. You need a kin visible on screen to
observe it. Any other world label composed by the same client mechanism follows `world.nick` too.

Neither world key follows a tooltip composition, and both fall back to the `*` cascade when unset, like
every other key.

## See also

- [keys](keys.md) — which properties each of these keys honours
- [style](README.md) — installing the sheet these keys go in
- [text](text.md) — the `font` and `color` properties themselves
- [`hafen.font`](../../font.md) — getting the handle a `font` property takes
