# 039-uniform-api — Spec

> Size limits lifted for this feature by the maintainer: it rewrites practically the whole `hafen.*`
> surface, and it is meant to **set the standard every later feature follows**. This spec is therefore
> normative — §2 is the grammar, §3–§7 are the complete inventory it is applied to, and nothing here is
> left for `/implement` to invent.
>
> **Read `API.md` beside it.** That file is the exhaustive **before → after** map of every section, verb
> and entity method: the completeness checklist, the porting map for 10,633 lines of Lua, and the source
> of §2.10's retired-name table. Where this spec states a rule, `API.md` states every row it generates.
> It is a working artefact of this feature, **not a contract** — `AREA.md` is explicit that the docs
> tier is the only published surface — and it is frozen as history once the feature closes.

## 1. What & why

Two `ROADMAP.md` entries land together — "Collection objects instead of arity-as-verb" and "Finish the
OOP migration" — because either alone leaves the API half-uniform. Today it has **four unrelated
shapes**:

| shape | example | count |
|---|---|---|
| dotted function on a plain table | `hafen.world.gobs(f)` | 23 sections |
| callable namespace (D-056) | `hafen.gob(id)`, `hafen.kin()` | 11 sections |
| config table as named arguments | `hafen.ui.window{…}` | 8 constructors (§5), ~290 sites |
| colon method on a section | `hafen.client:options()` | 1 section |

…and underneath the syntax, **half the API hands back live objects and half hands back dead snapshot
tables**. `hafen.party():members()` returning plain tables while `hafen.kin():list()` returns Kin objects
is the same inconsistency one layer down, so uniform syntax over non-uniform returns would be a veneer.

Nothing is released ([no-backward-compatibility]), so every replaced spelling is **hard cut**.

### The rule, in one sentence

> **A section is CALLED. Everything after it is a `:verb()`. Arity is the verb — `:x()` reads, `:x(v)`
> writes and returns self so it chains. A collection is an object with `:add`/`:remove`/`:list`. Every
> read hands back a live object; a snapshot is only ever what `:info()` gives you.**

```lua
local w = hafen.ui():window():title("Scout"):size(180, 48):position(80, 120):onDraw(fn)
w:title()                                          --> "Scout"   (arity is the verb)
w:title("Scouted"):size(200, 48)                   --> chains, each setter returns w

local rabbit = hafen.world():gob():nearest("rabbit")   -- gobs are a collection on the LIVE world
rabbit:overlay():add("tag"):text("dinner")         -- collection object, then a builder
hafen.act():moveTo(rabbit:position():offset(0, -11))   -- ONE position type: computable and durable
```

## 2. The grammar (normative — this is what later features must follow)

### 2.1 Section access

`hafen.<section>` is **always** a callable table; `hafen.<section>()` hands back the **section object**.
There are no dotted sub-verbs and no colon-on-the-namespace anywhere.

- The section object is a **per-addon singleton**, minted once at `installHafen` and handed back by
  identity — never constructed per call. `hafen.world() == hafen.world()` is asserted, because a section
  called inside a draw callback runs 60×/s and a per-call allocation is D-099's failure exactly.
- Where a section contains exactly **one thing**, the section object **is** that thing:
  `hafen.player()` is the character, `hafen.kin()` is the roster, `hafen.buff()` is the buff collection.
  This is not an exception to the rule — it is the rule with a collection of one.
- **No section keeps a shortcut form.** `hafen.log("x")` becomes `hafen.log():write("x")` and
  `hafen.speed()` becomes `hafen.speed():current()`. See §8.1 — `hafen.log` is 641 Lua sites and this is
  the single most expensive consequence of "TODOS iguales"; it is taken deliberately, because one
  exception in the busiest verb in the API is the exception everyone would learn first.

### 2.2 Verb naming

One table, applied everywhere. A new feature that needs a verb picks its row here rather than inventing.

| purpose | spelling | returns |
|---|---|---|
| read a property | `:name()` | the value, or `nil` when unavailable |
| write a property | `:name(v)` | **self**, so writes chain |
| undo *your* layer | `:name(nil)` — only where §2.9 allows it | self |
| a set you can address into | `:name()` (**singular**, §2.3) | the collection object |
| a distinguished member | `coll:current()` `:selected()` `:leader()` `:available()` | the entity, or nil |
| one member by key | `coll:get(key)` | the entity, or `nil` |
| first member matching | `coll:find(filter)` | the entity, or `nil` |
| every member | `coll:list(filter)` | a plain array (a **value**, §2.8) |
| how many | `coll:count(filter)` | number |
| create a member | `coll:add(key, …)` | the new entity |
| destroy a member | `coll:remove(keyOrEntity)` | the collection, so it chains |
| does it still exist | `:exists()` | boolean |
| the snapshot hatch | `:info()` | a plain table, or `nil` |
| end an owned thing | `:destroy()` | nothing |

- **A boolean property is a property**: `w:visible()` reads, `w:visible(false)` writes. `w:show()` and
  `w:hide()` are **hard cut** — two spellings for one write is the dual style D-013 forbids.
- **One name per property: no `getX`, no `setX`, no `clearX` anywhere in the API.** Five `get`/`set`
  pairs exist today and all collapse into one name:
  `hafen.speed.get`/`.set` → `:current()`/`:current(n)`, `kin:setGroup(g)` → `kin:group(g)`,
  `slot:set(res)` → `slot:res(name)`, `g:setRes(r, sdt)` → `g:res(r, spawnData)`, and the keybinding registry's
  `kb:get(name)`/`kb:set(name, key)` → `kb:key(name)`/`kb:key(name, key)`. In every case the bare read
  already existed, so the pair was always one name too many. `coll:get(key)` is **not** an exception — it
  addresses a member, it does not read a property.
