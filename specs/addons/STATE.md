# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`044-spatial-ui`](044-spatial-ui/) — 4 of 8 tasks.** `hafen.vr():widget()`: a Widget — yours or the
client's own — standing in the world as a quad, held to one rule, **transparency** (same `Draw`, input, controls,
theme, popups, keyboard), which is why its surface is a real UI **root** the widget is reparented into rather than
a texture with clicks forwarded. Next: focus/popups (044.5), then native windows (044.6).

**044.4 done — a click lands on the panel pixel it looks like it lands on, and the pick pass had nothing to do
with it.** The hit test is the quad's own **four projected corners** inverted as a homography, inside the very
`MapView` mouse event the press arrived in (**D-195**) — a press, its drag and its release are one gesture, and
`Hittest` answers a frame later. The corners are recorded by a `PView.Render2D` that draws nothing, so the pointer
follows the picture by construction and a `"screen"` blit is the same arithmetic's degenerate case; the stated
cost is that input ignores terrain occlusion. A standing widget therefore **left the world pick entirely** (no
`GobClick`, so a miss reaches the world beneath by construction) and answers a click **as a widget** (**D-196**):
`:onClick`/`WidgetClicked` refused, `:clickable(b)` now "does the panel take the pointer", default true.
`WidgetSurface.parentpos` answers with a live origin so a grabbing widget is fed correct coordinates by
`UI.PointerGrab` (**D-197**); the same `tick` pins the content at (0,0), keeping the title-bar drag inert now that
it can be pressed. New: `hafen.vr():pointer(key, x, y [, a])` and `widget:screen(x, y)`, exact inverses. **It found
a 044.1 defect** — a `Button` caches its rasterised face and `redraw()`s on press, invisible to the signature, so
a standing button clicked, played its sfx and never visibly pressed: `SIWidget.redrawing()` is the third
`// addon:` one-liner and D-192's enumeration is corrected a second time. 11/11 + 2/2 over five rounds.

**044.3 before it — `"camera"` is a real world quad, and the turn cost an override rather than a loop.** The mode is
**view-plane aligned** (parallel to the screen, not aimed at the eye point), so every panel is square-on everywhere
and two stay parallel — **D-193**. It is a `Gob.Placer` on the `Drawable`, which `Placed.autotick` already re-reads
every frame: no tick loop, one `// addon:` accessor (`MapView.camview()`). `facing` moved onto `LuaWorldEntity`
with **all three** values rather than the one asked for (**D-194**). It also caught a 044.1 defect — a `Window`'s
fade is a private field, not a `Widget.Anim` — fixed by `Window.animating()`, the second `// addon:` one-liner.

**044.2 and 044.1 before it.** `:add(w, gob)` stands beside `:add(w, p)` for ~15 lines — the refused
`:position(p)`, the `:offset`, death-with-the-gob and the read-only entry at the gob were already the shared
core's, **D-185 paying out**. And the gate: a widget subtree draws cleanly through an offscreen `GOut` when it is
**re-homed into an invisible `WidgetSurface` under `ui.root`** rather than detached (**D-191**), so it leaves the
flat UI's draw and hit-testing while `:exists()` and its `Tick`/`Draw` go on unchanged — the
`Streamer`/`HeadlessClient` recipe narrowed to one subtree, sampled by a `SurfaceQuad`, its pass issued in
`UILoop.display` **before** `ui.draw(g)` (same `Render`, never stale), and **not redrawn every frame** (**D-192**);
`p:surfaces()` → `live`/`uploads`/`frames`. Docs wait for 044.8.

[`043-vr-namespace`](043-vr-namespace/) **closed, 5/5 tasks** — pure reorganization, no new rendering.
**`hafen.vr()` is the one section for client-only things standing in the 3D world.** It absorbed `hafen.ghost()`
and `hafen.render()` whole, the kinds **registered** so 044's `:widget()` is one line (**D-184**, superseding
D-034's namespace half); made the **anchor an argument**, death-with-the-gob off an index written at
create/destroy (**D-185**), `:position(p)` refused there (**D-186**); emptied `gob:overlay()` of the world, its
homeless verb moving with the kinds as `<entity>:offset(x, y, z)` (**D-187**), an anchored one **listed at its gob
read-only** (**D-188**); gave the section `:list(filter)`/`:visible(b)` (**D-189**); and made `:billboard(b)` into
**`:facing(mode)`**, a STRING so 044's `"camera"` is a value (**D-190**).

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `042-event-driven-reads` (the addon layer stopped **polling the widget tree every frame** to synthesise its `*Changed`/`*Added` events — 12 sites wired onto the four moments the client already announces a change at, then the poll stage **deleted whole** on six core taps; D-178..D-182, of which **D-181 supersedes D-091**), `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip/Markers Changed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Gated tier**: `hafen.act()` and the per-subsystem write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (13, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, and each carrying one accurate paragraph in its manifest rather than a changelog (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the example addons and frozen `hello` stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
