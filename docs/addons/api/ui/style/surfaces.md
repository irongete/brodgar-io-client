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

## `window.frame` and `panel`

Neither draws text. They take `bg`, `border` and — for a window only — `padding`, and both are described in
full under [chrome](chrome.md).

## `button`

The captions of the client's standard buttons: the Options window, the character-sheet, craft and build
buttons, tab buttons, key-bind buttons, `wrapped` multi-line buttons, and any caption a button changes at
runtime. Buttons rasterise their caption into an image, so each **visible** button re-renders on the frame
after the rule moves — open a window with buttons while toggling and you see it live.

The stock caption font is **bold serif 12**, so overriding `button` with a serif handle at size 12 installs
correctly and looks like nothing happened. Pick a contrasting family when you want to see the change.

`color` reaches only part of this key: the ordinary caption is embossed, so a colour rule is inert on it,
while a `wrapped` multi-line caption and any caption the client sets *with* a colour of its own do follow
it, both rendering through the plain foundry.

Two surfaces are deliberately not in this key: a button whose face the client supplied as a ready-made
image or pre-rendered text (icon buttons, the character-selection list entries), and button-shaped widgets
that are not buttons at all (checkboxes, radio labels). Those are not button captions and keep their own
foundry.

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

Every tooltip the client pops up. The bulk of it is the client's tooltip **engine**, which composes the tip
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
