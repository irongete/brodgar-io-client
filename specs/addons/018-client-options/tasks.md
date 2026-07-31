# Tasks: 018 — hafen.client:options()

## 018.1 — Java bridge: OptionsHandle + subsystem handle classes ✅ DONE (2026-07-31)
Implement the five LuaBridge subclasses that back the Lua API. Each class reads/writes prefs directly (not via OptWnd), handles thread-safety, and supports both `methodName()` read and `methodName(value)` write patterns (or specialized patterns like `register(name, fn)` for keybindings).

**Context:**
- See [design/06-lua-api.md](../design/06-lua-api.md) for bridge patterns (handle objects, method dispatch)
- Study `src/io/brodgar/addon/lua/GobHandle.java` for reference-based handle mechanics
- Read `Utils.java` (Utils.getpref/setpref*), `GSettings.java` class structure, `KeyBinding.java` registry
- Audio: `Audio.Root.volume()` / `Audio.Subsys.volume`, `UI.audio.sys` / `.aui` / `.pos` / `.amb`

**Deliverables:**
- ✓ `OptionsHandle.java` — factory; returns a Lua table `{ interface = ..., video = ..., audio = ..., camera = ..., keybindings = ... }` with method proxies
- ✓ `InterfaceOptions.java` — `scale()`, `posGran()`, `angGran()` (read/write via Utils.getprefd/setprefd + MapView fields)
- ✓ `VideoOptions.java` — `shadows()`, `renderScale()`, `vsync()`, `fpsLimit()`, `bgFpsLimit()`, `lightingMode()`, `lightLimit()` (GSettings-backed)
- ✓ `AudioOptions.java` — `masterVolume()`, `uiVolume()`, `eventVolume()`, `ambientVolume()`, `latency()` (Audio system-backed)
- ✓ `CameraOptions.java` — `invertHorizontal()`, `invertVertical()` (Utils.pref* + MapView fields)
- ✓ `KeybindingsOptions.java` — `register(name, fn)`, `get(name)`, `set(name, keycode)`, `list()`, `unregister(name)` (KeyBinding registry-backed)
- ✓ Shared helper for method dispatch: check arg count, dispatch read (no args) or write (1 arg) + return self
- ✓ All setters queue to UI thread if needed; readers use monitor locks for concurrent reads
- ✓ `ant hafen-client` builds cleanly; no errors or warnings
- ✓ **Retire:** Remove old `hafen.key.bind` bridge code from LuaVM (if it exists as a separate wired function)

**Verification:** Compiles; all handle classes are instantiable; a manual test in `:lua` can construct each one and call a getter; `:lua` `hafen.client:options():keybindings():list()` returns a table.

---

## 018.2 — Retire `hafen.key` and port its three consumers ✅ DONE (2026-07-31)
**The Lua wiring landed in 018.1** — `hafen.client:options()` is installed by `AddonManager`, all five
subsystems proxy to Java, setters chain, and the REPL verification passed. What remains is the hard cut.

Per the maintainer (2026-07-31): nothing is released, so there is **no backward compatibility, no aliasing and
no transition period** — delete `hafen.key` outright and port its consumers in the same task.

**Context:**
- `src/io/brodgar/addon/HookApi.java` — `install()` still registers the `hafen.key` table; `newKeyBind`/
  `parseKeyMatch` are already package-private and reused by `KeybindingsOptions`
- Consumers to port: `addons/bags`, `addons/hello`, `addons/widgetstack` (grep `hafen.key.bind`)

**Deliverables:**
- ✓ **Remove** the `hafen.key` table from `HookApi.install` — the namespace stops existing (`newKeyBind` also lost
  its default-key argument and its per-bind Lua handle: `get`/`unregister` already answer by name, D-013)
- ✓ Port all three addons to `hafen.client:options():keybindings():register(name, fn)`
- ✓ **Their hotkeys now start UNBOUND** (D-047): each addon's README/docs advertises a *suggested* key
  instead of claiming one, and the maintainer assigns keys once in Options ▸ Keybindings
- ✓ `ant hafen-client` clean build; `grep -rn "hafen.key" src addons` returns nothing

**Verification:**
- `:lua hafen.key` → `nil`
- Each ported addon appears under its own section in Options ▸ Keybindings, initially unassigned
- Assign a key to each; confirm it fires and survives `:reload`

---

## 018.3 — Documentation: API reference + codebase coverage ✅ DONE (2026-07-31)
Write the user-facing API docs in `docs/addons/api/client.md`, update the catalog, extend the codebase map, and remove `hafen.key` references entirely. **Documentation reflects the current state only — no "before/after" notes or deprecated-style markers.**

**Context:**
- Model: `docs/addons/api/gob.md` (OOP pattern, method signatures, examples)
- Update: `docs/addons/api/README.md` catalog, `docs/addons/README.md` "API at a glance" table
- Codebase: add "Options / Preferences" subsection to `specs/codebase/services.md`
- **Clean state:** Remove `docs/addons/api/key.md` entirely if it existed; no "hafen.key was retired" notes in other docs

