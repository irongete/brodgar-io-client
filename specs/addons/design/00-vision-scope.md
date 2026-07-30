# Vision & Scope

> **Status:** 🟡 Draft · **Spec:** AddOns
> **Related:** [01-architecture.md](01-architecture.md), [12-security-and-permissions.md](12-security-and-permissions.md), [DECISIONS.md](../DECISIONS.md)

## Vision

Make `brodgar-io-client` **fully moddable by installing and enabling Lua addons**, the way
World of Warcraft is. A user should be able to drop a folder into `addons/`, enable it from an
in-game **AddOns** options panel, and have it change the client — add windows, draw overlays,
react to game events, and even **replace native UI** (e.g. a custom bag/inventory) — without
recompiling the Java client.

The historical Haven & Hearth approach is to fork the Java client and hand-edit it for every
feature. We want the opposite: a **stable Lua API** (a facade) that absorbs client internals,
so that customization happens in addons and the Java core stays close to upstream.

## Why this is tractable in Hafen

Two properties of the Hafen client make a broad API reachable incrementally (see
[01-architecture.md](01-architecture.md)):

- **One universal action channel:** every player action is a
  [`Widget.wdgmsg`](src/haven/Widget.java:737) to the server. One primitive covers the totality
  of actions.
- **One universal state root:** all world state hangs off [`Glob`](src/haven/Glob.java:34)
  (objects, terrain, party, character attributes), reachable via `ui.sess.glob`.

So the API does not need to enumerate every action/dato up front. It exposes the generic
primitives plus ergonomic wrappers and grows coverage **without touching the core**.

## Goals

- **G1 — Lua addons.** Addons are folders of Lua (+ assets) under a client-local `addons/`
  directory, each self-contained. See [03-addon-format.md](03-addon-format.md).
- **G2 — Read game state.** Objects/gobs, terrain, player, inventory & items, character
  attributes, party. See [06-lua-api.md](06-lua-api.md).
- **G3 — Create UI.** Custom windows/widgets, HUD overlays, and world-space overlays over gobs.
  See [07-ui-and-drawing.md](07-ui-and-drawing.md).
- **G4 — Events.** A synthesized event bus (`OnUpdate`, gob spawn/despawn, chat, inventory
  change, …). See [09-events-catalog.md](09-events-catalog.md).
- **G5 — Replace native UI.** Intercept server-driven widget creation and substitute a custom
  view (bags, equipment, character sheet, crafting, containers). See
  [08-widget-replacement.md](08-widget-replacement.md).
- **G6 — Perform actions (gated).** Move, click gobs, use items, invoke menu/flower actions.
  Behind an explicit opt-in because they act on your behalf (a declared per-addon permission). See
  [12-security-and-permissions.md](12-security-and-permissions.md).
- **G7 — Options panel.** An in-game **AddOns** button in Options to enable/disable addons and
  **Reload UI**, WoW-style. See [10-options-panel.md](10-options-panel.md).
- **G8 — Reload UI.** Reload the entire addon layer from disk without relogging, for a fast
  edit→reload dev loop. See [05-lifecycle-and-reload.md](05-lifecycle-and-reload.md).
- **G9 — Saved data.** Per-character persistent storage as JSON under a client-local
  `savedata/` directory. See [02-filesystem-and-build.md](02-filesystem-and-build.md).
- **G10 — Centralized, not necessarily minimal.** The engine + API live in
  `src/io/brodgar/addon/`; edits to the `haven` core are kept **centralized and tagged**
  (`// addon:`) for clean upstream merges. Per [D-011](../decisions/architecture-api.md), invasiveness is **no longer
  a hard constraint** — the system may add first-class hook points / hookable subclasses where
  they enable materially better features (see [13-hooks-and-interception.md](13-hooks-and-interception.md)).
  The invasiveness ledger ([11-core-hooks.md](11-core-hooks.md)) records the edits.

## Non-goals (for now)

- **N1 — No server-side changes.** The client is server-authoritative. Addons can only do what
  a player could do; no teleport, no seeing beyond what the server streams, no client-only
  cheats. This is a hard boundary, not a phase.
- **N2 — No replacement of the 3D world view.** [`MapView`](src/haven/MapView.java) is a GL
  view, not a re-skinnable 2D widget. Addons overlay on it; they do not replace it.
- **N3 — No cross-language addons.** Lua only (via LuaJ). No JS/Groovy/native.
- **N4 — No addon marketplace / auto-updater** in the initial scope. Addons are installed by
  copying folders. An in-game browser is a possible later enhancement.
- **N5 — No pre-login addon management** initially. The AddOns panel is in-game only
  ([D-004](../decisions/lifecycle.md)).
- **N6 — Reload does not rebuild the native/server UI.** "Reload UI" reloads only the addon
  layer ([D-005](../decisions/lifecycle.md)); a full rebuild of server-driven widgets is effectively a
  relog and is out of scope.

## Phased delivery plan

The API surface grows incrementally. Each phase is independently useful and reviewable.

- **Phase 0 — Spike.** LuaJ jar + `io.brodgar.addon` package + `AddonManager` + tick pump +
  a `:lua` console command that evals a string + read `player.pos()`. Proves Lua runs on the
  UI thread with safe state access.
- **Phase 1 — Read + Overlays (fully safe tier).** `hafen.world/player/items/char`,
  `hafen.ui.overlay`, `hafen.log`, timers, saved-vars, manifest + loader, the AddOns options
  panel with enable/disable and Reload UI. Example addons: gob radar overlay, item-quality
  display.
- **Phase 2 — Windows + Input + Events.** `LuaWidget`/`LuaWindow`, `GOut` wrapper, hotkeys,
  the event bus with its core one-liner hooks.
- **Phase 3 — Widget replacement.** Intercept server widget creation; wrap-and-reskin native
  windows (first target: inventory/bags).
- **Phase 4 — Actions (opt-in).** `hafen.act.*` behind a declared per-addon permission + opt-in.
- **Phase 5 — Hardening & ecosystem.** Sandbox tightening, watchdog tuning, dependency
  resolution, packaging, docs, optional in-game addon browser.

> Phase order is a plan, not a contract. Phases 1–3 are the "modding" tiers (low risk); Phase 4
> is the automation tier and is gated separately.

## Success criteria

- A non-trivial addon (e.g. a bag overhaul) can be built, enabled, edited, and **Reload-UI'd**
  live without relogging, and cleanly disabled with the native UI restored.
- Enabling/disabling and reloading leak **no** widgets, callbacks, timers, or overlays
  ([05-lifecycle-and-reload.md](05-lifecycle-and-reload.md)).
- Merging upstream `dolda2000/hafen-client` does not break addons, because addons bind to the
  Lua facade, not to `haven.*` internals ([01-architecture.md](01-architecture.md), principle P1).
