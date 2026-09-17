# hafen.map: Masks and Display Switches

Claims, village claims and provinces, as two things. The recorded masks: which tiles of a grid on disk an overlay covered when the client recorded the grid, read off a [`Grid`](grids.md#the-grid-object) with `grid:mask()`. The display switches: the client's map-menu switches deciding whether the overlay is drawn, driven through `hafen.map():display()`.

```lua
local here = hafen.session():current():player():gob():position()
local grid = hafen.map():grid():get(here:info().gridId)
for _, mask in ipairs(grid:mask():list()) do
  hafen.log():write(mask:tag() .. " " .. mask:count() .. " tiles")     -- e.g. "cplot 812 tiles"
end
```

---

## The recorded masks

| Method | Returns | Permission | Description |
|---|---|---|---|
| `grid:mask():list()` | [`Mask`](#the-mask-object)`[]` | Unprotected | Every mask recorded on this grid. Empty until the grid and its overlay resources have loaded. |
| `grid:mask():get(tag)` | [`Mask`](#the-mask-object) `\| nil` | Unprotected | Which tiles that tag covers here. `nil` for a tag this grid does not carry. |
| `grid:mask():count()` | `number` | Unprotected | How many it carries. |

| Rule | Detail |
|---|---|
| A view, not a handle | `grid:mask()` re-derives from the grid on every call and holds nothing between them, so two calls are two collection objects. The `Mask` inside is interned. |
| The tag space is open | An overlay's tags are declared by its resource on the server, so an unknown tag is `nil`, not an error. `:list()` is the census that makes a `nil` readable. |
| A union | Several overlay resources may carry one tag (two neighbouring personal claims). A mask is the union of all of them, what the client's minimap paints for that tag. |

## The Mask object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `mask:tag()` | `string` | Unprotected | The overlay tag, half its identity. Answers from the handle alone. |
| `mask:grid()` | [`Grid`](grids.md#the-grid-object) | Unprotected | The recorded grid it belongs to, the other half. |
| `mask:covers(cell)` | `boolean \| nil` | Unprotected | Whether within-grid tile coordinate `{x, y}`, `0..99`, is inside the overlay. The coordinate [`grid:tile`](grids.md#the-grid-object) takes. |
| `mask:count()` | `number \| nil` | Unprotected | How many of the grid's 10 000 tiles it covers. |
| `mask:area()` | `{x, y, w, h} \| nil` | Unprotected | The tight bounding box of those tiles, in within-grid tile coordinates. |
| `mask:exists()` | `boolean` | Unprotected | Whether that grid still carries this tag. |
| `mask:info()` | `table` | Unprotected | `{ tag, grid, count, area? }`. |

There is no call handing back ten thousand booleans: `count` and `area` answer the whole-mask questions, `covers` the one you ask.

```lua
local claim = grid:mask():get("cplot")
local cell = { x = 40, y = 40 }
if claim and claim:covers(cell) then hafen.log():write("inside a personal claim: " .. grid:tile(cell).name) end
```

## The display switches

| Tag | Where | Draws |
|---|---|---|
| `cplot` | world | Personal claims, on the ground in the 3D world. |
| `vlg` | world | Village claims, on the ground. |
| `prov` | world | Provinces, on the ground. |
| `realm` | map | Provinces, on the map window, drawn from the recorded masks. |

`prov` and `realm` are one feature and two client tags: holding one does not touch the other, and no tag reaches both.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.map():display():get(tag)` | `OverlayToggle` | Unprotected | One switch. A tag the client does not own raises. |
| `hafen.map():display():list()` | `OverlayToggle[]` | Unprotected | Every switch. |
| `toggle:tag()` | `string` | Unprotected | The client's tag for it. |
| `toggle:where()` | `string` | Unprotected | `"world"` or `"map"`: which side draws it. |
| `toggle:what()` | `string` | Unprotected | A sentence naming what it draws, for a settings list of your own. |
| `toggle:shown()` | `boolean \| nil` | Unprotected | Whether it is drawn right now, by anyone. `nil` before that side is up. |
| `toggle:held()` | `boolean` | Unprotected | Whether you hold it. |
| `toggle:hold()` | the toggle | Unprotected | Ask for it to be drawn, and keep asking. |
| `toggle:release()` | the toggle | Unprotected | Stop asking. |
| `toggle:info()` | `table` | Unprotected | `{ tag, where, what, shown?, held }`. |

```lua
local claims = hafen.map():display():get("cplot")
claims:hold()                                        -- show me the claims while I survey
claims:release()                                     -- and stop asking
```

| Rule | Detail |
|---|---|
| A hold is not a switch | The client counts how many things want an overlay drawn: your addon, the user's checkbox, the server flashing claims on mouse-over. `:release()` means stop asking, never turn off. The user's checkbox stays on. There is no `toggle(tag, on)`. A hold is idempotent: taking it twice is taking it once, and one release is the whole undo. |
| An owned resource | A `:reload`, a disable or a logout releases it for you, once. Nothing an addon does leaves an overlay stuck on. |
| The side must be up | `:hold()` on a tag whose side is not there takes nothing and says nothing. That is the world before a character has entered it, or the map window before it is opened. `:held()` reads `false`. A hold from `Load` never happens. Take it from [`SessionEnteredWorld`](../event/bus/lifecycle.md#sessions), and check `:held()` where the side is one the user opens. |
| Two questions | `:held()` is your hold. `:shown()` is what the screen is doing. |

---

## See Also

- [Segments and grids](grids.md) — the `Grid` a mask is read off, and the coordinate `covers` takes.
- [Drawings](drawings.md) — `grid:overlayImage`, the same mask as a picture.
- [The map database](README.md) — the `nil`-until-loaded rule, and interning.
