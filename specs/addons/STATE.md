# STATE — what works right now

> Maintained by REPLACING (max 60 lines). Branch `feature/addons`; per-feature detail: its `NNN-` folder.

**ACTIVE: [`048-act-dissolved`](048-act-dissolved/), 4 of 8** — the one section grouped by PERMISSION rather than by what
it acts on is being dissolved (**D-187** generalised as **D-215**: a verb lives with what it CHANGES, not with what it
COSTS — a permission is not a namespace). Every verb moves onto the thing it changes ([spec.md](048-act-dissolved/spec.md)
is the map), while `:flower`, `:menu` and `:enabled` are DELETED — 047 and 023 own those doors already (open **D-103**; no
path door is built). `gated` → **protected**, `pag:use()` gains its gate. **The docs tier is 048.8's whole job**, so until
then `docs/addons/api/act.md` still teaches verbs that have moved.
**048.1 DONE, 13/13**: `hafen.player():move(p)` and `gob:click(button, mods)` ship — same messages, same gate, on the
things they change. The section stays mounted while it empties (**D-117**) and a moved verb is retired under **BOTH**
field reads (**D-216**). A departed gob makes the click **RAISE** where every read answers nil (**D-217**).
**048.2 DONE, 29/29**: the cursor is an **object** — `hafen.player():hand()`, **nil** while you carry nothing, with
`:item()` and the protected `:use(target, mods)` dispatching onto an Item, a Position or a **Gob** (that arm being
`MapView.iteminteract`'s `clickargs` extension, a message no addon could send). What it *drops* is firing the gesture
with an empty cursor: **D-218** — the receiver must BE the message's implicit subject (`DTarget.Interact`'s `src` IS the
`ItemDrag`) and absent whenever the subject is. A take builds a **new** `GItem`, so no Item identity survives it.
**048.3 DONE, 14/14**: what you can do TO an item is **on the item** — `item:use(mods)` (the `iact` gesture), `:take()`,
`:drop(n)`, `:transfer(n)`: protected, chaining, and each refusing a **stale** handle without sending. `act():item` is
deleted whole and names all five replacements. **Only `:use` takes `mods`** — the other three messages have no modifier
field, because on a real click the keys select the COUNT (`WItem.mousedown`), so `n` IS the modifier.
**048.4 DONE, 11/11 + 11/11**: `hafen.world():place(p, angle, button, mods)` and `:select(p1, p2, mods)` — the world's
first protected verbs, `place` now three lines from the `snapPlace`/`snapAngle` that prepare its arguments. Both chain;
`moveClickCoord` died with them. The lasting lesson is the SUITE's: a wire recorder must identify its OWN sends —
`place`/`sel` are messages a *player* sends too, so "the last two recorded" asserted against a building placed by hand.
The run **clears** the buffer and asserts an **exact count**; a manual line must not ask for a fixture that pollutes it.

**[`047-flowermenu`](047-flowermenu/) is CLOSED, 3 of 3** — `hafen.flowermenu()` **is** the open radial menu: unprotected
`:list()`/`:count()`/`:gob()` · protected `:select(label|n)`/`:cancel()` · `FlowerMenuOpened`/`FlowerMenuClosed`. The reads
hand back bare **strings** in ring order and answer `{}`/`0`/`nil` with no menu up; the finder moved out of `ActApi`
(**D-103**). *Every Opened is followed by exactly one Closed* is kept **structurally** (**D-212**) — a weak map keyed on
the menu, all three ending doors calling one `closed()` — with Opened at the **END** of `added()`, the only complete
moment; the write half drives `FlowerMenu.choose(Petal)` and, unlike the reads, **REFUSES** (**D-213**, gate before
argument check). **`:gob()` is a CORRELATION** (**D-214**): a press records `(gob, UI.lcc)`. 26/26 · 19/19 · 15/15.

**[`046-gob-scale`](046-gob-scale/) is CLOSED, 1 of 1** — a **native** gob answers `:scale`: client-local, purely visual, in place, and the first client-local write on a read-only handle. A `GobScale extends GAttrib implements Gob.SetupMod` propagates through `Gob.ctick`'s per-tick `GobState` compare with **zero `haven` edits**, and `Location.scale(k)` is minted once per VALUE — the mechanism, not an optimisation: `Location` has no `equals` (**D-210**). Writing `1` removes the attrib and a stopped addon leaves nothing distorted (`UiApi.teardownGobScales`). Validation is the one divergence from the vr siblings (**D-211**): they clamp, a direct argument refuses `0`/negative/non-number.

**[`045-durable-places`](045-durable-places/) is CLOSED, 3 of 3** — a thing you stand at a **point** is at that point tomorrow, and after a cave. A free `hafen.vr()` entity's place is its **durable anchor** (grid id + the offset inside it) and the session coordinate a derived cache, because that space is re-based whenever the map is dropped: `:info()` is the place and `:x()` this session's answer (**D-207**); a place with no durable form is refused at both doors, one this session cannot **locate** is legal (**D-208**), and **the second event** (**D-209**, at `MiniMap.tick`) is the only edit.

