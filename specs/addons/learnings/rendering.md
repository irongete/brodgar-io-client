# Learnings — Custom rendering (images, sprites, glTF)

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **R1: a `.res` image is already a PNG, so the "custom image" loader is `ImageIO.read → new TexI(img)` — zero core
  edit.** `Resource.Image` does exactly `new TexI(ImageIO.read(...))` ([Resource.java:1060/1182](src/haven/Resource.java:1060)),
  and `SpeakerIcon.GLYPH` blits a bare `TexI` with no resource; every backing (`GOut.image`/`aimage`, `new TexI`,
  `TexI.sz`/`dispose`) is public. So `hafen.render.image` is `io.brodgar.addon`-only. Bonus: `new TexI(BufferedImage)`
  uploads to the GPU **lazily** (in `TexI.st()`), so the loader is even **headless-constructible** — the jshell test
  decodes a real PNG and builds the `TexI` with no GL context (only `st()`/a real blit would need one).
- **R1: the D-017 path sandbox is ONE containment check, not a pile of string bans.** `base.resolve(name).normalize()`
  then `p.startsWith(base)`: after `normalize()`, an **absolute** path and a `..` that **climbs out** both fail
  `startsWith` (they no longer share the folder prefix), while an internal `a/../b` stays inside and is allowed. One
  line covers "reject absolute + `..`-escape" without banning legitimate internal traversal — cleaner than blacklisting
  `..`/`:`/leading-`/`. (Windows case-insensitivity is a non-issue: both paths derive from the same `base` prefix.)
- **R1: guard the blit on a `volatile dead` flag or `:dispose()` isn't permanent.** `TexI.dispose()` only nulls the
  cached GL texture; the very next `TexI.st()` (inside `GOut.image`) **re-uploads** it. So a disposed image drawn again
  would silently resurrect. The fix is a `volatile dead` on the `LuaImage`, set before `tex.dispose()` and checked in
  `g:image`/`g:aimage` before drawing — dispose becomes final, and a UI-thread dispose racing a draw-thread blit costs
  at most one extra frame. Lesson: engine `dispose()` methods are often "release resources," not "make unusable" — add
  your own liveness flag when you need the latter.
- **R2a: the V-series ghost was already 95% the generic world-entity — the "one real refactor" was a mechanical base
  extraction, not a rewrite.** Only `res`/`sdt`/`setRes` were ghost-specific; the transform/look/scene/lifecycle/gizmo
  are visual-agnostic. So `LuaWorldEntity` (base) + `LuaGhost`/`LuaSprite` (visuals) + a `*Ghost`→`*Entity` rename of
  the `AddonManager` scene helpers was low-risk (pure rename + type-widen), and the **existing ghost auto-demo is the
  regression test** — it drives every renamed helper at each login. Extract a base the moment a second thing wants
  95% of the first's machinery; don't wait until the duplication is entrenched (would-be R3 would have tripled it).
- **R2a: a client-only textured world quad is a `.res` mesh minus the resource wrapper — build the `Model` directly.**
  The `basic` PView scene already preps `Homo3D.state` (the proj·cam·loc vertex transform), so a raw 4-vert
  `TRIANGLE_STRIP` `Model` of `Homo3D.vertex`(VEC3)+`Tex2D.texc`(VEC2) renders in world space with no shader wiring.
  Author the quad in **gob-local space** (x=0 plane, z up 0→h, y across the width) and the gob's `Placed` slot
  (translate + z-rotation by facing) makes it stand upright and face `a` — identical to how a `.res` mesh is authored,
  so no coordinate fights.
