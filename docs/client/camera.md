# The camera: reading one, and the registry that names them

> The one `MapView.Camera` a view is looking through: how to read its axes outside a render pass, how a
> camera is named, built, remembered and swapped, and the two traps in its frustum. The scene it looks at
> is [world-3d.md](world-3d.md).

## Reading the camera outside a render pass

| What | Where |
|---|---|
| **The camera, outside a render pass** | `MapView.camera` → `MapView.Camera`'s `protected view` (`haven.render.Camera extends Transform`) + `proj`; `camera.view.fin(Matrix4f.id)` is render→eye, so `invert()` (null when singular) gives the camera's axes as its first three **columns** (`m[col*4+row]`) and the eye point as the fourth. Fork: `MapView.camview()` (`// addon:`) — `Placer.getr` has no `Pipe`, so `Homo3D.obj2view` is not available there |

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

## See also

- [the 3D world](world-3d.md) — the scene the camera is pointed at
- [which ground is drawn](terrain-raster.md) — the raster a camera-centred `area` is bounded by
- [several sessions at once](multi-session.md) — `adoptcam` and what `restate` carries between views
- [the pointer and the ground](map-click.md) — the projection a screen ↔ world conversion uses
- [services](services.md) — the `defcam`/`camargs` prefs, and the Options panel that writes them
