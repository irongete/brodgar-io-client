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
- **(038.2) `OCache.remove` does NOT dispose a gob — which is why half of "dies with the gob" is free and half
  is not.** `OCache.remove(ob)` ([:95](../../../src/haven/OCache.java:95)) drops the map entry and calls
  `ob.removed()`, which only sets the `removed` flag; `Gob.dispose()` ([:561](../../../src/haven/Gob.java:561)),
  the thing that disposes every `GAttrib`, is **not** on that path. So a screen-space overlay (a `GAttrib`) dies
  by GC along with its `Gob`, costing nothing — while a world-space one, whose visual is a *separate* client-only
  gob in the MapView scene, survives its target's despawn untouched. That is the latent bug `follow=` had: an
  anchored sprite whose target was felled floated on forever with no owner, because `FollowMoving.getc()` holds
  at the last position when `oc.getgob(tgt)` answers null. The fix hangs on the `GobRemoved` drain (D-102).
- **(038.2) The world-space entity creators split cleanly into "public entry" + "make", and the anchor becomes a
  parameter.** `newSprite`/`newObject`/`newGhost` each kept their option parsing and gained a `make*(owner, opts,
  long tgt, Coord3f off)` body returning the `LuaWorldEntity` instead of the handle; the public entry demands
  `x`/`y` and refuses `follow=`, and `gob:overlay`'s world half calls `make*` with the target gob id. The spec
  table doubles as the entity's options table with **no adapter at all** — `image`/`model` are already the option
  names, `scale`/`alpha`/`tint`/`a`/`billboard` are read where they always were, and only `ghost` → `res` needs a
  parameter. A re-fronting that needs a mapping layer is usually re-fronting the wrong seam.
- **(038.2) An absorbed entity must be hidden from the collection it still belongs to.** A world overlay's entity
  is registered in `Addon.ghosts`/`sprites`/`objects` exactly like a free one — which is what makes teardown free
  — so `hafen.ghost.list()` would have handed its raw handle (with `:destroy()`/`:move()`) straight back out. One
  `asOverlay` boolean on `LuaWorldEntity` plus one condition in `ghostList` closes it (D-103). Falsifying it (the
  flag ignored) reddened 2 probe checks.
- **(043.3) Deleting a door orphans whatever only that door could WRITE — grep the fields, not just the call
  sites.** Cutting `ov:image`/`ov:model`/`ov:ghost` and the verb set that served them read as a pure subtraction:
  every one of `:scale`/`:alpha`/`:tint`/`:rotate`/`:billboard`/`:spawnData`/`:position` already existed on the
  entity handle, so each became a `Retired` row and nothing was lost. Except `ov:offset`'s **three-number** form:
  `LuaWorldEntity.followOff` + the volatile `FollowMoving.off` were written from there and **nowhere else**, so
  after the cut an anchored entity had no way to sit anywhere but exactly on its gob — `tagger`'s pin and
  `:hello follow` would both have dropped from ~1.6 tiles overhead to the feet, silently, with a green build and
  a green suite. The fix was one verb on the handle (`<entity>:offset(x, y, z)`, refused on a free one naming
  `:position(p)`, D-187) because the plumbing was already there and live. Practical rule for a relocation: after
  listing the verbs that move, list the **fields** the removed surface was the sole writer of; each is either a
  new verb on the new home or a capability you are deleting on purpose.
- **(043.3) `asOverlay` was the whole cost of 038's "hide it so there is one door", and cutting the second
  creator deleted the concept, not just the flag.** With the world kinds gone, `overlayEntity`,
  `destroyOverlayEntity`, the four `overlay*` look forwarders, `luaOffset`, the `boolean overlay` parameter
  threaded through all three `make*` bodies, and `Attach.ent`/`materialise`/`dispose`/`worldKind`/`KINDS`/`offZ`
  all went with it — ~180 lines for one door that should never have been a second one. The tell that the flag was
  load-bearing in the wrong direction: it had to be consulted in `entityMembers`, `memberArg`, `anchorRegister`
  AND `anchorUnregister`, i.e. by every reader of the registries, to keep one object out of the list it was in.
  A boolean that four unrelated readers must remember is usually a design being paid for in instalments.
