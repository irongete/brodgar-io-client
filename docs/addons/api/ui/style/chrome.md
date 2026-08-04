# hafen.ui: bg, border and pad

The [sheet](README.md) properties that **paint** rather than write, plus the one that moves the client's
own content. Two kinds of surface wear them: `window.frame`, the client's window chrome, and `panel`, every
framed surface that is not a window.

```lua
hafen.ui.skin{
  ["window.frame"] = { bg     = { color = {26, 26, 28, 240} },
                       border = { image = hafen.asset("img/panel.png"), slice = {8, 8, 8, 8} },
                       pad    = 6 },
  ["panel"]        = { border = { image = hafen.asset("img/panel.png"), slice = {8, 8, 8, 8} } },
}
```

| Property | Value | Meaning |
|---|---|---|
| `bg` | `{color = {r,g,b,a}}` **or** `{image = hafen.asset(…)}` | the surface something is painted on: a flat fill, alpha included, or a tiled image. One or the other, never both |
| `border` | `{image = hafen.asset(…), slice = {l, t, r, b}}` | a 9-slice frame: the four corners draw at their own size and the four edges stretch between them |
| `pad` | a number of pixels, `>= 0` | the space a surface keeps between its frame and its content |

A border's centre is never painted — that is `bg`'s job, so the two compose.

## bg and border

- **Why `window.frame` and not `window`.** A window's chrome is a *child* of the window rather than the
  window itself, which is the first thing anyone writing a theme trips over. So the frame is named the way
  its caption already is: `window.frame` is the sibling of `window.title`, a **site**, not a role. A
  `["window"]` [tree rule](keys.md#tree-keys) still reaches both the window's contents and its chrome.
- **A background is bounded by the frame that sits on it.** With a `border` in the same rule the `bg` fills
  the whole window, because your border is now the entire frame and a 9-slice is transparent between its
  slices. With no `border`, the stock frame still paints the window's margin, so the `bg` stays inside the
  content area — filling the whole window there would square off the stock chrome's shaped corners.
- **A restyled window still behaves like a window.** The chrome is *replaced*, not bypassed: dragging,
  resizing, the close button, focus and the caption all keep working, and the caption is still rendered
  through the `window.title` rule.
- **Slice insets are in the image's own pixels and are not DPI-scaled**, like every other image your addon
  draws. On a scaled client an 8 px border therefore reads *thinner* than the stock chrome it replaced;
  author the image at the weight you want to see.
- **A window whose chrome is its own is left alone.** A few build a decoration for a reason — an item's
  hover window, for one — and a rule never overrides that.
- **Painting chrome runs no Lua.** Your rule is parsed **once** into plain data — a colour, an image, four
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
  rule re-runs that. So [`pad`](#pad) is inert here, and so are your **border's own insets**: the art is
  drawn *into* the room the stock frame had, not around it. The stock boxes are about **5 px** of edge, so
  author your image to roughly that weight; a much fatter one overlaps the panel's contents rather than
  pushing them aside. This is the opposite of `window.frame`, where the insets *are* the margins, because a
  window re-lays itself out and a panel cannot.
- **The change is deliberately small.** A border rule on a boxed panel swaps a few pixels of edge art inside
  geometry that stays put. If you want a panel to read differently, say it in the border image; there is no
  fill behind it to carry the difference.

Dropping the sheet, disabling the addon or `:reload` puts the stock chrome back on every window and every
panel.

## pad

`font`, `color`, `bg` and `border` all change what a surface *looks* like. `pad` changes where the client's
own content **sits**: it is the space between a frame and what is inside it.

```lua
hafen.ui.skin{ ["window.frame"] = { pad = 6 } }   -- every window keeps 6 px more around its content
```

- **A window grows outward; its content never shrinks.** A window is built around a *content* size and its
  chrome is fitted around that, so padding makes the window **bigger** and leaves everything inside it
  exactly where it was. A `pad` never squeezes a client window's contents into a smaller box.
- **A `border`'s own insets are the frame's margins.** Once your 9-slice is the frame, the room the *stock*
  art needed is no longer relevant: the content starts one `pad` inside your slice insets, and the window
  measures exactly its content plus the insets plus twice the pad. So a themed window is as tight or as
  roomy as its image says, and nothing is added behind your back — including room for the caption. **A
  theme that wants a title bar puts it in its own top inset**: the caption is drawn a little way down, so a
  shallow top inset leaves it sitting over the content.
- **Pixels are raw pixels**, like a border's slice and like a window's own [`:size(w, h)`](../custom.md).
  `pad` is not DPI-scaled. A font's `size` is, because a type size is not a coordinate.
- **`pad = 0` is the same as no `pad`**, so it alone never restyles anything.
- **Removing the rule restores the exact numbers it found** — the same size, the same position, down to the
  pixel.

**A window is the only thing a `pad` moves.** Everything else ignores it: a surface that does not own its
own layout cannot honour one, so nothing is refused and nothing warns. That includes every
[panel](#panels). [The key table](keys.md#what-each-key-accepts) says which is which.

## See also

- [keys](keys.md#what-each-key-accepts) — which keys honour these three, and which are inert
- [surfaces](surfaces.md) — what a panel and a window frame are on screen
- [geometry](geometry.md) — the other way a rule moves something, and the only one that moves a widget
- [`hafen.asset`](../../asset.md) — loading the images a `bg` or a `border` points at
