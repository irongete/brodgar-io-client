# R2a — 2D image in the world, fixed quad (`hafen.render.sprite`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages, **40 headless
> checks** (`spriteWorldDims` aspect math + 1-tile height + degenerate/`null` → square fallback; `SpriteQuad.quadVerts`
> geometry — upright `z∈[0,h]`, `x=0` plane, `y=±w/2` width, texcoords `t` inverted so the image top is up, + the
> real `Model` builds without GL; the generalized `entityMatches` filter — nil/string/function/null-name/erroring-fn;
> the **anchor** helpers `luaOffset`/`followTargetId`/`entityWorldPos` — same-package + reflection for the private
> statics, the documented headless pattern) + LuaJ parse of `hello`/`planner`/`gizmo.lua`. The existing **ghost
> auto-demo** re-exercises the refactored shared core at every login (regression). **Java engine change ⇒ `ant`
> rebuild + a full client restart before the in-game test.** **In-game DoD pending.**
>
> **Post-verify fixes (in this slice):** (1) the fixed quad's material was corrected from the translucent-overlay
> blend recipe to the engine's own `TexRender.draw`/`clip` (alpha-**discard**) states, so it renders **solid** like
> `.res` art (an early draft read as a ~1% ghost); (2) added **anchor-to-a-gob** (`follow`/`:follow`/`:offset`), the
> world-space analog of `hafen.ui.gobOverlay`.
> **Design:** [specs/addons/17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md) (§2 the shared
> world-entity core, §5 the fixed quad), [16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) (the
> V-series core being generalized), decisions **D-034** (custom non-`.res` rendering is `hafen.render.*`, safe-tier —
> not gated) / **D-013** (extract shared) / **D-030** (bridge-owned handle).

R2a is the second slice of the **R-series**: it stands an addon's **own PNG** (the R1 `hafen.render.image` texture)
**upright in the 3D world** as a fixed textured quad — the non-`.res` sibling of a [ghost](../api/ghost.md). It is
the point where the V-series ghost core becomes a **reusable placement engine**: the transform (`:move`/`:rotate`/
`:scale`), look (`:alpha`/`:tint`), scene lifecycle, teardown, and the gizmo are all shared, and only the **visual**
differs (a `.res` `ResDrawable` for a ghost, a `TexI`-quad `SprDrawable` for a sprite). R2b (billboard) and R3 (glTF
model) slot in as two more visuals on this same core.

## The API

```lua
local icon = hafen.render.image("icon.png")           -- R1: the addon's own PNG → a TexI handle
local p    = hafen.gob.pos("player")
local s    = hafen.render.sprite{ image = icon, x = p.x, y = p.y, scale = 3 }  -- stand it upright, ~3 tiles tall
s:move(p.x + 22, p.y):rotate(math.pi/2):scale(4)      -- transform handle, gizmo-compatible (chains)
s:alpha(0.8):tint{ r=255, g=210, b=120 }              -- the ghost look states, for free
s:destroy()                                            -- or let reload/disable clean it up
```
`image=` accepts a `hafen.render.image` handle **or** an addon-relative path (auto-loaded + cached). Handle verbs:
`:move`/`:rotate`/`:scale`/`:alpha`/`:tint`/`:show`/`:hide`/`:pos` (→ `{x,y,a,scale}`)/`:image`/`:destroy`.

## The generalization — one world-entity core, pluggable visuals (D-013)