- **(043.3) The plan's measured port surface counted the addon NAMED for the feature and missed a site in
  `hello`.** `plan.md` recorded "Lua overlay world-kind sites: **2**, `tagger/main.lua`" — but `:hello follow`
  used `me:overlay():add("hello-follow"):image(icon):scale(2):offset(0, 0, 18)`, a third one, found only by
  grepping `overlay()` across `addons/` before writing any code. `hello` is frozen, so it is easy to think of it
  as not participating; it participates in everything. Grep for the **verb**, never for the addon you expect.
- **(043.5) When a construction property moves from the create's option table to a chained setter, the option
  key it left behind goes on being READ — dead, but silently.** `makeSprite` still opened with
  `boolean billboard = opts.get("billboard").toboolean()`, yet nothing had set that key since the sprite
  builder became `:add(image, anchor)` + chained setters: `Anchor.spec()` writes `x`/`y` and the collection
  adds `image`, so the read was permanently `false` and the create happened to agree with the field's default.
  It is invisible precisely because it *works* — a create that reads a key nobody writes is a default with an
  expensive spelling, and the day someone re-introduces the key it becomes two sources of truth. Rule, the
  D-187 one turned inward: *when a property changes door, grep the option KEY as well as the verb — the create
  body is a reader nothing points at.*
- **(044.1) A render target is v-FLIPPED relative to an uploaded image, so the quad that stands a PNG up stands a
  surface on its head.** `SpriteQuad.quadVerts` inverts `t` (bottom → `t=1`, top → `t=0`) because a `TexI` comes from
  a `BufferedImage` whose **first row is the image top**. A texture the client just drew into is the opposite: the
  framebuffer's first row is its **bottom**, and the [`Ortho2D`](src/haven/render/Ortho2D.java:41) the offscreen pass
  preps has `k[1] = -2/h`, i.e. widget `y=0` (the top) → NDC `+1` → the **top** of the viewport → `v ≈ 1`. So for a
  render target `t=0` is the widget's BOTTOM edge and the inversion must not be repeated. Reusing `SpriteQuad.quad`
  shipped a panel that read perfectly and was upside down — a green build, a green 19-check suite, and the one
  `[manual]` line catching it, which is exactly the class of thing that line exists for. Fixed as
  `SurfaceQuad.quadVerts` (its own vertices; the `VertexArray.Layout` plumbing is shared through
  `SpriteQuad.model(float[])` rather than copied). **Rule:** when a quad's texture changes PROVENANCE — uploaded
  image vs. render target — re-derive `t` from the projection rather than inheriting it from the sibling class.
- **(044.1) An offscreen pass ordered EARLIER IN THE SAME COMMAND STREAM is not "one frame stale" — it is the same
  frame.** The choice looked like "render-to-texture before the world, or accept a frame of lag"; it is neither,
  because `UILoop.display` builds ONE `Render buf` and everything is appended to it in order. Putting the surface
  pass immediately before `ui.draw(g)` — inside which the whole 3D scene is drawn, the MapView being a widget —
  puts the commands that WRITE each texture ahead of the commands that SAMPLE it, in the same submission. No second
  buffer, no fence, no staleness. Generalisable: in a retained-command renderer, "before" is a position in the
  stream, not a frame boundary — look for the single `Render` before reasoning about latency.
- **(044.1) A UI surface in the world wants `TexDraw + TexClip + blend`, which is neither of the two recipes R2a
  names.** A sprite is `draw + clip` (a solid cut-out) and the translucent-overlay recipe is `blend + maskdepth`
  (the ~1% ghost trap). A widget is both at once: its background is genuinely translucent and its glyph edges are
  antialiased (so it must blend), but the margin it never paints is fully transparent and must not write depth
  across the quad's whole rectangle (so it must clip). Keeping all three states composes correctly — clip discards
  the border, blend composites what survives, and depth is written by surviving fragments only, so the panel
  occludes and is occluded like any other world surface. The offscreen pass itself blends with alpha factors
  `ONE / INV_SRC_ALPHA` rather than the default `SRC_ALPHA / INV_SRC_ALPHA`: the client's own 2D pass targets an
  opaque frame buffer where destination alpha is never read again, but ours is a texture that gets sampled, and the
  default squares the alpha of everything drawn onto a cleared target.
