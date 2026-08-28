# 117 — tasks

Each suite is `addons/117-an-object-the-client-has-is-drawn.<X>/` and answers `:t117`. Both rest on
one oracle: a `gob:overlay()` is a `GAttrib` that is also a `RenderTree.Node`, so it paints exactly
while its gob stands in the render tree — which makes *is this object drawn* a thing Lua can read.

- [x] **117.1 — the attrib map is written under the gob's own monitor.** `Gob.setattr(Class, GAttrib)`,
      the funnel `setattr(GAttrib)` and `delattr` both pass through, becomes synchronized, and so does
      `delattr`, which reads `attr` before calling it; `Gob.attr` becomes a `ConcurrentHashMap`.
      `Gob.added(RenderTree.Slot)` is deliberately left alone — a monitor there is the tree-then-gob
      inversion. No call site moves and nothing is retired: `SpeakerIcon.sweep`, `GobScale.apply` and
      `LuaGobOverlay.ensure`/`prune` keep their spelling and stop being able to break.
      `docs/client/state.md` gains the rule and names who walks the map from which thread;
      `docs/addons/api/threading.md` gains one line under *What runs beside you* — a write on a gob is
      safe from either group of its table.
      *Its suite* attaches a counting overlay to every gob the session can see and records which of
      them paint. Then it drives `gob:scale` and `gob:overlay():add`/`:remove` over those same gobs
      from two threads at once — a timer on the step and a `Draw` handler on a window of its own —
      every call under `pcall`, for a bounded window. It then asserts that every gob that painted
      before still paints, that `gob:scale()` and the overlay's own key list read back the last value
      written to each, that no driven call raised (a raise is a `[fail]` quoting its message), and
      that the frame count advanced across the window, which is what a deadlock or a stall under the
      new monitor would take away. Two threads writing one gob is the exact shape the monitor exists
      for, and the first assertion is the symptom the feature is named after.
      <!-- extra context: docs/addons/api/ui/custom.md (the `Draw` handler, and which thread it is) -->

- [x] **117.2 — a dropped render add is not for ever.** `MapView.Gobs.addgob` gains a
      `RuntimeException` arm beside its `SlotRemoved` one: it takes the gob out of `adding` and issues
      a `Warning` naming its id and resource, so the Loader thread survives what today it dies of, and
      an object lost to the scene says so instead of simply not being there. `Loading` goes on
      escaping — that parking is the retry. `Gobs` gains the reconcile only `SessionGobs` had, on the
      same 0.25 s timer, called from `MapView.tick` on the drawn path, and it does both halves: add
      what the `OCache` holds and the view does not, evict what the view holds and the `OCache` no
      longer does. `SessionGobs.tick()` becomes `super.tick()` plus its own `skipgob`/`vis`
      conditions. `docs/client/state.md` gains the row for what a dropped add costs and what recovers
      it.
      *Its suite* attaches a counting overlay to every gob and samples it against the client's own
      frame count over a bounded window, asserting each gob's overlay paints **once per frame** —
      zero is an object the `OCache` holds and the scene does not, two is the double-add the
      reconcile's `adding` guard exists to prevent. It subscribes `GobAdded` and holds newcomers to
      the same count, which is the arrival path the reconcile races. And it asserts the two sets agree
      both ways: every gob `s:world():gob():list()` reports is painting, and nothing painting has left
      that list. The `RuntimeException` arm itself is verified by **reading the site** — nothing the
      API can spell injects one into `addgob` — so what the suite proves is the invariant that arm
      defends, and the arm is read.
      <!-- extra context: docs/addons/api/event/bus/world.md and event/bus/lifecycle.md (`GobAdded`, `Update`) -->
