# 091 — A set is a collection: tasks

Shipped as **one task with one suite**, in the maintainer's own session: twelve rows over one rule,
where splitting them would have left a tree in which some relations were collections and some were not
— which is the defect itself.

- [x] **091 — A set is a collection.** `conventions.md` says a relation whose members are objects is a
      collection; **seventeen** broke it, and four times the two shapes sat one verb apart on one
      object. **Nine become collections** — `w:children()`, `w:items()`, `contents:items()`,
      `q:conditions()`, `pag:children()`, `s:menugrid():roots()`, `seg:markers()` (which is A-082 as
      well, the same conversion said twice), `s:fight():deck()`, `gob:sessions()` — each with its own
      `noGet()`. **Six new types** carry the four reads that handed back arrays of *anonymous tables*:
      `LuaMeterSegment` (so `meter:segment():list()[1]:value()` says which segment it is, and
      `meter:value()`/`:color()` are **deleted**), `LuaCraftSpec` (`:count()` answering `1` where the
      wire said `-1`), `LuaFep`/`LuaFepEntry`/`LuaHunger` (so the live reads nest the way
      `food:info()` always did), and `LuaPetal` (so a ring is a set of petals, not of caption strings).
      **Three collections stop answering a different question**: `s:speed():list()` is all four with
      `:available(f)` as the partition, `hafen.font():list()` is the four built-ins always, and
      `hafen.sound()` does not enumerate — `:playing(f)` does. **Two degraded payloads** hand back what
      the API already has, and **`s:kin():add()` returns nothing**, so the mistake fails at the
      assignment. Eight `Retired` rows for the five deleted reads and the three renamed ones.
      *Its suite* asserts the one claim every conversion makes, as a helper it runs over each: the
      quartet answers, `#coll` is **refused**, and `#list() == :count()`. Then the objects: a Segment's
      colour is **keyed** and `[1]` is nil, a CraftSpec's `:count()` is `1` where the wire says `-1`, a
      tool answers `nil` to both `:count()` and `:optional()`, and `fep:cap()` equals
      `food:info().fep.cap` — the two shapes agreeing, which is the row's whole point. Then the ten
      deleted reads raise, each naming what says which part it meant. Then the three that stay arrays
      still take `#`.
      `[manual]`: two — a radial menu open, and a crafting recipe open.
      *Audit*: **A-073** (`audit/04-collections-and-queries.md`) · **A-074** (same) · **A-075**
      (`ns-meter.md` F1) · **A-076** (`ns-craft.md` F2) · **A-077** (`ns-char.md` F4) · **A-078**
      (`ns-flowermenu.md` F1, F2) · **A-079** (`ns-speed.md` F2) · **A-080** (`ns-font.md` F2) ·
      **A-081** (`ns-sound.md` F1) · **A-082** (`ns-map.md` F3) · **A-083** (`ns-event.md` F1) ·
      **A-084** (`ns-kin.md` F4).

## Result

`:t091` — **6 pass, 0 fail, 2 manual**, both manuals confirmed. Clean build. Nineteen pages updated.

Twelve rows ticked and struck; the open count went **45 → 33**. Two rows carry a correction to the
audit's own count, found while doing the work:

- **A-073** named ten relations and **`sheet:rules()` is not one of them** — it does not exist, only
  the snapshot field `sheet:info().rules`. The conversions are nine.
- **A-082** is the same conversion A-073 lists. One change discharged both.

**No row of another feature's block was implemented here.** **No `specs/ROADMAP.md` line is covered.**
