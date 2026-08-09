# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`047-flowermenu`](047-flowermenu/), 1 of 3** — `hafen.flowermenu()`: the open radial menu as a section
(`:list()` labels · `:count()` · `:gob()` · gated `:select(label|n)`/`:cancel()`) + `FlowerMenuOpened`/`FlowerMenuClosed`.
**047.1 is CLOSED**: the section (`:list()` — bare **strings** in ring order, no filter, a petal set being frozen from
`added()` to death with no field to match on — and `:count()`, both answering `{}`/`0` with no menu up and neither
ever throwing), the two bus keys, and the finder moved out of `ActApi` (**D-103**). The promise — *every Opened is followed by exactly one Closed* — is kept **structurally** (**D-212**):
a weak map keyed on the menu itself, key presence meaning "opened, not yet closed", with all three ending doors
(`uimsg("act")`, `uimsg("cancel")`, a new `destroy()` override) calling one `closed()` — which is what let the
fork's client-side *Mute voice* petal report its own label in one line at the head of `choose`. Opened fires at the
**END** of `added()`, the only point the set is complete (`addVoicePetal` replaces `opts` inside it). 26/26 over 5
menus; the **client-side (`BuddyWnd`) path is proven only headlessly** — 047.3 exercises it in-game.

**[`046-gob-scale`](046-gob-scale/) is CLOSED, 1 of 1** — a **native** gob answers `:scale`, the verb its `hafen.vr()`
siblings have: client-local, purely visual, in place, and the **first WRITE** on a handle that had been read-only.
A `GobScale extends GAttrib implements Gob.SetupMod` on the engine's own `Gob`, so `Gob.ctick`'s per-tick `GobState`
compare propagates it with **zero `haven` edits** — and the `Location.scale(k)` is minted once per VALUE, the
mechanism rather than an optimisation: `Location` has no `equals`, so a fresh op each tick would re-push every
scaled gob's render state forever (**D-210**). Writing `1` **removes** the attrib; the size **ends with the loaded
object** by directive; an addon that *stops running* leaves nothing distorted, because the attrib records its writer
and `UiApi.teardownGobScales` sweeps beside `teardownGobOverlays` — which is what pays for the ungating. **The one
deliberate divergence from the vr siblings is validation** (**D-211**): they clamp, their factor arriving in an
options table; a direct argument on a direct verb refuses `0`, a negative, a non-finite and a non-number.

**[`045-durable-places`](045-durable-places/) is CLOSED, 3 of 3** — a thing you stand at a **point** is at that
point tomorrow, and after a cave. A free `hafen.vr()` entity's place is its **durable anchor** (grid id + the offset
inside it) and the session coordinate a derived cache, because that space is re-based whenever the map is dropped:
`:info()` is the place and `:x()` this session's answer to it (**D-207**), and a place with no durable form is
refused at both doors. A place this session cannot **locate** is legal (**D-208**) — the entity exists, holds its
grid, is not drawn, and stands itself up when that ground resolves, with no `Resolve` chain and no cap. **The second
event** (**D-209**) is one guarded `// addon:` line at `MiniMap.tick`'s `sessloc` assignment (the only `haven` edit;
a `(seg, tc)` test, because `tick` mints a fresh `Location` every frame), witnessed by a seventh pull-only counter.

**[`044-spatial-ui`](044-spatial-ui/) is CLOSED, 9 of 9**: `hafen.vr():widget()` — a Widget, yours or the client's
own, standing as a quad under the one rule **transparency**, which is why its surface is a real UI **root** it is
reparented into (`docs/addons/api/vr/widgets.md`, `cupboard` the example). A tenth task, the client's own 0.1s fade
on a standing entity, was **dropped by maintainer directive** and its code removed whole. Along the way: a thing
standing at a POINT is in the scene only while the ground under it is DRAWN — hidden, not ended (**D-206**,
`<entity>:drawn()`), and `MapRaster.Grid.cuts` is what knows it; `getparent(Class)` out of a panel missed the
`GameUI`, so one seam crosses to **the record** (**D-204**); a window **announces its removal before it unlinks**
(**D-205**); culling is the four projected corners as a frustum test (**D-203**), and input those same corners
inverted as a homography (**D-195**). **A 040.10 defect is still NOT fixed**: `dropdown:size(w, h)` leaves its
drop arrow outside its box.
[`043-vr-namespace`](043-vr-namespace/) **closed, 5/5** before it — pure reorganization: `hafen.vr()` absorbed
`hafen.ghost()`/`hafen.render()`, made the **anchor an argument**, emptied `gob:overlay()` of the world, and turned
`:billboard(b)` into **`:facing(mode)`** (**D-184**–**D-190**).

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `042-event-driven-reads` (the addon layer stopped **polling the widget tree every frame** to synthesise its `*Changed`/`*Added` events — 12 sites wired onto the four moments the client already announces a change at, then the poll stage **deleted whole** on six core taps; D-178..D-182, of which **D-181 supersedes D-091**), `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip/Markers Changed, FlowerMenuOpened/Closed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Gated tier**: `hafen.act()` and the per-subsystem write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (14, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `cupboard`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, each carrying one accurate paragraph in its manifest (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the example addons and frozen `hello` stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
