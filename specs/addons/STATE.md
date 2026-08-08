# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`044-spatial-ui`](044-spatial-ui/) — 2 of 8 tasks.** `hafen.vr():widget()`: a Widget — yours or the
client's own — standing in the world as a quad, held to one rule, **transparency** (same `Draw`, input,
controls, theme, popups, keyboard), which is why its surface is a real UI **root** the widget is reparented into
rather than a texture with clicks forwarded. Plus `:facing("camera")`, real world size and occlusion.

**044.2 done — the fourth kind takes both anchors, and it cost ~15 lines.** `:add(w, gob)` stands beside
`:add(w, p)`: the collection stopped refusing a Gob and passes `an.tgt` on; `makeWidget` sets `followTgt` before
`anchorRegister` and applies the same `FollowMoving` a sprite gets. **Nothing else was needed** — the refused
`:position(p)` on an anchored one, the `:offset(x, y, z)` that means "where" there, `anchorGone`'s
death-with-the-gob, `hafen.vr():list()` across the kinds and the read-only entry at the gob are all the shared
core's. That is **D-185 paying out** — the anchor being an ARGUMENT made the second form a parameter, not a
second kind — so **no new decision came out of this task**. *"A title-bar drag is inert"* is asserted as the
flat UI's own hit test read twice over each window's rectangle (reachable before it stands, at **no** point
after), then by writing the place a drag would have written and showing the world place unmoved. 20/20 (twice)
+ 6/6 on the despawn round, each parked panel already gone INSIDE `GobRemoved`.

**044.1 before it — the gate holds: a widget subtree draws cleanly through an offscreen `GOut`.** The widget is
**re-homed into an invisible `WidgetSurface` under `ui.root`** rather than detached (**D-191**), so it leaves the
flat UI's draw and hit-testing while `:exists()`, its `Tick` and its `Draw` go on unchanged. The surface is the
`Streamer`/`HeadlessClient` recipe narrowed to one subtree, sampled by a `SurfaceQuad` in the `Drawable` slot;
its pass runs in `UILoop.display` **before** `ui.draw(g)`, same `Render`, never stale. **It does not redraw every
frame** (**D-192**): readable state redraws on a signature over the subtree, a `Draw` handler (or a running
`Anim`) every frame because only running it says what it paints, unarmed content not at all; `p:surfaces()` →
`live`/`uploads`/`frames`. 22/22. **Docs wait for 044.8**, as 043 wrote its tier in 043.5. Coverage paid:
`codebase/world-3d.md` gained render-to-texture and **split** its flagged halves into `codebase/render-gl.md`.

[`043-vr-namespace`](043-vr-namespace/) **closed, 5/5 tasks** — pure reorganization, no new rendering.
**`hafen.vr()` is the one section for client-only things standing in the 3D world.** It absorbed `hafen.ghost()`
and `hafen.render()` whole, the kinds **registered** rather than branched on so 044's `:widget()` is one line
(**D-184**, a section is named for whose the thing is, not the mechanism, superseding D-034's half); made the
**anchor an argument**, an anchored entity dying with its gob off an index written at create/destroy (**D-185**)
and its `:position(p)` refused (**D-186**); emptied `gob:overlay()` of the world, its one homeless verb moving
with the kinds as `<entity>:offset(x, y, z)` (**D-187**) and an anchored entity **listed at its gob read-only**
(**D-188**); gave the section `:list(filter)` and `:visible(b)`, the restore a **second boolean beside the
thing's own** (**D-189**); and turned `:billboard(b)` into **`:facing(mode)`**, the mode stored as the STRING so
044's `"camera"` is a value, not a re-opened shape (**D-190**). Docs moved into `docs/addons/api/vr/**`.

[`042-event-driven-reads`](042-event-driven-reads/) closed before it, 13/13 tasks. The addon layer stopped
**polling the widget tree every frame** to synthesise its `*Changed`/`*Added` events (12 sites, 6 of them
`TreeAdapter`s), wiring them onto the four moments the client already announces a change at — the `uimsg` tap,
widget placement/removal, geometry, `Loading`'s `Waitable` notify — then **deleted the poll stage whole** (no
gate, no fallback, no dual path) on **six** core taps rather than the three budgeted. D-178..D-182, of which
**D-181 supersedes D-091**. Closes the ROADMAP's *"Per-frame cost of the addon layer's polling suite"* — on the
idle-silence proof and the code shape, the closing `hafen.prof` having been skipped on the maintainer's call.

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip/Markers Changed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Gated tier**: `hafen.act()` and the per-subsystem write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (13, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, and each carrying one accurate paragraph in its manifest rather than a changelog (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the example addons and frozen `hello` stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
