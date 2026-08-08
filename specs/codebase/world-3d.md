# Subsystem: 3D scene & rendering (world entities, sprites, textures, glTF)

> The `MapView` scene, client-only gobs, the render tree and the texture/material path. Line
> numbers are indicative; the **class + method/field name is the stable anchor**. Max 70 lines.

## Client-only world entities

| What | Where |
|---|---|
| **Client-only world entity (template)** | [`MapView.Plob extends Gob`](src/haven/MapView.java:1779) — `super(glob, rc)` + `setattr(new ResDrawable(...))` + `basic.add(placed)`; `move(Coord2d,double)`; `slot.remove()` |
| Gob construction (no server id) | [`Gob(Glob,Coord2d)`](src/haven/Gob.java:441) / [`Gob(Glob,Coord2d,long)`](src/haven/Gob.java:433); `Gob implements RenderTree.Node, Sprite.Owner` ([:33](src/haven/Gob.java:33)) |
| Visual attr (`.res`-backed) | [`ResDrawable`](src/haven/ResDrawable.java:79) (`Gob.setattr`) |
| Add/remove in the 3D scene | [`MapView.addClientGob`](src/haven/MapView.java:1887) (`// addon:` seam) + `Gob.placed`; transform [`Gob.Placed`](src/haven/Gob.java:846). **A client gob is in no `OCache`, so nothing but its placer removes it** — and [`Placed.autotick`](src/haven/Gob.java:946) *catches* the `Loading` a `new Placement()` throws when the tile under it is unloaded (`getmapstate` → `glob.map.tiler`) and keeps `cur`, so it goes on drawing at its last placement rather than vanishing with the ground |
| Screen ↔ world | screen → world (ground raycast) [`MapView.Maptest`](src/haven/MapView.java:1810) (`Plob.Adjust.hit(Coord pc, Coord2d mc)`); world → screen `MapView.screenxf`, or per NODE [`Homo3D.obj2clip(objc, state)`](src/haven/render/Homo3D.java:190) → [`HomoCoord4f.toview(area)`](src/haven/HomoCoord4f.java:118) — object space through that slot's own chain (placement, facing, `obstate` scale). ⚠️ `toview` does the projective divide unguarded: **check `w > 0` first**, or a point behind the eye answers with a plausible number on the wrong side. The `state` `Pipe` exists only inside a pass, so the way to ask is a [`PView.Render2D`](src/haven/PView.java:406) — which may draw nothing and exist purely to be handed it (fork: `SurfaceDrawable`, 044.4). ⚠️ **Nothing is culled anywhere in this path**: [`ScreenList.draw`](src/haven/PView.java:417) walks every registered slot every frame and no gob is tested against a frustum — the GPU clips. So off-screen is not free, and a feature that needs it computes it itself (044.7 reads it off these same corners) |
| **Placement snapping — placegrid/placeangle** | [`PlobAdjust`](src/haven/MapView.java:1740) / [`StdPlace`](src/haven/MapView.java:1746) (position [:1749](src/haven/MapView.java:1749), rotation [:1764](src/haven/MapView.java:1764)); **public** [`plobpgran`/`plobagran`](src/haven/MapView.java:57); `:placegrid`/`:placeangle` cmds ([:2391](src/haven/MapView.java:2391)) |
| Pick pass (clickable entities, drag handles) | [`ClickMap`](src/haven/MapView.java:507), [`MapClick extends Clickable`](src/haven/MapView.java:911), [`Clicklist`](src/haven/MapView.java:1155), [`ClickLocation`](src/haven/MapView.java:1409), [`Gob.GobClick`](src/haven/Gob.java:673) |
| **Click dispatch ← intercept point** | [`MapView.Hittest`](src/haven/MapView.java:1962) resolves pick → [`Click.hit`](src/haven/MapView.java:2017) ends in `wdgmsg("click", …)` ([:2023](src/haven/MapView.java:2023)); the voice feature also hooks here ([:2018-2019](src/haven/MapView.java:2018)). ⚠️ **The pick is ASYNCHRONOUS** (`env.submit`, resolved on the render thread's callback), so it cannot serve anything that must answer inside the event — a press that takes a grab, for one. Fork: four `// addon:` hooks at the top of `mousedown`/`mouseup`/`mousemove`/`mousewheel` ([:2132](src/haven/MapView.java:2132)+) run a synchronous test first and fall through when it misses |
| Follow-a-gob motion | subclass [`Moving`](src/haven/Moving.java) with `getc()` = [`target.getc()`](src/haven/Gob.java:584)`.add(offset)`, re-resolved via [`OCache.getgob`](src/haven/OCache.java:199) each frame; the render tree's per-frame [`Placed.autotick`](src/haven/Gob.java:942) tracks it. NOT a [`Following`](src/haven/Following.java:32) subclass (that steals facing) |
| **Where a gob is placed and how it is TURNED — the per-drawable hook** | [`Gob.Placer`](src/haven/Gob.java:159) (`getc(rc,a)` + `getr(rc,a)`→`Matrix4f`); [`Gob.placer()`](src/haven/Gob.java:577) asks the `Drawable` first — [`Drawable.placer()`](src/haven/Drawable.java:50), **public, overridable**, defaulting to `gob.glob.map.trnplace` ([`MCache.trnplace`/`mapplace`](src/haven/MCache.java:161) = [`Gob.DefaultPlace`](src/haven/Gob.java:172), `getr` = z-rotation by `-a`). [`Placed.autotick`](src/haven/Gob.java:945) rebuilds a `Placement` **every frame** and re-reads `getr`, pushing `slot.ostate` only when it differs ([`Matrix4f.equals`](src/haven/Matrix4f.java:75)) ⇒ **a per-frame rotation costs an override, not a tick loop**. Applied as `Location("gobx")` (translate, y-negated) then `Location("gob")` (this rotation), with `GhostGob.obstate`'s scale below both. Ignore facing entirely: [`Location.nullrot`](src/haven/render/Location.java:169) |
| **The camera, outside a render pass** | [`MapView.camera`](src/haven/MapView.java:51) → [`MapView.Camera`](src/haven/MapView.java:74)'s `protected view` ([`haven.render.Camera extends Transform`](src/haven/render/Camera.java:31)) + `proj`; `camera.view.fin(Matrix4f.id)` is render→eye, so [`invert()`](src/haven/Matrix4f.java:175) (null when singular) gives the camera's axes as its first three **columns** (`m[col*4+row]`) and the eye point as the fourth. Fork: `MapView.camview()` (`// addon:`) — `Placer.getr` has no `Pipe`, so `Homo3D.obj2view` is not available there |

## Ground overlays — who decides one is drawn

| What | Where |
|---|---|
| **The ref count** | [`MapView.oltags`](src/haven/MapView.java:825) — a **multiset** `Map<String,Integer>` (seeded `{"show":1}`); [`enol`/`disol`/`visol`](src/haven/MapView.java:531) are `+1` / `-1`-and-drop / `containsKey`. All three `synchronized(oltags)` |
| Who counts | the user's three [`MenuCheckBox`es](src/haven/GameUI.java:1553) via the private `GameUI.MapMenu.toggleol` (`cplot`, `vlg`, **`prov`**), and the server's [`flashol`/`unflashol`](src/haven/MapView.java:1914) (hover a claim → on for `tm` seconds, then decremented) |
| What it gates | [`MapView.oltick`](src/haven/MapView.java:828): for each `OverlayInfo` from `glob.map.getols(...)`, visible iff **any** of its [`ResOverlay.tags()`](src/haven/MCache.java:214) is in `oltags`; adds/drops the scene `Overlay` accordingly |
| The **other** side | [`MapWnd.overlays`](src/haven/MapWnd.java:55) — a plain `CopyOnWriteArraySet<String>` fed by [`MapWnd.toggleol`](src/haven/MapWnd.java:149) from its own checkbox with **`realm`**, read by `MapWnd.View.drawgrid` to blit `DisplayGrid.olimg(tag)` from the RECORDED masks ([mapfile.md](mapfile.md), [minimap.md](minimap.md)) |

**Gotcha — two vocabularies for one feature.** Provinces are `prov` in the world and `realm` on the map; no
tag reaches both. And because `oltags` is a *count* with several owners, nothing can turn an overlay off —
only stop asking; a caller that "sets" it must instead take and release exactly one reference.

## Textures & materials (no `.res` required)

| What | Where |
|---|---|
| **`.res` images are just PNG** (the substrate) | [`Resource.readimage`](src/haven/Resource.java:1061) `ImageIO.read(fp)` → [`Resource.Image` `new TexI(img)`](src/haven/Resource.java:1182) |
| PNG → GPU texture (no `.res`) | [`new TexI(BufferedImage)`](src/haven/TexI.java:52); GPU upload lazy/thread-safe in [`TexI.st()`](src/haven/TexI.java:59) → a [`ColorTex`](src/haven/render/ColorTex.java:34) |
| **2D screen blit** | [`GOut.image(Tex,Coord)`](src/haven/GOut.java:97) / [scaled `(Tex,Coord,Coord)`](src/haven/GOut.java:117) / [`aimage(Tex,Coord,ax,ay)`](src/haven/GOut.java:107) |
| Bare-`TexI` blit precedent (no `.res`) | [`SpeakerIcon.GLYPH`](src/haven/SpeakerIcon.java:51) = `new TexI(img)` drawn via `g.image(...)` ([:87](src/haven/SpeakerIcon.java:87)) |
| **World-anchored 2D blit (billboard)** | [`SpeakerIcon`](src/haven/SpeakerIcon.java:44) `extends GAttrib implements RenderTree.Node, PView.Render2D` ([:406](src/haven/PView.java:406)); `draw(GOut,Pipe)` projects via [`Homo3D.obj2view`](src/haven/render/Homo3D.java:201) then `g.image(...)` |
| **World textured quad** | a resource-free `Sprite`: `quadVerts` (upright x=0 plane, z 0→h, y ±w/2, t-inverted) → [`Model(TRIANGLE_STRIP,VertexArray,null,0,4)`](src/haven/render/Model.java:45), [`Layout`](src/haven/render/VertexArray.java:65) of [`Homo3D.vertex` VEC3](src/haven/render/Homo3D.java:41)+[`Tex2D.texc` VEC2](src/haven/render/Tex2D.java:36) (world-vertex xf from the scene's `Homo3D.state`, [PView:385](src/haven/PView.java:385)) |
| Texture material (the working recipe) | `new Material(tr.draw, tr.clip, Material.nofacecull)`[`.apply(model)`](src/haven/Material.java:165) → a `RenderTree.Node` — `tr` = a [`TexRender`](src/haven/TexRender.java:34) over the `TexI` sampler (`tex.st().data`); [`TexDraw`](src/haven/TexRender.java:73) samples + [`TexClip`](src/haven/TexRender.java:97) **alpha-discards** (the `.res` [`$tex`](src/haven/TexRender.java:138) matpart, `clip=true`) → SOLID, not blended; double-sided, unlit. *(NOT `ColorTex`+`FragColor.blend` — that translucent-overlay recipe reads as a 1% ghost.)* |
| **Resource-free `Drawable`** | [`SprDrawable(Gob, Sprite.Mill)`](src/haven/SprDrawable.java:35), [`getres()==null`](src/haven/SprDrawable.java:63) (`Mill` resolves the owner cycle); same [`Drawable`](src/haven/Drawable.java:31) attr slot as `ResDrawable` |
| **Lighting** | material state = [`Light.PhongLight`](src/haven/Light.java:145) (frag; ctor takes emi/amb/dif/spc/shine; `defamb/defdif/defspc` neutral defaults) → the [`Phong`](src/haven/render/Phong.java:35) shader multiplies the scene's [`Lighting.lights`](src/haven/render/Lighting.java:38)/[`Light.LightList`](src/haven/Light.java:81) (applied at the PView scene root — [`PView.lights`](src/haven/PView.java:40)) into the fragment. Normals need the **inverse-transpose** of `basis·node` ([`Matrix4f.invert`](src/haven/Matrix4f.java:175)/[`transpose`](src/haven/Matrix4f.java:149)/[`trim3`](src/haven/Matrix4f.java:159)). **sRGB = no-op** ([`Texture.srgb`](src/haven/render/Texture.java:38) left `false`, like all game textures) |
| glTF → geometry | per primitive → a `Model` (POSITION→[`Homo3D.vertex`](src/haven/render/Homo3D.java:41), NORMAL→[`Homo3D.normal`](src/haven/render/Homo3D.java:42) [VEC3 `"normal"`, shaded to eye space as `mat3(cam)·mat3(wxf)·objn`], TEXCOORD_0→[`Tex2D.texc`](src/haven/render/Tex2D.java:36), indices via `Model.Indices`); baseColor `TexI.st()` + `BaseColor` factor → `Material.apply` |

## Render to texture (drawing a UI subtree off screen)

| What | Where |
|---|---|
| **The whole-client precedent** | [`Streamer.StreamerLoop`](src/haven/Streamer.java:85) / [`HeadlessClient.HeadlessLoop`](src/haven/HeadlessClient.java:46) override [`UILoop.basestate`](src/haven/UILoop.java:284) to `prep` a `FragColor`+`DepthBuffer` over `Texture2D` images — the ENTIRE UI then draws into textures with no other change |
| Colour target | `new Texture2D(sz, Usage.STATIC, new VectorFormat(4, NumberFormat.UNORM8), null)`; [`.image(0)`](src/haven/render/Texture2D.java:59) → [`FragColor`](src/haven/render/FragColor.java) on a fresh [`BufPipe`](src/haven/render/BufPipe.java) |
| **Colour-only FBO is legal** | [`RenderedNormals`](src/haven/RenderedNormals.java:90) and [`PView`](src/haven/PView.java:241) prep a `FragColor` with **no** `DepthBuffer` — a 2D pass needs no depth attachment |
| The 2D pipe over it | `prep(FragColor.blend(mode))` + [`States.Viewport(area)`](src/haven/render/States.java:44) + [`Ortho2D(area)`](src/haven/render/Ortho2D.java:48) + [`FrameInfo`](src/haven/render/FrameInfo.java:32), then `out.clear(base, FragColor.fragcol, colour)` |
| Drawing onto it | [`new GOut(Render, Pipe, Coord)`](src/haven/GOut.java:57) — **public**, the very ctor `UILoop.display` uses; hand it to any `Widget.draw` |
| Sampling it back | [`Texture2D.sampler()`](src/haven/render/Texture2D.java:101) → [`new TexRender(Sampler2D)`](src/haven/TexRender.java:40) → `tr.draw`/`tr.clip` in a `Material`, as for any texture |

**Gotcha — a render target is v-FLIPPED against an uploaded image.** `Ortho2D` has `k[1] = -2/h`
([:44](src/haven/render/Ortho2D.java:44)), so widget `y=0` (the top) maps to NDC `+1`, the top of the viewport,
i.e. `v ≈ 1`; a framebuffer's first texel row is its BOTTOM. A `TexI` is the opposite — its first row is the
image's TOP. So a quad whose `t` is inverted to stand a PNG upright (`SpriteQuad.quadVerts`) stands a render
target on its head, and every re-use of such geometry must re-derive `t` from the projection.

**Gotcha — ordering is a position in the command stream, not a frame.** `UILoop.display` builds ONE `Render`
and appends everything to it, so a pass issued before `ui.draw(g)` (inside which the 3D scene draws, the MapView
being a widget) writes its texture ahead of the commands that sample it **in the same frame**. There is no
double-buffering to reason about and no staleness to accept.
