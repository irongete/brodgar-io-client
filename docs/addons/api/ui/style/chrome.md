# hafen.ui: The Properties That Paint

The [sheet](README.md) properties that paint a surface (`bg`, `border`) and move the client's content (`padding`). Also the ones that replace a plate (`picture`) and dress a window's ornaments (`caption`, `sizer`, `closeButton`). [The key table](keys.md#what-each-key-accepts) says which surface does what with which.

```lua
local sheet = hafen.ui():sheet()
sheet:rule("window.frame")
  :bg{ { res = "gfx/hud/wnd/lg/bg", mode = "tile" },                    -- the client's own field...
       { res = "gfx/hud/wnd/lg/bgl", at = "left", mode = "tile" } }     -- ...and a shade down one side
  :border{ box = "gfx/hud/wnd", mode = "tile" }                         -- the client's own frame, by name
  :padding(8, 4, 8, 8)
  :caption{ at = "topleft", offset = {6, 3} }
sheet:rule("panel"):border{ image = hafen.asset():get("img/panel.png"), slice = {8, 8, 8, 8} }
sheet:install()
```

---

## Properties

Each setter returns the rule and reads back bare (`rule:bg()`, `rule:border()`, …). A border's centre is never painted — that is `bg`'s job — so the two compose.

| Method | Value | Permission | Description |
|---|---|---|---|
| `rule:bg(t)` | One [surface](#naming-a-picture), or an array of them, each with a [face per state](#a-face-per-state) | Unprotected | What something is painted on: one layer or several, in paint order — [bg](#bg). |
| `rule:border(t)` | `{<art>, slice = {l, t, r, b}}`, `{box = "gfx/hud/wnd"}` or `{color = {r, g, b[, a]}, width = n}` | Unprotected | The frame around it: your own 9-slice, one of the client's, or a line — [border](#border). |
| `rule:padding(n)`, `rule:padding(l, t, r, b)` | Design pixels, `>= 0` | Unprotected | The room between a frame and its content — [padding](#padding). |
| `rule:picture(t)` | One surface, with a face per state | Unprotected | The whole plate a surface is — [picture](#picture). |
| `rule:caption(t)` | `{at =, offset =}` | Unprotected | Where a window's title is measured from — [ornaments](#ornaments). |
| `rule:sizer(t)` | A surface with `at` | Unprotected | The corner grip a resizable window draws, and where. |
| `rule:closeButton(t)` | A surface with `hover`, `pressed`, `at`, `offset` | Unprotected | The window's close button: its face and its corner. |

---

## Naming a picture

Wherever a picture may go it is one value with the spellings below. One of them is the client's own art, so nothing has to be extracted from the game's resources.

| Written | Is |
|---|---|
| `{color = {r, g, b[, a]}}` | A flat fill, alpha included. Takes none of the fields below. |
| `{image = hafen.asset():get("img/panel.png")}` | A file your addon ships, as a [handle](../../asset/README.md). |
| `{asset = "img/panel.png"}` | The same file by path. The two intern to one texture. |
| `{res = "gfx/hud/wnd/lg/bg"}` | One of the client's own images, as the client names it. A name resolving to no resource raises at the rule. |

| Field | Value | Says |
|---|---|---|
| `at` | One of the nine corners: `topleft`, `top`, `topright`, `left`, `center`, `right`, `bottomleft`, `bottom`, `bottomright` | Which corner the art is pinned to. With no `at` it covers the whole surface. The corner decides which axes keep the art's own size. `left` pins at its own width across the whole height, `top` the mirror. `topleft` and `center` keep both. |
| `offset` | `{dx, dy}`, design pixels | How far from that corner. |
| `mode` | `"tile"` (default) or `"stretch"` | How an axis the art does not size itself on is filled. |

A shipped file's pixels are [design pixels](../pixels.md), scaled up with the interface. The client's own art carries its own scale, resampled per interface scale, so `{res = …}` stays crisper on a scaled-up client. A theme wanting the same ships its art at that weight.

---

## bg

One surface, or an array painted in order. A texture under a vignette, for example. The client's own window background is a tiled field then a shade down each side.

```lua
sheet:rule("window.frame"):bg{ color = {26, 26, 28, 240} }                 -- one flat fill
sheet:rule("panel"):bg{ { res = "gfx/hud/wnd/lg/bg", mode = "tile" },
                        { asset = "img/vignette.png", mode = "stretch" } }
```

| Rule | Detail |
|---|---|
| Read-back | `rule:bg()` hands back what you wrote, at the arity you wrote it. An empty array raises. |
| Bounded by the frame on it | With a `border` in the same rule the `bg` fills the whole window, your border being the entire frame. With none, the stock frame paints the margin and the `bg` stays inside the content area. |
| Painted, not composed | Each layer over the last, placed by its own `at` and filled by its own `mode`. No blend mode, no opacity beyond the art's alpha. |

### A face per state

Beside its own value a `bg` or a [`picture`](#picture) may name one for `hover`, `pressed`, `disabled` or `checked`. It is a whole value of the same shape, used while the surface is in that state. It falls back to the value it sits in.

```lua
hafen.ui():sheet():rule("button")
  :bg{ color = {70, 40, 100}, hover = { color = {130, 95, 175} }, pressed = { color = {220, 130, 40} } }
  :sheet():install()
```

A state is part of the value, never of the selector: the surface drawing itself knows its state, so no hover key exists. Which states a surface enters is [the surface's own](keys.md#what-each-key-accepts): a button three, a checkbox `checked`, a window frame none. A face nothing asks for costs nothing.

---

## border

| Written | Is |
|---|---|
| `{<art>, slice = {l, t, r, b}}` | Your own art cut into a 9-slice: corners at their own size, edges running between them. `<art>` is any of the three picture spellings. |
| `{box = "gfx/hud/wnd"}` | One of the client's own frames, named by the resource folder its eight pieces sit in. A `slice` beside a `box` raises: a client frame's corners are its insets. |
| `{color = {r, g, b[, a]}, width = n}` | A line, one colour at one thickness all round. No `slice`, no `mode`. `width` is design pixels, at least `1`, and is its inset. |

```lua
hafen.ui():sheet():rule("tooltip"):border{ color = {255, 140, 40}, width = 2 }:sheet():install()
hafen.ui():sheet():rule("panel"):border{ box = "gfx/hud/wnd", mode = "tile" }:sheet():install()
```

| Rule | Detail |
|---|---|
| `mode` | What the edges do between the corners: `"stretch"` (default) scales each edge across the run. `"tile"` repeats it at its drawn size, clipping the last repeat, as the client's own decoration does. A plain bar reads the same either way. A rivet, notch or weave needs `"tile"`. |
| `parts` | An array of pieces pinned inside the frame, each a [surface](#naming-a-picture) with a required `at`. They are painted over the frame in order. The client's window drops one at the foot of its left edge. An empty list raises. |
| Slice insets | In the image's own pixels, which are design pixels, for a `{res = …}` too. |
| `window.frame`, not `window` | The chrome is a child of the window, so the frame is a site, `window.title`'s sibling. A `["window"]` tree rule still reaches both contents and chrome. |
| A restyled window still behaves | Dragging, resizing, the close button, focus and the caption keep working. The caption still renders through `window.title`. |
| A window whose chrome is its own | An item's hover window builds a decoration for a reason. A rule never overrides it. |
| No Lua per frame | A rule is parsed once into data and painted from every frame. There is no per-frame callback. |
| Restore | Dropping the sheet, disable and `:reload` put the stock chrome back on every window and panel. |

```lua
sheet:rule("window.frame"):border{
  box = "gfx/hud/wnd", mode = "tile",
  parts = { { res = "gfx/hud/wnd/lg/lb", at = "bottomleft" },
            { asset = "img/rivet.png",   at = "topright", offset = {-4, 4} } },
}
```

---

## padding

The room between a frame and what is inside it, on a surface that owns where its content sits.

```lua
sheet:rule("window.frame"):padding(6)              -- every window keeps 6 px more around its content
sheet:rule("window.frame"):padding(8, 24, 8, 8)    -- room for a title bar at the top alone
```

| Rule | Detail |
|---|---|
| Arity | One number is all four sides. Four are `left, top, right, bottom`. Two or three raise. The later call replaces the earlier. `rule:padding()` hands back `{l=, t=, r=, b=}`, a shape the setter takes again. |
| Outward | A window is built around its content and grows outward. Nothing inside moves or shrinks. A [tooltip](surfaces.md#tooltip) grows outward too. A [text field](surfaces.md#textentry) is the other way (the room comes out of the width its caller owns). A [column](../column.md) grows outward. Everything else ignores it — every [panel](surfaces.md#panels) included — without refusal. |
| A `border`'s insets are the margins | Once your frame is in force the content starts one padding inside its corners, with no room added for the caption. A theme wanting a title bar says so in its top padding or top inset. |
| Units | Design pixels. `:padding(0)` is the same as none. Removing the rule restores the exact numbers it found. |

---

## picture

`bg` is what a surface is painted on. `picture` is what a surface is: the whole plate, for the places the client blits an image rather than framing something. Those are the [HUD's own plates](hud.md), `menu.frame`, `meter`, and every picture the server places in a window.

```lua
sheet:rule("minimap.frame"):picture{ asset = "img/mapframe.png" }   -- a plate the client blits
sheet:rule("@Img"):picture{ res = "gfx/hud/brframe" }               -- the ones the server places
```

| Rule | Detail |
|---|---|
| One surface, never a list | An array raises naming `bg`. It fills the rectangle the client drew in, by its own `mode`. The rectangle never moves. Takes a [face per state](#a-face-per-state). |
| Plates drawn over what they frame | The minimap's frame has a transparent centre. Art with no hole hides what it framed, while every click still lands. |
| Read at the draw | The server re-points a picture whenever it likes. The rule keeps applying, and `:release()` hands the client's art back. `rule:picture(t)` says what a surface is drawn as. [`widget:picture()`](../widget.md#read-methods) names what the client put there. |
| What names one | A site key for the client's own places, or a [tree key](keys.md#tree-keys) (`["@Img"]`, a chain naming the window) for the server's pictures. A tree key has no `*` above it, so no broad rule repaints every picture at once. |

---

## Ornaments

A window's decoration draws the caption, the plate it sits on, the close button and, on a resizable window, the corner sizer. All but the plate are dressed on `window.frame`. The plate is `window.title`'s surface.

```lua
sheet:rule("window.frame")
  :caption{ at = "topleft", offset = {6, 3} }
  :sizer{ res = "gfx/hud/wnd/sizer", at = "bottomright", offset = {-2, -2} }
  :closeButton{ asset = "img/close.png", hover = { asset = "img/closehover.png" }, at = "topleft", offset = {4, 4} }
sheet:rule("window.title"):bg{ color = {40, 34, 28, 230} }:border{ box = "gfx/hud/bosq" }
```

| Rule | Detail |
|---|---|
| A spot | A corner (the nine names an [`anchor`](geometry.md#anchor) uses, required) and an `offset` in design pixels, either sign, measured against the ornament's own size. `at = "topright"` puts the caption's right edge at the frame's right edge. |
| Name none | The client's own numbers stand to the pixel: the stock caption place, the stock grip corner, the stock X at the top right. |
| The sizer | Shows only where the client draws one: the map window, and a window of yours with [`:resizable(true)`](../custom.md#letting-the-user-resize-a-window-of-yours). |
| The close button | Art and place are independent: `at` alone moves the client's X, art alone re-faces it in place. The art is the button's box, resized to the picture's pixels. A flat `color` face leaves the box alone. `hover` and `pressed` ride inside the value, each falling back to the face. A themed X still closes its window and takes a click anywhere on its picture. |
| The plate | The client's box, sized around the caption (never narrower than a quarter of the window), filled by a `bg` or `border` on `window.title`. With neither, the stock title-bar art. `window.title` also carries the caption's [`font`](text.md#font). |
| Nothing here moves the contents | An ornament is painted, not laid out. A caption pushed down overlaps the content, and room for a title bar is [`padding`](#padding)'s. |

[`widget:chrome()`](../widget.md#read-methods) reads back where the ornaments went on one window, in design pixels from its outer top-left, each field present once its ornament has drawn. A window with no rule answers the client's numbers, and the plate's `styled` says whether a rule painted it.

```lua
local inventory_window = hafen.session():current():ui():match("window[title=Inventory]")
if inventory_window then hafen.log():write("caption at " .. inventory_window:chrome().caption.x) end
```

---

## See Also

- [Pixels](../pixels.md) — the unit a padding, a slice and an offset are counted in.
- [Keys](keys.md#what-each-key-accepts) — which keys honour these, and which are inert.
- [Surfaces](surfaces.md), [HUD](hud.md) — what a panel, a frame and a plate are on screen.
- [Geometry](geometry.md) — the properties that move a widget.
- [`hafen.asset`](../../asset/README.md) — the images a `bg` or `border` points at.
