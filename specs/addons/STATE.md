# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`042-event-driven-reads`](042-event-driven-reads/)** — 12 of 13 tasks done (042.1-042.12),
042.13 (the close: delete the poll stage, measure the numbers) next. The addon layer synthesises its `*Changed`/
`*Added` events by **polling the widget tree every frame** (11 sites, 6 of them `TreeAdapter`s) instead
of listening at the moment a change happens. 042 wires them to the four seams the client already
publishes — the `uimsg` tap, widget placement/removal, geometry, and `Loading`'s `Waitable` resolution
notify — and **deletes the poll stage**: no gate, no fallback, no dual path. **Six core taps** (the plan
originally budgeted three; 042.2/042.7/042.10 each found one more was unavoidable), each a one-liner:
`Widget.remove` (override-proof where `cdestroy` is not — 9 of 17 overrides skip `super`),
`Buff.reqdestroy` and `Window.reqdestroy` (reuse the removal hub from a second/third call site at the
fade's START, not its late unlink), `Widget.resize` and `Window.resize` (reuse the resize hub from a
second call site — `Window` skips `super.resize` outright, the same override gap one method over; of 17
`resize(Coord)` overrides only `Window` and `Tabs` skip it, `Tabs` a flagged, unfixed residual), and a
notify inside the two `setbelt` paths that defer the `belt[]` write. Decisions D-178..D-182, of which
**D-181 supersedes D-091** (*"a derived position is re-derived by POLLING what it reads"*). Closes the
ROADMAP's *"Per-frame cost of the addon layer's polling suite"*.

**042.12 DONE — `RenderApi.armPending`/`e.pending` go; a world entity's scene-add retries through
`Resolve` instead of a per-tick poll.** `addToScene`'s `catch(Loading l)` registers `l` with
`Resolve.on(...)`, whose retry re-checks `dead`/`hidden`/`mv==null` before re-adding. **Found and fixed a
real regression along the way**: `addClientGob`'s `Loading` is not only `MCache.LoadingMap` (an unstreamed
tile) — a busy render backend also throws `Defer.NotDoneException` ("finalizing texture …"), a
*different* texture each retry rather than one stable wait, so `Resolve`'s library-default 8-retry bound
gave up silently on a heavy load where the old unbounded `armPending` never did. Fixed with a `maxRetries`
overload on `Resolve.on` (default 8 unchanged elsewhere; scene-add uses 128) — learnings entry in
`learnings/ghosts.md`. Verified in-game: idle silence, `:t042-12` all-pass, both manuals confirmed.

**042.11 DONE — `MapApi.pollMarkers()` goes; `MarkersChanged` fires from a notify at the DB's own
mutation points**, inside the DB's own lock (enqueue only — avoids a re-entrant deadlock on the map DB's
RW lock), priming-and-firing on the first real mutation since there is no "first tick after login" to
distinguish from a real change unlike the old poll. Also fixed: `TESTING.md`'s stale copy-paste skeleton.

**042.1-042.10 DONE** — the widget-tree adapters and the UI-layer polls all ported onto M1
(removal)/M3 (placement)/M2 (`Resolve`)/the uimsg tap/M4 (geometry), each verified in-game idle-silent
with exactly-once firing: `MeterAdapter`/`BuffsAdapter` first proved M1 (`BuffRemoved` at `Buff.dest`, not
0.35s later — D-180); `EquipAdapter`/`StudyAdapter` proved `Resolve` on real streaming `GItem.info()`
data (D-092); `WoundAdapter` moved onto `WoundWnd`'s `"wounds"` uimsg + `Resolve`; `ActionbarAdapter`
closed the belt's deferred-write case (`AddonManager.onBeltSet(slot)`, D-178); `WidgetSubs`'s per-widget
`Destroy`/`ItemAdded`/`ItemRemoved` and `UiApi.pollReplaced`/`sweepReplaced` moved onto placement/removal
(`Destroy` covers a fading `Window` too, D-180's second consumer); `UiApi.pollSelectorWatches` went,
`disappear` off M1 and the late-caption re-check off the `"cap"` uimsg; `Layout.poll()`/`redrive()` went
(D-181 supersedes D-091), an anchor now re-derives on its own inputs' events — target resize/window
pack via `Widget.resize`/`Window.resize`, drag via a per-target `Widget.listen`, never a per-tick fold.

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip/Markers Changed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Gated tier**: `hafen.act()` and the per-subsystem write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (13, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, and each carrying one accurate paragraph in its manifest rather than a changelog (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the in-flight suite, frozen `hello` and the example addons stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
