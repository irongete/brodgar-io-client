# 028-asset-loader — Tasks

<!-- MAX 60 lines. One task = one session: self-contained, compiles, in-game verifiable on its own. -->

- [x] **028.1 — the loader: `hafen.asset` + one intern cache + the hard cut.**
      New `AssetApi`: callable namespace (`hafen.asset()` = live assets, `hafen.asset(path)` = one asset),
      `resolveAddonAsset` moved in from `RenderApi` (semantics untouched), extension dispatch
      (`.png/.jpg/.gif/.bmp` · `.ttf/.otf` · `.glb/.gltf`, unknown → an error listing them), and **one
      per-addon `path → asset` map** replacing the two linear scans — which is what finally gives fonts a
      cache (one `Font.createFont` + one `registerFont` per file, not one per call).
      Shared verbs `:type()`/`:path()`/`:dispose()` on all three handles; each type keeps its own
      (`:bounds()`, `:derive`/`:family`/`:size`). The `volatile dead` flag and the `g:image` liveness check
      carry over verbatim — `TexI.dispose()` releases, it does not invalidate.
      **Hard cut** in this task: `hafen.font.load`, `hafen.render.image`, `hafen.render.model` deleted, and
      built-in fonts become **`hafen.font("sans"|"serif"|"mono"|"fraktur")`** — name-keyed, interned, no
      lifetime (no `:dispose()`/`:exists()`, D-060); `hafen.font.setFont`/`reset`/`scopes` untouched.
      `hello` gets an **interim** edit only (its three loads re-pointed) so the client still runs.
      **Verify:** `jshell` first — dispatch, the four sandbox rejections, interning, a re-load after
      `:dispose()` giving a *new* object (all headless: `Gltf` is pure, `new TexI` uploads lazily). Then
      in-game: `hello`'s image, font and model all still draw, `hafen.font.load` reads `nil`.

- [x] **028.2 — the consumers: handle-only, and the teardown audit.**
      `hafen.render.sprite{image=}` / `object{model=}` become **handle-only**; a string raises an error
      naming `hafen.asset` as the way in. Wire the collection form (`hafen.asset()` lists **live** assets —
      never resurrect a dead entry) and confirm the typed owned-lists still drive teardown in the order
      R3b requires (`teardownObjects` **before** `teardownMeshes`, which own the shared `TexI`s).
      **Verify:** in-game — a sprite and an object built from handles draw as before; passing a path string
      raises the pointing error; `:reload`, disable and relogin each free everything (checked against
      `hafen.client:profiling()` texture/memory counters, the `012` method), and a manual `mesh:dispose()`
      while an object still draws it behaves as documented.

- [x] **028.3 — docs, harness, close.**
      New `docs/addons/api/asset.md` (the one flow *load → draw/decorate/stand*; the three types and their
      extensions; interning and the honest limit that identity is stable **while alive** — a disposed asset
      re-loads as a new object; the error catalogue; why there are no URLs and why engine resources are
      addressed, not loaded). Rows in `api/README.md` and the `docs/addons/README.md` "API at a glance"
      table. Sweep the cut through `render.md` (loaders gone, `sprite`/`object` handle-only), `fonts.md`
      (`load` → `hafen.asset` + `hafen.font(builtin)`), `getting-started.md` and any cross-ref.
      `hello` version bump + the once-per-login **contract check**: one asset of each type, `==` interning,
      `:type()`/`:path()`, one sandbox rejection, one unknown-extension rejection, and
      `loadersGone` (the three cut entry points read `nil`).
      **Verify:** one login re-checks 028 and every prior feature; docs match the shipped surface exactly.
      The page must state the loader rule outright: **`hafen.asset(path)` takes a path and nothing else** —
      a font's size/style comes from `:derive{…}`, never from the load.
