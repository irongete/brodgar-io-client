# 160 — Performance: plan

## Approach

One holder, twelve statics, every consumer a no-op at the default.

- **`io.brodgar.perf.Performance`** is the whole state: twelve `public static volatile` fields, each
  initialised from its preference (`Utils.getprefi`/`getprefb`) at class init and written by one
  setter that moves the field and persists it **in one statement** (the shape `MapView.recallon` and
  `Prof.arm` already have, so a click on the panel and a write from Lua are indistinguishable). The
  bounds of the three percentages are constants on this class (`DECORATION_MIN`/`MAX`,
  `PLANT_MIN`/`MAX`), stated once and read by the slider, the Lua refusal and nothing else. The class
  also carries the two **generations** the ground consumers compare against, and the two **walks**
  a write of `smoke` or of a plant amount performs over every live session.
- **The panel** is `io.brodgar.ui.PerformancePanel extends OptWnd.Panel`, a clone of `ClientPanel`'s
  pattern: every control re-reads its static every frame in `tick`, every write goes through the
  setter. It is the **first** `PanelEntry` of `OptWnd.SettingsPanel.gamepanels()`, and because the
  view opens on `game.entries.get(0)`, the Options window opens on it.
- **The handle** is `io.brodgar.addon.PerformanceOptions`, a clone of `ClientOptions`: twelve
  `OptionsMethod`s over the setters, registered in `OptionsHandle.create` as `performance` and held
  on `Addon.clientPerformance` like its six siblings.
- **The consumers** read the statics at the seam where the engine decides, each edit tagged
  `// addon: 160.N`, each an exact no-op when the static holds its default:
  - `Tileset.SpriteFlavor.flavor` scales the per-tile probability (decoration).
  - `TerrainTile.Blend` gives the base variant the whole tile (ground blend off).
  - `MapMesh.build` skips `dotrans` (transitions off).
  - `MCache.Grid.getcut`/`getfo` compare a stamp on the `Cut` against the generation and
    `Deferred.invalidate()` the half that moved — lazily, so only cuts the scene draws are rebuilt.
  - `Glob.weather()` and `Glob.tick` skip a weather whose resource name is withheld.
  - `GobSvaj.placestate()` answers `null` while sway is off.
  - `Gob.Overlay` carries a `withheld` flag decided at `init()` and re-decided by a walk.
  - Two adopted copies, `lib/plants` v11 and `lib/gplant` v1, draw a percentage of their sprouts;
    a walk re-creates the drawable of every plant in view on a write.
- **Docs**: a `performance()` section on the `hafen.client` page, and two new engine maps under
  `docs/client/` for the seams that had none — `ground-detail.md` and `world-effects.md`.

## The options

Every setting, with its name in each of the three places it is spelled.

