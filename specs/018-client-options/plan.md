# Plan: 018 — hafen.client:options() implementation

## Approach

Expose OptWnd's **five subsystems** as Lua handle objects:
1. **`interface()`** — UI scale, placement granularity (position/angle)
2. **`video()`** — graphics settings (GSettings-backed: shadows, render scale, vsync, lighting, framerate limits)
3. **`audio()`** — audio levels (Audio system-backed: master, UI sounds, events, ambient, latency)
4. **`camera()`** — camera inversion toggles (MapView + Utils.pref-backed)
5. **`keybindings()`** — hotkey registration, inspection, and mutation (KeyBinding registry-backed; **replaces `hafen.key.bind()`**)

(VoiceChatPanel is **out of scope** for v1; no addon use case identified yet.)

### Lua API shape
```lua
local opts = hafen.client:options()      -- returns Options handle

-- Interface
local scale = opts:interface():scale()   -- 1.0..N (from Utils.getprefd("uiscale"))
local pos_gran = opts:interface():posGran()   -- 2..17 or 0 (infinite)
local ang_gran = opts:interface():angGran()   -- 4° to 120° (actual angle degrees)
opts:interface():scale(1.2):posGran(5)   -- chain

-- Video (GSettings-backed)
opts:video():shadows()              -- bool; write: :shadows(true)
opts:video():renderScale()          -- float 0.5..8.0
opts:video():vsync()                -- bool
opts:video():fpsLimit()             -- int or math.huge (no limit)
opts:video():bgFpsLimit()           -- int or math.huge
opts:video():lightingMode()         -- "simple" or "zoned"
opts:video():lightLimit()           -- int 1..32

-- Audio
opts:audio():masterVolume()         -- 0.0..1.0
opts:audio():uiVolume()
opts:audio():eventVolume()
opts:audio():ambientVolume()
opts:audio():latency()              -- milliseconds

-- Camera
opts:camera():invertHorizontal()    -- bool
opts:camera():invertVertical()      -- bool

-- Keybindings (replaces hafen.key.bind)
opts:keybindings():register("my-hotkey", myFunction)  -- addon registers a hotkey
opts:keybindings():get("inv")                   -- read: returns keycode string or nil
opts:keybindings():set("opt", "Ctrl+O")         -- write: change binding
opts:keybindings():unregister("my-hotkey")            -- remove a binding
opts:keybindings():list()                             -- returns { name = keycode, ... }
```

### Implementation layers

**Java-side (`src/io/brodgar/addon/lua/`)**:
1. **`OptionsHandle`** — bridge wrapper, holds references to OptWnd/UI/GSettings/KeyBinding registry
2. **`InterfaceOptions`**, **`VideoOptions`**, **`AudioOptions`**, **`CameraOptions`**, **`KeybindingsOptions`** — subsystem handles
3. Interface/Video/Audio/Camera: implement `LuaBridge` with `call(String method, Object... args)` to handle `scale()` / `scale(value)` dispatch
4. Keybindings: implement `LuaBridge` with `call(String method, ...)` to handle `register(name, fn)`, `get(name)`, `set(name, keycode)`, `list()`, `unregister(name)`
5. Thread-safe reads via UI monitor locks where needed; writes queue to UI thread
6. **Retire** `hafen.key.bind()` bridge code (no longer needed; functionality moved to KeybindingsOptions)

**Lua-side**:
1. **`hafen.client.options`** — factory function that returns an Options handle
2. Simple proxy methods that dispatch to Java via the bridge
3. Chainable by returning `self` from setters

### Key integration points

- **OptWnd access:** `hafen.client:options()` requires access to the current OptWnd (or read prefs directly from Utils/GSettings if OptWnd is not created yet)
- **GSettings:** Already exposed in `GameUI.getgprefs()` — use `GSettings.SettingException` + `GSettings.update()` for video options
- **Audio system:** Read/write via `ui.audio.sys` (master) + subsystem volumes (`ui.audio.aui`, `ui.audio.pos`, `ui.audio.amb`)
- **Preferences store:** Utils.getpref/setpref (strings) + Utils.getprefd/setprefd (doubles) + Utils.getprefb/setprefb (booleans)
- **KeyBinding registry:** `KeyBinding.get()` returns bindings; addons call `register()` / `unregister()` to mutate; storage is the same as OptWnd's BindingPanel uses

