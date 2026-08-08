# Decisions — Custom rendering (images & glTF)

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-034 — Custom (non-`.res`) rendering is a new `hafen.render.*` namespace ✅ (maintainer, 2026-07-26) 💤 (its NAMESPACE half superseded by [D-184](architecture-api.md#d-184), 2026-08-08, 043.1 — the section is now `hafen.vr()`; the SAFE-tier ruling below stands)
**Decision.** Rendering of assets that are **NOT engine `.res`** — **PNG images** (on screen and in the world) and
**custom 3D models** ([glTF](../design/18-custom-models-gltf.md)) — lives in a **new `hafen.render.*` namespace**, separate
from `hafen.ghost`. **`hafen.ghost` is reserved for `.res` game models** and is **unchanged** (maintainer:
"hafen.ghost lo quiero únicamente para crear gobs como ahora, modelos del juego"). The path exposes the substrate
`.res` already uses: a `.res` image layer is PNG bytes decoded with `ImageIO.read` → [`TexI`](src/haven/TexI.java:52)
([`Resource.readimage`](src/haven/Resource.java:1061)); the 3D side builds the engine's own
[`Model`](src/haven/render/Model.java:45)/[`Material`](src/haven/Material.java:143) from glTF instead of a `.res`.
Surface (five capabilities, [17-custom-rendering.md](../design/17-custom-rendering.md) §1):
- **loaders** — `hafen.render.image(path)` (PNG→`TexI`) · `hafen.render.model(path)` (glTF→mesh, [D-035](rendering.md));
  addon-relative + sandboxed ([`Addon.dir`](src/io/brodgar/addon/Addon.java:173); `..`/absolute rejected, [D-017](security-sandbox.md)); cached, bridge-owned.
- **screen 2D** — `g:image`/`g:aimage` on [`LuaGOut`](src/io/brodgar/addon/LuaGOut.java) → [`GOut.image`](src/haven/GOut.java:97)/`aimage`.
- **world 2D** — `hafen.render.sprite{image=,billboard=…}` — a **billboard** ([`SpeakerIcon` `PView.Render2D`](src/haven/SpeakerIcon.java:44) screen blit, faces camera) or a **fixed** `TexI`-quad ([`SprDrawable`](src/haven/SprDrawable.java:40)).
- **world 3D** — `hafen.render.object{model=…}` — glTF `Model`s behind `SprDrawable`.
**Consequences.**
- **Shared virtual-entity core.** `ghost`, `render.sprite`, and `render.object` all build on **one** placement
  engine — the **generalized V-series core** ([16-virtual-entities.md](../design/16-virtual-entities.md)): a client-only
  virtual `Gob` + `MapView.addClientGob` + `Placed` transform + `GhostGob.obstate` (tint/alpha/scale) + teardown +
  the **gizmo** ([D-031](virtual-entities.md)). Only the **visual** differs (`Drawable` attr): `ResDrawable` (.res) /
  `TexI`-quad / glTF `Model`s. The gizmo works on any transform handle. This is an internal refactor, **not** a
  change to `hafen.ghost`'s public surface.
- **SAFE-tier, not gated** — client-only visualization like `hafen.ui.overlay`/ghosts ([D-029](virtual-entities.md));
  nothing reaches the server. Sandbox: own assets only; no shadowing of client `.res`.
- **Likely zero-to-minimal `haven` core edit** for images/2D (`GOut.image`/`aimage`, `TexI`, `SprDrawable`,
  `Material`, `Model` all public); the glTF loader is `io.brodgar.addon` over our [`Json`](src/io/brodgar/addon/Json.java).
- Built as the **R-series** (R1 screen-2D, R2 world-2D, R3 glTF-3D) — one slice + one in-game verification each.
**Rationale.** Maintainer direction (2026-07-26), re-scoped from an earlier "extend `hafen.ghost`" draft:
`hafen.ghost` stays a pure `.res` tool; all custom-asset rendering is a sibling namespace. Reflects the maintainer's
own instinct ("a lo mejor esto podría ir en `hafen.render`"). Faithful reuse of the substrate ([D-009](widgets-ui.md)),
one canonical home per concern ([D-013](architecture-api.md)).
**See.** [17-custom-rendering.md](../design/17-custom-rendering.md), [18-custom-models-gltf.md](../design/18-custom-models-gltf.md), [D-035](rendering.md), [D-030](virtual-entities.md).

### D-035 — Custom 3D models use glTF 2.0 (static subset first) ✅ (maintainer, 2026-07-26)
**Decision.** The custom-model format ([D-034](rendering.md) #4, `hafen.render.model`/`object`) is **glTF 2.0**
(maintainer choice 2026-07-26, over OBJ/FBX). **v1 = the STATIC subset**: `.glb` (single-file binary) preferred,
`.gltf`+external buffers too; static meshes (`POSITION`/`NORMAL`/`TEXCOORD_0` + indices), multiple
nodes/meshes/primitives with **baked** node transforms, PBR **baseColor** only (factor + texture), alpha modes
OPAQUE/MASK/BLEND, double-sided. **Deferred:** skins/joints, **keyframe animation**, morph targets, full PBR maps,
Draco/meshopt. Parsed by a **hand-rolled pure-Java** reader over [`Json`](src/io/brodgar/addon/Json.java) (accessors
decoded by hand via `ByteBuffer` little-endian; data-URIs via `java.util.Base64`; textures via `ImageIO`) — **no
native dependency** (the reason FBX is rejected: it effectively needs Assimp/JNI). Each glTF primitive → an engine
`Model` + `Material` (baseColor `TexI.st()`), collected in a `Sprite` behind `SprDrawable` on the shared core.
**Consequences.**
- **Two hard bits, flagged early** ([18-custom-models-gltf.md](../design/18-custom-models-gltf.md) §4): (1) a fixed
  **basis/units conversion** (glTF right-handed +Y-up metres → the H&H world axes/tile scale), baked once; (2)
  **PBR→engine shading** — the client isn't PBR, so v1 draws **unlit baseColor** (R3a/b) and adds **basic lighting**
  via `NORMAL` + the engine light state only in **R3c**; fidelity approximates, not matches, a PBR viewer.
- Sub-sliced **R3a** (parse + static mesh, unlit) → **R3b** (textures + multi-material + alpha) → **R3c** (lighting
  polish); animation is a later series.
- Unsupported glTF features fail with a **clear addon-facing error** (name the missing feature), never a client
  crash; vertex/primitive counts are capped with a logged limit ([D-018](security-sandbox.md) spirit).
**Rationale.** glTF is the open, modern runtime standard (scene + material graph + optional animation) — richer than
OBJ, and unlike FBX needs no proprietary/native lib. Static-first keeps the first delivery bounded and pure-Java.
**See.** [18-custom-models-gltf.md](../design/18-custom-models-gltf.md), [17-custom-rendering.md](../design/17-custom-rendering.md), [D-034](rendering.md).

### D-192 — a surface redraws on a SIGNATURE where its content is readable state, and every frame where it is code ✅ (044.1, 2026-08-08)
**Decision.** A widget standing in the world is drawn into its own texture, and *when* that costs anything is
decided by **what its content is**, not by a switch the addon sets:
- **readable state** — a panel built from the client's own [controls](../040-ui-controls/spec.md) shows what those
  controls hold, and that is inspectable. A **signature** over the surface's own subtree (each widget's class,
  place, size, visibility and [`LuaWidget.text`](src/io/brodgar/addon/LuaWidget.java:1555), depth-first) says when
  it changed; unchanged means no pass. What a signature cannot see — a control's `value`, its `rows`, its picture —
  is marked at the setter (`WidgetSurface.touch`, seven one-liners in `Controls`), because the bridge is the only
  writer of an owned control.
- **code** — a `widget:on("Draw", fn)` handler is a Lua function of anything at all, so the only way to know what
  it *would* paint is to run it, and running it **is** the draw. Such a surface therefore redraws **every frame**.
A running [`Widget.Anim`](src/haven/Widget.java:2085) counts as code for the same reason (the client's own
show/hide transitions are one), and a surface whose content is still `pending` (D-119) is skipped outright, so the
first pass is the first one that draws anything rather than a blank texture.
**Consequences.** The headline number is honest in both directions and the suite asserts **both**: a control panel
holds `uploads` at 1 while `frames` climbs, and one label change costs exactly one more; a hand-painted panel moves
them together. That second half is not a concession — the spec's own example paints the smelter's fuel level, which
*must* redraw when the fuel changes, and nothing but the handler knows that it did. The pair is readable from Lua
as `hafen.client():profiling():surfaces()` → `live`/`uploads`/`frames`, pull-only like every counter beside it
([D-051](architecture-api.md#d-051)). The signature walk is over the surface's **own** handful of widgets, once per
frame per surface — it is what a dirty check *is*, not the tree-wide poll [042](../042-event-driven-reads/spec.md)
deleted, whose cost scaled with the whole HUD and which existed to synthesise events nobody had raised.
**Rationale.** The two candidates that cover both cases uniformly are worse: hashing the emitted draw commands
needs a recording `Render` and a comparison of `Pipe`/`Model` objects, and reading the texture back is a GPU→CPU
round trip per frame — each more expensive than the pass it would avoid. Splitting by *what the content is* costs
one walk and is exact on the half that matters, which is the half an addon fills with labels.
**See.** [D-191](widgets-ui.md#d-191), [D-119](widgets-ui.md#d-119), [D-051](architecture-api.md#d-051),
[026-text-cache](../026-text-cache/spec.md) (the same redraw-on-change principle, one level down).
