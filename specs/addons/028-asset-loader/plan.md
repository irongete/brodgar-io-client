# 028-asset-loader — Plan

## Approach

**One entry point, one cache; the teardown stays typed.** A new `io.brodgar.addon.AssetApi` builds the
callable `hafen.asset` ([D-056](../decisions/architecture-api.md): `hafen.asset()` = the addon's live assets,
`hafen.asset(path)` = one interned asset) and becomes the home of the sandbox resolver. The three existing
handles (`LuaImage`, `LuaMesh`, `FontHandle`) are **kept as they are** — this feature unifies the *door*, not
the assets themselves.

1. **Move** `resolveAddonAsset` out of `RenderApi` into `AssetApi`, semantics untouched
   (`base.resolve(name).normalize()` + `startsWith(base)` — one containment check, per the R1 learning).
2. **Dispatch by extension**: `.png/.jpg/.gif/.bmp` → image (`ImageIO` → `TexI`), `.ttf/.otf` → font
   (`Font.createFont` + one `registerFont`), `.glb/.gltf` → mesh (`Gltf`). Unknown → an error listing the
   supported extensions.
3. **One intern map per addon**, `path → asset`, replacing the two linear scans
   ([RenderApi.java:552](src/io/brodgar/addon/RenderApi.java:552) / [:633](src/io/brodgar/addon/RenderApi.java:633))
   and giving fonts the cache they never had. A dead entry is not served: a disposed asset **re-loads as a new
   object** on the next call, so identity is stable *while alive* — documented, not hidden.
4. **Keep the typed owned-resource lists** (`Addon.images`, `Addon.meshes`, + a new one for fonts) as the
   teardown units. They encode an order a learning says is load-bearing (R3b: `teardownObjects` **must** precede
   `teardownMeshes`, which own the shared `TexI`s). Unifying them into one flat list would buy tidiness and risk
   a live object drawing a freed texture. **The cache is unified; the teardown is not.**
5. **Common handle surface** `:type()` / `:path()` / `:dispose()` on all three, alongside each type's existing
   verbs (mesh `:bounds()`; font `:derive`/`:family`/`:size`). Preserve the `volatile dead` flag and the
   draw-time check — `TexI.dispose()` releases but does not invalidate (R1).
6. **Built-in fonts move to the boundary the spec draws.** `hafen.font.load("sans")` is cut, and `"sans"` is not
   a path — it is an **engine-owned** font, so it is *addressed*, not loaded: **`hafen.font("sans"|"serif"|
   "mono"|"fraktur")`** → a `FontHandle`, name-keyed and interned, no lifetime and so no `:dispose()`/`:exists()`
   ([D-060](../decisions/architecture-api.md)) — the `hafen.sound(name)` shape exactly. `hafen.font` keeps
   `setFont`/`reset`/`scopes` untouched (feature **C** folds those into the stylesheet, not this feature).
7. **Hard cut**: delete `hafen.font.load`, `hafen.render.image`, `hafen.render.model`; make
   `hafen.render.sprite{image=}` / `object{model=}` **handle-only**, with an error naming `hafen.asset`.

## Files to create / modify

- **create** `src/io/brodgar/addon/AssetApi.java` — the callable namespace, the resolver, the intern map,
  extension dispatch, the shared handle verbs.
- **create** `docs/addons/api/asset.md` — the new section page (028.3).
- `src/io/brodgar/addon/RenderApi.java` — drop `image`/`model` + `newImage`/`newMesh`/`resolveAddonAsset`;
  `sprite`/`object` become handle-only; `teardownImages`/`teardownMeshes` stay (called from the new owner).
- `src/io/brodgar/addon/FontApi.java` — `load` deleted; `hafen.font` becomes callable for the built-ins;
  `teardownFonts` keeps the override stacks, gains nothing else.
