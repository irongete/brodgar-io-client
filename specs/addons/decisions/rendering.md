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
A running [`Widget.Anim`](src/haven/Widget.java:2085) counts as code for the same reason, and a surface whose
content is still `pending` (D-119) is skipped outright, so the first pass is the first one that draws anything
rather than a blank texture. ⚠️ **This entry originally added "(the client's own show/hide transitions are one)"
to that first clause, and it was WRONG**: a [`Window`](src/haven/Window.java:532)'s fade is its own private
`anim` field, not a `Widget.Anim` in `anims`/`nanims`, so nothing here saw it and every standing window could
freeze on the fade's first, near-transparent frame — which the world quad's `TexClip` then discarded **whole**.
Corrected in **044.3** (`Window.animating()`, a `// addon:` one-liner, asked beside the two lists); the decision
above stands unchanged, since "a transition is code" was always the rule — only the enumeration was short.
⚠️ **And it was short by one more, found the moment input arrived (044.4).** A widget that caches its rasterised
face — every [`SIWidget`](src/haven/SIWidget.java:31), so every `Button`, and the client is full of them — throws
that cache away with `redraw()` when it depresses under a click, arms as the pointer re-enters it, or is
disabled. None of that is in its place, its size, its visibility or its caption, so the signature never saw it
and a standing button **clicked, played its sfx, and never visibly pressed**. `SIWidget.redrawing()` is the third
`// addon:` one-liner, asked beside the other two; on the flat UI nobody needs it because the screen is redrawn
every frame regardless. A **held press** repaints its panel every frame besides, since a control being dragged —
a scrollbar, a slider — has no cached face to announce anything and simply draws its new position. The pattern in
all three corrections is one: *what a widget looks like is not always in what a widget holds.*
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

### D-193 — a thing that turns to the viewer aligns with the view PLANE, not with the eye point ✅ (044.3, 2026-08-08)
**Decision.** `"camera"` — the third [`:facing`](architecture-api.md#d-190) mode, on a sprite and on a standing
widget alike — orients its quad **parallel to the screen**: the quad's normal takes the camera's *back* axis, its
width the camera's *right* and its height the camera's *up*, all three read straight off the columns of the
inverse view matrix. It does **not** aim the quad at the eye point, which is the other thing "faces the viewer"
can mean.
**Rationale.** The mode exists to be *read*, and view-plane alignment is the one that is exactly square-on
**everywhere on screen**: an eye-aimed quad is square-on only at the centre and is progressively sheared toward
the edges, under a projection that is already a perspective frustum. It also keeps two panels standing side by
side **parallel** to each other rather than splaying, which is what makes a row of them look placed rather than
scattered. And it is cheaper and steadier: one rotation per frame for every camera-facing thing in the world,
instead of one per entity per frame, and no dependence on the entity's own position — so a panel does not swim as
you walk past it. The two modes agree exactly where they must: with the camera due east of a quad at
`:rotate(0)`, the rotation composes to the identity, which is where the `"fixed"` quad already faces.
**Consequences.** It is still **world geometry** — real world size, perspective, occlusion, shrinking with
distance, and picked by the ordinary pick — which is the whole distinction from `"screen"`. `:rotate(a)` is
*stored but unused* while it faces the camera and is honoured again the moment it is `"fixed"`, so the angle is
never lost. The turn is a [`Gob.Placer`](../../codebase/world-3d.md) on the `Drawable`, so the render tree's own
per-frame placement tick applies it: no tick loop, and one `// addon:` accessor (`MapView.camview()`) is the only
thing the client had to expose. **The corollary is a fact the docs must carry**: a quad rising along the camera's
up axis lies in the *horizontal plane through its anchor* when the camera looks straight down — at ground level
that is the terrain's own plane, and it is lost in it. `<entity>:offset(x, y, z)` ([D-187](architecture-api.md#d-187))
is the answer; centring the quad on its anchor is not, since it stays coplanar either way.
**See.** [D-190](architecture-api.md#d-190), [D-194](architecture-api.md#d-194),
[D-113](architecture-api.md#d-113), [044-spatial-ui](../044-spatial-ui/spec.md).

### D-195 — a flat thing in the world is picked by its own projected CORNERS, inside the event that arrived ✅ (044.4, 2026-08-08)
**Decision.** Input on a widget standing in the world is resolved by **inverting the homography its four
projected corners determine**, synchronously, in the very `MapView` mouse event the press arrived in — not by the
engine's pick pass. Each surface's visual records those corners once per frame from inside the 2D overlay pass
([`SurfaceDrawable`](src/io/brodgar/addon/SurfaceDrawable.java), a `PView.Render2D` that draws nothing and exists
only to be handed the `Pipe` its own slot resolved to), and the `"screen"` blit records its rectangle the same
way. A surface is a **plane**, so its picture reaches the screen through a projective map, and a projective map is
fixed by four point pairs: the inverse is exact, perspective foreshortening included, and the constant-size blit
falls out as the degenerate (affine) case of the same arithmetic rather than as a second code path.
**Rationale.** The engine's [`MapView.Hittest`](../../codebase/world-3d.md) answers a frame later, on the render
thread's callback. That is right for "the player clicked that tree" and useless for a button, which needs the
press, whatever drag follows and the release to be **one uninterrupted gesture** dispatched from inside the event
the press arrived in — a widget that grabs the mouse must grab it *now*. Inverting the projection and view
matrices instead would work, but it exposes them to Lua for no gain: the corners are already being projected to
put the quad on screen, so the map is read off the picture rather than solved for beside it, and the pointer
follows the picture *by construction* rather than by a second calculation that could disagree with it.
**Consequences.** Input does **not** consult the depth buffer: a panel behind a hill still takes the pointer, and
between two overlapping panels the nearer one wins on its centre's projected depth. That is the price of
answering in the same event, and it is stated rather than hidden. The corners carry a frame stamp, so a surface
that has stopped being drawn stops being clickable; a corner behind the eye (`w <= 0` in clip space, where the
projective divide is meaningless) drops the whole record rather than filling it with a plausible number; and an
edge-on quad determines no map and is refused, so a degenerate panel takes no pointer instead of taking it
everywhere. `hafen.vr():pointer(key, x, y)` enters this same function at a screen point and stops there — it is
the client's path from the map view *inward*, so it can neither move the character nor reach the server — and
`widget:screen(x, y)` is its exact inverse, both directions off the one map.
**See.** [D-196](widgets-ui.md#d-196), [D-193](#d-193), [D-192](#d-192),
[044-spatial-ui](../044-spatial-ui/spec.md), [world-3d](../../codebase/world-3d.md).
