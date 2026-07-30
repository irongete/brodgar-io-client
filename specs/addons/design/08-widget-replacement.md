# Widget Replacement (replacing native/server UI)

> **Status:** 🟡 Draft · **Spec:** AddOns · **Phase:** 3
> **Related:** [01-architecture.md](01-architecture.md) (P6), [07-ui-and-drawing.md](07-ui-and-drawing.md), [05-lifecycle-and-reload.md](05-lifecycle-and-reload.md), [DECISIONS.md](../DECISIONS.md) (D-009), [Q-008](../DECISIONS.md)

The WoW use case: addons that **replace native UI** — bag addons, auction-house addons, unit
frames. In Hafen the analog is replacing server-driven windows: inventory/bags, equipment, the
character sheet, crafting, containers (cupboards, stockpiles), the action bar ("belt"), etc.

**Answer: yes, any server widget creation can be intercepted, and non-invasively.** The key is
to **wrap, not reimplement** ([D-009](../decisions/widgets-ui.md)).

## How server widgets are created (verified)

Every server-created widget flows through [`UI.NewWidget.run()`](src/haven/UI.java:433):

```java
public void run() {
    if((type == null) && ((type = Widget.gettype3(typenm)) == null))  // (A) factory resolution
        throw(...);
    if(wdg == null)
        wdg = type.create(UI.this, cargs);                            //     construction
    synchronized(UI.this) {
        wdg.attach(UI.this);
        bind(wdg, id);                                                //     id binding
    }
}
```
and is placed via [`AddWidget.run()`](src/haven/UI.java:470): `pwdg.addchild(wdg, pargs)` — e.g.
[`GameUI.addchild`](src/haven/GameUI.java:910), the switch that places inventory/equipment/etc.
into the HUD.

Two verified facts that make interception clean:

1. **Type is resolved per-creation at `run()` time** via
   [`Widget.gettype3(typenm)`](src/haven/Widget.java:169), which reads the **package-private,
   mutable** map [`Widget.types`](src/haven/Widget.java:51). Overriding a map entry before
   creation makes the server's request for that type build **our** widget.
2. **`bind(wdg, id)` binds whatever the factory returns** to the server id. That widget then
   receives all subsequent `uimsg` and its `wdgmsg` route back correctly — **delivery is by id,
   not by tree position or visibility.**

## Interception seams

- **(A) Factory registry** — [`Widget.types`](src/haven/Widget.java:51) / `gettype3`. Substitute
  what class is built for a server type string (`"inv"`, `"epry"`, `"chr"`, `"scm"`, `"wnd"`,
  …). Coarse: keyed by **type only** (the factory sees just `(ui, cargs)`, no context).
  **Zero core edits** — a `haven`-package helper can read/replace entries (the same
  package-private trick `SpeakerIcon`/`VoiceTarget` already use), or we add a one-line public
  `Widget.registerType(name, factory)`.
  ⚠ **Coverage limit (audit):** `gettype3` consults `types` only for **builtin** names *without* `/`.
  Type names **containing `/`** load a **resource-published** `Factory` via `Resource.remote()`
  ([Widget.java:170](src/haven/Widget.java:170)) — those bypass `types`, so seam (A) cannot
  intercept resource-widget types (e.g. `DynresWindow` content).
- **(B) Placement** — [`GameUI.addchild`](src/haven/GameUI.java:910) (and other containers'
  `addchild`). Gives the **context** the factory lacks (parent, place-string) to distinguish the
  main inventory from a cupboard. **One line** at the top of `GameUI.addchild` lets the engine
  intercept HUD placement.

Expose **both** so addons can target by type (A) or by context (B).

⚠ **Threading caveat (audit).** In `UI.NewWidget.run`, `type.create(ui, cargs)` runs **before**
entering `synchronized(UI.this)` ([UI.java:437](src/haven/UI.java:437)) — i.e. on a **Loader thread
without the UI monitor**. An override factory/ctor (seam A) must not touch the widget tree or shared
engine state unguarded during construction; do tree work in `added()`/`attached()` (which run under
the lock) or marshal onto the UI thread.

⚠ **The map is not a server widget (audit B3).** `MapWnd`/`MapFile` is a **client-side** subsystem
(on-disk map DB), not a thin server-created window — the wrap-and-delegate model does **not** apply
to it. A map overhaul is its own subsystem ([coverage-gaps.md](../ROADMAP.md) A1/B3).

## The golden rule: wrap, don't reimplement

> Keep the real server-bound widget as a hidden **model**; present a custom **view**; delegate
> interactions to the real widget.

Why it works (verified):
- The real widget stays **bound to the server id**, so it keeps receiving `uimsg` (item adds via
  `addchild`, `"num"`, `"tt"`, …) and emitting `wdgmsg` (`"take"`, `"drop"`, `"transfer"`)
  **without us reimplementing any protocol.**
