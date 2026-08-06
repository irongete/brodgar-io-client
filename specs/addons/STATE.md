# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`042-event-driven-reads`](042-event-driven-reads/)** — 3 of 13 tasks done (042.1-042.3),
042.4 (study) next. The addon layer synthesises its `*Changed`/`*Added` events by **polling the
widget tree every frame** (11 sites, 6 of them `TreeAdapter`s) instead of listening at the moment a
change happens. 042 wires them to the four seams the client already publishes — the `uimsg` tap, widget
placement/removal, geometry, and `Loading`'s `Waitable` resolution notify — and **deletes the poll
stage**: no gate, no fallback, no dual path. **Four core taps** (the plan originally budgeted three;
042.2 found a fading widget needs its own), each a one-liner: `Widget.remove` (override-proof where
`cdestroy` is not — 9 of 17 overrides skip `super`), `Buff.reqdestroy` (reuses the same hub method, from
a second call site at the fade's START, not its late unlink), `Widget.resize` (which every size change
funnels through, screen included), and a notify inside the two `setbelt` paths that defer the `belt[]`
write. Decisions D-178..D-182, of which **D-181 supersedes D-091** (*"a derived position is re-derived
by POLLING what it reads"*). Closes the ROADMAP's *"Per-frame cost of the addon layer's polling suite"*.

**042.3 DONE — `EquipAdapter` is the third port, and `Resolve`'s first real consumer.** `EquipChanged`
now fires from placement/removal (M3/M1) for structure and the existing uimsg tap (`"num"`/`"chres"`/
`"tt"` on the worn `GItem`) for content, `"meter"` (wear) deliberately excluded. `GItem.info()`'s
derived build throws a bare, **unwaitable** `Loading` when its resource is still streaming — `Resolve`
catches the refusal and logs it (D-092's boundary), which is what the maintainer's login burst showed
repeatedly, all benign. Verified in-game under much harder conditions than planned: rapid back-to-back
gear swapping, dozens of real changes, every payload matching `hafen.ui():equipment():items()` by
identity, a departed item still answering with `:exists()` false, and silence the instant the swapping
stopped.

**042.2 DONE — `BuffsAdapter` is the second port, proving a widget that FADES needs its own tap.**
`BuffAdded` fires from the placement seam (M3); `BuffRemoved` fires from a one-line `// addon:` tap in
`Buff.reqdestroy()` at the exact moment the server's destroy sets `dest = true` — not 0.35s later when
the fade animation actually unlinks the widget, which M1 alone would give. That late M1 firing still
happens and is a deliberate no-op, guarded by `BuffsAdapter`'s cache-membership check. `BuffChanged` (the
`"ch"`/`"tt"` uimsg path) is unchanged. Verified in-game: a fresh buff (`visitor`) fired one `BuffAdded`
(res/name `nil` for a beat, as documented) and, on expiry, one `BuffRemoved` — printed the instant the
icon started fading, confirmed by the maintainer, never a second line. D-180 records the rule; D-179's
"exactly three core edits" is now four (`decisions/widgets-ui.md`).

**042.1 DONE — `MeterAdapter` is the first port, proving M1 (the removal seam) and M2 (`Resolve`).**
`MeterAdded`/`MeterRemoved` now fire from `Widget`'s placement/removal seams instead of a per-tick diff
of `LuaMeter.hud()`; `MeterChanged` (the `"set"`/`"col"` uimsg path) is unchanged. Verified in-game:
login seeds hp/stam/nrj as one `MeterAdded` each; mounting a horse adds two more (`:res()` legitimately
`nil` at that instant — accepted, per 027.2, never delayed to "fix"); dismounting fires two
`MeterRemoved`, each payload still answering its verbs with `:exists()` false. `Resolve` ships fully
built (retry-on-notify, marshalled onto the tick, owned per-`Addon`) but with **no functional consumer
yet** — every meter read was already `Loading`-guarded to `nil` with nothing to retry — proven for
real by 042.3.

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip Changed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Gated tier**: `hafen.act()` and the per-subsystem write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (13, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, and each carrying one accurate paragraph in its manifest rather than a changelog (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the in-flight suite, frozen `hello` and the example addons stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
