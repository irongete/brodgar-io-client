# 044 — Plan

> Caps lifted for this feature (maintainer directive). See [spec.md](spec.md) for what and why.
> **[043-vr-namespace](../043-vr-namespace/) must ship first** — this feature adds a fourth
> collection to the section 043 creates, and lands on the anchor argument 043 introduces.

## The approach, and why it is smaller than it looks

Every piece of the pipeline **already exists in this codebase**; the feature wires them together
and adds one collection. Measured before planning, not assumed:

| Step | The mechanism | Precedent already in the tree |
|---|---|---|
| 1. an offscreen colour target | `new FragColor<>(new Texture2D(sz, …).image(0))` prepped on a `Pipe` | [`Streamer`](src/haven/Streamer.java:91) and [`HeadlessClient`](src/haven/HeadlessClient.java:52) render the **whole UI** this way; also `RenderedNormals`, `ShadowMap` (depth) |
| 2. a `GOut` over it | `new GOut(Render, Pipe, Coord)` — **public constructor** ([GOut.java:57](src/haven/GOut.java:57)) | the same two loops, via `UILoop.basestate()` |
| 3. draw the widget into it | the ordinary `Widget` draw traversal | unchanged — this is what makes transparency real |
| 4. texture → world quad | `Texture2D.Sampler2D` → `TexRender` → `new Material(tr.draw, tr.clip, nofacecull).apply(model)` on the upright `quadVerts` `Model` | [world-3d.md](../codebase/world-3d.md) "World textured quad" + "Texture material (the working recipe)" |
| 5. stand it, at a point or on a gob | the shared client-only world-entity core, with 043's anchor argument | 043, and the core 012/016/038 already share |

So steps 1, 2, 4 and 5 are adaptation, not invention. **The genuinely new work is step 3's routing
(a widget tree that draws somewhere other than the screen) and input coming back.**

`Streamer`/`HeadlessClient` redirect the *entire* client by overriding `UILoop.basestate()`. We
need the per-surface version: our own `Pipe`, our own `GOut`, one widget subtree. Same
ingredients, narrower scope.

## The surface is a root — what that buys and what it costs

The spec's one architectural demand. A `WidgetSurface` is a root-like `Widget` that owns the
texture, and standing a widget **reparents** it into that surface. Focus, hover, popup placement,
tooltips and the drag gesture are then the client's own, unmodified, because they resolve against
a root rather than against the screen.

**The one thing this rests on** and the first thing 044.5 establishes: whether the client's popup
and tooltip machinery targets the *nearest* root or a fixed `ui.root`. If it is fixed, that is the
single `// addon:` seam this feature needs there — and it is a seam, not a subsystem.

## Screen point → widget point, without exposing any matrices

For input, a click at screen `(x, y)` must become widget-local `(wx, wy)`. Rather than inverting
projection/view matrices (and exposing them to Lua, which the spec rules out), **project the
quad's four corners to screen** with the same `Homo3D.obj2view` call the existing billboard path
uses (`learnings/rendering.md` R2b), then invert the resulting 2D homography to get the surface
UV. One code path for all three `:facing` modes, no new engine access, and it degenerates to a
plain rectangle test in `"screen"` mode.

## Files to create / modify

**New — `src/io/brodgar/addon/`**
- `WidgetSurface.java` — the offscreen `Texture2D` + `Pipe` + `GOut`, the root-like widget the
  target is reparented into, the dirty flag, and the upload counter.
- `SurfaceDrawable.java` — the world visual: the quad `Model` + `TexRender` `Material`, in the
  entity's `Drawable` slot. **Must be a `Drawable`, not a bare `GAttrib`** (see gotchas).
- `LuaWidgetEntity.java` — the collection member, on the same entity core as `LuaSprite`.

**Modified — `src/io/brodgar/addon/`**
- `VrApi` (043) — the fourth collection, `:widget()`. 043's tasks.md already flags keeping its
  dispatch open to exactly this.
- the facing enum (043) — `"camera"` stops raising and becomes a real world quad, on **sprites
  too**, since they share the entity core.
