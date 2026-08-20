# 084 — Every refusal names the fix: tasks

- [x] **084.1 — The entity types answer for themselves.** The 34 `Retired.methodIndex` call sites —
      37 types, `VrApi`'s four kinds included — take `Retired.closedIndex(entity, methods, hint)`, the
      hint written beside the methods table it guards. Same lookup, different fallthrough;
      `methodIndex` goes once nothing calls it. The field-read hazard is in `plan.md`.
      *Its suite* reaches every type it can within a bounded `hafen.timer()` window and scores what it
      reached: `x:nosuchverb()` **and** `x.nosuchverb` must both raise, the message must carry the
      receiver's spelling plus three of its real verbs, and a known verb must still answer. One verdict
      line with the count. A sample of existing `Retired` rows must still fire (`gob:isplayer`,
      `item:pos`). Its refusal:
      `hafen.time().clock()` must still say to use a **colon** call, so `Section.self`'s message is not
      swallowed by the new one.
      `[manual]`: with a character in the world and an inventory open, report the count reached out of
      37 — what it misses needs a kin, a buff or an item, and which those are is the answer.

- [x] **084.2 — The twelve that never asked.** `LuaBuff`, `LuaMeter`, `LuaSound`, `LuaIconCat`,
      `LuaMask`, `ProfHandle`, `ProfScope` and the five `*Options` set `LuaValue.INDEX` to the bare
      methods table, so an unknown key reads `nil` **and `Retired` is never consulted**. All twelve take
      `closedIndex` with their own hint — which is what makes 088 and 089 safe, since `buff:duration`,
      `meter:value` and `cat:*` retire there and every one of those rows would land here and do
      nothing.
      *Its suite* asserts the same pair as 084.1 over the twelve, then the point: the message must have
      the `closedIndex` **shape** — `<entity> has no verb 'x' — <hint>` — which only the index that also
      consults `Retired` produces, a bare table producing none. It reaches `s:buff()`, `s:meter()`,
      `hafen.sound():get("sfx/msg")`, `hafen.map():icon():list()[1]`, `hafen.client():profiling()` and
      its `:scope("x")`, and the five options handles, scoring buff and meter over a window.
      `[manual]`: report whether anything installed stopped working — this task changes what a typo does.

- [x] **084.3 — The collection says what it has not got.** `LuaCollection.Source` grows `noGet()` — the
      sentence a keyless collection's missing `:get` carries, so `s:buff():get(x)` says a buff has no
      key and `:find(needle)` is the search — and `missing()` returning `NIL`/`MINT`/`RAISE`, declared
      per collection rather than branched inside `getMember`. `MapApi`'s accessor stops naming a `:get` the
      marker collection has not got. `conventions.md` gains the three-row table; `buff.md`, `meter.md`,
      `study.md` and `map/markers.md` are rewritten **up** to the messages.
      *Its suite* asserts each keyless collection refuses `:get` naming its own entry verb **and that
      the named verb exists on it** — the defect `ROADMAP` (075) records for markers. Then the three
      behaviours: `hafen.session():get("nobodyhere")` is **not nil** with `:exists()` false,
      `hafen.map():icon():get(x)` is nil, `hafen.asset():get("no.png")` raises — and `s:kin()` is the
      one collection that is two, `:get(id)` **minting** where `:get(name)` answers nil, which is what
      `missing()` declares: the promise the KEY carries. Its refusal: `hafen.map():marker(1)` must name
      `:find`/`:nearest` and must **not** contain `":get"`.
      `[manual]`: none.

- [ ] **084.4 — One door for an argument.** `Args` grows `str` and `num` asserting
      `type() == TSTRING`/`TNUMBER` — the idiom `SessionApi.getMember` already carries with its reason —
      and the twelve hand-written type tests collapse onto them, closing both directions:
      **`s:kin():add(1234)` stops sending `"1234"` as a hearth secret**, and `entry:value("42")` and
      `radio:value("061.8")` stop being refused. `item:drop`, `s:world():place` and `widget:send` stop
      coercing a numeric string; the six `checkjstring`/`checkint` sites reach `Args.required` first;
      `hafen.json():parse` says `str`. `conventions.md` gains the exempt families and the `nil` table.
      *Its suite* declares `kin.add` and `world.place`; every check is an argument refusal firing
      **before** anything is sent, which is also what proves the grant. `s:kin():add(1234)` must refuse
      naming a string; `s:world():place("42", 0, 1, 0)` must refuse; an owned
      `hafen.ui():entry():value("42")` must **take** it and read back `"42"`; `item:drop("2")` scores
      over a bounded window. `hafen.timer():after(nil, fn)` must give the house nil message, not a type
      message. Its refusal: `hafen.timer():after(cfg.delay, fn)` with `cfg` empty is that same message,
      since a table field is a passed argument.
      `[manual]`: none.
      <!-- extra context: docs/addons/guides/permissions.md — the suite declares kin.add and world.place -->

- [ ] **084.5 — A mistake is loud.** `LuaCollection.keeps` catches `haven.Loading` alone, so a
      `LuaError` out of a filter predicate propagates while a not-ready read still does not throw out of
      the call containing it. `LuaWidget`'s `on` checks the tree before the key. `SessionApi`'s
      `current(s)` **raises** on a session with no screen of its own — the gap `Sessions.Member.run`
      leaves, not `:exists()` — naming `SessionSelected`, and `session.md`'s cycling example drops from
      eleven lines to four. A font handle carrying a `color`, installed on a client surface, raises
      naming `rule:color(…)`. The store's timer logs the first value it degrades, and still writes.
      *Its suite* proves the predicate both ways, which is the whole of it: one with a typo must
      **raise** out of `:list`, one reading a value merely not ready must **complete** —
      `s:world():gob():list(function(g) return (g:name() or ""):find("x") end)` with a resolving gob in
      the set. Then `w:on("Pressed", fn)` on a widget it destroyed itself must name the tree, not the
      key; a derived font with `:color(255,0,0)` installed through `w:rule():font(h)` must refuse naming
      `rule:color`. Its refusal: `hafen.session():current(hafen.session():get("nobodyhere"))` must still
      raise naming the account — the existing refusal must not be swallowed by the new one.
      `[manual]`: two. While a second character is still **connecting**, run
      `hafen.session():current(hafen.session():get("<that account>"))` from `:lua` and report whether it
      raises naming `SessionSelected` rather than doing nothing. Then put a function into a saved
      variable, wait out the thirty-second timer, and report whether the console names the path it
      degraded.

- [ ] **084.6 — Two widgets, one key.** `VrApi`'s panel kind and `LuaWidget` both spell their receiver
      `widget`, so the twenty-odd `Retired` rows keyed `widget:<verb>` fire on whichever of the two the
      author is holding — and `widget:pos`'s message, *"a widget lives on the screen, so this is not a
      Position"*, is the wrong fix for a panel standing in the world, whose `:position()` **is** a
      Position. The kind takes a receiver of its own, every `widget:` row that means only one of the two
      moves to it, and the rows that mean both are stated for both.
      *Its suite* asserts the two are different sentences, which is the whole of it: `panel:pos()` must
      name a Position and the world, `w:pos()` on a UI widget must go on naming pixels, and an unknown
      verb on each must name its own vocabulary and not the other's. It stands a panel on the player's
      gob within a bounded `hafen.timer()` window and scores what it reached. Its refusal: a row that
      means both — `widget:show` — must still fire on both.
      `[manual]`: none.
      <!-- extra context: src/io/brodgar/addon/VrApi.java (entityHandle), Retired.java (the widget: rows) -->
