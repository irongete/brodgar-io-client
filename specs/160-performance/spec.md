# 160 — Performance

## What & why

The client draws everything the server describes at the fidelity upstream chose once, for every
machine: every tuft of grass a tile can seed, every sprout a crop tile carries, the noise-blended
texture variants of the ground and the transition skirts between tile types, cloud shadows over the
whole scene, rain and snow particles, the shader that sways every tree, the particle plume over every
lit kiln and furnace. On a weak GPU or a laptop that is the difference between a playable frame rate
and a slideshow, and the player has no dial for any of it — the Video panel offers shadows, render
scale and lights, and nothing about *how much world* is drawn.

**Performance** is that dial: the first panel under **Options ▸ Game** — the one the window opens
on — and a handle at `hafen.client():options():performance()`, one setting per thing the client can
draw less of. Each setting is an amount or a switch, persists in the client's own preference store,
moves the open panel when written from Lua, and **applies live** — no relogin, no restart — through
machinery the engine already has: the cut meshes' `Deferred` rebuild, the per-frame weather
composition, the gob's per-tick placement, the overlay slot surgery `gob:visible` already does. At the
defaults the client draws exactly what it draws today: every seam this feature opens is a no-op until
a setting leaves its default, so a player who never opens the panel sees no change at all.

The addon side is not decoration. An addon can read the frame profiler and lower detail when the
frame rate drops, or raise it on a machine that can afford it; a bot running an alt can turn
everything down. It is the same `options()` tree, the same `client.settings` permission and the same
arity-is-the-verb grammar as every other panel handle.

## The surface

**Panel**: *Performance*, the first entry of Options ▸ Game (before *Interface settings*), so the
settings view opens on it. **Handle**: `hafen.client():options():performance()`, one handle per
addon, stateless like the other panels, `tostring` reads `Options(performance)`.

| Verb | Type | Range / values | Default | What it decides |
|---|---|---|---|---|
| `flavor()` / `flavor(percent)` | number | whole `0`..`100` | `100` | How many of the flavor objects a tile seeds are drawn: the tufts, pebbles and flowers a tileset scatters over its ground. `0` keeps only the pieces that emit ambient sound. |
| `crops()` / `crops(percent)` | number | whole `1`..`100` | `100` | How many of a crop tile's sprouts are drawn, field crops and trellis crops alike. At least one sprout is always drawn, so the growth stage stays readable. |
| `forage()` / `forage(percent)` | number | whole `1`..`100` | `100` | The same for forageables that grow as a clump. At least one. |
| `groundBlend()` / `groundBlend(flag)` | boolean | `true` / `false` | `true` | Whether the ground blends its texture variants by noise. Off, every tile draws its tileset's base texture alone: one layer of ground where there were up to several. |
| `transitions()` / `transitions(flag)` | boolean | `true` / `false` | `true` | Whether the skirts between two tile types are drawn. Off, tile borders are hard edges. |
| `treeEffects()` / `treeEffects(flag)` | boolean | `true` / `false` | `true` | Whether trees and bushes sway in the wind. Off, they stand still; their random tilt stays. |
| `smoke()` / `smoke(flag)` | boolean | `true` / `false` | `true` | Whether smoke plumes are drawn: kilns, furnaces, ovens, chimneys, fires. A scent trail's smoke is never withheld: it is information, not decoration. |
| `clouds()` / `clouds(flag)` | boolean | `true` / `false` | `true` | Cloud shadows moving over the ground. |
| `rain()` / `rain(flag)` | boolean | `true` / `false` | `true` | Rain particles and their splashes. |
| `snow()` / `snow(flag)` | boolean | `true` / `false` | `true` | Snow particles. |
| `wetGround()` / `wetGround(flag)` | boolean | `true` / `false` | `true` | The sheen the ground takes on after rain. |
| `seasonTint()` / `seasonTint(flag)` | boolean | `true` / `false` | `true` | The seasonal tint of the ground. |

The grammar is the options tree's: a colon call, no argument reads, one argument writes and hands
the handle back so writes chain; an explicit `nil` raises; a wrong type raises naming the option and
its parameter before anything is looked up; a value outside the range raises naming the bounds and
the value, and leaves the setting as it was; a misspelt verb raises naming what the handle answers.
Every write needs `client.settings`; every read needs nothing. The handle answers before the world
is up, as `interface()`, `camera()` and `client()` do: its backing is the client's own statics, built
with the class.

