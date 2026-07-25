# V4 — Grid-anchored layouts + the `planner` example addon

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + `ant bin` packages the new
> `planner` addon into `bin/addons/`. **Headless-verified:** LuaJ parse of `planner/main.lua`; a JSON
> round-trip of the exact layout shape through the real store writer (`AddonManager.json`) + reader (`Json`)
> — a 19-digit 64-bit `gridId` survives as an **exact string** (no double-precision loss), the nested
> `items[]`/`anchor{}` objects and `res`/`a`/`x`/`y`/`blueprint` all preserved, and an empty layout serializes
> as `[]`; plus the `fromGridPos` arithmetic round-trip (`gridUL + (world − gridUL) == world`). **One tiny
> `haven`-package accessor** (in the addon-owned `AddonWidgets`, **zero `MCache`/core edit**); the rest is
> `io.brodgar.addon` + a new Lua addon. **Java engine change ⇒ `ant` rebuild + a full client restart** before
> the in-game test. **In-game DoD pending.**
> **Design:** [specs/addons/16-virtual-entities.md](../../../specs/addons/16-virtual-entities.md) (§3
> "Positioning & persistence"; §8 V4), decisions **D-029** (client-only, safe-tier) / **D-031** (gizmo/planner
> is a Lua library, ships in `planner`) / **D-030** (ghost handle). The [[hafen-positioning]] rule (grid ids are
> the cross-session anchor).

V4 is the fourth slice of the **V-series**. [V1](v1-ghosts.md)–[V3](v3-look-orientation.md) gave us ghosts you
can create, move, click and style; V4 makes a **set** of them into a **saved layout** — a city/base plan that
comes back at the **same physical spot after a relog** — and ships a dedicated **`planner`** example addon that
places, selects, rotates and persists ghosts. `hello` stays the read-only regression harness; `planner` is the
V-series' own example, exactly as the spec intends (§8; D-031).

## The problem: world coords are login-relative