- **Visibility does not affect protocol delivery:** `ui.uimsg(id, …)` and `AddWidget.run` locate
  the target by the **id map**, independent of visibility. A `hide()`-ed widget (not drawn, not
  interactive) **still processes** item additions and messages → it is a perfect headless model.
- The view reads the model's state (e.g. an `Inventory`'s `GItem→WItem` map) and draws its own
  layout; clicks on the view translate into `gitem.wdgmsg("transfer"/"take"/…)` on the **real**
  items → reuses the protocol verbatim.

This mirrors how a WoW bag addon (e.g. Bagnon) re-presents the same item buttons without touching
the data model.

## Two strategies

| | (A) Factory override | (B) Adopt-after-create |
|---|---|---|
| **How** | Save the original factory; install ours in `Widget.types`. Ours creates the real widget (delegating `uimsg`/`addchild`) and notifies the addon. | Let it be created normally; a hook in `GameUI.addchild` hands the real widget to the addon, which hides it and builds a view. |
| **Coverage** | Any type, incl. container windows | HUD-placed widgets |
| **Context** | `(ui, cargs)` only → coarse (by type) | Knows parent/place-string → can target main inv vs a container |
| **Core edits** | **Zero** (haven-package helper touches the package-private map) | **One line** in `GameUI.addchild` |

Full behavioral **replacement** (reimplementing the protocol so our widget is bound to the id and
handles every `uimsg`/`wdgmsg` itself) is possible but **fragile** and content/version-dependent;
reserve it for trivial widgets. Default to wrapping.

## Proposed Lua API

```lua
hafen.ui.replace("inv", { context = "main" }, function(model)
  local win = hafen.ui.window{ title = "Bags", size = {260, 320} }
  model:hide()                                   -- native inventory becomes the hidden model
  model:onItemAdded(function(item) win:refresh() end)
  model:onItemRemoved(function(item) win:refresh() end)
  model:onDestroy(function() win:destroy() end)  -- server destroyed it → view dies too
  win.onDraw = function(g)
    for _, item in ipairs(model:items()) do      -- reads the real GItem→WItem map
      -- draw a cell; on click: item:transfer() / item:take() (delegates to the real GItem)
    end
  end
  return win                                     -- the addon's view; bridge-owned
end, )

-- Lower level: observe/intercept any creation
hafen.ui.onWidgetCreate(function(desc)
  -- desc = { id=, type=, context=, caption=, ... }; return a handler or nil
end)
```

### The `model` handle (proposed methods)
```lua
model:items()            -- array of item models (GItem→WItem snapshot/handles)
model:onItemAdded(fn) / model:onItemRemoved(fn) / model:onDestroy(fn)
model:hide() / model:show()
model:raw()              -- (advanced) escape hatch to the underlying widget wrapper
```
An **item model** exposes `name`, `res`, `num`, `meter`, `quality?`, and delegated actions
`take()`, `drop([n])`, `transfer([n])`, `use()` (which call `GItem.wdgmsg(...)`).

Exact handle shape is TBD; it is the natural next design step ([Q-008](../DECISIONS.md) for
targeting).

## Reload / teardown behavior

The `replace` registration and the created view are **addon-owned**. On disable/reload
([05-lifecycle-and-reload.md](05-lifecycle-and-reload.md)):
- destroy the view widget(s),
- **un-hide the native model** (the original UI reappears),
- unregister the type override / placement hook for that addon.

So disabling a bag addon cleanly restores the stock inventory window.

## Honest limits

1. **Less-semantic identification.** Server widgets are typed by string (`"inv"`, `"wnd"`), not
   by friendly frame names. Distinguishing "the cupboard" from "the backpack" needs heuristics
   (placement context, window caption, contained widget types). See [Q-008](../DECISIONS.md).
2. **Content-defined data.** Item quality and some tooltip fields come from server resource code;
   structure (items, counts) is stable, certain attributes are best-effort.
3. **3D world view is out of scope** ([N2](00-vision-scope.md)). `MapView` is overlaid, not
   replaced.
4. **Async arrival.** Item children arrive over time via `addchild` (deferred through the
   `CommandQueue`), so the view is **event-driven** (`onItemAdded`), like the real `Inventory`.
5. **Lifecycle coupling.** When the server destroys the widget (`RMSG_DSTWDG`), the view must die
   with the model (`onDestroy`).

## First target (proof of the mechanism)

An **inventory/bag overhaul** (the "bags" example). It exercises both seams, the model/view
pattern, `wdgmsg` delegation, async item arrival, and reload teardown — the "hello world" of UI
interception and one of the most useful addons.