- the profiling read surface (019) — the new counter joins the existing tables.

**Provenance, and going back.** Standing a widget must record where it was so removal can put it
back. That record is the same shape 029/031 already keep for a hidden native widget, and D-070 is
the rule it restores under — a fourth consumer of an existing mechanism, not a new one. `replace`
composes because it answers a different question (*what stands in for the native window* vs *where
that thing is drawn*), but the pair is asserted rather than assumed.

**Modified — `haven` (`// addon:` tagged, one-liners; the exact set is 044.4/044.5's finding)**
- the world click path around [`MapView.Hittest`](src/haven/MapView.java:1962) — route a pick that
  landed on a surface before it becomes `wdgmsg("click", …)`.
- popup/tooltip root resolution — **only if** 044.5 finds it hard-wired to `ui.root`.

**Docs** — `api/vr/widgets.md` (new page in the section 043 built), `api/vr/README.md` (its
collection table gains a fourth row), `api/vr/sprites.md` (the `"camera"` mode reaches sprites),
`api/client/profiling/counters.md`, and both "API at a glance" tables.

**Addons** — the new example addon, and whatever 043 left using `:facing()` that gains a third
mode.

**Codebase coverage to pay for (`/end` writes these)** — `specs/codebase/world-3d.md` gains the
render-to-texture recipe (the `Streamer`/`HeadlessClient` pattern, currently uncovered);
`specs/codebase/widgets.md` gains root resolution / popup targeting if 044.5 reads it.

## Risks & gotchas

- **A `Drawable`-less virtual client gob self-removes every `ctick`** (`learnings/rendering.md`
  R2b GOTCHA): `Gob.ctick` ends with `if(virtual && ols.isEmpty() && getattr(Drawable.class)==null)
  glob.oc.remove(this)`. The surface visual must occupy the `Drawable` slot (`getres()==null`,
  like `SprDrawable`) or it churns the shared `OCache` every tick.
- **Use the engine's own texture recipe, not a hand-rolled one** (`learnings/rendering.md` R2):
  `TexRender.draw`/`clip` + `nofacecull`. A `ColorTex` + `FragColor.blend` stack renders a fully
  opaque texture as a ~1% ghost. Our surface wants alpha though — `clip` alpha-discards rather
  than blends, so a widget with a translucent background needs the blend variant
  (`GhostGob.alpha`'s recipe) and 044.1 must establish which.
- **One UI thread, `tick → draw → swap`.** The offscreen draw is another draw pass in the same
  frame; it must be ordered before the world pass that samples the texture, or accept being one
  frame stale. 044.1 decides and states which.
- **`Loading` escapes from any resource read** — a widget drawing an unresolved `.res` throws it
  inside our `GOut`. Swallow to a skipped upload, never let it reach Lua.
- **026's text cache still applies** — it is keyed on content, not on the target, so text drawn
  into the offscreen `GOut` hits the same cache. Worth asserting rather than assuming.
- **`ant hafen-client` is incremental and can false-green** a moved symbol; `rm -rf build/classes`
  for a true compile check.
- **`"camera"` reaching sprites is not free scope creep — it is unavoidable.** Sprites and widgets
  share the entity core, so the mode either works for both or is special-cased for one. Working
  for both is the smaller change and the better API.

## Discarded alternatives

- **A texture with clicks forwarded into it, no root.** Rejected: focus, hover, popups, tooltips
  and drag each have to be re-implemented, and each breaks differently. The spec's transparency
  rule is unachievable this way.
- **Exposing camera/projection matrices to Lua and ray-casting there.** Rejected: the projection
  already exists in Java; the corner-homography does the same job with no new public surface.
- **A sixth `gob:overlay()` kind.** Rejected by 043, which takes world-space creation out of that
  section entirely. The anchor is an argument now, so there is one door.
- **`"camera"` as a `PView.Render2D` screen blit** (what `:billboard(true)` was). Rejected:
  constant screen size and no depth is a HUD pin, not a spatial panel — and it is click-through,
  which would make the headline mode the one mode you cannot use.
- **A per-addon cap on surfaces.** Rejected by maintainer directive: no cap.
