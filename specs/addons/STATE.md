# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`044-spatial-ui`](044-spatial-ui/) — 6 of 8 tasks.** `hafen.vr():widget()`: a Widget — yours or the
client's own — standing in the world as a quad, held to one rule, **transparency** (same `Draw`, input,
controls, theme, popups, keyboard, items), which is why its surface is a real UI **root** the widget is
reparented into. Next: culling (044.7), then the docs and the example addon (044.8).

**044.6 done — the client's own windows stand, and the last thing that did not was the DROP.** The provenance
refusal is gone: standing a native window is the same layer-that-restores as `:position`/`:visible`/`replace`,
**ungated**, the window still bound to its id and filling with items while it hangs. One rule for both
provenances, and it is **where the widget was, never whether it was shown** — so D-070 holds with no branch and
no second record, and the **toggle stays the client's**: standing hides nothing, so `togglewnd`/`wndstate` were
already right (**D-200**). A standing entity **ends with its content** (**D-201**, the 042.1 removal drain's
fifth consumer) — how `replace` composes when the server destroys the window its stand-in replaced; `stockPos`
answers from the record, or `savewndpos` would write the pinned origin to disk as the user's own window
position. **The manual round found a real gap**: an item aimed at a standing container landed on the map
beneath it — a drop is dispatched from the dragged item's OWN parent, reaching neither the surface nor 044.4's
intercept. Three `// addon:` lines answer *did a widget ACCEPT it*, so the fall-through is the flat UI's
(**D-202**). 11/11 + 5/5; `widget-input.md` gains the two drop families.

**044.5 before it — the root rule paid off, and the bill was six lines.** **Focus and the keyboard needed
NOTHING** (a surface is a plain non-`focusctl` child of `ui.root`, so `setfocus` forwards past it and
`FocusedKeyEvent` comes back down; `hasfocus` is the wrong read, so `widget:focused()` walks the delivery path).
**Popups WERE hard-wired** — a list adds itself to `ui.root` at `rootpos()`, both *constants* — so
`Widget.popuproot()` + `parentpos(popuproot())` replace them at all three sites (**D-198**); **so were the three
per-frame queries AT A POINT**, which now ask the panel under the pointer first (**D-199**). New:
`widget:focused()`, `:tooltip()`, `hafen.ui():tipAt()`. **A 040.10 defect surfaced and is NOT fixed**:
`dropdown:size(w, h)` leaves the drop arrow outside the box, clipped and unhittable.

**044.1–044.4 before it** (per-task detail in [`tasks.md`](044-spatial-ui/tasks.md)). **The gate**: a widget
subtree draws cleanly through an offscreen `GOut` when **re-homed into an invisible `WidgetSurface` under
`ui.root`** rather than detached (**D-191**) — the `Streamer`/`HeadlessClient` recipe narrowed to one subtree,
issued **before** `ui.draw(g)` in the same `Render` and **not redrawn every frame** (**D-192**, corrected twice).
**Both anchors** for ~15 lines (**D-185** paying out). **`"camera"`** is **view-plane aligned** (**D-193**), a
`Gob.Placer` the render tree re-reads every frame; `facing` carries all three values (**D-194**). **Input** is
the quad's four projected corners inverted as a homography, inside the very `MapView` event the press arrived in
(**D-195**, ignoring terrain occlusion); a standing widget left the world pick and answers a click **as a
widget** (**D-196**), `parentpos` giving `UI.PointerGrab` a live origin (**D-197**). Docs wait for 044.8.

[`043-vr-namespace`](043-vr-namespace/) **closed, 5/5 tasks** — pure reorganization, no new rendering.
**`hafen.vr()` is the one section for client-only things standing in the 3D world.** It absorbed `hafen.ghost()`
and `hafen.render()` whole, the kinds **registered** so 044's `:widget()` is one line (**D-184**, superseding
D-034's namespace half); made the **anchor an argument** with death-with-the-gob off a create/destroy index
(**D-185**) and `:position(p)` refused there (**D-186**); emptied `gob:overlay()` of the world, its homeless
verb moving with the kinds as `<entity>:offset(x, y, z)` (**D-187**), an anchored one listed at its gob
read-only (**D-188**); gave the section `:list(filter)`/`:visible(b)` (**D-189**); and made `:billboard(b)` into
**`:facing(mode)`**, a STRING so 044's `"camera"` is a value (**D-190**).

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `042-event-driven-reads` (the addon layer stopped **polling the widget tree every frame** to synthesise its `*Changed`/`*Added` events — 12 sites wired onto the four moments the client already announces a change at, then the poll stage **deleted whole** on six core taps; D-178..D-182, of which **D-181 supersedes D-091**), `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip/Markers Changed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Gated tier**: `hafen.act()` and the per-subsystem write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (13, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, and each carrying one accurate paragraph in its manifest rather than a changelog (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the example addons and frozen `hello` stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
