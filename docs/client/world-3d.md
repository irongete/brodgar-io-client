# The 3D world: the scene, client-only gobs, materials and glTF

> The `MapView` scene, a gob of the client's own in it, ground overlays over the terrain, and the
> texture/material path. A gob's own session is [multi-session.md](multi-session.md); the pointer and the
> pick pass are [map-click.md](map-click.md); which ground is drawn is
> [terrain-raster.md](terrain-raster.md); the camera is [camera.md](camera.md).

## Client-only world entities

| What | Where |
|---|---|
| **Client-only world entity (template)** | `MapView.Plob extends Gob` — `super(glob, rc)` + `setattr(new ResDrawable(...))` + `basic.add(placed)`; `move(Coord2d,double)`; `slot.remove()` |
| Gob construction (no server id) | `Gob(Glob,Coord2d)` / `Gob(Glob,Coord2d,long)`; `Gob implements RenderTree.Node, Sprite.Owner` |
| Visual attr (`.res`-backed) | `ResDrawable` (`Gob.setattr`) |
| Add/remove in the 3D scene | `MapView.addClientGob` (`// addon:` seam) + `Gob.placed`; transform `Gob.Placed`. **A client gob is in no `OCache`, so nothing but its placer removes it** — and `Placed.autotick` *catches* the `Loading` a `new Placement()` throws when the tile under it is unloaded (`getmapstate` → `glob.map.tiler`) and keeps `cur`, so it goes on drawing at its last placement rather than vanishing with the ground |
| Follow-a-gob motion | subclass `Moving` with `getc()` = `target.getc().add(offset)`, re-resolved via `OCache.getgob` each frame; the render tree's per-frame `Placed.autotick` tracks it. NOT a `Following` subclass (that steals facing) |
| **Where a gob is placed and how it is TURNED — the per-drawable hook** | `Gob.Placer` (`getc(rc,a)` + `getr(rc,a)`→`Matrix4f`); `Gob.placer()` asks the `Drawable` first — `Drawable.placer()`, **public, overridable**, defaulting to `gob.glob.map.trnplace` (`MCache.trnplace`/`mapplace` = `Gob.DefaultPlace`, `getr` = z-rotation by `-a`). `Placed.autotick` rebuilds a `Placement` **every frame** and re-reads `getr`, pushing `slot.ostate` only when it differs (`Matrix4f.equals`) ⇒ **a per-frame rotation costs an override, not a tick loop**. Applied as `Location("gobx")` (translate, y-negated) then `Location("gob")` (this rotation), with `GhostGob.obstate`'s scale below both. Ignore facing entirely: `Location.nullrot`. **Per-gob render state on a NATIVE gob — the `SetupMod` seam:** `Gob.SetupMod` is an interface a `GAttrib` may also implement; `gobstate()`/`placestate()` contribute a `Pipe.Op` to the gob's own child slot, i.e. **below** the `"gobx"` translate and `"gob"` rotate of the row above ⇒ **T·R·S**, the same level `GhostGob.obstate` scales at. `Gob.setattr` registers it in the private `setupmods`; `Gob.ctick` calls the private `updstate()`, which rebuilds `GobState` from that list and pushes `slot.ostate` **only** when `Utils.eq(mods)` differs — so a change lands next tick and costs no new seam. ⚠️ **`Location` has no `equals`**: a freshly minted op each tick compares unequal every tick and re-pushes forever — **cache the op per value**. `Pipe.Op.compose` drops nulls and returns the lone survivor **itself** (only `Composed.equals` is `Arrays.equals`), so with one mod the comparison is identity. Copy `GobHealth`; an attrib that is **also** a `RenderTree.Node` is the only shape for which `setattr` can throw `Loading` |

## Ground overlays — who decides one is drawn

