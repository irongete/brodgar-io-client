# hafen.ui: The HUD's Own Plates

The surfaces the client blits whole, without a widget or a frame: each a [site key](keys.md#site-keys) of its own taking one property, [`picture`](chrome.md#picture).

```lua
local sheet = hafen.ui():sheet()
sheet:rule("hud.belt"):picture{ asset = "img/belt.png" }
sheet:rule("hud.menu.left"):picture{ res = "gfx/hud/lbtn-bg" }      -- the client's own, named
sheet:rule("hud.menu.right"):picture{ color = {26, 26, 28, 240} }
sheet:rule("minimap.frame"):picture{ asset = "img/mapframe.png" }
sheet:install()
```

| Key | What it is | Where |
|---|---|---|
| `hud.belt` | The long plate the ten numbered belt squares are laid on. | Across the bottom edge of the screen. |
| `hud.menu.left` | The plate behind the map, claim and icon toggles. | The bottom-left corner, over the minimap. |
| `hud.menu.right` | The plate behind the inventory, equipment, character, kin and options toggles. | The bottom-right corner. |
| `hud.search` | The plate the action-search button sits on. | The bottom-right corner, under the action grid. |
| `minimap.frame` | The carved frame around the corner minimap. | The bottom-left corner. |

| Rule | Detail |
|---|---|
| One property | A plate has no text, no frame and no content to make room for. `font`, `color`, `bg`, `border` and `padding` land and do nothing, without refusal. A plate enters no state, so a [face per state](chrome.md#a-face-per-state) shows nothing. |
| Painted first | The belt's squares and numbers, the toggles and the search button are drawn over the plate where they were. A rule changes the paint, never the hit test. |
| The client's rectangle | Sized from the client's own art. Other art is repeated into that box, or scaled with [`mode = "stretch"`](chrome.md#naming-a-picture). The box never moves. |
| `minimap.frame` is drawn over the map | The client's art has a transparent centre. A flat `color` or art with no hole hides the map while the click still lands. |
| `["*"]` reaches every plate | Together with `menu.frame` and `meter`, the only sites that blit a picture: a `picture` on `*` paints them all alike. Name the key you mean. |
| `hud.belt` is the number belt's | Options picks between two belts. The function-key belt has no plate, so the rule has nothing to replace there. |
| Folded corners | The two menu corners slide off screen when folded, plates included. |

| Not in these keys | Where it is |
|---|---|
| The frame around the action grid | [`menu.frame`](surfaces.md#menuslot), a key of its own. |
| The belt squares | They blit the [`inventory.slot`](surfaces.md#inventoryslot) raster and keep the client's own. |
| Every picture the client or the server places elsewhere | A [tree key](keys.md#tree-keys): `["@Img"]`, or a chain naming its window. |

---

## See Also

- [Chrome](chrome.md#picture) — the `picture` property, and the ways a picture is named.
- [Keys](keys.md) — which properties each key honours.
- [Surfaces](surfaces.md) — the surfaces the client draws text and boxes at.
- [Style](README.md) — installing the sheet.