The V-series ghost was already "a client-only virtual `Gob` on the MapView scene, with a transform, look, teardown,
and the gizmo." A world sprite is the *same entity with a different visual*, so R2a factors the shared parts out
rather than duplicating them (the spec's "one real refactor", 17 §2):

- **New base [`LuaWorldEntity`](../../../src/io/brodgar/addon/LuaWorldEntity.java)** holds every field that is not
  visual-specific: the transform (`rc`/`a`), the look (`alpha`/`tint`/`scale`/`clickable`/`hidden`), the scene
  bind (`gob`/`slot`/`mv`), `dead`, `onClick`, `handle`, `owner`. Two abstract hooks: `unregister()` (drop from the
  addon's `ghosts` / `sprites` list) and `visualName()` (the filter/identity key — a `.res` name or an image path).
- **[`LuaGhost`](../../../src/io/brodgar/addon/LuaGhost.java) `extends LuaWorldEntity`** now carries only the
  `.res`-model specifics (`res`/`resName`/`sdt`/`failed` + `:setRes`'s deferred swap).
- **[`LuaSprite`](../../../src/io/brodgar/addon/LuaSprite.java) `extends LuaWorldEntity`** carries only the image
  (`img`/`imgName`).
- **The `GhostGob` is unchanged** — it is already the generic virtual-entity gob whose `obstate` preps the click
  surface + the `tint`/`alpha`/`scale` look states. A sprite is a `GhostGob` with a `SprDrawable` instead of a
  `ResDrawable`, so it inherits all of that for free (V2/V3/V6 look verbs work on a sprite with zero new code).
- **The `AddonManager` scene helpers were renamed `*Ghost` → `*Entity`** and widened to `LuaWorldEntity`:
  `destroyEntity`, `refreshEntityScene`, `setEntity{Clickable,Alpha,Tint,Scale}`, `hideEntity`, `showEntity`, plus
  the common handle verbs factored into `addEntityHandle(h, e)` and the filter into `entityMatches`. The ghost
  facade (`newGhost`/`ghostHandle`/`ghostList`/`setGhostRes`/`teardownGhosts`) keeps its `.res`-specific parts and
  calls the shared helpers. This is a **pure rename + type-widen** of verified logic — the ghost auto-demo (which
  runs the same helpers at every login) is the regression check.

## The fixed quad — `SpriteQuad` (the R2a visual)

[`SpriteQuad`](../../../src/io/brodgar/addon/SpriteQuad.java) is a resource-free `Sprite` (like `SprDrawable`'s
`getres()==null`) that stands a `TexI` upright, built **directly from the texture** — the same substrate the
engine's `.res` art runs on, skipping the container:

- **Geometry.** A 4-vertex `TRIANGLE_STRIP` [`Model`](../../../src/haven/render/Model.java) — `Homo3D.vertex` (VEC3
  world position) + `Tex2D.texc` (VEC2 texture coord), interleaved, stride 20 bytes. The quad stands in the gob's
  **local `x=0` plane**, `z` up (`0` at the feet → `h` at the top), `y` across `[-w/2, +w/2]`; the texture `t` is
  inverted (bottom verts `t=1`, top verts `t=0`) so the **image top is up**. Because the gob's `Placed` slot
  translates to the world point and rotates about the vertical by the facing, the quad renders **upright and faces
  `a`** — and the `basic` PView scene already preps `Homo3D.state` (the world-vertex transform), so a raw
  `Homo3D.vertex` model just works (it is exactly what a `.res` mesh is, minus the resource wrapper).
- **Material — the engine's own textured-surface states, `alpha-CLIP` not blend.** `new Material(tr.draw, tr.clip,
  Material.nofacecull)`, where `tr` is a `TexRender` wrapping the `TexI`'s live sampler (`tex.st().data`). `tr.draw`
  (`TexDraw`) samples the texture → multiplies into fragcol; `tr.clip` (`TexClip`) **discards** fragments whose texel
  alpha `< 0.5`. This is verbatim the `@tex`/`clip=true` matpart every `.res` textured object uses
  ([`TexRender.$tex`](../../../src/haven/TexRender.java:138)), so a sprite renders **solid** exactly like the client's
  own art — the opaque PNG parts fully opaque, the transparent background cut out. `nofacecull` makes it double-sided;
  depth is written normally (a solid occluder). Unlit — a full-bright textured panel. *(An earlier draft used
  `FragColor.blend(new BlendMode())` + `States.maskdepth` — the engine's **translucent-overlay** recipe — as the
  sprite's BASE material; that read as a ~1% ghost. Textured surfaces alpha-**clip**; only the opt-in `alpha<1` path
  blends, via `GhostGob.obstate`.)*
- **World size.** `spriteWorldDims(px)` maps the pixel size to world units **aspect-preserved**: height = one tile
  (`MCache.tilesz`, ≈ 1 tile at `scale=1`), width follows the image aspect. The uniform `:scale` (the `GhostGob`
  `obstate` `Location.scale`, V6) then adjusts both.
- **Ownership split.** The `TexI` belongs to the `LuaImage` handle (`Addon.images`), so `SpriteQuad.dispose()`
  frees only the quad's own `VertexArray` (via `Model.dispose()`) — **not** the shared texture, which
  `teardownImages` frees. One image can back several sprites and the screen-`g:image` at once.

## Synchronous create (no `Loading` to dodge)

Unlike a ghost — whose `res.get()` throws `Loading` until the resource streams in, forcing the `glob.loader.defer`
Plob dance — a sprite's `TexI` is **already decoded** (R1 decodes synchronously). So `newSprite` builds the
`GhostGob` + `SprDrawable` + `addClientGob` **immediately on the calling UI thread**; the handle's gob is live
before it is returned. The publish is still guarded by the sprite monitor (mirroring the ghost invariant), so the
shared `destroyEntity`/scene helpers see a consistent `gob`/`slot`/`mv`. `RenderTree` slot mutation is tree-locked,
so a UI-thread `addClientGob` is safe (the same seam a loader-thread ghost create uses).

Sprites are **click-through in R2a**: the `GhostGob.clickable` flag stays false, so `obstate` preps no `GobClick`
and the pick pass never returns them (a click falls straight through to whatever is behind). `:clickable` is not
exposed on the sprite handle — wiring the V2 click dispatch (`onGhostClick` searches only `Addon.ghosts`) to also
find sprites is deferred to R2b (when the gizmo wants to select them).

## Anchoring to a gob (`:follow` — the world-space `gobOverlay` analog)

A sprite/ghost can **anchor to a gob and follow it every frame**, with no per-tick Lua — the world-space sibling of
`hafen.ui.gobOverlay`. `hafen.render.sprite{follow=<gobref>, offset={x=,y=,z=}}` (or `:follow(gob[, offset])` /
`:follow(nil)` / `:offset{…}` on the handle; also on ghosts, D-013 shared).

- **Mechanism — a client `Moving` attrib.** The engine's `Gob.getc()` uses a gob's `Moving` attrib for its live
  position, and the render tree re-evaluates each client gob's placement **every frame** (`Gob.Placed.autotick` → a
  new `Placement` → `getc()`). So a new **`FollowMoving extends haven.Moving`** whose `getc()` returns
  `target.getc() + offset` makes the entity track the target automatically — the exact path the engine's own
  `Following` (a held item following a hand bone) takes, **minus** the bone machinery. It is deliberately **not** a
  `Following` subclass, so `Placed` uses the plain `getc()` path (translate to the followed point, then rotate by the
  entity's **own** `a`) — the sprite keeps its own facing/scale, independent of the target. The target id is
  re-resolved via `oc.getgob(id)` each frame, so it survives the gob unloading/reloading (falls back to hold
  position). **Zero `haven` edit** — `Moving`, `Gob.glob`/`getc`/`getrc`, `OCache.getgob`, `Coord3f.add` are all public.
- **Offset** is a world-space `Coord3f` (`x` east, `y` north, `z` up) added to the target's position; `{z=18}` floats
  it overhead. Stored `volatile` on the `FollowMoving`, so `:offset{…}` updates it live with no re-attach.
- **Attach/detach.** `:follow(gob)` `setattr`s a `FollowMoving` (autotick picks it up next frame, no re-add);
  `:follow(nil)` freezes the entity at the current followed point (reads `gob.getc()`, sets `rc`, `delattr`s). A
  plain **`:move` detaches** (drops the `FollowMoving`), so "manual move" always means "take control". All under the
  entity monitor → `synchronized(gob)` (the same lock the live res-swap uses). Anchor state lives on the shared
  `LuaWorldEntity` (`followTgt`/`followOff`), so a `follow` that lands before a ghost's deferred create streams in is
  applied at publish (`applyEntityFollow`). `:pos()` returns the **live** followed position (`entityWorldPos`) + a
  `following` = the anchored gob id.

## Zero `haven` core edit

R2a reuses the V1 `MapView.addClientGob`/`removeClientGob` seam and every render/follow primitive is public
(`Homo3D.vertex`, `Tex2D.texc`, `Model`/`VertexArray`, `Material`, `TexI.st()`, `TexRender.draw`/`clip`,
`SprDrawable`; and for the anchor: `Moving`, `Gob.glob`/`getc`/`getrc`, `OCache.getgob`, `Coord3f.add`). So all new
code is **`io.brodgar.addon`-only** — no new `// addon:` line.

## Files changed

- **`src/io/brodgar/addon/LuaWorldEntity.java`** (new) — the shared base of a client-only world entity: the
  transform/look/scene/lifecycle fields + the anchor fields (`followTgt`/`followOff`) + the `unregister()` /
  `visualName()` hooks.
- **`src/io/brodgar/addon/LuaGhost.java`** — now `extends LuaWorldEntity`; keeps only the `.res`-model specifics.
- **`src/io/brodgar/addon/LuaSprite.java`** (new) — `extends LuaWorldEntity`; the image (`img`/`imgName`).
- **`src/io/brodgar/addon/SpriteQuad.java`** (new) — the resource-free textured-quad `Sprite`: `mill(tex, w, h)`
  (the `Model` + the `TexRender.draw`/`clip` alpha-discard `Material`), `quad(w, h)`, the pure `quadVerts(w, h)`, and
  a `dispose()` that frees only the quad geometry.
- **`src/io/brodgar/addon/FollowMoving.java`** (new) — the client `Moving` that anchors an entity to a gob (`getc()`
  = `target.getc() + offset`), so the render tree auto-follows it each frame.
- **`src/io/brodgar/addon/AddonManager.java`** — the `hafen.render.sprite` facade; `newSprite` (validate → resolve
  image → build `GhostGob` + `SprDrawable` → publish), `spriteHandle`, `resolveSpriteImage`, `spriteWorldDims`,
  `teardownSprites` (wired into `teardown`, beside `teardownGhosts`); the shared-core rename (`*Ghost` → `*Entity`,
  widened to `LuaWorldEntity`) + `addEntityHandle` (incl. `:follow`/`:offset`, and `:move` detaching) / `entityMatches`;
  the anchor helpers `setEntityFollow`/`setEntityOffset`/`detachFollowAttr`/`applyEntityFollow`/`followTargetId`/
  `luaOffset`/`entityWorldPos`, wired into `newSprite`/`newGhost`; imports `haven.SprDrawable`, `haven.Coord3f`.
- **`src/io/brodgar/addon/Addon.java`** — the `sprites` owned-resource list.
- **`addons/hello/`** — v0.40.0: `:hello sprite` stands `icon.png` (the R1 handle) upright at your feet (`scale=3`),
  logs `:pos()`, then 2 s later `:move`s + `:rotate`s + `:scale`s it (the transform-handle proof); **`:hello follow`**
  anchors a sprite above your head (`follow="player", offset={z=18}`) so it FOLLOWS you; both toggle off; manifest updated.

## Try it in-game (R2a DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (a Java engine change — the JVM does not hot-reload
classes).

1. Log in (the `hello` harness loads `icon.png` at `OnLoad`). Type **`:hello sprite`** in the console: the green
   "H" icon **stands upright in the world at your feet**, ~3 tiles tall, facing you; the log reports its `:pos()`.
2. After ~2 s it **moves ~2 tiles east, rotates 90°, and grows** (the transform handle is live + chains) — the log
   line confirms.
3. **`:hello sprite`** again removes it; **`:reload`** (or disable `hello`) with a sprite up leaks nothing (the
   quad geometry is freed; the shared `icon.png` `TexI` is freed by the image teardown).
4. **Anchor:** type **`:hello follow`** — the icon **floats above your head and follows you as you walk** (a sprite
   anchored to `"player"` with `offset={z=18}`, updated every frame with no Lua polling). `:hello follow` again
   removes it.
5. **Regression:** the existing ghost auto-demo (a translucent rotated cabin a few tiles away, auto-destroyed after
   8 s) and `:hello ghost` still work — proving the `*Ghost` → `*Entity` refactor didn't disturb ghosts.

DoD: a PNG **stands fixed** in the world, upright, **solid** (opaque), the handle **transforms** it live, and it can
**anchor to a gob and follow** it; `:reload` leaks nothing.

## Deferred (R2b / later)

- **R2b — billboard** (`billboard=true`): a camera-facing screen blit (`SpeakerIcon`-style `PView.Render2D`) on the
  same entity; passing `billboard=true` today raises a guiding "arrives in R2b" error. R2b also wires the V2 click
  dispatch to sprites (so the planner gizmo can select them) and integrates a sprite into the `planner` example.
- **R3 — glTF model** (`hafen.render.model`/`object`) on the same core — see
  [18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md).
- A flat-on-ground **decal** variant (90° pitch); explicit width/height / pixels-per-unit sizing (R2a fixes height
  at one tile + aspect); `TexI` filter control; async decode for large textures.
