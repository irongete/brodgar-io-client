# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`044-spatial-ui`](044-spatial-ui/) — 1 of 8 tasks.** `hafen.vr():widget()`, the fourth collection. A
Widget — yours or the client's own — standing in the world as a quad, held to one rule, **transparency** (same
`Draw`, input, controls, theme, popups, keyboard), which is why its surface is a real UI **root** the widget is
reparented into rather than a texture with clicks forwarded; plus `:facing("camera")`, real size and occlusion.

**044.1 done — the gate holds: a widget subtree draws cleanly through an offscreen `GOut`.** `:add(w, p)` stands
one of your own widgets at a point, on the shared world-entity core. The widget is **re-homed into an invisible
`WidgetSurface` under `ui.root`** rather than detached (**D-191**), so it leaves the flat UI's draw and hit-testing
(`hafen.ui():at()` no longer finds it) while `:exists()`, its `Tick` and its `Draw` go on unchanged — three
questions `haven` answers with three properties of one node. The surface is the `Streamer`/`HeadlessClient` recipe
narrowed to one subtree (`Texture2D` → `FragColor` on a `BufPipe`, `Ortho2D`, the public `new GOut(Render, Pipe,
Coord)`), sampled by a `SurfaceQuad` in the entity's `Drawable` slot. Both open questions are decided: the pass is
issued from `UILoop.display` **before** `ui.draw(g)` — one `Render`, so "before" is a position in the stream and
the world samples a texture written **this** frame — and the material is `TexDraw + TexClip + blend`, neither of
R2a's two recipes, because a widget is translucent AND has a margin that must not write depth. **It does not redraw
every frame** (**D-192**): readable state redraws on a signature over the subtree; a `Draw` handler (or a running
`Anim`) redraws every frame, because only running it says what it paints; unarmed content is skipped, so the first
upload is the first that draws anything. `hafen.client():profiling():surfaces()` → `live`/`uploads`/`frames`,
pull-only. `:remove`/`:reload`/disable put the widget back where it stood from. 22/22 green, and the one `[manual]`
line earned its keep: a render target is v-flipped against an uploaded image, so `SpriteQuad`'s inverted `t` stood
the panel on its head. **Docs wait for 044.8** (mid-feature surface — no gob anchor, `:facing`, clicks or native
windows yet), exactly as 043 wrote its whole tier in 043.5. Coverage paid: `codebase/world-3d.md` gained
render-to-texture and **split** its two flagged halves into the new `codebase/render-gl.md`; `codebase/widgets.md`
gained the 2D draw target, the re-home recipe and focus.

[`043-vr-namespace`](043-vr-namespace/) **closed, 5/5 tasks** — pure reorganization, no new rendering.
**`hafen.vr()` is the one section for client-only things standing in the 3D world.** It absorbed `hafen.ghost()`
and `hafen.render()` whole, the kinds **registered** rather than branched on so 044's `:widget()` is one line
(**D-184**, a section is named for whose the thing is, not the mechanism — supersedes D-034's half); made the
**anchor an argument**, `:add(what, p)` or `:add(what, gob)`, an anchored entity dying with its gob off an index
written at create/destroy (**D-185**) and its `:position(p)` refused (**D-186**); emptied `gob:overlay()` of the
world, the one homeless verb moving with the kinds as `<entity>:offset(x, y, z)` (**D-187** — grep the FIELDS a
deleted door solely wrote) and an anchored entity now **listed at its gob read-only** (**D-188**); gave the
section `:list(filter)` and `:visible(b)`, the restore being a **second boolean beside the thing's own**
(**D-189**); and closed by turning `:billboard(b)` into **`:facing(mode)`**, the mode stored as the STRING so
044's `"camera"` is a value rather than a re-opened shape and the absent name raises rather than aliasing
(**D-190**). Docs moved wholesale into `docs/addons/api/vr/**`; 1404 links/anchors checked, 0 broken.

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
