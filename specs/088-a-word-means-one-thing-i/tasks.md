# 088 — A word means one thing I: tasks

Five tasks, ten rows, all from `audit/INVENTORY.md`'s own 088 block. **They are independent of each
other**; the numbering is the reading order. Nine of the ten are pure renames that `Retired` carries;
A-120 is the one reshape.

Every suite keeps to **≤ 15 output lines**, so group: one verdict line per claim, scored
(`4/4 reached`) rather than one line per verb.

**Tasks 2 to 5 ship ONE suite between them**, at the maintainer's asking: `088-a-word-means-one-thing-i.all`,
run as `:t088`, which carries 088.1's assertions too so the whole feature is provable in one command.
It is five tasks' worth of verdicts — 26 lines, about five per task — and each is one claim scored, with
a group of refusals naming the first spelling that failed rather than only a count.

**Before landing any task, grep `addons/` for the QUOTED spelling as well as the called one.**
`widgetstack` builds paste-ready lines with `('%s:find("%s")'):format(…)` and
`('%s:all("%s")[%d]'):format(…)`, and `eventstack` builds a snippet containing
`':current():ui():find("@' .. r.wclass .. '")'`. Those are string literals: the tool goes on compiling
and hands the user a line that raises when they paste it.

- [x] **088.1 — `:list()` enumerates, and nothing else.** `hafen.ui():list()` is the highest-frequency
      verb in the API meaning two unrelated things, and the wrong guess **draws on the user's screen**:
      `UiApi`'s `list` goes through `Controls.list` to `UiApi.attach`, which calls `u.root.add(rootw)`,
      so an empty control is in the tree before the next statement runs — and the error the author
      eventually sees is `ipairs` on userdata, one line later, naming nothing about listboxes. It
      becomes **`hafen.ui():listbox()`**, the client's own class name, sitting beside `dropdown` and
      `menu`. `VrApi`'s `list` builds a `LuaTable` array over `allEntities(owner)` while every
      `:list()` under it is a collection's, so `hafen.vr():ghost():count()` works and
      `hafen.vr():count()` throws; it becomes **`hafen.vr():entity()`**, a `LuaCollection` over the same
      `allEntities` with the quartet and `:remove(x)`. `hafen.vr()` cannot itself be a collection — it
      holds four kinds and the section switch — which is the row's own reason for a named sub-collection.
      `VrApi`'s `pointer` becomes **`click`**, a verb rather than a noun, keeping its boolean return,
      which is information the caller needs. Three `Retired` rows. Fix `addons/eventstack`, which builds
      two listboxes.
      *Its suite* asserts the refusal fires **before anything is built**, which is the whole severity:
      `hafen.ui():list()` raises naming `:listbox()`, and the count of this addon's own widgets is
      **unchanged** across the call — a retirement that fired from inside the builder would pass a
      message check and still leave a control on screen. Then `hafen.ui():listbox()` builds one that
      answers `:rowHeight(20)` and `:rows{…}`. Then the vr half: `hafen.vr():entity():count()` equals
      the sum of the four per-kind counts, `hafen.vr():entity():list()` is an array, and
      `hafen.vr():list()` raises naming `:entity()`. Then `hafen.vr():click(key, x, y, a)` answers a
      **boolean** and `hafen.vr():pointer(…)` raises. Its refusal: `hafen.ui():listbox(fn)` must still
      raise naming the chained setters, so renaming the builder kept its own arity check.
      `[manual]`: one. Run the suite and report whether **any** control appeared on screen — a listbox
      built by a refused call is the bug this task closes and a program cannot see the screen.
      *Audit*: **A-050** — *"`hafen.ui():list()` → `:listbox()` — today it builds a control and attaches
      it to the screen"* (`audit/ns-ui.md` F1) · **A-057** — *"`hafen.vr():pointer(…)` → `:click(…)`;
      `hafen.vr():list(f)` → `:entity()`, a collection"* (`audit/ns-vr.md` F1, F2).
      **Read those `audit/` pages before starting** — each carries the evidence, the cost written in a
      user's own code, and the replacement the one-line row summarises. `/end` ticks and strikes these
      ids in `audit/INVENTORY.md`, and nothing else in that file is touched.
      <!-- extra context: src/io/brodgar/addon/Controls.java (list), VrApi.java (allEntities and its javadoc on cross-kind verbs), SurfaceInput.java (pointer), addons/eventstack/main.lua (two hafen.ui():list() builders) -->

