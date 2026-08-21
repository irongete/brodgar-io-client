# 085 — The shapes the contract describes: tasks

Five tasks. Task 1 first — it makes the room the other four write into. 2, 3, 4 and 5 are independent
of each other and of everything outside this feature.

Every suite keeps to **≤ 15 output lines**, so group: one verdict line per claim, scored
(`3/3 reached`) rather than one line per handle.

- [x] **085.1 — Where a shape is written down.** Create `docs/addons/api/shapes.md` and move
      `conventions.md` §Coordinates and §Colours into it **verbatim**, with `types.md` §Color folded
      into §Colours. The headings do not change, so the slugs survive: only the page half of each
      anchor moves — `conventions.md#coordinates` → `shapes.md#coordinates`, `#colours` likewise,
      `types.md#color` → `shapes.md#colours`, minding the `../` on pages in subdirectories. Then the
      rules that are true today and written nowhere: the **anonymous-shape table** (place, pixel,
      size, counts, span — with `widget:size()` in the `{x=, y=}` row, which 085.3 moves), the
      **64-bit decimal-string** rule for `gridId` / `grid:id()` / `seg:id()` / a marker's `seg` under
      §Coordinates, and the **`…Fraction` unit** rule, which `buff:duration()`, `slot:cooldown()`,
      `w:severity()` and `slot:time()` then link to instead of correcting the reader in bold.
      `conventions.md` gains the **stylesheet path exception** in §A table is a value (a document
      names a file by path; a Lua call takes the handle — `asset.md`'s two paragraphs shorten to a
      link) and the **third value category** in §Snapshots vs handles (a table the bridge owns and
      you write into, which is what `hafen.store():get(name)` hands back). `types.md` §Position:
      `gridId` is a **string**, not a number — the bridge is right and the page is wrong.
      `api/README.md` gains the `shapes.md` row and its `conventions.md` row drops "coordinates,
      colours". A-031 and A-037 are **struck, not written**; the reasons are in `spec.md`.
      *Its suite* asserts every claim the new page makes, against the running client, because a page
      that describes the code is checkable: `p:info().gridId`, `grid:id()`, `seg:id()` and a marker's
      `:info().seg` are each `type(…) == "string"`; `hafen.map():grid():get(p:info().gridId)` answers
      a Grid whose `:id()` is that same string, which is the round-trip the rule exists for;
      `hafen.store():get(…)` is a plain table that `pairs` walks and that a write into is visible on
      re-read; `hafen.time():dayFraction()` and `:yearFraction()` are numbers in `0..1`;
      `item:durability()` carries `cur`/`max` and `p:tileCoord()` carries `x`/`y`. It scores what it
      reaches over a bounded `hafen.timer()` window — a marker and an item need the world and an
      open inventory. Its refusal: `hafen.store():get("nosuchvariable")` must still raise naming
      `manifest.json`, so the moved pages did not move the store's own vocabulary.
      `[manual]`: none.
      Before handing over, run `DOCUMENTATION.md` §11 over every page touched and report the counts:
      links and anchors (broken must be zero, matching across newlines and grepping link **text** as
      well as targets), `wc -l` on `conventions.md`, `types.md` and `shapes.md`, headings, wording,
      symbols.
      *Inventory*: A-027, A-028, A-030, A-031, A-032, A-033, A-034, A-037 — `/end` ticks and strikes
      them in `audit/INVENTORY.md`, with A-031 and A-037 carrying their strike reason in the row.
      <!-- extra context: docs/addons/api/asset.md (the two stylesheet paragraphs), docs/addons/api/buff.md, meter.md, api/ui/lists.md (the four bolded unit corrections) -->