- `src/io/brodgar/addon/Addon.java` — a fonts owned-list beside `images`/`meshes`, plus the intern map field.
- `src/io/brodgar/addon/AddonManager.java` — `imageHandle`/`meshHandle` builders gain the shared verbs; teardown
  call order preserved and commented.
- `src/io/brodgar/addon/LuaImage.java`, `LuaMesh.java`, `FontHandle.java` — a `path` + `type` field; no behaviour
  change beyond the shared verbs.
- `docs/addons/api/render.md`, `fonts.md`, `README.md`, `docs/addons/README.md`, `getting-started.md` — the cut,
  the new page, the two index tables (028.3).
- `addons/hello/main.lua` + `manifest.json` — interim edit in 028.1, the contract check in 028.3.
- **No `haven` file is read or edited** ⇒ no `specs/codebase/` coverage toll owed.

## Risks & gotchas

*(prior art: `learnings/rendering.md` R1/R3b, `learnings/fonts.md` F2/F3d — grepped, not read whole)*

- **`TexI.dispose()` releases, it does not invalidate**: the very next `TexI.st()` re-uploads. The `volatile dead`
  flag + the check in `g:image`/`g:aimage` is what makes dispose final — carry it over verbatim (R1).
- **Teardown order is load-bearing** (R3b). Objects before meshes; a manual `mesh:dispose()` while an object
  still draws it frees the textures underneath it. Unchanged here, but re-verified.
- **`registerFont` must run once per file, not once per call.** That is the defect the cache fixes; the check is
  that `h:family()` still resolves inside a `$font[…]` tag afterwards (F2).
- **`FontHandle` is immutable and caches a `RichText.Foundry` per effective px** (F2). Interning the *parse* must
  not make `:derive` expensive — derive off the cached base font, never re-read the file.
- **The collection form must not resurrect dead entries** — `hafen.asset()` lists live assets only.
- **Headless-checkable**: `Gltf` is pure and `new TexI(BufferedImage)` uploads lazily, so the whole loader
  (dispatch, sandbox rejections, interning) is assertable in `jshell` with no GL context — do that before asking
  for an in-game pass.

## Resolved: the loader takes a path and nothing else

**`hafen.asset(path)` — one argument, always. No per-type options** (maintainer, 2026-08-02). The rule is
*loading a file* (expensive, cached, once) is separate from *configuring a use* (cheap, many variants), which
is what every modern asset API does: `Resources.Load<T>(path)` (Unity), `load("res://…")` (Godot),
`@font-face` + `font-size` (CSS) — and, decisively, **AWT itself**: `Font.createFont` returns a 1pt font and
`deriveFont` makes the variants. `TTF_OpenFont(path, ptsize)` / `love.graphics.newFont(path, size)` are the
older school, from when the rasteriser baked at a fixed size.

So `FontHandle:derive{…}` — which already exists and already promises a cheap non-mutating variant — is the
one way to size a font: `hafen.asset("fonts/Inter.ttf"):derive{size=12}`. Consequences: the signature is
uniform across all three types, no argument is dead in 2 of 3 calls, and interning is unambiguous (`==` never
depends on an options table). Extension dispatch stays (Godot-style) rather than a type parameter
(Unity-style) for the same reason — a second argument would reintroduce the wart.

## Discarded alternatives

- **Keep the path-string shortcut on `sprite`/`object`** — rejected (D-012, and zero addon uses it).
- **A per-type `opts` on the loader (`hafen.asset(path, {size=…})`)** — rejected: dead in 2 of 3 calls; the
  variant belongs on the asset (`:derive`), as in AWT/Unity/Godot/CSS.
- **Composite intern key `(path, opts)`** — rejected: `==` would depend on an options table.
- **One flat `Addon.assets` list replacing the typed ones** — rejected: the typed lists encode the R3b order.
- **Name it `hafen.res`** — rejected: "res" already means *engine resource* here (`gob:res()`, `meter:res()`).
- **Fold A into B** — rejected: two contracts changing at once, verifiable as neither.