- **R2a GOTCHA: a textured world surface must alpha-CLIP (discard), NOT alpha-blend — copy the engine's `$tex` matpart.**
  The first cut wrapped the texture in `new Material(tex.st(), FragColor.blend(new BlendMode()), States.maskdepth,
  nofacecull)` — that is the engine's **translucent-overlay** recipe (blend + no-depth-write, used for the tile-grid /
  selection rectangle), so even a fully-opaque PNG (verified `A=255`) rendered as a ~1% ghost. The RIGHT recipe is the
  one EVERY `.res` textured object uses ([`TexRender.$tex`](TexRender.java), `clip=true` by default):
  `new Material(tr.draw, tr.clip, nofacecull)` where `tr` is a `TexRender` over the `TexI`'s sampler (`tex.st().data`)
  — `TexDraw` samples, `TexClip` **discards** texels with alpha `< 0.5`, no blend, depth written → a **solid** cut-out.
  Lesson: don't hand-roll a `ColorTex`+blend stack for a world texture; reuse the engine's `TexRender.draw`/`clip`
  states so it renders exactly like the client's own art. (Opt-in `alpha<1` still blends, but only via `GhostGob.obstate`.)
- **R2a: to make a client entity FOLLOW a gob, attach a `Moving` — the render tree does the per-frame work for you.**
  `Gob.getc()` uses a gob's `Moving` attrib for its live position, and the render tree re-evaluates each client gob's
  placement every frame (`Placed.autotick` → new `Placement` → `getc()`). So a `FollowMoving extends Moving` whose
  `getc()` returns `target.getc() + offset` makes the entity track the target automatically — **zero Lua polling**,
  the exact path the engine's own `Following` (held-item-follows-hand) uses. Deliberately NOT a `Following` subclass:
  `Placed` special-cases `Following` (the bone-transform path); a plain `Moving` takes the simple translate-to-`getc()`
  + rotate-by-own-`a` path, so the sprite keeps its own facing/scale. Re-resolve the target id each frame
  (`oc.getgob(id)`) so it survives the gob unloading/reloading. All backings public (`Moving`, `Gob.glob`/`getc`/`getrc`,
  `OCache.getgob`) → zero core edit. This is the world-space analog of the `gobOverlay` GAttrib sweep.
- **R2a: no `res.get()` ⇒ no `glob.loader.defer` — a sprite creates synchronously, unlike a ghost.** The ghost defers
  only to dodge the `Loading` that `res.get()` throws until the resource streams in (the Plob precedent). A sprite's
  `TexI` is already decoded (R1, on the UI thread), and `Model`/`Material`/`addClientGob` never throw `Loading`
  (`RenderTree` slot mutation is tree-locked, safe from the UI thread) — so `newSprite` builds + publishes inline and
  the handle's gob is live on return. Don't cargo-cult the defer dance when there's nothing to wait for.
- **R2a: dispose only what you own — split the sprite's geometry from the shared texture.** `SpriteQuad.dispose()`
  frees only its `Model`'s `VertexArray`; the `TexI` belongs to the `LuaImage` handle (`Addon.images`) and is freed by
  `teardownImages`. This lets one image back several sprites + the screen-`g:image` at once, and keeps teardown order
  irrelevant (the sprite never touches the texture). Mirror of R1's "engine `dispose()` releases, doesn't invalidate."
