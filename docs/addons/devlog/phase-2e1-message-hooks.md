# Phase 2e-1 — Message hooks (`hafen.hook.message`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 27/27 headless logic checks
> (shared `LuaMarshal` round-trips Java↔Lua incl. `Coord`↔`{x,y}` and opaque-object identity; `ev` construction
> + `preventDefault`/`rewrite`/passthrough through a real sandbox env; the colon-call convention `ev:rewrite`
> relies on; error isolation; `onMessage` precedence — prevent-wins-over-rewrite, dead-hook skip — and the
> no-hook fast path) + LuaJ parse of the harness under the sandbox.
> **In-game DoD pending.**
> **Design:** [specs/addons/13-hooks-and-interception.md](../../specs/addons/13-hooks-and-interception.md) §L3
> (Level 3 — message hooks), [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.hook`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md) (`UI.uimsg`), decision **D-011** (invasiveness
> allowed where it yields better features — this is **the one core edit**) + **D-018** (watchdog / CPU budget).

The fifth slice of **Phase 2 (Custom UI + hooks)** and the **third hook level** — the **inbound mirror of the
2d L2 action hook**: addons can now **intercept a server→client UI update before the widget applies it**, and
either **swallow** it (`ev:preventDefault()`) or **rewrite** its arguments (`ev:rewrite(newArgs)`). Where L2
sits on the **outbound** `UI.wdgmsg` (player actions to the server), L3 sits on the **inbound** `UI.uimsg`
(server updates to the client). The **DoD**: *suppress a chosen inbound message and watch the widget stop
updating; toggle it off and watch it resume.*

> **Task 2e was split.** The queue's task 2e bundled two unrelated features — message hooks (L3) **and** global
> hotkeys (`hafen.key`) — each with its own in-game DoD. Following the same one-feature-per-slice rhythm as 2c
> (=L1) and 2d (=L2), this slice **2e-1** is the L3 message hook; **2e-2** is `hafen.key` (next).

## The API — `hafen.hook.message`

```lua
-- Register fn as a PRE-hook on the inbound message `msg`. fn runs BEFORE the target widget applies the update.
local h = hafen.hook.message("set", function(ev)
  -- ev.msg     : the message name ("set")
  -- ev.target  : the receiving widget's class simple name (e.g. "IMeter") — for filtering
  -- ev.args    : the arguments, 1-based (same marshalling as L2: a Coord is a {x=,y=} table)
  if ev.target == "IMeter" and freezing then
    ev:preventDefault()                -- SWALLOW it — the widget never applies this update
  end
  -- ev:rewrite({ ... })               -- ...or apply the message with NEW args instead
  -- if you do nothing, the update is applied normally with its original args
end)

h:remove()                             -- stop hooking (also auto-removed on reload/disable)
```

| Argument | Accepts |
|---|---|
| `msg` | a **string** message name (matched exactly), e.g. `"set"`, `"food"`, `"tt"` |
| `fn(ev)` | pre-hook; may `preventDefault` (swallow) or `rewrite` (apply with new args) |

Returns a **handle** with `:remove()`. The hook is **bridge-owned** (P2) — `:reload` or disabling the addon
removes it automatically (no `OnDisable` cleanup needed). It needs **no live target** (you filter on `ev.target`
inside the handler), so it may be registered any time; `OnEnterWorld` onward is the natural place (matching L2).

### The `ev` object

| Member | Meaning |
|---|---|
| `ev.msg` | the message name (string) |
| `ev.target` | the receiving widget's class simple name (string; e.g. `"IMeter"`) — read-only, for filtering |
| `ev.args` | 1-based snapshot of the arguments. `Coord` → `{x=,y=}` table; numbers/strings/booleans direct; any other Java object is opaque but round-trips unchanged. Read it to inspect; to change the update, build new args and call `ev:rewrite`. |
| `ev:preventDefault()` | **swallow** the update — the target widget never applies it (and, because the widget did not change, the 1d widget-tree read tap does **not** fire either, so no `*Changed` event) |
| `ev:rewrite(argsTable)` | apply the message with a **new** argument table (converted Lua→Java) instead of the original. Does **not** swallow — the (rewritten) update is still applied. The message **name is unchanged**. |

> **`preventDefault` vs `rewrite`.** They are opposite intents: `preventDefault` means *don't apply this at
> all*; `rewrite` means *apply it, but with these args*. If several hooks match one message and both are used,
> **`preventDefault` wins** (a suppressed message is never applied, whatever any hook rewrote); otherwise the
> **last `rewrite` wins**. Doing nothing → the original is applied normally.

> **`ev.args` is a converted snapshot.** Mutating the Lua table does **not** change what the widget applies. To
> change the incoming update, call `ev:rewrite(newArgs)` — the **one canonical way** to rewrite a message (the
> inbound analog of L2's `ev:send`). This slice does **not** rewrite the message *name* (deferred — see gaps).

## How it works — the inbound `UI.uimsg` choke point (**one core edit**)

Every server→client UI update funnels through [`UI.uimsg(id, msg, args)`](../../src/haven/UI.java), which queues
a `UiMessage` command; that command (on a **Loader thread**, inside `synchronized(ui)`) resolves the target
widget and applies the update via `dispatch(wdg, new Widget.MessageEvent(msg, args))`. The **one `haven` edit**
is in `UiMessage.run`: before that `dispatch`, it calls `AddonManager.onMessage(wdg, msg, args)`, which returns
**the args to apply** — the originals, a **rewritten** array, or **`null` to swallow** the message. The existing
1d-1 **post-apply** read tap (`onUimsg`) now runs only when the message was actually applied (a swallowed
message left the widget unchanged, so there is nothing to re-read). Both are `// addon:` lines.

[`LuaMessageHook`](../../src/io/brodgar/addon/LuaMessageHook.java) builds the `ev` for one inbound message and
runs the addon's `fn` through **`AddonManager.callLua`** (watchdog-armed, error-isolated, CPU-accounted — like
every other Lua entry). The Lua handler's return value is **ignored**; `preventDefault`/`rewrite` are the
canonical controls (a hook is *intercepting someone else's* update, not implementing a widget).

The Java↔Lua argument marshalling was **extracted into a shared** [`LuaMarshal`](../../src/io/brodgar/addon/LuaMarshal.java)
(pure refactor of 2d's converters), so the L2 action hook and the L3 message hook convert args the **one
canonical way** and cannot drift — the same DRY move as the shared `LuaGOut` (2b) and `readEquipment` (1d-4).
`Coord`↔`{x,y}`, primitives directly (a `Long` becomes a double, the usual `>2^53` caveat), and anything else
as an **opaque userdata** that maps straight back to the identical Java object, so exotic args survive a rewrite
untouched.

### Threading — always under the `ui` monitor (no `holdsLock` gate needed)

Unlike L2, whose senders are **not** all UI-locked (hence its `Thread.holdsLock` gate), the L3 seam is reached
from exactly one place — `UiMessage.run` — which **always** holds `synchronized(ui)` while applying a message.
That is the same monitor the frame loop's tick and draw hold, so the hook Lua runs **serialized with all other
Lua** — no LuaJ race, no gate required. The trade-off (spec's caveat): an L3 hook runs **inline with
server-message application on a Loader thread, holding the UI monitor**, so a slow handler stalls the frame.
Keep handlers light; the **per-call instruction watchdog** (D-018 layer 1) still aborts a runaway. A **near-zero
fast path** — `messageHooks.isEmpty()` then a per-`msg` lookup — keeps `UI.uimsg` application untouched in cost
when nothing hooks the message (the overwhelmingly common case, since uimsg application is very hot).

The `MessageEvent` constructor **interns** the message name (`msg.intern()`), so this slice — which never
rewrites the name — sidesteps the spec's "a rewritten `ev.name` must be interned" caveat by construction. (Name
rewriting is deferred; when added, the ctor's intern already covers it.)

### Ownership, teardown

Each hook is **owned by the addon** ([`Addon.messageHooks`](../../src/io/brodgar/addon/Addon.java), copy-on-write
like `subs`/`timers`/`widgets`/`hooks`/`actionHooks`). Like an action hook (and unlike an input hook) there is
no widget to `deafen` — the hook lives only in `AddonManager`'s per-`msg` dispatch map, so teardown
(`teardownMessageHooks`) marks each **dead** and **unregisters** it from that map (dropping the now-empty
per-`msg` list keeps the fast path cheap). This runs on every reload/disable/CPU-auto-disable, so a `:reload`
(which rebuilds only the Lua layer while the `UI.uimsg` seam — session infra — stays) never leaves a stale hook
firing into a torn-down env (P2). The `alive` flag also no-ops a dispatch that races teardown.

## Files

**Core (`haven`) — the one edit:**
- **`UI.java`** — `UiMessage.run` calls `AddonManager.onMessage` before applying the message (swallow → skip the
  dispatch; rewrite → apply new args), and gates the post-apply read tap on the message actually being applied.
  Two `// addon:` lines. (This is the **second** `UI.java` edit region, after 1d-1's uimsg tap / 2d's wdgmsg hook.)

**Engine (`io.brodgar.addon`, package-scoped):**
- **`LuaMarshal`** *(new)* — the shared Java↔Lua argument marshalling (`toLua`/`toJava`/`argsToLua`/`luaToArgs`),
  extracted from `LuaActionHook` so both hook levels use one converter (D-013).
- **`LuaMessageHook`** *(new)* — the message-hook object: builds `ev` (`msg`/`target`/`args`/`preventDefault`/
  `rewrite`) and forwards to Lua via `callLua`.
- **`LuaActionHook`** — refactored onto `LuaMarshal` (identical behaviour; its private converters removed).
- **`Addon`** — one owned-resource list: `messageHooks` (like `actionHooks`/`hooks`/`subs`/`timers`/`widgets`, P2).
- **`AddonManager`** — the `hafen.hook.message` facade + `newMessageHook` + the `onMessage` dispatcher (fast path,
  precedence) + `registerMessageHook`/`unregisterMessageHook`/`removeMessageHook`/`teardownMessageHooks`;
  `teardown` now unregisters the addon's message hooks (alongside input + action hooks).

The engine package now holds `{AddonManager, Addon, Manifest, Json, AddonRoot, Sandbox, LuaWidget, LuaGOut,
LuaGobOverlay, LuaInputHook, LuaActionHook, LuaMarshal, LuaMessageHook}` + `io.brodgar.addon.ui.AddonPanel`. Core
files touched so far: `MapView.java`, `RemoteUI.java`, `Console.java`, `build.xml`, **`UI.java`** (uimsg tap in
1d-1, wdgmsg hook in 2d, **uimsg message hook in 2e-1**), `AddonWidgets.java`, `OptWnd.java`.

## Verification

- **Compile:** `ant hafen-client` → BUILD SUCCESSFUL.
- **Headless (27/27):** the shared `LuaMarshal` round-trips (`Coord`↔`{x,y}`, `Integer`/`Long`(value + the large
  `>2^31` double path)/`Double`/`String`/`Boolean`/`nil`, opaque object → userdata → **same instance**, a full
  arg vector through `argsToLua`→`luaToArgs`); `ev` built in a **real `Sandbox.create()` env** —
  `ev.msg`/`ev.target`/`ev.args[1]` correct, `preventDefault` sets the shared flag, `rewrite` sets the rewritten
  args (proving the colon-call **self=arg1, table=arg2** convention), passthrough touches neither, a throwing
  handler is isolated (never propagates, leaves both flags untouched); and `onMessage` precedence — no-hook
  **fast path** returns the *same* array (identity), an unhooked msg returns the originals, `preventDefault` →
  `null`, `rewrite` → the new args, **prevent-wins-over-rewrite**, and a **dead hook is skipped**. Plus the
  extended `hello/main.lua` parses under `Sandbox.create()`.
- **In-game (DoD) — pending.** Log in with `hello` (**v0.17.0**) enabled. The "Hello 2e" window shows a new
  **`vitals-freeze OFF (MMB)`** line; the HUD readout shows `freeze=OFF`.
  1. **Observe:** at login the console logs a few `2e: meter 'set' observed (target=IMeter, …)` lines — the L3
     hook **sees** the inbound meter updates streaming in without altering them.
  2. **Suppress (the DoD):** **middle-click the window body** → **`vitals-freeze ON`** (window line + HUD border
     turn **cyan**). Now run around (drains stamina) — the **hp/stamina/energy bars freeze**, both in the window
     and on the **real HUD meters**, because the `"set"` updates are swallowed before the `IMeter` sees them
     (and no `VitalsChanged` fires). Middle-click again → **`vitals-freeze OFF`**; the next update **thaws** the
     bars to their true values. Fully reversible and purely cosmetic (the server still knows your real vitals).
  3. **Teardown:** `:reload` (or disabling `hello`) removes the hook cleanly — leave `vitals-freeze` ON before a
     reload and the meters update normally afterwards (the old hook was unregistered, not leaked).

## Known gaps / deferred

- **Message-hook Lua time partly escapes the soft per-tick CPU budget** (like 2a/2b draw callbacks and 2d action
  hooks). An L3 hook runs on a Loader thread between/around ticks; its time accrues into `tickLuaNanos`, which
  the next tick **zeroes before evaluating** — so layer-2 auto-disable may not see it. The **per-call
  instruction cap (layer 1) still aborts** a single runaway hook. Folding it in needs frame-boundary accounting
  (deferred, tracked with the same gap for draws/actions).
- **Rewriting the message *name* is not exposed** — only args (`ev:rewrite`) or suppression. Remapping one
  message to another is a rarer need and would rely on the `MessageEvent` ctor's `.intern()`; deferred until a
  concrete use case.
- **No priority/ordering** between multiple addons hooking the same `msg` yet (Q-012). Matching hooks run in
  registration order; `preventDefault` wins over `rewrite`, otherwise the last `rewrite` wins. Post-hooks
  (`opts.post`) and priority are formalised later.
- **`hafen.key`** (global hotkeys over `KeyBinding`) is the sibling slice **2e-2**. **Level 4** (method
  replacement / hookable subclasses) folds into Phase 3 (widget replacement).