- A read whose name would collide with a write of different meaning gets renamed, not overloaded. The
  only case in the whole surface is `w:replace()`: the read becomes **`w:replacement()`**, while
  `w:replace(v)` installs and `w:replace(nil)` undoes (§2.9).
- `:info()` is preserved on **every** entity, old and new. `types.md` does not shrink — its 19 snapshot
  shapes stop being primary returns and become what `:info()` hands back.

### 2.3 Collections

A collection is an **object**, never a bare array, and it carries exactly the verbs in §2.2 that apply.

**The noun names the KIND; the verb says HOW MANY.** The accessor is the **singular** kind name:
`hafen.map():segment():list()`, `gob:overlay():get(key)`, `hafen.char():attr():list()`. A plural name
would be redundant with `:list()` — plurality belongs to the verb, which is this feature's own philosophy
applied one level down. It also removes a wart: sections are already singular (025 hard-cut `hafen.buffs`
for `hafen.buff`), so a plural sub-collection would have been the odd one out. The three sections that
are still plural — `hafen.quests`, `hafen.wounds`, `hafen.events` — are renamed to singular with it.

**Plural survives for a plain array read** — a verb that only answers "give me these", with nothing to
address into: `w:children()`, `w:items()`, `pag:children()`, `meter:segments()`, `seg:markers(f)`,
`craft:inputs()`/`:outputs()`, `hafen.fight():deck()`, `hafen.menugrid():roots()`, `quest:conditions()`.
Event **names** keep their plurals too (`MarkersChanged`) — a name is a sentence, not an accessor.
**A collection needs no `:add`/`:remove` to be one**: `hafen.buff()`, `hafen.meter()`, `hafen.actionbar()`
and `hafen.world():gob()` are read-only collections, and they are still addressed into.

**A distinguished member is a verb on its collection, never a second accessor**: `:current()`,
`:selected()`, `:leader()`, `:available()`. This is the rule the first draft got wrong —
`hafen.map.segment()` with no argument is *the segment you are standing in*, and folding it into the
collection accessor silently deleted it.

