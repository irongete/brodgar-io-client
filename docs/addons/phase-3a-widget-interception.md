# Phase 3a — Widget-creation interception (`hafen.ui.onWidgetCreate`)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL), 25/25 headless logic checks
> (descriptor construction incl. `nil` handling; `onWidgetPlaced` fast-path + field extraction + `type` lookup
> and removal; `onWidgetCreated` records only while observed + ignores a null type; `Window` caption skipped
> headless — it needs GL, covered by the direct descriptor test) + LuaJ parse of the harness under the sandbox.
> **In-game DoD pending.**
> **Design:** [specs/addons/08-widget-replacement.md](../../specs/addons/08-widget-replacement.md) (the creation
> seams + the golden rule), [specs/addons/api-reference.md](../../specs/addons/api-reference.md)
> (`hafen.ui.onWidgetCreate`/`replace`), [specs/addons/code-map.md](../../specs/addons/code-map.md)
> (`UI.NewWidget`/`AddWidget`, `Widget.types`, `GameUI.addchild`), decisions **D-009** (wrap, don't
> reimplement), **D-024** (targeting descriptor), **D-011** (invasiveness allowed).

The **first slice of Phase 3 (Widget replacement)** — the WoW "replace native UI" use case (bag addons, unit
frames). Task 3 is split into three slices because it is large and each wants its own in-game verification:

- **3a (this)** — *interception + descriptor + observe.* See every server widget the client builds, from Lua,
  with its full targeting descriptor. Observe-only: the foundation.
- **3b** — *the `model` handle.* Adopt a real widget by id, hide/show it, enumerate its items, delegate item
  actions, and lifecycle events (`onItemAdded`/`onItemRemoved`/`onDestroy`).
- **3c** — *`hafen.ui.replace` + the bags example + reload un-hide* — the DoD: replace the native inventory
  with a custom view; disabling restores the stock window.

3a establishes the **two core edits** (the creation/placement seams) and the **descriptor**, and proves them
in-game before the model/view machinery is built on top — the same incremental discipline used for the hook
levels (2c→2e) and the widget-tree read mechanism (1d-1).

## The API — `hafen.ui.onWidgetCreate`

```lua
-- Observe the server's own UI as the client builds it. fn(desc) runs for EVERY server widget as it is
-- placed into the tree, with the targeting descriptor (D-024):
local sub = hafen.ui.onWidgetCreate(function(desc)
  -- desc.id         : the server widget id (int) — the stable handle for adopt/replace (3b/3c)
  -- desc.type       : the registered server type name ("inv", "epry", "chr", "wnd", …) or nil
  -- desc.place      : the placement string the server used ("inv", "equ", "misc", …) or nil
  -- desc.caption    : a titled window's caption ("Cupboard") or nil (bare widgets have none)
  -- desc.parentType : the parent widget's class name — "GameUI" for every HUD-placed window
end)

sub:remove()   -- stop observing (also auto-removed on reload/disable)
```

| Field | Meaning |
|---|---|
| `desc.id` | server widget id (int) — the id `uimsg`/`wdgmsg` route by, and the handle 3b/3c adopt |
| `desc.type` | registered type name (`"inv"`, `"epry"`, `"chr"`, `"wnd"`, …); `nil` if built from a `Factory` directly, or created before this observer registered |
| `desc.place` | the server's placement string (`pargs[0]`); `nil` when the first placement arg is not a string (e.g. an item added at a grid `Coord`) |
| `desc.caption` | best-effort title: the widget's `cap` when it is itself a `Window` (so a cupboard reports its caption); `nil` for a bare widget the engine wraps in a titled window (e.g. `Inventory` — identify those by `type`/`place`) |
| `desc.parentType` | parent widget's class simple name; **`"GameUI"` for every HUD-placed window** |

Examples: the main inventory is `{type="inv", place="inv", parentType="GameUI"}`; a cupboard is
`{type="wnd", place="misc", caption="Cupboard", parentType="GameUI"}`. This is exactly what D-024 wants so an
addon can decide **per instance** which window it is (main inventory vs a container).

Returns a **handle** with `:remove()`. The observer is **bridge-owned** (P2) — `:reload` or disabling the addon
removes it automatically. **Register any time** (no live target needed) — the **file body** is ideal, because it
also catches the burst of windows the server creates at login (an observer registered in `OnEnterWorld` would
miss those, as they may already exist).