| What | Where |
|---|---|
| **The ref count** | `MapView.oltags` — a **multiset** `Map<String,Integer>` (seeded `{"show":1}`); `enol`/`disol`/`visol` are `+1` / `-1`-and-drop / `containsKey`. All three `synchronized(oltags)` |
| Who counts | the user's three `MenuCheckBox`es via the private `GameUI.MapMenu.toggleol` (`cplot`, `vlg`, **`prov`**), and the server's `flashol`/`unflashol` (hover a claim → on for `tm` seconds, then decremented) |
| What it gates | `MapView.oltick`: for each `OverlayInfo` from `glob.map.getols(...)`, visible iff **any** of its `ResOverlay.tags()` is in `oltags`; adds/drops the scene `Overlay` accordingly |
| The **other** side | `MapWnd.overlays` — a plain `CopyOnWriteArraySet<String>` fed by `MapWnd.toggleol` from its own checkbox with **`realm`**, read by `MapWnd.View.drawgrid` to blit `DisplayGrid.olimg(tag)` from the RECORDED masks ([mapfile.md](mapfile.md), [minimap.md](minimap.md)) |

**Gotcha — two vocabularies, and a count.** Provinces are `prov` in the world and `realm` on the map; no tag
reaches both. `oltags` has several owners, so nothing turns an overlay off — a "set" takes and releases one ref.

### Recolouring ground: a sheet over it, or a shader under it

Two ways, and they are not interchangeable. A **sheet** adds a translucent layer that conforms to the relief;
it can tint, and it cannot desaturate — alpha blending interpolates every fragment the same fraction toward one
colour, so what is under a white sheet goes pale and stays green, brown and blue. Taking the colour *out* is an
operation on a fragment's own channels against each other, and only the fragment shader reaches that.

| The sheet | Where |
|---|---|
| **An overlay needs no resource** | `MCache.OverlayInfo` is a plain interface — `tags()`, `mat()`, `omat()` — and `ResOverlay` is only its resource-backed implementation. `MapView.selol` is the precedent: an anonymous `OverlayInfo` with a `Material` built in code |
| **It conforms** | `MapMesh.makeol(OverlayInfo)` re-lays every masked tile through its own `Tiler.lay` into a second mesh over the ground's own vertices (`makeolvbuf`, cached per cut as `olvert`), wrapped in `MapMesh.OLOrder(id)` — `mainorder` **1002**, so it draws after the ground in the same pass and needs no depth bias |
| **The material** | `new Material(new BaseColor(r, g, b, a), States.maskdepth, new MapMesh.OLOrder(id))` — `MapView.gridmat`'s own set. Blending is already on: `PView.basic(id_misc, …)` preps `FragColor.blend(SRC_ALPHA, INV_SRC_ALPHA)` for the whole 3D scene, so alpha blends with no state of your own; with no `Light.PhongLight` the colour comes out **flat**, unlit |
| **Which tiles it covers** | `MCache.getol(id, area, buf)` — the grid's own recorded masks (`Grid.ols`/`Grid.ol`, filled by `mapdata2`) first, then every `MCache.LocalOverlay` the cache holds. `MCache.RectOverlay(id, Area)` is the ready-made one, registered with `MCache.add`; on a grid with no recorded masks it is the only thing that makes the mask answer at all |
| Into a scene | `MCache.getolcut(id, cc)`, cached per `Grid.Cut`; `MapView.Overlay extends MapRaster` is one raster per `OverlayInfo`, colour applied at the slot as `slot.add(base, id.mat())`. Its second grid — `outl`, over `getololcut`, drawn with `id.omat()` — goes into the tree **only where `omat()` is non-null**, so an overlay with no outline material has no `outl` slot and `getololcut` is never asked for one. Fork: the raster asks `getolcut` only for cuts the mask can reach — `MapRaster.skipcut` overridden against `MCache.olreaches(id, area)` (`// addon:`), over the cut's own tiles with `margin(1)`, which is the wider of the two areas the pair reads (`makeol` takes `Area.sized(ul, sz)`, `makeolol` that area's margin). Upstream asks for **every** cut of the drawn area — 25, per overlay, per frame, each taking `MCache`'s `grids` lock — so a mark covering two cuts pays for 25 there, and N marks pay 25×N to be told most of them are empty |
| **A mask the client computes, rather than one a grid recorded** | `MCache.LocalOverlay` is a plain interface beside `RectOverlay` — `id()`, `fill(Area, boolean[])`, and the two defaults `filter(Area)` and `tick()`. `MCache.add`/`remove` register and unregister one. `MCache.getols` skips an overlay whose `filter(a)` answers **true** for the area being built, and the default answers `false`, i.e. *ask me about every cut*; `fill` then marks each tile of `a` the overlay covers, indexed `a.ri(lc)`. Fork: `filter` is also what decides which **cuts** are asked for — `MCache.olreaches(id, area)` puts it to one cut's tiles, exactly and with no allocation. ⚠️ **A `filter` written generously now costs cuts, not merely a mask fill.** `olreaches` is one-sided: it may answer true where the mask misses, never false where it touches, so a grid that *records* the id answers **maybe** for all of it — a recorded mask is one boolean per tile of a 100×100 array, dearer to test than the `getolcut` it would save. Only a `ResOverlay` is ever recorded (`Grid.getol` matches `ols[i].get().layer(ResOverlay.class)`), so for every other `OverlayInfo` the locals decide alone and no grid is touched |
| **The once-per-tick walk of the whole map** | `MCache.ctick(dt)` — every loaded `Grid`'s `tick`, then every `LocalOverlay`'s. Called from `Glob.ctick`, i.e. the `stick` phase, so it runs **before** `ui.tick` reaches `MapView.oltick` in the same frame and long before anything is drawn ([boot-and-loop.md](boot-and-loop.md)). It is the one place per tick that reaches every `Grid.Cut` on the thread that builds them |