**[`044-spatial-ui`](044-spatial-ui/) is CLOSED, 9 of 9**: `hafen.vr():widget()` — a Widget, yours or the client's own,
standing as a quad under the one rule **transparency**, which is why its surface is a real UI **root** it is reparented
into (`cupboard` the example). Along the way: a thing at a POINT is in the scene only while the ground under it is DRAWN
(**D-206**); an upward walk out of a standing panel crosses to **the record** (**D-204**); a window **announces its
removal before it unlinks** (**D-205**); culling and input are the four projected corners (**D-203**/**D-195**).
**A 040.10 defect is still NOT fixed**: `dropdown:size(w, h)` leaves its drop arrow outside its box. And
[`043-vr-namespace`](043-vr-namespace/) **closed 5/5** before it — pure reorganization: `hafen.vr()` absorbed
`hafen.ghost()`/`hafen.render()`, made the **anchor an argument**, emptied `gob:overlay()` of the world, and turned `:billboard(b)` into **`:facing(mode)`** (**D-184**–**D-190**).

**Before that**, all DONE (detail in each `NNN-` folder, one-line summaries in `FEATURES.md`): `042-event-driven-reads` (the addon layer stopped **polling the widget tree every frame** to synthesise its `*Changed`/`*Added` events — 12 sites wired onto the four moments the client already announces a change at, then the poll stage **deleted whole** on six core taps; D-178..D-182, of which **D-181 supersedes D-091**), `041-unified-events` (one verb for every notification — `X:on(key, fn)` → a `Sub`, `hafen.hook()` deleted whole), `040-ui-controls` (18 of the client's own controls reach Lua, one builder per role), `039-uniform-api` (one grammar for 33 sections, one `position()`, the OOP migration finished), `038-gob-overlays` (`gob:overlay()` — the engine's own word for a thing attached to a gob), `037-map-database` (the RECORDED map on disk beside the live world — segments, grids, markers, masks, minimap drawings), `036-ui-layout` (position/size/anchor in the sheet, and a whole theme as a data file), `035-ui-chrome` (the sheet learns to DRAW), `034-ui-stylesheet-tree` (a tree key says WHICH widgets), `033-ui-stylesheet` (ONE table says what the client looks like), `032-replace-verb` (replacement is a verb on the entity; the `UI.NewWidget` core seam deleted), `031-window-lifecycle` (hiding a native window takes its toggle), `030-ui-selectors` (a tiny CSS-shaped grammar parsed once into a predicate), `029-widget-oop` (three objects for one widget became ONE interned entity), `028-asset-loader` (one loader for an addon's own files), `027-meters-oop`, `026-text-cache` (130 → 220-240 FPS on the harness), `025-buffs-oop`, `024-audio-oop` (the Track section was built to spec and then CUT — this server sends no MIDI), `023-menugrid-oop`, `022-actionbar-set`, `021-actionbar-oop`, `020-kin-oop`, `019-profiling`, `018-client-options`, `017-gob-oop`, and 001–016.

## Engine & runtime
- **Engine**: LuaJ embedded (`src/io/brodgar/addon/`); per-addon sandboxed envs (D-017), instruction watchdog + soft per-tick CPU budget with auto-disable (D-018). **Loading/lifecycle**: `manifest.json` discovery from `addons/`; `:reload` rebuilds the addon layer only (D-005); enabled set persisted (D-006). Console `:lua` / `:addons` / `:reload`; AddOns panel (toggle/status, and the manifest `description` as the row's tooltip). **Event bus** `hafen.event():on` (Load/EnterWorld/Update/Disable, GobAdded/Removed (a Gob), MeterAdded/Removed/Changed (a Meter), Buff*/Fep/Study/Actionbar/Equip/Markers Changed, FlowerMenuOpened/Closed, GhostClicked…) — error-isolated, UI-thread-marshalled; timers `hafen.timer():after/:every`. Container item events are **not** on the bus — they are per-widget (`w:on("ItemAdded"/"ItemRemoved"/"Destroy", fn)`). **Store**: `hafen.store():get(name)` JSON saved-vars, per-char + account (D-023), handing back the LIVE table.

## The `hafen.*` surface — the docs tier IS the contract

- **One grammar** (039 §2 · `docs/addons/api/conventions.md`): a section is CALLED and is a per-addon singleton, everything after it is a colon verb, **arity is the verb**, a set is a **collection**, every read hands back a live interned object with `:info()` as the only snapshot, an explicit `nil` raises except where a page documents a meaning for it, a builder is constructed bare and configured by chained setters, a place is a **Position**, and every retired spelling **throws naming its replacement**. Every notification is `X:on(key, fn)` → a `Sub`, `sub:off()` ends it (041); the per-verb surface is `docs/addons/api/` and is deliberately not copied here.
- **Protected tier** (the adjective *gated* is being retired with 048, D-215): the write verbs, behind the per-addon `actions` permission with enable-time consent and no global switch (D-027/D-028); `hafen.http` carries its own `network` host allowlist. Everything else observes, or writes client-local only and says so on its page.
- **Example addons** (14, installed, dormant until asked): frozen `hello`, `bags`, `theme`, `atlas`, `stockfilter`, `planner`, `cupboard`, `tagger`, `widgetstack`, `profiler`, `optionstest`, `walker`, `netdemo`, `hogtest` — each described in `docs/addons/examples.md`, each carrying one accurate paragraph in its manifest (D-142).
- **Testing**: one self-checking suite per task, run by hand as `:t<NNN>-<X>` (`specs/addons/TESTING.md`). Lives in `addons/<NNN>-<feature>.<X>/` while in flight; `/end` archives it to `specs/addons/<NNN>-<feature>/addons/<NNN>-<feature>.<X>/` once closed (D-183), so only the example addons and frozen `hello` stay loaded at login. The regression is never run in practice — each task's own suite carries forward whatever it depends on (`TESTING.md`).