> **This slice is observe-only.** `fn`'s return value is **ignored**. Adopting the real widget as a hidden
> **model** and drawing a custom **view** over it (the "wrap, don't reimplement" pattern, D-009) is 3b/3c.

## How it works — the two creation seams

Every server-created widget flows through two `UI` command steps (both on a Loader thread, under
`synchronized(ui)`):

1. [`UI.NewWidget.run`](../../src/haven/UI.java) — resolves the type via `Widget.gettype3(typenm)`, constructs
   the widget, and `bind(wdg, id)`s it to the server id.
2. [`UI.AddWidget.run`](../../src/haven/UI.java) — `pwdg.addchild(wdg, pargs)` places it into its parent (e.g.
   [`GameUI.addchild`](../../src/haven/GameUI.java), the `place`-string switch that routes inventory/equipment/
   character-sheet/… into the HUD).

The **descriptor needs both**: `type` is only known at creation (the widget instance does not carry its
registered type string); `place`/`parentType`/`caption` only exist after placement. So 3a uses **two one-line
`// addon:` edits**, both in `UI.java` (co-located with the 1d-1 read tap / 2d action hook / 2e-1 message hook):

- `NewWidget.run` → `AddonManager.onWidgetCreated(id, typenm)` records `id → typenm` (only while an observer is
  registered; only the in-flight set — the matching placement removes it).
- `AddWidget.run` (right after `pwdg.addchild`) → `AddonManager.onWidgetPlaced(id, wdg, pwdg, pargs)` builds the
  full descriptor and fires each observer.

Firing at **placement** (not pure creation) is deliberate: it is the first moment the complete descriptor
exists. (The api-reference's indicative "`UI.NewWidget` hook" backing note is refined here — placement is where
D-024's `place`/`parentType` become available.)

[`LuaWidgetObserver`](../../src/io/brodgar/addon/LuaWidgetObserver.java) is the Java half: it builds the `desc`
table (a `null` field is left **absent** → Lua `nil`, so the addon tests it idiomatically) and calls the
addon's `fn` through **`AddonManager.callLua`** (watchdog-armed, error-isolated, CPU-accounted — like every
other Lua entry).

### Why seam B (adopt-after-create), not the factory override

Spec 08 offers two strategies: **(A)** override the entry in the package-private `Widget.types` map so the
server's request builds *our* class, or **(B)** let the widget be created normally and adopt it after. 3a (and
3b's wrap model) use **(B)** — it needs no mid-flight tampering with the type registry, dodges the audit's
threading caveat (a factory `create()` runs off the UI monitor), and is the spec's recommended default for
HUD-placed windows. The factory-override seam (A) — needed only for pre-empting resource-published widget types
— is deferred.

### Threading, ownership, teardown

- `onWidgetPlaced` runs inside `AddWidget.run`'s `synchronized(ui)` block — on a Loader thread, but under the
  same monitor the tick and draw hold — so observer Lua **never races other Lua** (the same discipline as an L3
  message hook; no `holdsLock` guard needed). Keep handlers light: they run inline with widget placement.
- A **near-zero fast path**: `onWidgetPlaced` returns immediately when no observer is registered anywhere, so an
  unobserving client pays only an `isEmpty()` check per placement (every widget passes here). `onWidgetCreated`
  likewise records nothing unless an observer exists, so `widgetTypes` stays empty when the feature is unused.
