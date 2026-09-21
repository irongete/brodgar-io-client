# 160 — Performance: tasks

Five tasks, in this order: the surface first, because every later suite drives the settings through
it. Every Java edit in `src/haven/**` is tagged `// addon: 160.N`. Every task builds from a clean
tree (`rm -rf build/classes; ant hafen-client` → `BUILD SUCCESSFUL`, then `ant bin`), runs
`python tools/docverbs.py` and `python tools/refusalverbs.py` (both must exit `0`), and ships its
suite at `addons/160-performance.N/` with `manifest.json` (`"id": "160-performance.N"`,
`"permissions": ["client.settings"]`, plus `"console.run"` where named) and `main.lua`, registered as
`hafen.console():on("t160", …)` and printing the `[pass]`/`[fail]`/`[manual]`/`[summary]` lines the
skeleton in `specs/159-sqlite-store/addons/159-sqlite-store.1/main.lua` prints. A suite restores
every setting it wrote before its `[summary]` line: it never leaves the store changed. Java is source
level 1.8: no `var`, no `List.getFirst()`, no pattern-matching `instanceof`. No mention of any other
client, anywhere.

- [x] **160.1 — Performance: the store, the panel and `options:performance()`.** Adds the whole
      settings surface; nothing draws differently yet.
      *The store.* Create `src/io/brodgar/perf/Performance.java`, `public final class Performance`
      with a private constructor. Bounds as constants: `FLAVOR_MIN = 0`, `FLAVOR_MAX = 100`,
      `PLANT_MIN = 1`, `PLANT_MAX = 100`. Twelve `public static volatile` fields, each read from its
      preference at class init with its default and clamped into its bounds where it has any:
      `int flavor` (`perf-flavor`, 100), `int crops` (`perf-crops`, 100), `int forage`
      (`perf-forage`, 100), `boolean groundBlend` (`perf-groundblend`, true), `boolean transitions`
      (`perf-transitions`, true), `boolean treeEffects` (`perf-treeeffects`, true), `boolean smoke` (`perf-smoke`,
      true), `boolean clouds` (`perf-clouds`, true), `boolean rain` (`perf-rain`, true), `boolean
      snow` (`perf-snow`, true), `boolean wetGround` (`perf-wetground`, true), `boolean seasonTint`
      (`perf-seasontint`, true) — `Utils.getprefi`/`Utils.getprefb`. Twelve setters named after
      their field (`flavor(int percent)`, `groundBlend(boolean on)`, …), each the **only** write
      path: it clamps a percentage into the bounds, persists and moves the field in one statement
      (`Utils.setprefi("perf-flavor", flavor = percent);` — the shape `MapView.recallon` and
      `ClientPanel` write in). Two generation counters, `private static volatile int`, read through
      `flavorGeneration()` and `groundGeneration()`: `flavor(int)` bumps the first and
      `groundBlend`/`transitions` bump the second, **only when the value actually changed** (a
      slider drag repeats values). Nothing consumes them in this task; 160.3 and 160.4 do.
      *The panel.* Create `src/io/brodgar/ui/PerformancePanel.java`, `public class PerformancePanel
      extends OptWnd.Panel`, constructor `PerformancePanel(OptWnd opt)` beginning `opt.super();` —
      a clone of `ClientPanel`'s pattern and of nothing else: every control re-reads its static in
      `tick(double)` and writes through the setter in `set`/`changed`, so a Lua write moves an open
      panel and a click is the same act as a write. Layout, top to bottom, with a `Label` heading
      each section as `ClientPanel` heads *Remembered ground*: **Ground** — slider *Flavor
      objects* (`HSlider(UI.scale(140), FLAVOR_MIN, FLAVOR_MAX, Performance.flavor)`
      beside a `Label` reading `val + " %"`, the row laid with `addhlp` exactly as the *Range* row of
      `ClientPanel`), box *Blend ground textures*, box *Tile transitions*; **Plants** — slider *Crop
      density* and slider *Forageable density* (`PLANT_MIN`..`PLANT_MAX`); **Objects** — box
      *Tree effects*, box *Smoke plumes*; **Weather** — boxes *Cloud shadows*, *Rain*, *Snow*, *Wet
      ground*, *Seasonal tint*. Every control gets a `settip(…, true)` in the panel's own voice: what
      it draws less of, that it applies at once, and for flavor objects that the pieces which make
      ambient sound stay at `0`; for smoke that a scent trail's smoke always stays; for the plant
      sliders that at least one sprout is always drawn. End with `pack()`.
      *The entry.* `OptWnd.SettingsPanel.gamepanels()`: `ret.add(new PanelEntry("Performance", ()
      -> new io.brodgar.ui.PerformancePanel(OptWnd.this)));` as the **first** line of the list,
      with a `// addon: 160.1` comment saying that the view opens on `entries.get(0)` and so on this
      panel.
      *The handle.* Create `src/io/brodgar/addon/PerformanceOptions.java`, a clone of
      `ClientOptions`: `create(Addon owner)` opens `OptionsHandle.open("Options(performance)")`
      and closes it with `OptionsHandle.close(handle, "performance", methods(owner, handle), "the
      performance options", "each of them reads with no argument and writes with one")`. Twelve
      `OptionsMethod(owner, handle, "performance:<verb>")`, verbs `flavor`, `crops`, `forage`,
      `groundBlend`, `transitions`, `treeEffects`, `smoke`, `clouds`, `rain`, `snow`, `wetGround`,
      `seasonTint`. A percentage `onWrite` is `Args.integer(value, verb(), "percent", <hint>,
      Performance.FLAVOR_MIN, Performance.FLAVOR_MAX)` (the six-argument form: it refuses a
      non-number, a fraction and an out-of-range value each in its own sentence, naming the bounds
      and the value) then the setter; hints: *how many of the flavor objects are drawn, in
      percent* / *how many of a crop tile's sprouts are drawn, in percent* / *how many of a
      forageable clump's sprouts are drawn, in percent*. A switch `onWrite` is `bool(value, "on",
      <hint>)` then the setter. Every `onRead` is `LuaValue.valueOf(<field>)`. Register it in
      `OptionsHandle.create` as `m.set("performance", …)` shaped exactly like the `client` entry
      (`self(a, "performance")`, cached on a new `Addon.clientPerformance` declared beside
      `clientClient`), and add `performance()` to the class javadoc's list of subsystems.
      *Docs.* `docs/addons/api/client/README.md`: a first row in the *Handle | Covers* table —
      `options:performance()` · *How much world is drawn: flavor objects, crop and forageable
      density, ground blending and transitions, tree effects, smoke, weather.* · `[Below](#performance)`; a `##
      performance()` section before `## interface()` with the *Method | Type | Permission |
      Description* table of the twelve (read Unprotected / write `client.settings`, the ranges and
      what each decides, in the spec's words), a *Rule | Detail* table (whole percentages in the
      panel's unit; `1` is the floor of the two plant densities and `0` of flavor objects; the defaults draw
      the client's own picture; every write applies live and how soon; flavor `0` keeps the
      ambient-sound pieces; a scent trail keeps its smoke; a read answers before the world is up)
      and a runnable example of at most twelve lines with descriptive names (`local performance =
      hafen.client():options():performance()`); `performance()` added to the always-answer list in
      *Before the client is up*. `docs/addons/api/README.md`: the `hafen.client` row reads
      *performance, interface, video, audio, camera, client*. `docs/client/prefs-and-options.md`: a
      row in *What the Options window writes* — `Performance (fork)` · the `perf-*` prefs, each
      written live-and-persisted in one statement, read by the seams in `Tileset`, `TerrainTile`,
      `MapMesh`, `MCache`, `Glob`, `Gob` and the `lib/svaj` copy (no `io.brodgar` class is named on
      that page). `tools/docverbs.py`: `"performance": None` in `RECEIVERS`, beside `"video": None`.
      *Its suite* declares `client.settings`. It takes `performance = hafen.client():options():performance()`,
      remembers all twelve values, and checks, one line each: identity and print (`performance ==
      hafen.client():options():performance()` and `tostring(performance) == "Options(performance)"`);
      the round trip of all twelve (writes `flavor(37)`, `crops(42)`, `forage(55)` and every
      switch `false`, reads each back equal); chaining (`performance:rain(false):snow(false)` returns
      the handle by identity); a number refused (`flavor("50")` raises containing
      `performance:flavor: percent must be a number`, and `flavor(50.5)` containing `must be a
      whole number` and `got 50.5`); a range refused (`flavor(101)` containing `from 0 to 100,
      got 101`, `flavor(-1)` containing `got -1`, `crops(0)` and `forage(0)` containing `from 1
      to 100, got 0`, and `flavor()` still reading `37` after all four); a switch refused
      (`rain("no")` and `rain(0)` containing `performance:rain: on must be true or false`, `rain(nil)`
      containing `must not be nil`); an unknown verb (`performance:foo()` raises containing
      `performance has no verb 'foo'` and naming `flavor` and `seasonTint`); the arity
      (`performance:flavor(1, 2)` raises containing `use a COLON call with at most one
      argument`, and `hafen.client():options().performance()` raises containing `use a COLON call on
      the options handle`); and the restore (every remembered value written back and read equal).
      `[manual]`: open Options — it opens on *Performance*, first in the Game list, and every control
      shows the stored value; from `:lua` write `hafen.client():options():performance():flavor(37):rain(false)`,
      reopen the panel — expect: the *Flavor objects* slider at `37 %`, the Rain box clear; drag the slider
      to 60 — expect: `performance:flavor()` reads `60`; `:reload` and run `:t160` again — expect:
      the run's first line reports `flavor at load: 60` (at load `main.lua` only remembers
      `performance:flavor()` in a local — a suite never starts itself — and the run prints it
      first); then write the values back.
      <!-- extra context: src/io/brodgar/addon/Args.java (integer's six-argument form and the sentences it raises); docs/addons/api/client/README.md is the page being extended — keep its voice and its tables -->

- [x] **160.2 — Weather, tree effects and smoke plumes.** The five weather switches, `treeEffects` and
      `smoke` act.
      *Weather.* `Performance.withheldWeather(Resource res)`: by `res.name` — `gfx/fx/clouds` ↦
      `!clouds`, `gfx/fx/rain` ↦ `!rain`, `gfx/fx/snow` ↦ `!snow`, `gfx/fx/wet` ↦ `!wetGround`,
      `gfx/fx/seasonmap` ↦ `!seasonTint`, anything else `false`. In `Glob.weather()`, first thing
      inside the `for` over `wmap.entrySet()`: resolve `cur.getKey().get()` inside `try`/`catch
      (Loading)` and `continue` when it is withheld (a `Loading` means not withheld this frame — the
      factory branch below would throw the same `Loading`). In `Glob.tick`, change the weather loop
      to iterate `wmap.entrySet()` (the iterator's `remove()` stays valid) and skip `tick(dt)` for a
      withheld entry, same `try`/`catch(Loading)`, without removing it. `MapView.updweather` needs no
      change: a `Weather` node missing from the list leaves `rweather` and the tree, and comes back
      when listed again. Both edits `// addon: 160.2`.
      *Tree effects.* `src/haven/res/lib/svaj/GobSvaj.java`, `placestate()`: `return(io.brodgar.perf.Performance.treeEffects
      ? st() : null);` and **nothing else in the file** — `st()` carries the multi-session origin fix
      (069). One comment line above it saying why `null` is enough: `Gob.Placed.Placement` recomposes
      `mods` every tick and skips a `null`, so a changed answer is a changed placement.
      *Plumes.* `Performance`: `PLUME = "gfx/fx/ismoke"`, `CLUE = "gfx/terobjs/clue"`,
      `withheldPlume(String ownerRes, String overlayRes)` = `!smoke && PLUME.equals(overlayRes) &&
      !CLUE.equals(ownerRes)`; the `smoke(boolean)` setter calls `plumes()` after persisting when the
      value changed; `plumes()` walks `io.brodgar.session.Sessions.members()`, and for each member
      whose `ui != null && ui.sess != null` takes `OCache oc = ui.sess.glob.oc`, copies its gobs
      under `synchronized(oc)` (`for(Gob gob : oc)`), then calls `gob.plumes()` on each outside the
      lock. In `Gob`: `withheldplume(Sprite spr)` — the owner's name is `getattr(Drawable.class)`'s
      `getres().name` (`null` when there is no drawable, or when `getres()` throws `Loading`), the
      overlay's is `spr.res.name` (`null` when `spr` or `spr.res` is), answered by
      `Performance.withheldPlume`. `Overlay.init()`: the last line becomes `if((slots == null) &&
      !gob.withheldplume(spr)) RUtils.multiadd(gob.slots, this);`. That single gate is the whole
      *show* direction too: `Gob.ctick` calls `init()` again every tick while `slots == null` and
      ticks nothing that is in no tree, so a withheld plume costs nothing and is added on the first
      tick after `smoke(true)` with no walk. `Gob.plumes()` is the *withhold* direction:
      `defer(this::syncplumes)`, and `syncplumes()` — under the gob's monitor, as `addonsyncvis` is
      — walks `ols` and for every `ol` with `ol.slots != null && withheldplume(ol.spr)` does
      `RUtils.multirem(new ArrayList<>(ol.slots)); ol.slots = null;` (the field is the inner class's
      private, reachable from `Gob`). The overlay stays in `ols`: `gob:overlay()` lists it,
      `GobOverlayAdded` fired for it, the server's removal still removes it.
      *Docs.* Create `docs/client/world-effects.md` (§12 of `DOCUMENTATION.md`; a map, under 150
      lines, class and member names, never a line number): a *What | Where* table for **weather**
      (`Glob.wmap` keyed by `Indir<Resource>`, `Glob.Weather` and `Weather.Factory` published as
      `wtr`, `Glob.weather()` instantiating on first read, `Glob.tick` ticking, `MapView.updweather`
      composing the state ops into `basic` and adding every `Weather` that is a `RenderTree.Node`
      into `rweather`; fork: the withheld check at both places), for **tree effects** (`lib/svaj`
      adopted copy, `GobSvaj.placestate`, `Gob.Placed.Placement.mods` and its per-tick `equals`;
      fork: the `treeEffects` gate), for **overlays and plumes** (`OCache.$overlay` → `OlSprite` →
      `Gob.addol(Overlay, boolean)` → `Overlay.init`; `Gob.ctick`'s retry of `init()` and its rule
      that an overlay in no tree is not ticked; a plume's particles live in `autotick`, a
      `TickList.Ticking`, so out of the tree it simulates nothing; fork: the withhold gate and the
      walk), and the gotchas met (a weather not yet loaded throws `Loading` from `Indir.get`; the
      svaj copy's fix; `Overlay.slots` as the "in a tree" test). Leave room: 160.5 adds the plant
      rows. Add its row to `docs/client/README.md`. `docs/addons/api/overlay.md`: one rule beside
      *The game's own are read-only* — a native plume withheld by the Performance panel is still
      listed, with `native = true`, and drawn again when the switch goes back on.
      *Its suite* declares `client.settings`. It remembers the seven values it will touch. Weather
      cannot be caused, so the automated half is the plumes and the switches' round trip: it finds a
      gob with a plume in view, `session:world():gob():find(function(gob) return gob:overlay():find("gfx/fx/ismoke") ~= nil end)`;
      with one found it samples `hafen.client():profiling():render().drawSlots` (the median of ten
      reads 0.1 s apart), writes `smoke(false)`, polls the sample up to 6 s until it fell, `[pass]
      smoke(false) withheld the plume (N → M slots)`; checks the overlay is still listed
      (`gob:overlay():find("gfx/fx/ismoke") ~= nil` and `:native() == true`); writes `smoke(true)`
      and polls until the sample rose back, `[pass] smoke(true) brought it back without the server`;
      with none found it prints `[manual] stand near a burning kiln, furnace or oven and rerun`. It
      checks `treeEffects(false)` and each weather switch read back `false` after the write and `true`
      after the restore (one line). It restores everything.
      `[manual]`: at `treeEffects(false)` trees and bushes stand still on the next tick and keep their
      tilt; at `treeEffects(true)` they sway again — expect: no relog needed either way. In rain: `rain(false)`
      stops the drops and the splashes at once; `rain(true)` brings them back — expect: the same
      frame. In snow: `snow(false)` likewise. After rain: `wetGround(false)` takes the sheen off the
      ground. `clouds(false)`: no cloud shadows cross the ground. `seasonTint(false)`: the ground
      loses its seasonal tint. A scent trail keeps its smoke at `smoke(false)`.
      <!-- extra context: the "rts:" comment in Gob.ctick's overlay loop states the contract an overlay in no tree is not ticked; RUtils.multiadd/multirem; the resource names are served under exactly those spellings (checked with haven.Resource find-updates) -->

- [x] **160.3 — Flavor objects.** `flavor` acts, and the lazy rebuild of a cut's flavor
      objects exists.
      *The pass.* `Performance.ambient(Resource res)`: `true` when `res.layer(ClipAmbiance.Desc.class)
      != null`, or `res.layer(Audio.clip, "amb") != null` (`Audio.Clip extends
      Resource.IDLayer<String>`), or any `RenderLink.Res` in `res.layers(RenderLink.Res.class)` has
      `l instanceof RenderLink.AmbientLink`. `Tileset.SpriteFlavor` gets a `private Boolean
      ambient = null` computed once. In `flavor(Buffer, Terrain, Random)`, after the existing first
      statement `Resource res = this.res.get();` (a `Loading` leaves the method before any state is
      touched) and before the loops: `double p = this.p; if(io.brodgar.perf.Performance.flavor
      < Performance.FLAVOR_MAX) { if(ambient == null) ambient = Performance.ambient(res);
      if(!ambient) p = p * Performance.flavor / 100.0; }` — the loop's draw `ornd.nextDouble()
      < p` then reads the local. At `100` the field is used unchanged and the draw sequence is
      bit-for-bit upstream's. `ornd` is reseeded per tile and the first draw decides, so a lower
      setting is a strict subset: the same pieces at the same places. `// addon: 160.3`.
      *The rebuild.* `MCache.Grid.Cut`: `volatile int flavorstamp = io.brodgar.perf.Performance.flavorGeneration();`
      set at construction. `MCache.Grid.getfo(Coord cc)` becomes: take `Cut cut = geticut(cc)`; `int
      gen = Performance.flavorGeneration(); if(cut.flavorstamp != gen) { cut.flavorstamp = gen;
      cut.fo.invalidate(); }` then `return(cut.fo.get());`. `Deferred.invalidate()` is the lazy one
      (`inited = false`, the pending future cancelled): the `get()` that follows schedules one build
      and keeps answering the old `Flavobjs` until it is done, `MapView.MapRaster.Grid.tick` swaps the
      scene slot when `getfo` answers a different object, and the old value is disposed by
      `Deferred.get` after the swap. No lock is taken here: `getfo` is already inside the outer
      `MCache.getfo`'s `synchronized(grids)`, and `invalidate()` synchronises on the `Deferred`. A
      cut never built has `val == null` and is simply built at the current setting. `// addon:
      160.3`.
      *Docs.* Create `docs/client/ground-detail.md` (§12; under 150 lines): a *What | Where* table
      for **the flavor pass** (`Tileset.flavprob` and the per-flavour `p` computed in `Tileset`'s
      constructor; `Tileset.Flavor.Buffer`, `Tileset.Flavor.Terrain`, `Tileset.SpriteFlavor.flavor`
      with its per-tile reseed; `MCache.Grid.makeflavor` collecting per tileset; `MCache.Grid.Flavobjs`
      grouping by material and ticking every piece in `tick`/`gtick`; fork: the density scale and the
      ambient exemption), for **the cut lifecycle** (`MCache.Grid.Cut` and its two `Deferred`s,
      `Deferred.get` vs `cur`, `invalidate` vs `rebuild` and the terrain-loading comment, the `mesh`
      `update` that sets `Grid.olseq = -1`, `MapRaster.Grid.tick`'s `cur.a != cut` swap; fork: the
      generation stamps), and the gotchas (the per-tile reseed is what makes a subset stable; a
      `Loading` from `Indir.get` at the top of `flavor` retries the whole cut; ticking every
      flavor object every frame is the CPU half of their cost). Leave the blend and transition
      rows to 160.4. Add its row to `docs/client/README.md` and a *See also* line in
      `docs/client/terrain-raster.md`.
      *Its suite* declares `client.settings`. It prints the setting read at load first. Standing on
      grass or heath, it samples `render().drawSlots` (median of ten reads 0.1 s apart), writes
      `flavor(0)`, polls the sample up to 8 s until it fell by at least a tenth, `[pass]
      flavor(0) drew fewer slots (N → M)`; writes `flavor(100)`, polls until the sample is
      back within a tenth of the first, `[pass] flavor(100) drew them again (M → K)`; then
      `flavor(50)` and checks the sample sits between the two, `[pass] flavor(50) is between`;
      restores the remembered value. A sample that never moves is a `[fail]` carrying the numbers,
      with a `[manual]` line first saying the run needs a meadow in view.
      `[manual]`: on a summer meadow at `flavor(0)` the crickets and birds are still heard —
      expect: the ambient loops unchanged; the look at `0`, `50`, `100` — expect: the same tufts in
      the same places, fewer of them, none of them moved.
      <!-- extra context: src/haven/ClipAmbiance.java (Desc, layer name clamb), src/haven/Audio.java (Clip, the clip class constant), src/haven/RenderLink.java (Res.l, AmbientLink) -->

- [ ] **160.4 — Ground blend and tile transitions.** `groundBlend` and `transitions` act, and the
      lazy rebuild of a cut's mesh exists.
      *The blend.* `TerrainTile.Blend(MapMesh m)`: the three steps that compute the variant weights
      — the `lwc` noise loop, `setbase(buf1)` and the `sr` blur passes that end in `buf1 = buf2` —
      go inside `if(io.brodgar.perf.Performance.groundBlend) { … }`; the `else` fills
      `buf1[0]` with `1f` and leaves `buf1[i > 0]` at their fresh `0f`. Everything after (`bv =
      buf1`, the `v * 1.2f - 0.1f` post-processing, the `en` loop with its `fall`) stays exactly as
      it is: it then enables the base layer alone, opaque, on every tile, and `faces`/`_faces` emit
      one layer where they emitted up to `var.length + 1`. The blur would leave 1/0 arrays as they
      are; skipping it and the noise is the build-time half of the saving. `// addon: 160.4`.
      *The transitions.* `MapMesh.build`: `if(io.brodgar.perf.Performance.transitions) dotrans(m,
      rnd, c, gc);` — `rnd.setSeed(ns)` follows on the next line, so no other tile's randomness
      moves. `// addon: 160.4`.
      *The rebuild.* `MCache.Grid.Cut`: `volatile int groundstamp = Performance.groundGeneration();`
      at construction, and `MCache.Grid.getcut(Coord cc)` compares it exactly as 160.3's `getfo`
      does, calling `cut.mesh.invalidate()` on a mismatch — `getcut` is not under `synchronized
      (grids)`, which is why the stamp is `volatile` and the compare takes no lock: two threads
      racing it at worst invalidate twice, and `rebuild()` cancels the previous future. A rebuilt
      mesh sets `Grid.olseq = -1` through its `Deferred.update`, so `Grid.getolcut` re-lays every
      overlay over the new vertices with no further code. Remembered ground (`MapView.RecallTerrain`)
      and another session's ground (`MapView.SessionTerrain`) come through this same accessor and
      follow; the recall raster admits an already-built cut for free (`MCache.cutbuilt` reads
      `mesh.cur() != null`, still true while a rebuild is pending), so a switch re-meshes every
      drawn recall cut outside its build budget — stated on the page, accepted because a switch is
      rare and the old mesh stays on screen meanwhile. `// addon: 160.4`.
      *Docs.* `docs/client/ground-detail.md`: the rows for **the blend layers** (`TerrainTile.Blend`
      built once per mesh through `MapMesh.DataID`, `bv`/`en`/`lvfac`, `faces` emitting one
      `SModel` per enabled variant and `_faces` the same under a transition's alpha, `RidgeTile`
      sharing it; fork: the base-only branch) and **the transition pass** (`MapMesh.build`'s second
      pass, `dotrans` reading the eight neighbours, the per-tile reseed; fork: the switch), and the
      recall-terrain gotcha above. Keep the page under 150 lines; if the rows do not fit, split the
      cut lifecycle into `docs/client/cut-lifecycle.md` and index it.
      *Its suite* declares `client.settings`. The same sampler as 160.3: baseline; `groundBlend(false)`
      → the sample falls within 8 s → `[pass]`; `groundBlend(true)` → back within a tenth → `[pass]`;
      `transitions(false)` → falls → `[pass]`; `transitions(true)` → back → `[pass]`; both off →
      lower than either alone → `[pass]`; restore. A sample that never moves is a `[fail]` with the
      numbers, and the run needs mixed ground in view (grass beside dirt, sand or forest floor),
      said on a `[manual]` line first.
      `[manual]`: at `groundBlend(false)` every tile of one type wears one texture, no noise, no
      patches — expect: a uniform meadow; at `transitions(false)` the borders between two tile types
      are hard edges — expect: no skirt; both back on — expect: the upstream picture, cliffs and
      water included.
      <!-- extra context: src/haven/resutil/TerrainTile.java lines of Blend's constructor and setbase; src/haven/MapMesh.java build; specs/120's rejected "skip dotrans for remembered ground" is prior art on why one mesh path, not two -->

- [ ] **160.5 — Crop and forageable sprouts.** `crops` and `forage` act, through two adopted
      copies, and plants in view follow a write.
      *The copies.* Run `java -cp bin/hafen.jar haven.Resource get-code lib/plants lib/gplant` from
      the tree's root (`doc/resource-code`): it writes `src/haven/res/lib/plants/GrowingPlant.java`
      and `TrellisPlant.java` annotated `@haven.FromResource(name = "lib/plants", version = 11)`, and
      `src/haven/res/lib/gplant/GaussianPlant.java` annotated `version = 1`. Confirm the versions
      with `java -cp bin/hafen.jar haven.Resource find-updates` (note: its directory argument is
      read from the *second* positional, so pass a placeholder first: `find-updates x src`) — an
      empty answer is current. Give each file the preamble `Svaj.java` carries (a local copy under
      `doc/resource-code`, adopted for one change, wins only at its pinned version, degrades to the
      served code with a warning on a bump) and change **only** what follows.
      *The amount.* `Performance.sprouts(int count, int percent)`: `percent >= PLANT_MAX ? count :
      Math.max(1, (int)Math.round(count * percent / 100.0))`. `GrowingPlant.create`: `int drawn =
      io.brodgar.perf.Performance.sprouts(num, Performance.crops);` and the loop runs to `drawn`
      — nothing else moves, so the drawn sprouts are the first `drawn` of the full set, same
      variants, same offsets, same rotations. `TrellisPlant.create`: `int drawn = sprouts(num,
      crops); float d = 11f / drawn; float c = -5.5f + (d / 2);` and the loop runs to `drawn` — the
      sprouts spread evenly along the trellis rather than crowding one end. `GaussianPlant.create`:
      after `int num = …;`, `int drawn = sprouts(num, Performance.forage);` and the loop runs to
      `drawn`. At `100` each is upstream's code path exactly.
      *The follow.* The `crops(int)` and `forage(int)` setters call `replant()` after persisting when
      the value changed; `Performance.replant()` walks sessions and gobs exactly as `plumes()` does
      and calls `gob.replant()`. `Gob.replant()` (`// addon: 160.5`): `defer(this::syncplant)`;
      `syncplant()` takes `Drawable d = getattr(Drawable.class)`, returns unless `d instanceof
      ResDrawable`, reads `Object factory = rd.rres.getcode(Sprite.Factory.class, false)` inside
      `try`/`catch(Loading)` (return on it), returns unless `factory instanceof
      haven.res.lib.plants.GrowingPlant || … TrellisPlant || haven.res.lib.gplant.GaussianPlant`
      (a served class of another version is not ours and is left alone, which is right: the setting
      does not reach it), then `setattr(new ResDrawable(this, rd.res, rd.sdt.clone()));
      updated();` — `ResDrawable.sdt` is package-private, which is why this lives in `Gob`; the
      constructor resolves the already-loaded resource, creates the sprite at the new amount and ages
      it; `setattr` swaps the slots and disposes the old drawable through the path a re-sent
      attribute takes. The gob's id, position, name, state bytes and overlays are untouched.
      *Docs.* `docs/client/world-effects.md`: the rows for **plant sprites** (`lib/plants`:
      `GrowingPlant` scatters `num` parts per tile, `TrellisPlant` lines them along the trellis;
      `lib/gplant`: `GaussianPlant` draws `numl`..`numh` parts in a gaussian spread; the factory is
      the resource's `Sprite.Factory` reached through `Resource.getcode`; `CSprite.addpart` with an
      explicit angle; `ResDrawable` holding `res`, `rres`, `sdt` and `spr`; fork: the amount and the
      re-creation) and the gotcha that the count is fixed at sprite creation, so a change is a new
      sprite. Add the three copies to the adoption row of `docs/client/services.md` if that row
      lists them by name (read it; otherwise leave it).
      *Its suite* declares `client.settings`. It prints the two settings read at load. It finds a
      field crop in view — `session:world():gob():find(function(gob) local name = gob:name(); return
      name ~= nil and name:find("gfx/terobjs/plants/", 1, true) ~= nil and name:find("trellis", 1,
      true) == nil end)` — and with one: remembers `gob:name()`, samples `render().drawSlots`
      (160.3's sampler), writes `crops(1)`, polls up to 8 s until the sample fell, `[pass] crops(1)
      drew fewer sprouts (N → M)`; checks the same gob still `exists()` with the same `name()`,
      `[pass] the crop is the same object`; writes `crops(100)`, polls until back within a tenth,
      `[pass] crops(100) drew them again`. With no crop in view: `[manual] stand in a field and
      rerun`. Then the same for `forage(1)`/`forage(100)` over a gob whose name contains
      `gfx/terobjs/herbs/`: a fall is a `[pass]`; no fall or no gob is `[manual] stand near a
      forageable that grows as a clump and rerun` (a single-mesh forageable does not use the clump
      factory and cannot move the number). Restores both. Last, the feature's closing check (the
      spec's fifth criterion, this being the last task): it remembers all twelve settings, writes
      every one to its default, samples, and — when the twelve were already at their defaults when
      the run began — checks the sample against the run's first sample within a tenth, `[pass] the
      defaults draw the same scene`; otherwise `[manual] set every control to its default and rerun`;
      then restores the twelve.
      `[manual]`: a field at `crops(1)` shows one sprout per tile, its stage still readable, and a
      trellis one sprout centred on the trellis — expect: the same crop names on hover; `crops(100)`
      — expect: the full field, same layout as before the run; with every control at its default —
      expect: the world as it was before the feature: the flavor objects, the ground, swaying trees,
      smoke, weather; on a server-side version bump of `lib/plants` (`find-updates` lists it) the
      client warns once and draws the full field whatever the setting — expect: no error, the
      setting inert.
      <!-- extra context: src/haven/res/lib/svaj/Svaj.java (the preamble to copy); doc/resource-code (get-code, the -o flag, find-updates); src/haven/Resource.java getcode(Class, boolean) -->
