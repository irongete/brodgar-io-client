# R1 — 2D image on screen (`hafen.render.image` + `g:image`/`g:aimage`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages the asset,
> **33 headless checks** (the D-017 path-containment `resolveAddonAsset`; the `LuaImage.resolve` opaque-userdata
> round-trip + foreign-value rejection; `newImage` end-to-end — decode → handle → `:size()` → cache-returns-same
> → `:dispose()` marks-dead + drops-from-registry + idempotent → post-dispose reload makes a fresh handle; and
> the validation rejects: non-string / escape / missing file — same-package + reflection for the private statics,
> the documented headless pattern) + LuaJ parse of `hello`/`planner`/`walker`/`bags`/`hogtest`.
> **Java engine change ⇒ `ant` rebuild + a full client restart before the in-game test.** **In-game verified ✅.**
> **Design:** [specs/addons/17-custom-rendering.md](../../../specs/addons/17-custom-rendering.md) (§0 the PNG
> substrate, §3 loaders + sandbox, §4 `g:image`), decisions **D-034** (custom non-`.res` rendering is a new
> `hafen.render.*` namespace, safe-tier — not gated) / **D-017** (constructive sandbox: an addon reads only its
> own folder).

R1 is the first slice of the **R-series** (custom rendering): it lets an addon draw its **own PNG images** — not
engine `.res` art — on screen (in a widget, a HUD overlay, or a gob overlay). It is the foundation loader every
later R-slice reuses: R2 stands a PNG in the world (billboard / fixed quad), R3 places a glTF 3D model, and both
feed their textures through this same `hafen.render.image` → `TexI` path.

## The API

```lua
local img = hafen.render.image("icon.png")   -- PNG from THIS addon's folder → a cached, bridge-owned handle
img:size()      -- {w, h} in pixels
img:dispose()   -- free the GPU texture early (also automatic on reload/disable/relogin)

-- inside any draw callback (onDraw / hafen.ui.overlay / hafen.ui.gobOverlay), via the g wrapper:
g:image(img, x, y)             -- native size, top-left at (x, y)      → GOut.image(Tex, Coord)
g:image(img, x, y, w, h)       -- scaled into a w×h box                → GOut.image(Tex, Coord, Coord)
g:aimage(img, x, y, ax, ay)    -- anchored (ax/ay 0..1), mirrors atext → GOut.aimage(Tex, Coord, ax, ay)
```

## The key insight — `.res` is already PNG under the hood

A `.res` file is a **container**, not an image format: its image layers are **PNG bytes** the client decodes with
`ImageIO.read` and wraps in a [`TexI`](../../../src/haven/TexI.java:52) — that is exactly what
[`Resource.readimage`](../../../src/haven/Resource.java:1060) + [`Resource.Image`](../../../src/haven/Resource.java:1068)
(`new TexI(img)`) do, and how [`SpeakerIcon.GLYPH`](../../../src/haven/SpeakerIcon.java:51) blits a bare texture with
zero resource involvement. So "render a PNG" is the substrate the engine already runs on; R1 exposes it directly
(`PNG → BufferedImage → TexI`) and skips the `.res` wrapper. `new TexI(BufferedImage)` uploads to the GPU
**lazily** (in [`TexI.st()`](../../../src/haven/TexI.java:59)), so the loader itself needs no GL context — it is
even headless-constructible (the test decodes a real 32×32 PNG without a window).

## Mechanism — the loader, the opaque-userdata handle, the draw verbs

**`hafen.render.image(path)` → `newImage`** (in `AddonManager`):

1. **Sandbox the path (D-017).** `resolveAddonAsset(owner, name)` resolves `name` under
   `owner.dir.toAbsolutePath().normalize()` and requires the result to still `startsWith(base)`. After
   `normalize()`, both an **absolute** path and a `..` that **climbs out** fail that containment check (they no
   longer start with the folder), while an internal `a/../b` is allowed — one check that covers "reject
   absolute / `..` escape" without banning legitimate internal traversal. An addon reads **only its own assets**.
