# Spec: 018 — hafen.client options API

**Feature:** Expose the client's Options window (OptWnd) settings to Lua addons via a fluent, chainable API.

## What & Why

The Hafen client stores user preferences (interface scale, position, angle, camera settings, keybindings, audio levels, etc.) in OptWnd and underlying preference stores. Today, addons have **zero access** to read or modify these settings programmatically. This blocks addons from creating alternative UI layers (e.g., a "BetterOptions" addon that replaces OptWnd entirely) or inspecting/manipulating keybindings.

This feature opens a **`hafen.client:options()` namespace** that exposes **all OptWnd settings** with a Lua-idiomatic single-method read/write pattern. It unifies keybinding registration (today scattered across `hafen.key.bind()`) into one coherent API:

```lua
-- Read
local scale = hafen.client:options():interface():scale()

-- Write + chain
hafen.client:options():interface():scale(1.2):angle(15)

-- Keybindings (unified)
hafen.client:options():keybindings():register("my-hotkey", myFunction)
hafen.client:options():keybindings():get("inv")
hafen.client:options():keybindings():set("opt", "Ctrl+O")
hafen.client:options():keybindings():list()
```

**Why:** 
- Addons benefit from UI scale (HUD sizing), placement hints (overlay layout), and full settings access.
- A "BetterOptions" addon can read/write the entire client config and replace OptWnd.
- Keybindings are now exposed through the same unified surface, eliminating the need for separate `hafen.key.bind()`.
- The idiomatic Lua pattern (single method, overloaded read/write) keeps the API surface minimal and consistent.

## Acceptance Criteria

1. ✓ `hafen.client:options()` returns an Options handle object
2. ✓ Options handle exposes subsections: `interface()`, `video()`, `audio()`, `camera()`, `keybindings()`
3. ✓ Interface/video/audio/camera: getters/setters as single methods (e.g., `scale()` / `scale(value)`)
4. ✓ Keybindings: `register(name, fn)`, `get(name)`, `set(name, keycode)`, `list()`, `unregister(name)`
5. ✓ All methods return `self` (or subsection handle) for chaining
6. ✓ Writes persist to the client's preference store (same as GUI edits)
7. ✓ Changes requiring a restart are documented (e.g., interface scale)
8. ✓ Full API reference documented in `docs/addons/api/client.md`
9. ✓ `addons/hello` addon exercises the full API (interface/video/audio/camera/keybindings)
10. ✓ `hafen.key.bind()` is retired; all keybinding functionality moves under `options:keybindings()`

## Out of Scope

- GUI modifications to OptWnd itself (that is separate haven work)
- Real-time application of settings that require a restart (behavior matches OptWnd: change is persisted, effect is on next login)
- Addon-specific options sub-panels (deferred; see design/10-options-panel.md open items)
- Voice Chat Integration settings (no addon use case identified yet; can be added later if needed)

## Context Files

- **Java backing:** `OptWnd`, `Utils.getpref/setpref`, `KeyBinding`, audio/video subsystem prefs (see `codebase/services.md`)
- **Design:** `design/06-lua-api.md` (conventions), `design/07-ui-and-drawing.md` (UI namespace shape), `design/13-hooks-and-interception.md` (hotkey integration)
- **Related:** `017-gob-oop` (reference-based Gob API pattern to emulate for Options handle)
- **Retire:** `hafen.key.bind()` — all hotkey functionality moves to `options:keybindings()`
- **Docs:** `docs/addons/api/README.md` (catalog to extend)