- Observers are a **flat global list** (an observer watches *every* creation — there is no per-target keying,
  unlike the hook levels' per-name maps), with owned copies on each [`Addon`](../../src/io/brodgar/addon/Addon.java)
  (`widgetObservers`, copy-on-write, like `hooks`/`actionHooks`/`messageHooks`). `teardownWidgetObservers` marks
  each dead and drops it from the global list (P2) — like an action/message hook, there is no widget to deafen.
- **Session-scoped:** `widgetTypes` is cleared in `init` (widget ids are per-session), and the observer list is
  emptied by tearing down every addon on session rebind.

## Files

**Core edits (`haven`, both `// addon:`, D-011):**
- **`UI.java`** — one line in `NewWidget.run` (`onWidgetCreated`) + one in `AddWidget.run` (`onWidgetPlaced`).
  This is the **fourth `UI.java` edit region** (after 1d-1's read tap, 2d's action-hook split, and 2e-1's
  message hook). No other `haven` file changes.

**Engine (`io.brodgar.addon`, all package-scoped):**
- **`LuaWidgetObserver`** *(new)* — builds the `desc` and forwards a placed widget to a Lua observer.
- **`Addon`** — one owned-resource list: `widgetObservers` (like `hooks`/overlays, P2).
- **`AddonManager`** — the `hafen.ui.onWidgetCreate` facade + `onWidgetCreated`/`onWidgetPlaced` (the seam
  targets, building the descriptor) + `newWidgetObserver`/`removeWidgetObserver`/`teardownWidgetObservers`
  + `widgetTypes`/`widgetObservers` fields (cleared/torn down per session); `teardown` drops the observers.

The engine package now holds `{AddonManager, Addon, Manifest, Json, AddonRoot, Sandbox, LuaWidget, LuaGOut,
LuaGobOverlay, LuaInputHook, LuaActionHook, LuaMarshal, LuaMessageHook, LuaKeyBind, LuaWidgetObserver}` +
`io.brodgar.addon.ui.AddonPanel`.

## Verification

- **Compile:** `ant hafen-client` → BUILD SUCCESSFUL.
- **Headless (25/25):** `LuaWidgetObserver.invoke` builds the right descriptor for full / captioned / id-only
  inputs (absent fields are Lua `nil`); `onWidgetPlaced` no-fires and keeps the type record on the fast path,
  and — with an observer registered — fires with `id`/`type` (from the map)/`place` (`pargs[0]`)/`parentType`
  (parent class)/`caption` (nil for a bare widget) and **removes** the consumed type record; a non-string
  `pargs[0]` yields `place=nil`; `onWidgetCreated` records only while observed and ignores a null type. The
  `Window`-caption branch is skipped headless (the class needs GL to load), but the caption *logic* is covered
  by the direct `invoke` test. Plus the extended `hello/main.lua` parses under `Sandbox.create()`.
- **In-game (DoD) — pending.** Log in with `hello` (**v0.20.0**) enabled and:
  1. At login the console logs a few `3a: widget created …` lines for the HUD windows the server builds
     (inventory, equipment, character sheet, belt, meters, …), each with its `{id,type,place,parent,caption}`.
  2. **Open a cupboard/chest or a crafting window** → a fresh `3a: widget created … caption=<name>` line
     appears, proving live interception of a user-opened, titled server window with its caption.
  3. `:reload` (or disabling `hello`) removes the observer cleanly — after a reload, opening another window logs
     again (the observer re-registered from the file body), and a disabled `hello` logs nothing (no leak).

## Known gaps / deferred

- **Observe-only.** Adopting the real widget as a hidden **model** (`hide`/`show`/`items`/item actions/
  `onItemAdded`/`onItemRemoved`/`onDestroy`) is **3b**; the high-level `hafen.ui.replace(type, opts, fn)` sugar
  + the **bags** example + reload **un-hide** (the full DoD) is **3c**.
- **HUD-level focus.** The observer fires for *every* placement (items into inventories included); the `hello`
  demo filters to `parentType=="GameUI"` + titled windows to stay readable. A future convenience could pre-filter
  in the facade, but the flat firing keeps the low-level surface honest ("observe any creation", per spec 08).
- **Caption is best-effort.** It reads a `Window`'s own `cap`; a bare widget the engine wraps in a titled window
  (like `Inventory`) reports `caption=nil` — identify those by `type`/`place`. Reaching into the engine wrapper
  for a synthetic caption was intentionally avoided.
- **Factory-override seam (A) not exposed** — resource-published widget *types* (names containing `/`, loaded
  via `Resource.remote()`) bypass `Widget.types`, so they cannot be pre-empted; the adopt-after-create model (B)
  covers them regardless. `hafen.hook.method` (L4, full method replacement via a hookable subclass) is Phase 3+.
- **The map window is not a server widget** (audit B3) — `MapWnd`/`MapFile` is client-side; it is not
  reskinnable via this seam. A map overhaul is its own subsystem (A1).
