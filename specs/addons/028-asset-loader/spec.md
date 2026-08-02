# 028-asset-loader — Spec

## What & why

**One loader for the files an addon ships.** Three entry points across two namespaces load an addon-relative
file today — `hafen.font.load`, `hafen.render.image`, `hafen.render.model` — each with its own cache and
teardown, and `hafen.render` is a scene namespace that also happens to be a loader. They collapse into a
callable **`hafen.asset(path)`** ([D-056](../decisions/architecture-api.md) arity) returning a typed,
**interned** handle over one cache, one ownership/teardown policy, and the sandbox resolver in its own home.
**Hard cut** of the three ([D-013](../decisions/architecture-api.md)), and **one flow for every local file** —
load → draw / decorate / stand — with no path-string shortcut at the use sites ([D-012](../decisions/architecture-api.md):
interning makes repeating the load free, so a shortcut only buys a second way to say the same thing).

First of an agreed five-feature run toward ElvUI-style skinning under a single `hafen.ui` (**A** asset →
**B** `hafen.ui` to OOP + selector engine → **C** `hafen.ui.skin` stylesheet → **D** window chrome over
`Window.Deco` → **E** layout). C inherits the handle-only rule: a JSON-authored theme maps its strings
through `hafen.asset` in Lua, once — data authoring is not a contract concern.

**Ground truth (verified; narrower than it looked).** The sandbox check is **already single** —
`RenderApi.resolveAddonAsset` ([:526](src/io/brodgar/addon/RenderApi.java:526)), which `FontApi` already calls
([:144](src/io/brodgar/addon/FontApi.java:144)) — wrong home, not duplicated. Image and mesh each carry their
own linear-scan cache ([:552](src/io/brodgar/addon/RenderApi.java:552) /
[:633](src/io/brodgar/addon/RenderApi.java:633)). And **fonts have no cache at all**: every `hafen.font.load`
re-reads the file and re-`registerFont`s the family. That last one is the real defect; the rest is API shape.

## Acceptance criteria

- [ ] `hafen.asset("icon.png")` → image handle; `g:image`/`hafen.render.sprite{image=h}` draw it as before.
- [ ] `hafen.asset("fonts/X.ttf")` → font handle — **one argument, always: the loader has no per-type
      options**; `:derive{size=12}` is the sized variant. Both work in `hafen.ui.window{font=h}`,
      `g:text(…,{font=h})`, `hafen.font.setFont(scope, h)`, `node:setFont(h)`.
- [ ] `hafen.asset("chair.glb")` → mesh handle; `hafen.render.object{model=h}` stands it in the world.
- [ ] **Interning**: same path → **same object**, all three types (and a `.ttf` too, which today re-parses
      every call): `hafen.asset("icon.png") == hafen.asset("icon.png")`.
- [ ] `a:type()` → `"image"|"font"|"mesh"`, `a:path()`, `a:dispose()`; `hafen.asset()` (no args) lists the
      addon's live assets.
- [ ] Errors name `hafen.asset` and are distinguishable: absolute path, `..` escape, missing file,
      undecodable file, unknown extension (listing the supported ones), non-string argument.
- [ ] **Hard cut**: `font.load`/`render.image`/`render.model` read `nil`; `hafen.render` keeps only `sprite`/`object`.
- [ ] **One flow, no sugar** ([D-012](../decisions/architecture-api.md)): consumers take a **handle only** —
      `sprite{image = "icon.png"}` / `object{model = "chair.glb"}` raise a clear error naming `hafen.asset`.
      The path-string shortcut those two accept today is **removed** (no addon uses it, not even the demos).
- [ ] `:reload` / disable / relogin free every asset (no GPU leak) — profiler texture counters, as `012` did.
- [ ] `hello` loads one of each type, asserts the interning and one sandbox rejection, and the full
      regression still passes.

## Out of scope

- **URLs / remote assets.** Async, an untrusted binary into the AWT font and GL texture paths, and a
  tracking channel; if ever wanted it is a separate, gated, async `fetch` — not this entry point.
- **Engine resources.** `hafen.sound(name)` and `g:resource(name)` *address* what the client already
  owns: no sandbox, no cache, no lifetime. They stay where they are; the boundary is deliberate.
- New asset types (audio, shaders) and async decode — decode stays synchronous as today.
- Renaming `LuaModel` (the `hafen.ui.adopt` handle) despite its collision with `hafen.render.model`'s
  `LuaMesh` — feature **B**.
- The `Gltf`/`MeshSprite`/`SpriteQuad` package move listed under the ROADMAP's tier-3 split.

## Context files

- `design/17-custom-rendering.md` — R1/R3 image + mesh loading, the `hafen.render` surface (D-034)
- `design/21-fonts.md` — F1 `load` + `FontHandle` (D-043); §"Java shape" for the teardown model
- `src/io/brodgar/addon/RenderApi.java` — `resolveAddonAsset`, `newImage`, `newMesh`, `teardownImages/Meshes`
- `src/io/brodgar/addon/FontApi.java` — `load` + `teardownFonts` (the uncached path)
- `src/io/brodgar/addon/LuaImage.java`, `LuaMesh.java`, `FontHandle.java` — the three handles being unified
- `src/io/brodgar/addon/Addon.java` — the owned-resource lists (`images`, `meshes`); a fonts list is added
- `src/io/brodgar/addon/AddonManager.java` — **where a namespace is installed** (`installRender`/`installFont`,
  `hafen.set("sound", …)`) and the `imageHandle`/`meshHandle` builders + userdata round-trip
- `src/io/brodgar/addon/AddonRegistry.java` — **the teardown call site and its ORDER** (objects → images →
  meshes → fonts, with the ordering comments R3b depends on)
- `src/io/brodgar/addon/LuaGOut.java` — the `img.dead` liveness guard in `g:image`/`g:aimage` that makes
  `:dispose()` final (`TexI.dispose()` only releases)
- `addons/hello/main.lua` + `manifest.json` — the harness: interim edit in 028.1, contract check in 028.3
- `docs/addons/api/render.md`, `fonts.md` — the surfaces being cut/redirected; **028.3 also**:
  `api/README.md`, `api/conventions.md` (the callable-namespace shape), `docs/addons/README.md`,
  `docs/addons/getting-started.md`
- `learnings/rendering.md`, `learnings/fonts.md` — **grep, never read whole**: R1 (`dead` flag, the one
  containment check), R3b (teardown order, shared mesh textures), F2 (`FontHandle` immutability, `$font`)
- `012-custom-rendering/`, `016-fonts/` — prior art: the handle + owned-teardown mechanism reused here
- `decisions/architecture-api.md` (D-056 arity, D-013 hard cut, D-012 one canonical way),
  `decisions/security-sandbox.md` (D-017)
