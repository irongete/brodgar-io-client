# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`045-durable-places`](045-durable-places/) — planned, 0 of 3.** A free `hafen.vr()` entity's place becomes its **durable
anchor** (grid id + offset) and the session coordinate a derived cache — that space is re-based whenever the map is dropped, so a cave
and back leaves the thing not where it was put. Extends 044.9; one guarded `haven` line. **[`044-spatial-ui`](044-spatial-ui/) is CLOSED, 9 of 9**:
its `hafen.vr():widget()` shipped — a Widget, yours or the client's own, standing as a quad under the one rule **transparency**, which is why its surface is a real UI **root** it is reparented into (`docs/addons/api/vr/widgets.md`, `cupboard` the example).
A tenth task, the client's own 0.1s fade on a standing entity, was **dropped by maintainer directive** and its code removed whole: the ramp
reached a ghost and never the panel, through two different publish channels, and neither failure reproduced outside the running client.

**044.9 done — the ground under a free entity, and the test was not the one the task named.** A thing standing at a
POINT is in the scene only while the ground under it is drawn: **hidden, not ended, and not a policy** (**D-206**), on
the shared core so it reaches all four kinds, while an anchored one is untouched because its place is its gob's.
`MCache`'s grid set — the obvious test, with `gridwait` as its ready-made event — is a GRID wider than what is DRAWN
(only the server trims it), so it would have left the thing over the void past the visible edge; `MapRaster.Grid.cuts`
holds a cut exactly while its mesh is in the scene, so `MapView.grounddrawn` asks the very structure that draws the
ground and its two mutation points ARE the event — flag only, drained on the tick, never a sweep. A **third** boolean
ANDed into `shows()`; new read `<entity>:drawn()`. 10/10 + 2/2. **Round one found a real fault**: a scene add is a map
read at the gob's CURRENT point, so `gob.move` precedes every `addClientGob` now.

**044.8 before it — writing it down is where two engine faults surfaced.** The page, eleven edits, the `cupboard`
example (entirely event-driven), 12/12 and a clean §12 sweep. **Standing broke the UPWARD walk** — `getparent(Class)`
out of a panel missed the `GameUI`, so `Inventory`'s shift-wheel transfer threw; one seam crosses to **the record**
(**D-204**). **And a window announces its removal BEFORE it unlinks** (its fade's start, the path every server-closed
container takes), so the put-back left dead windows on the flat UI — `contentGone` is set at the removal **tap**, true
for every door at once (**D-205**); the example's record cost three rounds, a container shedding its grid and items
*before* the window speaks (`learnings/`).

**044.7 before it — no engine culling to reuse, and the test was already being computed**: **044.4's four projected
corners, against the view they landed in, ARE** the frustum test (**D-203**), asked FIRST in `needsDraw`;
`p:surfaces()` gained `culled`. **044.5–044.6 before that — the root rule paid off, and the client's own windows stand.** Focus and the keyboard needed
**nothing** (a surface is a plain non-`focusctl` child of `ui.root`); popups and the three per-frame queries AT A POINT
**were** hard-wired to `ui.root` and now resolve against the nearest root (**D-198**, **D-199**); new:
`widget:focused()`, `:tooltip()`, `hafen.ui():tipAt()`. Standing a native window is the same layer-that-restores as
`:position`/`:visible`/`replace`, **ungated**, under one rule for both provenances — **where the widget was, never
whether it was shown** — so D-070 holds with no branch and the toggle stays the client's (**D-200**); a standing entity
**ends with its content** (**D-201**), a drop is answered by *did a widget ACCEPT it* (**D-202**). **A 040.10 defect is
still NOT fixed**: `dropdown:size(w, h)` leaves its drop arrow outside its box.

**044.1–044.4 before it** (detail in [`tasks.md`](044-spatial-ui/tasks.md)). **The gate**: a widget subtree draws
cleanly through an offscreen `GOut` when **re-homed into an invisible `WidgetSurface` under `ui.root`** rather than
detached (**D-191**), the pass issued before `ui.draw(g)` in the same `Render` (**D-192**, corrected twice); both
anchors cost ~15 lines. `"camera"` is **view-plane aligned** (**D-193**), `facing` carrying all three values (**D-194**);
**input** is those four corners inverted as a homography inside the `MapView` event the press arrived in (**D-195**),
and a standing widget left the world pick to answer as a widget (**D-196**, **D-197**).
[`043-vr-namespace`](043-vr-namespace/) **closed, 5/5** before all of it — pure reorganization: `hafen.vr()` absorbed
`hafen.ghost()`/`hafen.render()` with the kinds **registered**, made the **anchor an argument**, emptied
`gob:overlay()` of the world, turned `:billboard(b)` into **`:facing(mode)`** (**D-184**–**D-190**).

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `042-event-driven-reads` (the addon layer stopped **polling the widget tree every frame** to synthesise its `*Changed`/`*Added` events — 12 sites wired onto the four moments the client already announces a change at, then the poll stage **deleted whole** on six core taps; D-178..D-182, of which **D-181 supersedes D-091**), `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip/Markers Changed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Gated tier**: `hafen.act()` and the per-subsystem write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (14, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `cupboard`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, each carrying one accurate paragraph in its manifest (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the example addons and frozen `hello` stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
