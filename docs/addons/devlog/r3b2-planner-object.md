# R3b-2 — `planner` integration for glTF models (`:planner object`)

> **Status:** ✅ Implemented; **Lua-only** (no `haven` engine change — the object render, the transform handle, the
> V2 pick/`ObjectClicked`, and gizmo-compatibility all shipped in [R3a](r3a-models-world.md)/[R3b-1](r3b1-textures-materials.md)).
> Verified: `ant hafen-client` → **BUILD SUCCESSFUL** (nothing broke — no Java touched), `ant bin` **packages
> `planner/cube.glb`**, and a **LuaJ parse** of the edited `planner/main.lua` (+ `gizmo.lua`, `hello/main.lua`) — all
> compile clean. The select/gizmo/persist flow is session-dependent, so it is verified **in-game**. **No rebuild
> needed** (Lua files reload): `ant bin` to package the addon + `:reload` (or relog). **In-game DoD pending.**
>
> **Design:** [specs/addons/17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md) (§2 the shared
> world-entity core), [16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) §8 (the `planner`
> example), decisions **D-013** (extract the shared core so ghosts/sprites/objects ride one code path) / **D-034**
> (custom non-`.res` rendering is `hafen.render.*`, safe-tier — not gated) / **D-031** (a dedicated example addon).
> Mirrors [r2b-billboard-sprite](r2b-billboard-sprite.md) (which did the same for sprites). **Completes R3b.**

R3b-2 is the last slice of **R3b**; where [R3b-1](r3b1-textures-materials.md) made glTF models *render* (textured,
multi-material, proven by `:hello object`), R3b-2 wires them into the **`planner`** editor — so a model is
**click-selectable + gizmo-driven + grid-anchor-persisted**, exactly like a ghost or a sprite. It is the object
analog of what [R2b](r2b-billboard-sprite.md) did for sprites, and — because the shared world-entity core (D-013)
already makes an object handle *identical* to a ghost/sprite handle — it is a small, **Lua-only** slice: one more
`kind` on the planner's records, one `spawn` branch, one `:planner object` command.

## Why this is Lua-only (the Java was already done)

Everything the planner needs from an object shipped in R3a/R3b-1 and needs no new engine code:

- **`hafen.render.object{model, x, y, a, scale, clickable, onClick}`** stands a glTF model in the world and returns
  the **same transform handle** a ghost/sprite exposes (`:move`/`:rotate`/`:scale`/`:alpha`/`:pos`/`:destroy`).
- **Click/pick** was generalized to objects in R3a: `findEntityIn` also scans `Addon.objects`, and a clickable
  object's mesh renders into the clickmap, so [`MapView.Click.hit`](../../../src/haven/MapView.java:2082) →
  `onGhostClick` fires the per-object `onClick` **and** the owner-scoped **`ObjectClicked`** event, consuming the
  click (client-only ⇒ still safe-tier, D-032).
- **Gizmo-compatibility** is automatic — [`gizmo.lua`](../../../addons/planner/gizmo.lua) drives any handle that
  exposes `:pos()`/`:move()`/`:rotate()`/`:scale()`, which an object does (`:hello object` proves the live transform).

So R3b-2 touches **only** `addons/planner/` — no `haven` core edit, no `io.brodgar.addon` change.

## The `planner` change — a third `kind`

The `planner` already drove ghosts **or** sprites through one kind-agnostic code path (R2b). R3b-2 adds
`kind="object"` as a peer of `"ghost"`/`"sprite"`; the live handle is `it.entity` regardless, so selection, the
gizmo, `grab`, `list`, `rotate`/`scale`, `remove`/`clear`, and `resolvePending` are all **unchanged** — they already
operate on `it.entity` kind-agnostically. The additions:

- **`spawn(it, wx, wy)`** gains an `object` branch: `hafen.render.object{ model = it.model or OBJECT_MODEL, x, y,
  a = it.a, scale = it.scale, clickable = true, onClick = fn }`. The `onClick` closes over the **record** (robust to
  list reorders), calling `selectItem(it)` — identical to the ghost/fixed-sprite branches. An object's mesh is a
  real pick surface, so a **click selects it** (no need for `:planner select`, unlike a billboard).
- **`applyLook(it)`** treats an object like a sprite: **alpha-only** highlight (selected = `1.0`, idle = `0.7`). A
  colour **tint** would recolour the model's *texture* (the same reason a PNG sprite highlights by alpha, not tint);
  the bluish/warm tint stays for `.res` ghosts, which are translucent shells designed for it.
- **`recLabel(it)`** returns `"object cube.glb"` for the list/log lines.
- **Persistence** stores `model` (the addon-relative glTF path) beside `res`/`img`; the loader accepts a
  `kind="object"` record (`(kind == "sprite") or (kind == "object") or s.res`) and restores `model` (default
  `OBJECT_MODEL`). Objects ride the **same grid-anchored** persistence as ghosts/sprites (`hafen.map.gridPos` →
  `{gridId, x, y}`, re-resolved with `hafen.map.fromGridPos` as the grid streams in), so a relog restores them —
  position, facing, and scale — at the same spot.
