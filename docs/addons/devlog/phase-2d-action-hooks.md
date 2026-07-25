# Phase 2d — Action hooks (`hafen.hook.action`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 33/33 headless logic checks
> (arg marshalling round-trips Java↔Lua incl. `Coord`↔`{x,y}` and opaque-object identity; `ev` construction +
> `preventDefault`/passthrough through a real sandbox env; the colon-call convention `ev:send` relies on;
> error isolation; the `onWdgmsg` no-hook fast path) + LuaJ parse of the harness under the sandbox.
> **In-game DoD pending.**
> **Design:** [specs/addons/13-hooks-and-interception.md](../../specs/addons/13-hooks-and-interception.md) §L2
> (Level 2 — action hooks), [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.hook`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md) (`UI.wdgmsg`), decision **D-011** (invasiveness
> allowed where it yields better features — this is **the one core edit**) + **D-018** (watchdog / CPU budget).

The fourth slice of **Phase 2 (Custom UI + hooks)** and the **second hook level**: addons can now **intercept an
outbound player action before it reaches the server**, cancel it (`ev:preventDefault()`), or re-issue it
(`ev:resend()` / `ev:send(newArgs)`). This is the exact analog of intercepting the network request a UI click
would trigger — and, unlike the 2c L1 input hook (screen coords, pre hit-test), the action's arguments here are
**already fully resolved**: for a MapView move-click, `ev.args[2]` is the destination **world coordinate**.
The **DoD**: *intercept a move-click, run logic, then re-send.*

## The API — `hafen.hook.action`

```lua
-- Register fn as a PRE-hook on the outbound action `msg`. fn runs BEFORE the message reaches the server.
local h = hafen.hook.action("click", function(ev)
  -- ev.msg      : the action name ("click")
  -- ev.sender   : the sending widget's class simple name (e.g. "MapView")
  -- ev.args     : the arguments, 1-based. For a MapView move: {pc, worldCoord, button, mods}
  --               a Coord is a {x=,y=} table; a clicked gob appends more args.
  local w = ev.args[2]                 -- the resolved destination world coordinate
  if someCondition(w) then
    ev:preventDefault()                -- do NOT send the move to the server
    doSomethingIntermediate(w)
    ev:resend()                        -- ...then issue it myself (unchanged) — or ev:send(newArgs)
  end
  -- if you do nothing, the action is sent normally with its original args
end)

h:remove()                             -- stop hooking (also auto-removed on reload/disable)
```

| Argument | Accepts |
|---|---|
| `msg` | a **string** action name (matched exactly), e.g. `"click"`, `"belt"`, `"drop"` |
| `fn(ev)` | pre-hook; may `preventDefault`, `resend`, or `send` |

Returns a **handle** with `:remove()`. The hook is **bridge-owned** (P2) — `:reload` or disabling the addon
removes it automatically (no `OnDisable` cleanup needed). It needs **no live target** (unlike an input hook),
so it may be registered any time; `OnEnterWorld` onward is the natural place.

### The `ev` object

| Member | Meaning |
|---|---|
| `ev.msg` | the action name (string) |
| `ev.sender` | the sending widget's class simple name (string; e.g. `"MapView"`) — read-only, for filtering |
| `ev.args` | 1-based snapshot of the arguments. `Coord` → `{x=,y=}` table; numbers/strings/booleans direct; any other Java object is opaque but round-trips unchanged. Read it to inspect; to change the action, build new args and call `ev:send`. |
| `ev:preventDefault()` | do not send the action to the server |
| `ev:resend()` | send the action **now** with the **original** args (verbatim, lossless), bypassing the hook chain; implies `preventDefault` |
| `ev:send(argsTable)` | send the action **now** with a new argument table (converted Lua→Java), bypassing the hook chain; implies `preventDefault` |

> **`resend`/`send` re-issue the action, and bypass the hook chain.** They call the engine's raw send path
> (`UI.rawWdgmsg`), **not** `UI.wdgmsg`, so a hook that re-issues its own action **cannot loop** (the spec's
> re-entrancy caveat). Because they take over the send, they **imply `preventDefault`** — the original is not
> sent as well. Calling neither → the original is sent normally. Calling `preventDefault` alone → nothing is
> sent (you can `resend()` later, e.g. from a timer — the *"do something intermediate, then move"* case).

> **`ev.args` is a converted snapshot.** Mutating it does **not** change the default send (which uses the
> original args verbatim). To change the outgoing action, call `ev:send(newArgs)` — the **one canonical way**
> to rewrite an action. A coordinate arg is a `{x=,y=}` table; reuse `ev.args` values (they round-trip) and
> swap only what you need.

## How it works — the outbound `UI.wdgmsg` choke point (**one core edit**)

Every player action is a [`Widget.wdgmsg`](../../src/haven/Widget.java) that funnels through the single
outbound choke point [`UI.wdgmsg(sender, msg, args)`](../../src/haven/UI.java). The **one `haven` edit** splits
that method: `wdgmsg` now calls `AddonManager.onWdgmsg(...)` first (the hook), and the original send body moves
into a new **public `UI.rawWdgmsg`** that `resend`/`send` use to reach the server without re-entering the hook.
Both lines are tagged `// addon:`.

[`LuaActionHook`](../../src/io/brodgar/addon/LuaActionHook.java) builds the `ev` for one outbound action and
runs the addon's `fn` through **`AddonManager.callLua`** (watchdog-armed, error-isolated, CPU-accounted — like
every other Lua entry). The Lua handler's return value is **ignored**; `preventDefault`/`resend`/`send` are the
canonical controls (a hook is *intercepting someone else's* action, not implementing a widget). Its static
converters marshal each argument both ways — `Coord`↔`{x,y}`, primitives directly (a `Long` becomes a double,
the usual `>2^53` caveat), and anything else as an **opaque userdata** that maps straight back to the identical
Java object, so exotic args (a clicked gob's click-data, byte arrays) survive a `resend`/`send` untouched.

### Threading — run Lua only while the UI monitor is held (`Thread.holdsLock`)

The MapView move-click is the tricky case: `mousedown` submits an **async GPU hit-test**, and the resolved
`wdgmsg("click", …)` is sent from the **render thread** inside the hit-test callback
([`MapView.Hittest.ckdone`](../../src/haven/MapView.java)) — but **under `synchronized(ui)`**. The frame loop
runs input dispatch, the addon tick, and draw all under that **same `ui` monitor**
([`UILoop.Frame.tick`](../../src/haven/UILoop.java)). So `onWdgmsg` runs the hook's Lua **only when the calling
thread already holds the `ui` monitor** (`Thread.holdsLock(ui)`): that is true for every player-action path
(input dispatch, the addon tick, and the hit-test callback), and because tick/draw hold it too, the hook Lua
**cannot race any other Lua** — LuaJ is single-threaded here as always. Crucially, `onWdgmsg` only **tests** the
lock (it never acquires a new one), so there is **no deadlock risk**. A rare off-lock `wdgmsg` (e.g. from a
widget-bind on a loader thread) is passed straight through, unhooked.

A **re-entrancy guard** makes a hook body that itself sends a `wdgmsg` pass through rather than recurse
(`resend`/`send` already bypass via `rawWdgmsg`; the guard covers a hook that calls some other action API).
And a **near-zero fast path** — `actionHooks.isEmpty()` then a per-`msg` lookup — keeps `UI.wdgmsg` untouched in
cost when nothing hooks the action (the overwhelmingly common case, since `wdgmsg` is on the hot action path).

### Ownership, teardown

Each hook is **owned by the addon** ([`Addon.actionHooks`](../../src/io/brodgar/addon/Addon.java), copy-on-write
like `subs`/`timers`/`widgets`/`hooks`). Unlike an input hook there is no widget to `deafen` — the hook lives
only in `AddonManager`'s per-`msg` dispatch map, so teardown (`teardownActionHooks`) marks each **dead** and
**unregisters** it from that map (dropping the now-empty per-`msg` list keeps the fast path cheap). This runs on
every reload/disable/CPU-auto-disable, so a `:reload` (which rebuilds only the Lua layer) never leaves a stale
hook firing into a torn-down env (P2). The `alive` flag also no-ops a dispatch that races teardown.

## Files

**Core (`haven`) — the one edit:**
- **`UI.java`** — `wdgmsg` calls `AddonManager.onWdgmsg` (may cancel/rewrite), and its original body moves into
  the new **public `rawWdgmsg`** (used by `resend`/`send` to bypass the hook chain). Two `// addon:` lines.

**Engine (`io.brodgar.addon`, package-scoped):**
- **`LuaActionHook`** *(new)* — the action-hook object: builds `ev`, forwards to Lua, and marshals args both
  ways (the `toLua`/`toJava`/`argsToLua`/`luaToArgs` converters).
- **`Addon`** — one owned-resource list: `actionHooks` (like `hooks`/`subs`/`timers`/`widgets`/overlays, P2).
- **`AddonManager`** — the `hafen.hook.action` facade + `newActionHook` + the `onWdgmsg` dispatcher (fast path,
  `holdsLock` gate, re-entrancy guard) + `registerActionHook`/`unregisterActionHook`/`removeActionHook`/
  `teardownActionHooks`; `teardown` now unregisters the addon's action hooks (alongside input hooks).

The engine package now holds `{AddonManager, Addon, Manifest, Json, AddonRoot, Sandbox, LuaWidget, LuaGOut,
LuaGobOverlay, LuaInputHook, LuaActionHook}` + `io.brodgar.addon.ui.AddonPanel`. Core files touched so far:
`MapView.java`, `RemoteUI.java`, `Console.java`, `build.xml`, `UI.java` (uimsg tap in 1d-1, **wdgmsg hook in
2d**), `AddonWidgets.java`, `OptWnd.java`.

## Verification

- **Compile:** `ant hafen-client` → BUILD SUCCESSFUL.
- **Headless (33/33):** the marshalling round-trips (`Coord`↔`{x,y}`, `Integer`/`Double`/`Long`/`String`/
  `Boolean`/`nil`, opaque object → userdata → **same instance**, and a full `{pc, world, button, mods}` click
  vector through `argsToLua`→`luaToArgs`); `ev` built in a **real `Sandbox.create()` env** — `ev.msg`/`ev.sender`/
  `#ev.args`/`ev.args[2].x`/`ev.args[3]` correct, `preventDefault` sets the shared flag, passthrough does not, a
  throwing handler is isolated (never propagates, leaves the default send intact); the **colon-call convention**
  `ev:send(t)` depends on (self=arg1, table=arg2); and `onWdgmsg`'s no-hook **fast path** returns `true`. Plus
  the extended `hello/main.lua` parses under `Sandbox.create()`.
- **In-game (DoD) — pending.** Log in with `hello` (**v0.16.0**) enabled. The "Hello 2c/2d" window shows
  **`map-lock OFF`** and **`move-hook OFF`**; the HUD readout shows both too.
  1. **Observe:** with both OFF, click the map — you move normally; the console logs the first few
     `2d: move observed -> X,Y (passed through)` lines (the action hook **sees** each resolved move without
     altering it — note the destination world coord, which the 2c L1 hook cannot know).
  2. **Intercept + resend (the DoD):** **right-click the window body** → **`move-hook ON`** (window line +
     HUD border turn **orange**). Click the map — you **still move**, and each move logs
     `2d: MOVE intercepted -> X,Y … resending`. That proves the full cycle: the move was **cancelled**
     (`preventDefault`), logic ran, then it was **re-issued** (`ev:resend()`). Right-click again to turn it OFF.
  3. **Layering:** turn **map-lock ON** (left-click the window). Now clicking the map is cancelled at **L1**
     (2c) *before* any hit-test, so **no `"click"` is sent** and the 2d hook does **not** fire (no `2d:` lines) —
     demonstrating L1 pre-empts L2. Turn map-lock OFF to see move-intercept again.
  4. **Teardown:** `:reload` (or disabling `hello`) removes the hook cleanly — leave `move-hook` ON before a
     reload and movement still works normally afterwards (the old hook was unregistered, not leaked).

## Known gaps / deferred

- **Action-hook Lua time escapes the soft per-tick CPU budget** (like 2a/2b draw callbacks). A `wdgmsg` sent
  between ticks (the hit-test callback) or during input dispatch accrues into `tickLuaNanos`, which the next
  tick **zeroes before evaluating** — so layer-2 auto-disable never sees it. The **per-call instruction cap
  (layer 1) still aborts** a single runaway hook. Folding it in needs frame-boundary accounting (deferred).
- **Off-lock senders are passed through** (unhooked) by design — the `holdsLock` gate keeps Lua on a race-free
  path. Player actions are always sent under the `ui` lock, so this only skips rare internal sends.
- **No priority/ordering** between multiple addons hooking the same `msg` yet (Q-012). Matching hooks currently
  run in registration order; the first `preventDefault`/`resend`/`send` decides the default send. Post-hooks
  (`opts.post`) and priority are formalised later.
- **Level 3 (message hooks, inbound `uimsg`) and `hafen.key`** are Phase 2e. **Level 4** (method replacement /
  hookable subclasses) folds into Phase 3 (widget replacement).
