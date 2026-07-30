# 002-read-api — Plan

> History: this work appears in git history and `learnings/` tagged **1c-1, 1c-2, 1c-3**
> (Phase 1c). Split into three sessions because 9 sub-APIs is too big for one verification.

## Approach
- **Zero `haven` edits across all three slices** — every backing is public state reached through
  the session hooks from 001: `OCache`/`Gob` (gobs), `MCache` (terrain), `Glob`/`Astronomy`
  (time), `GameUI.chrid` + `MapView.screenxf` (player), `Glob.getcattr` + public `CharWnd.exp/enc`
  (char), `Glob.party` (party), and the `WItem` children of the public `Inventory`/`Equipory`
  widgets (items — deliberately NOT their package-private `wmap`).
- **One centralized `resolve(LuaValue)`** for GobRefs (id / numeric string / `"player"`/`"me"` /
  nil / `"partyN"`; unknown tokens → nil) — the single place future tokens plug into.
- **Snapshots vs accessors**: gobs get both (they have stable ids to re-resolve); items get
  snapshots ONLY — no stable item id exists, so per-item accessor fns are deliberately absent
  (D-013 one canonical way; handles deferred to the UI phase).
- **Loading-guarded everywhere**: every resource-backed read swallows `Loading` → nil/omitted;
  before the world exists all reads are nil/empty, never errors.
- **Grid ids as exact decimal strings** — 64-bit persistence anchors; Lua numbers are doubles
  (2^53); `gc` stays numeric. `gridPos()` = grid id + within-grid offset (0..1100) = the only
  shareable position (no global coords in H&H).
- **Sound/music resolve off the UI thread** (loader defer mirroring `GobIcon.resnotif`) so a
  cold resource never blocks or throws into Lua.
- **`OnEnterWorld` retimed** (in this feature, benefit to all): fire only once the HUD
  (`GameUI`) is assembled — map view attaches frames earlier; `gui()` reaches GameUI up from
  the map view or down from `ui.root`.
- **The `hello` harness grew the `[now]`/`[+3s]` double-read idiom** — map, items and char data
  all stream in a beat after enter-world; the delayed pass proves resolution.

## Files created / modified
- `src/io/brodgar/addon/AddonManager.java` — everything: `hafen.gob/world/map/player/time/sound/
  music/items/char/party`, `resolve()`, `gobSnapshot`/`itemSnapshot`/`memberSnapshot`, filter
  helpers (`matches`/`distTo`), locator helpers (`glob()`/`mcache()`/`astro()`/`gui()`/
  `maininv()`/`equipory()`/`charwnd()`…)
- `addons/hello/` — v0.3.0 → v0.5.0 (readPlace/readInv/readChar double-read helpers)
- No `haven` edits.

## Risks & gotchas hit (detail: learnings/world-reads.md, threading.md, engine-lifecycle.md)
- THREE distinct "not ready yet" races at `OnEnterWorld`: HUD attach (fixed by retiming), map
  grid streaming, and char/items data streaming — the `[+3s]` re-read pattern is the answer.
- `Glob.getcattr` auto-creates zero entries → report 0/0 as nil; attr names are content-defined
  (hard-coded list of nine).
- `party.memb` is replaced wholesale off-thread (snapshot-safe copy); `Party.Member` has NO name
  (protocol limitation); out-of-view members only have remembered positions.
- `gob.health("player")` is nil by design — the player body carries no `GobHealth` attr.
- OCache iteration: copy under `synchronized(oc)`, per-gob under `synchronized(g)`.

## Discarded alternatives
- Reflection into `Inventory.wmap` / `GameUI.equwnd` — child-walk of public widgets instead.
- Numeric grid ids — precision loss above 2^53.
- Per-item accessor functions — no stable id to resolve; snapshots are the one way.
