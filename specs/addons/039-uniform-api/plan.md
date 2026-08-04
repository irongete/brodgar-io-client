# 039-uniform-api — Plan

> Size limits lifted for this feature. **Every task runs in its own context**, so each one in
> `tasks.md` restates its own dependencies, files and gotchas rather than pointing back here. This file
> holds what is true across all of them: the build order and why, the four mechanisms built once, the
> risks measured up front, and what was discarded.
>
> **Read with `spec.md` (the grammar, §2) and `API.md` (every row it generates).** `/implement` reads a
> task's own "Context files" line and nothing else.

## 1. Approach

The feature is one **mechanism** applied 33 times, plus one **new type**, plus a migration that was
already half done. So it builds in that order: the machinery first on sections that carry no entities,
then the type everything spatial depends on, then section by section, then the OOP half, then the sweep.

### 1.1 The build order, and the two constraints that fix it

Two rules from `learnings/process-method.md` decide the slicing, and they pull in opposite directions:

- **"A hard cut is ONE task or it is a broken client" (017).** Deleting a flat table breaks every addon
  the instant it lands, so "Java first, addons and docs next" leaves the client unusable *between*
  tasks. Everything a cut touches — engine, suites, example addons, `hello`, docs — lands together.
- **"The split that works is by verifiability" (017), and "the docs tier can already be complete before
  the docs task runs" (030.4).** Each task is what one in-game round can prove; each writes its own
  reference pages as it lands; the closing tasks do the *sweep*, not the sections.

Together: **one task = one cut that a maintainer can verify in one login, with its ports and its pages
inside it.** Sections are independent tables, so a section's cut is naturally atomic — `hafen.world`
becoming callable does not break `hafen.char`. That is what makes 16 tasks possible instead of one
unreviewable one.

### 1.2 Why this order and not another

| tasks | why here |
|---|---|
| **039.1** machinery + the 8 verb-only sections | Every other task consumes the section object, the Collection type and the refusal. Proving them on sections with no entities keeps the first cut small enough to debug. |
| **039.2** Position + `hafen.world()` | 18 verbs take a Position; nothing spatial can be finalised before it exists, and its constructors live on `hafen.world()`. |
| **039.3–039.8** the entity-bearing sections | Each is one subsystem, each already has its shape decided in `API.md`, each is one login to verify. |
| **039.9–039.10** the sections that are already callable | Mechanically the smallest — they only move from `hafen.x(k)` to `hafen.x():get(k)`. |
| **039.11–039.14** the OOP half | Every one needs its own identity/intern-key decision (D-094), so they cannot be batched. Deliberately after the shape, so each migration lands *into* the final grammar rather than being re-spelled later. |
| **039.15–039.16** the sweep and the close | Cross-cutting pages, index tables, the demolition of 017's transitional markers, the cost measurement and the design doc. |

### 1.3 What every task does, without exception