A ghost's `x, y` are **world coordinates**, and world coordinates in Hafen are **login-relative** — the origin is
re-randomized every login, so the raw `(x, y)` you saved this session points somewhere else next session (the
[[hafen-positioning]] rule; [coverage-gaps](../../../specs/addons/coverage-gaps.md) C4). The persistent anchor is
the **grid id**: a stable 64-bit id, identical across sessions *and* players. [`hafen.map.gridPos(x, y)`](../api/map.md)
already gives the forward mapping — `{gridId, x, y}` = a stable grid id plus a **within-grid offset** (invariant
across sessions, because it is relative to the grid's own corner). What was missing was the **inverse**: turning a
saved `{gridId, offset}` back into a login-relative world coord in the *current* session.

## The new primitive: `hafen.map.fromGridPos`

```lua
local anchor = hafen.map.gridPos(wx, wy)     -- save: {gridId="…", x=off, y=off}  (grid id + within-grid offset)
-- …persist `anchor`, relog…
local wp = hafen.map.fromGridPos(anchor)     -- load: {x, y} world in THIS session, or nil if that grid isn't loaded
```

`fromGridPos` is the exact inverse of `gridPos`: `fromGridPos(gridPos(x, y))` round-trips to `(x, y)` whenever that
grid is loaded. It takes the **same table `gridPos` returns** (`{gridId, x, y}`), so a stored anchor reloads with no
reshaping. It returns `nil` when no loaded grid carries that id — i.e. that part of the map hasn't streamed in yet
(the caller retries as it loads, or the anchor is simply out of range this login).

### How it resolves a grid by id — one `haven`-package accessor, zero `MCache` edit

`gridPos` computes the offset by subtracting the grid's session-local upper-left corner: `off = world − g.ul·tilesz`.
The inverse must find the grid **whose stable `id` matches** in the current session and add its *current* `g.ul`
back: `world = g.ul·tilesz + off`. The engine indexes loaded grids by session-local coord (`MCache.grids`, a
package-private map), with no public "find grid by stable id" — so the bridge reaches it through **one** accessor in
the addon-owned `haven`-package helper `AddonWidgets` (the same "SpeakerIcon trick" used since
[1d-1](phase-1d1-vitals-widget-tree.md), keeping reflection out of Lua — D-017):

```java
// haven/AddonWidgets.java — pure read; tolerates null; never throws.
public static Coord2d gridWorldUL(MCache mc, long id) {
    if(mc == null) return null;
    synchronized(mc.grids) {
        for(MCache.Grid g : mc.grids.values())
            if((g.id == id) && !g.removed)
                return Coord2d.of(g.ul.x * MCache.tilesz.x, g.ul.y * MCache.tilesz.y);
    }
    return null;
}
```

Because it adds back the **same `g.ul` basis** `gridPos` subtracted, the round-trip is exact. `MCache.java` itself
is untouched — the one line of package-private access lives in the addon accessor, not the engine. `hafen.map.
fromGridPos` (in `AddonManager`) just parses the anchor table, calls this, and adds the offset (a safe read: it
returns `nil` on a null cache / bad id / unloaded grid, never throwing).

## The `planner` addon

A pure-Lua addon (no core dependency beyond the ghost + map API) that is a small base planner.

- **Data model — one source of truth.** `items` is a runtime list of records
  `{ res, a, anchor = {gridId, x, y}, ghost }`. `ghost` is the live [handle](../api/ghost.md) (`nil` while its grid
  hasn't streamed in). `persist()` writes only the serializable fields (`res`/`a`/`anchor`) to the per-char store
  `hafen.store.layout.items` — the handle never goes to disk. The store round-trips the nested array-of-objects as
  JSON (verified headless).
- **Place → anchor.** `:planner place` reads the player's position, computes `anchor = hafen.map.gridPos(px, py)`,
  spawns a **clickable, translucent** ghost there, appends the record, and flushes. So the anchor is captured at
  placement in the persistent grid-id form.
- **Load → re-resolve, with retry.** At `OnEnterWorld` the per-char store is already restored ([1e](phase-1e-saved-variables.md)),
  so `planner` rebuilds `items` from it and calls `resolvePending()`: for each record, `hafen.map.fromGridPos(anchor)`
  → world, then spawn. Grids stream in over the first seconds after login, so any record whose grid isn't loaded yet
  stays pending and a `hafen.timer.every(1, …)` retries it — up to ~15 s, then it gives up (those anchors are out of
  range this login and are **kept** in the store for a closer login, never dropped).
- **Click-to-select (V2).** Each ghost is created `clickable = true` with an `onClick` that closes over its record
  and selects it; selection swaps the ghost's look (idle bluish translucent → a brighter warm highlight) via
  `:alpha`/`:tint`. The owner-scoped `GhostClicked` event is also subscribed (a log line), mirroring `hello`.
- **Edit verbs.** `:planner rotate [deg]` turns the selected ghost and **persists the new facing** (`a`), proving a
  second field survives the relog; `remove [n]` / `clear` / `select <n>` / `list` / `blueprint [name]` round it out.

`:reload` rebuilds it cleanly: ghosts are bridge-owned so they are torn down, the Lua env is rebuilt (so `items`
resets), and the re-fired `OnEnterWorld` reloads the layout from the store and re-resolves — no leak, no dup timer
(the handler cancels any prior retry timer first).

## Files changed

- **`src/haven/AddonWidgets.java`** — new `gridWorldUL(MCache, long)`: resolve a stable grid id to its current
  world upper-left corner (the one package-private grid-map read; zero `MCache` edit).
- **`src/io/brodgar/addon/AddonManager.java`** — new `hafen.map.fromGridPos(anchor)`: the inverse of `gridPos`,
  backed by `AddonWidgets.gridWorldUL`.
- **`addons/planner/`** — new example addon (`manifest.json` + `main.lua`, v0.1.0): the grid-anchored layout +
  click-to-select planner. Declares the per-char saved variable `layout`; **no permissions** (safe-tier).
- **`hello` is untouched** — it stays the read-only harness; `planner` is the V-series example (D-031).

## Try it in-game (V4 DoD)

Rebuild (`ant hafen-client`) and **fully restart** the client (Java engine change — no hot-reload). Then, in the
world:

1. `:planner place` a few times, walking a little between each — several **translucent blueprint cabins** appear.
   (`:planner blueprint timber` switches to the timber house; `:planner place gfx/…` takes a raw resource path.)
2. **Click** one — it does **not** walk your character (V2), it highlights (warm tint) and logs the selection.
   `:planner rotate` turns the selected one; `:planner list` shows them all.
3. **Relog** (log out to character select, back in). The layout reloads and each ghost re-appears at the **same
   physical grid position** (and the same facing) — the DoD. (They stream in over a second or two as the map loads.)
4. `:planner clear` removes them all + wipes the layout; `:reload` / disabling `planner` removes every ghost cleanly.

## Deferred

- **`hafen.map.screenToWorld` + `snapPlace`/`snapAngle`/`placeGrid`/`placeAngle`** and the **transform gizmo**
  (`hafen.ghost.gizmo`, a bundled Lua library in `planner`) → **V5/V6**. V4 places at the player and rotates in
  fixed steps; dragging a ghost on the ground with placegrid snapping is the gizmo's job.
- **`:scale(s)`** → V6.
- **Account-scope / shared layouts.** `planner` uses a **per-character** layout (the DoD is "relog → same spot").
  Grid ids are cross-character, so an account-scope variant (all alts see one plan) is a trivial manifest change,
  left as a note.
- **Multi-select / group transform** → post-V6.
