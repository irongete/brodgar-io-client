# 067 — Coordinate seams

## What and why

A coordinate crossing into Lua must say which space it is in. Two crossings do not, and each fails by
drawing the line or walking the character *slightly* wrong, with no error to read.

- **The projection verbs speak device pixels.** `hafen.player():worldToScreen(p)` hands
  `MapView.screenxf` straight to a `{x,y}` table, and `hafen.world():screenToWorld(sx, sy, fn)` rounds its
  arguments and submits them, both unconverted — while every other screen coordinate in the API is a
  **root design pixel**. On a scaled client a projected point lands off by the scale factor, and
  `mouse.md`'s own grab example, which feeds `ev:x(), ev:y()` into `screenToWorld`, is wrong as written.
  Feature 058 already fixed this exact crossing for the gob overlay (`UiApi.paintGobOverlays`,
  `Px.out(sc)`); these two were missed by that sweep, which is what the ROADMAP line records.
- **A raw protocol coordinate has no typed door.** A `Coord` reaches `ev:args()` as a bare `{x=,y=}`,
  identical in shape whether it is a screen pixel or a world point scaled by `OCache.posres` — a `click`
  carries one of each, adjacent. Reading the wrong one walks the character somewhere far away and raises
  nothing. `ev:args()` must stay a faithful snapshot, because `ev:resend()`/`ev:send(t)` round-trip
  through it, so the answer is a reader beside it rather than a conversion inside it.

## Acceptance criteria

1. `hafen.player():worldToScreen(p)` answers **root design pixels** — the space `hafen.ui():at()`, the
   mouse and a HUD overlay's painter already share.
2. `hafen.world():screenToWorld(sx, sy, fn)` **takes** root design pixels, so the `mouse.md` grab example
   is correct as written.
3. `screenToWorld(worldToScreen(p))` returns to the same Position within a tile, at any interface scale —
   the round trip a suite can assert without looking at the screen.
4. `ev:position(i)` answers a Position for a wire coordinate and `ev:pixel(i)` answers design pixels; both
   **raise** on an argument that is not a coordinate, each naming the other. `ev:args()` is unchanged, and
   `ev:resend()` still reaches the server with the bytes the client built.
5. A `Position` may be written back through `ev:send(t)` / `ev:rewrite(t)` and reaches the server as the
   wire form, so a destination can be rewritten without the addon knowing `posres`.
6. No addon needs `11/1024`, `hafen.ui():scale()` or a `@MapView` lookup to place or draw a world point.

## Out of scope

- **Movement events** — `PlayerMove` (an outbound walk order, cancelable) and `GobMoving` (the inbound
  `Moving` attribute, any gob). They are a new observational surface rather than a unit that is
  mis-stated, and they explain each other, so they are planned together as their own feature.
- **`hafen.vr():path()` — world-space lines, polylines and areas.** A new rendering surface with its own
  geometry and occlusion; stays the ROADMAP candidate (filed: 043). This feature adds no drawing verb.
- World-space **text** (ROADMAP, filed: 043).
- A per-message argument **schema**. The `action`/`message` key set is open because a message name is
  protocol the server owns; a table of argument meanings keyed on (sender class, message) reintroduces the
  closed catalogue and fails silently when it drifts.
- Any new **type** for a screen point. Once the seam is right a screen point has one form.

## Docs impact

Derived with:

```text
grep -rn "worldToScreen\|screenToWorld\|design pixel\|device pixel" docs/addons/
grep -rn "args()\|coordinate is" docs/addons/api/event.md
```

**Revised:** `api/player.md` (the `worldToScreen` row, and the paragraph calling it "plain pixels, relative
to the map view") · `api/world.md` (the `screenToWorld` row, and "game-window pixels, the space
worldToScreen returns") · `api/ui/mouse.md` (the grab example's feed into `screenToWorld`, correct only
after this) · `api/event.md` (the `:args()` row says raw protocol in wire units; the two new readers; that
`click` is not the map's alone, since `Avaview` and `ISBox` send their own) · `api/ui/pixels.md` (the
projection verbs join the "one space" list, which makes "nothing multiplies by it" true again) ·
`docs/client/world-3d.md` (the space `MapView.screenxf` and `Maptest` speak — read for this feature).

**Discharged:** `api/overlay.md:52,62,65` — its `sx, sy` already reaches Lua through `Px.out` (058.2) and
its claim of design pixels is already true; this feature makes its siblings agree with it, and the page
needs no edit. `api/vr/**`, `api/asset.md`, `api/menugrid.md`, `api/ui/{custom,drawing,lists,native,
controls/README,style/chrome}.md` — every hit is a *widget or image* measurement already in design pixels
through `Px`, untouched by a projection.

## Context files

- `src/io/brodgar/addon/Px.java` — 1
- `src/io/brodgar/addon/CharApi.java` — 1
- `src/io/brodgar/addon/WorldApi.java` — 1
- `src/haven/MapView.java` — 1 (`screenxf`, `Maptest`), 2 (the `click` argument order)
- `src/io/brodgar/addon/UiApi.java` — 1 (the `Px.out(sc)` precedent in `paintGobOverlays`)
- `src/io/brodgar/addon/LuaEvent.java` — 2
- `src/io/brodgar/addon/LuaMarshal.java` — 2, 3
- `src/io/brodgar/addon/LuaPosition.java` — 2, 3
- `src/io/brodgar/addon/AddonManager.java` — 2 (`xy`), 3 (`dispatchAction`)
- `src/haven/OCache.java` — 2, 3 (`posres`)
- `docs/client/world-3d.md`, `docs/client/ui-scaling.md` — 1, 4
- `docs/client/multi-session.md` — 3 (the `click` argument row: the order, and which space each of `pc`
  and `mc` is in)
- `DOCUMENTATION.md` — 4
