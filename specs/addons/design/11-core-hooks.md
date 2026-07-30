# Core Hooks — Invasiveness Ledger

> **Status:** 🟡 Draft · **Spec:** AddOns
> **Related:** [01-architecture.md](01-architecture.md), [04-engine.md](04-engine.md), [09-events-catalog.md](09-events-catalog.md), [13-hooks-and-interception.md](13-hooks-and-interception.md)

> **Note ([D-011](../decisions/architecture-api.md)).** Minimal invasiveness is no longer a hard constraint — the
> system may add first-class hook points / hookable subclasses where they enable better features
> ([13-hooks-and-interception.md](13-hooks-and-interception.md)). This ledger is now a **record**
> of the edits we make (kept centralized and `// addon:`-tagged for clean upstream merges), not a
> budget to stay under.

Exact inventory of edits to the `haven` core, so we can keep the footprint minimal and centralized
(the voice integration touched only 3 existing `haven` files + 2 new `haven` files + 1
`io.brodgar` file + a manifest line). Goal: **match or beat that**.

Two categories:
- **Zero-edit seams** — public APIs / package-private access from a `haven`-package helper (the
  `SpeakerIcon`/`VoiceTarget` trick). No change to existing `haven` files.
- **One-liners** — a single `Addons.xxx(...)` call added at a choke point in an existing file.

## Zero-edit seams (no existing `haven` file changes)

| Capability | Mechanism | Reference |
|---|---|---|
| Engine tick (`OnUpdate`) | Invisible addon-root widget on `ui.root`; its `tick` runs from the `UI.tick` broadcast | [`Widget.tick`](src/haven/Widget.java:748), [`UI.tick`](src/haven/UI.java:371) |
| HUD overlays | Addon-root widget's `draw` paints the engine overlay list | [07](07-ui-and-drawing.md) |
| Custom windows/widgets | `ui.root.add(...)` / `gameui.add(...)` | [`Widget.add`](src/haven/Widget.java:250) |
| Gob spawn/despawn events | `OCache.callback` / `uncallback` | [`OCache.callback`](src/haven/OCache.java:75) |
| Register widget types (replacement A) | Write `Widget.types` from a `haven`-package helper (package-private) | [`Widget.types`](src/haven/Widget.java:51), [`gettype3`](src/haven/Widget.java:169) |
| Console commands (`:addon`,`:reload`,`:lua`) | `Console.setscmd` (fixed, engine-lifetime) | [`Console.setscmd`](src/haven/Console.java:54) |
| Hotkeys | `KeyBinding.get(...)` | [`KeyBinding`](src/haven/KeyBinding.java) |
| Read all state | `ui.sess.glob` (OCache/MCache/party/cattr) | [`Glob`](src/haven/Glob.java:34) |
| Send actions | `Widget.wdgmsg` | [`Widget.wdgmsg`](src/haven/Widget.java:737) |
| Options panel | `OptWnd.Panel` subclass + a `PButton` (additive, same as voice) | [`OptWnd`](src/haven/OptWnd.java) |
| Custom `.res` assets | classpath `/res/` or `-Dhaven.resdir` / addon resource source | [`Resource`](src/haven/Resource.java) |
| Addon dir / savedata paths | `Utils.srcpath(...).resolveSibling(...)` | [`Utils.srcpath`](src/haven/Utils.java:122) |

**Phases 0–2 can be built almost entirely on these** (plus the init hook below).

## One-liner hooks (single call in an existing file)

Each is a single `Addons.fire(...)` / `Addons.xxx(...)`, tagged with a `// addon:` comment.

| Hook | File / method | Purpose | Phase |
|---|---|---|---|
| Per-session init | [`RemoteUI.init(UI)`](src/haven/RemoteUI.java:147) | `Addons.attach(ui, sess)` — attach addon-root, start pump | 0 |
| (opt) explicit tick | [`UILoop.Frame.tick`](src/haven/UILoop.java:445) | `Addons.tick(dt)` — if not using the invisible-widget tick | 0 |
| Placement intercept | [`GameUI.addchild`](src/haven/GameUI.java:910) | `if(Addons.placeChild(this, child, args)) return;` — widget replacement by context | 3 |
| Widget-create event | [`UI.NewWidget.run`](src/haven/UI.java:433) | `Addons.onWidgetCreated(id, type, wdg)` — `WidgetCreated` event / replacement | 2–3 |
| Chat event | [`ChatUI`](src/haven/ChatUI.java) message-in | `Addons.fire("ChatMessage", …)` | 2 |
| System message event | [`RootWidget.uimsg`](src/haven/RootWidget.java) `msg`/`err` | `Addons.fire("SystemMessage", …)` | 2 |
| Inventory change event | [`Inventory.addchild`/`cdestroy`](src/haven/Inventory.java) | `Addons.fire("InventoryChanged", …)` | 2 |
| Flower menu event | [`FlowerMenu.added`](src/haven/FlowerMenu.java) | `Addons.fire("MenuOpened", …)` | 2 |
| (opt) generic wdgmsg tap | [`UI.wdgmsg`](src/haven/UI.java:665) | `Addons.fire("WidgetMessage", …)` — power users | 2+ |

## New files (all in `io.brodgar.addon`, plus minimal `haven` helpers)

- `src/io/brodgar/addon/**` — the engine, bridge, event bus, UI plumbing, store. Compiles
  automatically (whole-tree `javac`).
- `src/haven/AddonHook.java` (or similar) — **only if** package-private access is needed (e.g. to
  write `Widget.types`, or read a package-private field like `SpeakerIcon` does). Keep to the
  minimum; ideally one small helper.

## Build edits

Per [02-filesystem-and-build.md](02-filesystem-and-build.md):
1. LuaJ jar placement + one manifest `Class-Path` token ([Q-003](../DECISIONS.md)).
2. `bin` target: copy repo `addons/` → `bin/addons/`.
3. `run` target: `-Dhaven.addondir=${basedir}/addons` ([Q-004](../DECISIONS.md)).
4. (maybe) a JSON facility for saved data ([Q-006](../DECISIONS.md)).

## Footprint summary

- **Phases 0–2:** ~1 required core one-liner (init in `RemoteUI.init`) + a handful of optional
  event one-liners; everything else via zero-edit seams. New code is self-contained in
  `io.brodgar.addon`.
- **Phase 3 (replacement):** +1 line in `GameUI.addchild` and +1 in `UI.NewWidget.run` (or the
  `Widget.types` override with zero edits).
- This **meets or beats** the voice integration's footprint, honoring the "non-invasive"
  requirement ([G10](00-vision-scope.md)).

> Keeping edits to one-liners tagged with `// addon:` also keeps upstream merges clean, the same
> way the voice hooks are tagged `// brodgar voice:`.
