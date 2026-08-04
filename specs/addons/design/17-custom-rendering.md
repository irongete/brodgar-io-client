# Custom Rendering — `hafen.render.*` (non-`.res` images & models)

> **Status:** 🟡 Draft · **Spec:** AddOns · **Series:** R (Render)
> **Related:** [18-custom-models-gltf.md](18-custom-models-gltf.md) (the 3D-model deep-dive),
> [16-virtual-entities.md](16-virtual-entities.md) (the shared world-entity core), [07-ui-and-drawing.md](07-ui-and-drawing.md),
> [02-filesystem-and-build.md](02-filesystem-and-build.md), [DECISIONS.md](../DECISIONS.md) (D-034, D-035 — **ratified 2026-07-26**),
> [../../codebase-map.md](../../codebase-map.md) (R-series seams), [the API reference](../../../docs/addons/api/README.md),
> [24-gob-overlays.md](24-gob-overlays.md) — which **supersedes the anchoring half**: `follow = gob` and the
> handles' `:follow`/`:offset` are hard cut, and a sprite or model ON a gob is now `gob:overlay(key, spec)`.
> `hafen.render`'s own surface (a fixed image or model in the world) is untouched.

A namespace for rendering **custom assets that are NOT engine `.res`** — PNG images (on screen and in the world)
and custom 3D models (**glTF**). `hafen.ghost` stays exactly as it is (place `.res` **game models** in the world);
this is its non-`.res` sibling. Maintainer direction (2026-07-26): images 2D en pantalla y en el mundo
(billboard + fijas), y modelos 3D que no sean `.res` (glTF), todo bajo `hafen.render` ([D-034](../decisions/rendering.md)).

## 0. The key insight — `.res` is already PNG under the hood

A `.res` file is a **container**, not an image format. Its image layers are **PNG bytes** the client decodes with
`ImageIO.read` and wraps in a [`TexI`](src/haven/TexI.java:52) ([`Resource.readimage`](src/haven/Resource.java:1061)
→ [`Resource.Image`](src/haven/Resource.java:1182) `new TexI(img)`). So "render a PNG" is the substrate the engine
already runs on; `hafen.render` exposes it directly (`PNG → BufferedImage → TexI`) and skips the `.res` wrapper.
The engine already blits bare `TexI`s with zero resource involvement — e.g.
[`SpeakerIcon.GLYPH`](src/haven/SpeakerIcon.java:51). The 3D side is the same idea one level up: build the engine's
own [`Model`](src/haven/render/Model.java:45)/[`Material`](src/haven/Material.java:143) primitives from **glTF** data
instead of from a `.res`.

## 1. The five capabilities & the namespace

| # | Capability | Lua | Surface | Engine |
|---|---|---|---|---|
| 0 | `.res` **game model** in world | `hafen.ghost.*` | 3D world | `ResDrawable` — **unchanged, exists** (V1–V6) |
| 1 | **2D image on screen** | `hafen.render.image` + `g:image` | HUD/widget/overlay | `GOut.image(Tex,…)` |
| 2 | **2D image in world — billboard** | `hafen.render.sprite{…,billboard=true}` | 3D world (faces cam) | `SpeakerIcon`-style `PView.Render2D` blit |
| 3 | **2D image in world — fixed** | `hafen.render.sprite{…,billboard=false}` | 3D world (fixed quad) | `TexI`-quad `Model` + `SprDrawable` |
| 4 | **custom 3D model in world** | `hafen.render.object{model=…}` | 3D world | **glTF** → `Model`+`Material` + `SprDrawable` ([18](18-custom-models-gltf.md)) |

**Namespace ([D-034](../decisions/rendering.md)):** everything non-`.res` lives under **`hafen.render.*`**; `hafen.ghost.*` is
reserved for `.res` game models. One home, split from ghost, so "custom asset" vs "game model" is never ambiguous.

