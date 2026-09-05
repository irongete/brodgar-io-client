# Themes

A whole client look, loaded from a JSON file instead of written in Lua.

Every value a [stylesheet](../../docs/addons/api/ui/style/README.md) rule takes has a spelling a file can
carry — a colour is a table of numbers, a face is *named* rather than handed over, a picture is named by its
resource or by a path inside the addon folder — so a theme needs no code at all. This addon is the two lines
that read one and install it, plus the bookkeeping: which files there are, which one is on, and remembering
that across a restart.

```text
addons/themes/
  manifest.json
  main.lua
  themes/
    index.json      the files in this folder, in the order the list shows them
    default.json    this client's own look, key by key and property by property
    plainframe.json + plainframe/*.png    the same look on the plain box frame, no blackletter
    hellokitty.json + hellokitty/*.png    a look with art of its own
    cyberpunk.json  + cyberpunk/*.png     ...and another
```

## Where you pick one

**Options ▸ AddOns ▸ Themes** holds one row: a dropdown with `off` and every theme `themes/index.json`
named, in its order. Picking one installs it there and then.

The row **is** the setting — it is where the theme in force is kept — and the commands below write it
rather than installing anything themselves, so the page and the console cannot disagree about what is on.
It is kept by the client, like every other setting the Options window edits, and put back on at the next
start.

| Typed | Does |
|---|---|
| `:theme` | lists what `themes/` holds, `*` beside the one installed |
| `:theme <name>` | picks it on the row — a theme is named by its **file**, minus the `.json` |
| `:theme off` | picks `off`: the client's own look back, to the pixel |

A theme dropped from the folder is no longer one of the choices, so a client that was wearing it comes back
on `off` rather than on nothing. The sheet is **owned** by this addon, so `:reload`, disabling it in the
AddOns panel and `off` are three ways to the same stock client.

`off` is the release, so a theme file cannot be called that; one that tries is skipped with a line saying so.

## Adding a theme

Drop `mytheme.json` into `themes/`, name it in `themes/index.json`, and `:reload`:

```json
{ "themes": ["default.json", "mytheme.json"] }
```

The index exists because `hafen.asset()` loads a file by path and does not enumerate a folder — an addon
reads its own files, it does not browse them. Naming them by hand also makes the order the list comes out in
yours rather than the filesystem's.

## The file

```json
{
  "name": "Default",
  "author": "brodgar",
  "description": "one line, shown by :theme",
  "rules": { "<selector>": { "<property>": "<value>" } }
}
```