- **R2b: a world billboard is the `SpeakerIcon`/`gobOverlay` pattern — a `PView.Render2D` on the gob, not a mesh.**
  A camera-facing world image is a screen-space blit anchored at a projected world point: implement `PView.Render2D`
  and, in `draw(GOut, Pipe state)`, `Homo3D.obj2view((0,0,z), state, …)` → screen, then `g.image(tex, pos, sz)`. The
  `state` already carries the gob's `Placed` world transform, so moving the gob (or a followed one) moves the blit —
  position + gizmo-move come free; world-rotate/scale are meaningless (it's 2D). The 2D pass draws after the 3D scene,
  so it's on top (no depth) and screen-sized. This is the ergonomic, gob-anchored twin of drawing at
  `player.worldToScreen` in a `hafen.ui.overlay`.
- **R2b GOTCHA: a Drawable-less virtual client gob self-removes every `ctick` — make the billboard the gob's `Drawable`.**
  [`Gob.ctick`](src/haven/Gob.java:463) ends with `if(virtual && ols.isEmpty() && getattr(Drawable.class)==null)
  glob.oc.remove(this)` — it treats a virtual gob with no overlays and no `Drawable` as garbage. A billboard drawn by a
  bare `GAttrib`(+`Render2D`) would hit this **every tick** (locking the shared `OCache` + copying its callback list;
  the remove itself is a no-op since a client gob was never in `objs`, but the churn is real). Fix: make the visual a
  resource-free **`Drawable`** (`getres()==null`, like `SprDrawable`) — then `getattr(Drawable.class)!=null` and the
  cleanup never fires, AND (bonus) `attrclass` stores it under `Drawable.class` = THE gob visual (correct), while
  `Gob.added`'s `slot.add(drawable)` still registers its `Render2D` (membership is by slot-object type in the tree
  adapter, independent of the node's no-op `added` — the same mechanism that renders `SpeakerIcon`).
- **R3a: a whole new file format is one pure class + the shared visual pattern — the R-series core paid off a 3rd time.**
  Adding glTF models was ~2 real pieces: a **pure-Java parser** (`Gltf`, over our existing `Json`) and a **`MeshSprite`**
  that clones `SpriteQuad`'s "resource-free `Sprite` → engine `Model` + `Material`, added to the `SprDrawable` slot"
  shape (just N primitives instead of 1 quad, and a `BaseColor` instead of a `TexRender`). `LuaObject`/`LuaMesh`/the
  facade/teardown/pick are all copy-adjust of the R2 sprite equivalents. So the "big one" was mostly the *parser*, not
  the *engine plumbing* — the shared world-entity core (D-013) already had transform/look/gizmo/teardown solved.
- **R3a: keep the file parser 100% pure so it is fully headless-testable — bake into vertices, not a runtime matrix.**
  `Gltf` touches no GL and no session — only `Json`, `haven.Matrix4f`/`Coord3f` (pure math), and byte arrays — so a
  known model's baked geometry can be asserted numerically (27 checks: `.glb`/`.gltf`+data-URI, indexed/non-indexed,
  TRS + hierarchy baking, the basis round-trip, materials, the error paths). Baking the node transforms **and** the
  basis/units conversion into the vertex positions **at parse time** (rather than a per-frame root `Location`) is both
  cleaner (the gob's own `Placed`/`obstate` do the world transform) and what makes the output directly testable +
  `:bounds()` trivially world-unit-correct. The engine-`Model` build (`MeshSprite`) is then a thin GL wrapper verified
  by compile + in-game, the A8/A10 "headless resource skip" precedent.
- **R3a: the glTF→engine coordinate basis is a det-+1 rotation × a unit scale, chosen once and documented.** glTF is
  right-handed +Y-up metres; H&H model space is Z-up tiles (the ghost/sprite convention). `(x,y,z)→(x,-z,y)` (+90°
  about X) maps up→up and **preserves winding** (determinant +1), so R3b can honour `doubleSided`/back-face cull
  without reversing indices; times `MODEL_UNIT` (=`tilesz.y`=11 world units/metre → 1 metre = 1 tile). The glTF origin
  = the gob position (author base at `Y=0`), mirroring how ghost `.res` models sit at their feet. `haven.Matrix4f` is
  column-major with the SAME `m[col*4+row]` layout as a glTF `node.matrix`, so a glTF matrix drops straight in.
- **R3a: an unlit flat solid needs only `POSITION` + `BaseColor` — the scene provides the rest.** The `basic` PView
  scene already preps `Homo3D.state` (world-vertex transform), the camera/projection, and the `FragColor` target, so
  a `Model` with just `Homo3D.vertex` + a `new Material(new BaseColor(...))` renders as a solid, opaque, flat-coloured,
  depth-writing surface — `BaseColor` multiplies the default white fragment (no light math). No blend / no `maskdepth`
  for an opaque solid (those are the *translucent-overlay* recipe — the R2a "1% ghost" trap); `nofacecull` for R3a so a
  winding/basis quirk can never hide a face.
- **R3b: `texture × baseColorFactor` = `TexRender.TexDraw` + `BaseColor` (both `mul` at priority 0).** `Tex2D.mod`
  (from `TexDraw`) and `BaseColor.shader` both do `FragColor.fragcol().mod(in→mul(in, …), 0)`, so listing both in a
  `Material` composes them into a single multiply → the glTF base-colour semantics. Alpha modes reuse existing engine
  states: `MASK` = `TexRender.TexClip` (fixed 0.5 discard — the engine has no per-material cutoff, so glTF `alphaCutoff`
  ≠ 0.5 isn't honoured); `BLEND` = `FragColor.blend(new BlendMode())` + `States.maskdepth` (the exact translucent recipe
  `GhostGob.alpha` uses); `doubleSided` = `Material.nofacecull`, else `new States.Facecull()` (BACK). `State implements
  Pipe.Op`, so a `List<Pipe.Op>` of states → `new Material(list.toArray(new Pipe.Op[0]))`.
- **R3b: decode mesh textures with `new TexI(img, false)` (NO power-of-two rounding).** The default `new TexI(img)`
  rounds to POT (`tdim = nextp2(sz)`), which for a non-power-of-two image leaves glTF's `[0,1]` UVs sampling into the
  padding. `round=false` (`tdim == sz`) makes `[0,1]` map to the whole image — the correct choice for externally-authored
  UVs (modern desktop GL handles NPOT + NEAREST). (The 2D `.res` path dodges this by sampling in *pixels* / `tdim`; a
  3D `[0,1]`-UV mesh can't.)
- **R3b: keep the glTF *parser* GL-free — extract image BYTES, let the caller build the `TexI`.** `Gltf.resolveImage`
  pulls each referenced image to a raw `byte[]` (from a `bufferView` slice / `data:` URI / external file), **lazy +
  deduped** per glTF image (so N materials sharing a texture → one blob). `ImageIO`→`TexI` happens in `AddonManager.newMesh`
  (needs the addon session anyway). This keeps `Gltf` a pure byte/math class (headless-testable: assert blob count / PNG
  magic / dedup), the same discipline as R3a's geometry.
- **R3b: the shared mesh `TexI` changes `mesh:dispose()` semantics — teardown ORDER matters.** R3a's mesh held no GPU
  state (objects owned their own `Model`s), so a mesh `:dispose()` never hurt a live object. R3b's mesh owns the shared
  textures, so `teardownObjects` MUST run before `teardownMeshes` (it does) — else a live object still holds a sampler
  when its `TexI` is disposed and that memory is never reclaimed. **Corrected in 028.2, measured:** this entry used to
  claim a manual `mesh:dispose()` "frees the textures out from under" a live object. **It does not.** The object keeps
  drawing, textured and unchanged, because `MeshSprite.texRender` captures `tex.st().data` **once, at mill time**, into
  the material state — it never re-reads the `TexI`. What dispose forfeits under a live object is the *freeing*, not the
  picture. So the order is load-bearing for **leak-freedom**, not for visual correctness, and the leak check
  (`profiling():memory()` texture counters back to baseline after `:reload`/disable/relogin) is what actually tests it.
  Still: dispose a mesh only when unused — it buys nothing while an object draws it, and the handle goes `dead`.
- **A prediction derived from ownership is not an observation — say which one an entry is.** The R3b line above was
  reasoned from "the mesh owns the textures, so freeing them must break the object" and written as fact; it survived two
  features before 028.2's acceptance list finally made someone *look*. Ownership tells you who may free what; it does not
  tell you what a consumer that already **captured** the resource will do. The same shape as R1 ("engine `dispose()`
  releases, it does not invalidate") and R2a ("dispose only what you own") — both learned by measuring, not deducing.
  **Lesson:** when a learning states a user-visible consequence nobody has watched happen, mark it *predicted* and put it
  on an acceptance list; a wrong learning is worse than a missing one, because it gets copied into the docs.
- **glTF UV `v`-orientation is a genuine can't-verify-headlessly item — make it a one-line switch.** glTF `v=0` = image
  top; `TexI.st()` uploads the `BufferedImage`'s row 0 (top) directly (no flip) → glTF UVs *should* map straight (no
  `1-v`). But 3D sampling orientation can't be asserted without GL, so `MeshSprite.TEXV_FLIP` is a single `boolean`:
  ship the reasoned default (no flip), flag it as the first in-game check, flip in one line if wrong. (Same spirit as the
  A8/A10 "headless resource skip" — isolate the un-testable bit behind a trivial toggle.)
- **R3b-2: the D-013 shared core pays off again — an example-addon integration can be pure Lua.** Adding glTF models to
  the `planner` (select + gizmo + persist) needed **zero** Java: the object handle is the same `:move`/`:rotate`/`:scale`
  handle a ghost/sprite exposes, the V2 pick/`ObjectClicked` + gizmo were generalized to objects back in R3a, and
  `gizmo.lua` already drives "anything with `:pos()`/`:move()`". So R3b-2 was one more `kind` on the planner's records +
  a `spawn` branch + `:planner object` — the same shape R2b used for sprites. **Lesson:** once the core is shared, a new
  visual kind rides *every* kind-agnostic consumer (selection, gizmo, grab, persistence, teardown) for free; the only
  per-kind code is the one `spawn` line that names its `hafen.render.*` constructor. Verify such a slice with the **LuaJ
  parse** check (compile each `.lua` via `Globals.load` without running it) + a compile-still-green + logic review — no
  rebuild, `ant bin` + `:reload` to test.
- **R3c: to make custom geometry lit, add `Homo3D.normal` + a `Light.PhongLight` state — the scene supplies the lights.**
  The engine's world lights (`Lighting.lights`/`Light.LightList`) are applied *at the MapView scene root*, so any gob in
  the `basic` scene (our ghost gobs included) already has them in its pipe. A material is **unlit** unless it declares a
  Phong state — that's the entire difference between R3b (fullbright) and R3c (world-shaded): one `Light.PhongLight`
  `Pipe.Op` on the material + the `normal` vertex attribute it consumes. No light *setup*, no per-frame light code, no
  core edit — the `.res` `col` layer does exactly this via `$col`→`PhongLight`. Reflectance = the engine's neutral
  `PhongLight` defaults (amb 0.2/dif 0.8/matte); the texture stays the albedo the light modulates.
- **Normals transform by the inverse-transpose, and it's cheap enough to always do right.** A normal is NOT a position:
  under a non-uniform scale a vertex-style transform *skews* it off the surface. Bake normals by `trim3(transpose(
  invert(fin)))` (fin = basis·node). For a pure rotation + uniform scale (BASIS and most nodes) this equals the plain
  transform after re-normalization — but it's one 4×4 invert per primitive, so just always do it; a headless check on a
  synthetic triangle proved the baked normal comes out unit-length and un-scaled (`(0,-1,0)`, not `(0,-11,0)`) — the
  unit-basis scale correctly drops out of the direction.
- **When a mesh lacks `NORMAL`, compute smooth ones from the BAKED geometry — the det-+1 basis keeps them outward.**
  Area-weighted face normals (`e1×e2` accumulated per vertex → normalize) computed *after* baking need no normal matrix
  and, because the basis preserves winding (det +1, the same reason back-face culling is correct), point outward
  consistently with front faces. The headless check confirmed the fallback matches the transformed attribute exactly.
- **sRGB was a non-issue because the engine doesn't sRGB-convert model textures.** Spec flagged sRGB as an R3c concern,
  but grepping the load path showed `Texture.srgb` is left `false` everywhere for models (game textures are plain
  `UNORM8`). Our `TexI`s already match, so *doing* sRGB would make ours the odd ones out. **Lesson:** "match the engine"
  can mean "do nothing" — verify what the engine actually does before adding a correction, and document the finding so
  the next person doesn't re-open it.
- **Pre-existing repo state:** `addons/planner/cube.glb` is byte-identical to `addons/hello/tank.glb` (both 2 MB), not the
  940-byte R3a cube the R3b-2 docs describe — flagged to the maintainer (it may make `:planner object` stand a tank; out
  of R3c scope, untouched). Worth a check before the next planner in-game test.
