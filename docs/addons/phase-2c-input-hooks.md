# Phase 2c — Input / gesture hooks (`hafen.hook.input`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 20/20 headless logic checks
> (preventDefault suppresses the widget's own default; passthrough runs it; `ev` fields x/y/button/amount
> forward correctly; both teardown paths — `alive=false` and `deafen` — make the hook inert) + LuaJ parse of
> the harness under the sandbox. **In-game DoD pending.**
> **Design:** [specs/addons/13-hooks-and-interception.md](../../specs/addons/13-hooks-and-interception.md) §L1
> (Level 1 — input hooks), [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.hook`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md) (`Widget.listen`/`handle`), decision **D-011**
> (invasiveness allowed, but this level needs none) + **D-018** (watchdog / CPU budget).

The third slice of **Phase 2 (Custom UI + hooks)** and the **first hook level**: addons can now **intercept a
client widget's input before the widget itself sees it** and cancel it. This is the JS
`event.preventDefault()` model at the widget-input layer. It reuses an **existing engine seam** —
`Widget.listen` — so it is **zero core edit** (2d/2e add the action/message levels + `hafen.key`).

## The API — `hafen.hook.input`

```lua
-- Register fn as a PRE-hook on a client widget's input. fn runs BEFORE the widget's own handler.
local h = hafen.hook.input("mapview", "mousedown", function(ev)
  -- ev.x, ev.y   : the pointer position in the target widget's local pixels
  -- ev.button    : 1 = left, 2 = middle, 3 = right   (mousedown / mouseup only)
  -- ev.amount    : wheel scroll amount, + = down / - = up   (mousewheel only)
  if someCondition then
    ev:preventDefault()   -- the widget's own mousedown never runs (and children don't see it either)
  end
  -- if you DON'T preventDefault, the default runs as usual after your handler
end)