| The shader | Where |
|---|---|
| **A state above the materials reaches them** | A program is compiled from the **composed `Pipe`** of the slot being drawn, not per `Material` — so every `State` in that composition contributes its `ShaderMacro`, and one installed at a subtree's root is compiled into the program of every material below it. `haven.ColorMask` and `render.BaseColor` are the two precedents |
| **The recipe** | `extends State` with a `State.Slot`, a `Uniform` reading it, and `shader()` returning `prog -> FragColor.fragcol(prog.fctx).mod(fn, order)` — `fn` is a `UnaryOperator<Expression>` over the fragment colour, built with `haven.render.sl.Cons` (`pick`, `dot`, `mix`, `vec3`, `vec4`). `BaseColor` mods at order **0**, `ColorMask` at **100**; nothing ships above that |
| What it costs | one program, compiled on the first frame that needs it. No geometry, no draw, no pass. Keeping the amount in the `Uniform` rather than the macro means changing it recompiles nothing either — push a new instance with `Slot.ostate` |

**Gotcha — what a re-registration invalidates, and what still means the whole grid.** `MCache.add` and
`MCache.remove` are the only way to make a changed **mask** take effect, and the wrong way to change
anything else: a colour, a shader amount or a turn inside the tiles already covered reaches the screen as
a state push and costs no terrain work. Fork: the pair bumps that `OverlayInfo`'s own sequence
(`MCache.olseqs`, over `olbump`, read back as `olseq(id)`), and `Grid.getolcut` compares it against the
`Cut.olstamp` it built that id at — so laying the Nth overlay re-cuts its own cuts and leaves the other
N-1 standing, where upstream's single `MCache.olseq` had `getolcut` dispose and clear every entry of
every cut in the grid. Two bumps do still mean everything and keep that grid-wide path: the `mapdata2`
fill, where the server replaced a grid's recorded masks, and `Grid`'s own `olseq = -1` when a cut's ground
mesh was rebuilt and its overlays must be re-laid over new vertices. ⚠️ **`remove` disposes nothing by
itself** — `getolcut` is never asked for that id again, so the id goes on `MCache.oldrops` and the meshes
go one tick later, in the drain `MCache.ctick` runs over every loaded `Grid` (`Grid.dropols`), on the
thread that builds cuts.

