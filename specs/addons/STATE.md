# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: none — [`044-spatial-ui`](044-spatial-ui/) is planned and waiting** (0 of 8 tasks): `hafen.vr():widget()`,
the fourth collection of the section 043 just finished. A Widget — yours or the client's own — standing in the world
as a quad, held to one rule, **transparency** (same `Draw`, input, controls, theme, popups, keyboard), which is why
its surface is a real UI **root** the widget is reparented into rather than a texture with clicks forwarded; plus
`:facing("camera")`, a world quad turning in yaw and pitch with real size, perspective and occlusion. Every
rendering piece exists.

[`043-vr-namespace`](043-vr-namespace/) **closed, 5/5 tasks** — pure reorganization, no new rendering.
**`hafen.vr()` is the one section for client-only things standing in the 3D world.** It absorbed `hafen.ghost()`
and `hafen.render()` whole (**043.1** — `:ghost()/:sprite()/:object()`, each answering every verb its old section
answered, the kinds **registered** rather than branched on so 044's `:widget()` is one line; the old sections are
`Retired` **section** rows, so the refusal fires on the field read; 38 Lua sites ported; **D-184**, a section is
named for whose the thing is, not the mechanism, supersedes D-034's half). It made the **anchor an argument**
(**043.2** — `:add(what, p)` stands, `:add(what, gob)` follows, anything else refused naming **both**; an anchored
entity dies with its gob off the `GobRemoved` drain through a by-target index, **D-185**, an index written at
create/destroy rather than the sweep D-100 deleted; `:position(p)` on a follower is **refused**, **D-186**). It
emptied `gob:overlay()` of the world (**043.3** — `ov:image`/`ov:model`/`ov:ghost` and the verb set serving only
them are `Retired` rows naming `hafen.vr()`, so `ov:offset(x, y)` means screen pixels and a third argument raises;
the one world verb with no home moved with them as `<entity>:offset(x, y, z)`, **D-187** — grep the fields the
deleted door SOLELY wrote, not just the call sites. An anchored entity is **listed at its gob read-only**, every
write refused naming its collection, **D-188**, a door is a write and a list is a read — which deleted `asOverlay`
whole). It gave the section the two verbs no single collection can be asked (**043.4** — `hafen.vr():list(filter)`
is everything you have stood, across the kinds, in **creation order**, taking the same canonical filter a per-kind
list takes; `hafen.vr():visible(b)` switches the whole section off and back, destroying nothing, and the restore is
**D-189**: a section-wide switch is a **second boolean beside the thing's own**, never a write over it, ANDed where
the scene slot is added — so one hidden on its own handle stays hidden. `<entity>:visible()` and
`hafen.vr():visible()` are different questions and neither is "is it on screen"; both verbs walk one registry
sweep, so 044's fourth kind joins for free). And it closed (**043.5** — `:billboard(b)` → **`:facing(mode)`**,
`"fixed"` or `"screen"`, the mode stored **as the string** so 044's `"camera"` is a value rather than a re-opened
shape and a saved layout round-trips it as itself; `"camera"` is refused today like any unknown word, because an
alias is a wrong answer that survives the fix — **D-190**. `planner`'s persisted `billboard` boolean became a
`facing` string with no shim. The docs tier moved wholesale into **`docs/addons/api/vr/**`** — hub, ghosts,
sprites, models and the gizmo — `api/gob.md`'s Overlays was rewritten around what stays, both glance tables
re-rowed and 14 pages swept, 1404 links/anchors checked and 0 broken).

[`042-event-driven-reads`](042-event-driven-reads/) closed before it, 13/13 tasks. The addon layer stopped
**polling the widget tree every frame** to synthesise its `*Changed`/`*Added` events (12 sites, 6 of them
`TreeAdapter`s), wiring them instead onto the four moments the client already announces a change at — the
`uimsg` tap, widget placement/removal, geometry, and `Loading`'s `Waitable` notify — then **deleted the poll
stage whole** (no gate, no fallback, no dual path; `TreeAdapter.poll()` is gone from the interface, 042.13) on
**six** core taps rather than the three budgeted. Decisions D-178..D-182, of which **D-181 supersedes D-091**.
Closes the ROADMAP's *"Per-frame cost of the addon layer's polling suite"*; the closing `hafen.prof` was skipped
on the maintainer's call, so the categorical zero rests on the idle-silence proof and the code shape, not a figure.

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip/Markers Changed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Gated tier**: `hafen.act()` and the per-subsystem write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (13, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, and each carrying one accurate paragraph in its manifest rather than a changelog (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the example addons and frozen `hello` stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