- [ ] **085.2 — One colour, one shape.** `AddonManager.colorValue` is **deleted** and its three
      readers take `AddonManager.color`: `FontApi`'s `color` property read, `LuaOverlay`'s
      `ov:color()` (its one-line private wrapper goes with it) and `VrApi`'s `e:tint()`. So every
      colour in the API reads back keyed. Going in, **three** parsers each carry the loose branch and
      all three lose it — `AddonManager.colorArg` (`font:color`, `overlay:color`, a vr `:tint`),
      `LuaMarker.colorArg` (`marker:color`) and `LuaRule.colorArg` (`rule:color` and every sheet
      colour) — each raising a message that names the table: *a colour is a table — `{200, 210, 220}`
      or `{r = 200, g = 210, b = 220[, a]}`, 0..255 each, or a colour value read back from the API*.
      `AddonManager.luaColor` is untouched and stays the one place a colour table is read. **The draw
      context is the exception and gains the half it never had**: `LuaGOut`'s `g:color` reads
      `a.arg(2)` with `toint()`, so `g:color(kin:color())` draws black today — give it a table branch
      first and leave the loose components, which are the language of `g:line` and `g:frect`.
      Check `Chrome.seqShape`'s `positional` flag still receives the truth once the loose form is
      gone from `LuaRule.colorArg`. Pages: `shapes.md` §Colours is rewritten to one output shape and
      two input spellings; `overlay.md`, `font.md`, `map/markers.md`, `meter.md`, `ui/drawing.md`,
      `ui/style/README.md`, `ui/style/text.md`, `vr/README.md` and `guides/theming.md` lose every
      `(r, g, b, a)` signature row. `ui/style/chrome.md`, `chat.md` and `keys.md` keep theirs —
      `{color = {r, g, b}}` inside a document is a positional **table** and stays legal.
      *Its suite* stands one overlay on the player's gob (`me:overlay():add("085c")`), derives a font
      (`hafen.font():get("sans"):derive()`) and stands one vr ghost, then asserts the three that were
      positional now answer `.r`, `.g`, `.b`, `.a` **and** that `[1]` is nil on each — both
      directions, since only the pair proves the shape moved rather than widened. A sample of the
      readers that were already keyed (`kin:color()`, `meter:color()`, `marker:color()`) must be
      unchanged. Then the write: `ov:color(200, 210, 220)` must **raise** with a message containing
      `{` and not the old *expects three or four numbers*; `marker:color(200, 210, 220)` and
      `rule:color(200, 210, 220)` must raise too — the two private parsers are the half a change to
      `AddonManager` alone would miss; `ov:color({200, 210, 220})` and
      `ov:color({r = 200, g = 210, b = 220})` must both be taken and read back keyed with those
      numbers; and `ov:color(kin:color())` must be one expression. Its refusal: a font handle already
      **used** must still refuse `h:color(…)` naming `:derive()`, so the ownership guard beside this
      code still fires.
      `[manual]`: one. The suite paints two 12×12 squares in one draw callback, the left with
      `g:color(200, 60, 60)` and the right with `g:color({r = 200, g = 60, b = 60})`. Report whether
      the two squares are the **same** colour — a program cannot read the draw colour back, and a
      black right-hand square is the bug this task closes.
      *Inventory*: A-020, A-021, A-022 — `/end` ticks and strikes them in `audit/INVENTORY.md`.
      <!-- extra context: src/io/brodgar/addon/LuaGOut.java (g:color, and g:text's forgiving contract), Chrome.java (seqShape, parsePalette), addons/profiler/main.lua and addons/clickpath/main.lua (thirty loose g:color calls that must go on working) -->

- [ ] **085.3 — A size is a size.** Add `LuaWidget.whTable(Coord)`, the `{w=, h=}` twin of `xyTable`,
      and point the **four** size readers at it: `LuaWidget`'s `size` verb, the `size` key of
      `LuaWidget`'s `:info()` snapshot, `LuaRule`'s `size` read, and `Sheet`'s two `size` snapshot
      keys. `xyTable` keeps every position, offset, anchor and `rootPos` — it is the pixel-and-place
      helper and it is right for those. `AssetApi.meshHandle`'s `bounds` renames `size` to `extent`,
      keeping `min`, `max` and the `{x, y, z}` shape. **A key this task retires raises**: `Retired`
      grows `field(shape, old, message)` and `closedFields(shape, hint)`, the data-table sibling of
      `closedIndex` — `__index` fires only for keys a table does not carry, so `t.w` reads the real
      field and `t.x` falls through and says *a size is `{w=, h=}`*. One shared `static final`
      metatable per shape, so a `w:size()` in a draw callback costs a field write and no allocation.
      Two shapes get one: `size` (`.x`, `.y`) and `bounds` (`.size`). The write side round-trips:
      `Layout.parseCoord` reads `w`/`h` (or `[1]`/`[2]`) when `prop == "size"`, and an `{x=, y=}`
      there raises naming `w`/`h` — this is the **document** parser too, so the JSON and Lua sheet
      spellings move together; nothing under `docs/` or `addons/` writes `size = {x = …}` today.
      `w:size(w, h)` takes no table and is unaffected, and **which box** each verb speaks for does not
      change. Pages: `ui/widget.md`, `ui/pixels.md` (its two `{x = 100, y = 40}` literals),
      `ui/style/geometry.md`, `asset.md`, `vr/models.md`'s `mdl:bounds().size.z` blockquote, and two rows of
      `shapes.md`'s anonymous-shape table: `widget:size()` moves out of the pixel row into the size row, and
      the `{x=, y=, z=}` row stops naming `mdl:bounds()`'s span `size`. Fix
      `addons/eventstack/main.lua`: `tall(w)` reads `sz.y`, and three call sites read `l:size().x` /
      `le:size().x`.
      *Its suite* ships a minimal `.gltf` beside its `main.lua` so the mesh half needs nothing from
      the world. It builds one owned window, writes `w:size(120, 40)` and asserts the read comes back
      `{w = 120, h = 40}`; that `w:size().x` **raises** with a message naming `.w`, which is the
      claim the whole task rests on; that `w:cell()`, `w:position()` and `w:rootPos()` are unmoved;
      that `w:info().size.w` and `w:info().pos.x` are each right; that `rule:size(rule:size())` is one
      expression and `rule:size({x = 300, y = 200})` raises naming `w`/`h`; that `img:size().w` is
      unchanged; that `mdl:bounds().extent.z` is a number while `mdl:bounds().size` **raises** naming
      `extent`; and that `pairs(w:size())` walks exactly two keys and `hafen.json():write(w:size())`
      still produces `{"w":120,"h":40}` — the metatable must not reach the serialiser. Its refusal:
      `w:size(400)` on a control with no art of its own must still raise naming `widget:size(w, h)`
      and `widget:pack()`.
      `[manual]`: none.
      *Inventory*: A-023, A-024 — `/end` ticks and strikes them in `audit/INVENTORY.md`. A-030's row
      is finished here; it is ticked with 085.1.
      <!-- extra context: src/io/brodgar/addon/Layout.java (parseCoord), Sheet.java (its two size snapshot keys), addons/eventstack/main.lua -->

- [ ] **085.4 — The answer is the thing.** Three reads stop standing for a thing and start being it.
      **`hafen.time():season()`** returns one of four strings. `WorldApi.installTime`'s `season`
      hands back `Astronomy.is` raw; upstream names the seasons nowhere — `haven.Cal` is the only
      reader and uses `is` to index four textures (`Tex[4] dlnd`,
      `gfx/hud/calendar/dayscape-<i>`), and `haven.Glob`'s `"astro"` branch defaults it to `1` when
      the server omits the field — so the bridge carries a four-entry table over `0..3` and anything
      outside answers `nil`, as the whole verb already does before the first astro update.
      **`s:study():summary()`** becomes a live object: new `LuaStudySummary`, a copy of
      `LuaFightSummary`'s shape — a `SAttrWnd.StudyInfo` field, `of(owner, si)` through a per-addon
      weak intern `Cache` on `Addon` keyed by the widget's identity, `:lp()`, `:attention()`,
      `:cost()` off `si.texp` / `si.tw` / `si.tenc`, plus `:exists()`, `:info()`,
      `Retired.closedIndex` and a `__tostring` of `StudySummary()`. `CharApi.studySummary` keeps
      answering `NIL` with no window, which is exactly what `s:fight():summary()` does — matching it
      is the whole point of the row. **`w:severity()`** splits in two: `severity` parses the string
      `LuaWound.severityOf` produced and answers a number or `nil`, and a new `label` answers the
      string unchanged, the way `food:label()` already names the shown half of a pair. Pages:
      `time.md`, `api/README.md`'s `hafen.time` line, `study.md`, `fight.md` (one line saying the two
      summaries are now the same kind), `wound.md` (the `:severity()` row, its blockquote, the
      tutorial line, and a new `:label()` row), `types.md`, and `shapes.md` §Units, whose closing
      sentence names `w:severity()` as the half that is not a number — after this task that is `w:label()`. `docs/client/state.md` gains the
      gotcha on its astronomy row: `Astronomy.is` is `0..3`, it indexes `Cal`'s four textures, `Glob`
      defaults it to `1`, and nothing upstream names the four.
      *Its suite* asserts `hafen.time():season()` is a string and one of the four; that
      `s:study():summary()` is userdata whose `:lp()`, `:attention()` and `:cost()` are numbers,
      whose `:exists()` is true and whose `:info()` carries `lp`, `attention` and `cost`; that
      `sum.lp` **raises** naming `:lp()`, which is what proves it is an object and not a table; that
      `s:study():summary() == s:study():summary()`, so it is interned like every other object; and
      that `w:severity()` is a number or `nil` while `w:label()` is a string, with
      `tonumber(w:label()) == w:severity()` wherever the label parses. It scores study, wounds and
      the clock over a bounded `hafen.timer()` window. Its refusal: `hafen.session():get("nobodyhere")
      :study():summary()` must answer `nil` and **not** raise, exactly as `s:fight():summary()` does
      for a session with no window.
      `[manual]`: two. First, the season. The suite prints the raw index beside the name it chose —
      open the calendar in the top-right corner and report which season it shows, because which index
      carries which name is nowhere in the client's source and the page is written from your answer.
      Second, the wounds. Report whether any wound on the character shows a `:label()` that is not a
      number; "they are all numbers" is a complete answer.
      *Inventory*: A-025, A-026, A-036 — `/end` ticks and strikes them in `audit/INVENTORY.md`.
      <!-- extra context: src/io/brodgar/addon/LuaFightSummary.java (of, Cache, the number() helper), src/haven/Cal.java, src/haven/Glob.java (the "astro" branch), src/haven/SAttrWnd.java (StudyInfo) -->