Percentages are **whole numbers** in the unit the panel shows. `flavor` allows `0`; the two plant
amounts start at `1` because a plant tile that draws nothing would be a crop the player cannot read.

## How each setting takes effect

| Setting | Where it decides | When a change shows |
|---|---|---|
| `flavor` | Where a tileset's flavor objects are seeded per tile: the per-tile draw against the tileset's own probability is scaled by the percentage, so a lower setting is a strict subset of the full set — the same pieces at the same places, fewer of them — and a piece whose resource carries an ambient-sound layer is seeded at full probability whatever the setting | The flavor objects of every drawn cut are rebuilt lazily: a change bumps a generation, a cut compares its stamp when the scene asks for it, and only cuts the scene draws are rebuilt. What is on screen stays until its replacement is built. |
| `groundBlend`, `transitions` | Where a cut's ground mesh is built: the blend's per-vertex variant weights, and the transition pass per tile | Same lazy generation, over the cut's mesh rather than its decoration. Remembered ground and another session's ground go through the same accessor and follow. |
| `crops`, `forage` | In the plant sprite factories: the number of parts a plant sprite adds, `max(1, round(count × percent / 100))`, walking the same random sequence so a lower setting draws a prefix of the full set | Plants in view are re-created on the write (their drawable is rebuilt from the resource and state bytes it was built from); plants that come into view later are built at the new setting. |
| `treeEffects` | In the placement state the sway attribute contributes to a gob | The next tick: the gob's placement is recomposed every tick and a state that went away is a changed placement. |
| `smoke` | When a smoke overlay is attached to a gob, and on a write, over every gob's overlays | The write walks every session's objects; a plume already burning is withheld from the scene without being removed, so turning smoke back on shows it again without waiting for the server. |
| `clouds`, `rain`, `snow`, `wetGround`, `seasonTint` | Where the client composes the weather the server sent into the scene, and where it ticks that weather | The next frame. Both halves are gated: a withheld weather is neither drawn nor simulated. |

Two things never change with any setting: **what the server knows** (a crop's stage, a kiln's state,
the weather itself) and **what a click reaches** (the ground under a hidden tuft, the plant under a
withheld sprout). Every setting is client-local and purely visual.

## Acceptance criteria

Each is verifiable in-game through the task's own suite, `[manual]` where a program cannot observe.

1. **The handle and its vocabulary.** `hafen.client():options():performance()` answers the twelve
   verbs above and nothing else; `performance:foo()` raises naming the twelve; a call with two
   arguments raises naming the colon form; `tostring(performance)` reads `Options(performance)`;
   `options:performance() == options:performance()`.
2. **Reads, writes, chaining, persistence.** Each verb reads back what was written; a write returns
   the handle so `performance:rain(false):snow(false)` chains; a written value survives `:reload`
   (the store is the client's own preference file, read at class init).
3. **Refusals, each naming why.** `flavor("50")` raises naming the parameter as a number;
   `flavor(101)`, `flavor(-1)`, `crops(0)`, `forage(0)` raise naming the bounds and the value
   and leave the setting unchanged; `flavor(50.5)` raises naming a whole number; `rain("no")` and
   `rain(0)` raise naming a switch; `rain(nil)` raises rather than reading.
4. **The panel follows a write and a write follows the panel.** `[manual]`: after
   `performance:flavor(37):rain(false)`, Options ▸ Game ▸ Performance shows the *Flavor objects* slider
   at 37 % and the rain box clear; moving the slider to 60 makes `performance:flavor()` read `60`.
   The Options window opens on the Performance panel.
5. **The defaults draw the upstream picture.** With every setting at its default the scene is
   unchanged: the same flavor objects, the same ground, swaying trees, smoke, weather. `[manual]`, and a
   number: `render.drawSlots` at the defaults before the feature's writes equals `render.drawSlots`
   after every setting has been written back to its default, within the frame-to-frame jitter.
6. **Flavor objects draw less.** `flavor(0)` makes `render.drawSlots` fall, and
   `flavor(100)` brings it back, both within a bounded poll of a few seconds while standing on
   grass; the ambient sound emitters are still there at `0` (`[manual]`: crickets and birds are still
   heard on a summer meadow at `flavor(0)`).
7. **Ground blend and transitions draw less.** `groundBlend(false)` and `transitions(false)` each
   make `render.drawSlots` fall within the poll and rise again when turned back on. `[manual]`: at
   `groundBlend(false)` the ground is one texture per tile type with no variant noise; at
   `transitions(false)` tile borders are hard edges.
8. **Plant amounts draw less.** With a field crop in view, `crops(1)` makes `render.drawSlots` fall
   and `crops(100)` restores it, within the poll; the crop's `gob:name()` and its state read the same
   before and after. `forage(1)` likewise with a clump forageable in view; the suite says `[manual]`
   when none is in view rather than failing.
9. **Weather withheld.** `[manual]`, each in its weather: at `clouds(false)` no cloud shadows cross
   the ground; at `rain(false)` no drops and no splashes while it rains, and at `snow(false)` no
   flakes while it snows; at `wetGround(false)` the ground does not shine after rain; at
   `seasonTint(false)` the ground loses its seasonal tint. Turning any of them back on restores it
   within a frame without relogging.
10. **Tree effects withheld.** `[manual]`: at `treeEffects(false)` trees and bushes stand still on the
    next tick and keep their tilt; at `treeEffects(true)` they sway again.
11. **Smoke withheld, symmetrically.** With a burning kiln or furnace in view, `smoke(false)` makes
    `render.drawSlots` fall and `smoke(true)` restores it without the server re-sending anything; the
    plume is still listed by `gob:overlay():find("gfx/fx/ismoke")` while withheld. `[manual]`: a scent
    trail keeps its smoke at `smoke(false)`.
12. **Nothing is retired and nothing breaks.** `ant hafen-client` builds from a clean
    `build/classes`; `python tools/docverbs.py` and `python tools/refusalverbs.py` exit `0`; the
    served plant code still runs when the local copy's version no longer matches (the copy degrades
    to upstream behaviour with a warning, as every adopted copy does).