```lua
-- assets (load once → cached handle)
local img = hafen.render.image("icons/sign.png")     -- PNG → TexI       (feeds 1,2,3)
local mdl = hafen.render.model("props/chair.glb")    -- glTF → mesh(es)  (feeds 4; see doc 18)

-- 1) screen 2D  (inside any draw callback)
onDraw = function(g,w,h) g:image(img, 8, 8) end

-- 2/3) 2D image in the world  → a transform handle (gizmo-compatible)
local s = hafen.render.sprite{ image = img, x=wx, y=wy, billboard=true }     -- faces camera
local p = hafen.render.sprite{ image = "poster.png", x=wx, y=wy, a=0 }        -- fixed quad (billboard=false default)

-- 4) custom 3D model in the world → a transform handle (gizmo-compatible)
local o = hafen.render.object{ model = mdl, x=wx, y=wy, a=0, scale=1 }

for _,h in ipairs{s,p,o} do h:move(x,y); h:rotate(a); h:scale(k); h:hide(); h:show(); h:destroy() end
```

## 2. Architecture — one world-entity core, pluggable visuals

`hafen.ghost` (V-series) already is "a **client-only virtual `Gob`** placed on the MapView scene, with a
**transform** (`:move`/`:rotate`/`:scale`), **look** (`:tint`/`:alpha`), teardown, and the **gizmo**." A world
image or a glTF model is the *same entity with a different visual*. So the design **generalizes the V-series core**
([16-virtual-entities.md](16-virtual-entities.md)) into a reusable placement engine with a **pluggable `Drawable`
visual**, and both namespaces build on it:

```
                 shared virtual-entity core  (generalize LuaGhost/GhostGob + MapView.addClientGob + Placed + gizmo)
                 ─ transform (move/rotate/scale) · tint/alpha · scene add/tick · teardown · gizmo ─
                          │ visual = a Gob Drawable attr (swappable)                      ▲
        ┌─────────────────┼───────────────────────────┬───────────────────────────────┐ │ all handles are
   hafen.ghost      hafen.render.sprite (fixed)   hafen.render.sprite (billboard)  hafen.render.object
   ResDrawable      TexI-quad + SprDrawable        PView.Render2D screen blit       glTF Model(s) + SprDrawable
   (.res model)     (world quad)                   (faces camera, screen-space)     (custom 3D mesh)
```

- **Fixed sprite / object** are true world geometry (`SprDrawable`, a **resource-free** `Drawable` —
  [`getres()==null`](src/haven/SprDrawable.java:63)), so they get the full transform + gizmo + `tint`/`alpha` for
  free (all key off the `Gob` and its `Drawable` slot, not off `ResDrawable`).