- [x] **088.2 — The selector language has its own verb.** `:find` takes a **filter** on a collection —
      nil, substring, predicate — and a **selector** on the ui, which is a grammar. So
      `s:kin():find("Bo")` matches by substring while `s:ui():find("Cupboard")` parses as a **role**
      that does not exist and answers `nil`; the author meant `window[title=Cupboard]`, and the failure
      is an empty result rather than a refusal. The second half: "give me all" is `:list()` on a
      collection and `:all()` on the ui. Four closures — `UiApi`'s `find` and `all`, `LuaWidget`'s
      `find` and `all` — become **`match`** and **`matchAll`**. **D3** chose those words: `select` is
      taken twice (`s:world():select`, `s:flowermenu():select`), and a verb named for the selector
      grammar makes `match("Cupboard")` obviously a role. `:matchAll` also closes the `:list`-vs-`:all`
      split, so `:list` enumerates a collection and `:matchAll` runs a selector. Four `Retired` rows,
      **plus the two existing `Retired.uiMoved` keys** for `find` and `all` — the rows telling someone
      who wrote `hafen.ui():find` to use the session's half — which must go on firing and now name
      `s:ui():match`. Fix `addons/widgetstack` (its hot path, its offered lines and its `pcall`) and
      `addons/eventstack` (its generated snippet).
      *Its suite* asserts the two languages are now two verbs, which is the claim: `s:ui():match(sel)`
      answers one widget or `nil` and **raises when the selector matches more than one**, exactly as
      `:find` did; `s:ui():matchAll(sel)` answers an array; `w:match(sel)` and `w:matchAll(sel)` do the
      same inside a subtree. Then a collection's `:find` is untouched: `s:kin():find("Bo")` still
      matches by substring — the half that must not move. Then the four retirements raise naming
      `:match`/`:matchAll`, and `hafen.ui():find(sel)` still raises from the **global** half naming the
      session's, now spelled `s:ui():match`. Its refusal: `s:ui():match("window, button")` — a selector
      matching more than one — must still raise naming `:matchAll`, so the rename kept the ambiguity
      refusal that is the verb's whole contract.
      `[manual]`: none.
      *Audit*: **A-119** — *"`s:ui():find/all` → `:match`/`:matchAll`, and `w:find/all` likewise — the
      verb names the selector language, and `:matchAll` closes the `:list`-vs-`:all` split. Four
      closures, four `Retired` rows (plus the two existing `Retired.uiMoved` keys for `find`/`all`),
      `ui/selectors.md` · `ui/widget.md` · `references.md` · `ui/replace.md`, and both tools"*
      (`audit/ns-ui.md` F4 · **D3**).
      **Read `audit/ns-ui.md` F4 before starting** — it sets out the two candidate spellings and why the
      third option, keeping `:find` and aliasing `:list`, does not fix the ambiguity. `/end` ticks and
      strikes this id.
      <!-- extra context: src/io/brodgar/addon/Retired.java (uiMoved, uiKept — the two existing find/all keys), audit/16-consumer-evidence.md (the widgetstack sites) -->

