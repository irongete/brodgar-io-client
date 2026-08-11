# 029-widget-oop — Spec

## What & why

**One `Widget` entity instead of three.** `hafen.ui` today hands back three different objects for the
same thing — a widget: the **window/widget handle** from `hafen.ui.window{}` (`:move/:size/:show/:hide/
:pack/:destroy`), the **model handle** from `adopt`/`replace` (`:hide/:show/:visible/:raw/:items/
:onItemAdded/:onItemRemoved/:onDestroy`), and the transient **`WidgetNode`** from `root/node/at`
(`:type/:id/:children/:parent/:pos/:size/:visible/:text/:walk/:same/:at/:rootpos/:setFont`). They overlap
(`model:raw()` *is* `node:id()`; visibility is written three times) and none is complete, so real work
constantly needs two of them at once. This collapses them into **one interned entity** on the proven
`LuaMeter`/`LuaBuff` shape (userdata + per-addon metatable + weak-valued identity cache with
`IdentityHashMap` + a drained `ReferenceQueue`), with `:info()` as the one snapshot escape hatch.
**What you create and what you find become the same type.**

The consequence that matters: **reading stops being coupled to replacing.** Today the only way to read a
chest's items is `hafen.ui.adopt(id)`, which *hides the native window* — you must take a window over to
look inside it. With one entity, `:items()` answers on any container, adopted or not.

**Items decision (maintainer, 2026-08-02).** The container widget answers for its own children —
`widget:items()`, a **relation** like `:children()` — and **`hafen.items` is hard-cut** as a section:
`hafen.items.inventory()` → `hafen.ui.inventory():items()`, `equipment()` → `hafen.ui.equipment():items()`,
`hand()` → `hafen.ui.hand()`. In Hafen there is no inventory model outside the widget tree (`GameUI.maininv`
is an `Inventory` exactly like a chest's), so a separate section would preserve the player-inventory
privilege this feature removes.

Feature **B1** of the agreed run (A asset ✅ → **B1 the entity** → B2 the selector engine → C
`hafen.ui.skin` → D window chrome → E layout). It also closes the `LuaModel`/`LuaMesh` collision 028
deferred — by deletion, since the adopt handle stops existing.

## Acceptance criteria

- [ ] `hafen.ui.root()`, `node(id)`, `at(x,y)`, `window{}`, `widget{}`, `inventory()` and `replace`'s
      callback **all hand back the same type**; `:info()` answers on every one of them.
- [ ] **Interning**: two lookups of the same live widget are the **same object** —
      `hafen.ui.at(m.x,m.y) == hafen.ui.at(m.x,m.y)`, and a node kept across frames stays `==`.
      **`:same()` is hard-cut** (D-012: `==` is the identity test now).
- [ ] **Arity is the verb** on geometry (the `018` options shape): `w:pos()` / `w:size()` read,
      `w:pos(x,y)` / `w:size(w,h)` write and chain on self. `:move()` is hard-cut.
- [ ] **Owned vs borrowed is explicit**: `:destroy()`/`:pack()` work on a widget the addon created and
      raise a clear error on a native one; a geometry **write** on a native widget raises an error naming
      feature E (layout) rather than half-working.
- [ ] **`adopt` is deleted, not renamed** (maintainer, 2026-08-02): `w:hide()` on a *native* widget registers
      the restore, `w:show()` gives it back, `model:raw()` is gone (it was `:id()`), and **`replace(...)` is
      unchanged** — it stays the API you use, now handing your callback the same entity.
- [ ] **Read without adopting**: read a chest's `:items()` with its window still visible and interactive.
- [ ] `hafen.ui.inventory()/equipment()/hand()` answer, **`hafen.items` reads `nil`**, and
      `:onItemAdded/:onItemRemoved/:onDestroy` fire from the entity on any container.
- [ ] A stale entity (widget destroyed) reads `nil`/empty, `:exists()` false, and **never pins the dead
      subtree** — the D-041 no-pin rule must survive interning (profiler memory read).
- [ ] `hello` exercises the collapse once per login (an entity from each entry point, `==` interning, the
      owned/borrowed errors, a chest read with nothing hidden, `itemsGone`); `bags` and `widgetstack` both
      still work; full regression passes.

## Out of scope

- **The selector engine and `hafen.ui(selector)` callable** — feature **B2**, next. Here the existing
  lookups survive unchanged in *name*; only their return type unifies.
- The `inventory` **role** and selector-filtered widget events — B2 (D-063 applies there: a container's
  key is the engine-published widget **type**, not its localized title).
- Restyling (`skin`), layout writes on native widgets — C/D/E. New reads beyond the three surfaces.

## Context files

- `design/20-widget-introspection.md` — the `WidgetNode` contract, the transient-handle rationale
  (§Ownership, the no-pin rule) and W2 hit-testing; `:same()` exists because nothing was interned
- `design/08-widget-replacement.md` — adopt/replace, the hidden-model lifetime + restore-on-teardown
- `design/07-ui-and-drawing.md` — the own-widget handle + owned-resource registry (P2)
- `src/io/brodgar/addon/UiApi.java` — the `hafen.ui` namespace, every entry point, and `model:items()` (:508)
- `src/io/brodgar/addon/LuaWidgetNode.java`, `LuaModel.java`, `LuaWidget.java` — the three types collapsing
- `src/io/brodgar/addon/CharApi.java:777` — **where `hafen.items` is actually installed** (not `AddonManager`)
- `src/io/brodgar/addon/AddonManager.java` — namespace install + the handle builders
- `src/io/brodgar/addon/AddonRegistry.java` + `Addon.java` — teardown order, the un-hide restore path, and
  the owned lists (`models`, `widgets`) the intern cache must NOT become
- `src/io/brodgar/addon/LuaMeter.java` — **the shape to copy**: userdata + weak-valued identity `Cache`
- `docs/addons/api/ui.md`, `items.md`, `types.md` — the surfaces unified and cut; `017-gob-oop/`,
  `027-meters-oop/` — prior art: OOP migration + hard cut
- `learnings/ui-widgets.md`, `learnings/widget-replacement.md` — **grep, never read whole**
- `decisions/widgets-ui.md` (D-009, D-024, D-041, D-042), `decisions/architecture-api.md`
  (D-012, D-013, D-044/045/046 the OOP mechanism, D-056, D-060 `:exists()`)
