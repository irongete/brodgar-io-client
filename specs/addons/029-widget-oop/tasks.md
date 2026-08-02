# 029-widget-oop — Tasks

<!-- MAX 60 lines. One task = one session: self-contained, compiles, in-game verifiable on its own. -->

- [x] **029.1 — the entity: one type, interned, and the reads.**
      Rename `LuaWidget` → `AddonWidget` (the addon's own drawn widget; package-private, zero contract
      change), then grow `LuaWidgetNode` into the new **`LuaWidget`** entity: userdata + per-addon
      metatable + the intern cache, which is a **`WeakHashMap<Widget, WeakReference<LuaValue>>`** —
      weak on BOTH sides (`haven.Widget` overrides neither `equals` nor `hashCode`, so this is identity
      keying without pinning). Liveness is `hasparent(ui.root)`; a stale entity reads `nil`/empty with
      `:exists()` false and nulls its `wdg`. `root/node/at` return it; `:same()` is **hard cut** (`==`).
      **Gate the task on a measurement** (`hafen.client:profiling()`): a full `root():walk()` and
      `widgetstack`'s hover path, before vs after. If interning costs too much, stop and take the
      documented fallback rather than improvising.
      **Verify:** `jshell` on a bare `new haven.Widget(Coord)` (interning, `==`, staleness, no-pin), then
      in-game `widgetstack` with `:same()` replaced by `==`, plus the profiler numbers.

- [x] **029.2 — owned vs borrowed: creation, geometry, and the end of `adopt`.**
      `hafen.ui.window{}`/`widget{}` return the same entity, tagged OWNED. Geometry becomes
      arity-as-verb — `:pos()`/`:size()` read, `:pos(x,y)`/`:size(w,h)` write and chain; `:move()` cut.
      OWNED-only verbs (`:destroy`, `:pack`, the geometry writes) raise a clear error on a BORROWED
      widget, the write path naming feature **E**. `adopt` is **deleted**: `w:hide()` on a native widget
      records it on the addon's restore list and teardown un-hides it, guarded on `getwidget(id) == wdg`
      so a relog skips it and a `:reload` performs it. **`replace` keeps its own hiding rule** — it hides
      the enclosing wrapper and replays `hideTargetOrigVisible`; do not merge the two.
      **Verify:** in-game — `bags` still replaces and still restores the *hidden-by-default* inventory
      wrapper correctly on toggle, disable and `:reload`; a relog leaves nothing wrongly shown; the
      owned/borrowed errors read clearly from `:lua`.

- [ ] **029.3 — items without hiding, and the `hafen.items` cut.**
      `:items()` on any container (its `WItem` children, deep traversal), readable while the window is
      **visible and interactive** — no hiding, no registration. `:onItemAdded/:onItemRemoved/:onDestroy`
      move onto the entity as a per-tick diff (the `BuffsAdapter` shape, since item add/remove is not a
      uimsg), **`hasSub`-gated** so an unsubscribed widget is never polled. Add
      `hafen.ui.inventory()/equipment()/hand()` and **hard-cut `hafen.items`** at `CharApi.java:777`.
      `hello` gets an **interim** edit (its 4 `adopt` sites + the items reads) so the client still runs.
      **Verify:** in-game — open a chest and read its items with the window still open and usable;
      pick something up and see one add event; `hafen.items` reads `nil`; with nobody subscribed the
      profiler shows no per-tick item polling.

- [ ] **029.4 — docs, harness, close.**
      `docs/addons/api/ui.md` rewritten around the entity (the one type; owned vs borrowed and which
      verbs each answers; interning and `==`; `:hide()` on a native widget carries the restore — the
      line that must be impossible to miss; why there is no `adopt`; `replace` unchanged);
      **`items.md` deleted**, its content folded in. Sweep `types.md`, `events.md`, `conventions.md`
      (the OOP roster), `getting-started.md` and both index tables. `hello` version bump + the
      once-per-login contract check (an entity from each entry point, `==`, the owned/borrowed errors,
      a chest read with nothing hidden, `itemsGone`, `adoptGone`, `sameGone`, `moveGone`).
      **Verify:** one login re-checks 029 and every prior feature; `bags` and `widgetstack` clean; every
      link and anchor under `docs/addons/` resolves.