- **(044.1) A dirty flag has to know about the ARMING tick and about `Anim`, or it measures the wrong thing and
  freezes the right one.** Two traps, both found by reasoning about the acceptance criterion "unchanged content
  holds the counter at 1" before the in-game run: (1) a widget is attached inert and paints nothing until the tick
  after the statement that built it (D-119), so a surface drawn on the frame it is stood spends an upload on a
  blank texture and the criterion reads 2 — skip the pass entirely while any content is `pending`; (2) `Window`'s
  `added()` calls `initanim()`, so a window REPARENTED into a surface starts a show transition, and a
  state-signature cannot see an animation's progress — a surface with anything in `anims`/`nanims` must count as
  changing or it freezes on the transition's first frame. Both are cases of the same thing: *a dirty check over
  state must enumerate the things that change without changing state.* **⚠️ (2) is HALF RIGHT and shipped broken —
  see the 044.3 entry below: a `Window`'s transition is not in `anims`/`nanims` at all.**
- **(044.3) "Enumerate the things that change without changing state" was the right rule and the WRONG list — a
  `Window`'s transition is not a `Widget.Anim`.** 044.1 wrote that a surface with anything in `anims`/`nanims`
  must count as changing, *naming the client's own show/hide transitions as the case it covered*. It does not
  cover them: [`Window`](src/haven/Window.java:532) keeps its fade in its **own private `anim` field**, ticked in
  `Window.tick`, entirely separate from [`Widget.anims`/`nanims`](src/haven/Widget.java:2082). So every standing
  window was one frame's luck away from freezing on the first frame of its own fade-in. The failure is much worse
  than "a bit faint", because the world quad's material carries `TexClip` (discard alpha `< 0.5`): a texture
  captured at `na ≈ 0.05` is discarded **whole**, so the panel is *absent*, while the `"screen"` blit — no clip —
  shows the same texture faintly. That difference is what finally located it. It is timing-dependent (the
  `pending` gate can outlast the 0.1 s `FadeAnim`, in which case the single upload is already the finished
  window), so a fresh client looked perfect and every post-`:reload` generation was broken — for two whole tasks.
  Fixed with a `// addon:` `Window.animating()` asked beside the two lists. **Rule: when a learning names the
  cases a rule covers, open the class and check that the case is IN the list you wrote — "the client's own
  transitions are one" was an assumption about where an animation lives, stated as an enumeration.**
- **(044.3) To turn a world quad every frame, override the `Drawable`'s `Gob.Placer` — the render tree already
  re-reads it.** [`Gob.Placed.autotick`](src/haven/Gob.java:945) rebuilds a `Placement` each frame and takes its
  rotation from [`Gob.placer().getr(rc, a)`](src/haven/Gob.java:875), and [`Gob.placer()`](src/haven/Gob.java:577)
  asks the `Drawable` first ([`Drawable.placer()`](src/haven/Drawable.java:50), public and overridable). So a
  camera-facing quad is a `SprDrawable` subclass whose `placer()` returns a `Placer` that computes the rotation and
  delegates `getc` to `glob.map.trnplace` — **no tick loop, no polling, no per-frame write from the addon layer**,
  and `Placement.equals` compares the matrix so a still camera costs nothing. The same shape as R2a's "attach a
  `Moving` and the tree does the work", one slot along: *for a client-only entity, look for the engine hook the
  render tree already re-evaluates before adding a loop of your own.* The camera itself is the only thing that had
  to be exposed (`MapView.camview()`), because `Placer.getr` runs outside any render pass and has no `Pipe`.
- **(044.3) A camera-facing quad anchored at the feet lies in the GROUND PLANE when the camera looks straight
  down.** It rises along the camera's *up* axis; make up horizontal and the quad is horizontal, at the anchor's
  own z — which at ground level is the terrain's plane, so it is lost in it. This is geometry, not a bug, and it
  is not fixed by centring the quad on its anchor (still coplanar): the fix is **elevation**, i.e. the
  `<entity>:offset(x, y, z)` D-187 already put on the handle. Worth stating because the first instinct on seeing
  it is to hunt for a depth/culling fault in the new code, and half an hour went that way before the elimination
  test (only the camera-facing things vanish; the `"screen"` blit never does) named it in one line.