| Panel section · control | API verb on `performance` | Preference key | `Performance` member | Type · range · default | Decided at | A change shows |
|---|---|---|---|---|---|---|
| **Ground** · slider *Ground decoration* (`N %`) | `decoration()` / `decoration(percent)` | `perf-decoration` | `int decoration`, setter `decoration(int)`, bumps `decorationGeneration` | whole `0`..`100` · `100` | `Tileset.SpriteFlavor.flavor`: `p × percent/100` per tile, ambient pieces at `p` | decoration of drawn cuts rebuilt lazily |
| **Ground** · box *Blend ground textures* | `groundBlend()` / `groundBlend(flag)` | `perf-groundblend` | `boolean groundBlend`, setter bumps `groundGeneration` | switch · `true` | `TerrainTile.Blend(MapMesh)`: base weight 1, variants 0, no noise, no blur | mesh of drawn cuts rebuilt lazily |
| **Ground** · box *Tile transitions* | `transitions()` / `transitions(flag)` | `perf-transitions` | `boolean transitions`, setter bumps `groundGeneration` | switch · `true` | `MapMesh.build`: `dotrans` skipped | mesh of drawn cuts rebuilt lazily |
| **Plants** · slider *Crop sprouts* (`N %`) | `crops()` / `crops(percent)` | `perf-crops` | `int crops`, setter runs `replant()` | whole `1`..`100` · `100` | `GrowingPlant.create` / `TrellisPlant.create`: `max(1, round(num × percent/100))` parts | plants in view re-created on the write |
| **Plants** · slider *Forageable sprouts* (`N %`) | `forage()` / `forage(percent)` | `perf-forage` | `int forage`, setter runs `replant()` | whole `1`..`100` · `100` | `GaussianPlant.create`: same over its drawn `num` | plants in view re-created on the write |
| **Objects** · box *Foliage sway* | `sway()` / `sway(flag)` | `perf-sway` | `boolean sway` | switch · `true` | `GobSvaj.placestate()` → `null` | next tick |
| **Objects** · box *Smoke plumes* | `smoke()` / `smoke(flag)` | `perf-smoke` | `boolean smoke`, setter runs `plumes()` | switch · `true` | `Gob.Overlay.init` (a new plume) and `Gob.plumes()` (the burning ones) | on the write |
| **Weather** · box *Cloud shadows* | `clouds()` / `clouds(flag)` | `perf-clouds` | `boolean clouds` | switch · `true` | `Glob.weather()` + `Glob.tick`, resource `gfx/fx/clouds` | next frame |
| **Weather** · box *Rain* | `rain()` / `rain(flag)` | `perf-rain` | `boolean rain` | switch · `true` | same, `gfx/fx/rain` | next frame |
| **Weather** · box *Snow* | `snow()` / `snow(flag)` | `perf-snow` | `boolean snow` | switch · `true` | same, `gfx/fx/snow` | next frame |
| **Weather** · box *Wet ground* | `wetGround()` / `wetGround(flag)` | `perf-wetground` | `boolean wetGround` | switch · `true` | same, `gfx/fx/wet` | next frame |
| **Weather** · box *Seasonal tint* | `seasonTint()` / `seasonTint(flag)` | `perf-seasontint` | `boolean seasonTint` | switch · `true` | same, `gfx/fx/seasonmap` | next frame |

The `OptionsMethod` verb strings are `performance:decoration`, `performance:crops`, … — the
spelling every refusal starts with. The panel labels above are the exact strings the controls show.
The five weather resource names and `gfx/fx/ismoke`, `lib/plants`, `lib/gplant` are served today
under exactly those names (checked against the resource server with `haven.Resource find-updates`).

## Files to create / modify

**160.1 — the surface**
- create `src/io/brodgar/perf/Performance.java`
- create `src/io/brodgar/ui/PerformancePanel.java`
- create `src/io/brodgar/addon/PerformanceOptions.java`
- `src/io/brodgar/addon/OptionsHandle.java` — the `performance` accessor in `create`, and the
  class javadoc's list of subsystems
- `src/io/brodgar/addon/Addon.java` — `clientPerformance` beside `clientClient`
- `src/haven/OptWnd.java` — `gamepanels()`: the entry, first (`// addon: 160.1`)
- `docs/addons/api/client/README.md` — the `## performance()` section, the *Handle | Covers* row,
  the *Before the client is up* paragraph
- `docs/addons/api/README.md` — the `hafen.client` row
- `docs/client/prefs-and-options.md` — a row in *What the Options window writes*
- `tools/docverbs.py` — `"performance": None` in `RECEIVERS`, beside `"video": None`

**160.2 — weather, sway, smoke**
- `src/haven/Glob.java` — `weather()` and `tick` (`// addon: 160.2`)
- `src/haven/res/lib/svaj/GobSvaj.java` — one expression in `placestate()`
- `src/haven/Gob.java` — `Overlay.withheld`, `Overlay.init`, `plumes()` (`// addon: 160.2`)
- `src/io/brodgar/perf/Performance.java` — `withheldWeather(Resource)`, `plume(Gob, Sprite)`,
  `plumes()`
- create `docs/client/world-effects.md` (weather, sway, plumes); `docs/client/README.md` — its row
- `docs/addons/api/overlay.md` — the withheld-plume rule

