# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`043-vr-namespace`](043-vr-namespace/)** — **3 of 5 tasks** (043.1, 043.2, 043.3 done), and its sibling
[`044-spatial-ui`](044-spatial-ui/) is planned behind it (0 of 8). 043 is **pure reorganization, no new
rendering**: `hafen.vr()` is the one section for client-only things standing in the 3D world, absorbing
`hafen.ghost()` and `hafen.render()` whole, taking the world-space kinds **out** of `gob:overlay()`, and
making the **anchor an argument**. **043.1 landed the section itself** — `hafen.vr():ghost()/:sprite()/:object()`,
each answering every verb its old section answered, the kinds **registered** rather than branched on (044's
`:widget()` is one line), `hafen.ghost`/`hafen.render` as `Retired` **section** rows so the refusal fires on the
field read, 38 Lua sites ported; **D-184** (a section is named for whose the thing is, not the mechanism)
supersedes D-034's half. **043.2 made the anchor an argument**: `:add(what, p)` stands at a point,
`:add(what, gob)` follows one, anything else refused naming **both** forms; an
anchored entity dies with its gob off the `GobRemoved` drain through a by-target index — **D-185** (where a
derived thing has no record on its source, index it by the source's key — written at create/destroy, read on its
event, so not the sweep D-100 deleted) — and `:position(p)` is **refused** on a follower, **D-186** (a write the
engine would silently undo is refused, naming the verb that works).
**043.3 emptied `gob:overlay()` of the world**: `ov:image`/`ov:model`/`ov:ghost` and the set serving only them
(`:scale :alpha :tint :rotate :billboard :spawnData :position`) are `Retired` rows naming `hafen.vr()`, so
`ov:offset(x, y)` means screen pixels and a third argument raises. The one world verb with no home on the handle
**moved there rather than vanishing** — `<entity>:offset(x, y, z)`, refused on a free entity naming
`:position(p)` — **D-187** (a verb that existed only for a kind leaves with it: grep the fields the deleted door
was the sole writer of, not just the call sites — `followOff` was one, and `tagger`'s pin would otherwise have
silently dropped to the ground). An anchored entity is **listed at its gob read-only**, every write/`:add`/
`:remove` refused naming its collection — **D-188** (a door is a write, a list is a read), reversing which side
038 hid and **deleting `asOverlay` whole** with `overlayEntity`, the four `overlay*` forwarders and `Attach`'s
entity half. So `gob:overlay()` now means exactly **what is drawn at this gob**: the game's own read-only, your
screen-space `draw`/`text`, and what you stood there through `hafen.vr()`. `tagger` (manifest 1.5.0) and
`:hello follow` ported. **The docs tier still describes `hafen.ghost`/`hafen.render`, batched into 043.5** —
which therefore owes the anchor argument, its refusals, the death-with-gob rule, `<entity>:offset(x, y, z)` and
a rewritten `api/gob.md` Overlays section, none of it in `docs/` yet. 043 still owes `:list()`/`:visible(b)`
(043.4) and `:billboard(b)` → `:facing(mode)` (043.5). **044 adds the fourth**,
`hafen.vr():widget()`: a Widget — yours or the client's own — standing in the world as a quad, held to one rule,
**transparency** (if it works on screen it works in the world: same `Draw`, input, controls, theme, popups and
keyboard), which is why its surface is a real UI **root** the widget is reparented into rather than a texture with
clicks forwarded. 044 also brings `:facing("camera")` — a world quad turning in yaw and pitch, with real world
size, perspective and occlusion. Every rendering piece exists (folder + `codebase/world-3d.md`).

[`042-event-driven-reads`](042-event-driven-reads/) closed before it, 13/13 tasks. The addon layer stopped
**polling the widget tree every frame** to synthesise its `*Changed`/`*Added` events (12 sites, 6 of them
`TreeAdapter`s), wiring them instead onto the four moments the client already announces a change at — the
`uimsg` tap, widget placement/removal, geometry, and `Loading`'s `Waitable` notify — then **deleted the poll
stage whole** (no gate, no fallback, no dual path; `TreeAdapter.poll()` is gone from the interface, 042.13) on
**six** core taps rather than the three budgeted. Decisions D-178..D-182, of which **D-181 supersedes D-091**.
Closes the ROADMAP's *"Per-frame cost of the addon layer's polling suite"*; the closing `hafen.prof` before/after
was skipped on the maintainer's call, so the categorical zero rests on the idle-silence suite proof and the
code-shape sweep rather than a measured figure.

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip/Markers Changed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Gated tier**: `hafen.act()` and the per-subsystem write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (13, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, and each carrying one accurate paragraph in its manifest rather than a changelog (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the in-flight suite, frozen `hello` and the example addons stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