**Deliverables:**
- ✓ `docs/addons/api/client.md` — **clean, present-tense only**: full reference for interface/video/audio/camera/keybindings subsections, method sigs, return types, side effects (e.g., "requires restart")
  - No "formerly called hafen.key.bind()" or "hafen.key.bind() has been moved" notes
  - Example code: read scale, write scale, chain multiple setters; register hotkey, read/write bindings
  - Note which options require a restart (uiscale) and which apply live
  - Keybindings examples: `register("my-hotkey", fn)`, `get("inv")`, `set()`, `list()`, `unregister()`
- ✓ `docs/addons/api/README.md` — add `client.md` to the catalog; ~~**fully remove** `key.md` entry~~ **done in 018.2**
  (`api/keys.md` deleted, and `hafen.key` stripped from `hooks.md`, `getting-started.md`, both READMEs and the
  standing `specs/addons/design/` set — retiring the namespace included its prose)
- ✓ `docs/addons/README.md` — add `hafen.client` to the "API at a glance" table (brief 1-liner); ~~**fully remove**
  `hafen.key` line~~ **done in 018.2**
- ~~`specs/codebase/services.md` — "Options / Preferences" section~~ **already done in 018.1** (paid as that
  task's coverage toll: the two pref stores, the `GSettings`-is-immutable trap, `lightmode` values, the
  live-field+pref double write, and the hand-written keybind panel)
- ✓ Document the gotchas that bit the implementation, since they shape the API's contract: `uiscale` needs a
  restart; `angGran` is degrees (the client stores the divisor `180/x`); `lightingMode` is `"simple"`/`"zoned"`;
  audio/video read `nil` before the UI exists
- ✓ State plainly that **addon hotkeys start unbound** and the user assigns the key (D-047) — with the
  suggested-key convention for addon authors

**Verification:** 
- Docs build; links are valid; example code matches the implemented API
- **No historical references:** grep for "hafen.key" in docs/ returns zero results
- grep for "was moved", "deprecated", "formerly" in docs/addons/api/ returns zero results
- User reads `docs/addons/api/client.md` and learns exactly how keybindings work, with no confusion about prior state

---

## 018.4 — Test harness: a dedicated "Brodgar.io Options Test" addon ✅ DONE (2026-07-31)
Exercise `hafen.client:options()` end-to-end — interface / video / audio / camera / keybindings — in a **new,
dedicated addon**, `addons/optionstest/`.

**Maintainer decision (2026-07-31):** `hello` is already too large to keep absorbing whole feature
demos, so this one does **not** fold into it. `AREA.md` explicitly allows a dedicated example addon
when a feature does not fold in cleanly; this is that case. `hello` is left untouched — it keeps
covering hotkey *registration* (its four hotkeys already run through `options:keybindings()`), so the
standing regression on the keybindings path is not lost.

**Context:**
- Create: `addons/optionstest/manifest.json` (`id` MUST equal the folder name; `name` is the
  Options ▸ Keybindings section header) + `addons/optionstest/main.lua`
- Model: `addons/netdemo/main.lua` — small, slash-command-driven, one demo per sub-command
- API contract: `docs/addons/api/client.md`

**Deliverables:**
- ✓ `addons/optionstest/` — read-only-by-default addon (declares no permissions, so it needs no consent)
- ✓ Reads every subsystem at `OnLoad` (login screen — video/audio legitimately read `nil` there) and
  again at `OnEnterWorld`, proving the "before the client is up" rule from the docs
- ✓ Writes are **non-destructive round-trips**: read → write a different value → read back → restore the
  original, logging all three, so the maintainer's real settings survive the test. `scale` is never
  written (restart-gated)
- ✓ Chaining demo: several setters in one statement (`:posGran(v):angGran(v)`)
- ✓ Keybindings: `register`, `get` (own + a built-in like `inv`), `set`, `list`, `unregister`
- ✓ Error handling: `pcall`s an invalid write (`scale(-1)`, `lightingMode("fancy")`, `set` on an unknown
  name) and logs that each failed loudly instead of silently doing nothing
- ✓ A registered hotkey that starts UNBOUND (D-047); the manifest advertises a *suggested* key
- ✓ No external dependencies; `ant hafen-client` still builds

**Verification (by maintainer in-game):**
- Launch the client: the addon appears in the AddOns panel, enabled, and logs its `OnLoad` read
  (video/audio `nil` on the login screen); at login the `OnEnterWorld` dump shows real values
- `:opttest` sub-commands: `dump`, `write`, `chain`, `keys`, `error` — each logs as described
- After `:opttest write`, open Options and confirm every touched setting is back to its original value
- Options ▸ Keybindings shows a **Brodgar.io Options Test** section, initially unassigned; assign a key,
  confirm the hotkey fires and survives `:reload`
- Change an option in Options (e.g. camera inversion), then `:opttest dump` → the new value is read
- Regression: `hello` and the other addons still load and behave as before

---

## Sequence

1. **Implement 018.1** (Java bridges + retire hafen.key)
2. **Implement 018.2** (Lua glue + remove old hafen.key wiring) — depends on 018.1
3. **Implement 018.3** (docs + remove hafen.key doc) — can happen in parallel with 018.1/018.2
4. **Implement 018.4** (test addon exercises full API) — depends on 018.2
5. **Maintainer in-game verification** — full integration test (BetterOptions use case, keybindings work, no regressions)
6. **Close with `/end`** — commit all at once (src + docs + addons + specs)

---