**160.3 — ground decoration**
- `src/haven/Tileset.java` — `SpriteFlavor.flavor`, the `ambient` field (`// addon: 160.3`)
- `src/haven/MCache.java` — `Grid.Cut.decostamp`, the compare in `Grid.getfo` (`// addon: 160.3`)
- `src/io/brodgar/perf/Performance.java` — `decorationGeneration()`, `ambient(Resource)`
- create `docs/client/ground-detail.md` (decoration pass, cut lifecycle); `docs/client/README.md` —
  its row; `docs/client/terrain-raster.md` — a *See also* line

**160.4 — ground blend and transitions**
- `src/haven/resutil/TerrainTile.java` — `Blend(MapMesh)` (`// addon: 160.4`)
- `src/haven/MapMesh.java` — `build` (`// addon: 160.4`)
- `src/haven/MCache.java` — `Grid.Cut.groundstamp`, the compare in `Grid.getcut`
- `src/io/brodgar/perf/Performance.java` — `groundGeneration()`
- `docs/client/ground-detail.md` — the blend-layers and transition rows

**160.5 — crops and forageables**
- create `src/haven/res/lib/plants/GrowingPlant.java`, `TrellisPlant.java` (adopted, v11)
- create `src/haven/res/lib/gplant/GaussianPlant.java` (adopted, v1)
- `src/haven/Gob.java` — `replant()` (`// addon: 160.5`)
- `src/io/brodgar/perf/Performance.java` — `sprouts(int)`, `replant()`
- `docs/client/world-effects.md` — the plant-sprites rows

Each task also ships its suite at `addons/160-performance.N/` (`manifest.json`, `main.lua`).

## Risks & gotchas — the classes and members read

- **Every `public` member of `haven` is an ABI served code links against**
  ([published-code.md](../../docs/client/published-code.md)). Nothing here changes a signature:
  `CSprite.addpart(float, float, float, Pipe.Op, RenderTree.Node)` is used as is, `Glob.weather()`
  keeps its return type, `Gob.addol(Overlay, boolean)` and the `Overlay` constructors are untouched,
  `Tileset.SpriteFlavor`'s two public finals stay; a field added to an inner class is safe.
