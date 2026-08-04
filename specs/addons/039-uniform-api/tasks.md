# 039-uniform-api — Tasks

> **Every task runs in its own context.** Each entry below is therefore self-contained: what it ships,
> which `API.md` rows it owns, the exact files to read, what its suite must prove, and the gotchas that
> apply *to it*. `/implement` reads the task's own **Context** line and nothing else.
>
> **Read first, always, in every task**: `specs/addons/AREA.md` · `specs/addons/TESTING.md` ·
> **`specs/docs/design/style-guide.md`** (the page standard — §9 size, §10 mechanics, §11 what never
> appears, **§12 the six checks**) and `specs/docs/decisions/docs-standard.md` (D-001..D-013) ·
> `specs/addons/039-uniform-api/spec.md` §2 (the grammar) · the rows this task owns in
> `specs/addons/039-uniform-api/API.md`.
>
> **Every task, without exception, does all five:**
> 1. **Cuts** its rows — the old spelling **throws naming the new one** (§2.10), never reads as `nil`.
> 2. **Ports** every call site it breaks: the 21 suites, the 11 example addons, frozen `hello`.
> 3. **Writes its own pages** under `docs/addons/**` as it lands (030.4 — the close does the sweep,
>    not the sections) — **to area `docs`'s standard, and running its §12 checks in the same task**:
>    links and anchors (falsified in both directions), `wc -l` ≤ 300, no em dash in a heading, the
>    retired-name grep list, and every `hafen.*` name written verified to exist in `src/`. A page that
>    does not meet the standard when the task ends is a page a later docs review has to rewrite, which
>    is the waste this rule exists to prevent.
> 4. **Ships its suite** `addons/039-uniform-api.<N>/`, command `:t039-<N>`, per `TESTING.md`.
> 5. **Ticks the `API.md` rows it covered**, so 039.16 can prove nothing was missed.
>
> **Docs gotchas that apply to every task** (from area `docs`): a page **never** mentions a task or
> feature number, a decision id, a `specs/` path, a `src/` path or class, or a measured figure — *a
> reader of `docs/` cannot tell that `specs/` exists* (style guide §11). A reference page's verbs are a
> **table**; a `###` heading is only for a verb that needs prose, and it carries its parameter list
> without `[ ]` (D-006/D-007). A client-local write group carries `(ungated)` (D-010). A page **names**
> its example addon; only `examples.md` links it (D-009). `docs/` never links `specs/` (D-005).
>
> **Universal gotchas** (they apply everywhere; a task repeats only the ones that bite it hardest):
> Java is `source/target 1.8` · `rm -rf build/classes` before the final build, because `ant` is
> incremental and hides a moved symbol · a Java change needs a **full client restart** · `hafen.log` is
> **ASCII-only** in runtime strings · never name a Java helper `get`/`set`/`type`/`len`/`call` inside an
> anonymous `LuaValue` subclass (029.1 — it shadows the inherited member) · falsify with a **file copy**,
> never `git checkout`.

---

## 039.1 — The machinery, and the eight verb-only sections ✅ DONE

**Depends on:** nothing. This is the foundation every other task consumes.

**Ships**
- `Section.java` — the per-addon **singleton** section object (userdata + metatable), minted in
  `installHafen`, handed back by identity. `hafen.x() == hafen.x()`.
- `LuaCollection.java` — §2.3's collection type: `:list(f) :count(f) :get(k) :find(f) :add(…)
  :remove(x)`, **userdata, not indexable**.
- `Retired.java` — the old-name → message table, and the `__index` on every section that **throws**
  naming the replacement. Seeded with every row this task cuts.
- The `nil` discipline helper (§2.9): an explicit `nil` throws unless the verb documents a meaning.
- Applied end to end to the **eight sections that carry no entities**:
  `hafen.time() hafen.slash() hafen.json() hafen.http() hafen.hook() hafen.timer() hafen.log()` and
  `hafen.events` → **`hafen.event()`** (the first of the three plural renames).
- `hafen.log():write(msg)` — **641 Lua sites**, the largest single port in the feature.
- `http`'s `opts.headers`/`opts.timeout` become setters on the request object (R4).
- `docs/addons/api/conventions.md` rewritten as the statement of **§2** — the grammar every later
  feature follows — including the value-vs-arguments boundary in D-092's form.

**API.md rows owned:** `hafen.act`? no — only `event`, `hook`, `http`, `json`, `log`, `slash`, `time`,
`timer`, plus the "Section names" row for `events` → `event`.

**Context:** `spec.md` §§2.1–2.3, 2.8–2.12 · `API.md` (the eight section tables + "Conventions used
below") · `src/io/brodgar/addon/Sandbox.java` (`installHafen`) · `HookApi.java`, `HttpApi.java`,
`Json.java`, `StoreApi.java` (for how a section is mounted today) · `docs/addons/api/conventions.md`,
`time.md`, `slash.md`, `json.md`, `http.md`, `hook.md`, `timer.md`, `log.md`, `events.md` ·
`specs/addons/learnings/luaj-bridge.md` (grep `narg`, `callable`, `userdata`) ·
`020-kin-oop/` (the D-044/D-045 entity mechanism this generalises).

**Suite must prove**
- `hafen.time() == hafen.time()` — the singleton, asserted by identity, for all eight.
- Every retired dotted verb **throws**, and the message **names its replacement** (not just "nil").
- A collection is **not indexable**: `#coll` and `coll[1]` are refused; `coll:list()` is the array.
- The `nil` discipline **both ways**: `f(nil)` throws *and* `local x = nil; f(x)` throws — and the
  documented hole is asserted honestly, `f(g())` with `g` returning nothing reads instead (§4.1 of
  `plan.md`).
- `hafen.log():write("…")` reaches the console; `hafen.log("…")` throws naming it.

**Gotchas for this task**
- **028.3 is being reversed.** `learnings/luaj-bridge.md` documents an explicit `nil` as *the collection
  form* on a callable namespace. That contract dies with the callable namespaces; the learning is
  amended at `/end`, not silently left to mislead.
- `narg()` separates `f()` from `f(x)` reliably but **not** `f()` from `f(g())` when `g` returns
  nothing. Document the limit on `conventions.md`; assert it rather than pretending it is exact.
