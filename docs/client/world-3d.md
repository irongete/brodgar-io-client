# The 3D world: scene, sprites, textures and glTF

> The `MapView` scene, client-only gobs, the render tree and the texture/material path. A gob's own
> session is [multi-session.md](multi-session.md).

## Client-only world entities

| What | Where |
|---|---|
| **Client-only world entity (template)** | `MapView.Plob extends Gob` — `super(glob, rc)` + `setattr(new ResDrawable(...))` + `basic.add(placed)`; `move(Coord2d,double)`; `slot.remove()` |
| Gob construction (no server id) | `Gob(Glob,Coord2d)` / `Gob(Glob,Coord2d,long)`; `Gob implements RenderTree.Node, Sprite.Owner` |
| Visual attr (`.res`-backed) | `ResDrawable` (`Gob.setattr`) |
| Add/remove in the 3D scene | `MapView.addClientGob` (`// addon:` seam) + `Gob.placed`; transform `Gob.Placed`. **A client gob is in no `OCache`, so nothing but its placer removes it** — and `Placed.autotick` *catches* the `Loading` a `new Placement()` throws when the tile under it is unloaded (`getmapstate` → `glob.map.tiler`) and keeps `cur`, so it goes on drawing at its last placement rather than vanishing with the ground |
| Screen ↔ world | screen → world (ground raycast) `MapView.Maptest` (`Plob.Adjust.hit(Coord pc, Coord2d mc)`); world → screen `MapView.screenxf`, or per NODE `Homo3D.obj2clip(objc, state)` → `HomoCoord4f.toview(area)` — object space through that slot's own chain (placement, facing, `obstate` scale). **Both speak MAP-VIEW-LOCAL DEVICE pixels**: `screenxf` ends in `toview(Area.sized(this.sz))`, and `Maptest(Coord pc)` hands `pc` to `checkmapclick` in that same box — so either side is a `Widget.rootpos()` from a root coordinate and a `UI.scale` from a design one, and the two corrections compose in opposite orders on the two sides. What comes *back* out of `Maptest.hit` is a `Coord2d` in **world units already** — `OCache.posres` is not in this path at all. ⚠️ `screenxf(Coord2d)` projects at the **player's** z, not the terrain height under the argument: it fills in `getcc().z` (`MapView.getcc` → `player().getc()`), so for a point on a slope it answers where that point would be *at the player's altitude*, and a screen→world raycast back does not return to it. ⚠️ `toview` does the projective divide unguarded: **check `w > 0` first**, or a point behind the eye answers with a plausible number on the wrong side. The `state` `Pipe` exists only inside a pass, so the way to ask is a `PView.Render2D` — which may draw nothing and exist purely to be handed it (fork: `SurfaceDrawable`). ⚠️ **Nothing is culled anywhere in this path**: `ScreenList.draw` walks every registered slot every frame and no gob is tested against a frustum — the GPU clips. So off-screen is not free, and a feature that needs it computes it itself |
| **Placement snapping — placegrid/placeangle** | `PlobAdjust` / `StdPlace` (position, rotation); **public** `plobpgran`/`plobagran`; `:placegrid`/`:placeangle` cmds. The position half is the static `MapView.placeSnap(Coord2d, modflags)`, which `StdPlace.adjust` calls (`// addon:` seam, so a gizmo snaps identically to a building). ⚠️ **The modifier goes the way round you do not expect**: with **no** SHIFT it is the coarse snap — `mc.floor(tilesz).mul(tilesz).add(tilesz.div(2))`, the **tile centre**, so a snapped world coordinate always sits at `tilesz/2` inside its tile; SHIFT picks the *finer* `plobpgran` sub-tile grid, and SHIFT with `plobpgran == 0` is free placement, returning `mc` untouched. Nothing here touches `OCache.posres` — it is world units in and world units out |
| Pick pass (clickable entities, drag handles) | `ClickMap`, `MapClick extends Clickable`, `Clicklist`, `ClickLocation`, `Gob.GobClick` |
| **Click dispatch ← intercept point** | `MapView.Hittest` resolves pick → `Click.hit` ends in `wdgmsg("click", …)`; the voice feature also hooks here (-2019). ⚠️ **The pick is ASYNCHRONOUS, on a thread of its own**: `env.submit` queues the readback, and the completion is run from `GLEnvironment.callbacks` by `GLEnvironment.cbthread` — the `"Render-query callback thread"`, started on demand and exiting after 5 idle seconds, which is neither the UI thread nor the render thread. So the pick cannot serve anything that must answer inside the event (a press that takes a grab, for one), and — the half that is easy to miss — **whatever the callback body touches, it touches off the UI thread, while the frame mutates it underneath**: `checkmapclick` resolves its cut and its coordinate there, so any state it reaches has to be *published* for that thread rather than merely reachable from it. Fork: four `// addon:` hooks at the top of `mousedown`/`mouseup`/`mousemove`/`mousewheel` (+) run a synchronous test first and fall through when it misses |
| Follow-a-gob motion | subclass `Moving` with `getc()` = `target.getc()``.add(offset)`, re-resolved via `OCache.getgob` each frame; the render tree's per-frame `Placed.autotick` tracks it. NOT a `Following` subclass (that steals facing) |
| **Where a gob is placed and how it is TURNED — the per-drawable hook** | `Gob.Placer` (`getc(rc,a)` + `getr(rc,a)`→`Matrix4f`); `Gob.placer()` asks the `Drawable` first — `Drawable.placer()`, **public, overridable**, defaulting to `gob.glob.map.trnplace` (`MCache.trnplace`/`mapplace` = `Gob.DefaultPlace`, `getr` = z-rotation by `-a`). `Placed.autotick` rebuilds a `Placement` **every frame** and re-reads `getr`, pushing `slot.ostate` only when it differs (`Matrix4f.equals`) ⇒ **a per-frame rotation costs an override, not a tick loop**. Applied as `Location("gobx")` (translate, y-negated) then `Location("gob")` (this rotation), with `GhostGob.obstate`'s scale below both. Ignore facing entirely: `Location.nullrot`. **Per-gob render state on a NATIVE gob — the `SetupMod` seam:** `Gob.SetupMod` is an interface a `GAttrib` may also implement; `gobstate()`/`placestate()` contribute a `Pipe.Op` to the gob's own child slot, i.e. **below** the `"gobx"` translate and `"gob"` rotate of the row above ⇒ **T·R·S**, the same level `GhostGob.obstate` scales at. `Gob.setattr` registers it in the private `setupmods`; `Gob.ctick` calls the private `updstate()`, which rebuilds `GobState` from that list and pushes `slot.ostate` **only** when `Utils.eq(mods)` differs — so a change lands next tick and costs no new seam. ⚠️ **`Location` has no `equals`**: a freshly minted op each tick compares unequal every tick and re-pushes forever — **cache the op per value**. `Pipe.Op.compose` drops nulls and returns the lone survivor **itself** (only `Composed.equals` is `Arrays.equals`), so with one mod the comparison is identity. Copy `GobHealth`; an attrib that is **also** a `RenderTree.Node` is the only shape for which `setattr` can throw `Loading` |
| **The camera, outside a render pass** | `MapView.camera` → `MapView.Camera`'s `protected view` (`haven.render.Camera extends Transform`) + `proj`; `camera.view.fin(Matrix4f.id)` is render→eye, so `invert()` (null when singular) gives the camera's axes as its first three **columns** (`m[col*4+row]`) and the eye point as the fourth. Fork: `MapView.camview()` (`// addon:`) — `Placer.getr` has no `Pipe`, so `Homo3D.obj2view` is not available there |
| **Which ground is DRAWN — the terrain display list** | `MapView.MapRaster` (private inner) → the public `Terrain` and its `Grid main`/`flavobjs`. `MapRaster.tick` — the base default, which `Terrain` takes and every other raster overrides — sets `area = Area(cc - view, cc + view + 1)` around the player's CUT (`getcc().floor(tilesz).div(MCache.cutsz)`, `view = 2`, `cutsz = 25×25` tiles) ⇒ the drawn terrain is only **~50–75 tiles** across from you; `Grid.tick` then adds/removes one scene slot per cut, keyed in `Grid.cuts`, which therefore holds a cut **exactly while that cut's mesh is in the scene**. ⚠️ That is a GRID smaller than `MCache.grids`: map data is dropped only when the server says so (`invalblob` type 1 → `MCache.trim`, which has **no caller inside the client**), so "the grid is loaded" is true well past the visible edge — test `cuts` when the claim is about what the player SEES. Fork: `MapView.grounddrawn(Coord2d)` (`// addon:`) + a `groundChanged()` tap at `Grid.tick`'s two mutation points, which is how a client-only gob stops drawing over the void |
| **A SECOND source of ground in one scene** | Fork: `MapView.RecallTerrain extends MapRaster` over a second `MCache` filled from the map database ([mapfile.md](mapfile.md)) instead of from the wire — the same pattern as `MapView.SessionTerrain`, which rasterizes another *session's* `MCache` into this scene. Four things separate such a raster from `Terrain`. Its `area` is centred on **the camera** (`RTSCam.center()`) rather than on `getcc()`. It is bounded by what its source has actually filled, **less one grid**: `MapMesh.dotrans` reads a tile across the cut edge, so a cut at the fill's own edge throws `MCache.LoadingMap` and never completes. Its `skipcut` yields every cut inside `terrain.area`, because both sources hold the same ground there and one mesh on its twin is z-fighting, not a merge — the same rule `SessionTerrain` obeys against the anchor. And it culls with `cutvisible` unconditionally rather than on `ui.gprefs.cullterrain`, because its slot carries `ShadowMap.maskshadow` anyway, which is the one thing that option buys an invisible cut. Installed in `basic` with the `rts` camera and removed with it; `:recall off`/`on` in `cmdmap` takes it out of the tree and puts it back. What tells it apart from live ground is a **shader state on its slot** — a desaturation compiled into every tileset material below it (recipe [below](#recolouring-ground-a-sheet-over-it-or-a-shader-under-it)), which is one program and no second mesh; `:recall wash <a>` pushes a new amount through `Slot.ostate` and rebuilds nothing |
| **Bounding a raster the camera aims, rather than the player** | Fork: a camera-centred `area` is as large as the view is wide — 5×5 grids at `cutn = 4` is **400 cuts** where `Terrain` keeps 25 — and `MapMesh.build` is two passes over 625 tiles plus `dotrans`'s eight neighbour reads each, then a slot compile and a VBO upload, on the `Defer` threads every loading thing in the client shares. `MapRaster` has no budget of its own, and `Grid.tick` calls `getcut` for every cut of `area` in **iteration order**, so the bound goes in `skipcut`: `RecallTerrain.tick` builds the wanted set once, sorts it by distance from the camera's own cut, and admits cuts until a stated cap — plus at most a few whose mesh is not yet in `Grid.cuts`, which bounds what is **in flight** and not merely what is begun, since a cut still building has no entry there. `skipcut` is then a set membership, and `Grid.tick` removes the slot of everything the budget or the view left out. ⚠️ A cap derived from the frustum instead is not a cap: the camera can frame more ground than this client was built to draw |

**Gotcha — a raster over a cache the client must not send for cannot use `getcut` alone.** `MCache.getcut`
ends in `getgrid`, which on a miss calls `request(gc)` and throws `LoadingMap` — so a `MapRaster` walking an
area wider than its source has filled queues every absent grid in it. On the live cache that is the point; on
one filled from disk it fills a queue nothing may ever send, and buries the request count that is the only
evidence such a source is behaving. Ask the cache what it holds first (`AddonWidgets.loadedGrid`, `// addon:`)
and skip the cut when it holds nothing. That is also what drops a cut whose grid `MCache.trim` has just
disposed: `MapRaster.Grid.tick` removes the slot of any cut its `skipcut` starts refusing, so the stale mesh
leaves the scene at the next tick instead of being drawn after its `dispose()`. ⚠️ **`trimall` has no such
next tick** — it disposes every `Grid` at once, and a raster removed in the same breath never ticks again to
notice, so **the slot comes out first and the dispose follows**; and an incremental `trim`'s kept rectangle
stays concentric with the drawn `area` and a grid wider, so no pan trims a grid still in `Grid.cuts`.

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
| Which one is INSTALLED | Fork: `MapView.camname()` reverse-looks the live `camera`'s class up in the registry, `null` for a camera nobody registered. It is **not** the `defcam` pref: `setcam` installs on the view it is called on, and with several sessions ([multi-session.md](multi-session.md)) the other views are still on whatever they were built with, so the pref answers what a session will come up on and this answers what is on screen now |
| **One camera, several sessions** | Fork: a camera is an inner class of the view it draws, so sessions cannot share the object — they share its **state**. `MapView.adoptcam(Camera, Coord2d)` takes the camera the session losing the screen was being played with: it rebuilds the type when the two views differ (bare, with none of `:cam`'s arguments) and then `Camera.restate` copies the settings over. `restate` walks `getDeclaredFields` down the subclass chain and **stops at `Camera`** — `view` and `proj` are this view's own render state, derived from a size the other view need not share, and `resized()` rebuilds them. A field holding a place (`Coord`, `Coord2d`, `Coord3f`) is nulled rather than copied: coordinates are per-session and every such field in the shipped cameras is a cache the next tick refills. The exception is `RTSCam.center`, a pan the player set, which that class's own `restate` puts back **translated** by the session offset it is given |
| Choosing one by hand | the `cam` command in `MapView`'s `cmdmap` (`Console.Directory`) — `:cam <name> [args…]`, whose whole body is `setcam`. `findcmds` exposes the map, so the command lives on the map view rather than on the client, and `ConsoleHost.done` catches what `setcam` throws and prints it |
| Choosing one from Options | Fork: `OptWnd.CameraPanel.CamSelector`, an `SDropBox<String, Widget>` whose items **are** `camnames()` — the registry's keys carry no display label of their own, so a camera has one spelling in the dropdown, at the console and in the refusals |
| ⚠️ **A camera that cannot answer blacks the WHOLE view out** | `MapView.tick` runs `camera.tick(dt)` in a `try` and keeps whatever it throws in `camload`; `draw` re-throws that before anything else, and its own `catch(Loading)` fills the widget with black and centres the message — no scene, no gobs, no ground. So the point a camera decides to look at must **not** propagate `Loading`: right for one bolted to the player, who cannot be where they have never been, and ruinous for one that can be aimed anywhere. `Loading.boostprio` (5 on the way in, 6 in `draw`) is the only thing that keeps the wait short |
| The camera's own input | `Camera.keydown`, `click`, `drag`, `release`, `wheel` — all no-ops on the base class. `MapView.keydown` runs the fork's dispatch **before** `camera.keydown`, so a camera never sees a key the client has already claimed |
| **The middle button is the camera's alone** | `MapView.mousedown` tests `ev.b == 2` first and sends it straight to `camera.click`, with **no modifier branch and no fallthrough** — the plob placement, the map grab, the RTS layer and `Click` all sit in the `else` chain below it. `mouseup` mirrors it against `camdrag`, and `mousemove` gives the drag to `camera.drag` whenever `camdrag` is held. So a camera may claim any modifier it likes on a middle drag without colliding with anything else on the widget |

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
| Into a scene | `MCache.getolcut(id, cc)`, cached per `Grid.Cut`; `MapView.Overlay extends MapRaster` is one raster per `OverlayInfo`, colour applied at the slot as `slot.add(base, id.mat())` |

| The shader | Where |
|---|---|
| **A state above the materials reaches them** | A program is compiled from the **composed `Pipe`** of the slot being drawn, not per `Material` — so every `State` in that composition contributes its `ShaderMacro`, and one installed at a subtree's root is compiled into the program of every material below it. `haven.ColorMask` and `render.BaseColor` are the two precedents |
| **The recipe** | `extends State` with a `State.Slot`, a `Uniform` reading it, and `shader()` returning `prog -> FragColor.fragcol(prog.fctx).mod(fn, order)` — `fn` is a `UnaryOperator<Expression>` over the fragment colour, built with `haven.render.sl.Cons` (`pick`, `dot`, `mix`, `vec3`, `vec4`). `BaseColor` mods at order **0**, `ColorMask` at **100**; nothing ships above that |
| What it costs | one program, compiled on the first frame that needs it. No geometry, no draw, no pass. Keeping the amount in the `Uniform` rather than the macro means changing it recompiles nothing either — push a new instance with `Slot.ostate` |

**Gotcha — the sheet's two hidden costs.** `RectOverlay.update` bumps `MCache.olseq`, which `Grid.getolcut` reads
as *dispose and rebuild every overlay mesh in this grid* — so a rectangle tracked to the camera rebuilds the whole
sheet each time it moves, and a mask meaning "everywhere" is a **fixed, enormous** rectangle instead (nothing
iterates it: `RectOverlay.fill` walks only its overlap with the cut being built). And `getolcut` builds the
**outline** mesh (`makeolol`) whether or not anything draws it, so an overlay is two full tile-laying passes per
cut, on the calling thread. Where the mask is empty for a cut `makeol` answers **null**, which is a legal
`RenderTree` child (`TreeSlot` calls `added` only on a node that is there) and draws nothing.

## Textures and materials (no `.res` required)

| What | Where |
|---|---|
| **`.res` images are just PNG** (the substrate) | `Resource.readimage` `ImageIO.read(fp)` → `Resource.Image` `new TexI(img)` |
| PNG → GPU texture (no `.res`) | `new TexI(BufferedImage)`; GPU upload lazy/thread-safe in `TexI.st()` → a `ColorTex` |
| **2D screen blit** | `GOut.image(Tex,Coord)` / scaled `(Tex,Coord,Coord)` / `aimage(Tex,Coord,ax,ay)` |
| Bare-`TexI` blit precedent (no `.res`) | `SpeakerIcon.GLYPH` = `new TexI(img)` drawn via `g.image(...)` |
| **World-anchored 2D blit (billboard)** | `SpeakerIcon` `extends GAttrib implements RenderTree.Node, PView.Render2D`; `draw(GOut,Pipe)` projects the anchor with `Homo3D.obj2clip` (the `w > 0` test above, then `toview`) and blits it with `g.image(...)` |
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