2. **Decode.** `ImageIO.read(file)` → `BufferedImage`; a null/failed decode raises a clear `LuaError`.
   `new TexI(img)` wraps it (arbitrary-format `BufferedImage` → RGBA conversion happens inside `TexI.st()`, so any
   PNG/JPG/GIF/BMP works and alpha is preserved).
3. **Cache (P2).** One `LuaImage` per `(addon, path)`: a repeated load of the same path returns the **same
   handle** (scan `owner.images`; a dead entry is skipped so a post-`:dispose` reload makes a fresh one).
4. **Handle.** `imageHandle` builds the Lua table `{ :size(), :dispose() }` and stores the `LuaImage` in it as an
   **opaque userdata** (the `LuaImage.KEY` field).

**`g:image` / `g:aimage`** (in [`LuaGOut`](../../../src/io/brodgar/addon/LuaGOut.java)): resolve the handle arg
back to its texture with `LuaImage.resolve(v)` — read the handle table's `KEY` userdata (or accept a raw
userdata), cast to `LuaImage`, else `null`. A `null` / `dead` / null-`tex` handle **draws nothing** (the forgiving
`g`-wrapper contract — a typo or a disposed image never throws), otherwise it calls
[`GOut.image`](../../../src/haven/GOut.java:97)/[`aimage`](../../../src/haven/GOut.java:107). Because all three
draw surfaces (widgets, HUD overlays, gob overlays) share the one `LuaGOut.build()`, the two verbs light up
everywhere at once (the D-013 shared-`g` reasoning).

### Why an opaque userdata (facade-safety, P1)

The handle needs to carry a Java `TexI` reference so `g:image(img, …)` can find it, but principle P1 forbids
handing Lua a `haven.*` object it could call methods on. The solution is the **same opaque round-trip
[`LuaMarshal`](../../../src/io/brodgar/addon/LuaMarshal.java) already uses** for hook arguments:
`LuaValue.userdataOf(luaImage)` has **no metatable**, so Lua cannot index or call it, and it **cannot be forged**
(the sandbox omits `luajava`, D-017) — the only such userdata in existence are the ones the bridge created. It is
inert data, exactly like an int `WidgetRef`, just carried *in* the handle instead of resolved through a map (an
image is a bridge-owned asset with no natural server id, so — unlike a widget/item/gob — there is nothing to
re-resolve; the reference travels with the handle and is GC'd with the env, needing no global registry).

## Zero `haven` core edit

Everything is public: `GOut.image`/`aimage`, `new TexI(BufferedImage)`, `TexI.sz()`/`dispose()`, `ImageIO`, and
`Addon.dir`. So R1 is **`io.brodgar.addon`-only** — the spec's "likely ZERO core edit" held. (The V-series needed
a `MapView` seam because a ghost enters the 3D scene; an on-screen image just blits through the existing `g`.)

## Threading & lifecycle (P2/P5)

- **UI thread for the loader** (`hafen.render.image` / `:dispose` / teardown run from `OnLoad`/`OnEnterWorld`/a
  command — P5); v1 decodes **synchronously** there (small local files, spec 17 §3). **Draw thread for the blit**
  (`g:image` inside a draw callback): it only reads the `LuaImage`'s `tex` + a `volatile dead` flag, and `TexI` is
  itself draw-thread-safe (its GL upload is lazy + `synchronized`), so no extra locking is needed. `Addon.images`
  is copy-on-write.
- **`dead` is `volatile` and guarded before the blit** so a `:dispose()` is final — without the guard, drawing a
  disposed `TexI` would lazily **re-upload** it via `TexI.st()` (dispose only nulls the cached GL texture). The
  worst a dispose/draw interleave can do is paint one extra frame, then the guard takes over.
