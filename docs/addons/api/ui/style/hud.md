# hafen.ui: the HUD's own plates

The five surfaces the client blits **whole** rather than building a widget or a frame for: the belt across
the bottom of the screen, the two menu backgrounds in the bottom corners, the plate the action-search
button sits on, and the frame around the corner minimap. Each is a [site key](keys.md#site-keys) of its own
and each takes one property, [`picture`](chrome.md#picture) — the plate *is* what the surface is.

| Key | What it is | Where you see it |
|---|---|---|
| `hud.belt` | the long plate the ten numbered belt squares are laid on | across the bottom edge of the screen |
| `hud.menu.left` | the plate behind the map, claim and icon toggles | the bottom-left corner, over the minimap |
| `hud.menu.right` | the plate behind the inventory, equipment, character, kin and options toggles | the bottom-right corner |
| `hud.search` | the plate the action-search button sits on | the bottom-right corner, under the action grid |
| `minimap.frame` | the carved frame drawn around the corner minimap | the bottom-left corner |

```lua
local s = hafen.ui():sheet()
s:rule("hud.belt"):picture{ asset = "img/belt.png" }
s:rule("hud.menu.left"):picture{ res = "gfx/hud/lbtn-bg" }      -- the client's own, named
s:rule("hud.menu.right"):picture{ color = {26, 26, 28, 240} }
s:rule("minimap.frame"):picture{ asset = "img/mapframe.png" }
s:install()
```

- **One property reaches them.** A plate has no text on it, no frame around it and no content of its own to
  make room for, so `font`, `color`, `bg`, `border` and `padding` all land on these keys and do nothing.
  Nothing is refused and nothing warns, which is what lets one `["*"]` rule carry a property for the keys
  that *do* wear it. A plate enters no state either, so a
  [face per state](chrome.md#a-face-per-state) inside the value costs nothing and shows nothing.
- **The plate is painted first, and everything on it after.** The belt's squares and their numbers, the
  toggles, the search button: each is drawn over the plate exactly where it was. A rule changes the paint
  and never the hit test, so every button on a themed plate still takes a click where it always did.
- **The picture fills the rectangle the client already had.** These plates are sized from the client's own
  art, so art of another size is repeated into that box, or scaled into it with
  [`mode = "stretch"`](chrome.md#naming-a-picture), and the box itself never moves. Give a plate art of the
  size the client's own is if you want it to read the same.
- **`minimap.frame` is drawn *over* the map it frames.** The client's own art has a transparent centre,
  which is what makes the two read as one surround. A flat `color`, or any art with no hole in the middle,
  hides the map: nothing moves and the click still walks your character, but you see the plate alone. Give
  it art carrying the transparency the stock art had.
- **A `["*"]` rule reaches all five.** `*` is the fallback for a key you did not write, and these are the
  only sites in the client that blit a plate — so a `picture` on `*` paints the five of them alike and
  nothing else. Name the key you mean.
- **`hud.belt` is the *number* belt's plate.** The client ships two belts and Options picks between them:
  the numbered one is a row of squares on a plate, the function-key one is the squares alone with no
  background at all. So a `hud.belt` rule shows on the first and has nothing to replace on the second.
- **A folded corner takes its plates with it.** The two menu corners slide off screen when you fold them,
  and their plates are part of what slides; there is nothing to see while one is away.

Deliberately not in these keys: the frame around the **action grid** in the bottom-right corner, and every
other picture the client or the server places. Those are named by a
[tree key](keys.md#tree-keys) — `["@Img"]`, or a chain naming the window one sits in — because one picture
is not distinguishable from the next by anything but where it is in the tree. The **belt squares**
themselves are not [`inventory.slot`](surfaces.md#inventoryslot) either: they blit the same raster and keep
the client's own, exactly as the action grid's squares do.

## See also

- [chrome](chrome.md#picture) — the `picture` property itself, and the four ways a picture is named
- [keys](keys.md) — which properties each key honours, these five included
- [surfaces](surfaces.md) — the surfaces the client draws text and boxes at
- [style](README.md) — installing the sheet these keys go in