- [x] **088.3 — A place, a size and a hit test.** `item:cell()` is the inventory grid cell an item sits
      in — a **place** — and `widget:cell()` is a grid control's cell **box**, a size. Both are
      two-number keyed tables and neither is labelled, so `grid:cell(c.w, c.h)` fed from `item:cell()`
      reads `nil, nil` and is **taken**, leaving the grid default-sized. `LuaWidget`'s `cell` becomes
      **`cellSize`**, matching `w:rowHeight(n)`, the verb it sits beside; `LuaItem`'s **keeps the
      word**, a cell in an inventory being a place in the game's own vocabulary, and its snapshot field
      stays `cell`. Then `:at()`: `s:world():grid():at(p)` addresses a member **by a place** while
      `hafen.ui():at(x, y)` and `w:at(x, y)` hit-test the screen for the **deepest** widget under a
      point — a search, not an address. Both hit tests become **`hit(x, y)`**, which pairs with
      `mouse:over()` and leaves `:at(x)` one meaning. `WorldApi`'s `extra.at` is untouched. **`at` is
      also a table key in `Layout`, `Chrome` and `Stock`** — a corner name inside a declarative
      document, not a verb — and a blind package-wide rename breaks every stylesheet anchor. Three
      `Retired` rows. Fix `addons/widgetstack`, which calls `hafen.ui():at(mx, my)` on its hot path and
      reads `w:cell()` in its property table.
      *Its suite* asserts the two `cell`s are now two words and both still answer: an item in an open
      inventory answers `item:cell().x` and `.y`, an owned grid control answers `w:cellSize().w` and
      `.h`, and `w:cell()` raises naming `:cellSize()` while `item:cell()` does **not** — the pair is
      what proves only one moved. Then the hit test: `hafen.ui():hit(x, y)` over a widget the suite
      built answers that widget, `w:hit(x, y)` answers inside it, and both `:at(x, y)` spellings raise.
      Then the word that stayed: `s:world():grid():at(p)` still answers a Grid. Then the anchors:
      a rule written with `anchor = { at = "bottomright" }` still installs — the document key is not a
      verb. Scored over a bounded window: an item needs an open inventory and a grid needs the world.
      Its refusal: `hafen.ui():hit()` with no arguments must raise naming the two numbers, so the
      rename kept its own arity check.
      `[manual]`: none.
      *Audit*: **A-051** — *"`widget:cell()` → `:cellSize()` — today a size wearing the name of a
      place"* (`audit/ns-ui.md` F3) · **A-052** — *"`hafen.ui():at(x,y)` / `w:at(x,y)` → `:hit(x, y)`,
      freeing `:at` for 'address by a place'"* (`audit/ns-world.md` F6).
      **Read those `audit/` pages before starting.** `/end` ticks and strikes these two ids.
      <!-- extra context: src/io/brodgar/addon/Controls.java (cell), Layout.java / Chrome.java / Stock.java (the "at" DOCUMENT key that must not move), addons/widgetstack/main.lua -->

