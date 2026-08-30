# 121 — a shape and its edge

## What and why

A **patch** is a convex ring drawn flat on the terrain, and today it is a fill and nothing else: the whole
ring is one colour at one opacity, and its edge is only the place where that fill stops. So the one marker a
shape on the ground is usually wanted for — a bright rim over see-through ground, a figure standing on a
plinth — cannot be said at all.

Two concentric patches will not do it either: they are two ground overlays drawn one over the other, so the
second *adds* to the first — the interior can never be less opaque than the band around it, the rim
inverted.

The edge needs no new mesh, because the number it is made of is already computed. `PatchCarve` gives every
fragment its signed distance inside the ring and smoothsteps the sign of it into the silhouette; a band near
zero is the edge, in the same expression, on the same fragment, with no second overlay, no second mesh and
no tile re-laid.

So: **a patch takes a border** — a
[stylesheet rule's](../../docs/addons/api/ui/style/chrome.md#border) own word for a line at one colour and
one thickness all the way round, its centre left to what fills it and the property left out where nothing is
drawn. It is **written** the way this API writes a line rather than the way a document says one:
`patch:border(colour, width)`, as `g:line(x1, y1, x2, y2, width)` has them.

The fill is the other half, and it is the feature and not a nicety: a border that cannot be more opaque than
the shape it surrounds is this unfinished, and a patch's fill carries no opacity of its own today.
`:alpha(a)` is the whole patch's, and the `a` of `:tint(c)` is dropped, since "blend strength" names nothing
on the one kind with no picture of its own to blend against. On a patch the tint **is** the fill, so
its `a` is the fill's own opacity, and `:alpha(a)` goes on multiplying the whole shape, border included.

## Acceptance criteria

Each is verifiable in-game through the task's own suite.

1. `patch:border(c [, w])` writes the edge and hands the patch back; `patch:border()` reads **both** back,
   the colour keyed, so `two:border(one:border())` is one expression; `patch:border(nil)` clears it and the
   read answers `nil`.
2. `width` is in **world units**. `0` is the default and means the thinnest line the screen draws — one pixel
   at every zoom, the silhouette's own. A value outside the range is brought into it, the way `:scale` and
   `:alpha` are, rather than refused.
3. A width in world units is never thinner **on screen** than that pixel, so a border does not vanish as the
   camera pulls back.
4. A border handed a stylesheet's own value — `patch:border{box = "gfx/hud/wnd"}` — is refused naming that
   out here a border is two arguments, a colour and a width.
5. `patch:info()` carries `border` as `{color, width}`, absent while none is laid.
6. The `a` of `patch:tint(c)` is the fill's own opacity; `patch:alpha(a)` multiplies the whole patch, fill and
   border alike. A patch with a translucent tint and an opaque border draws a solid line round see-through
   ground.
7. Laying, colouring, widening and clearing a border cost **no terrain work**: `overlayMeshes` and
   `overlayOutlines` stand still across all four, and so does the click test.
8. A verb a patch has not got names `border` among the ones it has.

## Out of scope

- **A border on the four kinds that stand up.** A ghost, a sprite, a model and a standing widget are gobs
  with their own materials and the engine has an `Outlines` state for them — a different mechanism, and a
  patch's border is carved out of a distance those four never compute.
- **The picture spellings of a border.** `box`, `slice` and the three picture forms frame a rectangle out of
  art, and they belong to a document rather than a call. Refusing one by name is in scope; meaning it is not.
- **A line you place.** The ROADMAP's *World-space shapes* is an arbitrary polyline; this is one ring's own
  edge and nothing else can be drawn with it.

## Docs impact

Written: `docs/addons/api/virtual/patches.md` (the border, and what the fill's own opacity is).

Derived impact set — `grep -rn "without an outline\|no outline\|adds none" docs/`:

- `docs/addons/api/client/profiling/counters.md:91` — "because a patch is drawn without an outline". The
  *number* stays right and the reason stops being: the border is carved into the sheet, not laid as a second
  mesh. Revised.
- `docs/addons/api/virtual/README.md:110` — "a patch adds none: it is the shape it was laid as". Revised,
  size-neutrally: the page is at 326 lines already.
- `docs/client/world-3d.md:44`, `:94` — the engine's own `omat()` outline mesh, a different subject.
  Discharged.

`grep -rn "blend strength\|:tint" docs/addons/` — `virtual/README.md:122` (the shared row), `ghosts.md:64`
(the ghost's own reading, which stands), `shapes.md:73`, `patches.md:21,36,99`. `chrome.md`, whose *word* this
borrows and whose value shape it does not, is untouched.

## Context files

- `src/io/brodgar/addon/PatchCarve.java` — 1
- `src/io/brodgar/addon/PatchOverlay.java` — 1
- `src/io/brodgar/addon/LuaPatch.java` — 1
- `src/io/brodgar/addon/VirtualApi.java` — 1
- `docs/addons/api/virtual/patches.md` — 1, 2
- `docs/addons/api/virtual/README.md` — 1
- `docs/addons/api/client/profiling/counters.md` — 1, 2
- `docs/addons/api/ui/style/chrome.md`, `docs/addons/api/ui/drawing.md` — 1
- `docs/addons/api/shapes.md` — 1
- `docs/addons/api/conventions.md` — 1
- `docs/client/world-3d.md` — 1
- `docs/client/render-gl.md` — 1
- `addons/session-manager/main.lua`, `addons/session-manager/README.md` — 2
