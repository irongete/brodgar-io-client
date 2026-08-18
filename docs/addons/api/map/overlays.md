# hafen.map: overlays

Claims, village claims and provinces. They come in two halves that share only a word, and telling them
apart is most of what this page is for:

- the **recorded masks** — which tiles of a grid on disk an overlay covered when the client wrote that
  grid down. Read them off a [`Grid`](grids.md#the-grid-object).
- the **display toggles** — the switches in the client's own map menu that decide whether the overlay is
  drawn. Drive them through `hafen.map():overlay()`.

## The recorded masks

| Call | Returns | Description |
|---|---|---|
| `grid:overlay():list()` | [`Mask`](#the-mask-object)`[]` | every overlay recorded on this grid; empty until the grid and its overlay resources have loaded |
| `grid:overlay():get(tag)` | [`Mask`](#the-mask-object) \| nil | which tiles that tag covers here; `nil` for a tag this grid does not carry |
| `grid:overlay():count()` | number | how many it carries |

`grid:overlay()` is a **view** rather than a handle: it re-derives from the grid on every call and holds
nothing between them, so two calls hand back two collection objects. The `Mask` inside is what is interned —
asking the same tag twice, through either one, gives you the same object.

**The tag space is open, so an unknown tag is `nil` and not an error.** An overlay's tags are declared by
its own resource on the server, and no client-side list of them can be complete — which is exactly why
`:list()` exists: it is the census that makes a `nil` readable. (The display toggles below are the
opposite: that set is the client's own, so a tag it does not own is refused.)

```lua
local here = hafen.session():current():player():gob():position()
local g = hafen.map():grid():get(here:info().gridId)
for _, m in ipairs(g:overlay():list()) do
  print(m:tag(), m:count(), "tiles")                 -- e.g. "cplot 812 tiles"
end
```

Several overlay resources may carry the same tag — two neighbouring personal claims, say — and a mask is
the **union** of all of them, which is the same thing the client's own minimap paints for that tag.

## The Mask object

| Method | Returns | Description |
|---|---|---|
| `mask:tag()` | string | the overlay tag — half its identity; answers from the handle alone |
| `mask:grid()` | [`Grid`](grids.md#the-grid-object) | the recorded grid it belongs to — the other half |
| `mask:covers(c)` | bool \| nil | is the within-grid tile coord `{x, y}`, `0..99`, inside the overlay? |
| `mask:count()` | number \| nil | how many of the grid's 10 000 tiles it covers |
| `mask:area()` | `{x, y, w, h}` \| nil | the tight bounding box of those tiles, in within-grid tile coords |
| `mask:exists()` | bool | does that grid still carry this tag? |
| `mask:info()` | table | `{ tag, grid, count, area? }` — the snapshot escape hatch |

`mask:covers` takes the same `{x, y}` coord [`grid:tile`](grids.md#the-grid-object) does, so the two
compose over one loop. There is deliberately no call that hands you ten thousand booleans: `count` and
`area` answer the whole-mask questions without a table per tile, and `covers` answers the one you
actually ask.

```lua
local m = g:overlay():get("cplot")
local c = { x = 40, y = 40 }
if m and m:covers(c) then print("that tile was inside a personal claim", g:tile(c).name) end
```

## The display toggles

The client's map menu owns exactly these, and the province one is **two different switches**:

| Tag | Where | What it draws |
|---|---|---|
| `cplot` | world | personal claims, on the ground in the 3D world |
| `vlg` | world | village claims, on the ground |
| `prov` | world | provinces, on the ground |
| `realm` | map | provinces, on the **map window** — drawn from the recorded masks |

> **`prov` and `realm` are the same feature and two engine tags.** The world draws provinces under `prov`;
> the map window draws them under `realm`. Holding one does not touch the other, and there is no tag that
> reaches both.

| Call | Returns | Description |
|---|---|---|
| `hafen.map():overlay():get(tag)` | `OverlayToggle` | one switch; a tag the client does not own is refused |
| `hafen.map():overlay():list()` | `OverlayToggle[]` | all four |
| `toggle:tag()` | string | the engine's tag for it |
| `toggle:where()` | string | `"world"` or `"map"` — which side draws it |
| `toggle:what()` | string | a sentence naming what it draws, for a settings list of your own |
| `toggle:shown()` | bool \| nil | is it drawn right now, **by anyone**? `nil` before that side is up |
| `toggle:held()` | bool | do *you* hold it? |
| `toggle:hold()` | self | ask for it to be drawn, and keep asking |
| `toggle:release()` | self | stop asking |
| `toggle:info()` | table | `{ tag, where, what, shown?, held }` — the snapshot escape hatch |

A tag the client does not own is an **error**, not a silent no-op — the one failure here that nothing else
would ever report is a typo that quietly does nothing forever.

> **A hold is not a switch, which is why there is no `toggle(tag, on)`.** The engine counts how many
> things want an overlay drawn: your addon, the user's own checkbox, and the server (which flashes claims
> on for a few seconds when you mouse over one). So `:release()` means *stop asking*, never *turn it off* —
> if the user's checkbox is on, it stays on. Your hold is **idempotent**: taking it twice is taking it
> once, because a single release has to be the whole undo.

The hold is an owned resource like a hidden window: **a `:reload`, a disable or a logout releases it for
you**, exactly once. Nothing an addon does can leave an overlay stuck on the screen.

```lua
local claims = hafen.map():overlay():get("cplot")
claims:hold()                                        -- show me the claims while I survey
-- …later
claims:release()                                     -- and stop asking
```

`:held()` is *your* hold; `:shown()` is what the screen is doing. They are different questions, and only
the first one is yours to answer.

## See also

- [segments and grids](grids.md) — the `Grid` a mask is read off, and the coord `covers` takes
- [drawings](drawings.md) — `grid:overlayImage`, the same mask as a picture
- [the map database](README.md) — the `nil`-until-loaded rule, and interning