- [x] **088.4 — `:overlay()` means one thing.** The word names four unrelated things: the minimap's
      display switches (`MapApi`'s `section(owner, "overlay", toggles)`), a grid's recorded masks
      (`LuaMapGrid`), a gob's decorations (`LuaOverlay`), and a HUD painter you install (`UiApi`). So
      `hafen.map():overlay():get("claim")` is a switch and `grid:overlay():get("claim")` is a mask —
      both succeed, both return an object, and the objects have different verbs. `MapApi`'s becomes
      **`display()`**; `LuaMapGrid`'s becomes **`mask()`**, already what the object is called (`LuaMask`,
      `mask:covers(c)`); `gob:overlay()` is untouched, being the client's own term and the owner of the
      type page. Then the fourth: `UiApi.newHudOverlay` hands back a `LuaHudOverlay` carrying exactly
      `onDraw`, `destroy` and `exists`, and it becomes a **collection with `gob:overlay()`'s shape** —
      `:add(key)`, `:get(key)`, `:remove(key)`, `:list/count/find(filter)`, with **`:draw(fn)` on the
      member**. `:list()` is the **draw order**, so the `Source.members()` must preserve
      `Addon.hudOverlays`' insertion order rather than sorting by key. `:onDraw(fn)` retires onto
      `:draw(fn)` and `ov:destroy()` onto `hafen.ui():overlay():remove(key)` — **D2** — and
      `LuaCollection.meta` already consults `Retired`, so all three fire. Fix `addons/clickpath` and
      `addons/widgetstack`, which each install a painter through `:onDraw`.
      *Its suite* asserts the word now means one thing, which is the claim: `hafen.ui():overlay()` and
      `gob:overlay()` answer the **same vocabulary** — `:add`, `:get`, `:remove`, `:list`, `:count`,
      `:find` on both — one scored line over the six verbs on the two receivers. Then the HUD half
      works: `:add("a")` and `:add("b")` each answer a member taking `:draw(fn)`, `:get("a")` finds the
      first, `:count()` is 2, `:list()` is `{a, b}` **in that order** (the draw order), and
      `:remove("a")` returns the collection with `:count()` at 1. Then the two map renames:
      `hafen.map():display():get("claim")` answers a toggle and `grid:mask()` answers the masks, while
      `hafen.map():overlay()` and `grid:overlay()` each raise naming their own replacement. Then the
      three retirements on the HUD side: `hafen.ui():overlay():onDraw(fn)` and `ov:destroy()` each
      raise naming `:draw(fn)` and `:remove(key)`. Scored over a bounded window — the masks need the
      map database. Its refusal: `hafen.ui():overlay():add()` with no key must raise naming the key,
      since a keyed collection is the whole point of the reshape.
      `[manual]`: one. The suite paints one HUD member as a small rectangle. Report whether it appears,
      and whether it is gone after the suite's `:remove(key)` — a painter that registers and never
      draws would pass every assertion above.
      *Audit*: **A-054** — *"`hafen.map():overlay()` → `:display()`; `grid:overlay()` → `:mask()`"*
      (`audit/ns-map.md` F4) · **A-120** — *"`hafen.ui():overlay()` takes the shape of `gob:overlay()` —
      a collection of KEYED decorations rather than an anonymous builder … `:onDraw(fn)` retires onto
      `:draw(fn)` and `ov:destroy()` onto `:remove(key)` (**D2**). `:list()` is the draw order"*
      (`audit/ns-map.md` F4 · `LuaHudOverlay`).
      **Read `audit/ns-map.md` F4 before starting.** Note that the finding offers
      `hafen.ui():painter()` and A-120 supersedes it: the maintainer chose to keep the word and give it
      one meaning, so the HUD reads like the world. `/end` ticks and strikes these two ids.
      <!-- extra context: src/io/brodgar/addon/LuaOverlay.java and LuaGob.java (the shape being copied), Addon.java (hudOverlays), addons/clickpath/main.lua and addons/widgetstack/main.lua (both call :onDraw) -->

