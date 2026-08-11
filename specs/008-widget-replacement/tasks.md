# 008-widget-replacement — Tasks

- [x] 008.1 — Widget-creation interception: `hafen.ui.onWidgetCreate(fn)` + the
      `{id,type,place,caption,parentType}` descriptor (two `UI.java` seams, observe-only).
- [x] 008.2 — The model handle: `hafen.ui.adopt(id)` — hide/show/items + lifecycle events,
      poll-driven destroy/item diff, teardown un-hide.
- [x] 008.3 — `hafen.ui.replace(type, opts, fn)` + the `bags` example: creation + scan find
      paths, wrapper-window hide with original-visibility restore — the Phase-3 DoD.