- **`:planner object`** places `cube.glb` at the player's feet (a 1-tile cube at `scale = 1`), auto-selects it, and
  persists. A **`ObjectClicked`** listener logs the world point (mirroring the `GhostClicked`/`SpriteClicked` ones),
  and the help/`list` text gains the `object` line.

`OBJECT_MODEL = "cube.glb"` is the planner's own model constant (the analog of `SPRITE_IMG = "icon.png"`). The addon
ships a tiny **`cube.glb`** (940 bytes — the R3a test cube: a 1×1×1 m tan cube, base at `Y=0`, so it stands 1 tile
tall at `scale = 1`). We ship the **cube**, not the maintainer's 2 MB `tank.glb`, to keep the example lightweight —
the *textured* render is already proven by `:hello object` (R3b-1); the planner's job is the **editor flow**
(select + gizmo + persist), which is kind-agnostic and identical whatever the mesh.

## Why not preload / auto-scale like `hello`

`hello` holds a `hafen.render.model` handle (loaded at `OnLoad`) to log `:bounds()`/`:info()` and scale the tank
from its bounds. The planner instead passes the **path string** `OBJECT_MODEL` to `hafen.render.object{model=…}`,
which auto-loads + **caches** the mesh on first use (D-017-sandboxed to the addon folder) — no module-level handle,
no `OnLoad` hook, and a player who never types `:planner object` never parses the model. The cube is a fixed 1-tile
size, so no bounds-based scaling is needed (the gizmo/`:planner scale` adjust it per-object afterwards).

## Files changed

- **`addons/planner/cube.glb`** (new) — the shipped glTF model (the 940-byte R3a test cube; base at `Y=0`, 1 tile
  tall). Packaged into `bin/addons/planner/` by `ant bin` (the `<fileset dir="addons"/>` copy, like `hello/tank.glb`).
- **`addons/planner/main.lua`** — `OBJECT_MODEL` constant; `spawn`/`applyLook`/`recLabel`/`persist`/loader gain the
  `object` kind; new **`:planner object`** command + **`ObjectClicked`** listener; help/`list` text; v0.6.0 banner.
- **`addons/planner/manifest.json`** — v0.6.0; description updated to include the model kind + `:planner object`.

## Try it in-game (R3b-2 DoD)

**No rebuild** — this is Lua-only (assuming your client is already built at `203a2622`, which has R3a/R3b-1). Run
**`ant bin`** to package the updated `planner` (its `main.lua` + `cube.glb`) into `bin/addons/`, then in-game
**`:reload`** (or relog).

1. **Place.** In the world, type **`:planner object`** — a **tan cube (~1 tile)** stands at your feet, and it is
   auto-selected (slightly solid vs. the faded idle look). The log reports its grid id + index.
2. **Click-select.** Place a couple more (walk a few tiles between). **Click** a cube — it selects (its `onClick`),
   and the log shows a **`:planner ObjectClicked`** line (client-only, no server click). `:planner list` shows them
   with `object cube.glb` labels.
3. **Gizmo-move.** `:planner gizmo`, then drag the **RED(X)/GREEN(Y) arrows** or **yellow centre** — the cube moves,
   the camera stays put; the **cyan ring** rotates it, the **magenta box** scales it (all snap to the placegrid/angle,
   SHIFT = fine). `:planner grab` also moves it by the body.
4. **Persistence.** With a few cubes placed (moved/rotated/scaled), **relog** — they reload at the **same grid
   position**, facing, and scale, alongside any ghosts/sprites.
5. **`:reload` leaks nothing.** With cubes up, `:reload` (or disable `planner`): every scene slot is removed and the
   cube's `Model`s + the shared `cube.glb` mesh are freed (teardown ordered objects-before-mesh) — no orphaned
   gob/GPU state.
6. **Regression.** `:planner place` (ghosts), `:planner sprite [billboard]` (sprites), and the gizmo/grab still work —
   R3b-2 added only a third branch to the shared code path.

DoD: `:planner object` **places a model, click-selects it, gizmo-moves it, relog restores it**.

## Deferred (R3c / later)

- **R3c — lighting.** `NORMAL` decode + the engine light state (models shade with the world) + sRGB baseColor +
  emissive — the last R-series slice. See [18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md) §4/§5.
- A small **model palette** for the planner (like the blueprint `PALETTE`), so `:planner object <name>` could place
  different models (today it is the single `cube.glb`, mirroring `:planner sprite`'s single `icon.png`).
- A billboard-style **pick** for models authored below the ground plane; a per-object **model swap** (`:setModel`).
