# 003-widget-tree-reads — Plan

> History: this work appears in git history and `learnings/` tagged **1d-1, 1d-2, 1d-3, 1d-4**
> (Phase 1d). Split by adapter: one task = one in-game verification.

## Approach
- **Three-piece mechanism** (spec 14): a **Locator** (`gui()` + public `Widget.children(Class)` —
  no reflection to *find* widgets), an **Adapter** per target tree (localizes the
  upstream-volatile shape knowledge; reads into plain Lua snapshots), and the **inbound-`uimsg`
  tap** (`UI.UiMessage.run` → `AddonManager.onUimsg`, post-apply, off-thread → only flags the
  interested adapter dirty; the tick re-reads and fires the semantic event on the UI thread —
  the same marshalling as the gob events).
- **Two update paths.** uimsg-driven (`interested`→dirty→`refresh`): vitals, FEP, buff content.
  **Per-tick `poll()`** (added in the buffs slice): structural changes that are widget
  create/`cdestroy` (buff add/remove, study slots, equip) or writes deferred to a loader task
  (`belt[]`) — a uimsg refresh would miss or race them. `refresh` runs before `poll` so a new
  buff is one `BuffAdded`, not change-then-add.
- **Core edits: exactly two files, minimal (D-011).** The `UI.java` one-liner (the single seam
  every `*Changed` event and the future message hooks ride) and `AddonWidgets.java` — the
  "`SpeakerIcon` trick": a tiny `haven`-package accessor for `protected` fields
  (`LayerMeter.meters`, `Buff.dest`), so Lua never gets reflection (D-017). The study, skills,
  actionbar and equip slices are **zero-edit** (all-public reads); only `AddonManager.java` grew.
- **Change-detection everywhere**, excluding live meters (`cooldown`) and drift (`wear`) so
  events mean something. Headless check groups per slice (8/11/15/19 checks).
- **Honesty rules baked into the API**: vitals are bar fractions ONLY (no absolute numbers, no
  hunger — they don't exist client-side, B5); no invented seconds timers; `actionbar` is the
  engine's "belt" renamed once at the API boundary (D-013).

## Files created / modified
- `src/haven/UI.java` — one `// addon:` line (the inbound-uimsg tap)
- `src/haven/AddonWidgets.java` — new (`meters()`, `buffDest()`)
- `src/io/brodgar/addon/AddonManager.java` — `TreeAdapter` (+`poll()`), `VitalsAdapter`,
  `BuffsAdapter`, `FepAdapter`, `StudyAdapter`, `ActionbarAdapter`, `EquipAdapter`, facades +
  snapshot/equality helpers; `hafen.items.equipment` refactored onto shared `readEquipment()`
- `addons/hello/` — v0.6.0 → v0.9.0 (readVitals/readBuffs/readFood/readStudy/readActionbar)

## Risks & gotchas hit (detail: learnings/widget-tree-reads.md, threading.md)
- The tap runs on a Loader thread under `synchronized(ui)` — cheap `instanceof` + concurrent
  set only; never Lua off the UI thread.
- `belt[]` writes land on a **deferred loader task** → poll, don't trust the uimsg.
- Don't rely on `msg` interning; positional IMeter mapping is content-defined (confirmed
  in-game; fallback = `IMeter.bg` name matching).
- `SkillWnd` lists are swapped wholesale off-thread → copy before iterating.

## Discarded alternatives
- Reflection from Lua / the addon package — the `AddonWidgets` accessor localizes it (D-017).
- uimsg-refresh for buffs/study/actionbar/equip — structural/deferred changes require poll.
- A `hafen.belt` name — renamed `actionbar` at the API boundary (worn-belt confusion).