- **Teardown (P2).** Each image is a bridge-owned entry in [`Addon.images`](../../../src/io/brodgar/addon/Addon.java);
  `teardownImages` (wired into `AddonManager.teardown`, beside `teardownGhosts`) disposes each on
  `OnDisable` / `:reload` / relogin — frees the `TexI`'s GL texture — leaking nothing, the same guarantee as
  windows, overlays, and ghosts. `disposeImage` is idempotent (the `dead` flag).

## Files changed

- **`src/io/brodgar/addon/LuaImage.java`** (new) — the bridge-owned image: `owner`, `name`, the `TexI`, `sz`,
  a `volatile dead` flag, the stable `handle`, the `KEY` field constant, and the static `resolve(LuaValue)` used
  by the draw verbs.
- **`src/io/brodgar/addon/LuaGOut.java`** — `g:image(img, x, y[, w, h])` (native/scaled) and
  `g:aimage(img, x, y, ax, ay)` (anchored), resolving via `LuaImage.resolve` with the forgiving nil/disposed
  no-op; class-doc note updated (image drawing is no longer "deferred").
- **`src/io/brodgar/addon/AddonManager.java`** — imports (`TexI`, `BufferedImage`, `IOException`,
  `javax.imageio.ImageIO`); the `hafen.render` namespace (`image`) in `installHafen`; `resolveAddonAsset` (D-017),
  `newImage` (validate + sandbox + decode + cache), `imageHandle` (`:size`/`:dispose` + the opaque userdata),
  `disposeImage`, `teardownImages` (wired into `teardown`).
- **`src/io/brodgar/addon/Addon.java`** — the `images` owned-resource list.
- **`addons/hello/icon.png`** (new) — a 32×32 RGBA test asset (a green "H" disc on a transparent background, so
  the alpha channel is exercised).
- **`addons/hello/`** — v0.38.0: loads `icon.png` at `OnLoad` (logs `:size()`), draws it in the 2a window
  (native 32×32 + scaled 16×16) and the 2b HUD overlay (anchored beside the readout via `g:aimage`); manifest
  description updated.

## Try it in-game (R1 DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (a Java engine change — the JVM does not hot-reload
classes).

1. **The `hello` harness** does it automatically: log in, and the **Hello 3a window** shows the green "H" icon in
   its top-right (a native 32×32 and a scaled 16×16), and the **top-centre HUD readout** has the icon anchored
   just to its left. The `OnLoad` log line reports `R1: loaded icon.png (32x32) …`.
2. **From `:lua`** (in a draw-less context you cannot blit, but you can verify the loader):
   ```
   :lua local i = hafen.render.image("icon.png"); return i:size()   -- {w=32, h=32}
   :lua return hafen.render.image("../secret.png")                  -- errors: path escapes the addon folder
   ```
3. **`:reload`** (or disable `hello`) → the image's `TexI` is disposed (no GL leak); a fresh reload re-loads it and
   the icon reappears. Transparency composites correctly over the window/HUD behind it.

DoD: the icon renders **native + scaled**, in a **widget and an overlay**; `:reload` disposes the `TexI`.

## Deferred (later R-slices)

- **R2** — `hafen.render.sprite{image=, …}`: a PNG standing **in the world** — a camera-facing billboard
  (`SpeakerIcon`-style `PView.Render2D` blit) and a fixed textured quad (`TexI`-quad `Model` + `SprDrawable`),
  on the generalized V-series world-entity core, gizmo-transformable. *(May split R2a fixed / R2b billboard.)*
- **R3** — `hafen.render.model` + `hafen.render.object`: a custom **glTF** 3D model, hand-rolled pure-Java parser
  (R3a mesh, R3b textures/material, R3c lighting) — full plan in
  [18-custom-models-gltf.md](../../../specs/addons/18-custom-models-gltf.md).
- Async decode for large assets (`glob.loader.defer`, a not-ready handle drawing nothing); `hafen.render.image`
  from bytes / a data-URI; `TexI` filter control (R1 keeps the engine default).