- **Billboard sprite** is the odd one: a screen-space `PView.Render2D` blit anchored at a projected world point
  (always faces camera, sized in screen px). Position/gizmo-move apply; world-rotate/world-scale do not (it's 2D).
- The **gizmo** (`planner/gizmo.lua`, D-031) operates on any transform handle → works on all of them unchanged.

This is an internal refactor of the V-series, **not** a change to `hafen.ghost`'s public surface.

## 3. Asset loaders (`hafen.render.image` / `hafen.render.model`)

```lua
local img = hafen.render.image("icons/heart.png")    -- → image handle; img:size()→{w,h}; img:dispose()
local mdl = hafen.render.model("props/chair.glb")     -- → model handle; mdl:bounds(); mdl:dispose()  (see doc 18)
```

- **Source & sandbox** — paths resolve under the addon's own folder
  ([`Addon.dir.resolve(name)`](src/io/brodgar/addon/Addon.java:173), the `main.lua` root). **Absolute paths and
  `..` escape are rejected** ([D-017](../decisions/security-sandbox.md)); an addon reads only its own assets. No shadowing of client
  `.res`; no cross-addon reads.
- **Image decode** — `ImageIO.read` → `BufferedImage` → [`new TexI(img)`](src/haven/TexI.java:52). GPU upload is
  lazy/thread-safe on first render ([`TexI.st()`](src/haven/TexI.java:59)). **PNG (alpha) is canonical**; JPG/GIF/BMP
  work via `ImageIO` but are not the recommended form.
- **Model decode** — glTF (`.glb`/`.gltf`) parsed to engine `Model`+`Material`; the full pipeline is
  [18-custom-models-gltf.md](18-custom-models-gltf.md).
- **Caching / ownership (P2)** — one handle per (addon, path); repeated loads return the **same** handle.
  Bridge-owned in per-addon registries (`Addon.images` / `Addon.models`); `:reload`/disable/relogin disposes every
  `TexI`/`Model` (frees GL), leaking nothing — the windows/overlays/ghosts guarantee. `:dispose()` frees one early.
- **Threading** — loaders are called from **setup code** (`OnLoad`/`OnEnterWorld`/commands), never inside a draw;
  v1 decodes **synchronously on the calling (UI) thread** (local files, small assets). A large-asset async path
  (`glob.loader.defer`, not-ready handle draws nothing) is a later refinement.

## 4. Capability 1 — 2D image on screen (`g:image`)

Add to the [`LuaGOut`](src/io/brodgar/addon/LuaGOut.java) wrapper — the single `g` surface every draw callback gets
(widgets, `hafen.ui.overlay`, `hafen.ui.gobOverlay`):

```lua
g:image(img, x, y)          -- native size at (x,y) top-left           → GOut.image(Tex,Coord)          [:97]
g:image(img, x, y, w, h)    -- scaled into w×h                         → GOut.image(Tex,Coord,Coord)    [:117]
g:aimage(img, x, y, ax, ay) -- anchored (ax/ay 0..1), mirrors g:atext  → GOut.aimage(Tex,Coord,ax,ay)   [:107]
```

All backing is public → **likely ZERO `haven` core edit**. The wrapper stays inert outside a draw (the `cur==null`
no-op guard); a disposed/not-ready image no-ops. `img` is the `hafen.render.image` handle (its `TexI` is a `Tex`).

## 5. Capabilities 2 & 3 — 2D image in the world (`hafen.render.sprite`)

```lua
hafen.render.sprite{ image = img|"foo.png", x, y [, z], a=0, scale=1, billboard=false, alpha=1, tint=… }
```
→ a transform handle (`:move`/`:rotate`/`:scale`/`:show`/`:hide`/`:destroy`/`:pos`), gizmo-compatible. `image=`
accepts a handle or a path (auto-loaded, owned by the sprite). `billboard` selects the form:

- **`billboard=true` — faces camera (capability 2).** A screen-space blit at the projected world anchor, the
  [`SpeakerIcon`](src/haven/SpeakerIcon.java:44) pattern: a `GAttrib implements RenderTree.Node, PView.Render2D`
  ([PView.Render2D](src/haven/PView.java:406)) whose `draw(GOut,Pipe)` projects the anchor via
  [`Homo3D.obj2view`](src/haven/render/Homo3D.java:201) and `g.image(texI, pos, size)`. **Screen-sized** (constant
  px regardless of zoom, like a marker); world-rotate/world-scale don't apply (it's 2D). No depth test (draws on
  top). Cheapest form. *(This overlaps a 2D overlay image drawn at `hafen.player.worldToScreen` — a billboard
  sprite is just the ergonomic, gob-anchored version.)*
- **`billboard=false` (default) — fixed quad (capability 3).** A true **world** quad: a 4-vertex `TRIANGLE_STRIP`
  [`Model`](src/haven/render/Model.java:45) ([`Homo3D.vertex`](src/haven/render/Homo3D.java:41) VEC3 +
  [`Tex2D.texc`](src/haven/render/Tex2D.java:36) VEC2) wrapped by
  [`new Material(texI.st(), FragColor.blend(new BlendMode()), States.maskdepth).apply(quad)`](src/haven/Material.java:165),
  put in a `Sprite` (`added(slot){slot.add(part);}`, the [`StaticSprite`](src/haven/StaticSprite.java:83) pattern),
  attached via [`SprDrawable`](src/haven/SprDrawable.java:40). It **stands fixed** in the world and gets the full
  transform + gizmo + `tint`/`alpha` from the shared core (§2). Default orientation = upright, facing `a`
  ([D-035](../decisions/rendering.md)); a flat-on-ground decal (90° pitch) is a later variant.