**Gotcha — an overlay nobody tagged is never added, and nothing says so.** `oltick` adds a scene `Overlay`
only for an `OverlayInfo` one of whose `tags()` is a key of `oltags`, and there is no error and no log on a
miss — an overlay with an unknown tag simply never appears. `{"show": 1}` is seeded in `MapView`'s own
initialiser and nothing ever decrements it, so `show` is the tag for an overlay that is always visible.
`getols` is asked for `terrain.area.mul(MCache.cutsz)` besides, so one outside the drawn terrain
([terrain-raster.md](terrain-raster.md)) is not considered at all.

**Gotcha — `MapView.Overlay` reads `id.mat()` exactly once.** `added` is the only place upstream calls it,
and a uniform is baked into the compiled slot rather than re-read ([render-gl.md](render-gl.md)), so
mutating the `Material` an `OverlayInfo` hands back propagates nothing whatever. The new material has to go
through the slot `added` put the old one in. Fork: `MapView.Overlay.rematerial` keeps that slot and
`cstate`s a fresh `id.mat()` into it, reached by `MapView.rematerial(MCache.OverlayInfo)` (`// addon:`).

**Gotcha — map space is world space with `y` negated.** `Gob.Placed.Placement` copies the gob's coordinate
and does `rc.y = -rc.y` before building the `"gobx"` `Location`, and `Homo3D.fragmapv` is that space carried
to the fragment stage. So geometry, a half-plane or any other test built from world coordinates and
compared against `fragmapv` is mirrored about the x axis, and the mirror is invisible on anything
symmetric — which is every rectangle a first test tends to use.

**Gotcha — a mask meaning "everywhere" is a fixed, enormous rectangle.** Nothing iterates it —
`RectOverlay.fill` walks only its overlap with the cut being built — so it is laid once per cut it covers
and never again, while a rectangle tracked to the camera pays a tile-laying pass for every cut its mask
reaches, every time it moves: `RectOverlay.update` bumps that rectangle's own sequence, which is every one
of its cuts out of date at once. Where the mask is empty for a cut `makeol` answers **null**, which is a
legal `RenderTree` child (`TreeSlot` calls `added` only on a node that is there) and draws nothing.

**Gotcha — a cut can cache an overlay mesh with no outline, and keep it.** `Grid.getolcut` commits the base
mesh and its `Cut.olstamp` *before* it calls `makeolol`, so a `Loading` out of that second call leaves the
pair half built. The throw is ordinary rather than rare: `makeolol` reads its mask over
`Area.sized(ul, sz).margin(1)`, one tile into the neighbouring grid, and `MCache.getol` → `getgrid` throws
`LoadingMap` for a neighbour not streamed in yet — which is what walking into new ground is. The retry
finds the stamp current, skips the build, and `getololcut` answers `null` for that cut until something
invalidates it. **Committing the stamp after both instead is worse, not better:** the pair is then rebuilt
for every border cut of every overlay on every tick until the neighbour arrives. And that `Loading` is the
one reason `MCache.getol`'s multi-grid branch is reached at all — a fresh `boolean[10000]` and a 100×100
scan per neighbouring grid, per cut, per overlay, which is where an overlay's real cost is.

## Textures and materials (no `.res` required)

