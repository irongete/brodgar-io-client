# Learnings — Engine, lifecycle & persistence

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- The in-game console **strips quotes** (`Utils.splitwords`) → use **`Console.rawcmd()`** for raw
  input (done for `:lua`).
- `UI.msg(String)` shows in-game notices; at **session init (pre-HUD)** logs may only reach stdout —
  `AddonManager.log` writes to both.
- **Invisible widgets are still ticked.** `TickEvent.shandle` calls `w.tick()` regardless of
  `visible`; only `draw` skips invisible children. So a `visible=false`, zero-size child of
  `ui.root` is a perfect zero-core-edit per-frame hook (the `AddonRoot` tick pump). `ui.root` exists
  before `RemoteUI.init` runs (set in the `UI` ctor just before `fun.init(this)`), so attaching in
  `AddonManager.init` is safe.
- **`OCache.callback` holds callbacks in a `WeakList`** → you MUST keep a strong ref to the
  `ChangeCallback` (static field `ocCb`) or it gets GC'd and stops firing. `added/removed` fire on
  the network/loader thread → enqueue to a `ConcurrentLinkedQueue` and drain on the UI-thread tick.
- **The `:lua` REPL is a resource owner** (synthetic `Manifest.internal("(console)")`), so
  events/timers are testable from the console. Its subscriptions **persist across relogs** (the REPL
  `Globals` is long-lived and is NOT torn down on `init`, unlike real addons).