- **A collection object is NOT indexable.** No `[n]`, no `#`, no `ipairs` on the object itself —
  `coll:list()` hands the array and you index that. **Note the reason, because it is not the obvious
  one**: D-057's problem was a 0-keyed *table* (`LuaTable.len()` ignores `__len`), but a collection here
  is **userdata**, and `learnings/luaj-bridge.md` records that userdata *does* get `__len`. So indexing
  is technically available and is refused on design grounds, not capability ones — a collection that is
  both an object and a sequence has two ways to enumerate it, which is D-013's dual style, and
  `:list()` is one call away. `hafen.party()[1]:gob()` (the ROADMAP's old promise) is written
  `hafen.party():list()[1]:gob()`.
- `:list(filter)` takes the standard filter argument unchanged (`nil` / string substring / predicate),
  and the predicate receives an **object**, never a snapshot — which is now true everywhere rather than
  in the OO half only.
- A collection **owned by an entity** is reached with the plural verb on that entity and holds no
  lifetime of its own: `gob:overlay()` re-derives from the gob each call, so it cannot outlive it. This
  is the ROADMAP's own objection to collection handles (D-100/D-102) answered by construction — the
  collection object is a **view**, not a handle, and holds nothing across a call.

### 2.4 Entities and identity

Every entity follows 017/020's mechanism verbatim: **userdata + a per-addon metatable, interned in a
per-`Addon` weak-valued cache with a drained `ReferenceQueue`** (D-044/D-045), so `==` is the identity
test and a stashed handle stays live.

The intern **key** follows D-094 — *the engine's own stability*, not convenience:

| the engine… | key on | example |
|---|---|---|
| publishes a stable id | that id | Gob, Kin, Segment, Grid |
| rebuilds/evicts the object | the published id | icon category, segment |
| mutates one object in place | Java identity | `MapFile.Marker` |
| exposes only a widget | widget identity | Buff, Meter, StudySlot |

`:exists()` belongs to entities with a **lifetime** and is absent from name-keyed handles (D-060).

### 2.5 Builders: no config tables as arguments

A thing you create is created **bare** and configured with chained setters. There is no `opts` table
anywhere in the API after this feature.

```lua
hafen.ui():window():title("Scout"):size(180, 48):onDraw(fn):onClose(fn2)
```

Why this is not a taste question: **Lua has no keyword arguments.** `f{…}` is only sugar for `f({…})`, so
the config table was never a style choice — chaining is the one other valid spelling of named arguments.

- Every setter returns **self**; every setter has a matching bare read (§2.2), so a builder's properties
  are readable after construction with no second vocabulary.
- **Construction-time state**: a bare `:window()` exists for the length of the statement without a size
  or title. It is built with the client's own defaults and **does not lay out or draw until the tick
  after the statement** — so a half-configured widget is never painted. This is new behaviour and is
  asserted, not assumed (§9).
- A **required** argument stays positional on the constructor where the thing is meaningless without it:
  `hafen.render():sprite(imageHandle)`, `hafen.ghost():add(resName)`. Everything optional is a setter.

### 2.6 Names

- **N1 — expand what a reader cannot decode; keep the game's own words.** `mtime` -> `modified`,
  `sdt` -> `spawnData`, `comp` -> `composite`, `lvl` -> `level`. But `res`, `lp` and `fep` stay:
  **D-061** (the API's vocabulary comes from the engine, not the genre) governs which *concepts* exist —
  it refuses "debuff" and "radar" because the engine has neither — and does **not** require the engine's
  *spelling*.
- **N2 — camelCase, always.** Already the majority (`isNight`, `worldToScreen`, `skillsAvailable`,
  `renderScale`, `masterVolume`, `overlayImage`) with five stragglers: `isplayer`, `isnew`, `onmap`,
  `rootpos`, `endkin`.
- **N3 — one word per concept.** The sweep found two dual names: `marker:dist()`/`gob:distance()`
  (unified), and `hafen.world.gridPos()`/`marker:anchor()`, which return the identical `{gridId, x, y}`
  shape — deleted rather than renamed, by §2.7.

### 2.7 The Position type — one `position()`, everywhere, durable

A position was a plain `{x, y}` table, which can only be **one** of the two things a position must be:
a point you can do arithmetic on, or a place you can save and send. **An object can be both**, and
everything else in this API is already an object.

```lua
local p = gob:position()
p:x()  p:y()                          -- session world components
p:offset(0, 22)                       -- a NEW Position; the engine crosses grid boundaries
p:distance(other)   p:tileCoord()     p:durable()   p:info()
hafen.act():moveTo(p)                 -- every spatial verb takes a Position
hafen.store():get("cfg").home = p     -- persists as {gridId, x, y}: durable and shareable
```

**There is exactly one position verb in the whole API** — no `gridPosition`, no `sessionPosition`, no
`worldPos`, no `anchor`. `hafen.world.gridPos`, `fromGridPos` and `marker:anchor()` are **CUT**: the
Position *is* the anchor.

**Why the arithmetic moves into the engine, and why that is the point.** A grid is 100 tiles x 11 =
**1100 world units**, so `p.y + 22` is correct on a continuous coordinate and a *bug* on a grid-relative
one — at `y = 1095` it yields 1117, past the grid edge, when the answer is a different grid id. The **38
arithmetic sites** in the shipped addons (`moveTo(p.x, p.y + SOUTH)` at `walker:88`, the four-corner box
at `walker:102`) become `p:offset(...)`. That one move is what lets a single `position()` be computable
*and* durable, and it retires `atlas:85`'s hand-written flattening (`(sc.x * SIDE) + wt.x`).

**Durability is EXPLORED, not loaded**, and the lookup is two steps: the streamed grid's id from
`MCache`, else session coord -> segment coord via `sessloc` -> the recorded grid there. Step 2 needs
nothing streamed — `MapApi:487` says it outright: *"it answers for any grid in the player's current
segment whether or not that ground is streamed in right now"*. So a Position is durable **anywhere you
have been**, and fails only on ground you have never visited — which is also ground you cannot act on.
`:durable()` reports it.

**Two consequences that are work, not phrasing:**

- **`hafen.store` and `hafen.json` must marshal it.** `Json.java:31` degrades a userdata to a quoted
  `tostring` in *forgiving* mode — the mode the store uses — so a Position would persist as **garbage,
  silently**. They must emit `{gridId, x, y}` and reconstruct on read. Non-optional.
- **`:info()` and `:x()`/`:y()` do not report the same numbers**: `:info()` is the durable form (grid id
  + *within-grid* offset), `:x()`/`:y()` are *session world*. Both pages must say so.

**What is not a Position.** A **lattice cell** is an index, not a place: `grid:segmentCoord()` (grids),
`marker:segmentTile()` (tiles), and the within-grid `0..99` argument of `grid:tile(c)`. **Screen pixels**
are not either — `w:position()`, `w:rootPos()` and `worldToScreen` answer plain `{x, y}` px. The verb is
still `position()` (*where is this thing, in the space it lives in*) and the entity says which space;
that is safe rather than ambiguous now Position is a **type**, since handing `w:position()` to
`hafen.act():moveTo()` throws instead of walking you somewhere wrong.

**`grid:sessionCoord()` is not created**: the live `gc` has no consumer — grep finds it only in the
037.1 suite that tests it, in none of the eleven example addons.

### 2.8 A table used as a VALUE is untouched

The ban in §2.5 is on a table standing in for **named arguments**. A table that is **data** is unaffected,
or the API could not return a list at all:

- every `:list()` array and every `:info()` snapshot
- a coordinate `{x=, y=}`, a colour `{r,g,b,a}`, an anchor `{gridId,x,y}`
- a document parsed by `hafen.json():parse(s)`

**Colours**: a setter takes positional components — `:color(200, 210, 220)` — *and* accepts a colour
**value** so a read passes straight back: `meterA:color(meterB:color())`. That is one canonical way (the
setter's own form) plus pass-through of the API's own product, not a dual style.

### 2.9 The `nil` discipline

**An explicit `nil` argument is an ERROR unless the page documents a meaning for it**, and the meanings
are few enough to list here in full. **This reverses 028.3**, which documented an explicit `nil` as *the
collection form* on a callable namespace; that shape is gone with the callable namespaces themselves.

| `nil` means | where | why it is safe |
|---|---|---|
| **undo your layer** — D-089: it removes a LEVEL and re-resolves, never a reset to stock | `w:position(nil)`, `w:size(nil)`, `w:replace(nil)` | the widget returns to what the **user** had: visible, immediate, reversible |
| **none** | `ov:tint(nil)` and the `sprite`/`object`/`ghost` tints | "no tint" is a real value |
| **an error** | everywhere else | it is an accident, and there is nothing to undo |

The bridge distinguishes `f()` from `f(nil)` by `narg()` — **but only for a direct argument**, and
`learnings/luaj-bridge.md` (028.3) is explicit about the hole: `f(g())` where `g` returns *nothing*
arrives as `narg == 0`, indistinguishable from `f()`, while `g` returning `nil` arrives as `narg == 1`.
So the refusal is exact for `f(x)` and for a table field — which is where both measured hazards live
(`cat:show(cfg.enabled)`, `opt:name(v)`) — and degrades to "treated as a read" for a zero-return call.
That is a documented limit, not a defect to hide: the page says so and the suite asserts both forms.

**What the error row is for.** Two of the four hazards measured in shipped code were *silent
destruction* — `w:skin(computeStyle())` dropping your style (`LuaWidget:392`) and
`gob:overlay(k, buildSpec())` removing the overlay (`LuaGob:289`) — and **both are already gone**, not
because of this rule but because §2.3/R7 replaced them: an overlay goes with `gob:overlay():remove(k)`,
a style with `w:rule():remove()`. What survives is the *silent no-op* pair, and it is what the rule
still earns its keep on:

| today | with an accidental `nil` |
|---|---|
| `cat:show(cfg.enabled)` | a **silent no-op write** that reads instead (`LuaIconCat:232`) |
| `opt:name(v)` (×18) | the same silent no-op (`OptionsMethod:28`) |

Neither property has an undo, so refusing `nil` there costs nothing and buys the loudest failure.

**No `clearX` verb exists** (§2.2): the first draft answered undo with `clearPos()`/`clearSkin()`/
`restore()`, which is a `set`/`clear` pair in spirit — the very thing §2.2 removes — and which the two
deletions above made unnecessary.

**One corner where `nil` still destroys**: `w:replace(nil)`. A `makeView()` returning nil undoes the
substitution and destroys your view. Visible and reversible — `position(nil)`'s class, not
`skin(nil)`'s — but plan.md carries it as a known edge rather than discovering it.

### 2.10 Errors name their replacement

Every retired spelling must **fail loudly and say what replaced it**. A deleted table field would read as
plain `nil` and fail later as "attempt to call a nil value", which is useless while porting 10,633 lines.

- Each section's metatable carries an `__index` that **throws for every retired name**:
  `hafen.world.gobs` → `"hafen.world.gobs(f) is now hafen.world():gob():list(f)"`.
- Each entity metatable does the same for retired methods.
- The retired-name table is generated from this spec's §3–§7 inventory, so coverage is mechanical.

### 2.11 Events

`hafen.event():on(name, fn)`. **An event's payload is the entity it is about** — never a snapshot. Where
an event genuinely carries several things it hands a plain value table (§2.8), and the entities inside it
are objects. The five snapshot-carrying events are fixed by the §4 migration: `FepChanged` (Food),
`StudyChanged` (StudySlot[]), `EquipChanged` (Item[]), `WoundChanged` (Wound[]), `QuestAdded`/`QuestDone`
(Quest). D-104 (scope follows the key's scope) and D-105 (where the read collapses, the event follows the
key) are unchanged.

### 2.12 Gating is unchanged

The `actions` permission (D-027/D-028) and the `network` host allowlist keep their current meaning and
their current verbs. A gated verb still chains on self and still throws naming itself when undeclared.
This feature moves **no** verb between the gated and ungated tiers.

## 3. Every section, before and after

All 34, exhaustively. "→ object" means the section object carries these as `:verbs()`.

### 3.1 Sections that become plain section objects

| section | today | after |
|---|---|---|
| `act` | `.clickGob .enabled .flower .item .menu .moveTo .place .raw .select .useItemOn` | `hafen.act():clickGob(…)` etc., 10 verbs |
| `char` | `.attr .attrs .credos .experiences .food .lp .skill .skills .skillsAvailable .weight` | §4.1 — becomes entity-bearing |
| `craft` | `.current .make` | §4.4 |
| `events` → **`event`** | `.on` | `hafen.event():on(name, fn)` |
| `fight` | `.deck .maneuvers .summary` | §4.6 |
| `hook` | `.action .grab .input .message` | `hafen.hook():input(…)` etc., 4 verbs |
| `http` | `.get .post` | `hafen.http():get(url, fn)` |
| `json` | `.encode .parse` | `hafen.json():parse(s)` |
| `log` | `hafen.log(msg)` | `hafen.log():write(msg)` — **641 sites**, see §8.1 |
| `party` | `.leader .member .members` | §4.3 |
| `quests` → **`quest`** | `.list .selected` | §4.5 |
| `slash` | `.register` | `hafen.slash():register(name, fn)` |
| `speed` | `.get .set .max .name` | `hafen.speed():current()` / `:current(v)` / `:max()` / `:name()` |
| `store` | `.cfg .settings .camp .spot .seen .flush` | `hafen.store():cfg()` etc., 6 verbs |
| `study` | `.slots .summary` | §4.2 |
| `time` | `.clock .dayFraction .isNight .moon .season .yearFraction` | `hafen.time():clock()` etc., 6 verbs |
| `timer` | `.after .every` | `hafen.timer():every(s, fn)` → handle with `:cancel()` |
| `wounds` → **`wound`** | `.has .list` | §4.7 |
| `world` | 18 dotted verbs | `hafen.world():gob():list(f)`, `:tile(x,y)`, … §3.3 |

### 3.2 Sections that are already callable (D-056) and keep their meaning

`actionbar`, `asset`, `buff`, `client`, `font`, `kin`, `menugrid`, `meter`, `player`, `sound`, `ui`.
The change is that **addressing one entity becomes a verb on the collection**, per the maintainer's own
`overlays:add`/`:remove` sketch:

| today | after |
|---|---|
| `hafen.kin(idOrName)` | `hafen.kin():get(idOrName)` |
| `hafen.actionbar(n)` | `hafen.actionbar():get(n)` (0-based game index kept, D-057) |
| `hafen.buff(needle)` / `hafen.meter(needle)` | `…():find(needle)` — it is a substring search, not a key |
| `hafen.menugrid(key)` / `hafen.sound(name)` | `…():get(key)` |
| `hafen.asset(path)` | `hafen.asset():get(path)` |
| `hafen.font(name)` | `hafen.font():get(name)` |
| `hafen.client:options()` / `:profiling()` | `hafen.client():options()` / `:profiling()` |
| `hafen.ui(selector)` / `.all(sel)` / `()` | `hafen.ui():find(sel)` / `:all(sel)` / `:root()` |

**`hafen.ui()` collides** and the resolution is above: it is the section, and the root widget — 36 sites —
becomes `hafen.ui():root()`.

### 3.3 `hafen.gob` is deleted into `hafen.world()`

The searches already live on the live world (`gobs`/`count`/`nearest`/`within`, 037.1); `hafen.gob(id)`
was only the by-id door. **D-066** — a thing that lives inside another is a relation on it, not a section
of its own — is exactly how 037 folded markers and icons into `hafen.map`. So:

- `hafen.world():gob()` is the read-only Gob collection and absorbs all five entry points:
  `:get(id)` (was `hafen.gob(id)`), `:list(f)`, `:count(f)`, `:nearest(f)`, `:within(r, f)`. Keeping
  `:gob(id)` beside `:gobs(f)` would have left standing the exact dual pair §2.3 kills twice elsewhere.
- **`:gob():get(id)` is never nil**, unchanged from today — it is what lets you anchor to a gob before it
  streams in, with `:exists()` as the liveness test. Living under *the live world* invites the opposite
  reading, so the page must say so (D-092: a boundary is a decision on the page, not a gap in the code).
- **Events are unaffected**: `GobAdded`/`GobRemoved` hand the Gob object itself, so no handler ever went
  through the section. A removed gob keeps answering `:id()` with `:exists()` false.
- The **Gob object keeps its own doc page** and every method; only the entry points move.

**And `hafen.world()` and `hafen.map()` become deliberately parallel** — they are one domain read two
ways, so where they hold the same concept they spell it the same. `hafen.world():grid()` becomes a
collection mirroring `hafen.map():grid()`, and the two hand back **one Grid entity through two doors**:
both halves already key on the same server-published grid id (`WorldApi:175` publishes `MCache.Grid.id`,
`MapApi:130` interns on the same `long`), so `:live()` says whether it is streamed and `:exists()`
whether it was saved. That turns 037.2's headline result — `grid:tile(c)` and `hafen.world.tile(x,y)`
agreeing — from a cross-check you must remember to make into a property of the object.

**Symmetry stops where the game does**: there is no `hafen.map():gob()` (the client records no gobs) and
no `hafen.world():marker()` (a marker is a disk record by definition). `API.md` carries the full
concept-by-concept table.
- The live/recorded split 037 established is preserved and made visible: **`hafen.world()` is LIVE**
  (gobs + terrain, gone at logout), **`hafen.map()` is RECORDED** (the on-disk database).
- `hafen.map`'s own dotted verbs follow the same rule, singular per §2.3:
  `hafen.map():segment():current()`, `:segment():get(id)`, `:grid():get(id)`, `:marker():list()`,
  `:icon():get(res)`, `:overlay():list()`.

### 3.4 `hafen.render` / `hafen.ghost`

Shape-migrated here (`hafen.render():sprite(img)`, `hafen.ghost():add(res)`) but **not relocated** —
moving them under `hafen.world` is the other deferred ROADMAP item and stays its own feature (§10).

## 4. The OOP half: the last flat sections become entities

The 020/021/023/024/025/027 pattern applied for the last time. Each gains an interned entity with
`:info()` as the escape hatch, and each entity's intern key is chosen by §2.4's table.

### 4.1 `hafen.char` (10 verbs) — Attr, Skill, Credo, Experience, Food

`hafen.char():attr()` → the **Attr** collection (`:name/:base/:comp/:value/:info`), so `:attr():get(name)`
is one and `:attr():list()` is all. `:skill()`/`:credo()`/`:experience()` likewise, with
`skillsAvailable()` becoming `:skill():available()` (§2.3's distinguished-member rule); `:food()` → a
**Food** entity. `:lp()` and `:weight()` are scalars and stay plain reads.

### 4.2 `hafen.study` (2) — StudySlot

`hafen.study():slot()` → the **StudySlot** collection (`:res/:name/:lp/:attention/:exists/:info`),
interned on **widget identity** (§2.4). `:summary()` stays a scalar read.

### 4.3 `hafen.party` (3) — PartyMember

`hafen.party():list()`, `:get(gobId)`, `:leader()`. **PartyMember** carries `:id/:pos/:color/:leader/
:gob()/:exists/:info` — and `:gob()` is what **restores the regression 017 accepted**. A member has no
name (the client is never sent one) and the page must keep saying so.

### 4.4 `hafen.craft` (2) — Craft, CraftSpec

`hafen.craft():current()` → a **Craft** entity; `:make(all)` stays the gated write, moved onto the entity
where it belongs. **Open**: moving it changes the failure mode — with no craft window open,
`hafen.craft.make()` was a no-op and `:current():make()` indexes `nil`. Either `:current()` hands back an
inert entity whose `:exists()` is false (the `hafen.kin(<unknown id>)` precedent, D-056) or the page
documents the guard. Settle in plan.md.

### 4.5 `hafen.quest` (2, renamed from `quests`) — Quest, Condition

`hafen.quest():list(filter)`, `:selected()`, `:get(id)` — **the section is renamed to the singular**
(§2.3). **Quest** carries `:id/:title/:conditions()/:done/:exists/:info`; **Condition** is a sub-entity.
`QuestAdded`/`QuestDone` carry the Quest.

### 4.6 `hafen.fight` (3) — Maneuver, DeckCard, FightSummary

`hafen.fight():maneuver():list()`, `:deck()` (a layout — a plain array, §2.3), `:summary()`. This is the
other half of 017's regression:
**`hafen.fight():target():gob()`** resolves the combat target's Gob for the first time since 017.

### 4.7 `hafen.wound` (2, renamed from `wounds`) — Wound

`hafen.wound():list(filter)`, `:find(needle)` (the old `has`) — **the section is renamed to the
singular** (§2.3). **Wound** carries `:res/:name/:severity/:exists/:info`. `has` returned a boolean and
`:find` returns the Wound; both are truthy in every shipped call site, verified during the port.

### 4.8 Items — the one genuinely open migration

`widget:items()` and `hafen.ui():hand()` hand back **Item** entities instead of snapshots. This is the
ROADMAP's "Item handles & rich item data", and its intern key is a real D-094 question: an item has **no
stable content id** — it is addressed by its server widget id, which is reused when an item moves. The
entity therefore has a genuine lifetime, `:exists()` is real, and a stale one must refuse its writes
rather than act on whatever now holds that id. **Plan.md must settle this before the task is written.**

### 4.9 The final demolition

With the migration complete, `/end` deletes the transitional markers 017 planted: the `design/06` banner,
the `(SUPERSEDED by D-044)` headers, and `conventions.md`'s "Gob is the only OO section, the rest is
flat" paragraph. Grep-verified in §9.

## 5. Every builder, table by table

### 5.1 `hafen.ui.window{…}` / `hafen.ui.widget{…}` / `hafen.ui.overlay{…}`

13 keys today: `title parent pos size font onDraw onClick onClose onDrop onMouseMove onMouseUp onTick
onWheel`. Each becomes a setter with a matching bare read, `pos` spelt **`position`** (§2.7) and
answering **pixels**. `widget{}` is the same minus `title`.

### 5.2 `hafen.render.sprite{…}` / `object{…}` / `hafen.ghost.new{…}`

Keys: `image`/`model`/`res` (→ the positional constructor argument, §2.5), plus
`scale rotate alpha tint billboard clickable onClick` as setters, `x y z` folded into
**`:position(p)`** (§2.7) and `sdt` spelt `spawnData` (§2.6). The entities already carry
`:scale/:rotate/:alpha/:tint/:clickable`, so **most setters already exist** — the change is that the
constructor stops taking them, and that `:pos()`/`:move(x,y,a)` collapse onto the one
`:position()`/`:position(p, a)` pair.

### 5.3 `font:derive{…}` — 43 sites

`h:derive()` returns a new handle; `size`/`color`/etc. become setters on it.

### 5.4 `hafen.ui.skin{…}` — 141 sites, the hardest

A stylesheet is a **selector → properties map**, which has no natural chained spelling. The answer is a
**Sheet object with Rule children**:

```lua
local s = hafen.ui():sheet()
s:rule("window"):bg(…):border(…):pad(4)
s:rule("chat"):color(200, 210, 220)
s:install()                      -- and s:drop()
```

- `sheet:rule(selector)` hands back a **Rule** interned per selector on that sheet; Rule setters return
  the Rule; `rule:sheet()` climbs back for one-expression use.
- **`sheet:load(parsedTable)` is kept as an explicit DATA door** (legal under §2.8), because 036.4's
  headline result was `hafen.json.parse(file)` going straight into `skin{}` unmapped — a whole theme for
  zero lines of Lua. Without it that capability is lost; `theme.json` is the regression test for it.
- `w:skin{…}` becomes **`w:rule()`** — the widget's own hand-named cascade level (D-077) as a Rule — and
  `w:rule():remove()` is the undo (R7, not a `clear` verb). `w:style()` keeps its current meaning: the
  **resolved** style, a value.

### 5.5 `g:text{…}` / `g:atext{…}` and `hafen.map.markers.add{…}`

The marker constructor becomes `hafen.map():marker():add(name, p)` plus setters on the Marker it hands
back. `g:`'s 11 drawing verbs are already positional and unchanged — but `g:text{…}`/`g:atext{…}` are the
one R4 case with **no persistent object** to hang setters on, and §8 carries it as an open question.

**The eight constructors §5 covers**: `window`, `widget`, `overlay`, `sprite`, `object`, `ghost`,
`font:derive`, `marker:add` — plus `skin`, which is not a constructor but a whole sheet (§5.4), and
`g:text`, which is a draw call (above).

## 6. Cost rules (measured, not asserted)

- A section object is a singleton — identity asserted, and a draw callback calling one must allocate
  nothing measurable through `hafen.client():profiling()`.
- Entity interning stays weak on both axes (D-064), so no cache pins a closed widget or a despawned gob.
- A collection object is a **view** (§2.3): it holds nothing, so it costs one userdata per call at worst
  and is not stashed by the documented idiom.
- The whole feature is a **behaviour no-op**: the profiler's frame time before and after must be
  indistinguishable on an identical scene.

## 7. What must be ported (the port IS the proof)

10,633 lines of Lua and 74 doc pages, in three groups. An unported call site cannot run, so a green suite
is proof the cut is complete.

| group | what | size |
|---|---|---|
| suites | the 21 shipped `NNN-*.X` addons | must stay green, never edited to be green |
| examples | `atlas bags hogtest netdemo optionstest planner profiler tagger theme walker widgetstack` | 11 |
| frozen | `addons/hello` (2,909 lines) | edited only because the cut breaks it, with a version bump |
| docs | 74 pages, 3,115 `hafen.*` occurrences (1,908 Lua / 1,207 docs) | link/anchor checker 0 broken |

## 8. Decisions taken here that are worth objecting to

1. **`hafen.log():write(x)` — 641 sites, the busiest verb in the API.** Uniformity says no section keeps
   a shortcut; pragmatism says this one should. Taken as **uniform**, because an exception in the
   most-used verb is the exception every reader meets first. Reversing it is a one-line change to §2.1.
2. **Arity-as-verb forecloses tooling, permanently.** One symbol with two signatures and two return
   types cannot be typed: no autocomplete, no LSP hover, no type-checking, ever — and a read and a write
   are the same string, so neither is greppable. The closest peer in this domain, WoW's own addon API,
   uses `frame:GetText()`/`SetText()` for this reason. Taken deliberately in exchange for one rule.
3. **Collections are not indexable** (§2.3) — `hafen.party():list()[1]`, not `hafen.party()[1]`.
4. **`w:show()`/`:hide()` are cut** in favour of `w:visible(b)` (§2.2).
5. **Collections are singular** (§2.3) — `hafen.map():segment():list()`, not `:segments()`. Raised by
   the maintainer while reviewing `API.md`, and it generalises: plurality lives in the verb.
6. **Three sections are renamed to the singular** with it — `hafen.quests`→`quest`,
   `hafen.wounds`→`wound`, `hafen.events`→`event`, 86 Lua sites. Every other section is already singular
   (025 hard-cut `hafen.buffs`), so these three were the only survivors. Purely a naming call, and the
   cheapest thing in the feature to reverse.
7. **`hafen.world():grid()` and `hafen.map():grid()` share ONE Grid entity**, gaining `:live()`. It is
   one of the two places the feature adds a **read** rather than only re-spelling one, so it is a
   deliberate exception to §10 — justified because the alternative is two entities for one engine
   identity, which is D-063/D-094 read backwards. (`:sessionCoord()` is **not** added: the live `gc` it
   would expose has no consumer outside its own test suite.)
8. **`hafen.world():placeGrid()`/`:placeAngle()` are CUT as duplicates** of
   `options():interface():posGran()`/`:angGran()` — the same `MapView` fields through two doors, with
   the units disagreeing (raw vs degrees). A defect the review found, not a rename.
9. **ONE `position()`, and it is a Position OBJECT** (§2.7) — the maintainer's requirement, and the
   only shape that satisfies it: a plain `{x, y}` can be computable or durable, never both. It deletes
   `gridPos`, `fromGridPos` and `marker:anchor()` outright, and moves 38 arithmetic sites into the
   engine. **The two things that make it real rather than a rename** are the store/JSON marshalling
   (silent data loss otherwise) and the engine-side vector verbs.
10. **The abbreviation sweep** (§2.6) — `mtime`/`sdt`/`comp`/`lvl` expanded, five names camelCased,
   `marker:dist()`/`gob:distance()` unified. `res`/`lp`/`fep` deliberately survive.
11. **Five `get`/`set` pairs collapse** (§2.2). `slot:set(res)` → `slot:res(name)` is the one worth a
   second look: `set` read as an action there, but `:res()` already read the same property back.
12. **Item identity** (§4.8) is not settled here and must be settled in plan.md, with the **six** other
   open questions listed at the foot of `API.md`.

## 9. Acceptance criteria

- [ ] Every one of the **33** surviving sections answers as a called object (34 today, less `hafen.gob`),
      and `hafen.<section>()` is the **same object** on every call within one addon (`==` asserted).
- [ ] Every retired spelling — dotted sub-verb, `hafen.gob(id)`, callable-namespace key form, config
      table, `w:show`/`:hide`, `:x(nil)` — **throws naming its replacement** (§2.10), asserted per section
      rather than described. No retired name reads as plain `nil`.
- [ ] Every builder in §5 constructs bare and configures by chained setters; each setter returns self
      (`==` the receiver) and each has a matching bare read. A half-configured widget never paints.
- [ ] An explicit `nil` throws on every verb **except** the three layer undos and the tints (§2.9),
      and no `getX`/`setX`/`clearX` name exists anywhere (grep). `w:position(nil)` returns the widget
      to the value the **user** had, with **D-089's level semantics** (drops your level, re-resolves)
      rather than a reset to stock.
- [ ] All of §4 hands back entities with a working `:info()`; `hafen.party():list()[1]:gob()` and
      `hafen.fight():target():gob()` resolve to live Gobs; the **six** snapshot-carrying events carry
      objects; `types.md`'s shapes survive as `:info()` returns.
- [ ] `hafen.world():grid():at(p)` and `hafen.map():grid():get(id)` hand back the **same object**
      (`==` asserted) for a grid that is both streamed and saved; a streamed-but-unsaved grid reads
      `:live()` true, `:exists()` false and nil for every recorded read; `hafen.world():placeGrid()` and
      `:placeAngle()` are gone and `options():interface():posGran()`/`:angGran()` still answer.
- [ ] `hafen.world():gob():get(id)` is the only by-id door, is **never nil**, and `hafen.gob` is gone;
      a `GobAdded`/`GobRemoved` handler still receives a live Gob and a removed one still answers `:id()`
      with `:exists()` false; `hafen.ui():root()` is the widget `hafen.ui()` used to hand back.
- [ ] `sheet:load(hafen.json():parse(f))` installs `theme.json` **unmapped**, with the geometry the
      file's own numbers predict — 036.4's zero-lines-of-Lua claim re-proven under the new shape.
- [ ] A draw callback calling a section allocates nothing measurable through
      `hafen.client():profiling()`; frame time on an identical scene is indistinguishable from before.
- [ ] **One position verb exists**: `:position()` answers a Position on Gob, Marker, Grid, PartyMember,
      sprite, object, ghost and overlay, and `gridPos`, `fromGridPos` and `marker:anchor()` all throw
      naming it. `p:offset(0, 1100)` from a point near a grid edge lands in the **next grid** (its
      `:info().gridId` differs), which is the check that proves the engine owns the arithmetic.
- [ ] A Position **round-trips through `hafen.store`**: saved, `:reload`ed, read back, and it resolves to
      the same tile — asserted, since `Json` would otherwise persist a userdata as a quoted `tostring`.
- [ ] `:durable()` is **true for an explored grid that is not streamed in** (the `sessloc` path, not the
      `MCache` one) and false only for ground never visited.
- [ ] No verb name is an undecodable abbreviation and none is non-camelCase (grep against `API.md`'s
      rename table).
- [ ] `conventions.md` states §2 as **the** grammar, including the value-vs-arguments boundary in
      D-092's form; no transitional marker from §4.9 survives (grep); the link/anchor checker reports
      0 broken over all 74 pages.
- [ ] The 21 suites, 11 examples and frozen `hello` are ported and green. Each task ships its own
      self-checking addon per `specs/addons/TESTING.md`: all `[pass]`, every `[manual]` confirmed by the
      maintainer, and every prior suite still green.

## 10. Out of scope

- **Moving `hafen.render`/`hafen.ghost` under `hafen.world`** — the other deferred ROADMAP item. Unlike
  `hafen.gob`, it is not forced by the shape change: both already have a coherent form and want only a
  rename, so it stays its own feature and both at once would make neither reviewable.
- **New capability**, with **two stated exceptions**: only spelling and return *type* move and nothing
  changes tier (§2.12) — except (a) the unified Grid's `:live()` (§8.7), so one engine identity is not
  split into two Lua entities, and (b) the **Position type** (§2.7), which adds a value object, its
  vector verbs and store marshalling. Both are deliberate; (b) is the larger and can only be done here,
  since doing it later means re-touching every spatial site a second time.
- **Renaming reads to `get*`** — arity is the verb, which is the point.
- The other ROADMAP items (sandbox hardening, hook priority, `:reload <id>`, package layout, allocation
  profiling, `dependencies`) are untouched.

## 11. Context files

- **`API.md`** (beside this file) — the complete before → after inventory; every task names the rows it
  covers, and `/end` checks the file has no unported row left
- `ROADMAP.md` — both merged entries · `FEATURES.md` — the OOP migrations 020/021/023/024/025/027 whose
  pattern §4 repeats
- `decisions/architecture-api.md` — D-013 (one canonical way), D-044/D-045 (the entity mechanism),
  D-056/D-057 (the decision this replaces), D-063/D-064/D-066, D-094 (intern keys), D-099 (cost is shape)
- `decisions/widgets-ui.md` — D-069/D-070/D-077/D-089; `decisions/process.md` — D-085, D-092
- `docs/addons/api/conventions.md` — becomes the statement of §2 · `types.md` — the 19 snapshot shapes
- `docs/addons/api/README.md` — the page index that must stay exact
- `src/io/brodgar/addon/Sandbox.java` — `installHafen`, where today's 34 sections are mounted
- `src/io/brodgar/addon/UiApi.java`, `LuaWidget.java`, `Sheet.java` — §5's builders and the stylesheet
- `src/io/brodgar/addon/CharApi.java` (2,022 lines) — char/study/party/fight, §4's flat half
- `src/io/brodgar/addon/WorldApi.java` (`gridPos`/`fromGridPos`), `MapApi.java` (`gridWorldUL`, `sessloc`
  — the two-step durability lookup) and `Json.java` / `StoreApi.java` — §2.7's Position and its
  marshalling
- `src/io/brodgar/addon/LuaGob.java`, `LuaKin.java`, `LuaSlot.java`, `LuaBuff.java`, `LuaMeter.java`,
  `LuaPagina.java`, `LuaSound.java`, `LuaIconCat.java`, `OptionsMethod.java` — the eleven callable
  namespaces and the arity dispatch §2.9 replaces
- `017-gob-oop/` — the transitional markers §4.9 deletes · `020-kin-oop/`, `027-meters-oop/` — the
  migration pattern · `036-ui-layout/` — D-089 and 036.4's `theme.json` claim · `038-gob-overlays/` —
  D-100/D-102, the lifetime objection §2.3 answers by construction
