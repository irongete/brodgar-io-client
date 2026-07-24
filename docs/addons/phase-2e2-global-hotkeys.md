# Phase 2e-2 — Global hotkeys (`hafen.key`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 31/31 headless logic checks
> (the key-string parser → `KeyMatch` for named keys/letters/modifiers/`None`/invalid input; `KeyMatch`
> modifier-**exactness** matching; the `onGlobKey` dispatcher — fast path, fire+consume, mod-mismatch no-fire,
> dead-hook skip, first-match-wins; and `newKeyBind` register/handle/`:key()`/error paths) + LuaJ parse of the
> harness under the sandbox.
> **In-game DoD pending.**
> **Design:** [specs/addons/07-ui-and-drawing.md](../../specs/addons/07-ui-and-drawing.md) (Input — Global
> hotkeys), [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.key`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md) (`KeyBinding` / `GlobKeyEvent` / `Widget.globtype`).

The sixth slice of **Phase 2 (Custom UI + hooks)** and the second half of task 2e — **global hotkeys**. Addons
can now bind a **remappable, persisted** key that runs a Lua handler when pressed, regardless of what has focus
(as long as no widget consumed the keypress first). It is the addon-facing analog of WoW's `SetBinding` /
`CreateFrame` keybind, built directly on the client's own `KeyBinding` registry so an addon hotkey sits
alongside the client's and is **re-mappable in the same keybind options**. The **DoD**: *bind a key, press it
in-world, and watch the handler run — here Ctrl+H toggles the custom window.*

> **Task 2e was split** (see [2e-1](phase-2e1-message-hooks.md)). The queue's task 2e bundled two unrelated
> features — message hooks (L3) **and** global hotkeys — each with its own in-game DoD. Following the same
> one-feature-per-slice rhythm as 2c (=L1) / 2d (=L2), **2e-1** was the L3 message hook; this slice **2e-2** is
> `hafen.key`. (**Level 4** — method replacement / hookable subclasses — folds into Phase 3.)

## The API — `hafen.key.bind`

```lua
-- Bind a global hotkey. `name` is the addon-local binding name; `defaultKey` is a key string (or nil / "None"
-- for unbound-by-default); `fn()` runs when the key is pressed. Returns a handle { :remove(), :key() }.
local h = hafen.key.bind("toggle", "Ctrl+H", function()
  if panel:visible() then panel:hide() else panel:show() end
end)

h:key()      -- -> the current key's display name, e.g. "Ctrl+H" (or "None" if unbound)
h:remove()   -- stop the hotkey (also auto-removed on reload/disable)
```

| Argument | Accepts |
|---|---|
| `name` | a **string** — the addon-local binding name. Registered in the client keybind registry as `addon/<addon-id>/<name>` (so it is namespaced per addon and **cannot collide** with another addon or the client). |
| `defaultKey` | a **key string** (see below), **or `nil` / `"None"`** for *unbound by default* — the binding exists but never fires until the user assigns a key in the keybind options. |
| `fn()` | the handler, called with **no arguments** when the key is pressed. Runs through `callLua` (watchdog-armed, error-isolated, CPU-accounted). Its return value is ignored — a match **consumes** the key. |

### Key-string syntax

`defaultKey` is a human-readable string: **zero or more modifiers** then a **key**, joined by `+`.

| Part | Accepts (case-insensitive) |
|---|---|
| Modifiers | `Ctrl` (`Control`, `Ctl`, `C`), `Shift` (`S`), `Alt` (`Meta`, `M`) |
| Named keys | `F1`..`F12`, `Space`, `Enter`/`Return`, `Tab`, `Esc`/`Escape`, `Backspace`, `Delete`/`Del`, `Insert`/`Ins`, `Home`, `End`, `PageUp`/`PgUp`, `PageDown`/`PgDn`, `Up`, `Down`, `Left`, `Right` |
| Any single character | a letter, digit, or symbol — `M`, `7`, `/` … (matched by character, so caps/lock-independent) |

Examples: `"F5"`, `"Ctrl+M"`, `"Shift+Alt+Left"`, `"H"`, `"None"`. Modifier matching is **exact**: `"M"` fires
only on a bare `M`, never on `Ctrl+M`. An unparseable string (unknown modifier, or an unknown multi-character
key name) raises a clear Lua error at `bind` time.

> **Re-mappable + persisted.** The binding is a real client `KeyBinding`, so the user can re-map it in the
> client's keybind options and the choice is **saved in the client prefs** (`keybind/addon/<id>/<name>`) — it
> **survives reloads and sessions**. `defaultKey` is only the *initial* key, applied the first time the binding
> is created; a later change to the default in code does **not** override a key the user already has (or
> re-mapped), matching how every client binding behaves.

### The handle

Returns a table with:

| Method | Meaning |
|---|---|
| `:key()` | the **current** key's display name (`"Ctrl+H"`, `"None"`, …) — reflects the user's re-map if any |
| `:remove()` | stop the hotkey and drop it from the dispatcher |

The hotkey is **bridge-owned** (P2): `:reload` or disabling the addon removes it automatically (no `OnDisable`
cleanup needed). It needs **no live target** (unlike an input hook), so it can be bound **any time** — the file
body, `OnLoad`, or `OnEnterWorld` — and it only fires once you are in-world and press the key.

## How it works — the `GlobKeyEvent` seam (**zero core edit**)

Key presses funnel through [`UI.keydown`](../../src/haven/UI.java), which first dispatches a **focused**
`KeyDownEvent` and, **only if nothing consumed it**, a [`Widget.GlobKeyEvent`](../../src/haven/Widget.java) that
**walks the whole widget tree** calling [`Widget.globtype`](../../src/haven/Widget.java) on each widget — the
client's own hotkeys (`GameUI`, `MapView`, the belt, …) are matched exactly this way. So a global hotkey needs
**no `haven` edit at all** (like 2c's input hooks reusing `Widget.listen`): the invisible **`AddonRoot`** tick
widget — already attached to `ui.root` every session — **overrides `globtype`** to run
[`AddonManager.onGlobKey`](../../src/io/brodgar/addon/AddonManager.java), which matches every registered hotkey
against the event and, on a match, runs the handler and returns `true` (consumed).

[`LuaKeyBind`](../../src/io/brodgar/addon/LuaKeyBind.java) pairs a client `KeyBinding` (from
[`KeyBinding.get`](../../src/haven/KeyBinding.java), remappable + persisted) with the addon's Lua `fn`; its
`matches(ev)` delegates to `KeyBinding.key().match(ev)` — the **same** `KeyMatch` comparison the client uses, so
modifier/char/code semantics are identical. The key-string → `KeyMatch` parser (`parseKeyMatch`) builds a
`KeyMatch.forcode` for named keys and `KeyMatch.forchar` for single characters.

### "Fires only when free" — the two guarantees

1. **Only on an unconsumed keypress.** `UI.keydown` fires the `GlobKeyEvent` **only after an unconsumed focused
   `KeyDownEvent`**. A focused text field (chat, a rename box, …) consumes the keys **it handles** — which is
   **all ordinary typing** (`ReadLine` inserts any printable char with no Ctrl/Alt and returns `true`) — so a
   hotkey bound to a normally-typed key is **naturally suppressed while typing**. This is **exactly** how the
   client's own global hotkeys behave; a modifier chord the field *ignores* (e.g. `Ctrl+H` in the default PC
   edit-mode, which `ReadLine` passes through) can still fire while a field has focus, just like the client's own
   `Ctrl`-bindings do.
2. **Client bindings win.** The `GlobKeyEvent` walk visits widgets in reverse child order; `AddonRoot` is an
   **early** child of `ui.root` (attached at session bind, before `GameUI`), so it is walked **last** — any
   client binding on the same key is matched first. An addon hotkey is therefore a **fallback**, never a hijack
   of a key the client already uses. (Bind an obviously-free key like `Ctrl+H` or an `F`-key not owned by the
   belt to be sure it reaches the addon layer.)

### Threading

`onGlobKey` runs on the **UI thread** — input dispatch is part of the frame loop's `synchronized(ui)` tick, the
same monitor tick/draw hold — exactly like a 2c input hook. So the handler goes **straight through `callLua`**
with no thread guard and never races other Lua. A **near-zero fast path** (`keyBinds.isEmpty()`) keeps
`AddonRoot.globtype` free when no addon has any hotkey; otherwise it is a short scan of a small list, run only
on an **unconsumed keypress** (not per frame).

### Ownership, teardown

Each hotkey is **owned by the addon** ([`Addon.keybinds`](../../src/io/brodgar/addon/Addon.java), copy-on-write
like `subs`/`timers`/`hooks`/`actionHooks`/`messageHooks`). Teardown (`teardownKeyBinds`, on every
reload/disable/CPU-auto-disable) marks each **dead** and drops it from the global dispatch list — like an
action/message hook there is **no widget to `deafen`**. The `KeyBinding` registry entry itself is process-global
and **persistent and is deliberately left intact** — that is how the client remembers a re-mapped key across
reloads; teardown removes only the Lua-handler wrapper. The `alive` flag also no-ops a dispatch that races
teardown. A `:reload` rebuilds only the Lua layer (the `AddonRoot`/`globtype` seam is session infra), so
re-binding on reload re-uses the same persistent `KeyBinding` and re-attaches a fresh handler — no leak, no
duplicate.

## Files

**Core (`haven`):** none. Zero `haven` edit — this reuses the engine's own `Widget.globtype` / `GlobKeyEvent`
seam and the `KeyBinding` registry (like 1d-3, 1e, 2a, 2b, 2c).

**Engine (`io.brodgar.addon`, package-scoped):**
- **`LuaKeyBind`** *(new)* — the hotkey object: a client `KeyBinding` + the Lua `fn` + `alive`, with
  `matches(GlobKeyEvent)` delegating to `KeyBinding.key().match(ev)`.
- **`AddonRoot`** — overrides `globtype(GlobKeyEvent)` to call `AddonManager.onGlobKey` (the zero-edit seam).
- **`Addon`** — one owned-resource list: `keybinds` (like `hooks`/`actionHooks`/`messageHooks`/`subs`/`timers`, P2).
- **`AddonManager`** — the `hafen.key.bind` facade + `newKeyBind` (parse + register + handle) + `parseKeyMatch`
  (key-string → `KeyMatch`) + the `KEYCODES` named-key table + the `onGlobKey` dispatcher (fast path,
  first-match-wins, consume) + `removeKeyBind`/`teardownKeyBinds`; `teardown` now unregisters the addon's
  hotkeys (alongside input/action/message hooks).

The engine package now holds `{AddonManager, Addon, Manifest, Json, AddonRoot, Sandbox, LuaWidget, LuaGOut,
LuaGobOverlay, LuaInputHook, LuaActionHook, LuaMarshal, LuaMessageHook, LuaKeyBind}` +
`io.brodgar.addon.ui.AddonPanel`. Core files touched so far (unchanged this slice): `MapView.java`,
`RemoteUI.java`, `Console.java`, `build.xml`, `UI.java`, `AddonWidgets.java`, `OptWnd.java`.

## Verification

- **Compile:** `ant hafen-client` → BUILD SUCCESSFUL.
- **Headless (31/31):** `parseKeyMatch` — `"F5"`→`forcode(VK_F5, none)`, `"Ctrl+M"`→`forchar('M', C)`,
  `"Shift+Alt+Left"`→`forcode(VK_LEFT, S|M)`, `"m"`→`forchar('M')` (uppercased), `"Space"`→`VK_SPACE`,
  `"control+enter"` (case-insensitive alias + named key)→`forcode(VK_ENTER, C)`, `"None"`/`""`→`KeyMatch.nil`,
  and `null` for `"Ctrl+Foo"` (unknown key) / `"Hyper+M"` (unknown modifier) / `"Ctrl+"` (no key). `KeyMatch`
  **modifier-exactness** on deterministic `forcode` keys (bare `F5` matches `F5` but not `Ctrl+F5`; `Ctrl+F5`
  matches only `Ctrl+F5`, not bare `F5` nor `Ctrl+Shift+F5`) plus a `forchar` bare-`M` match, and `KeyMatch.nil`
  never matching. `onGlobKey` in a real `Sandbox.create()` env — empty list → `false` (fast path), a matching
  press fires the handler and consumes (`true`), a mod-mismatch neither fires nor consumes, a **dead** bind is
  skipped, and with two binds only the matching one fires. `newKeyBind` — registers in both the global +
  owned lists, the handle exposes `:remove()`/`:key()`, `:key()` returns `"Ctrl+H"`, `:remove()` drops from both
  lists, a `nil` default registers as `"None"` (unbound), and an unparseable key / non-function `fn` each raise a
  `LuaError`. Plus the extended `hello/main.lua` parses under `Sandbox.create()`.
- **In-game (DoD) — pending.** Log in with `hello` (**v0.18.0**) enabled. At load the console logs
  `2e-2: global hotkey bound (Ctrl+H toggles the window) …`; the "Hello 2e-2" window's controls line and the
  HUD readout are unchanged (the hotkey is not a per-frame surface).
  1. **The DoD:** in-world, press **Ctrl+H** → the window **hides**; press **Ctrl+H** again → it **shows**. The
     console logs `2e-2: Ctrl+H -> window hidden/shown` each time. (Pressing it before the window exists logs
     `… but the window is not up yet` — proving a hotkey can be bound before its target exists.)
  2. **Focus behaviour:** open chat, focus the input line, and type — ordinary characters go into the line and
     do **not** trigger any hotkey (a focused field consumes all normal typing). Re-bind `toggle` to a plain
     letter and confirm it toggles the window when the world is focused but is swallowed as text while the chat
     line has focus. (Ctrl+H itself, a chord the PC edit-mode line ignores, may still fire while chat is
     focused — that is the engine's own global-hotkey behaviour, identical to the client's `Ctrl`-bindings.)
  3. **Re-mappable:** open the client's keybind options and confirm the `addon/hello/toggle` binding is present
     and can be re-assigned; the new key persists across a `:reload`/relog.
  4. **Teardown:** `:reload` (or disabling `hello`) removes the hotkey cleanly — after a reload Ctrl+H still
     toggles (re-bound on the re-fired file body, re-using the same persistent `KeyBinding`), with no duplicate
     firing; disabling `hello` and reloading leaves Ctrl+H doing nothing.

## Known gaps / deferred

- **Not shown in the client's keybind *panel* list.** The binding **is** created, persisted, and re-mappable via
  the `KeyBinding` registry, but the client's `OptWnd.BindingPanel` is a **hardcoded list** (it does not
  auto-enumerate `KeyBinding.bindings`), so addon bindings do not yet appear there for point-and-click re-mapping.
  Surfacing them needs either a core edit adding an "AddOn hotkeys" section to the panel or a dedicated
  addon-bindings sub-panel (candidate for the AddOns options panel, Phase 2 UI). Deferred.
- **Character keys go through `forchar`, named keys through `forcode`.** A single character (`"M"`) binds by
  *character* (caps/lock-independent); a named key (`"F5"`, `"Left"`) binds by *key code*. The `+` separator
  means a literal `+` or `Space` **as the last token via the string** is awkward (`"Space"` works via the named
  table; a bare `"+"` does not) — a rarely-needed edge, deferred.
- **No `Super`/Windows-key modifier** in the string syntax (the client's `KeyMatch.MODS` is Shift/Ctrl/Alt only).
- **Hotkey handler CPU time** is subject to the per-call instruction watchdog (D-018 layer 1); like other
  non-tick Lua entries it partly escapes the soft per-tick budget (the same gap tracked for draws/action/message
  hooks).
- **No key-up / key-repeat / chord (sequential) bindings** — `bind` fires once on key-down. Press-and-hold or
  release semantics are a later addition if a use case arises.
