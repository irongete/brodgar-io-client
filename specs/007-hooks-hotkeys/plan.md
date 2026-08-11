# 007-hooks-hotkeys — Plan

> History: this work appears in git history and `learnings/` tagged **2c, 2d, 2e-1, 2e-2,
> 2e-3** (Phase 2). Task 2e was split into three one-DoD slices.

## Approach
- **L1 rides the built-in `Widget.listen` seam** (zero edit): `Widget.handle` runs listeners
  before the widget's own handler; a listener returning true short-circuits handler AND child
  dispatch — so `ev:preventDefault()` IS the pre-hook consume, and there is deliberately no
  separate stopPropagation. Targets are string tokens → live instances (`mapview`/`gameui`/
  `root`); register in `OnEnterWorld`.
- **L2 = ONE core edit at the outbound choke point**: `UI.wdgmsg` split into hook call +
  `rawWdgmsg` (the original body, public). `resend`/`send` call `rawWdgmsg` → cannot loop;
  both imply preventDefault. `ev.args` is a read snapshot; `send(t)` is the one way to rewrite.
  **Threading pivot**: the MapView click is sent from the render thread (async hit-test
  callback) but under `synchronized(ui)`; `onWdgmsg` runs Lua only when the caller already
  HOLDS the ui monitor (`Thread.holdsLock` — tests, never acquires → no deadlock); tick/draw
  hold it too, so hook Lua never races other Lua. Fast path + re-entrancy guard keep the hot
  action path near-zero when unhooked.
- **L3 = the inbound mirror** at `UiMessage.run` (always under the ui monitor — no gate
  needed): `onMessage` returns the args to apply — originals, rewritten, or null to swallow;
  the 1d post-apply read tap is now gated on the message actually applying. `MessageEvent`'s
  ctor interns the name (spec caveat handled by construction). Marshalling extracted into
  shared `LuaMarshal` (L2+L3 one converter, D-013).
- **Hotkeys ride `Widget.globtype`/`GlobKeyEvent`** (zero edit): the invisible `AddonRoot`
  overrides `globtype`; fired only on unconsumed keypresses (focused fields swallow typing);
  walked LAST → client bindings win. `LuaKeyBind` pairs a real persisted `KeyBinding`
  (`addon/<id>/<name>`) with the Lua fn; the registry entry deliberately survives teardown
  (that's how re-maps persist); parse key strings TO `KeyMatch`, don't reinvent matching.
- **Panel integration** = one `OptWnd.BindingPanel` block looping `describeKeyBinds()` through
  the existing `addbtn`/`SetButton` capture path (persistence for free) + the **client-wide
  `KeyBinding.set` exclusivity fix** (assigning a key unbinds it elsewhere; char/code
  normalised; only explicit assignment steals).

## Files created / modified
- `src/haven/UI.java` — the wdgmsg split + the uimsg message hook (`// addon:` lines)
- `src/haven/OptWnd.java` — BindingPanel addon sections; `src/haven/KeyBinding.java` —
  exclusivity in `set()`
- `src/io/brodgar/addon/LuaInputHook.java`, `LuaActionHook.java`, `LuaMessageHook.java`,
  `LuaMarshal.java`, `LuaKeyBind.java` — new; `AddonRoot.java` — `globtype` override
- `Addon.java` — owned lists `hooks`/`actionHooks`/`messageHooks`/`keybinds`
- `AddonManager.java` (→ `HookApi.java`) — facades, dispatchers, teardowns
- `addons/hello/` — v0.15.0→v0.19.0 (map-lock, move-intercept, vitals-freeze, Ctrl+H, ping)

## Risks & gotchas hit (detail: learnings/hooks-hotkeys.md, threading.md, testing-tooling.md)
- `:reload` keeps engine widgets ALIVE → hooks must be deafened/unregistered on teardown
  (dead-flag + deafen both belt-and-braces).
- L3 hooks run inline with message application on a Loader thread holding the ui monitor —
  keep handlers light (instruction cap still guards).
- Hook/hotkey/draw Lua time escapes the soft per-tick budget (zeroed before evaluation) —
  known gap, ROADMAP.
- Headless: building any `KeyEvent`/`GlobKeyEvent` drags in `UI.<clinit>` — test via
  reflection into the private dispatch map / hand-built events.

## Discarded alternatives
- A separate `stopPropagation` at L1 — the seam makes consume atomic; one canonical control.
- Hooking via truthy returns — `preventDefault()` is explicit; returns are ignored (a hook
  intercepts someone else's widget, unlike a LuaWidget's own onClick).
- An addon-side keybind store — the client's `KeyBinding` registry does remap+persist for free.