### Restart behavior

Options that require a restart (e.g., `uiscale`) are **documented** in the API but **applied live** — the change is persisted, and the effect happens on next login (matching OptWnd behavior).

### Testing harness

A dedicated `addons/optionstest` ("Brodgar.io Options Test") addon — `hello` is already too large to absorb
another whole feature demo — exercises:
- Read interface scale, position/angle granularity
- Set a new scale and log the old/new values
- Read video shadows and toggle them
- Read/write a camera inversion
- Verify chainability: `:scale(X):posGran(Y)` returns self

## Files to create / modify

**Create:**
- `src/io/brodgar/addon/lua/OptionsHandle.java` — main bridge (Options factory)
- `src/io/brodgar/addon/lua/InterfaceOptions.java` — interface subsystem
- `src/io/brodgar/addon/lua/VideoOptions.java` — video subsystem (GSettings-backed)
- `src/io/brodgar/addon/lua/AudioOptions.java` — audio subsystem
- `src/io/brodgar/addon/lua/CameraOptions.java` — camera subsystem
- `src/io/brodgar/addon/lua/KeybindingsOptions.java` — keybindings subsystem (replaces hafen.key.bind)
- `docs/addons/api/client.md` — API reference (interface/video/audio/camera/keybindings subsections)
- `addons/optionstest/manifest.json` + `addons/optionstest/main.lua` — the dedicated example addon

**Modify:**
- `src/io/brodgar/addon/LuaVM.java` — wire `hafen.client.options` to OptionsHandle factory; **remove** `hafen.key.bind` registration
- `src/io/brodgar/addon/lua/KeyBinding.java` — retire old `hafen.key.bind` code (if it exists as a separate bridge class)
- `docs/addons/api/README.md` — add `client.md` to the catalog; **remove** `key.md` if separate
- `docs/addons/README.md` — update "API at a glance" table with `hafen.client`; remove `hafen.key`

**Extend codebase doc:**
- `specs/codebase/services.md` — add "Options / Preferences" subsection (OptWnd, GSettings, Utils.pref*, Audio.sys, KeyBinding registry)

## Risks & gotchas

1. **OptWnd may not exist** — if an addon calls `hafen.client:options()` in a login context before OptWnd is created, we need a fallback that reads prefs directly. ✓ Handled by reading from Utils/GSettings/KeyBinding registry directly, not OptWnd state.
2. **Thread safety** — GSettings updates, Audio volume changes, and KeyBinding mutations must happen on the UI thread. ✓ Use `UIThreadQueue` / monitor locks.
3. **Live-apply mismatches** — some settings (render scale, lighting mode) reconfigure the engine on change. ✓ Acceptable: document as "changes persist, effect on next login" like OptWnd.
4. **API gaps** — the audio system doesn't expose sub-track-type volume control at the Lua level; we can only set `Audio.Root.volume` globally and the UI subsystems. This is acceptable v1; finer control is deferred.
5. **GSettings exceptions** — rare, but calling `update()` with invalid settings throws; wrap in try-catch and log.
6. **Keybinding conflicts** — if two addons try to register the same hotkey, the KeyBinding registry may reject or overwrite. ✓ Document that keybindings are global and addons should use namespaced names (e.g., "myAddon-action").
7. **Callback lifecycle** — addon hotkey callbacks must outlive the addon's environment (stored in KeyBinding registry). ✓ The bridge keeps strong refs to bound Lua functions; teardown explicitly unregisters them.

## Discarded alternatives

- **Mutating OptWnd directly:** Would couple addons to OptWnd's internal Widget tree. Instead, we expose a stable bridge that reads/writes prefs independently.
- **Exporting the entire GSettings object:** Would leak unstable implementation details (it's a value object with many internal settings). Instead, we curate the subset that's useful for addons.
- **Separate `read()` / `write()` methods:** e.g., `getScale()` / `setScale(X)`. Less Lua-idiomatic than overloaded single method. Rejected per API design rules (one canonical way).
- **Keep `hafen.key.bind()` separate:** Rejected because (a) no third-party addons yet, so no backward-compat cost, and (b) unified under `options:keybindings()` is architecturally cleaner for BetterOptions-style addons.
