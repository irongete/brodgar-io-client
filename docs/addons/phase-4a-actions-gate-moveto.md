# Phase 4a — Write-actions permission (D-027) + `hafen.act.moveTo`

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), **24 headless checks**
> (`moveClickCoord` coord-math incl. negative flooring; master-switch config-flag flip incl. default-OFF;
> `Manifest.usesActions()` declaration parse; `actionsGranted(owner)` across null / declaring /
> non-declaring × master on/off; `requireActions(owner, verb)` distinct errors; and the `hafen.act`
> namespace end-to-end in the **real facade** — a declaring owner passes the gate to `"no map view"` when
> granted and throws `"…permission is OFF"` when the master switch is off, a non-declaring owner throws
> `"…did not declare…"` even with the switch on) + LuaJ parse of the updated `hello`. `moveTo` sends a live
> `wdgmsg` → verified **in-game**.
> **In-game verification pending.**
> **Design:** [specs/addons/12-security-and-permissions.md](../../specs/addons/12-security-and-permissions.md),
> [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.act`),
> [specs/addons/09-events-catalog.md](../../specs/addons/09-events-catalog.md#appendix--action-message-reference-clientserver)
> (the action-message encodings), decisions **D-010** / **D-025** / **D-027** (the permission model).

The first slice of **Phase 4**, the **gated write-actions tier** — the one part of `hafen.*` that
**drives the character** (sends player-action `wdgmsg`s to the server) rather than only observing. It
delivers the mechanism the whole tier hinges on:

1. **The permission** — a two-part opt-in (D-027): a global master switch **plus** a per-addon manifest
   declaration. Modelled like **app permissions**.
2. **The first verb** — `hafen.act.moveTo(x, y)`, the flagship **click-macro** (the Phase-4 DoD): walk
   the character to a world position.

Everything else in `hafen.*` (Phases 1–3, the A-series) is **modding**: it changes *presentation*, not
what the character does. Writes are the same plain `hafen.*` API as reads — the **only** difference is
that a write verb requires the permission.

## Why gated — you stay in control (D-010 / D-025 / D-027)

The client is **server-authoritative**: an addon can only send what a *player could click* — no teleport,
no seeing beyond what the server streams. So this is **not a client exploit**. But write-actions let an
addon **act on your behalf** — move your character, use items, interact with the world — which is
powerful and consequential. So they ship **off** and are granted only through an **explicit, declared**
opt-in, so you know (and choose) which addons can drive your character. Custom clients and automation are
an established, permitted part of Haven & Hearth; this is about *your* control, not legality.

## The permission (D-027) — two parts, both required

A write verb runs only when **BOTH** hold:

1. **The global master switch is ON.** For now `actionsEnabled()` reads the `haven-config.properties` flag
   **`addons.actions.enabled`** (default `false`) — a config edit / `-Daddons.actions.enabled=true` enables
   it, restart to apply. Slice **4b** layers a **persisted, panel-toggled pref** on top so it can be flipped
   at runtime. This is the *user's* choice — never the addon's (a self-granting addon would defeat the
   opt-in). It reads the config flag **only** until 4b: with no runtime toggle yet, a stray persisted pref
   must not be able to strand the switch ON with no UI to turn it off.
2. **The calling addon DECLARED the permission** in its manifest:

   ```json
   "permissions": ["actions"]
   ```

   An **array** (`Manifest.usesActions()` checks it), so it can grow into finer categories later
   (`"actions.move"`, `"actions.items"`, …) without a format change. This is the *addon author's*
   responsibility, and it is what lets the client tell the user — **before** the addon runs — that it
   wants to drive the character (the basis for a future enable-time "this addon moves your character /
   interacts with objects" breakdown).

**`requireActions(owner, verb)`** enforces both, with a **distinct** guiding error for each failure —
declaration checked first (an addon-author bug: "add `permissions:[actions]` to your manifest"), then the
switch (a user setting: "turn it on in the AddOns panel"). **`actionsGranted(owner)`** = both halves.

The read/UI/event tiers are **completely unaffected** by this permission.

> The trusted operator console (`:lua` REPL) declares the permission implicitly (`Manifest.internal`), so
> `:lua hafen.act.…` works — still subject to the master switch, like any addon.

## `hafen.act` — the actions namespace

```lua
hafen.act.enabled()      -- bool; is THIS addon granted right now (master ON AND permission declared)?
hafen.act.moveTo(x, y)   -- walk to a WORLD position (the same coords hafen.gob.pos returns)
```

- The namespace is **always present**; the **verbs are gated** (throw until granted). `enabled()` never
  throws, so an addon can feature-detect (`if hafen.act.enabled() then …`) without a `pcall`, and a
  not-granted call gives a clear, guiding error rather than a mysterious `nil`.
- `moveTo` validates its args (`x`, `y` must be numbers) *after* the permission check.

### `moveTo` — the click-macro, faithfully encoded

`moveTo(x, y)` sends **exactly** what a left-click on that ground spot sends — the `MapView` `"click"`:

```java
view.wdgmsg("click", pc, new Coord2d(x, y).floor(OCache.posres), 1, 0);
```

- **`pc` (screen coord)** = the **current mouse position** (`view.ui.mc`), a *dummy*. This is the crux of
  a *programmatic* move: we already know the world destination, so we don't hit-test the screen. The
  engine itself does this — **`MiniMap.mvclick`** (clicking the minimap to walk) passes the current mouse
  coord as `pc` and the real destination as the world arg
  ([MiniMap.java:1218](../../src/haven/MiniMap.java)). So an **off-screen destination is fine** — the
  server uses the world coord.
- **world coord** = `Coord2d(x, y).floor(OCache.posres)` (`posres = 11/1024` world-units per server unit —
  1 tile = 11 world = 1024 server units). `x, y` are the **same world coordinates `hafen.gob.pos`
  returns** (one coordinate space across the API).
- **button `1`** = walk; **mods `0`** = no modifier.

Sent **from the `MapView`** (the widget the server expects `"click"` from). Runs on the UI thread (addon
callback / REPL); a 2d `"click"` action-hook *does* see it (it is a real action), and the 2d re-entrancy
guard prevents a hook-issued `moveTo` from looping.

## Zero `haven` core edit

Pure engine — only `AddonManager.java` (the permission + facade + verb) and `Manifest.java` (the new
`permissions` array + `usesActions()`). Every backing is public: `Widget.wdgmsg`, `UI.mc`, `Coord2d`,
`OCache.posres`, `Config.Variable`.

## The `hello` example (`addons/hello/main.lua`) — declares the permission, walks on demand

- **`manifest.json`** now declares `"permissions": ["actions"]` — hello is a write addon.
- **`readActions(tag)`** logs whether the permission is granted at the `[now]`/`[+3s]` login passes via
  `hafen.act.enabled()` — **read-only**; `hello` never moves your character on its own.
- **`:hello walk`** is a **deliberate, opt-in** `moveTo` demo: it no-ops with a hint while not granted,
  and otherwise walks you `~2 tiles south` of your current `hafen.gob.pos("player")`.

One login re-checks every prior slice **and** this one. Version **v0.32.0**.

## How to test in-game

**Java changed → full rebuild + restart is required** (the JVM does not hot-reload classes). Because the
runtime toggle (the panel checkbox) is the **next** slice (4b), grant the master switch for now via the
config file:

1. **Not granted (default).** Build & run:

   ```bash
   ant run
   ```

   At login the harness logs `[hello] [now] actions: write-permission not granted …` (the master switch
   defaults off). Confirm the gate is closed:

   ```
   :hello walk                         -- "write-actions permission not granted …" (no movement)
   :lua hafen.act.enabled()            -- false
   :lua hafen.act.moveTo(0, 0)         -- errors: "the global write-actions permission is OFF …"
   ```

2. **Grant the master switch.** Add this line to **`bin/haven-config.properties`** and restart (`ant run`):

   ```
   addons.actions.enabled=true
   ```

   Now `hafen.act.enabled()` → `true` (hello declares the permission **and** the switch is on), and the
   login log shows `write-permission GRANTED`.

3. **Run the click-macro:**

   ```
   :hello walk                         -- walks you ~2 tiles south (watch the character move)
   :lua local p = hafen.gob.pos("player"); hafen.act.moveTo(p.x + 33, p.y)   -- ~3 tiles east
   ```

4. **The declaration half.** Prove that the switch alone isn't enough: temporarily remove
   `"permissions": ["actions"]` from `addons/hello/manifest.json`, `:reload`, and run `:hello walk` — it
   now errors with **"this addon did not declare the actions permission …"** even though the master
   switch is on. Restore the line and `:reload`.

> **Temporary:** editing `haven-config.properties` is the stop-gap until slice **4b** adds the AddOns-panel
> **"Allow addon actions (writes)"** checkbox (with a notice that addons will be able to act on your
> behalf) — the real runtime control. The old `:addons actions on|off` console command has been **removed**.

## Files

- `src/io/brodgar/addon/AddonManager.java` — the permission (`CFG_ACTIONS` config flag,
  `actionsEnabled`/`actionsGranted`/`requireActions(owner, …)`); the `hafen.act` facade
  (`enabled` + `moveTo`, passing `owner`); helpers `moveClickCoord` (testable coord math) + `actMoveTo`
  (the `wdgmsg` send); removed the `:addons actions` console branch; one new import (`haven.Config`).
- `src/io/brodgar/addon/Manifest.java` — the `permissions` array + `usesActions()`; parsed in `load()`
  (reusing `strlist`); the REPL's `internal()` owner declares it.
- `addons/hello/` — `manifest.json` declares `"permissions": ["actions"]`; `readActions` + `walkDemo` +
  the `walk` sub-command on `:hello`; **v0.32.0**.

**No `haven` core edit.**

## Threading & safety

- The permission reads/writes a client pref on the UI thread (console / addon callback); cheap and
  self-contained. `CFG_ACTIONS` is a lazy, cached `Config.Variable` (no config I/O at class-load).
- `moveTo` sends a `wdgmsg`, which queues to the session (thread-safe). It null-guards the `MapView`
  (`"no map view"` when not in the world) and `view.ui` (falls back to `Coord.z`). Args are validated.
- **Stateless** — no cached snapshot, no listener, nothing to reset across `:reload`/relog; the permission
  pref is persisted independently. Nothing to leak.
- **Server-authoritative** — the worst a granted action can do is drive a *legal* click faster /
  automatically. The permission gates *convenience/automation*, not capability; it exists so you stay in
  control of which addons act on your behalf, which is why declaring + authorizing it is deliberate.

## Limitations / deferred (later Phase-4 slices)

- **4b** — the **AddOns-panel master checkbox** (with a notice that addons will be able to act on your
  behalf) + **default-disabled** for addons that declare `"actions"` (D-027's narrowing of D-006),
  replacing the config-file stop-gap.
- **4c** — the **enable-time warning dialog** when turning on a write addon (base for the future
  "intelligent" per-category breakdown).
- **4d** — the rest of the `MapView` action verbs: `clickGob` / `useItemOn` / `place` / `select` +
  `hafen.act.raw` (they share the coord/`clickargs` encoding `moveTo` establishes).
- **4e** — `hafen.act.menu(path…)` + `hafen.act.flower(label)`.
- **4f** — item verbs: `hafen.act.item(item, verb[, n])` + the `LuaModel` item-mutating verbs.
- **4g** — the **folded-in** per-subsystem gated verbs, each behind this same permission: `hafen.speed.set`
  (A7), `hafen.craft.make` (A8), `hafen.actionbar.use` (**A3**), `hafen.kin.add/remove/rename` (A6).
- `moveTo` uses a dummy `pc` — if any on-screen use ever needs a truthful screen coord,
  `hafen.player.worldToScreen` (1c-2) could supply it; not needed for movement.