## Out of scope — the boundary

- **Hiding whole objects by kind** (trees, bushes, boulders, walls). That is an addon on the shipped
  `gob:visible(flag)`, written in the addons repository, not client work: this feature draws *less of*
  what it draws, never nothing of an object.
- **Flattening the terrain.** Not detail: the same triangles drawn at another height, and every
  click, placement and water read would have to agree with the flattening. A feature of its own if
  ever wanted.
- **The screen effects a buff puts up** — intoxication, the tremor, Valhalla's desaturation. They are
  the same weather kind and would ride the same filter, but they are comfort, not performance, and
  none costs a frame when no buff is up. One line of a later feature: a `comfort()` handle beside
  this one.
- **Variable materials, cave walls, cliff edges, object scale.** Visibility and information, not
  frame time.
- **Culling objects outside the camera's view.** A rendering feature with its own design (bounding
  volumes per sprite), not a setting.
- **A read of the weather the server sent** (which weathers are up). Nothing in the API lists it
  today, and this feature only withholds; a `weather()` read would be a feature of the world section.

## Docs impact

**Pages written or extended**

- `docs/addons/api/client/README.md` — a `## performance()` section (the verb table, the rules, an
  example), a row in the *Handle | Covers* table, the *Before the client is up* list.
- `docs/addons/api/README.md` — the `hafen.client` index row enumerates the panels.
- `docs/addons/api/overlay.md` — one rule: a native plume withheld by the Performance panel is still
  listed.
