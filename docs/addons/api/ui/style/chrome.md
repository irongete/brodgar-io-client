# hafen.ui: the properties that paint

The [sheet](README.md) properties that **paint** rather than write, the one that moves the client's own
content, and the ones that dress a window's ornaments. The surfaces that wear them are the ones that draw a
box of their own — a window's chrome, the framed panels that are not windows, a tooltip, an inventory square,
a button, a text field — and [the key table](keys.md#what-each-key-accepts) says which does what with which.

```lua
local s = hafen.ui():sheet()
s:rule("window.frame")
  :bg{ { res = "gfx/hud/wnd/lg/bg", mode = "tile" },   -- the client's own field...
       { res = "gfx/hud/wnd/lg/bgl", at = "left", mode = "tile" } }   -- ...and a shade down one side
  :border{ box = "gfx/hud/wnd", mode = "tile" }        -- the client's own frame, by name
  :padding(8, 4, 8, 8)
  :caption{ at = "topleft", offset = {6, 3} }          -- the title, tight under the corner
s:rule("panel"):border{ image = hafen.asset():get("img/panel.png"), slice = {8, 8, 8, 8} }
s:install()
```

| Call | Value | Meaning |
|---|---|---|
| `rule:bg(t)` | one [surface](#naming-a-picture), or an **array** of them, either with a [face per state](#a-face-per-state) | what something is painted on: one layer, or several in paint order |
| `rule:border(t)` | `{<art>, slice = {l, t, r, b}}`, `{box = "gfx/hud/wnd"}` or `{color = {r, g, b[, a]}, width = n}` | the frame around it: your own art, one of the client's own, or a plain line |
| `rule:padding(n)` | [design px](../pixels.md), `>= 0` | the room a surface keeps between its frame and its content, on all four sides |
| `rule:padding(l, t, r, b)` | [design px](../pixels.md), `>= 0` each | the same room, said one side at a time |
| `rule:caption(t)` | `{at =, offset =}` | which corner of a window's frame its title is measured from, and how far — see [ornaments](#ornaments) |
| `rule:sizer(t)` | a [surface](#naming-a-picture) with an `at` | the corner grip a resizable window draws, and where |
| `rule:close(t)` | a [surface](#naming-a-picture) with `hover`, `pressed`, `at` and `offset` | the button that closes a window: what it looks like, and which corner it sits in |

Each reads back bare: `rule:bg()`, `rule:border()`, `rule:padding()`, `rule:caption()`, `rule:sizer()`,
`rule:close()`.

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

### A face per state

Beside its own value a `bg` may name one for a **state** — `hover`, `pressed`, `disabled`, `checked` — each
a whole background of the same shape, worn while the surface is in that state and falling back to the value
it sits in where the rule names none.

```lua
hafen.ui():sheet():rule("button")
  :bg{ color = {70, 40, 100}, hover = { color = {130, 95, 175} },
       pressed = { color = {220, 130, 40} } }:sheet():install()
```

A state rides **inside the value, never in the selector**: the surface drawing itself already knows which
state it is in, so there is no hover key and nothing publishes a state to the cascade. A face is an ordinary
background — an array of layers if you like — and carries no state of its own. **Which states a surface
enters is [the surface's own](keys.md#what-each-key-accepts)**: a button has three, a window frame none, and
a face nothing ever asks for costs nothing.

## border

A frame is said one of three ways, and a rule uses one of them.

| Written | Is |
|---|---|
| `{<art>, slice = {l, t, r, b}}` | your own art cut into a 9-slice: the four corners draw at their own size and the four edges run between them |
| `{box = "gfx/hud/wnd"}` | one of the client's own frames, named by the resource **folder** its eight pieces sit in |
| `{color = {r, g, b[, a]}, width = n}` | a **line**: one colour at one thickness, all the way round |

The `<art>` is any of the three picture spellings [above](#naming-a-picture). A value naming two of the three
is an error, and so is a `slice` beside a `box`: a client frame's own corners *are* its insets.

**A line is a frame with no picture behind it**, which is what several of the client's own boxes are — the
tooltip's outline is a colour and a rectangle, drawn in code rather than loaded from a resource. So it takes
neither of the two fields that only mean something to a picture: no `slice`, because there is no art to cut,
and no `mode`, because there is no edge art to repeat. Its `width` is [design px](../pixels.md) and at least
`1`; a frame nobody can see is said by leaving the property out. A line's width **is** its inset, exactly as a
9-slice's corners are, so a window framed by one reserves the room the line paints.

```lua
hafen.ui():sheet():rule("tooltip"):border{ color = {255, 140, 40}, width = 2 }:sheet():install()
```

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

### Pieces pinned inside a frame

A frame is corners and runs, and some frames carry a piece that is neither: the client's own window drops
one at the foot of its left edge. `parts` is an array of those — each an ordinary
[surface](#naming-a-picture) with an `at` saying which of the nine corners it is pinned to, painted **over**
the frame in the order written.

```lua
hafen.ui():sheet():rule("window.frame"):border{
  box = "gfx/hud/wnd", mode = "tile",
  parts = { { res = "gfx/hud/wnd/lg/lb", at = "bottomleft" },
            { asset = "img/rivet.png",   at = "topright", offset = {-4, 4} } },
}:sheet():install()
```

`at` is required on a part: being pinned is what makes it one, and a picture that covers the whole surface
is a [`bg`](#bg) layer instead. A `parts` list with nothing in it is an error rather than an empty frame.
`rule:border()` hands the list back in paint order, each entry in the shape it was written.

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

A frame lands on a window's decoration and on every window-**less** [panel](surfaces.md#panels) alike, and
what each does with one — which of the two properties reaches it, and why a panel's insets cannot move
anything — is on that page, beside what those surfaces are.

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

**`padding` moves a surface that owns where its own content sits, and nothing else.** A window and a
[tooltip](surfaces.md#tooltip) both grow **outward**: each is built around its content, so the box widens
and what is inside stays where it was. A [text field](surfaces.md#textentry) is the third and the other
way round — its width is whoever built it's, so the room comes out of that width and the text moves in.
Everything else ignores it: a surface whose layout was decided when it was built cannot honour one, so
nothing is refused and nothing warns. That includes every [panel](surfaces.md#panels).
[The key table](keys.md#what-each-key-accepts) says which is which.

## Ornaments

A window's decoration draws more than the frame: its **caption**, the **plate** the caption sits on, the
**close** button, and — on a window the user may resize — the corner **sizer**. All but the plate are dressed
by a rule on `window.frame`; the plate is `window.title`'s own surface, because the caption is what it
belongs to.

```lua
local s = hafen.ui():sheet()
s:rule("window.frame")
  :caption{ at = "topleft", offset = {6, 3} }
  :sizer{ res = "gfx/hud/wnd/sizer", at = "bottomright", offset = {-2, -2} }
  :close{ asset = "img/close.png", hover = { asset = "img/closehover.png" },
          at = "topleft", offset = {4, 4} }
s:rule("window.title"):bg{ color = {40, 34, 28, 230} }:border{ box = "gfx/hud/bosq" }
s:install()
```

**A spot is a corner and an offset** — the same nine names an [`anchor`](geometry.md#anchor) picks and a
picture's `at` pins to. `at` is required, because a spot with no corner is not a place; `offset` is
[design px](../pixels.md), either sign, from that corner. The corner is measured against the ornament's
**own size**, so `at = "topright"` puts the caption's right edge at the frame's right edge rather than its
origin there. A `sizer` is an ordinary [surface](#naming-a-picture), so its art and its place are one value.

- **Name none and the client's own numbers stand, to the pixel.** With no `caption` the title is drawn where
  the stock client draws it; with no `sizer` the client's own grip sits in the client's own corner; with no
  `close` the client's own button sits at the top right.
- **The sizer shows only where the client draws one.** Most windows are not resizable; the map is.
- **The close button's art and its place are independent, and either alone is a rule.** `close{at = …}` moves
  the client's own X; an art with no `at` re-faces it where the client puts it. **The art is the button's
  box** — it is resized to the picture's own pixels, so nothing is squeezed or stretched, and a face that is
  a flat `color` leaves the client's own box alone, having no size of its own to give.
- **The button's states ride inside its value**, the same way a [`bg`](#a-face-per-state) carries a face per
  state: `hover` and `pressed` are ordinary [surfaces](#naming-a-picture) of the same shape as the face they
  vary, and each falls back to that face.
- **A themed X still closes its window**, and still takes a click anywhere on its picture. What a rule
  replaces is the button's face and its corner, never what pressing it does.
- **The plate is the client's box and your art.** The client sizes it around the caption — a longer title
  makes a wider plate, and it never narrows past a quarter of the window — and a `bg` or a `border` on
  `window.title` says what fills that box. With neither, the window's own title-bar art is the plate, as it
  always is.
- **`window.title` carries the caption's `font` as well.** One key for the caption and for the surface it
  sits on: [`font`](text.md#font) writes the letters, `bg` and `border` write what is behind them.
- **Nothing here moves the window's contents.** An ornament is painted, not laid out, so a caption pushed
  down over the content area overlaps it — the room for a title bar is [`padding`](#padding)'s to make.

[`widget:chrome()`](../widget.md#read) reads back where the ornaments actually **went** on one window —
a different question from what a rule asked for, since a window with no rule answers the client's own
numbers. Coordinates are [design px](../pixels.md) from that window's **outer** top-left, the corner its
frame is drawn from, and each field appears only once its ornament has been drawn at least once. The
plate's `styled` says whether the last frame painted it from a rule or from the client's own art; its box
is the client's either way.

```lua
local w = hafen.ui():find("window[title=Inventory]")
if w then hafen.log():write("caption at " .. w:chrome().caption.x) end
```

## See also

- [the pixel](../pixels.md) — the unit a padding, a slice and an offset are counted in
- [keys](keys.md#what-each-key-accepts) — which keys honour these, and which are inert
- [surfaces](surfaces.md) — what a panel and a window frame are on screen
- [geometry](geometry.md) — the other way a rule moves something, and the only one that moves a widget
- [`hafen.asset`](../../asset.md) — loading the images a `bg` or a `border` points at
