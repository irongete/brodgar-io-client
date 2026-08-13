# hafen.ui: bg, border and padding

The [sheet](README.md) properties that **paint** rather than write, plus the one that moves the client's
own content. Two kinds of surface wear them: `window.frame`, the client's window chrome, and `panel`, every
framed surface that is not a window.

```lua
local s = hafen.ui():sheet()
s:rule("window.frame")
  :bg{ { res = "gfx/hud/wnd/lg/bg", mode = "tile" },   -- the client's own field...
       { res = "gfx/hud/wnd/lg/bgl", at = "left", mode = "tile" } }   -- ...and a shade down one side
  :border{ box = "gfx/hud/wnd", mode = "tile" }        -- the client's own frame, by name
  :padding(8, 4, 8, 8)
s:rule("panel"):border{ image = hafen.asset():get("img/panel.png"), slice = {8, 8, 8, 8} }
s:install()
```

| Call | Value | Meaning |
|---|---|---|
| `rule:bg(t)` | one [surface](#naming-a-picture), or an **array** of them | what something is painted on: one layer, or several in paint order |
| `rule:border(t)` | `{<art>, slice = {l, t, r, b}}` **or** `{box = "gfx/hud/wnd"}`, either with a `mode` | the frame around it, cut from your own art or taken from the client's |
| `rule:padding(n)` | [design px](../pixels.md), `>= 0` | the room a surface keeps between its frame and its content, on all four sides |
| `rule:padding(l, t, r, b)` | [design px](../pixels.md), `>= 0` each | the same room, said one side at a time |

Each reads back bare: `rule:bg()`, `rule:border()`, `rule:padding()`.

A border's centre is never painted — that is `bg`'s job, so the two compose.

## Naming a picture

Wherever a picture may go, it is the same value with the same four spellings, and one of the four is the
**client's own art**. So nothing has to be extracted from the game's resources to be themed with, and a theme
of your own art built on the client's frames mixes the two freely inside one rule.

| Written | Is |
|---|---|
| `{color = {r, g, b[, a]}}` | a flat fill, alpha included. It has no picture, so it takes none of the three fields below |
| `{image = hafen.asset():get("img/panel.png")}` | a file your addon ships, as a [handle](../../asset.md) |
| `{asset = "img/panel.png"}` | the same file, named by its path. The two intern to one art and one uploaded texture |
| `{res = "gfx/hud/wnd/lg/bg"}` | one of the client's own images, named as the client names it |

A name that resolves to no resource is an error saying so, at the rule rather than at the frame.

**A shipped file's pixels are [design pixels](../pixels.md)** — authored at the weight you want to see, and
scaled up with the interface. The client's own art carries **its own scale** instead: the HUD is drawn from
art authored several times larger and resampled per interface scale, so `{res = …}` stays crisper on a
scaled-up client than a file at the same apparent weight. A theme that wants the same ships its art at that
weight.

Three fields say where a picture goes and how it fills the room left over. A colour takes none of them.

| Field | Value | Says |
|---|---|---|
| `at` | one of the nine corners — `topleft`, `top`, `topright`, `left`, `center`, `right`, `bottomleft`, `bottom`, `bottomright` | which corner the art is pinned to. With no `at` it covers the whole surface |
| `offset` | `{dx, dy}`, [design px](../pixels.md) | how far from that corner, either way |
| `mode` | `"tile"` (the default here) or `"stretch"` | what an axis the art does **not** size itself on is filled with |

**The corner decides which axes the art keeps its own size on.** `at = "left"` pins the art to the left edge
at its own width and gives it the **whole height** — a shade down one side. `at = "top"` is the mirror: its
own height, the whole width. `at = "topleft"` keeps both, so it is one piece in one corner, and `at =
"center"` keeps both and centres them. Whichever axis is left over is where `mode` applies.

## bg

A `bg` is one surface, or an **array** of them painted in order — a texture under a vignette, or the client's
own window background, which is three: a tiled field, then a shade down each side.

```lua
local s = hafen.ui():sheet()
s:rule("window.frame"):bg{ color = {26, 26, 28, 240} }        -- one flat fill
s:rule("panel"):bg{ { res = "gfx/hud/wnd/lg/bg", mode = "tile" },
                    { asset = "img/vignette.png", mode = "stretch" } }
s:install()
```

`rule:bg()` hands back what you wrote, at the arity you wrote it: one surface as one table, several as an
array in paint order. A layer array with nothing in it is an error.

- **A background is bounded by the frame that sits on it.** With a `border` in the same rule the `bg` fills
  the whole window, because your border is now the entire frame and a 9-slice is transparent between its
  slices. With no `border`, the stock frame still paints the window's margin, so the `bg` stays inside the
  content area — filling the whole window there would square off the stock chrome's shaped corners.
- **Layers are painted, not composed.** Each one is drawn over the last in the order written, and each is
  placed by its own `at` and filled by its own `mode`. There is no blend mode and no opacity beyond the
  alpha in the art itself.

## border

A frame is said one of two ways, and a rule uses one or the other.

| Written | Is |
|---|---|
| `{<art>, slice = {l, t, r, b}}` | your own art cut into a 9-slice: the four corners draw at their own size and the four edges run between them |
| `{box = "gfx/hud/wnd"}` | one of the client's own frames, named by the resource **folder** its eight pieces sit in |

The `<art>` is any of the three picture spellings [above](#naming-a-picture). A value naming both an art and
a `box` is an error, and so is a `slice` beside a `box`: a client frame's own corners *are* its insets.

**`mode` says what the four edges do between the corners.** `"stretch"`, the default, scales each edge across
the run; `"tile"` repeats it at the size it was drawn, clipping the last repeat rather than squeezing it,
which is what the client's own window decoration does. **It only shows where the edge art has something to
repeat**: a plain bar reads the same either way, which is why the client's own frames survive being stretched,
while an edge carrying a rivet, a notch or a weave needs `"tile"` or it smears one of them across the whole
run.

```lua
-- the client's own window frame on every panel in the client, shipping nothing
hafen.ui():sheet():rule("panel"):border{ box = "gfx/hud/wnd", mode = "tile" }:sheet():install()
```

- **Slice insets are in the image's own pixels, and those are [design pixels](../pixels.md)** — the same unit
  everything else your addon draws is measured in, and the unit a `{res = …}` art's slice is written in too.
  So a `slice` of `{8, 8, 8, 8}` is 8 pixels of the weight the client's own chrome is drawn at, on every
  client.
- **Why `window.frame` and not `window`.** A window's chrome is a *child* of the window rather than the
  window itself, which is the first thing anyone writing a theme trips over. So the frame is named the way
  its caption already is: `window.frame` is the sibling of `window.title`, a **site**, not a role. A
  `["window"]` [tree rule](keys.md#tree-keys) still reaches both the window's contents and its chrome.
- **A restyled window still behaves like a window.** The chrome is *replaced*, not bypassed: dragging,
  resizing, the close button, focus and the caption all keep working, and the caption is still rendered
  through the `window.title` rule.
- **A window whose chrome is its own is left alone.** A few build a decoration for a reason — an item's
  hover window, for one — and a rule never overrides that.
- **Painting chrome runs no Lua.** Your rule is parsed **once** into plain data — a colour, a texture, four
  insets — and the engine paints from that every frame. There is no per-frame callback here and no way to
  write one, which is deliberate: text has a [raster cache](../drawing.md#text-is-cached-across-frames)
  behind it and a frame does not, so chrome is redrawn every frame and a declarative property is the only
  shape that stays free.

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
  rule re-runs that. So [`padding`](#padding) is inert here, and so are your **border's own insets**: the art is
  drawn *into* the room the stock frame had, not around it. The stock boxes are about **5 design px** of edge, so
  author your image to roughly that weight; a much fatter one overlaps the panel's contents rather than
  pushing them aside. This is the opposite of `window.frame`, where the insets *are* the margins, because a
  window re-lays itself out and a panel cannot.
- **The change is deliberately small.** A border rule on a boxed panel swaps a few pixels of edge art inside
  geometry that stays put. If you want a panel to read differently, say it in the border image; there is no
  fill behind it to carry the difference.

Dropping the sheet, disabling the addon or `:reload` puts the stock chrome back on every window and every
panel.

## padding

`font`, `color`, `bg` and `border` all change what a surface *looks* like. `padding` changes where the
client's own content **sits**: it is the room between a frame and what is inside it.

```lua
local s = hafen.ui():sheet()
s:rule("window.frame"):padding(6)            -- every window keeps 6 px more around its content
s:rule("window.frame"):padding(8, 24, 8, 8)  -- ...or room for a title bar at the top alone
s:install()
```

- **One number is all four sides; four are `left, top, right, bottom`.** They are one property and one slot,
  so the later call replaces the earlier, and `rule:padding()` hands the four back as
  `{l = , t = , r = , b = }` — the shape the setter takes again, so a read round-trips into a write. Two or
  three numbers is an error naming both spellings.
- **A window grows outward; its content never shrinks.** A window is built around a *content* size and its
  chrome is fitted around that, so padding makes the window **bigger** and leaves everything inside it
  exactly where it was. It never squeezes a client window's contents into a smaller box.
- **A `border`'s own insets are the frame's margins.** Once your frame is in force, the room the *stock*
  art needed is no longer relevant: the content starts one padding inside the frame's corners, and the window
  measures exactly its content plus those corners plus the padding on each side. So a themed window is as
  tight or as roomy as its frame says, and nothing is added behind your back — including room for the
  caption. **A theme that wants a title bar says so in its own top padding or its own top inset**: the caption
  is drawn a little way down, so a shallow top edge leaves it sitting over the content.
- **Pixels are [design pixels](../pixels.md)**, like a border's slice, a `font`'s `size` and a window's own
  [`:size(w, h)`](../custom.md). A padding of `6` is 6 of the pixels the client's own margins are written in,
  so a padded window keeps the same proportions at every interface scale.
- **`:padding(0)` is the same as none**, so it alone never restyles anything.
- **Removing the rule restores the exact numbers it found** — the same size, the same position, down to the
  pixel.

**A window is the only thing `padding` moves.** Everything else ignores it: a surface that does not own its
own layout cannot honour one, so nothing is refused and nothing warns. That includes every
[panel](#panels). [The key table](keys.md#what-each-key-accepts) says which is which.

## See also

- [the pixel](../pixels.md) — the unit a padding and a slice are counted in
- [keys](keys.md#what-each-key-accepts) — which keys honour these three, and which are inert
- [surfaces](surfaces.md) — what a panel and a window frame are on screen
- [geometry](geometry.md) — the other way a rule moves something, and the only one that moves a widget
- [`hafen.asset`](../../asset.md) — loading the images a `bg` or a `border` points at
