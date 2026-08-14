# The 3D world: scene, sprites, textures and glTF

> The `MapView` scene, client-only gobs, the render tree and the texture/material path. Line
> numbers are indicative;

## Client-only world entities

| What | Where |
|---|---|
| **Client-only world entity (template)** | `MapView.Plob extends Gob` — `super(glob, rc)` + `setattr(new ResDrawable(...))` + `basic.add(placed)`; `move(Coord2d,double)`; `slot.remove()` |
| Gob construction (no server id) | `Gob(Glob,Coord2d)` / `Gob(Glob,Coord2d,long)`; `Gob implements RenderTree.Node, Sprite.Owner` |
| Visual attr (`.res`-backed) | `ResDrawable` (`Gob.setattr`) |
| Add/remove in the 3D scene | `MapView.addClientGob` (`// addon:` seam) + `Gob.placed`; transform `Gob.Placed`. **A client gob is in no `OCache`, so nothing but its placer removes it** — and `Placed.autotick` *catches* the `Loading` a `new Placement()` throws when the tile under it is unloaded (`getmapstate` → `glob.map.tiler`) and keeps `cur`, so it goes on drawing at its last placement rather than vanishing with the ground |
| Screen ↔ world | screen → world (ground raycast) `MapView.Maptest` (`Plob.Adjust.hit(Coord pc, Coord2d mc)`); world → screen `MapView.screenxf`, or per NODE `Homo3D.obj2clip(objc, state)` → `HomoCoord4f.toview(area)` — object space through that slot's own chain (placement, facing, `obstate` scale). ⚠️ `toview` does the projective divide unguarded: **check `w > 0` first**, or a point behind the eye answers with a plausible number on the wrong side. The `state` `Pipe` exists only inside a pass, so the way to ask is a `PView.Render2D` — which may draw nothing and exist purely to be handed it (fork: `SurfaceDrawable`). ⚠️ **Nothing is culled anywhere in this path**: `ScreenList.draw` walks every registered slot every frame and no gob is tested against a frustum — the GPU clips. So off-screen is not free, and a feature that needs it computes it itself |
| **Placement snapping — placegrid/placeangle** | `PlobAdjust` / `StdPlace` (position, rotation); **public** `plobpgran`/`plobagran`; `:placegrid`/`:placeangle` cmds |
| Pick pass (clickable entities, drag handles) | `ClickMap`, `MapClick extends Clickable`, `Clicklist`, `ClickLocation`, `Gob.GobClick` |
| **Click dispatch ← intercept point** | `MapView.Hittest` resolves pick → `Click.hit` ends in `wdgmsg("click", …)`; the voice feature also hooks here (-2019). ⚠️ **The pick is ASYNCHRONOUS** (`env.submit`, resolved on the render thread's callback), so it cannot serve anything that must answer inside the event — a press that takes a grab, for one. Fork: four `// addon:` hooks at the top of `mousedown`/`mouseup`/`mousemove`/`mousewheel` (+) run a synchronous test first and fall through when it misses |
| Follow-a-gob motion | subclass `Moving` with `getc()` = `target.getc()``.add(offset)`, re-resolved via `OCache.getgob` each frame; the render tree's per-frame `Placed.autotick` tracks it. NOT a `Following` subclass (that steals facing) |
| **Where a gob is placed and how it is TURNED — the per-drawable hook** | `Gob.Placer` (`getc(rc,a)` + `getr(rc,a)`→`Matrix4f`); `Gob.placer()` asks the `Drawable` first — `Drawable.placer()`, **public, overridable**, defaulting to `gob.glob.map.trnplace` (`MCache.trnplace`/`mapplace` = `Gob.DefaultPlace`, `getr` = z-rotation by `-a`). `Placed.autotick` rebuilds a `Placement` **every frame** and re-reads `getr`, pushing `slot.ostate` only when it differs (`Matrix4f.equals`) ⇒ **a per-frame rotation costs an override, not a tick loop**. Applied as `Location("gobx")` (translate, y-negated) then `Location("gob")` (this rotation), with `GhostGob.obstate`'s scale below both. Ignore facing entirely: `Location.nullrot`. **Per-gob render state on a NATIVE gob — the `SetupMod` seam:** `Gob.SetupMod` is an interface a `GAttrib` may also implement; `gobstate()`/`placestate()` contribute a `Pipe.Op` to the gob's own child slot, i.e. **below** the `"gobx"` translate and `"gob"` rotate of the row above ⇒ **T·R·S**, the same level `GhostGob.obstate` scales at. `Gob.setattr` registers it in the private `setupmods`; `Gob.ctick` calls the private `updstate()`, which rebuilds `GobState` from that list and pushes `slot.ostate` **only** when `Utils.eq(mods)` differs — so a change lands next tick and costs no new seam. ⚠️ **`Location` has no `equals`**: a freshly minted op each tick compares unequal every tick and re-pushes forever — **cache the op per value**. `Pipe.Op.compose` drops nulls and returns the lone survivor **itself** (only `Composed.equals` is `Arrays.equals`), so with one mod the comparison is identity. Copy `GobHealth`; an attrib that is **also** a `RenderTree.Node` is the only shape for which `setattr` can throw `Loading` |
| **The camera, outside a render pass** | `MapView.camera` → `MapView.Camera`'s `protected view` (`haven.render.Camera extends Transform`) + `proj`; `camera.view.fin(Matrix4f.id)` is render→eye, so `invert()` (null when singular) gives the camera's axes as its first three **columns** (`m[col*4+row]`) and the eye point as the fourth. Fork: `MapView.camview()` (`// addon:`) — `Placer.getr` has no `Pipe`, so `Homo3D.obj2view` is not available there |
| **Which ground is DRAWN — the terrain display list** | `MapView.MapRaster` (private inner) → the public `Terrain` and its `Grid main`/`flavobjs`. `MapRaster.tick` sets `area = Area(cc - view, cc + view + 1)` around the player's CUT (`getcc().floor(tilesz).div(MCache.cutsz)`, `view = 2`, `cutsz = 25×25` tiles) ⇒ the drawn terrain is only **~50–75 tiles** across from you; `Grid.tick` then adds/removes one scene slot per cut, keyed in `Grid.cuts`, which therefore holds a cut **exactly while that cut's mesh is in the scene**. ⚠️ That is a GRID smaller than `MCache.grids`: map data is dropped only when the server says so (`invalblob` type 1 → `MCache.trim`, which has **no caller inside the client**), so "the grid is loaded" is true well past the visible edge — test `cuts` when the claim is about what the player SEES. Fork: `MapView.grounddrawn(Coord2d)` (`// addon:`) + a `groundChanged()` tap at `Grid.tick`'s two mutation points, which is how a client-only gob stops drawing over the void |

**Gotcha — the camera's frustum is two traps, and neither is a draw-distance setting.** `Camera.resized`
builds `Projection.frustum(-field, field, …, 1, 2000)`: the far plane is fixed at **2000**, so a camera
pulled back past that clips the scene away entirely, ground included, and no rendering option reaches it —
a camera meant to pull further sets its own projection per tick. The second is subtler: `makefrustum`'s
scale term is `2*near/(right-left)`, so `field` is a **size given at the near plane**, not an angle, and
the field of view is `field/near`. Move the near plane (to buy depth precision at distance) with `field`
held at the shipped `0.5f` and the view narrows by exactly the factor the distance widened it — the
camera moves and the image does not, which reads as a zoom that is stuck.

## The camera registry — how one is named, built and remembered

`MapView.camera` is a plain public field holding one `MapView.Camera`, and every camera is a
**non-static inner class** of `MapView` — which is why building one takes the enclosing instance
(`mv.new SOrthoCam()`) and why the registry reflects rather than calls a factory.

| What | Where |
|---|---|
| **The registry** | `MapView.camtypes`, private static, name → `Class<? extends Camera>`. Each camera class is followed by its own `static {camtypes.put(…)}` block, so **a camera class nobody registered is unreachable**: `OrthoCam` is real and has no name, `SOrthoCam` is `ortho`. Fork: it is a `LinkedHashMap`, so its key order is the order those blocks appear in the file — Java runs static initialisers in source order, which is what makes that order something a caller may rely on |
| The names the client has | `follow` (`FollowCam`), `worse` (`SimpleCam`), `bad` (`FreeCam`), `ortho` (`SOrthoCam`), `rts` (`RTSCam`) — in that order |
| Reading the names from outside | Fork: `MapView.camnames()`, public static, a fresh `List` of the registry's keys in that order. The registry itself is private, so this is the only way anything else learns which cameras exist, and both the selector below and `setcam`'s own refusal read it rather than keeping a second list |
| Building one | `MapView.makecam(Class, String...)` reflects for a `(MapView, String[])` constructor, then for `(MapView)`; with neither it throws naming the class. The `String[]` is the console's trailing words, and a camera that wants none simply declares the shorter constructor |
| **The two preferences** | `defcam` is the name, `Utils.getpref`/`setpref`; `camargs` is the argument array, serialized whole through `Utils.getprefb`/`setprefb` + `Utils.serialize`/`deserialize`. They are written together, by the one writer below |
| Restoring at construction | `MapView.restorecam()`, called **in the `camera` field initialiser** — so it runs before the widget is attached and can reach nothing but the prefs |
| **Installing one — the one writer** | Fork: `MapView.setcam(String name, String... args)`, public — it resolves the name, `makecam`s it into `camera` and writes both prefs, and throws an unchecked `IllegalArgumentException` on a name the registry does not have, naming what it got and every name it does. Everything that chooses a camera goes through it, so a camera chosen one way reads back the same through the others |
| Which one is INSTALLED | Fork: `MapView.camname()` reverse-looks the live `camera`'s class up in the registry, `null` for a camera nobody registered. It is **not** the `defcam` pref: the RTS mode swaps a camera in without writing prefs, so the pref answers what the next session will come up on and this answers what is on screen now |
| Choosing one by hand | the `cam` command in `MapView`'s `cmdmap` (`Console.Directory`) — `:cam <name> [args…]`, whose whole body is `setcam`. `findcmds` exposes the map, so the command lives on the map view rather than on the client, and `ConsoleHost.done` catches what `setcam` throws and prints it |
| Choosing one from Options | Fork: `OptWnd.CameraPanel.CamSelector`, an `SDropBox<String, Widget>` whose items **are** `camnames()` — the registry's keys carry no display label of their own, so a camera has one spelling in the dropdown, at the console and in the refusals |
| The camera's own input | `Camera.keydown`, `click`, `drag`, `release`, `wheel` — all no-ops on the base class. `MapView.keydown` runs the fork's dispatch **before** `camera.keydown`, so a camera never sees a key the client has already claimed |

**Gotcha — an unknown `defcam` is silent, not an error.** `restorecam` returns `new SOrthoCam()` both
when `camtypes.get` misses and when `makecam` throws anything at all. A preference naming a camera the
client does not have therefore comes up on `ortho` with nothing said, and the pref keeps its dead
value until something writes it — so a camera that loses its name reads as "my setting was ignored",
never as a failure anyone can see.

## Ground overlays — who decides one is drawn

| What | Where |
|---|---|
| **The ref count** | `MapView.oltags` — a **multiset** `Map<String,Integer>` (seeded `{"show":1}`); `enol`/`disol`/`visol` are `+1` / `-1`-and-drop / `containsKey`. All three `synchronized(oltags)` |
| Who counts | the user's three `MenuCheckBox`es via the private `GameUI.MapMenu.toggleol` (`cplot`, `vlg`, **`prov`**), and the server's `flashol`/`unflashol` (hover a claim → on for `tm` seconds, then decremented) |
| What it gates | `MapView.oltick`: for each `OverlayInfo` from `glob.map.getols(...)`, visible iff **any** of its `ResOverlay.tags()` is in `oltags`; adds/drops the scene `Overlay` accordingly |
| The **other** side | `MapWnd.overlays` — a plain `CopyOnWriteArraySet<String>` fed by `MapWnd.toggleol` from its own checkbox with **`realm`**, read by `MapWnd.View.drawgrid` to blit `DisplayGrid.olimg(tag)` from the RECORDED masks ([mapfile.md](mapfile.md), [minimap.md](minimap.md)) |

**Gotcha — two vocabularies, and a count.** Provinces are `prov` in the world and `realm` on the map; no tag
reaches both. `oltags` has several owners, so nothing turns an overlay off — a "set" takes and releases one ref.

## Textures and materials (no `.res` required)

| What | Where |
|---|---|
| **`.res` images are just PNG** (the substrate) | `Resource.readimage` `ImageIO.read(fp)` → `Resource.Image` `new TexI(img)` |
| PNG → GPU texture (no `.res`) | `new TexI(BufferedImage)`; GPU upload lazy/thread-safe in `TexI.st()` → a `ColorTex` |
| **2D screen blit** | `GOut.image(Tex,Coord)` / scaled `(Tex,Coord,Coord)` / `aimage(Tex,Coord,ax,ay)` |
| Bare-`TexI` blit precedent (no `.res`) | `SpeakerIcon.GLYPH` = `new TexI(img)` drawn via `g.image(...)` |
| **World-anchored 2D blit (billboard)** | `SpeakerIcon` `extends GAttrib implements RenderTree.Node, PView.Render2D`; `draw(GOut,Pipe)` projects via `Homo3D.obj2view` then `g.image(...)` |
| **World textured quad** | a resource-free `Sprite`: `quadVerts` (upright x=0 plane, z 0→h, y ±w/2, t-inverted) → `Model(TRIANGLE_STRIP,VertexArray,null,0,4)`, `Layout` of `Homo3D.vertex` VEC3+`Tex2D.texc` VEC2 (world-vertex xf from the scene's `Homo3D.state`, PView) |
| Texture material (the working recipe) | `new Material(tr.draw, tr.clip, Material.nofacecull)``.apply(model)` → a `RenderTree.Node` — `tr` = a `TexRender` over the `TexI` sampler (`tex.st().data`); `TexDraw` samples + `TexClip` **alpha-discards** (the `.res` `$tex` matpart, `clip=true`) → SOLID, not blended; double-sided, unlit. *(NOT `ColorTex`+`FragColor.blend` — that translucent-overlay recipe reads as a 1% ghost.)* |
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
