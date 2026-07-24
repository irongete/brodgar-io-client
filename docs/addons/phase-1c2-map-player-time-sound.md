# Phase 1c (part 2) — Read API: map, player, time & sound

> **Status:** ✅ Implemented; compile + headless coordinate-math verified; **in-game verified** —
> `map.tile` → `gfx/tiles/grass`, `map.gridPos().gridId` → an exact 64-bit string
> (`-666514804926139788`), `map.height` → a number, `player.worldToScreen(player)` → ~screen-centre,
> `player.name()` → `"Irongete W16.1"` (now resolves **inside `OnEnterWorld`**), `time.isNight()` →
> `true`, and the sound ping all worked; the `hello` `[+3s]` re-read shows the map surfaces resolving
> after the grid streams in. `OnEnterWorld` was retimed to fire once the HUD (`GameUI`) is assembled.
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.map`,
> `hafen.player`, `hafen.time`, `hafen.sound`/`hafen.music`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md) (Java backings).

This is the **second slice of Phase 1c** (the Glob-backed read API), on top of the
[1c-1 gob & world reads](phase-1c-read-gobs.md). It adds four surfaces:

- **`hafen.map.*`** — terrain (`MCache`): the tile / height / grid under a point, the persistent
  **grid anchor**, and pure coordinate conversions.
- **`hafen.player.*`** — the local player's **identity** (name/id), plus `worldToScreen`. (The
  player's *gob* attributes — position, health, moving… — come from `hafen.gob.*("player")`.)
- **`hafen.time.*`** — the game clock and astronomy (day fraction, night, season, moon, year).
- **`hafen.sound.play`** / **`hafen.music.play`** — fire a client sound / background music.

All of it is **zero core edit** — it reads existing public state (`MCache`, `Glob`, `Astronomy`,
`GameUI`, `MapView.screenxf`, `UI.sfx`/`Music`) through the live map view captured in Phase 0.

## Coordinates: world units in, conversions provided

Every positional argument is in **world units** — the same units as `hafen.gob.pos(ref)` — so you
can feed a gob position straight in:

```lua
local p = hafen.gob.pos("player")
local tile = hafen.map.tile(p.x, p.y)
```

## `hafen.map.*` — terrain

```lua
hafen.map.tile(x, y)        -- {id, name} of the tile at a world point, or nil   (name = tileset resource)
hafen.map.height(x, y)      -- number: interpolated terrain height (z), or nil
hafen.map.grid(x, y)        -- {id, gc={x,y}} of the map grid there, or nil
hafen.map.gridPos([x, y])   -- {gridId, x, y}: the persistent anchor (no args = player), or nil
hafen.map.worldToTile(x, y) -- {x, y} tile coord   (floor(world / 11))
hafen.map.tileToWorld(tx,ty)-- {x, y} world coord  (tile's upper-left corner)
hafen.map.tileToGrid(tx,ty) -- {x, y} grid coord   (floor-divide by 100 tiles)
```

`tile` / `height` / `grid` / `gridPos` need the map data for that spot to be loaded; while it is
still streaming they return **`nil`** (never an error). The three `*To*` conversions are pure math
and always work.

> **No global position — anchor on the grid.** `hafen.gob.pos` / `rc` is **login-relative**
> (session-local, starts near the origin), *not* a global or cross-player coordinate. The only stable
> anchor is the **grid id** (identical for every player) plus the **within-grid offset**. For a
> shareable / persistent position use **`hafen.map.gridPos()`**, whose `x,y` are the offset **within**
> the grid in world units (`0 .. 1100`). See the `hafen-positioning` note.

> **Grid ids are strings.** A grid id is a full 64-bit value and is the persistence anchor, so it is
> exposed as a **decimal string** (`grid().id`, `gridPos().gridId`) — Lua numbers are doubles and
> would lose precision above 2⁵³. Treat it as an opaque token (compare/store it; don't do arithmetic).
> `grid().gc` (the session-local grid coord) stays a numeric `{x,y}`.

## `hafen.player.*` — the local player

```lua
hafen.player.exists()            -- bool: is the player gob known yet?
hafen.player.id()                -- number: the player's gob id (the "player" GobRef), or nil
hafen.player.name()              -- string: the LOCAL character name (GameUI.chrid), or nil
hafen.player.worldToScreen(x, y) -- {x, y} map-view-relative pixels, or nil
```

> This table holds only data with **no per-gob equivalent**. For the player's position / health /
> facing / speech, use `hafen.gob.*("player")` (the canonical accessor). `name()` is *your own*
> character's name (`GameUI.chrid`, e.g. `"Irongete W16.1"`); other players' display names are not
> reliably available in this client.

> **Timing.** `OnEnterWorld` now waits for the HUD (`GameUI`) to be assembled before firing (the map
> view can attach a few frames earlier), so `name()` — and every future `GameUI`-backed read — works
> **inside** the handler. `gui()` reaches `GameUI` both up from the map view and, as a fallback, down
> from `ui.root`. The map/terrain/camera data for your spot, however, **streams in a moment later**:
> `map.tile` / `map.height` / `map.gridPos` / `worldToScreen` may still return `nil` for the first
> frames, then start returning values (all Loading-guarded — `nil`, never an error). That's why the
> `hello` harness re-reads them after a short delay.

`worldToScreen` projects a world point to **map-view-relative** pixels via the live camera
(`MapView.screenxf`); it returns `nil` while the camera/scene is still loading. Your own position
projects to roughly the centre of the map view. (Its main consumer — on-screen overlays — arrives
with the UI/drawing phase.)

## `hafen.time.*` — clock & astronomy

```lua
hafen.time.clock()         -- number: interpolated game-time seconds (always available in-world)
hafen.time.dayFraction()   -- 0..1 through the day, or nil
hafen.time.isNight()       -- bool, or nil
hafen.time.season()        -- number: season index, or nil
hafen.time.moon()          -- 0..1 moon phase, or nil
hafen.time.yearFraction()  -- 0..1 through the year, or nil
```

`clock()` comes from `Glob.globtime()`. The rest come from `Glob.ast` (`Astronomy`), which is **nil
until the first "astro" update** arrives after login — those readers return `nil` until then.

## `hafen.sound.play` / `hafen.music.play`

```lua
hafen.sound.play("sfx/msg")             -- fire a one-shot client sound (a ping)
hafen.music.play("sfx/music/...", true) -- start background music (loop); interrupts current music
hafen.music.play(nil)                   -- stop background music
```

`sound.play` resolves the resource **off the UI thread** (a loader defer, mirroring
`GobIcon.resnotif`) so a not-yet-loaded resource never blocks or throws `Loading` into Lua;
client-bundled names such as `"sfx/msg"` and `"sfx/error"` resolve locally. `music.play` takes a
content resource (resolved on its own player thread) and a `loop` flag; a `nil`/empty name stops
playback.

## Try it from the `:lua` console

```
:lua hafen.player.name()
:lua hafen.time.clock()
:lua hafen.time.isNight()
:lua local p = hafen.gob.pos("player") return hafen.map.tile(p.x, p.y)
:lua hafen.map.gridPos()
:lua hafen.map.worldToTile(0, 1105)
:lua hafen.sound.play("sfx/msg")
```

Results print as compact JSON in-game (`lua= …`) and echo to the terminal tagged `[console]`.
Before you are in the world every read is `nil` (never an error).

## The `hello` example (`addons/hello/main.lua`)

On `OnEnterWorld` it now also logs the player identity (`hafen.player.*`) and the clock + astronomy
(`hafen.time.*`), plays a `sfx/msg` **confirmation ping** (`hafen.sound.play`), and reads the map /
projection data at the player's spot via a `readPlace(tag)` helper **twice** — once immediately
(`[now]`, often still loading) and again after 3 s (`[+3s]`, resolved) — so the login log shows
`map.tile` / `height` / `gridPos` / `worldToTile` / `worldToScreen` actually resolving. It remains our
standing regression harness — one login re-checks Phase 0/1a/1b/1c-1 **and** this slice. Bumped to
**v0.4.0**.

## How to test in-game

```
ant run
```

On login (terminal), after `[hello] entered the world` you should see:

- `[hello] player: exists=true id=… name=<your character>` — **player identity** (`hafen.player.*`),
- `[hello] time: clock=… day=… night=… season=… moon=…` — **clock + astronomy** (`hafen.time.*`),
- `[hello] [now] tile=… height=… worldToTile=tx,ty` / `[hello] [now] gridPos=… worldToScreen=…` —
  the immediate read (map/terrain/camera may still be `nil` here),
- `[hello] [+3s] tile=gfx/tiles/… height=… worldToTile=tx,ty` / `[hello] [+3s] gridPos=<id> @X,Y
  worldToScreen=X,Y` — the **resolved** read a few seconds later (this is the one that proves the
  map + projection surfaces),
- and an audible **ping** (the `sfx/msg` sound) — **`hafen.sound.play`**.

Then poke it live with the `:lua` snippets above.

## Files

- `src/io/brodgar/addon/AddonManager.java` — `hafen.map.*` (tile/height/grid/gridPos + worldToTile/
  tileToWorld/tileToGrid), `hafen.player.*` (exists/id/name/worldToScreen), `hafen.time.*`
  (clock/dayFraction/isNight/season/moon/yearFraction), `hafen.sound.play`, `hafen.music.play`; plus
  `glob()`/`mcache()`/`astro()`/`gui()`+`findGui()`/`xy()`/`playSound()` helpers (and `oc()` now routes
  through `glob()`). `gui()` reaches `GameUI` both up from the map view and, as a fallback, down from
  `ui.root` — so `player.name` works even at `OnEnterWorld`, before the map view is parented.
- `addons/hello/` — example + manifest bumped to **v0.4.0** to exercise the new surfaces.
- **No `haven` core edits.**

## Threading & safety

- All reads run on the **UI thread**. Map reads that touch grid data (`tile`/`height`/`grid`/
  `gridPos`) **swallow `Loading`** (the map for that spot isn't here yet) → `nil`.
- `sound.play` never blocks the UI thread: it hands resolution + playback to a **loader defer**
  (`Loading` re-runs the task; other failures print `could not play …`).
- `worldToScreen` catches `Loading`/projection errors → `nil`; the coordinates are **map-view
  relative**.

## Limitations (rest of Phase 1c and later)

- **`hafen.player.vitals()`** (hp/stamina/energy bar fractions) is **not** here — the meters are
  private `GameUI` widgets, so it needs the widget-tree read mechanism (Phase 1d, see
  `coverage-gaps.md` B5).
- **`hafen.map.grid().seg`** (segment id) is omitted — segment ids live in the client-side `MapFile`,
  delivered with the map/markers subsystem (A1), not in `MCache.Grid`.
- Still to come in Phase 1c: **1c-3** — `hafen.items`, `hafen.char`, `hafen.party`.
- No sandbox/watchdog yet — a runaway Lua scan can still stall the UI thread (Phase 1f).