| What | Where |
|---|---|
| **`.res` images are just PNG** (the substrate) | `Resource.readimage` `ImageIO.read(fp)` → `Resource.Image` `new TexI(img)` |
| PNG → GPU texture (no `.res`) | `new TexI(BufferedImage)`; GPU upload lazy/thread-safe in `TexI.st()` → a `ColorTex` |
| **2D screen blit** | `GOut.image(Tex,Coord)` / scaled `(Tex,Coord,Coord)` / `aimage(Tex,Coord,ax,ay)` |
| Bare-`TexI` blit precedent (no `.res`) | `SpeakerIcon.GLYPH` = `new TexI(img)` drawn via `g.image(...)` |
| **World-anchored 2D blit (billboard)** | `SpeakerIcon` `extends GAttrib implements RenderTree.Node, PView.Render2D`; `draw(GOut,Pipe)` projects the anchor with `Homo3D.obj2clip` (the `w > 0` test above, then `toview`) and blits it with `g.image(...)` |
| **World textured quad** | a resource-free `Sprite`: `quadVerts` (upright x=0 plane, z 0→h, y ±w/2, t-inverted) → `Model(TRIANGLE_STRIP,VertexArray,null,0,4)`, `Layout` of `Homo3D.vertex` VEC3+`Tex2D.texc` VEC2 (world-vertex xf from the scene's `Homo3D.state`, PView) |
| Texture material (the working recipe) | `new Material(tr.draw, tr.clip, Material.nofacecull).apply(model)` → a `RenderTree.Node` — `tr` = a `TexRender` over the `TexI` sampler (`tex.st().data`); `TexDraw` samples + `TexClip` **alpha-discards** (the `.res` `$tex` matpart, `clip=true`) → SOLID, not blended; double-sided, unlit. *(NOT `ColorTex`+`FragColor.blend` — that translucent-overlay recipe reads as a 1% ghost.)* |
| **Resource-free `Drawable`** | `SprDrawable(Gob, Sprite.Mill)`, `getres()==null` (`Mill` resolves the owner cycle); same `Drawable` attr slot as `ResDrawable` |
| **Lighting** | material state = `Light.PhongLight` (frag; ctor takes emi/amb/dif/spc/shine; `defamb/defdif/defspc` neutral defaults) → the `Phong` shader multiplies the scene's `Lighting.lights`/`Light.LightList` (applied at the PView scene root — `PView.lights`) into the fragment. Normals need the **inverse-transpose** of `basis·node` (`Matrix4f.invert`/`transpose`/`trim3`). **sRGB = no-op** (`Texture.srgb` left `false`, like all game textures) |
| glTF → geometry | per primitive → a `Model` (POSITION→`Homo3D.vertex`, NORMAL→`Homo3D.normal` [VEC3 `"normal"`, shaded to eye space as `mat3(cam)·mat3(wxf)·objn`], TEXCOORD_0→`Tex2D.texc`, indices via `Model.Indices`); baseColor `TexI.st()` + `BaseColor` factor → `Material.apply` |

## Render to texture (drawing a UI subtree off screen)

| What | Where |
|---|---|
| **The whole-client precedent** | `Streamer.StreamerLoop` / `HeadlessClient.HeadlessLoop` override `UILoop.basestate` to `prep` a `FragColor`+`DepthBuffer` over `Texture2D` images — the ENTIRE UI then draws into textures with no other change |
| Colour target | `new Texture2D(sz, Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null)`; `.image(0)` → `FragColor` on a fresh `BufPipe` |
| **Colour-only FBO is legal** | `RenderedNormals` and `PView` prep a `FragColor` with **no** `DepthBuffer` — a 2D pass needs no depth attachment |
| The 2D pipe over it | `prep(FragColor.blend(mode))` + `States.Viewport(area)` + `Ortho2D(area)` + `FrameInfo`, then `out.clear(base, FragColor.fragcol, colour)` |
| Drawing onto it | `new GOut(Render, Pipe, Coord)` — **public**, the very ctor `UILoop.display` uses; hand it to any `Widget.draw` |
| Sampling it back | `Texture2D.sampler()` → `new TexRender(Sampler2D)` → `tr.draw`/`tr.clip` in a `Material`, as for any texture |

**Gotcha — a render target is v-FLIPPED against an uploaded image.** `Ortho2D` has `k[1] = -2/h`
, so widget `y=0` (the top) maps to NDC `+1`, the top of the viewport,
i.e. `v ≈ 1`; a framebuffer's first texel row is its BOTTOM. A `TexI` is the opposite — its first row is the
image's TOP. So a quad whose `t` is inverted to stand a PNG upright (`SpriteQuad.quadVerts`) stands a render
target on its head, and every re-use of such geometry must re-derive `t` from the projection.

**Gotcha — ordering is a position in the command stream, not a frame.** `UILoop.display` builds ONE `Render`
and appends everything to it, so a pass issued before `ui.draw(g)` (inside which the 3D scene draws, the MapView
being a widget) writes its texture ahead of the commands that sample it **in the same frame**. There is no
double-buffering to reason about and no staleness to accept.