- [x] **088.5 — Three words freed.** `rule:close(…)` is the close **button**'s art on a window's
      chrome, and `:close()` is the word a reader expects to mean "end this" — which in this API it
      means exactly once, on a Session. It becomes **`closeButton(…)`**, and `Sheet`/`Chrome`'s `close`
      **property key** follows, so a document and a call say the same word. `pag:path()` is an array of
      the categories above an entry while `a:path()` on an asset is one string, a file path — so
      `"menu > " .. pag:path()` fails as *attempt to concatenate a table value*. It becomes
      **`categories()`**, which is `menugrid.md`'s own phrase, leaving `:path()` to mean a file path
      across the API; the **snapshot field `path` stays**, being the client's own shape.
      `ev:sender()` and `ev:target()` are, by `LuaEvent`'s own comment, *"the same widget, named for the
      direction it is on"* — and the direction is already named by which stream you subscribed on. Both
      become **`ev:widget()`**, and both `Kind` hints in the enum (`ACTION`, `MESSAGE`) move with them,
      so the two near-identical tables on `event/streams.md` stop differing by one row. Four `Retired`
      rows. Fix `addons/clickpath` and `addons/eventstack`, which read `ev:sender()` and `ev:target()`.
      *Its suite* asserts each new spelling answers what the old one did and the old one raises — the
      whole of a rename, one scored line over the three: a rule's `:closeButton{…}` installs and
      `w:style()` reads it back, `pag:categories()` answers an array of strings whose concatenation
      works, and `ev:widget()` inside both an action and a message handler answers a Widget. Then
      `rule:close(…)`, `pag:path()`, `ev:sender()` and `ev:target()` each raise naming their own
      replacement. Then the two that did **not** move: `hafen.asset():get("dot.png"):path()` still
      answers a string, and `s:close()` still ends a login — the words this task frees `:path` and
      `:close` **for**. Then the document key: a sheet written with `close = {…}` must be refused
      naming `closeButton`, since a stylesheet is a document and its keys are a closed set. Scored over
      a bounded window — a menu entry needs the action menu open, and a message event needs traffic.
      Its refusal: `ev:widget()` on an event kind that carries no widget must still raise naming that
      kind's own vocabulary, so merging the two verbs did not add one where there was none.
      `[manual]`: one. Open the action menu and report what `pag:categories()` prints for a nested
      entry — the array's order is the breadcrumb's, and only you can put the menu in a nested state.
      *Audit*: **A-053** — *"`rule:close(…)` → `:closeButton(…)`, freeing `:close`"* (`audit/ns-ui.md`
      F6) · **A-055** — *"`pag:path()` → `:categories()`, freeing `:path` for a file path"*
      (`audit/ns-menugrid.md` F2) · **A-056** — *"`ev:sender()` / `ev:target()` → `ev:widget()`"*
      (`audit/ns-event.md` F5).
      **Read those `audit/` pages before starting.** `/end` ticks and strikes these three ids.
      **This task also carries the feature's sweep** (AC8, which no single task owns otherwise):
      confirm every snapshot still carries the client's own field spelling (`Pagina.path`, `Item.cell`)
      with only its live-read column moved, and that the five addons under `addons/` load — report both.
      <!-- extra context: src/io/brodgar/addon/LuaEvent.java (the Kind enum's ACTION/MESSAGE hints), Sheet.java and Chrome.java (the close property key), addons/clickpath/main.lua:134 and addons/eventstack/main.lua:655,663 -->

## When the feature closes

`/end` runs per task and ticks that task's own rows:

| Rows | Ticked by |
|---|---|
| A-050, A-057 | 088.1 |
| A-119 | 088.2 |
| A-051, A-052 | 088.3 |
| A-054, A-120 | 088.4 |
| A-053, A-055, A-056 | 088.5 |

Nothing else in the file is touched. An id never moves. **No row of another feature's block is
implemented here**, and none of these is implemented elsewhere — **087** owns the teardown family and
is told by its own A-048 to skip `ov:destroy()` because this feature replaces it; **089** owns the
character sheet; **094** owns `hafen.ui()` becoming enumerable (A-113), which is what could give
`:list()` a new meaning here later.

With 087 closed the open count stood at **70**, and 088.1 took it to **68**. When the last task closes,
`grep -c '^| ☐' audit/INVENTORY.md` must print **60**, and

```bash
comm -23 <(grep -oE 'A-[0-9]{3}' audit/INVENTORY.md | sort -u) <(grep -rhoE 'A-[0-9]{3}' specs/*/spec.md | sort -u)
```

must no longer name A-050 … A-057, A-119 or A-120.

**No `specs/ROADMAP.md` line is covered by this scope**, and two are adjacent:

- *"an addon can neither select nor hit-test the windows it built itself"* (filed 074) — this feature
  frees `hafen.ui():list()`, and **A-113** (094) is the row that would fill it.
- *"a HUD overlay (`hafen.ui():overlay()`) is an after-draw of the session's own tree, so it paints
  under an addon's own windows"* (filed 074) — 088.4 reshapes that very verb. **The draw-order
  question is not this feature's**: A-120 changes the shape, not the tree it paints in, and the
  ROADMAP line survives the reshape with its spelling updated to `:add(key)`/`:draw(fn)`.