## 6. Capability 4 — custom 3D model (`hafen.render.object`, glTF) — summary

```lua
hafen.render.object{ model = mdl|"chair.glb", x, y [, z], a=0, scale=1, alpha=1, tint=… }
```
→ a transform handle like a sprite/ghost, gizmo-compatible. The visual is the parsed **glTF** model: each glTF mesh
primitive becomes an engine `Model` + a `Material` (baseColor texture → `TexI.st()`), collected in a `Sprite` and
attached via `SprDrawable` on a virtual `Gob` — the same §2 core as a fixed sprite, just with real mesh data
instead of a quad. **v1 = static glTF** (mesh + baseColor texture + normals; **no skeleton/animation**), hand-rolled
pure-Java parser over our [`Json`](src/io/brodgar/addon/Json.java) (**no native deps**), `.glb` preferred
(self-contained). Format, accessor decoding, PBR→engine material/lighting mapping, and scope are in
**[18-custom-models-gltf.md](18-custom-models-gltf.md)** ([D-035](../decisions/rendering.md)).

## 7. Decisions (ratified — maintainer, 2026-07-26)

- **[D-034](../decisions/rendering.md) — custom non-`.res` rendering is a new `hafen.render.*` namespace.** Images (screen +
  world) and custom 3D models live under `hafen.render`; `hafen.ghost` stays `.res`-only. Shared virtual-entity core
  (generalize the V-series) with a pluggable `Drawable` visual; the gizmo works on any handle. SAFE-tier
  (client-only, not gated — [D-029](../decisions/virtual-entities.md)).
- **[D-035](../decisions/rendering.md) — custom 3D models use glTF 2.0 (static subset first).** Hand-rolled pure-Java parser
  (over `Json`, no native deps), `.glb` preferred; static mesh + baseColor texture + normals in v1, animation later.
  OBJ/FBX not adopted (FBX needs native libs; glTF is the open, richer standard the maintainer chose).

## 8. Task slices (delivered as [012-custom-rendering](../012-custom-rendering/tasks.md))

- **R1 — 2D image on screen** (`hafen.render.image` + `g:image`/`g:aimage`). The foundation loader + the `LuaGOut`
  draw verbs. New Java is `io.brodgar.addon` only; **likely ZERO core edit**. Extend `hello`. Cap 1.
- **R2 — 2D image in the world** (`hafen.render.sprite`, billboard + fixed). The billboard (`Render2D`) + fixed
  (`TexI`-quad `SprDrawable`) visuals on the shared world-entity core; reuse the transform + gizmo. Caps 2 + 3.
  *(May split R2a fixed / R2b billboard.)*
- **R3 — custom 3D model in the world** (`hafen.render.object` + the glTF static loader). The big one — see
  [18-custom-models-gltf.md](18-custom-models-gltf.md) for its own sub-slices (R3a parse+mesh, R3b textures/material,
  R3c lighting polish; animation deferred). Cap 4.

*(R1 → R2 → R3: easy→hard, each one /implement task + one in-game verification. R2/R3's fixed visual reuses the R2
`SprDrawable`/core work; all reuse R1's loader for textures.)*

## 9. Non-goals / open items

- **Non-goals (now):** skeletal/vertex **animation**; runtime-generated / draw-to-texture images; sprite atlases;
  9-slice; shadowing client `.res` art; FBX/OBJ import.
- **Open:** exact naming (`sprite`/`object` vs `billboard`/`quad`/`mesh`); whether billboard sprites belong under
  `hafen.render` or `hafen.ui` (they're screen-space); default world size of a sprite/model (aspect, tile units);
  how much of the V-series core to physically refactor vs. duplicate for the shared engine; glTF PBR→engine shading
  fidelity (see [18](18-custom-models-gltf.md)).