1. **Cuts** its rows in `API.md` — the old spelling throws naming the new one (§2.10).
2. **Ports** every call site it breaks: the suites, the example addons, frozen `hello`.
3. **Writes its own doc pages** in `docs/addons/api/` as it lands (030.4's rule).
4. **Ships its self-checking suite** `addons/039-uniform-api.<N>/` per `specs/addons/TESTING.md`.
5. **Ticks the rows it covered** in `API.md`, so the close can prove nothing was missed.

## 2. The four mechanisms, built once

### 2.1 The section object (§2.1)

A per-addon **singleton** userdata + metatable, minted in `installHafen` and handed back by identity.
`hafen.world() == hafen.world()` is asserted because a section called inside a draw callback runs 60×/s
and a per-call allocation is D-099's failure exactly.

`hafen.<section>` stays a **callable table** rather than becoming a function — `learnings/luaj-bridge.md`
(017) records that this is what makes a hard cut visible from Lua (`hafen.world.gobs` reads as a field,
which is where the refusal hangs), and (028.1) that `pcall` works over a callable table, so every suite
that wraps a section in `pcall` keeps working.

### 2.2 The Collection type (§2.3)

One Java type parameterised by its backing read, carrying `:list(filter) :count(filter) :get(key)
:find(filter) :add(…) :remove(x)` and only the ones that apply. **Userdata, not a table**, so `==`
and table-key identity come free (`luaj-bridge`, 021.1/030.2).

**Not indexable, and the reason is not the obvious one.** D-057's problem was a 0-keyed *table*
(`LuaTable.len()` ignores `__len`); a collection here is userdata and userdata *does* get `__len`. So
indexing is available and is refused on design grounds — an object that is also a sequence has two ways
to enumerate, which is D-013's dual style.

### 2.3 The Position type (§2.7)

A **value object**, not interned: it holds a durable identity (grid id + within-grid offset) and answers
session-world questions on demand. `:x() :y() :offset(dx,dy) :distance(o) :tileCoord() :durable()
:info()`.

Durability is **explored, not loaded**, and the lookup is two steps — `MCache` for a streamed grid,
otherwise session coord → segment coord via `sessloc` → the recorded grid there. Step 2 is arithmetic
only; `MapApi:487` already says it answers *"for any grid in the player's current segment whether or not
that ground is streamed in right now"*.

### 2.4 The refusal (§2.10)

Every section's metatable carries an `__index` that **throws for every retired name**, generated from
`API.md`'s before-column. Without it a deleted field reads as plain `nil` and fails later as "attempt to
call a nil value" — useless while porting 10,633 lines. The table is data, so it costs one Java map and
is impossible to get partially wrong: a row in `API.md` with no entry in the table is the close's check.

## 3. Files

### 3.1 New Java (`src/io/brodgar/addon/`)

| file | holds |
|---|---|
| `Section.java` | the per-addon singleton base + the retired-name `__index` |
| `LuaCollection.java` | §2.3's collection type |
| `LuaPosition.java` | §2.7's Position value object |
| `Retired.java` | the generated old-name → message table |
| `LuaAttr/LuaSkill/LuaFood/LuaStudySlot/LuaPartyMember/LuaCraft/LuaQuest/LuaWound/LuaManeuver/LuaItem` | the 14 new entities (§4) |
| `LuaSheet.java`, `LuaRule.java` | §5.4's stylesheet objects |

### 3.2 Modified Java

`Sandbox.java` (`installHafen` — all 33 sections), `WorldApi`, `MapApi`, `CharApi`, `UiApi`,
`LuaWidget`, `LuaGob`, `LuaGobOverlay`, `LuaKin`, `LuaSlot`, `LuaBuff`, `LuaMeter`, `LuaPagina`,
`LuaSound`, `LuaIconCat`, `LuaMarker`, `LuaSegment`, `LuaMapGrid`, `LuaMask`, `LuaSprite`, `LuaObject`,
`LuaGhost`, `AssetApi`, `FontApi`, `RenderApi`, `ActApi`, `StoreApi`, `Json`, `Sheet`, `OptionsMethod`
and the five `*Options` classes.

**`Json.java` and `StoreApi.java` are not optional**: `Json:31` degrades a userdata to a quoted
`tostring` in *forgiving* mode — the mode the store uses — so a Position would persist as garbage,
silently. They must marshal `{gridId, x, y}` and reconstruct on read.

### 3.3 Docs, addons, specs

- `docs/addons/api/**` — 74 pages, each landing with its task; `conventions.md` becomes the statement of
  §2; `types.md`'s 19 shapes become `:info()` returns; both index tables at the close.
- `addons/` — 21 suites, 11 examples, frozen `hello` (2,909 lines), plus 16 new suites.
- `specs/addons/` — `design/25-uniform-api.md` (the standing design), `DECISIONS.md` +
  `decisions/architecture-api.md` (D-107…), `LEARNINGS.md` + `learnings/luaj-bridge.md`.
- **Coverage owed**: none foreseen — every subsystem this touches already has a `specs/codebase/` file.
  A task that reads uncovered `haven` source must say so and pay it at `/end`.

## 4. Risks and gotchas (measured, not guessed)

### 4.1 `narg()` does not fully separate `f()` from `f(nil)` — and 028.3 says so

`learnings/luaj-bridge.md` (028.3) is explicit: `f(g())` where `g` returns **nothing** arrives as
`narg == 0`, indistinguishable from `f()`, while `g` returning `nil` arrives as `narg == 1`.

So §2.9's refusal is **exact for `f(x)` and for a table field** — which is where both measured hazards
live (`cat:show(cfg.enabled)`, `opt:name(v)`) — and degrades to "treated as a read" for a zero-return
call. This is documented on the page and asserted both ways in 039.1's suite. **It also reverses 028.3's
own contract** (an explicit `nil` was *the collection form*), which is fine because the callable
namespaces it applied to are gone — but the learning must be amended at `/end` or it will mislead.

### 4.2 A helper named after a `LuaValue` member shadows it

`luaj-bridge` (029.1): inside an anonymous `OneArgFunction` body, a static helper named `type`, `len`,
`get`, `set`, `call`, `method` or `tostring` resolves against the *inherited* `LuaValue` member first and
fails with a type error that says nothing about the shadowing. **This feature adds `LuaCollection` with
`get`/`list`/`count`/`find`/`add`/`remove`** — `get` and `set` are exactly the trap. Name the Java
helpers `getMember`/`listMembers`, never `get`/`set`.

### 4.3 The interning rules that already bit

- Weak on **both** axes (D-064) or a cache pins a closed widget / despawned gob.
- **An interned userdata is an identity, not a record** (021.1): per-entity state that must outlive a
  weak cache is *derived*, not stored (D-065).
- Intern **keys follow the engine's own stability** (D-094) — a published id where the engine rebuilds
  the object, Java identity where it mutates in place. The 14 new entities each need this decided; the
  table in §2.4 of the spec is the default, `LuaItem` is the open one.

### 4.4 Position allocation

`gob:position()` in a draw callback at 60 fps mints a userdata per call. A `{x, y}` table did too, so it
is not obviously worse — but the ROADMAP already flags ~11 MB/frame of allocation, so 039.2's suite
**measures** it through `hafen.client():profiling()` rather than assuming parity.

### 4.5 Two failure modes a move creates

- **`hafen.craft():current():make()` indexes `nil`** where `hafen.craft.make()` was a no-op. Either
  `:current()` hands back an inert entity whose `:exists()` is false (the `hafen.kin(<unknown id>)`
  precedent, D-056) or the page documents the guard. **039.13 settles it.**
- **`hafen.store():get(name)` must return the LIVE persisted table**, not a copy, or `store:get("cfg").x
  = 1` silently stops saving. **039.10 asserts the round trip.**

### 4.6 The build and the box

- Java is `source/target 1.8` — no `var`, no `Files.readString`, no switch-expressions.
- **`ant hafen-client` is incremental and hides breakages** when a symbol moves between files
  (`learnings/ant-incremental-build-hides-breakages`): every task that moves or deletes a class does
  `rm -rf build/classes` before its final build.
- A Java change needs a **full client restart**; only Lua files reload with `:reload`.
- `hafen.log` is **ASCII-only in practice** (037.5): no em-dashes in runtime strings.
- Multi-line `perl -0pi` needs `\r?\n` on this CRLF box, and **falsify with a file copy — a
  `git checkout` wiped a task's uncommitted work** (036.3).

## 5. Discarded alternatives

- **A deprecation period / aliases.** Nothing is released; a hard cut is the project's rule and is what
  makes the port itself the proof of completeness.
- **Doing the shape now and the OOP half as 040.** Rejected by the maintainer and correctly: the nine
  flat sections would be re-spelled twice, and the API would ship uniform syntax over non-uniform
  returns — a veneer.
- **Keeping `hafen.log("x")` as a shortcut.** 641 sites, the busiest verb in the API. Rejected: one
  exception in the most-used verb is the exception every reader meets first (§8.1 — reversible in one
  line if the maintainer changes their mind).
- **Grid-relative numbers as the only coordinate.** They are not closed under arithmetic — a grid is
  1100 world units, so `p.y + 22` at `y = 1095` is a bug, and the 38 arithmetic sites in the shipped
  addons would each need an engine call anyway. The Position object gets the same durability *and* keeps
  the maths, which is why it exists.
- **Indexable collections** (`hafen.party()[1]`). Available — userdata takes `__len` — and refused: two
  ways to enumerate is D-013's dual style.
- **`grid:sessionCoord()`.** The live `gc` has no consumer outside the suite that tests it.
- **A generic `w:clear("position")`.** Stringly-typed, and the layer undo already has a spelling that
  costs no new verb: `w:position(nil)`.

## 6. How a task is verified

Per `specs/addons/TESTING.md`, unchanged: one self-checking addon per task, `:t039-<N>`, printing
`[pass]`/`[fail]`/`[manual]` and a `[summary]`, **standing alone** (D-085) — it duplicates whatever
premise it rests on rather than depending on another suite.

Two things this feature adds to the recipe:

- **A cut is a feature and needs a regression test** (017): every task asserts its *removals* — the old
  name throws, and the message names the replacement — beside the new surface.
- **Headless first.** A suite that does not build windows can be dry-run under the LuaJ jar against a
  stub `hafen` (`learnings/testing-tooling.md`); a suite that does needs 036.1's fabricated-`UI` probe.
  Report the counts in `HANDOFF.md` before handing over.