- Keep `hafen.<section>` a **callable table**, not a bare function — that is what makes the refusal
  hang off a field read (017), and `pcall` over it keeps working (028.1).
- `hafen.log` is the busiest verb in the API; the port is mechanical but touches nearly every file
  under `addons/`. Do it with a scripted rewrite and **verify by grep count**, not by eye.

---

## 039.2 — The Position type, and `hafen.world()` ✅ DONE

**Depends on:** 039.1 (section object, collection, refusal).

**Ships**
- `LuaPosition.java` — §2.7's **value object** (not interned): `:x() :y() :offset(dx,dy) :distance(o)
  :tileCoord() :durable() :info()`. `:info()` is `{gridId, x, y}` — the durable form, whose `x,y` are
  the **within-grid** offset and therefore *not* the same numbers as `:x()`/`:y()`.
- **Durability is EXPLORED, not loaded**, via the two-step lookup: `MCache` for a streamed grid,
  otherwise session coord → segment coord via `sessloc` → the recorded grid there.
- **`Json` + `StoreApi` marshalling** — a Position serialises to `{gridId, x, y}` and reconstructs on
  read. Non-optional: `Json.java:31` degrades userdata to a quoted `tostring` in *forgiving* mode, which
  is the mode the store uses, so without this a Position persists as **garbage, silently**.
- `hafen.world()` — the LIVE section: `:gob()` (the read-only Gob collection: `:get(id) :list(f)
  :count(f) :find(f) :nearest(f) :within(r,f)`), `:tile(p) :height(p) :grid():at(p)`,
  `:position(x,y)` / `:position(info)` constructors, `:screenToWorld(sx,sy,fn)` handing the callback a
  Position, `:snapPlace(p, fine)`, `:snapAngle(a, fine)`, `:tileToWorld` / `:tileToGrid`.
- **`hafen.gob` is DELETED** into `hafen.world():gob():get(id)`, which stays **never nil**.
- **`hafen.world.placeGrid()` / `:placeAngle()` are CUT** as duplicates of
  `options():interface():posGran()`/`:angGran()` — the same `MapView.plobpgran`/`plobagran` fields
  through two doors, with the units disagreeing (raw vs degrees).
- Gob's own one-liners: `gob:pos()` → **`:position()`**, `gob:isplayer()` → **`:isPlayer()`**.
- `hafen.act()` moves with it, because its four spatial verbs take Positions: `moveTo(p)`,
  `useItemOn(p, mods)`, `place(p, angle, button, mods)`, `select(p1, p2, mods)`.

**Context:** `spec.md` §2.7 (Position), §3.3 (the `hafen.gob` deletion) · `API.md`
(`hafen.act`, `hafen.world`, `hafen.gob`, "The Position type", "What is NOT a Position") ·
`src/io/brodgar/addon/WorldApi.java`, `ActApi.java`, `LuaGob.java`, `MapApi.java` (`gridWorldUL`,
`sessloc` — the durability path), `Json.java`, `StoreApi.java` · `src/haven/MCache.java`
(`tilesz`, `cmaps`, `Grid.id`), `src/haven/MapFile.java` (`GridInfo`, `merge`) ·
`docs/addons/api/world.md`, `gob.md`, `act.md`, `conventions.md` (the coordinates section) ·
`specs/codebase/mapfile.md`, `minimap.md` (`sessloc`, `tryLock`) · `037-map-database/` (D-095, D-096).

**Suite must prove**
- **`p:offset(0, 1100)` from a point near a grid edge lands in the NEXT grid** — `:info().gridId`
  differs. This is the check that proves the engine owns the arithmetic, and it is the feature's central
  claim in one line.
- **A Position round-trips through `hafen.store`**: save, `:reload`, read back, and it resolves to the
  same tile. Without the marshalling this fails as a quoted `tostring`.
- **`:durable()` is true for an explored grid that is NOT streamed in** — the `sessloc` path, not the
  `MCache` one. Park a Position, walk away until the grid unloads, re-read.
- `hafen.world():gob():get(<unknown id>)` is **never nil** and `:exists()` is false.
- `hafen.gob`, `hafen.world.gridPos`, `fromGridPos`, `placeGrid`, `placeAngle` all **throw naming their
  replacement**; `options():interface():posGran()` still answers.
- A `GobAdded`/`GobRemoved` handler still receives a live Gob; a removed one answers `:id()` with
  `:exists()` false.
- **Allocation**: `gob:position()` in a draw callback measured through `hafen.client():profiling()`,
  compared against the `{x,y}` table it replaces. Report the number; do not assume parity.

**Gotchas for this task**
- A Position is a **value**, so it is *not* interned — do not reach for the weak intern cache. Its
  identity is its numbers.
- `fromGridPos` returns nil when the grid is not loaded (`WorldApi:227`); the new lookup must fall
  through to the database rather than inheriting that nil.
- A **merge** re-bases segment coords and every marker inside (`MapFile:1525`), which is why the durable
  form keys on the **server's grid id** and never on a segment coord (D-096).
- `hafen.world():gob()` is a **read-only collection** — it has no `:add`/`:remove`, and that is fine
  (§2.3: `hafen.buff()`, `hafen.meter()`, `hafen.actionbar()` are read-only too).

---

## 039.3 — `gob:overlay()` as a collection, and the Overlay builder ✅ DONE

**Depends on:** 039.1, 039.2 (Position — a world-space overlay's offset is one).

**Ships**
- `gob:overlay()` → the collection: `:list()` (yours first, then the game's), `:get(key)`,
  `:add(key)` + chained setters, `:remove(key)`. The four-arity `gob:overlay(key, spec)` is **CUT**.
- The overlay **spec table becomes setters** on the Overlay `:add` returns (R4): `:draw(fn) :text(s)
  :image(a) :model(a) :ghost(res) :color(c) :offset(…) :scale(k) :alpha(a) :tint(c) :billboard(b)
  :spawnData(sdt)` — `sdt` expanded per N1.
- `ov:pos()` → **`:position()`**; `ov:tint(nil)` **stays** ("no tint" is a real value, §2.9).
- `GobOverlayAdded`/`GobOverlayRemoved` keep their payload shape.

**Context:** `spec.md` §2.3 (collections), §2.5 (builders), §2.9 (`nil` means none) · `API.md` (the Gob
and Overlay entity sections) · `src/io/brodgar/addon/LuaGob.java`, `LuaGobOverlay.java`,
`FollowMoving.java`, `GhostGob.java` · `docs/addons/api/gob.md`, `guides/custom-ui.md` ·
`038-gob-overlays/` **in full** — this task restructures that feature's whole surface, and D-100
(the state belongs on the THING), D-102 (a derived thing's end rides its source's event), D-104/105/106
(the events) all still hold · `specs/codebase/state.md`.

**Suite must prove**
- The four old arities all **throw naming the collection verbs**.
- `:add(k)` twice on one key is a **replace**, firing removal *and* add (D-105), and the read collapses
  on the key.
- **An overlay still dies with its gob** — 038's central claim, re-asserted here because the shape moved:
  park overlays, despawn, and check all three read bare *and come back bare*.
- The game's own overlays still read as `native = true`, read-only, and `ov:count()` still publishes the
  multiplicity (D-101).
- `gob:info().overlays` is **absent, not empty**, when the gob carries none — 038.4's only in-game
  defect, and it is a docs claim as much as a code one.

**Gotchas for this task**
- 038's suites are the densest regression in the area; **`038-gob-overlays.4` is 376 lines** and will
  need the most porting of any single addon file.
- A world-space overlay's visual is its own client gob and `OCache.remove` does **not** call `dispose()`
  (D-102) — the teardown path must survive the restructuring unchanged.
- The queue is drained **one frame's worth, never to empty** (D-106): a handler that re-attaches must
  not hang the engine loop.

---

## 039.4 — `hafen.map()`, and the ONE Grid entity ✅ DONE

**Depends on:** 039.1, 039.2 (Position, and `hafen.world():grid()` — this task completes the other door).

**Ships**
- `hafen.map()` with five collections: `:segment()` (`:current() :get(id) :list()`), `:grid()`
  (`:get(id)`), `:marker()` (`:list(f) :nearest(f) :add(name, p) :remove(m)`), `:icon()`
  (`:get(res) :list(f)`), `:overlay()` (`:get(tag) :list()`).
- **The unified Grid entity** — `hafen.world():grid():at(p)` and `hafen.map():grid():get(id)` hand back
  **the same object**, because both halves already key on the same server-published grid id
  (`WorldApi:175` publishes `MCache.Grid.id`; `MapApi:130` interns on the same `long`). It gains
  **`:live()`** (streamed right now?) beside `:exists()` (in the database?).
- Grid renames: `:sc()` → `:segmentCoord()`, `:pos()` → `:position()`, `:mtime()` → `:modified()`,
  `:image(lvl)` → `:image(level)`, `grid:overlays()` → `grid:overlay():list()`.
- Marker renames: `:tc()` → `:segmentTile()`, `:dist()` → `:distance()`, `:onmap()` → `:onMap()`,
  and **`:anchor()` is CUT** — `marker:position()` is a Position and *is* the anchor.
- Segment: `seg:grid(sc)`/`seg:grids(area)` → `seg:grid():get(segmentCoord)` / `:list(area)`.
- The display toggles stop pretending to be a boolean setter: `hafen.map():overlay():get(tag)` →
  `:shown() :hold() :release()`, because **D-097** made them a ref-counted *hold*, not a switch.
- `hafen.map.icons`'s **shape split is deleted** — `:get(res)` vs `:list(filter)` says which you meant,
  so the "a string with a `/` is a resource" heuristic (D-093) is no longer needed.

**Context:** `spec.md` §2.3, §2.4 (intern keys) · `API.md` (`hafen.map`, the world/map symmetry section,
the Marker/Segment/Grid/Mask/IconCategory entity block) · `src/io/brodgar/addon/MapApi.java`,
`LuaSegment.java`, `LuaMapGrid.java`, `LuaMarker.java`, `LuaMask.java`, `LuaIconCat.java`,
`MapImages.java`, `WorldApi.java` (the `grid` door) · `src/haven/MapFile.java`,
`src/haven/MiniMap.java` · `docs/addons/api/map/**` (6 pages) · `specs/codebase/mapfile.md`,
`minimap.md`, `world-3d.md` (the ground-overlay multiset) · `037-map-database/` **in full**
(D-093..D-098).

**Suite must prove**
- **`hafen.world():grid():at(p) == hafen.map():grid():get(id)`** for a grid that is both streamed and
  saved — one object, two doors.
- A **streamed-but-unsaved** grid reads `:live()` true, `:exists()` false, and nil for every recorded
  read. A saved-but-unstreamed one is the mirror.
- The hold round trip: take, read `:shown()`, release, and a release with nothing held is inert. Do it
  with **another holder already present** (`mv.enol("cplot")`), because *a ref count with a single owner
  cannot fail the way a ref count fails* (037.3).
- `marker:position():info()` round-trips exactly, and a marker's position lands on the tile the marker
  reports — not its corner (037.2: *a check that quantises its input cannot guard anything finer than
  the quantum*).
- The one write it makes — a pin in the real marker DB — is **undone in the same run with the removal
  asserted**.

**Gotchas for this task**
- **D-095**: a read of a stored world **kicks the load and answers nil**; never blocks, never takes a
  callback. The lock is `tryLock`, never `lock`. The marker list is the documented exception (an empty
  list is a lie a caller cannot tell from "no markers").
- **D-094** twice over: a Segment lives in a `BackCache(5)` and a Grid in a weak `CacheMap`, so both key
  on the **published id**; a `MapFile.Marker` is mutated in place, so Java identity is the only truth.
- The two tag spaces are deliberately opposite (D-072): an unknown **display** tag is refused, an unknown
  **recorded** tag is plain nil.

---

## 039.5 — `hafen.ui()` and the Widget entity ✅ DONE

**Depends on:** 039.1.

**Ships**
- `hafen.ui()` the section — and **the root moves**: `hafen.ui()` was the root widget (030.1, 36 sites)
  and becomes the section; the root is **`hafen.ui():root()`**.
- `hafen.ui(sel)` → `:find(sel)`; `.all(sel)` → `:all(sel)`; `.node(id) .at(x,y) .mouse() .inventory()
  .equipment() .on(sel, ev, fn)` all move onto the section unchanged in meaning.
- Widget entity: `:pos()` → **`:position()`** (and `(x,y)` / `(nil)`), `:size()` unchanged in shape,
  `:rootpos()` → **`:rootPos()`**, `:show()`/`:hide()` **CUT** for `:visible(b)` (R6),
  `:replace()` → **`:replacement()`** with `:replace(v)` installing and `:replace(nil)` undoing.
- **Widget positions are pixels, not Positions** — `w:position()`, `w:rootPos()` and
  `worldToScreen` answer plain `{x, y}` px. Safe now that Position is a *type*: handing
  `w:position()` to `hafen.act():moveTo()` **throws** instead of walking you somewhere wrong.

**Context:** `spec.md` §2.2 (R6, the `w:replace` rename), §2.7 ("What is not a Position"), §2.9 (the
layer undos) · `API.md` (`hafen.ui`, the Widget entity block) · `src/io/brodgar/addon/UiApi.java`,
`LuaWidget.java`, `Selector.java`, `AddonWidget.java` · `docs/addons/api/ui/README.md`, `widget.md`,
`selectors.md`, `native.md`, `replace.md` · `029-widget-oop/`, `030-ui-selectors/`,
`031-window-lifecycle/`, `032-replace-verb/`, `036-ui-layout/` (D-086..D-091) ·
`specs/codebase/widgets.md`, `gameui-windows.md`.

**Suite must prove**
- `hafen.ui():root()` **is** the widget `hafen.ui()` used to hand back, and `hafen.ui()` is now the
  section (`==` against a second call).
- `w:position(nil)` restores **the value the user had** (D-086's substitution, not the addon's), with
  D-089's level semantics — it drops *your* level and re-resolves, it does not reset to stock.
- `w:show()`/`w:hide()` **throw naming `:visible(b)`**.
- Hit-testing follows a move: `hafen.ui():at(x, y)` finds a moved widget at its **new** place and not at
  the corner it used to cover — and the negative probe is the old **top-left + 2**, not the old centre
  (036.1's own test bug).
- Handing `w:position()` to `hafen.act():moveTo()` throws.

**Gotchas for this task**
- A window that **owns its own size** re-packs before the call returns (`Hidewnd.cresize` → `pack()`),
  so `:size(w,h)` reaches it and does not overrule it. **Inert, never an error** (D-084 one level up).
- `GameUI.savewndpos` runs from `dispose()` **and every 60 s from `tick`** — D-086's substitution at all
  eight `Utils.setprefc` sites must survive untouched, or a laid-out HUD snaps once a minute.
- A window's *corner* is not hit-testable (`DefaultDeco.checkhit` owns the caption strip and the content
  area, not the transparent pixels between) — ask about the addon's own content child.

---

## 039.6 — The UI builders: window, widget, overlay

**Depends on:** 039.5.

**Ships**
- `hafen.ui():window()` / `:widget()` / `:overlay()` built **bare** and configured by chained setters
  (R4), replacing the 13-key `opts` table: `:title(s) :parent(w) :position(x,y) :size(w,h) :font(h)
  :onDraw(fn) :onClick(fn) :onClose(fn) :onDrop(fn) :onMouseMove(fn) :onMouseUp(fn) :onTick(fn)
  :onWheel(fn)` — **each with a matching bare read**.
- **Construction-time state**: a bare `:window()` exists for the length of the statement without a size
  or title. Built with the client's own defaults, and it **does not lay out or draw until the tick after
  the statement**, so a half-configured widget never paints. New behaviour — assert it.
- `hafen.ui.window{…}` and `widget{…}`/`overlay{…}` all **throw naming the chained form**.

**Context:** `spec.md` §2.5 (builders) · `API.md` (the Widget builder setter list) ·
`src/io/brodgar/addon/UiApi.java`, `LuaWidget.java`, `AddonWidget.java`, `LuaGOut.java` ·
`docs/addons/api/ui/custom.md`, `drawing.md` · `006-custom-ui/`, `014-ui-extensions/`.

**Suite must prove**
- Every setter returns **self** (`==` the receiver) and every one **reads back bare**.
- A window built bare and configured across several statements **never paints half-configured** —
  measured through a draw counter, not eyeballed.
- The old table form throws for all three constructors.

**Gotchas for this task**
- 47 `window{` sites and 7 `widget{` across the corpus; `profiler` (748 lines) and `widgetstack`
  (451) are the heaviest consumers.
- `hafen.ui.overlay(fn)` took a bare function, not a table — it becomes `:overlay():onDraw(fn)`, so its
  refusal message differs from the other two.

---

## 039.7 — The stylesheet: Sheet and Rule

**Depends on:** 039.5, 039.6.

**Ships**
- `LuaSheet.java` + `LuaRule.java`: `hafen.ui():sheet()` → the addon's Sheet; `sheet:rule(selector)` →
  a **Rule** interned per selector; Rule setters return the Rule; `rule:sheet()` climbs back;
  `sheet:install()` / `sheet:drop()`.
- **`sheet:load(parsedTable)` — the DATA door**, legal under §2.8, because 036.4's headline result was
  `hafen.json.parse(file)` going **straight into** `skin{}` unmapped: a whole theme for zero lines of
  Lua. Without it that capability is lost.
- `w:skin{…}` → **`w:rule()`** (the hand-named cascade level, D-077); the undo is `w:rule():remove()`
  (R7 — **not** a `clear` verb). `w:style()` keeps its meaning: the **resolved** style, a value.
- The sheet key **`pos` becomes `position`** with the verb; `size` and `anchor` unchanged.
- `hafen.ui.skin{…}` throws naming the Sheet — **141 sites**, the single densest port in the feature.

**Context:** `spec.md` §5.4 · `API.md` (the Sheet and Rule block) · `src/io/brodgar/addon/Sheet.java`,
`SkinDeco.java`, `Chrome.java`, `Layout.java`, `FontApi.java`, `FontHandle.java`, `LuaWidget.java` ·
`docs/addons/api/ui/style/**` (6 pages) · `addons/theme/` (`main.lua` **and** `theme.json`) ·
`033-ui-stylesheet/`, `034-ui-stylesheet-tree/`, `035-ui-chrome/`, `036-ui-layout/` (D-072..D-092).

**Suite must prove**
- **`sheet:load(hafen.json():parse(f))` installs `theme.json` unmapped**, with the geometry the file's
  own numbers predict — 036.4's zero-lines-of-Lua claim re-proven under the new shape. This is the
  task's headline.
- A layout-only sheet still **opens no per-widget frame and costs the draw nothing** (D-088): one string
  in two windows keeps **one** text-cache key; the same rule with a `font` in it takes **two** — the
  falsification is built into the run, or "still one key" could only mean the check sees nothing.
- `w:rule():remove()` drops **your** level and re-resolves (D-089), so a rule underneath takes the
  widget back; stock returns only when nothing names that half.
- A rule saying both `position` and `anchor` is **refused**, not resolved by table order (D-090).

**Gotchas for this task**
- The sweep runs **outside** `Sheet`'s own lock — the draw established `ui`→`Sheet.class`, so a resolver
  that gained a side effect puts the side effect at the call site.
- The tick **prunes** records whose widget left the tree: a *rule* makes one record per matching window
  forever, each pinning a widget that will close.
- `Sheet.rulesChanged` always ends in `Fonts.treeActive(…)`, which bumps `gen` unconditionally — so the
  number to assert after a sheet change is **one**, not zero.

---

## 039.8 — `hafen.render()`, `hafen.ghost()`, `hafen.asset()`, `hafen.font()`

**Depends on:** 039.1, 039.2 (Position).

**Ships**
- `hafen.render():sprite()` and `:object()` — collections: `:add(asset)` + chained setters,
  `:list(filter)`, `:remove(x)`. `hafen.render.sprite{…}`/`object{…}` **CUT**.
- `hafen.ghost()` — the section **is** the collection: `:add(res)`, `:list(f)`, `:remove(g)`.
- Entity: `:pos()` + `:move(x,y,a)` **collapse onto `:position()` / `:position(p [, a])`** (R2 — a
  read/write pair on one name); `:show()`/`:hide()` → `:visible(b)` (R6); `:destroy()` → the
  collection's `:remove()` (R7); `g:setRes(res, sdt)` → **`g:res(res, spawnData)`**.
- `hafen.asset():get(path)` / `:list()`; `a:dispose()` **kept** (R7 — it frees a resource *now*, which
  is not a removal from a collection).
- `hafen.font():get(name)`; `h:derive{…}` → `h:derive()` + setters (R4, **43 sites**).

**Context:** `spec.md` §2.5, §5.2, §5.3 · `API.md` (`hafen.render`, `hafen.ghost`, `hafen.asset`,
`hafen.font`, the Sprite/Object/Ghost and Asset/FontHandle entity blocks) ·
`src/io/brodgar/addon/RenderApi.java`, `LuaSprite.java`, `LuaObject.java`, `LuaGhost.java`,
`GhostGob.java`, `MeshSprite.java`, `SpriteQuad.java`, `AssetApi.java`, `LuaImage.java`, `LuaMesh.java`,
`FontApi.java`, `FontHandle.java`, `Gltf.java` · `docs/addons/api/render/**`, `ghost.md`, `asset.md`,
`font.md` · `addons/planner/` (930 lines — the heaviest consumer) · `011-virtual-entities/`,
`012-custom-rendering/`, `028-asset-loader/` (D-012, D-043, D-060).

**Suite must prove**
- A sprite placed at `p` and one placed at `p:offset(11, 0)` are **one tile apart** — the Position
  reaching the world entity core.
- `:destroy()` throws naming the collection's `:remove()`; `a:dispose()` still works and still frees.
- `h:derive()` chained gives the same handle a table did — same family, same size, same cache key.
- The old table constructors all throw.

**Gotchas for this task**
- `planner` (930 lines) both places entities *and* persists them grid-anchored — it is the addon most
  affected by the Position change and must be ported carefully, not mechanically.
- A manual `mesh:dispose()` does **not** break a live object (028's R3b refutation): the object captured
  its samplers at mill time, so what dispose forfeits is the freeing, not the picture.
- Teardown order is load-bearing and typed (028): do not flatten it while moving the constructors.

---

## 039.9 — The six existing entity collections

**Depends on:** 039.1.

**Ships** — mechanically the smallest task in the feature; all six only move from `hafen.x(k)` to
`hafen.x():get(k)`:

| section | after |
|---|---|
| `hafen.kin` | `:list()` `:get(idOrName)` `:find(f)` `:add(secret)`; **`kin:setGroup(g)` → `kin:group(g)`**, `kin:endkin()` → `:endKin()` |
| `hafen.actionbar` | `:list()` (1-based, D-057) `:get(n)` (raw 0-based index); **`slot:set(res)` → `slot:res(name)`** |
| `hafen.menugrid` | `:list()` `:get(key)` `:find(text)` `:roots()`; `pag:isnew()` → `:isNew()` |
| `hafen.sound` | `:list()` `:get(name)` |
| `hafen.buff` | `:list()` `:find(needle)` |
| `hafen.meter` | `:list()` `:find(needle)` |

**Context:** `spec.md` §2.2 (the `get`/`set` collapse), §2.3 · `API.md` (those six section tables and
their entity blocks) · `src/io/brodgar/addon/LuaKin.java`, `LuaSlot.java`, `LuaPagina.java`,
`LuaSound.java`, `LuaBuff.java`, `LuaMeter.java` · `docs/addons/api/kin.md`, `actionbar.md`,
`menugrid.md`, `sound.md`, `buff.md`, `meter.md` · `020-kin-oop/`, `021-actionbar-oop/`,
`022-actionbar-set/`, `023-menugrid-oop/`, `024-audio-oop/`, `025-buffs-oop/`, `027-meters-oop/`
(D-056..D-063).

**Suite must prove**
- `hafen.kin():get(7) == hafen.kin():get(7)` — interning survives the move, on all six.
- The **substring** collections (`buff`, `meter`) answer `:find`, and `:get` is absent or refused —
  a needle is not a key.
- Each gated write still **refuses without the permission**, naming the verb (D-027/D-028).
- `hafen.kin(7)`, `hafen.buff("x")`, `hafen.actionbar(0)` etc. all throw naming `:get`/`:find`.

**Gotchas for this task**
- `hafen.actionbar():list()` builds 144 interned objects per call — cheap and intended (D-057), but do
  not let the collection wrapper add a second allocation on top.
- **`isnumber()` must be tested before `isstring()`** — LuaJ's `isstring()` is true for numbers, so the
  reverse order resolves `kin:get(42)` as the *name* `"42"` (D-056).

---

## 039.10 — `hafen.act()`, `speed`, `store`, `player`, `client` and the options

**Depends on:** 039.1, 039.2 (`act`'s spatial verbs already moved there — this task finishes the rest).

**Ships**
- `hafen.act()`'s remaining six verbs (`enabled clickGob item menu flower raw`).
- `hafen.speed():current()` / `:current(n)` / `:max()` / `:name(n)` — **the `get`/`set` pair collapses**.
- `hafen.store():get(name)` / `:flush()`. **The returned table must be the LIVE persisted table, not a
  copy**, or `hafen.store():get("cfg").foo = 1` silently stops saving.
- `hafen.player()` — unchanged in meaning; `:worldToScreen(p)` takes a Position and answers plain
  `{x, y}` **px**.
- `hafen.client():options()` / `:profiling()` — the colon-on-the-section form dies.
- The **18 option methods** gain the §2.9 nil refusal (they are already `:name()`/`:name(v)`).
- The keybinding registry's **`kb:get(name)`/`kb:set(name, key)` → `kb:key(name)`/`kb:key(name, key)`**
  — the one surviving `get`/`set` pair in the whole API.

**Context:** `spec.md` §2.2, §2.9, §2.12 (gating unchanged) · `API.md` (`hafen.act`, `speed`, `store`,
`player`, `client`, the Options handles block) · `src/io/brodgar/addon/ActApi.java`, `CharApi.java`
(the `speed`/`player` halves), `StoreApi.java`, `ClientOptions.java`, `InterfaceOptions.java`,
`VideoOptions.java`, `AudioOptions.java`, `CameraOptions.java`, `KeybindingsOptions.java`,
`OptionsMethod.java`, `ProfHandle.java` · `docs/addons/api/act.md`, `speed.md`, `store.md`, `player.md`,
`client/**` · `addons/optionstest/` · `010-write-actions/`, `018-client-options/`, `019-profiling/`,
`004-saved-variables/` (D-027, D-028, D-047).

**Suite must prove**
- **`hafen.store():get("cfg").foo = 1` survives a `:reload`** — the live-table assertion, and the one
  that would be silent data loss if it were a copy.
- An option written through `:name(v)` reads back, and `:name(nil)` **throws** (the silent no-op class).
- `kb:key("name")` reads and `kb:key("name", "Ctrl+M")` remaps; the old pair throws.
- The gate still refuses an undeclared addon, naming the verb.

**Gotchas for this task**
- `hafen.store` is a **declared field** today (`hafen.store.cfg.foo = 1`), not a verb — this is the one
  section whose *access pattern* changes, not just its spelling.
- `UI.scalef` is `static final`, read once at class load: nothing can change the UI scale at runtime, so
  do not write an assertion that needs to.
- The profiler is armed by a single master switch and is **zero cost when off** (D-049) — do not add a
  read that costs when disarmed.

---

## 039.11 — OOP: `hafen.char()` and `hafen.study()`

**Depends on:** 039.1 (collections), 039.10 (`hafen.client():profiling()` for the cost check).

**Ships**
- **Attr, Skill, Credo, Experience, Food, StudySlot** — six new interned entities with `:info()`.
- `hafen.char():attr():get(name)` / `:list()`; `:skill():list()` / `:find(name)` / **`:available()`**
  (R8 — was `skillsAvailable()`); `:credo():list()`; `:experience():list()`; `:food()`;
  `:lp()` and `:weight()` stay scalars.
- `hafen.study():slot():list()`; `:summary()` stays a scalar read.
- **`hafen.char.skill(name)` changes return type**: it was a boolean, it is now the Skill (or nil).
  Truthy-compatible with every shipped call site — verify that during the port rather than assuming it.
- `Attr`'s `comp` field → **`:composite()`** (N1).
- Events: **`FepChanged`** carries a Food entity; **`StudyChanged`** carries StudySlot entities.

**Context:** `spec.md` §2.4 (intern keys), §4.1, §4.2, §2.11 (events) · `API.md` (`hafen.char`,
`hafen.study`, the new-entity table) · `src/io/brodgar/addon/CharApi.java` (2,022 lines — the char and
study halves), `AddonManager.java` (the adapter poll + `hasSub` gating) ·
`docs/addons/api/char.md`, `study.md`, `types.md` (Attr, Food, StudySlot, Skill/Credo/Experience) ·
`003-widget-tree-reads/`, `009-gap-subsystems/`, `025-buffs-oop/` and `027-meters-oop/` (**the pattern
this repeats — read one of them in full**) · `specs/codebase/widget-tree-reads.md`.

**Suite must prove**
- Each new entity **interns**: two reads of the same attribute are `==`.
- `:info()` on each hands back exactly the snapshot shape `types.md` documented, so nothing is lost.
- A StudySlot keeps answering after it leaves the window, with `:exists()` false (widget identity,
  §2.4).
- `FepChanged`/`StudyChanged` hand **objects**, not tables — assert `type(payload) == "userdata"`.
- Every old dotted verb throws naming its replacement.

**Gotchas for this task**
- Much character-sheet data streams in a beat **after** `OnEnterWorld` — stage the reads or subscribe,
  and gate any assertion whose precondition is not guaranteed (028.3: *a harness assertion whose
  precondition is not guaranteed reports a fake pass*).
- StudySlot interns on **widget identity**, not a name — the same curiosity can be in two slots.
- `AddonManager.tick`'s adapters poll **whether or not any addon is loaded**; do not make that worse.

---

## 039.12 — OOP: `hafen.party()` and `hafen.fight()` — and 017's regression ends

**Depends on:** 039.1, 039.2 (`gob()` on a member resolves through `hafen.world():gob():get(id)`).

**Ships**
- **PartyMember** — `:id() :position() :color() :leader() :gob() :exists() :info()`;
  `hafen.party():list()` / `:get(gobId)` / `:leader()` (R8).
- **Maneuver, DeckCard, FightSummary** — `hafen.fight():maneuver():list(f)`, `:deck()` (a layout, a
  plain array per R3), `:summary()`, and **`:target()`**.
- **`hafen.party():list()[1]:gob()` and `hafen.fight():target():gob()` resolve to live Gobs** — this is
  the accepted regression from 017 finally closed, and it is the task's headline.
- A party member still has **no name** (the client is never sent one) — the page must keep saying so.

**Context:** `spec.md` §4.3, §4.6 · `API.md` (`hafen.party`, `hafen.fight`, the new-entity table) ·
`src/io/brodgar/addon/CharApi.java` (the party and fight halves), `LuaGob.java` ·
`docs/addons/api/party.md`, `fight.md`, `types.md` (PartyMember, Maneuver/DeckCard/FightSummary) ·
`017-gob-oop/` (**the regression this closes — read its spec**), `009-gap-subsystems/` ·
`specs/codebase/world-reads.md`, `learnings/gap-subsystems.md` (grep `fight`, `party`).

**Suite must prove**
- `hafen.party():list()[1]:gob():exists()` — a live Gob from a party member, which nothing could do
  since 017.
- `hafen.fight():target():gob()` likewise, or an honest `[manual]` if the maintainer is not in combat —
  **and say which**, rather than skipping silently.
- Outside a party, `:list()` is an **empty array** and `:leader()`/`:get(id)` are nil; nothing throws.
- A member's `:position()` is a Position and `:durable()` is true where the ground is explored.

**Gotchas for this task**
- A member's `x`/`y` are the **live** gob position while in view and the last-known one otherwise, and
  **may be absent entirely** — so `:position()` can be nil and the page must say when.
- `hafen.fight():target()` may legitimately have no target; that is nil, not an error.
- This task is the one most likely to need a `[manual]` line for combat. Write it as a **command to
  run**, not prose — 037.4 cost three console errors by pasting English into `:lua`.

---

## 039.13 — OOP: `hafen.quest()`, `hafen.wound()`, `hafen.craft()`

**Depends on:** 039.1.

**Ships**
- **Quest + Condition** — `hafen.quest():list(f) :selected() :get(id)`; Quest carries
  `:id() :title() :conditions() :done() :exists() :info()`. Events `QuestAdded`/`QuestDone` carry the
  Quest object.
- **Wound** — `hafen.wound():list(f)` / `:find(needle)` (the old `has`, **boolean → the Wound**).
  `WoundChanged` carries Wound objects.
- **Craft** — `hafen.craft():current()`; `:make(all)` moves onto the entity.
- **The two remaining plural section renames**: `hafen.quests` → `hafen.quest`,
  `hafen.wounds` → `hafen.wound`.
- **Settles the open question**: `hafen.craft.make()` was a no-op with no craft window open, and
  `hafen.craft():current():make()` indexes `nil`. Either `:current()` hands back an inert entity whose
  `:exists()` is false (the `hafen.kin(<unknown id>)` precedent, D-056) or the page documents the guard.
  **Decide and record it as a decision**, do not leave it implicit.

**Context:** `spec.md` §4.4, §4.5, §4.7 · `API.md` (`hafen.quest`, `hafen.wound`, `hafen.craft`,
"Section names", the new-entity table) · `src/io/brodgar/addon/CharApi.java` ·
`docs/addons/api/quests.md`, `wounds.md`, `craft.md`, `types.md` (Quest/Condition, Wound,
Craft/CraftSpec) · `009-gap-subsystems/`, `learnings/gap-subsystems.md` (grep `quest`, `wound`,
`craft`) · `decisions/actions-permissions.md` (D-027/D-028 — `make` is gated).

**Suite must prove**
- The craft decision, whichever way it went, is **asserted with no craft window open** — that is the
  case that used to be a silent no-op.
- `hafen.wound():find("x")` is truthy-compatible with the old boolean at every shipped call site.
- `hafen.quests`, `hafen.wounds` throw naming the singular.
- The gated `:make()` refuses without the permission, naming itself.

**Gotchas for this task**
- `make` is **gated** — a suite must never fire it (`TESTING.md`: a suite declares no permissions). Test
  that the **gate refuses**; the firing demo belongs in `walker` or a `[manual]` line.
- A quest's conditions are a plain array (R3's second half) — do not mint a collection for them.

---

## 039.14 — OOP: the Item entity

**Depends on:** 039.5 (`widget:items()`), 039.11 (the adapter pattern).

**Ships**
- **`LuaItem`** — `:res() :name() :quality() :handle() :exists() :info()`, replacing the Item snapshot
  at `widget:items()` and `hafen.ui():hand()`. `EquipChanged` carries Item objects.
- **The intern key and staleness rule — the one genuinely open migration.** An item has **no stable
  content id**: it is addressed by its server widget id, which is *reused* when an item moves. So the
  entity has a real lifetime, `:exists()` is real, and **a stale one must refuse its writes rather than
  act on whatever now holds that id**. Settle it as a D-094 decision before writing code.
- `hafen.act():item(item, verb, n)` takes the Item entity (it took the snapshot or a raw handle).

**Context:** `spec.md` §4.8 (**read it — it is the open question**), §2.4 (intern keys) ·
`API.md` (the new-entity table's Item row, `hafen.ui` `:hand()`) ·
`src/io/brodgar/addon/LuaWidget.java` (the container item diff), `ActApi.java`, `CharApi.java`
(the equip adapter) · `docs/addons/api/ui/items.md`, `types.md#item`, `act.md`, `conventions.md`
(the ItemRef paragraph) · `029-widget-oop/` (`:onItemAdded`/`:onItemRemoved`, the per-tick `WItem`
diff — **the lifetime this must respect**) · `ROADMAP.md` ("Item handles & rich item data") ·
`learnings/client-limits.md` (grep `quality` — **check what the client can actually give before
promising `:quality()`**).

**Suite must prove**
- Two reads of the same item are `==`; an item that moved is a **different** entity or reports
  `:exists()` false — assert which, because the answer is the decision.
- A **stale** Item refuses a gated write naming itself, rather than acting on the id's new occupant.
  This is the check the whole task exists for.
- `:info()` hands back exactly the `types.md#item` shape.
- `:onItemAdded`/`:onItemRemoved` hand Item objects.

**Gotchas for this task**
- `learnings/client-limits.md` records what the protocol **cannot** give (typed quality among them) —
  read it before promising `:quality()`, or the page will claim a field the client never sets.
- `hafen.ui():hand()` has **no widget** behind it, so whatever identity rule you pick for a container
  item may not apply to the cursor item. Say so on the page.

---

## 039.15 — The docs sweep and the demolition

**Depends on:** 039.1–039.14 (every section's own pages already landed with it).

**Ships**
- The **cross-cutting pages**, which no single task owns: `docs/addons/api/README.md` (both index
  tables), `conventions.md` (final pass — §2 in full), `types.md` (19 shapes, now `:info()` returns),
  `events.md` (the catalogue and the six re-payloaded events), `docs/addons/README.md`'s "API at a
  glance", `getting-started.md`, `runtime.md`, `examples.md`, and all nine `guides/`.
- **The final demolition** (§4.9) — with the OOP migration complete, delete 017's transitional markers:
  the `design/06-lua-api.md` banner, every `(SUPERSEDED by D-044)` header, and `conventions.md`'s
  "Gob is the only OO section, the rest is flat" paragraph.
- **Area `docs`'s §12, run over the whole tree rather than per task** — this is an *audit against an
  existing standard*, not a fresh review: links and anchors (self-verified against a planted bad path
  **and** a planted bad anchor — an over-reporting checker is the failure mode that actually happened),
  `wc -l` ≤ 300 on every page, headings clean, the retired-name grep list at zero, and every `hafen.*`
  name in the tree found in `src/`.
- A grep pass that no page still says "arity is the verb" of a *namespace*, and no retired name survives
  in prose.
- **Anything the audit finds that is an engine or API defect is FILED to area `addons`, not fixed here**
  — and anything that is a *standard* defect is filed to area `docs`. That boundary is `specs/docs/AREA.md`'s,
  and it is what keeps a docs sweep from silently becoming a code change.

**Context:** `spec.md` §4.9, §9 (acceptance) · `API.md` in full (the coverage table is this task's
checklist) · `docs/addons/**` (74 pages) · **`specs/docs/design/style-guide.md` §12 (the checks),
`specs/docs/design/information-architecture.md` (the target tree and §7's ownership),
`specs/docs/learnings/docs-maintenance.md` (grep `sweep`, `matrix` — how a green sweep still hides a
structural problem)** · `specs/addons/design/06-lua-api.md` ·
`learnings/testing-tooling.md` (grep `link`, `anchor` — the checker recipe; **Python 3.14 is on this
box**, which retired 033.3's "re-derive it in Java") · `learnings/process-method.md` (grep `docs sweep`
— 030.4's rule that the sections are already done and this is the sweep).

**Suite must prove** — this task is mostly textual, so its suite is thin and its verification is the
checker plus grep. What it *does* assert:
- Every code snippet in `conventions.md`, `getting-started.md` and the guides **runs verbatim** — 038.4's
  only in-game defect was a docs snippet, found exactly this way.
- No transitional marker survives (grep, asserted as a count of zero).

**Gotchas for this task**
- **Do not sweep the old design docs.** `learnings/process-method.md`: the docs tier is the one place
  that must be true *now*; sweeping every design doc destroys the record of what was believed when a
  decision was made. Only the 017 markers listed in §4.9 go.
- A docs claim is a claim: 038.4's `gob:info().overlays` bug was the *page* being wrong, not the code.
  Run the snippets, do not read them.

---

## 039.16 — The close

**Depends on:** everything.

**Ships**
- **The example addons audited, not just ported.** All eleven (`atlas bags hogtest netdemo optionstest
  planner profiler tagger theme walker widgetstack`) run green, and each one that *demonstrates* a
  changed shape has its comments rewritten to teach the new grammar — they are documentation demos, not
  tests, and a stale comment in `planner` is a wrong page.
- **Frozen `hello` (2,909 lines)** final pass and version bump. It is frozen but this feature genuinely
  breaks it, which is exactly the case `TESTING.md` allows an edit for.
- **The cost measurement**, and it is categorical (D-099 — *a categorical cost claim is a property of
  the code's SHAPE, never a branch inside the callback*):
  - a section object is the **same object** across calls, so a draw callback calling one allocates
    nothing measurable through `hafen.client():profiling()`;
  - `gob:position()` per frame measured against the `{x, y}` table it replaced — report the number;
  - frame time on an **identical scene** before and after, median of 15 frames.
- **`specs/addons/design/25-uniform-api.md`** — the standing design doc: §2's grammar as the area's
  permanent rule, with supersession notes on `design/06` (the flat API) and `design/22` (selectors).
- **The decisions**: D-107.. in `decisions/architecture-api.md` for the section object, the collection,
  the Position type, the singular rule, the `nil` discipline, the section renames — plus the amendment
  to **028.3** in `learnings/luaj-bridge.md`, whose contract this feature reverses.
- The **full regression**: every `:t0NN-N` command, one at a time, in any order.

**Context:** `spec.md` §6 (cost rules), §7 (the port), §8 (the decisions to record), §9 (acceptance) ·
`API.md`'s coverage table (**the completeness proof — every row ticked**) · `addons/**` ·
`specs/addons/design/06-lua-api.md`, `22-ui-selectors.md` · `specs/addons/DECISIONS.md`,
`decisions/architecture-api.md` · `specs/addons/LEARNINGS.md`, `learnings/luaj-bridge.md`,
`learnings/process-method.md` · `019-profiling/` (how a cost claim is measured) ·
`036-ui-layout/` (036.4 — the categorical-zero pattern and its failure: *a zero is only as good as the
scene it is measured in, and the scene must be built by the check rather than inherited*).

**Suite must prove**
- The **categorical zero**, with the falsification built in: the *other* state of the same addon must be
  able to read non-zero, or the zero is a measurement of a flag rather than of the code.
- Every `API.md` row is ticked and none is orphaned.
- The full regression is green, run one command at a time.

**Gotchas for this task**
- **Build the scene the cost check measures.** 036.4's first run was red because two probe windows whose
  whole job is to draw were still on screen from the step before.
- **A cross-reload count of something the server owns measures the world, not the code** (038.4) — assert
  "we left the natives alone" as the *refusal* that makes touching one impossible.
- `/end` is the only self-driven commit and it lands the whole feature: `src docs addons specs`.