- `docs/client/ground-detail.md` — new engine map: the flavor pass (`Tileset.Flavor.Buffer`,
  `Tileset.SpriteFlavor.flavor`, `MCache.Grid.makeflavor`, `MCache.Grid.Flavobjs`), the ground blend
  layers (`TerrainTile.Blend`, `TerrainTile.faces`/`_faces`), the transition pass
  (`MapMesh.build` → `MapMesh.dotrans`), the cut lifecycle (`MCache.Deferred.invalidate` vs
  `rebuild`, `MapView.MapRaster.Grid.tick`'s identity swap, `Grid.olseq = -1` on a rebuilt mesh) and
  the gotchas met on the way. None of these seams has a page today.
- `docs/client/world-effects.md` — new engine map: weather (`Glob.wmap`, `Glob.Weather`,
  `Glob.Weather.Factory`, `Glob.weather()`, `Glob.tick`, `MapView.updweather`, `MapView.rweather`),
  tree effects (the `lib/svaj` copy, `GobSvaj.placestate`, `Gob.Placed.Placement.mods`), smoke
  overlays (`OCache.$overlay`, `OCache.OlSprite`, `Gob.Overlay.init`, `Gob.addol`, what `ISmoke`
  simulates in `autotick`), plant sprites (`lib/plants`, `lib/gplant`, `Sprite.Factory` via
  `Resource.getcode`, `CSprite.addpart`, `ResDrawable`). None of these seams has a page today.
- `docs/client/README.md` — two index rows.
- `docs/client/prefs-and-options.md` — a row for the panel in *What the Options window writes*.

**Derived impact set** — `grep -rn` over the whole of `docs/` for the prose names of this surface:

```text
grep -rn "interface, video, audio, camera, client" docs/
  docs/addons/api/README.md:145   → the row enumerates the panels; add the new one, first
grep -rn -i "flavou\?r object\|flavobj\|weather\|\brain\b\|\bsnow\b\|cloud shadow\|sway\|smoke\|tile transition\|ground transition" docs/
  docs/addons/api/map/drawings.md:40   "tile transitions blend across the border" — describes the default; reviewed, no change
  docs/client/glossary.md:33           Glob holds "weather/light" — stays true
  docs/client/minimap.md:69            the minimap's own transition blend, a different pass — no change
  docs/client/multi-session.md:180     the sway gotcha on the svaj copy — stays true; the sway switch is the new page's
  docs/client/terrain-raster.md:8      names Terrain's flavobjs — stays true; links to the new page
grep -rn "client.settings" docs/addons/guides/permissions.md
  :49, :122   "every option write" — already covers the new panel; no change
grep -rn "gob:overlay()" docs/addons/api/overlay.md
  :3, :28   "everything drawn at the gob" — a withheld plume is listed and not drawn; one rule added
```

## Context files

Tagged with the tasks that need them; an untagged line is read by every task.

- `DOCUMENTATION.md`
- `specs/159-sqlite-store/addons/159-sqlite-store.1/main.lua` — the suite skeleton: `check`, `refuses`, `manualCheck`, the `[summary]` line, `hafen.console():on("t159", …)`
- `docs/addons/api/client/README.md` — 1, 2, 3, 4, 5
- `docs/addons/api/client/profiling/counters.md` — 2, 3, 4, 5
- `docs/addons/api/conventions.md` — 1
- `docs/addons/api/README.md` — 1
- `docs/addons/api/overlay.md` — 2
- `docs/addons/api/world.md` — 2, 5
- `docs/addons/api/gob.md` — 5
- `docs/client/README.md` — 2, 3
- `docs/client/prefs-and-options.md` — 1
- `docs/client/published-code.md` — 2, 5
- `docs/client/terrain-raster.md` — 3, 4
- `docs/client/ground-detail.md` — 4 (written by 3: the flavor pass and the cut lifecycle; 4 adds the blend and transition rows)
- `src/haven/Defer.java` — 3, 4 (`Future.run`: a `Loading` out of a cut build parks the future as `resched`, re-queued on the next `done()`)
- `docs/client/gob-sprites.md` — 5
- `doc/resource-code` — 5
- `tools/docverbs.py` — 1
- `src/io/brodgar/addon/ClientOptions.java` — 1
- `src/io/brodgar/addon/OptionsHandle.java` — 1
- `src/io/brodgar/addon/OptionsMethod.java` — 1
- `src/io/brodgar/addon/Args.java` — 1
- `src/io/brodgar/addon/Addon.java` — 1
- `src/io/brodgar/ui/ClientPanel.java` — 1
- `src/io/brodgar/perf/Performance.java` — 2, 3, 4, 5
- `src/io/brodgar/session/Sessions.java` — 2, 5
- `src/haven/OptWnd.java` — 1
- `src/haven/Utils.java` — 1
- `src/haven/Glob.java` — 2
- `src/haven/MapView.java` — 2, 3
- `src/haven/Gob.java` — 2, 5
- `src/haven/OCache.java` — 2, 5
- `src/haven/RUtils.java` — 2
- `src/haven/res/lib/svaj/GobSvaj.java` — 2
- `src/haven/res/lib/svaj/Svaj.java` — 2, 5
- `src/haven/Tileset.java` — 3
- `src/haven/MCache.java` — 3, 4
- `src/haven/ClipAmbiance.java` — 3
- `src/haven/Audio.java` — 3
- `src/haven/RenderLink.java` — 3
- `src/haven/Resource.java` — 3, 5
- `src/haven/resutil/TerrainTile.java` — 4
- `src/haven/MapMesh.java` — 4
- `src/haven/resutil/CSprite.java` — 5
- `src/haven/ResDrawable.java` — 5
- `src/haven/Sprite.java` — 5
