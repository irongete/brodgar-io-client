# Lifecycle & Reload UI

> **Status:** 🟡 Draft · **Spec:** AddOns
> **Related:** [04-engine.md](04-engine.md), [08-widget-replacement.md](08-widget-replacement.md), [10-options-panel.md](10-options-panel.md), [DECISIONS.md](../DECISIONS.md) (D-005, D-006)

## Addon lifecycle

```
discover → (enabled?) → create env → run files → OnLoad → [enter world] → OnEnterWorld
                                                                              ↕ (running)
                                                          OnUpdate / events / timers
   Reload UI / disable → OnDisable → teardown (release owned resources) → drop env
```

Lifecycle events delivered to addons (see [09-events-catalog.md](09-events-catalog.md)):

- **`OnLoad`** — after the addon's Lua files have executed. Register handlers, build state.
- **`OnEnterWorld`** — the world/HUD is ready (`GameUI` exists, player gob known). Fired on
  first world entry, and **immediately at Reload** if already in-world (so addons initialize as
  if freshly logged in — the WoW `PLAYER_LOGIN` analog).
- **`OnUpdate(dt)`** — every tick while running.
- **`OnDisable`** — the addon is being torn down (reload, disable, or shutdown). Last chance to
  flush state; the engine then releases owned resources regardless.

Saved variables are restored just before `OnLoad` reads them and flushed at `OnDisable`
(and throttled during running). See "Saved variables" below.

## Reload UI (the WoW dev loop)

**What it is** ([D-005](../decisions/lifecycle.md)): reload the **addon layer only** — all Lua state and every
resource addons created — **from disk**, without relogging and without touching the native/
server-driven Java UI. This delivers the WoW intent: edit a `.lua`, Reload, see the change, no
relog.

**What it is NOT.** It does not rebuild server-driven widgets (inventory, menus, HUD). In Hafen
those exist only because the server sent `RMSG_NEWWDG`/`RMSG_ADDWDG`; the client cannot recreate
them without the server resending (a reconnect). A full rebuild ≈ a relog and is out of scope.

**Triggers:** the `:reload` console command, a **Reload UI** button in the AddOns panel
([10-options-panel.md](10-options-panel.md)), and (optional) a keybinding. Also implicitly after
enable/disable changes ([D-006](../decisions/lifecycle.md), WoW model: changes apply on reload).

### Reload sequence

Runs entirely on the **UI thread under `synchronized(ui)`** (it destroys/creates widgets).
Triggered from the tick pump or a console command that defers onto the UI thread.

1. **Flush** saved variables of all loaded addons to disk.
2. **Fire `OnDisable`** to each loaded addon (reverse load order).
3. **Teardown** each addon — release every owned resource (see below).
4. **Re-scan** `addons/`, re-read manifests and the enabled set, recompute load order.
5. **Re-create** environments; **re-run** each enabled addon's files from disk.
6. **Fire `OnLoad`**, then **`OnEnterWorld`** (we are already in-world).
7. **Restore** saved variables into each addon.

Per-addon steps are wrapped in try/catch so one addon's failure does not abort the whole reload.

### Partial / live variants (nice-to-have, beyond WoW)

Because teardown is already per-addon, these fall out cheaply:
- **`:reload <id>`** — reload a single addon.
- **Live disable** — tear down one addon immediately (no full reload).
- **Live enable** — run one addon immediately.

v1 follows the WoW model ([D-006](../decisions/lifecycle.md)); live variants are enhancements once teardown is
proven robust.

## Ownership & teardown (the correctness core)

Per [P2](01-architecture.md), the bridge **owns and tracks every resource an addon creates**, in
a per-addon **owned-resource registry**. Teardown walks it and releases each kind:

| Resource kind | Created via | Released by |
|---|---|---|
| Widgets / windows | `hafen.ui.window`/`widget` → `LuaWindow`/`LuaWidget` added to the tree | `remove()`/`destroy()` for **synchronous** teardown. ⚠ `Window.reqdestroy()` plays an **async hide animation** ([Window.java:593](src/haven/Window.java:593), sets `animst="dest"`) — a `LuaWindow` torn down that way survives the frame, so reload must call `destroy()`/`remove()` directly |
| HUD overlays | `hafen.ui.overlay` | removed from the engine's overlay list |
| World/gob overlays | gob-attached `GAttrib` view | detached from the gob + disposed |
| Widget-replacement views | `hafen.ui.replace` | destroy view + **un-hide the native model** ([08](08-widget-replacement.md)) |
| Gob spawn/despawn observers | [`OCache.callback`](src/haven/OCache.java:75) | [`OCache.uncallback`](src/haven/OCache.java:79) |
| Event subscriptions | `hafen.events.on` | removed from the event bus (engine-owned) |
| Timers | `hafen.timer` | cleared (engine-owned) |
| Keybinding handlers | `hafen.key.bind` | handler mapping cleared (the `KeyBinding` singleton persists by id — like WoW keybinds — only the handler resets) |
| Lua environment | engine | dropped for GC |

**Not touched on reload:**
- **Console commands** — a fixed engine-lifetime set ([04-engine.md](04-engine.md)); reload
  routes through them, never re-registers (`Console` has no unregister anyway).
- **`UI.drawafter`** — verified to be **one-shot** (the list is cleared every frame in
  [`UI.draw`](src/haven/UI.java:391)). So overlays are **not** persistent registrations; the
  engine keeps its own overlay list and paints it from the addon-root widget's `draw`. Disabling
  an addon simply removes its overlays from that list — nothing to unregister.

### Why "everything through the bridge" is mandatory

If an addon could obtain a raw `haven.*` handle and stash a widget/callback the bridge didn't
record, reload would leak it (or worse, leave a dangling handler firing into a dead Lua env).
[P1](01-architecture.md) (no direct `haven.*`) + [P2](01-architecture.md) (bridge owns
everything) together guarantee complete teardown. This is a hard design constraint, not a
convenience.

### Failure modes & mitigations

- **Escaped resource** → leak. Mitigation: the bridge is the sole factory; Lua never gets raw
  handles.
- **Teardown throws** → partial teardown. Mitigation: per-resource and per-addon try/catch;
  continue releasing the rest; log.
- **File edit-lock (Windows)** → can't edit `.lua` while running. Mitigation: read each file
  fully and close it immediately; LuaJ compiles from a string.
- **Server destroys a replaced widget mid-session** (`RMSG_DSTWDG`) → the addon's view must die
  with its model. Mitigation: the replacement plumbing hooks the model's destroy and tears down
  the view ([08-widget-replacement.md](08-widget-replacement.md)).

## Saved variables

- **Scope:** per `<genus>_<char>` ([D-002](../decisions/filesystem-build.md)); files at
  `savedata/<genus>_<char>/<addon>.json`. Account-wide store is open ([Q-002](../DECISIONS.md)).
- **What is saved:** the global Lua tables named in the manifest's `saved_variables`, serialized
  to JSON ([Q-006](../DECISIONS.md) for the JSON facility).
- **When:** restored before `OnLoad`/`OnEnterWorld`; flushed at `OnDisable` and on client
  shutdown; throttled auto-save while running (mirrors how `GameUI` throttles window-position
  saves).
- **Access:** addons read/write via `hafen.store` (a per-addon table proxy) — see
  [06-lua-api.md](06-lua-api.md).