- [ ] **085.5 — The same handle every time.** `hafen.client():options() == hafen.client():options()`
      is false today: all seven handles are minted per call, against `conventions.md`'s "the object is
      the same one every time" and `Section`'s own "a section called inside a draw callback at 60 fps
      allocates nothing". `Addon` grows seven lazily-built fields beside `subMeta` and `grabMeta` and
      follows their pattern exactly — `if(owner.x != null) return owner.x;`, no lock, because two
      threads racing build two equal values and one wins, which those fields already accept.
      `OptionsHandle.install`'s `options` and `profiling` closures and `OptionsHandle.create`'s six
      sub-closures hand back the held value instead of calling `create()`. The handles are stateless
      proxies over the client's live preference stores, so there is nothing to invalidate and nothing
      to tear down; the field goes with the `Addon`. Second, **`h:size(nil)` and `h:aa(nil)` undo the
      layer**: `FontApi.property`'s write path accepts an explicit `nil` on those two alone, clearing
      `FontHandle.size` / `FontHandle.aa` back to the surface's stock — the meaning `w:size(nil)`
      already carries, on the same word — with the `draft`/`used` ownership guard applying to it like
      any other write. Pages: `client/README.md`'s stateless-proxy paragraph gains the identity
      sentence, `conventions.md`'s nil table gains the two rows, and `font.md`'s `h:size()` /
      `h:aa()` rows say the write and not only the read.
      *Its suite* asserts identity across two calls for all seven —
      `hafen.client():options()`, `:interface()`, `:video()`, `:audio()`, `:camera()`, `:client()`,
      `:keybindings()` and `hafen.client():profiling()` — one scored line, then the sharper form: a
      table keyed by `opts:video()` is found through a **second** `opts:video()`, which is what a
      user actually needs identity for. Then that interning changed no behaviour: `opts:video()
      :fpsLimit()` still answers a number, and a value written back through
      `opts:interface():posGran(n)` still reads back. Then the font: `h:derive():size(12):size(nil)
      :size()` is `nil` and `h:aa(false):aa(nil):aa()` is `nil`, and a **used** handle still refuses
      `h:size(nil)` naming `:derive()`. Its refusal: `opts:audio():masterVolume(2)` must still name
      the `0.0..1.0` range, so the identity change did not swallow the range refusal.
      `[manual]`: one. With the suite's handle still held in a variable, open the client's Options
      window, change the UI scale by hand, close it, and re-run the suite's read line: report whether
      the held handle shows the **new** value. The handle is now the same object every call, and it
      must still be a window onto the live store rather than a copy of it.
      *Inventory*: A-029, A-035 — `/end` ticks and strikes them in `audit/INVENTORY.md`.
      <!-- extra context: src/io/brodgar/addon/Addon.java (subMeta and grabMeta, the lazy-field pattern), Section.java (its identity javadoc), FontHandle.java (size, aa, draft, used) -->

## When the feature closes

`/end` ticks eighteen rows in `audit/INVENTORY.md` — A-020 … A-037 — turning each box to `☒` and
striking the id. Two of them are struck as **not done** and say so in their own row text: A-031
(`types.md` is one subject, and `DOCUMENTATION.md` splits by subject, never by line count) and A-037
(already true — `vr/widgets.md` §It stands with the character you stood it from). Then
`grep -c '^| ☐' audit/INVENTORY.md` must print **83**, and

```bash
comm -23 <(grep -oE 'A-[0-9]{3}' audit/INVENTORY.md | sort -u) <(grep -rhoE 'A-[0-9]{3}' specs/*/spec.md | sort -u)
```

must no longer name any id between A-020 and A-037.