- **`GobSvaj` is a version-pinned copy that carries a fix** (069: "change the word in its comment
  and nothing else; a stray edit there restores a foliage bug"). 160.2 changes exactly one expression
  — the return of `placestate()` — and nothing in `st()`, where the multi-session origin fix lives.
- **`Deferred.invalidate()` is the lazy one; `rebuild()` schedules now.** The `addon: (terrain
  loading)` comment on `MCache.Deferred.invalidate` records the flood `rebuild()` caused at login
  (every cut of every grid, twice). A setting write bumps a generation; the stamp compare in
  `Grid.getcut`/`getfo` calls `Deferred.invalidate()` on the half that moved, and only when the scene
  asks for that cut. `Deferred.get()` keeps answering the old value until the new build completes
  (`val` stays; `def.done()` swaps and disposes the previous), and `MapView.MapRaster.Grid.tick`
  replaces the scene slot when `getcut` answers a different object (`cur.a != cut`). Nothing else is
  needed for the swap.
- **A rebuilt mesh re-lays its overlays for free.** The `mesh` `Deferred`'s `update` sets
  `Grid.olseq = -1`, and `Grid.getolcut` drops and rebuilds every cut's `ols`/`olols` on that.
- **Two generations, not one.** `HSlider.changed()` fires on every step of a drag; a decoration drag
  bumps `decorationGeneration` alone, so the ground mesh — two passes over 625 tiles plus `dotrans`
  per cut — is never rebuilt for a slider that does not touch it.
- **`MCache.getfo` takes `synchronized(grids)`; `MCache.getcut` does not.** The stamp compare must
  take no lock of its own: `Deferred.invalidate()` is `synchronized(this)` on the `Deferred` and
  returns at once; the stamp is a `volatile int` on `Cut`. Two threads racing the compare at worst
  invalidate twice, and `rebuild()` cancels the previous future.
- **Remembered ground follows, unbudgeted.** `MapView.RecallTerrain` admits a cut "already built"
  through `MCache.cutbuilt` (`Cut.mesh.cur() != null`, still true while a rebuild is pending), so a
  ground switch re-meshes every drawn recall cut in a burst outside `recallmaxbuild`. Accepted: a
  switch is flipped rarely, only drawn cuts rebuild, and the old mesh stays on screen meanwhile.
  Stated on the new page.
- **`MapMesh.build` reseeds `rnd` per tile** (`rnd.setSeed(ns)` after `lay`/`dotrans`), so skipping
  `dotrans` changes no other tile's randomness.
- **`SpriteFlavor.flavor` reseeds `ornd` per tile and the first draw decides**, so scaling `p`
  yields a strict subset of the full set: the same pieces at the same places. `this.res.get()` is
  its first statement, so a `Loading` propagates before any state is touched; the `ambient` flag is
  computed lazily right after it, once per `SpriteFlavor` (they are cached per tileset by
  `Utils.cache` in `Tileset`'s constructor).
- **Ambient decoration** is a resource with a `ClipAmbiance.Desc` layer (`clamb`), or an
  `Audio.Clip` layer with id `"amb"` (`res.layer(Audio.clip, "amb")`; `Audio.Clip extends
  Resource.IDLayer<String>`), or a `RenderLink.Res` whose `l instanceof RenderLink.AmbientLink`
  (`res.layers(RenderLink.Res.class)`).
- **`TerrainTile.Blend` is built once per mesh** (`MapMesh.DataID<Blend> blend`, `m.data(blend)`)
  and shared by `faces` and `_faces`, so the transition skirts of a cut and its ground agree.
  `TerrainTile.RidgeTile` (`@ResName("trn-r")`) is the same class family and needs nothing.
  With blend off the base layer must be **opaque**: `bv[0] = 1` and `bv[i>0] = 0` before the
  unchanged post-processing (`v*1.2-0.1`, clamp) and the unchanged `en` loop, which then enables the
  base alone and marks `fall`. Skipping the `lwc` noise and the `sr` blur passes is what saves the
  build; both would leave 1/0 arrays as they are.
- **Weather is composed twice a frame.** `MapView.updweather` (from `MapView.tick`) reads
  `Glob.weather()` for the state ops and adds every `Weather` that is a `RenderTree.Node`; a node
  missing from the list is removed from `rweather` and the tree. `Glob.tick` ticks every `Weather`
  in `wmap`. Both must skip a withheld resource, or a withheld rain would go on simulating drops.
  `wmap` is keyed by `Indir<Resource>`; `get()` on one not yet loaded throws `Loading`, and in that
  window the factory branch throws the same `Loading` — so the name check catches `Loading` and
  treats the entry as not withheld for that frame. `Glob.tick` iterates `wmap.values()` with
  `i.remove()`; changing it to `entrySet()` keeps the remove valid.
- **A plume's `Sprite.res` is known only after `Overlay.init` creates it**, so the withhold
  decision is taken there, after `sm.create(this)` and before `RUtils.multiadd(gob.slots, this)`.
  `Gob.added(slot)` adds only overlays whose `slots != null`, so a withheld overlay — which never
  got slots — is skipped there with no extra code. `ISmoke` simulates in `autotick` (a
  `TickList.Ticking`), which runs only while the node is in the tree: a withheld plume costs nothing.
  The owner's resource is `ResDrawable.getres()` → `rres`, already resolved, so the clue exemption
  (`gfx/terobjs/clue`) throws nothing.
- **The plume walk mirrors `Gob.addonsyncvis`.** Per gob, under `gob.defer`: withhold →
  `RUtils.multirem(new ArrayList<>(ol.slots)); ol.slots = null`; show → `RUtils.multiadd(gob.slots,
  ol)` (which fills `ol.slots` through `Overlay.added`). `Overlay.slots` is private to the inner
  class, reachable from `Gob`. The walk is `synchronized(oc)` over `oc` for each `Sessions.Member`
  whose `ui != null && ui.sess != null`, collecting first, deferring after. Withholding keeps the
  overlay in `gob.ols`, so `GobOverlayAdded` fires and `gob:overlay()` lists it.
- **`ResDrawable.sdt` is package-private**, so the replant walk is a `Gob` method:
  `setattr(new ResDrawable(this, rd.res, rd.sdt.clone()))` then `updated()`. `ResDrawable`'s
  constructor calls `res.get()` (already loaded) and `Sprite.create`, then `spr.age()`. A plant is a
  gob whose `Drawable` is a `ResDrawable` and whose `rres.getcode(Sprite.Factory.class, false)` is
  an instance of one of the three adopted classes — a served class of a bumped version is not ours
  and is not re-created, correctly: the setting does not reach it.
- **An adopted copy wins only at its pinned version** (`Resource.ResClassLoader.loadClass`): a
  server bump warns and uses the served code, so the plant amounts silently stop applying. Not a
  failure: `find-updates` is how it is noticed, as for `lib/svaj` and `lib/vmat`.
- **Java 8 source level**: no `var`, no `List.getFirst()`, no pattern-matching `instanceof`.
- **The Options window now opens on Performance**: `OptWnd.SettingsPanel`'s constructor selects
  `game.entries.get(0)`.
- **`tools/docverbs.py`** skips a receiver spelling it does not map and counts it; `"performance":
  None` beside `"video": None` keeps the page's `performance:` calls out of the unresolved list, as
  the other panels are.

## Discarded alternatives

- **Gating each weather inside an adopted copy of its own resource** (`gfx/fx/rain`, `snow`,
  `clouds`, `wet`, `seasonmap`) — five version-pinned copies for five one-line gates, each silently
  inert the day the server bumps that resource, when the client composes every weather in one place.
- **A decoration switch instead of a percentage** — `0` is the switch, and scaling the per-tile draw
  yields a stable subset, so the slider costs nothing a switch does not.
- **Deciding decoration after building every piece and discarding most** — builds the sprites and
  resolves the resources of pieces that are then thrown away, on every cut build.
- **Keeping the topmost variant when blend is off** — the base is the tileset's own look; the
  variants are what the noise paints over it.
- **Removing a plume's overlay on `smoke(false)`** — nothing brings it back until the server
  re-sends it; withholding is symmetric and costs nothing while withheld.
- **Exempting plumes by the owner's kind (a list of kiln-like objects)** — the plume's own resource
  names it, and a list of owners goes stale with every new object.
- **One generation for the mesh and the decoration** — a slider drag would re-mesh the ground it
  does not touch, twenty times.
- **Eager `rebuild()` of every cut of every loaded grid on a write** — floods the `Defer` pool with
  cuts nobody draws; the terrain-loading comment on `Deferred.invalidate` records the same flood at
  login.
- **Fractions `0.0`..`1.0` at the API, as the volumes are** — rejected by the maintainer: whole
  percentages are what the panel shows and refuse cleanly.
- **The buff screen effects as three more switches on the same filter** — rejected by the
  maintainer: comfort, not performance; the boundary is in the spec.
- **A cap in sprouts (an absolute count) instead of a percentage** — crops carry different counts,
  and a percentage is one rule across them.
- **Disabling sway by not attaching the attribute when a tree is built** — needs an adopted copy of
  the tree library, misses the sway attribute the server sends as its own, and cannot toggle live;
  the placement is recomposed every tick anyway.
- **Withholding a plant gob's drawable instead of re-creating it** — the sprout count is decided at
  sprite creation, so the sprite has to be rebuilt.
- **An addon-only surface with no panel** — the player without an addon needs the dial; the panel
  and the handle write through one setter.
- **Statics on the consuming `haven` classes** (the `MapView.recallon` shape) — twelve settings over
  eight classes; one holder keeps the bounds, the generations and the walks in one place.