`rules` is the whole of what the client reads — it is handed straight to
[`sheet:load(rules)`](../../docs/addons/api/ui/style/README.md#a-sheet-from-data), so what a key may be and
what a property may say is exactly what that page and
[keys](../../docs/addons/api/ui/style/keys.md) say, with nothing added here. Every other field — `name`,
`description`, and the two reference blocks `default.json` carries — is for the reader and for `:theme`'s own
listing.

- A key is a [selector](../../docs/addons/api/ui/selectors.md): a **site** key (`chat`, `window.frame`, `*`)
  says which kind of surface, a **tree** key (`window[title=Cupboard]`, `@Button`) says which widgets.
- Distances are [design pixels](../../docs/addons/api/ui/pixels.md) and colour components are `0..255`.
- `position`, `anchor` and `size` are the three properties only a **tree** key may carry. On a site key they
  are an error, which is why `default.json` keeps them in its `treeKeys` block instead.
- An unknown property is an error naming the ones that exist, and so is a rule saying both `position` and
  `anchor`.

## What `default.json` is

Every one of the **36 site keys** the client draws at, in the order
[keys](../../docs/addons/api/ui/style/keys.md#site-keys) lists them, and under each one **every property that
key honours** — 92 in all. 29 carry the value the client itself draws with; the other 63 are `null`.
Installing it changes nothing you can see, **at every interface scale**, which is the point: it is the sheet
you edit a theme of your own out of, and a diff against it says what your theme actually changed.

**`null` means the client sets none here — write yours in its place.** A JSON `null` parses to Lua `nil`, and
a `nil` property is one the rule does not name, so a null is invisible to the client and visible to you: the
file is a form with every field on it, and the sheet it installs is the client's own look exactly.

The values were read off the sites that draw them (`Fonts.stock(…)` in `src/haven/`), the same declarations
[`sheet:stock()`](../../docs/addons/api/ui/style/README.md#the-clients-own-look) hands back live. `stock()`
is the *running* answer and this file is the *whole* one: a catalogue only carries a surface a window has
actually opened and drawn, while a file carries the lot from the login screen.

**Two more blocks, neither of them read by the client.** `treeKeys` is the tree half — one template row with
every property a tree key carries, the three layout ones included; move it into `rules`, give it a real
selector and fill what you want. `shapes` is one line per property saying what its value looks like, so a
`null` can be filled in without leaving the file.

**A property a key *ignores* is left out rather than written `null`.** A `bg` on `chat` is accepted and
does nothing — a sheet never errors because a surface cannot use a property — and writing it here would
suggest otherwise. So `chat` carries `font` and `color`, `window.frame` carries the six that dress a window's
chrome, and the five HUD plates carry `picture` alone.
[The key table](../../docs/addons/api/ui/style/keys.md#what-each-key-accepts) is the full map of which is
which.

### No value in this file is a length

**Every distance a rule says is a design pixel, and several of the client's own are device pixels.** A
[design pixel](../../docs/addons/api/ui/pixels.md) is what an addon writes and reads back unchanged, at every
scale — but the inverse does not hold, and the client's chrome constants are device-first: an inventory
square's outline is one *device* pixel whatever the interface scale, because the raster it lives in is built
without scaling it. So `sheet:stock()` divides that 1 by the scale, gets 1 design pixel back, and a rule
multiplies it again — at **1.5×** that is 2 device pixels where the client draws 1.

That is why `default.json` carries no length and no offset at all. What it does carry is scale-free: font
families and their design sizes, colours, resource names, corners, modes and the layer stack. Five values are
`null` for this reason alone, and each is written here so a theme can take it knowingly:

| Key | `null` | The client's own | At 1.5× a rule gives |
|---|---|---|---|
| `inventory.slot` | `border` | a 1 **device** px outline, unscaled | 2 device px |
| `tooltip` | `border` | the same, `{color: {244,247,21,192}, width: 1}` | 2 device px |
| `button` | `glow` | `{color: {80,40,0,255}, radius: 0.75 × scale}` → 1 device px | 2 device px |
| `window.frame` | `caption` | `{at: "topleft", offset: 36 × 16.4 design px}` | `[36, 16]`, 1 device px high |
| `window.frame` | `sizer` | `gfx/hud/wnd/sizer` at `bottomright`, offset `-13 × -22` | 1 device px sideways |

Set any of them and it is exact at 1× and at 2×, and off by a pixel or two in between. Whether that matters
is yours to decide — a theme that draws its own frames does not care what the stock ones measured.

### Where a `null` is hiding something

Most nulls are simply the client not setting that property: it colours its own labels per call, its controls'
art is the whole picture with no separate frame, and nothing in the client asks for a `padding`. Write a
value there and you have **added** something the stock client has not got.

These are the other kind — the client draws something you can see, and this vocabulary cannot say it
**whole**. A site declares a property only where the declaration *is* what it draws; half a surface would
give you a theme that reads right and installs wrong. So writing a value here **replaces** the client's own,
and the change is bigger than the null makes it look:

| Key | The `null`s | What the client draws there instead |
|---|---|---|
| `inventory.slot`, `menu.slot` | `bg`, `border` | one raster, not a fill with a frame on it: the outline is a 1-device-px ring, its four corners are left transparent, and the fill stops *inside* the ring rather than running under it. A `bg` under a `border` composites two translucent layers where the client lays one, so every grid line goes darker — and the squares overlap by a pixel, so the shared lines double it again. The action menu's grid is paved with the very same raster, under a key of its own, so it carries the caveat with it |
| `tooltip` | `bg`, `border` | likewise one figure: the outline is drawn one device pixel **outside** the box and the fill painted over the rest of it, where a rule's border is drawn *inside* the box over the fill. So the tip's ring darkens and its box loses a pixel each way |
| `checkbox`, `checkbox.mark` | `bg` | a box is built **large or small** and the two wear different art — `gfx/hud/chkbox`/`chkmark` and `gfx/hud/chkboxs`/`chkmarks` — under one key, so no single value is both. Name one and the other kind wears it stretched to its own box |
| `window.frame` | `border`, `padding` | the frame's top-left corner **is** the caption plate and its width follows the caption, so no eight-part box reproduces it; and the frame's insets are what a window reserves its content by, so writing them re-lays every window out |
| `window.title` | `bg`, `border`, `glow` | the plate is a run whose middle tiles *between* two end caps — the shape of a frame, not of a stack of layers. The halo is a **pair**: one colour focused, another unfocused, and a rule says one |
| `panel` | `bg`, `border` | the window-less panels do not share one box — a list frame, a petal, a dropdown and an item-stock box each bring their own, and one value would repaint the surfaces it does not describe |
| `heading` | `emboss`, `glow` | a heading is carved out of one texture when it succeeded and another when it failed, and the smaller group captions blur at a radius of their own |
| `button` | `bg`, `border` | the fill is inset a fixed margin inside four end caps, which are not a nine-slice |
| `textentry` | `border`, `padding` | the two end caps are pinned left and right at their own size — no nine-slice either. The stock field keeps a margin whether or not a rule names one |
| `tooltip` | `padding` | the two pixels around a tip are kept regardless, so a padding here widens every tip by that much again |
| `scrollbar`, `slider` | `bg`, `border` | the rail is a fixed number of chain links spread evenly along the bar, not one picture tiled or stretched — neither `mode` draws it |
| `chat.system`, `chat.mine`, `chat.private`, `chat.party` | `color` | the colour of a line comes from the *line* — a notice's white, an error's dark red, the party member's own hue. Write one and you have flattened all of them, which is a theme's choice to make and not the default's |
| `world.speech` | `bg` | the white stops at the stock frame's inner edge; painting it would paint the whole bubble, its shaped corners included |

And one null is a **decision** rather than a limit: `*`'s `color`. The client's fallback colour is white and
it could be written — but a `color` on `*` flattens every colour that carried meaning, a red warning
included. Set it deliberately, never by default.

12 keys end up saying nothing at all: `panel`, `scrollbar`, `slider`, `checkbox`, `checkbox.mark`,
`inventory.slot`, `menu.slot`, `meter` and the four `chat.` kinds. `meter` is the one whose silence is a
**finding**: two shapes of bar wear that key and are made of different things, so one entry would describe
neither — [surfaces](../../docs/addons/api/ui/style/surfaces.md#meter) says so. A rule that names no property styles nothing and is skipped when
the sheet is installed, so those surfaces stay byte-for-byte the client's — the key is there because it is
real and yours to write, not because the default has anything to say through it.

## The two art themes

`hellokitty.json` and `cyberpunk.json` are the other end of the range: a look that replaces the client's chrome outright, with its
own art in a folder beside the JSON — 29 and 32 PNGs, ~60 KB each, named by path from the rules.

```text
themes/hellokitty.json         the rules
themes/hellokitty/*.png        the art they name, e.g. { "asset": "themes/hellokitty/frame.png" }
```

They are built the same way and demonstrate the same properties; the notes below are written about Hello
Kitty because it came first, and every one of them holds for Cyberpunk too.

**Its field is deep plum, not pale pink, and that is the one decision worth explaining.** The client draws
most of its own text **white**, through `*`. A pale-pink window would need `*` recoloured to something dark
to stay readable — and `*` reaches the login screen too, which is drawn on the client's own dark art, so the
theme would black out the screen you install it from. Pink went into the chrome instead, and into the
surfaces that carry their own dark-rose text: captions, buttons, fields, tips, petals. `*` keeps its colour,
so nothing the theme does not name is ever unreadable.

What it uses, and what each one is worth copying for:

| Property | Where | Worth copying for |
|---|---|---|
| `border` with `slice` | frame, panel, button, field, tip, bubble | **9-slice from your own art**: corners at their own size, edges between them |
| `border.parts` | `window.frame` | a piece **pinned** inside a frame — the bow at the foot of the left edge |
| `bg` with `mode: "tile"` | `window.frame` | a tileable wallpaper, authored 32×32 |
| `bg` with a **face per state** | `button`, `checkbox` | `hover`/`pressed` on the button, `checked` on the box |
| `picture` | the five HUD plates | a whole plate replaced — `minimap.frame`'s art keeps a **transparent centre**, so the map still shows through |
| `emboss` + `glow` | `heading` | headings **carved** out of the theme's own pink relief |
| `emboss: false` + `color` | `window.title`, `button` | the opposite: dropping the client's relief so a flat colour reaches a caption |
| `caption`, `sizer`, `closeButton` | `window.frame` | the ornaments — a bow for a close button, in three faces |
| `padding` | `window.frame`, `textentry`, `tooltip` | the room the frame keeps around the client's own content |
| `color` sequences | `chat.urgent`, `chat.speaker` | a palette to cycle, and a hue walk to mint per speaker |

**Two things it does that are worth stealing.** Its frames are **hairline** — a 1 px outer rule, a 3 px body,
a 1 px inner lip — because a rule's pixels are design pixels and a 1.5× client multiplies whatever you write:
a 14 px edge that looked right on paper lands as a 21 px band. And `window.title`'s `bg` is a **fully
transparent fill**, which is the only way to have no caption plate at all: the plate's box is the client's own
scrollwork — 77 design px tall and never narrower than a quarter of the window — so no art fits it, while
leaving the property out brings the stock brown plate back. Painting nothing there drops the caption onto the
frame's own band, where a light `glow` and a dark-rose `color` make it read.

**Every PNG is authored in design pixels and at the exact size of the hole the client already draws in** —
409×63 for the belt plate, 14×14 for a checkbox, 11×16 for the thumb both the scrollbar and the slider run,
34×34 for an inventory square, 20 tall for the text field because a field is **as tall as the background it
is given**. So nothing is stretched out of shape, and the lot scales with the interface. Where art *is*
stretched — the button faces, the two rails — it is uniform along the axis it stretches on, which is what
keeps a rounded end from smearing.

### What Cyberpunk adds

Same skeleton, opposite palette: neon rules on near-black, chamfered corners instead of rounded ones, and
the `mono` built-in everywhere text is short enough to want a terminal face — captions, buttons, the text
field, chat, tips, petals, floating names. `label` takes it too, at 11 against the client's fraktur 18, so
rows measured at construction have room to spare.

Three things it does that Hello Kitty does not:

- **Four `parts` instead of one.** A corner bracket pinned at each of `topleft`, `topright`, `bottomleft`
  and `bottomright`, which is why the frame art itself is a plain rule: a motif baked into an *edge* tile
  repeats across the whole run, and a corner bracket belongs to a corner.
- **An emboss texture that completes its ramp every 16 px.** The relief is **tiled** through the glyph mask
  rather than stretched to it, so a texture whose sweep takes the full 32 px would show a 16 px heading only
  the first half of it. Two stacked ramps means any cap height sees the whole cyan-to-magenta run.
- **A second accent colour with a job.** Amber is the tooltip's rule and its text, and the `chat.system`
  line — the terminal-warning register — while magenta stays for pressed, checked and hazard.

Both themes leave `*`'s colour alone, and for the same reason: their fields are dark, so the client's own
white text reads on them without a rule, and the login screen stays legible.

## It reaches addon UI too

The two art themes also dress **Actionbars**, and that addon knows nothing about themes. It NAMES the
surfaces it builds and DECLARES what they look like by default:

```lua
w:name("bar")
w:stock{ bg = {color = BACK}, border = {box = "gfx/hud/wnd", mode = "tile"} }
```

...and a theme names them back:

```json
"[name=actionbars/bar]":  { "bg": { "color": {"r": 9, "g": 13, "b": 22, "a": 224} }, "border": … },
"[name^=actionbars/slot]": { "bg": { "asset": "themes/cyberpunk/menu-slot.png", "mode": "stretch" } }
```

**One step is enough, and that is the point.** A `stock` sits at the *bottom* of the
[cascade](../../docs/addons/api/ui/style/README.md#the-cascade), under every rule — so a theme wins
mechanically, without chaining for extra specificity and without depending on which sheet installed first.
An addon that instead wrote a rule of its own would either sit above every theme (`widget:rule()`) or tie
with one and leave the winner to load order.

The engine writes the addon's id in front of the name, so two addons naming a `bar` cannot collide, and
`[name=…]` outranks every other refiner — it is this vocabulary's `#id`. See
[selectors](../../docs/addons/api/ui/selectors.md#the-one-refiner-an-addon-owns) and
[your own surfaces](../../docs/addons/api/ui/custom.md#naming-and-dressing-your-own-surfaces).

## Writing your own

Copy `default.json`, fill the nulls you want and change the values you want; everything you leave is what the
client already looked like. A shorter theme is just as valid — a rule the sheet does not name falls back to
the client's stock, per property, so a file holding one key and one colour is a theme.

```json
{
  "name": "Ink",
  "rules": {
    "*":              { "font": { "builtin": "sans", "size": 11 } },
    "window.title":   { "emboss": false, "color": { "r": 230, "g": 220, "b": 190 },
                        "glow": { "color": { "r": 0, "g": 0, "b": 0 }, "radius": 2 } },
    "window.frame":   { "bg": { "color": { "r": 26, "g": 26, "b": 28, "a": 240 } }, "padding": 6 },
    "tooltip":        { "bg": { "color": { "r": 20, "g": 20, "b": 20, "a": 230 } },
                        "border": { "color": { "r": 200, "g": 160, "b": 60 }, "width": 1 },
                        "padding": [6, 4, 6, 4] },
    "inventory.slot": { "bg": { "color": { "r": 30, "g": 34, "b": 30, "a": 140 } },
                        "border": { "color": { "r": 12, "g": 14, "b": 12, "a": 180 }, "width": 1 } },
    "button":         { "bg": { "color": { "r": 70, "g": 40, "b": 100 },
                                "hover": { "color": { "r": 130, "g": 95, "b": 175 } },
                                "pressed": { "color": { "r": 220, "g": 130, "b": 40 } } } },
    "window[title=Inventory]": { "anchor": { "to": "screen", "at": "bottomright",
                                             "offset": [-8, -8] } }
  }
}
```

Art of your own ships in this folder and is named by path — `{ "asset": "img/panel.png" }` for a picture,
`{ "asset": "fonts/Inter.ttf", "size": 12 }` for a face — beside the client's own, which is
`{ "res": "gfx/hud/wnd/lg/bg" }`. A name that resolves to nothing is an error at the rule, so a broken theme
says which key and which property before it paints anything: this addon gives the sheet back rather than
leaving the client wearing half a look.

## See also

- [the stylesheet](../../docs/addons/api/ui/style/README.md) — the sheet, the cascade, and `sheet:load`
- [keys](../../docs/addons/api/ui/style/keys.md) — which surfaces a key reaches, and what each honours
- [surfaces](../../docs/addons/api/ui/style/surfaces.md) — what each of these keys is on screen
- [the pixel](../../docs/addons/api/ui/pixels.md) — the unit a length in a rule is counted in
- [text](../../docs/addons/api/ui/style/text.md) · [chrome](../../docs/addons/api/ui/style/chrome.md) ·
  [geometry](../../docs/addons/api/ui/style/geometry.md) — the properties themselves