- **Two different "not ready yet" races at `OnEnterWorld`** (both surfaced in the 1c-2 in-game test,
  both correctly returned nil, not errors): (1) **HUD not assembled** — `OnEnterWorld` was firing from
  `MapView`'s ctor (loader thread → next tick) *before* GameUI exists in the tree, so **even the
  `ui.root` scan found nothing** and `player.name` was nil. Real fix: **gate the event** —
  `if(enterWorldPending && gui() != null) fire("OnEnterWorld")`. Now it fires once the HUD is up
  (≈ WoW `PLAYER_ENTERING_WORLD`), so every GameUI-backed read works inside the handler. Safe because
  `enterWorldPending` is reset each session in `init()`, and MapView (hence its GameUI ancestor) always
  materialises, so it can't stick. (2) **data-streaming** — the MCache grid, terrain z, and camera for
  the spot download a beat AFTER enter-world, so `map.tile`/`height`/`gridPos`/`worldToScreen` are nil
  for the first frames, then resolve. Nothing to "fix" (the data isn't here) — the `hello` harness
  reads map data **twice** (`readPlace("now")` + `timer.after(3, readPlace("+3s"))`) to demonstrate
  them. `hafen.gob.*`/`world.*`/`time.*` (incl. astronomy) ARE ready immediately at enter-world.
- **A THIRD "not ready yet" race at `OnEnterWorld` (data-streaming, 1c-3):** char cattrs, `lp`/`weight`,
  and the inventory/equipment widgets **arrive a beat AFTER enter-world** — in the in-game test they
  were all nil/empty at the immediate `OnEnterWorld` read and populated ~seconds later (`str`→124/178,
  `lp`→403475, `weight`→12429, inventory 0→12, equipment 0→19). Same shape as the 1c-2 map-data stream.
  Not a bug (data isn't here yet; all Loading-guarded → nil, never errors). Addons must read char/items
  on a later tick/timer or off a change event, **not** synchronously inside the `OnEnterWorld` handler.
  The `hello` harness reads char/items twice (`readInv`/`readChar` at `now` + a `+3s` timer) to show it.
  (The `*Changed` semantic events that make this ergonomic come with the widget-tree mechanism, 1d.)
- **Play a sound without blocking the UI thread:** mirror `GobIcon.resnotif` — `Indir<Resource> r =
  Resource.local().load(name)` then `glob.loader.defer(() -> { res = r.get(); ui.sfx(Audio.fromres(res)); }, null)`,
  **rethrowing `Loading`** (the loader re-runs the task) and catching other `RuntimeException` (report + swallow).
  `Resource.Named implements Indir<Resource>` so `Pool.load(String)` feeds `Music.play(Indir,loop)`
  directly (music resolves on its own player thread — no defer needed; `Music.play(null,false)` stops).
  Bundled sfx that always resolve locally: `"sfx/msg"`, `"sfx/error"`, `"sfx/hud/btn"`.
- **Saved variables (1e) — the per-char folder is unknown when addons load.** Addons load at
  `RemoteUI.init` (session bind), **before** the HUD exists, so `<genus>_<char>` (= `GameUI.genus`/`chrid`)
  can't be known yet. Resolution: **account-scope** vars (no char needed) load at addon-load (before
  `OnLoad`); **per-char** vars load at the **first `OnEnterWorld`** (gate: `gui() != null`, same as the
  retimed event) — so the per-char store is ready *inside* the OnEnterWorld handler (no post-enter
  streaming delay, unlike the read API). Rule for addon authors: **read/init per-char `hafen.store` from
  `OnEnterWorld` onward, never in `OnLoad`.**
- **Flush must use a CAPTURED scope, not a live `gui()` lookup.** A relog calls `AddonManager.init(newUi)`
  which sets `ui=newUi` then tears down the OLD addons — at that moment the new `GameUI` doesn't exist and
  a live `gui()` returns null, so the old character's data would have nowhere to go. Fix: **capture
  `charScope = "<genus>_<char>"` at OnEnterWorld** (static volatile) and flush against it; reset it to
  null in `init()` **after** the old-session teardown flush. Teardown order is **`OnDisable` → flush**
  (fire the event first so the addon's last-chance writes are captured, then persist) — the spec's
  "flush then OnDisable" numbering is just a list; capturing OnDisable writes is strictly better.
- **Reuse the REPL `json()` writer + the `Json` reader for persistence — they're already correct.** The
  1e serializer is literally the compact `json(LuaValue)` used by `:lua` (array/object auto-detect, cycle
  guard); the reader is the manifest `Json.parse`. Only new glue: `jsonToLua`/`fillTable` (JSON→Lua:
  object→string-keyed table, array→1-based; **fill in place** so a cached `hafen.store.x` ref stays valid).
  **Numbers are doubles** (integers >2⁵³ lose precision — same reason grid ids are strings). An **empty**
  Lua table serializes as `[]` (can't distinguish empty array from empty object) but round-trips as an
  empty table — cosmetic only. **A saved var can be an array** (`hafen.store.data = {1,2,3}`), so `fillTable`
  must handle both List and Map, not just Map (round-trips arrays intact).
- **Write-skip + throttled auto-save keep persistence cheap and crash-safe.** Per-scope, cache the last
  serialized JSON on the `Addon`; a flush that produces the same string **skips the disk write**. A **30 s
  throttled auto-save** in `tick()` (UI thread → no races reading the Lua tables) covers an unclean exit
  without a racy JVM shutdown hook; a relog still flushes cleanly via teardown. Writes are **atomic** (write
  `<file>.tmp`, then `Files.move` with `ATOMIC_MOVE`, fall back to `REPLACE_EXISTING`) and **non-fatal**
  (a read-only install is logged, never thrown — a broken save can't break the addon or the frame).
- **`savedata/` resolution mirrors `addonDir()`** — it's the **sibling of the resolved addon dir**
  (`saveDir()` = `addonDir().getParentFile()/savedata`), so it tracks dev vs release automatically
  (`bin/savedata` in a release; `${basedir}/savedata` under the dev override) without a second rule.
  `-Dhaven.savedatadir` overrides. Added `/savedata` to `.gitignore` (the dev-override path at repo root;
  `bin/` was already ignored). Sanitize genus/char to one safe path segment (`[A-Za-z0-9._-]`, else `_`).
- **Reload = reuse the relogin path, don't re-implement it (1f-2).** `AddonManager.init(ui)` already
  does teardown-all → reset → `loadAll`; a `:reload` is the same *minus* the session-infra reset. So
  `reload()` reuses the existing `teardown(Addon)` (OnDisable → flush → clear subs/timers) and `loadAll`
  verbatim, and only adds the in-world tail (restore per-char vars + re-fire `OnEnterWorld`). This
  guarantees reload and relogin can't drift, and keeps the new code tiny. **Do NOT** touch the
  session-scoped infra on reload — the `AddonRoot` tick pump, the `OCache` gob callback, and the
  inbound-`uimsg` tap belong to the session, not the addon layer (D-005 "addon layer only"); rebuilding
  them would be wrong and would double-register.
- **Defer reload to the UI-thread tick — console commands aren't all on one thread.** In-game `:`
  commands run on the **UI thread** (`ConsoleHost.done` ← key event), but the terminal/stdin console
  (`Chatwindow`'s `System.in` reader loop) runs `ui.cons.run` on **its own reader thread**. So a command
  that mutates widgets/Lua state (reload) must not act inline. `:reload` just sets a `volatile
  reloadPending`, and `tick()` runs `reload()` at the top of the next frame (UI thread) — the exact
  `enterWorldPending` pattern. `reload()` is `synchronized` (same monitor as `init`) so a reload and a
  concurrent relogin serialize instead of interleaving; `init` clears `reloadPending` so a reload queued
  against a dead session is dropped. (The `:lua` REPL calling Lua directly from stdin has always been
  off-UI-thread; reads are tolerant, but *reload* is not, hence the defer.)
- **On reload, re-fire `OnEnterWorld` (WoW `PLAYER_LOGIN`), and know it re-ticks login counters.** Spec
  05 step 6 says fire `OnLoad` then `OnEnterWorld` if already in-world, so addons re-init as if freshly
  logged in. Unlike first login there's **no streaming delay** — the HUD/char/items are already resolved,
  so reads inside the re-fired handler work immediately. Consequence to document for addon authors: any
  "logins"/"world entries" saved-var counter also increments on each `:reload`. `restorePerChar()` is
  reused to reload per-char saved vars first (charScope is still valid in-world).
- **Enabled set = a persisted DISABLED set (default-enabled, WoW), in the client prefs (D-006).** Storing
  the *disabled* ids (not the enabled ones) makes a freshly-installed addon **default to enabled** for
  free (it's absent from the set) — matching WoW, and failure-safe (a lost prefs entry re-enables, never
  silently disables). Persist it under the **client folder** via the client's own `Utils.getprefsl`/
  `setprefsl` (a `List<String>` in Java Preferences, key `addons/disabled`) — NOT `savedata/` (that's
  per-char/account saved *variables*, a different scope). `loadAll` reads the set once per scan and skips
  disabled folders (by folder name = addon id, checked before parsing the manifest). Toggling is
  **apply-on-reload** (D-006): `setEnabled` only persists + flags `reloadNeeded`; the change lands on the
  next `:reload`/login. `isEnabled`/`setEnabled` are **public** for the 1f-3 panel; enable/disable is NOT
  an addon-facing Lua API (operator/panel only, D-004).
- **`:addons` should scan the folder, not just the loaded list.** A disabled addon isn't in `addons`, so
  the panel/console must enumerate `addonDir()` folders (those with a `manifest.json`) and cross-reference
  the loaded set + the disabled set to show every discovered addon's status (loaded version / `disabled` /
  `not loaded` / `error`). `findLoaded(id)` maps a folder id back to its live `Addon`. Same enumeration the
  1f-3 panel will need.
- **The options panel lives in `io.brodgar.addon.ui`, NOT nested in `OptWnd` (1f-3) — one-line core edit.**
  Spec 10 endorses either, but the panel needs **no** package-private `haven` access (every widget it
  uses — `Scrollport`/`CheckBox`/`Button`/`Label`/`Widget.add`/`settip`/`pack` — is public, and `ACheckBox.a`
  + `set(boolean)` are public so the voice-style `new CheckBox(){ {a=…} set(){…} }` subclass works
  cross-package), and it only calls the **all-static `AddonManager` facade** (exactly like the voice panel
  calls `Voice`). So the ONLY `haven` edit is one `// addon:` `PButton` line in `OptWnd`; the whole panel
  is new code in the addon package. **`OptWnd.Panel`/`PButton` are non-static inner classes** → extend/
  instantiate them from another package with the qualified `opt.super()` (first ctor statement) and
  `opt.new PButton(...)` forms. It compiles clean at source 1.8.
- **`OnEnterWorld` was firing BEFORE `GameUI` is attached to `ui.root` (2a fix — the real "entered" signal).**
  The 2a demo window was invisible on a fresh login but appeared after `:reload`. Root cause found by
  instrumenting the server→client widget protocol (temporary `[wdbg]` traces at `UI.NewWidget.run` /
  `AddWidget.run` / `UiMessage.run` + a marker where we fire OnEnterWorld): across 3 logins, our marker fired
  **exactly one line before** `ADD GameUI -> parent RootWidget`. `MapView` sets `enterWorldPending` from its
  ctor and `gui()` finds `GameUI` **via the map view** (`view.getparent(GameUI.class)`) a beat before GameUI
  is added to the root — so the old gate `gui() != null` fired too early. A window added to `ui.root` then is
  a sibling placed **before** GameUI → the full-screen HUD draws **on top of it** (hidden). On `:reload`
  (in-world) GameUI is already under root, the window is added **after** it → on top → visible. That's the
  exact login-vs-reload asymmetry. **Fix:** gate OnEnterWorld on `gui().parent != null` (GameUI is in the
  tree), firing the tick after the HUD mounts — one clean line in `tick()`, benefits every addon (ui.root
  windows now land on top), and OnEnterWorld is semantically correct (the HUD root is actually attached).
  Streaming-in of GameUI *children* (inv/meters/char) still lags a beat — unchanged; read those on a timer.
  **The reliable "fully entered the world" signal is `GameUI` being added to `RootWidget`** (≈ WoW
  `PLAYER_ENTERING_WORLD`); `gui().parent != null` is the cheap UI-thread test for it.
- **A `:lua`/`hafen.log` result rendered in-game is ONE text texture — clamp it, or a big result crashes the GPU.**
  `A2 categories()` (~400 entries → ~40 KB single-line compact JSON, no spaces = no wrap points) made the REPL's
  `UI.msg(result)` build a text texture wider than `GL_MAX_TEXTURE_SIZE` → `GL_INVALID_VALUE (1281)` on the render
  thread → client crash. The **read was correct** (full result fine on the terminal) — it's a pre-existing REPL
  limit that the first ~40 KB read exposed. Fix once at the choke point: `clampMsg` bounds every string handed to the
  in-game notice sink (`UI.msg`/`error`) to ~500 chars; stdout keeps the full text and addons still get the real Lua
  value. **Takeaway for future reads that can return large collections:** the data path is fine (Lua/terminal handle
  size), but the in-game notice render is texture-bounded — never `UI.msg` an unbounded string.
- **V5b: a "bundled Lua library" ships as a separate file exposing a global — addon files share one env, `require` is
  withheld.** `Addon.run()` loads `manifest.files` in order into ONE `Globals`, so `gizmo.lua` (listed first) sets a
  global `gizmo` that `main.lua` reads. The sandbox doesn't lock the global table, so new globals are fine. This is
  the D-031 "ships in the planner example" shape; promote to a real `hafen.ghost.gizmo` later with a thin Java shim.
- **`files` in a manifest lists only the `.lua` sources, not assets.** `hello`'s manifest lists `["main.lua"]` though it
  ships `icon.png`/`tank.glb`; the planner's stays `["gizmo.lua","main.lua"]` with `cube.glb`/`icon.png` unlisted. Assets
  are resolved on demand by `hafen.render.*` (addon-relative, D-017-sandboxed) and packaged by `ant bin`'s whole-folder
  copy (`<fileset dir="addons"/>`). Don't add assets to `files`.
- **When resource-published code is the blocker, ADOPT it — the engine has a sanctioned mechanism.**
  `doc/resource-code`: `java -cp bin/hafen.jar haven.Resource get-code <res>` extracts the published source into
  `src/haven/res/...` annotated `@FromResource(name, version)`, and `Resource.ResClassLoader` prefers the local
  class **only when name+version match the resource it replaces** (else it warns and uses the resource's own code).
  That turned F3d's "intrinsic limit" into a 6-line edit. Price: a **version pin** — re-run `get-code` when the
  game updates that resource; never use `override = true` to dodge it. **Method for finding the code:** the
  resource cache files carry the resource name in a plaintext header and the `.res` keeps the published SOURCE, so
  you can read un-editable code instead of guessing (plus a temporary `Tip`-class diagnostic in `longtip`).
- **(F4) When the fork has no call site, ask "is this code even ours?" before assuming a missing feature.** The
  `"world.nick"` surface looked unmapped because it *is* not in `src/`: `haven.KinInfo` was deleted upstream and the
  floating kin name now ships as published code inside `ui/obj/buddy`. The tells were `OCache.OD_BUDDY` commented
  `-- Removed` and `Partyview`'s only `KinInfo` use sitting inside a comment block. `get-code <res>` + `@FromResource`
  (the F3d technique) is now a **routine** tool, not a last resort — the second `src/haven/res/` tree in the fork.
- **(F4) Adopting published code: diff the fetch against the server you actually play on.** `get-code` uses the
  configured `haven.resurl`, which is not necessarily the maintainer's server. Fetch twice (`-U <server> -o <tmp>`)
  and `diff -r`; then `haven.Resource find-updates src` to confirm the pin is current. A takeover check
  (load the resource through `Resource.remote()`, assert the entry-point class sits on the **AppClassLoader** and
  carries our edit) is cheap and catches the `-alt`-variant class of mistake F3d fell into.
- **(F4) When adopting, look for the invalidation the original author already wrote.** `Info` had a `dirty()` that
  disposes the composed `Tex`; the whole slice was *calling* it on a `Fonts.gen()` move. Adding a second
  invalidation path would have been strictly worse.
- **(020.2) An adopted class READ as an attrib needs a name-based fallback, or a version bump goes silently
  blind.** `gob:kin()` reads the `ui/obj/buddy` `GAttrib` via `getattr(Buddy.class)`. If the server ships v5,
  the `@FromResource(version=4)` pin stops matching, [`ResClassLoader.loadClass`](src/haven/Resource.java:1556)
  drops our copy, and the resource's own class — **same FQN, different `Class`** — is installed instead: the
  `getattr` then answers `null` for *every* gob, and nothing warns at the read site. The fix is cheap: on a
  miss, scan `Gob.attr`'s values for a class whose `getName()` matches and read the field reflectively, so a
  bump degrades to *slow*, never to *wrong*. **Rule: adoption used for an EDIT fails loudly (the warn + the
  edit disappears); adoption used to READ a value fails silently — pay for a fallback, or at least an
  assertion.** (`Gob.attr` is package-private, so the fallback is `getDeclaredField("attr")` +
  `setAccessible`; latch it off on `NoSuchFieldException` rather than retrying per gob.)
- **A one-shot clip has no end-of-clip signal — only a lazy drain.** [`Audio.Mixer.get`](src/haven/Audio.java:68)
  removes a `CS` the frame its `get()` returns `< 0`; there is no callback and no `done()` flag. So "is my sound
  still playing" IS `Mixer.playing(cs)`, and the same call is the prune: a per-addon record of started clips
  cannot grow, because every read of it drops what has drained. Design the read as the sweep and there is no
  bookkeeping to keep honest. (Stop/test are fully public — `ActAudio.RootChannel.remove(cs)` / `mixer()` — so
  the whole 024.2 surface needed **zero** core edits.)
- **A deferred start must be cancellable, and "cancelled" is not enough — the start has to be ATOMIC with the
  registration.** `sound:play()` resolves on a loader thread, so `:play():stop()` has to cancel a play still in
  flight: a per-key generation stamp the deferred task re-checks does that. But the first draft still leaked
  sound in theory, because the task registered the clip, released the monitor, and only *then* handed it to
  `UI.sfx` — a `:stop()` landing in that window "stops" a clip the mixer has not received yet, and the play
  lands right after. Register + start under ONE monitor (lock order: the per-key state → the channel/mixer,
  never the reverse) and the window is gone. **Rule: for any deferred acquire, the stamp check, the registration
  and the hand-off to the engine are one critical section.**
- **The `:lua` REPL is not an addon and never gets `AddonRegistry.teardown`** — `consoleOwner` deliberately
  outlives sessions and reloads. Any per-addon teardown that is a *user-visible promise* ("a `:reload` silences
  what the addon left playing") must therefore be swept for the REPL explicitly in `reload()`, or the rule has a
  silent exception exactly where the maintainer tests it. (`UiApi.resetSession` already had this shape for the
  console's models/replacers — follow it rather than inventing a second pattern.)
