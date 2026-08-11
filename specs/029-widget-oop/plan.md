# 029-widget-oop — Plan

## Approach

One entity, built on the `LuaMeter`/`LuaBuff` shape — with **one deliberate deviation** the prior art does
not have (see *The interning trap*). `LuaWidgetNode` grows into it, `LuaModel` is deleted, and the addon's
own drawn widget is renamed out of the way.

1. **Naming first.** `LuaWidget` today is the addon's own *drawn* widget (a `haven.Widget` subclass) — not a
   Lua handle. Rename it **`AddonWidget`** (package-private, zero contract change) and give `LuaWidget` to
   the one Lua-facing entity. Same feature also deletes `LuaModel`, closing the `LuaModel`/`LuaMesh`
   collision 028 deferred.
2. **Provenance on the entity**: `OWNED` (created by `hafen.ui.window{}`/`widget{}`) vs `BORROWED` (a native
   widget). Reads and `:hide()`/`:show()` work on both; `:destroy()`/`:pack()`/geometry **writes** are
   OWNED-only and raise a clear error on a native one — the write path names feature **E** (layout) rather
   than half-working.
3. **Geometry is arity-as-verb** (the `018` options shape): `:pos()`/`:size()` read, `:pos(x,y)`/`:size(w,h)`
   write and chain on self. `:move()` and `:same()` are hard cut (`==` is the identity test once interned).
4. **Liveness is `hasparent(ui.root)`**, the node rule — NOT the model's `getwidget(id) != wdg`, which only
   covers server-bound widgets. `getwidget(id) != wdg` stays, but only as what fires `onDestroy`.
5. **`:items()` on any container**, reading its `WItem` children while the widget is visible — no hiding, no
   registration. Item add/remove is **not a uimsg** (a `WItem` create/`cdestroy`), so the lifecycle events
   stay a per-tick diff on the `BuffsAdapter` shape — now **`hasSub`-gated** (`fireBuff`/`fireMeter`
   pattern) so a widget nobody subscribed to is never polled.
6. **`hafen.items` hard cut** at its real home, `CharApi.java:777` (not `AddonManager`) →
   `hafen.ui.inventory()/equipment()/hand()`.
7. **`adopt` deleted; `replace` untouched.** `w:hide()` on a BORROWED widget records it on the addon's
   restore list; teardown un-hides. `replace` keeps its own hiding rule (below) — the two are not merged.

## The interning trap (the one real design risk)

`LuaMeter`/`LuaBuff` use `IdentityHashMap<K, WeakRef<handle>>` + a drained `ReferenceQueue`: **weak values,
strong keys**. With a handful of meters that is bounded. Over a **widget tree** it is not — a strong key
would pin every destroyed widget until the queue drains, breaking the **D-041 no-pin rule** that
`LuaWidgetNode` implements on purpose (it nulls its `wdg` on the first stale access so a stashed node cannot
pin a dead subtree).

So this cache must be **weak on both sides**: `WeakHashMap<Widget, WeakReference<LuaValue>>`. Verified:
`haven.Widget` overrides neither `equals` nor `hashCode`, so `WeakHashMap` gives **identity** keying *and*
weak keys — exactly what is needed, with no custom map.

**Second-order risk: walk cost.** `root():walk()` currently mints throwaway handles; interned, it becomes a
map insert per node per walk — the per-frame allocation class 026 was written to kill. **Gate the task on a
measurement**: `hafen.client:profiling()` around a full-tree walk and around `widgetstack`'s hover path,
before and after. If it does not hold, the fallback is documented, not improvised: intern only entities that
escape a walk (entry points + `:parent()`), leaving `:children()` transient — at the cost of `==` being
partial, which would send the `:same()` cut back to B2.

## Files to create / modify

- **rename** `LuaWidget.java` → `AddonWidget.java`; **grow** `LuaWidgetNode.java` into the new `LuaWidget`
  (entity + intern cache); **delete** `LuaModel.java`.
- `src/io/brodgar/addon/UiApi.java` — every entry point returns the entity; `adopt` removed; `replace`
  rewired to hand over the entity; `model:items()` (:508) becomes the entity's `:items()`.
- `src/io/brodgar/addon/CharApi.java` — delete the `hafen.items` table (:777).
- `src/io/brodgar/addon/Addon.java` — `models` becomes the **hidden-native restore list**; `widgets` stays
  the owned list; the intern cache is a third thing and is **not** a teardown registry.
- `src/io/brodgar/addon/AddonRegistry.java` — un-hide on teardown, guard unchanged (below).
- `src/io/brodgar/addon/AddonManager.java` — handle builders + namespace install.
- `docs/addons/api/ui.md` (rewritten around the entity), `items.md` (**deleted**), `types.md`,
  `conventions.md`, `events.md`, `api/README.md`, `docs/addons/README.md`, `getting-started.md`.
- `addons/hello/main.lua` (4 `adopt` sites + the items reads), `addons/bags/main.lua`,
  `addons/widgetstack/main.lua` (loses `:same()` → `==`).
- **No `haven` file is edited**; `Widget.java` is read-only reference ⇒ no coverage toll expected.

## Risks & gotchas

*(prior art: `learnings/widget-replacement.md`, `learnings/ui-widgets.md` — grepped, not read whole)*

- **`replace` hides the WRAPPER, `adopt` hid the widget.** `replace` hides `nativeWindowOf(wdg)` — the
  `Hidewnd "Inventory"` around `maininv` — and must replay the **captured original visibility** on teardown,
  because that wrapper is hidden-by-default (the client only shows it on Tab). Blindly `show()`ing it leaves
  the native inventory *showing* after a disable. Keep `hideTarget` + `hideTargetOrigVisible`; do **not**
  collapse `w:hide()` and `replace`'s hiding into one rule.
- **Un-hide-on-teardown timing.** Guard on `getwidget(id) == wdg`: `init` sets the NEW session's `ui` before
  the teardown loop, so a relog correctly *skips* the un-hide while a same-session `:reload` performs it.
- **`Widget.hide()` keeps a server widget fully live** — still a child, still bound, still receiving
  `uimsg`/`addchild`. That is what makes reads-while-hidden work, and it is why hiding is not ownership.
- **`children(Class)` is a DEEP traversal** (whole subtree). Fine for an `Inventory` (WItems are direct
  children) — do not assume it elsewhere.
- **Anonymous subclasses are everywhere**: `:type()` must keep walking up to the nearest *named* superclass.
- **Headless-checkable**: a bare `new haven.Widget(Coord)` constructs with no GL and `hide`/`show`/`visible`/
  `children(Class)` all work on it — so interning, provenance errors and the no-pin rule are `jshell`-
  assertable. Only `UI.getwidget`-backed paths and real items need in-game.
- **Item verbs stay gated.** Reads are ungated; anything emitting a player `wdgmsg` remains `hafen.act.*`.

## Discarded alternatives

- **Rename `adopt` (`takeover`/`cover`)** — rejected: after the collapse it has no capability of its own, so
  a name would only preserve a concept that stopped existing.
- **Keep `hafen.items` as a callable section taking a container** — rejected by the maintainer: it would
  preserve the player-inventory privilege the feature removes.
- **`IdentityHashMap` + weak values (the LuaMeter cache verbatim)** — rejected: strong keys pin dead widgets.
- **Intern nothing, keep `:same()`** — rejected: `==` is the OOP contract every other section already has.
- **Fold B2's selector engine in here** — rejected: two contracts in one feature, verifiable as neither.