h:remove()                -- stop hooking (also auto-removed on reload/disable)
```

| Argument | Accepts |
|---|---|
| `target` | a **string** naming a client widget: `"mapview"` (alias `"map"`), `"gameui"` (alias `"hud"`), or `"root"` |
| `event` | `"mousedown"` · `"mouseup"` · `"mousemove"` · `"mousewheel"` |
| `fn(ev)` | pre-hook; call `ev:preventDefault()` to cancel the widget's default |

Returns a **handle** with `:remove()`. The hook is **bridge-owned** (P2) — `:reload` or disabling the addon
removes it automatically (no `OnDisable` cleanup needed).

### The `ev` object

| Member | On | Meaning |
|---|---|---|
| `ev.x`, `ev.y` | all | pointer position, **target-widget-local pixels** |
| `ev.button` | mousedown / mouseup | `1` left · `2` middle · `3` right |
| `ev.amount` | mousewheel | scroll steps (`+` down, `-` up) |
| `ev:preventDefault()` | all | skip the widget's own handler for this event |

> **preventDefault is also stopPropagation here.** At this seam a consuming listener short-circuits
> `Event.dispatch`, which suppresses **both** the widget's own handler **and** dispatch to its child widgets.
> So there is deliberately **no separate `ev:stopPropagation()`** at Level 1 (one canonical consume). Likewise
> `ev:default()` / `ev:resend()` are **not** offered here — they belong to the outbound-action choke point
> (Level 2, Phase 2d) where re-sending is meaningful; at L1 you simply don't preventDefault and the default
> runs normally after your handler.

> **Register in `OnEnterWorld`.** The target widget must already exist. Hooking `"mapview"`/`"gameui"` from
> the file body or `OnLoad` (before the HUD is up) raises a clear Lua error — do it in `OnEnterWorld`, which
> now fires once the HUD is attached (the 2a timing fix). A `:reload` re-runs `OnEnterWorld`, so the hook is
> re-installed automatically.

## How it works — the built-in `Widget.listen` pre-hook (zero core edit)

Haven's `Widget` already has a **typed listener** mechanism that is exactly a pre-hook with preventDefault:

- [`Widget.listen(Class<E>, EventHandler)`](../../src/haven/Widget.java) / `deafen` register/remove a listener.
- [`Widget.handle(Event)`](../../src/haven/Widget.java) runs listeners **before** the widget's default: if any
  listener returns `true`, it **short-circuits** — the widget's own `mousedown`/etc. never runs, and (because
  [`Event.dispatch`](../../src/haven/Widget.java) returns immediately) neither do the widget's children.

So an addon listener returning `true` on a `MouseDownEvent` **is** preventDefault. 2c just exposes
`listen`/`deafen` to Lua behind a small, safe facade — no `haven` edit at all.

[`LuaInputHook`](../../src/io/brodgar/addon/LuaInputHook.java) is that listener: an
`EventHandler<Widget.Event>` registered on the target for the chosen event class. On dispatch it builds the
`ev` table for the concrete event type (`PointerEvent.c` → `x,y`; `MouseButtonEvent.b` → `button`;
`MouseWheelEvent.a` → `amount`), calls the addon's `fn` through **`AddonManager.callLua`** (watchdog-armed,
error-isolated, CPU-accounted — like every other Lua entry), and returns whether `fn` called
`ev:preventDefault()`. The Lua handler's return value is **ignored** — `preventDefault()` is the one canonical
way to consume (this differs from a `LuaWidget`'s own `onClick`, which is the widget *implementing itself* and
uses a truthy return; a hook is *intercepting someone else's* widget).

### Threading, ownership, teardown

- Input dispatch is on the **UI thread**, so the callback runs synchronously with no marshalling.
- Each hook is **owned by the addon** ([`Addon.hooks`](../../src/io/brodgar/addon/Addon.java), copy-on-write,
  like `subs`/`timers`/`widgets`). Teardown (`teardownHooks`) sets each hook **dead** and calls
  `Widget.deafen` — this matters because a `:reload` keeps the **engine widgets alive** (only the Lua layer
  rebuilds), so without deafening, the old listener would keep firing into a torn-down env. The `alive` flag
  is a belt-and-braces no-op guard for a dispatch that races teardown.
- Because the target is resolved to a **specific live instance** at registration, a hook naturally dies with
  its widget too (a destroyed MapView drops its listener list); the explicit deafen covers the reload case
  where the instance persists.

## Files

**Engine (`io.brodgar.addon`, all package-scoped — no `haven` edit):**
- **`LuaInputHook`** *(new)* — the `EventHandler<Widget.Event>` that forwards a widget input event to a Lua
  pre-hook and reports preventDefault.
- **`Addon`** — one owned-resource list: `hooks` (like `subs`/`timers`/`widgets`/overlays, P2).
- **`AddonManager`** — the `hafen.hook.input` facade + `newInputHook` (validate target/event, wire the
  listener, register + return the `:remove()` handle) + `eventClass`/`isKnownTarget`/`hookTarget`/`listenHook`
  helpers + `removeHook`/`teardownHooks`; `teardown` deafens the addon's hooks (before clearing subs).

**Zero `haven` edit** — every backing is public: `Widget.listen`/`deafen`/`handle`, `Widget.PointerEvent.c`,
`MouseButtonEvent.b`, `MouseWheelEvent.a`, `UI.root`, `GameUI` (via the existing `gui()` locator). The engine
package now holds `{AddonManager, Addon, Manifest, Json, AddonRoot, Sandbox, LuaWidget, LuaGOut, LuaGobOverlay,
LuaInputHook}` + `io.brodgar.addon.ui.AddonPanel`.

## Verification

- **Compile:** `ant hafen-client` → BUILD SUCCESSFUL.
- **Headless (20/20):** on a real `Widget` with a `LuaInputHook` listener — `preventDefault()` makes
  `handle()` report consumed and the widget's own `mousedown` **not** run; **passthrough** (no preventDefault)
  runs the default and still fires the handler; `ev.x/ev.y/ev.button` (mousedown) and `ev.amount/ev.x`
  (mousewheel) forward the right values; setting the hook **dead** skips the handler and runs the default;
  **`deafen`** removes it entirely. Plus the extended `hello/main.lua` parses under `Sandbox.create()`.
- **In-game (DoD) — pending.** Log in with `hello` (**v0.15.0**) enabled and:
  1. The "Hello 2a/2c" window shows **`map-lock OFF`**; click the map — you move normally; the console logs the
     first few `2c: map mousedown observed …` lines (the hook **sees** every click without altering it).
  2. **Click the window body** → it flips to **`map-lock ON`** (window line + the HUD readout border both turn
     red). Now click the map — **your character does not move**, and each attempt logs
     `2c: map click CANCELLED … (map-lock ON)`. **This is the DoD: a MapView mousedown pre-hook cancels the
     default.** Click the window again to turn it OFF and confirm movement returns.
  3. `:reload` (or disabling `hello`) removes the hook cleanly — with `map-lock` left ON before a reload,
     movement still works afterwards until you toggle it on again (proves the old listener was deafened, not
     leaked).

## Known gaps / deferred

- **Targets are named client widgets** (`mapview`/`gameui`/`root`), not arbitrary instances or **widget
  *types*** (auto-attach to every instance of a type needs the factory-override seam from spec 08 / Phase 3).
  More tokens can be added as subsystems land. Hooking your **own** `hafen.ui` widget is unnecessary — it
  already has `onClick`/`onWheel`/etc. callbacks (2a).
- **Mouse events only** (`mousedown`/`mouseup`/`mousemove`/`mousewheel`). Widget-level **key** events and
  remappable **global hotkeys** are Phase 2e (`hafen.hook.message` + `hafen.key`).
- **No `ev:default()`/`ev:resend()`** at L1 (see above) — they arrive with the action choke point (2d).
- **No priority/ordering** between multiple hooks on the same widget yet (Q-012) — listeners currently run in
  registration order; first `true` wins. Formalised when 2d/2e bring multi-level hook ordering.
