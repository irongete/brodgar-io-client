# A7 — Movement speed (`hafen.speed`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **8/8 headless checks**
> (locator null-safety with no HUD; `hafen.speed` table + `get`/`max`/`name` functions installed; each
> returns `nil` with no HUD) + LuaJ parse of the harness under `Sandbox.create()`. `speedName()`'s
> in-range value reads the widget's resource tooltips → verified **in-game** (headless has no resource
> loader). **In-game verified ✅.**
> **Design:** [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.speed`
> gap-subsystem A7), [specs/addons/coverage-gaps.md](../../specs/addons/coverage-gaps.md)
> (A7 — "Movement speed (`Speedget`)").

A speed-toggle keybind, auto-crawl-on-sneak, or a HUD speed readout are classic convenience addons. The
client keeps the current movement speed in the **`Speedget`** widget — the four-way **crawl / walk / run /
sprint** selector at the bottom of the HUD. `hafen.speed` exposes it as a read surface. **Changing** speed
(clicking a speed, scrolling, or the client's own speed-up/down keybinds) sends a `wdgmsg` to the server,
so `hafen.speed.set` is the **gated Phase-4 action tier** — this slice ships the **read** only.

## Zero core edits — every backing is public

No `haven` file changed. `Speedget` and the two fields we read are already public:

- `Speedget` is a `public class` the server places under the HUD (created via its `@RName("speedget")`
  factory). It has **no named `GameUI` field**, so we locate it with the **1d-1 Locator** — a
  `children(Speedget.class)` subtree walk from the HUD, exactly how vitals finds its `IMeter`s.
- `Speedget.cur` (`public int`) — the current speed, **0..3** (`0`=crawl `1`=walk `2`=run `3`=sprint).
- `Speedget.max` (`public int`) — the highest speed currently **selectable** (speeds `> max` are drawn
  disabled).
- `Speedget.tips` (`public static final String[]`) — the four display names, from the speed icons'
  resource tooltips (loaded once at class-init).

So the only file changed is `AddonManager.java` (the bridge) — like [A6](a6-kin.md),
[A4](a4-skills-credos-lore.md), [A2](a2-radar.md) and [1d-3](phase-1d3-study-skills.md). No `AddonWidgets`
haven-package accessor is needed (both fields are public — unlike vitals/buffs, audit B5).

## `hafen.speed.get()` — the current speed

```lua
local s = hafen.speed.get()     -- 0=crawl 1=walk 2=run 3=sprint, or nil if the selector isn't up yet
```

Returns the current speed as an integer **0..3** (`Speedget.cur`), or `nil` if the speed widget hasn't
streamed in yet. This is the api-reference contract for A7.

## `hafen.speed.max()` — the highest selectable speed

```lua
local m = hafen.speed.max()     -- speeds 0..m are available; higher ones are disabled (e.g. sprint locked)
local canSprint = (hafen.speed.max() or -1) >= 3
```

Returns `Speedget.max` (0..3) — the highest speed you can currently pick. Speeds `0..max()` are available;
anything above it is disabled (the greyed-out icons in the selector). `nil` if the widget isn't up.

## `hafen.speed.name([n])` — a speed's display name

```lua
local label = hafen.speed.name()      -- name of the CURRENT speed (nil if the selector isn't up)
local label = hafen.speed.name(3)     -- "Sprint" (any index 0..3, regardless of whether it's up)
```

Returns the display name string for speed `n` (from `Speedget.tips` — the speed icons' own tooltips), or
`nil` for an out-of-range index. With **no argument** it names the **current** speed (`nil` if the widget
isn't up). Passing a number reads the static tooltip table, so it works even before the widget exists (once
the class has initialised). The names are the well-known **Crawl / Walk / Run / Sprint** — `name()` just
saves you hard-coding them.

> **One canonical way, three data:** `get()` reads the current speed, `max()` the availability ceiling,
> `name()` the display label — one function per datum, no overlap. `get()` returns a **number** (the
> api-reference shape), not a snapshot table.

## No `SpeedChanged` event

Speed is exposed **read-on-demand**, with **no `SpeedChanged` event** — matching the api-reference (which
lists only `get`/`set`) and the read-only gap surfaces [A2](a2-radar.md)/[A4](a4-skills-credos-lore.md).
The motivating addon reads `get()` inside a keybind handler (then, in Phase 4, `set`s the next speed); a
HUD readout can poll `get()` cheaply each tick. The `Speedget` `"cur"`/`"max"` updates **do** flow through
the 1d-1 inbound-`uimsg` tap, so a `SpeedChanged` adapter is a trivial future add if a use case appears.

## The `hello` example (`addons/hello/main.lua`) — read-only

A new `readSpeed(tag)` helper logs `get()` / `name()` / `max()` in both the `[now]` pass (usually `nil` —
the selector is still streaming in) and the `[+3s]` pass (populated). One login re-checks every prior slice
**and** this one. Bumped to **v0.27.0**.

`hello` is **read-only** for speed — `hafen.speed.set` (change speed) is the **gated Phase-4 action tier**,
not shipped here.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes):

```bash
ant run
```

Read side (the harness, automatic):

- After `[hello] entered the world`, at `[+3s]` expect
  `[hello] [+3s] speed: cur=<n> (<Name>), max=<m>` — e.g. `cur=1 (Walk), max=3`. `speed: nil` at `now` is
  normal (the selector streams in a beat later).

From `:lua`, then **change speed** (click a speed in the selector, scroll it, or use your speed keybinds)
and re-read:

```
:lua hafen.speed.get()      -- 0..3
:lua hafen.speed.name()     -- the current speed's name
:lua hafen.speed.max()      -- the highest selectable speed
:lua hafen.speed.name(3)    -- "Sprint"
```

`get()` should track the selector as you change speed; `max()` should reflect which speeds are enabled.

## Files

- `src/io/brodgar/addon/AddonManager.java` — **the only file changed**: the `hafen.speed`
  `get`/`max`/`name` facade; helpers `speedget()` (the Locator) and `speedName(int)`; one new import
  (`haven.Speedget`).
- `addons/hello/` — `readSpeed` helper wired into the now/+3s read passes; manifest + load-line bumped to
  **v0.27.0** (the load line was still `v0.25.0` — resynced).

**No `haven` core edit.**

## Threading & safety

- Both facade functions run on the **UI thread** (the addon tick / the `:lua` console). `speedget()` walks
  the widget tree via the public `children(Class)` (the same read discipline as vitals) and returns the
  first `Speedget`, or `null` when the HUD isn't up — **null-safe, no NPE** (headless-verified).
- `cur`/`max` are plain public ints (no resource resolve → no `Loading` to guard). `speedName` bounds-checks
  the index against `Speedget.tips.length` before indexing.
- Stateless — no cached snapshot, no listener, nothing to reset across `:reload`/relog (each call re-reads
  live), so there is nothing to leak.

## Limitations / deferred

- **Read only.** `hafen.speed.set(n)` (change speed) is the **gated Phase-4 action tier** (`Speedget`
  `wdgmsg("set", n)`), not shipped here.
- **No `SpeedChanged` event** — read on demand / poll `get()`. The `"cur"`/`"max"` uimsgs flow through the
  1d-1 tap, so an adapter can be added later if needed.
- `get()` passes `Speedget.cur` through **as-is** (faithful) — if the server ever reports an unusual value
  it is returned unclamped, like the other raw reads.
