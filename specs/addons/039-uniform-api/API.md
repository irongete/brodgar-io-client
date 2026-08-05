# 039 — the complete API surface, before → after

> **What this is.** The exhaustive inventory of every `hafen.*` section, verb and entity method, with
> its spelling before and after this feature. It exists so nothing is forgotten, and it does three jobs:
> the **completeness checklist** for `plan.md` and `tasks.md`, the **porting map** for 10,633 lines of
> Lua and 74 doc pages, and the **source of the retired-name table** that makes an old spelling throw
> naming its replacement (spec §2.10).
>
> **What this is NOT: a contract.** `AREA.md` is explicit — the docs tier IS the contract and there is
> no second designed copy to keep in sync. This file lives inside the feature folder, is measured
> against while the feature is built, and is then frozen as history like `spec.md`. `docs/addons/api/`
> stays the only published surface.
>
> Rows marked **NEW** are entities this feature creates. Rows marked **CUT** disappear with no
> replacement. Extracted from the shipped doc pages, not from memory.

## Conventions used below

Per `spec.md` §2. Eight structural rules and three naming rules generate almost every row:

| # | rule |
|---|---|
| R1 | `hafen.<section>` is callable; `hafen.<section>()` is the section object, a per-addon singleton |
| R2 | a read is the bare noun `:x()`; a write is `:x(v)` and returns self. **One name per property — no `getX`, no `setX`, no `clearX` anywhere in the API** |
| R3 | **the noun names the KIND, the verb says HOW MANY.** A set you can address into is the **singular** kind name returning a collection object — `:list(f)` `:count(f)` `:get(k)` `:find(f)` `:add(…)` `:remove(x)`, **not indexable**. A verb that only answers "give me these" stays **plural** and returns a plain array |
| R4 | a builder is constructed bare and configured by chained setters; no `opts` table survives |
| R5 | an explicit `nil` argument is an **error**, unless the page documents a meaning for it — *undo a layer* (`w:position(nil)`) or *none* (`ov:tint(nil)`). `:info()` is the snapshot hatch on every entity |
| R6 | a boolean property is a property: `:visible()` / `:visible(false)`. `:show()`/`:hide()` are CUT |
| R7 | **ending a thing**: `coll:remove(key)` where the collection owns it and you name it; `:destroy()` where you hold the handle of something you created; `:dispose()` only for an owned *resource* (frees memory now, D-060) |
| R8 | **a distinguished member is a verb on its collection**, not a second accessor: `:current()`, `:selected()`, `:leader()`, `:available()` |
| N1 | expand an abbreviation a reader cannot decode (`mtime` `sdt` `comp` `lvl`); keep the game's own words (`res` `lp` `fep`) |
| N2 | **camelCase, always** — `isPlayer`, `isNew`, `onMap`, `rootPos`, `endKin` |
| N3 | one word per concept — `:distance()` not `dist`/`distance`; and **one `position()`**, a Position object that is both computable and durable |

### R3 worked through, because it is the rule that was wrong in the first draft

`hafen.map.segment()` (no argument) is **the current segment** — a distinguished member, not the
collection. The first draft folded it into `:segments()` and lost it. Under R3 + R8:

```lua
hafen.map():segment():list()        -- every known segment
hafen.map():segment():current()     -- the one you are standing in
hafen.map():segment():get(id)       -- a specific one
```

The plural name was redundant with `:list()` — plurality belongs to the verb, which is the whole
philosophy of this feature applied one level down. **Singular is therefore the rule everywhere**, which
also removes a wart the first draft had: sections were singular (`hafen.buff`, `hafen.kin`) while
sub-collections were plural (`gob:overlays()`, `char():attrs()`). Now both are singular.

**Where plural survives** (R3's second half — a plain array read, nothing to address into):
`w:children()`, `w:items()`, `pag:children()`, `meter:segments()`, `seg:markers(f)`,
`craft:inputs()`/`:outputs()`, `hafen.fight():deck()`, `hafen.menugrid():roots()`,
`quest:conditions()`.

---

# Sections

## `hafen.act()` — the gated tier (unchanged in meaning, R1 only)

> **Shipped in 039.2** — and the four spatial verbs now take a Position.

| before | after | does |
|---|---|---|
| `hafen.act.enabled()` | `hafen.act():enabled()` | is the `actions` grant held (never throws) |
| `hafen.act.moveTo(x, y)` | `hafen.act():moveTo(p)` | walk to a **Position** |
| `hafen.act.clickGob(gob, button, mods)` | `hafen.act():clickGob(gob, button, mods)` | click a game object |
| `hafen.act.item(item, verb, n)` | `hafen.act():item(item, verb, n)` | `take`/`drop`/`transfer`/`iact`/`itemact` |
| `hafen.act.useItemOn(x, y, mods)` | `hafen.act():useItemOn(p, mods)` | apply the held item at a Position |
| `hafen.act.place(x, y, angle, button, mods)` | `hafen.act():place(p, angle, button, mods)` | confirm a placement |
| `hafen.act.select(x1, y1, x2, y2, mods)` | `hafen.act():select(p1, p2, mods)` | rectangle-select, corner to corner |
| `hafen.act.menu(path)` | `hafen.act():menu(path)` | pick an action-menu entry by path |
| `hafen.act.flower(label)` | `hafen.act():flower(label)` | choose a petal on the flower menu |
| `hafen.act.raw(target, msg, …)` | `hafen.act():raw(target, msg, …)` | send a raw `wdgmsg` |

## `hafen.actionbar()` — the hotbar, a collection of Slot

> **Shipped in 039.9.** ✅ every row. Read-only as a set: the bar is a fixed 144 slots, so there is no
> `:add`/`:remove` and what changes is a slot's *content*. A string filter matches a slot's res name.

| before | after | does |
|---|---|---|
| `hafen.actionbar()` | `hafen.actionbar():list()` | all 144 slots, 1-based (D-057) ✅ |
| `hafen.actionbar(n)` | `hafen.actionbar():get(n)` | the slot at the raw 0-based game index ✅ |

## `hafen.asset()` — the files your addon ships, a collection of Asset

> **Shipped in 039.8.** ✅ every row. No `:add` (an asset is a file you shipped) and no `:remove`
> (`a:dispose()` frees the resource now, which is not leaving a set).

| before | after | does |
|---|---|---|
| `hafen.asset(path)` | `hafen.asset():get(path)` | load/intern one, typed by extension ✅ |
| `hafen.asset()` | `hafen.asset():list()` | the ones this addon holds ✅ |

## `hafen.buff()` — the buff bar, a collection of Buff

> **Shipped in 039.9.** ✅ every row. **No `:get`** — a buff has no key (two buffs can share a resource,
> and a `"ch"` uimsg replaces a live buff's resource), so a needle is a search and `:get` throws naming
> `:find`.

| before | after | does |
|---|---|---|
| `hafen.buff()` | `hafen.buff():list()` | active buffs, bar order ✅ |
| `hafen.buff(needle)` | `hafen.buff():find(needle)` | first whose res or name contains the needle ✅ |

## `hafen.char()` — the character sheet *(OOP migration, spec §4.1)*

| before | after | does |
|---|---|---|
| `hafen.char.attr(name)` | `hafen.char():attr():get(name)` | one attribute |
| `hafen.char.attrs()` | `hafen.char():attr():list()` | every populated attribute |
| `hafen.char.lp()` | `hafen.char():lp()` | learning points (scalar) |
| `hafen.char.weight()` | `hafen.char():weight()` | carried weight (scalar) |
| `hafen.char.food()` | `hafen.char():food()` | **NEW** Food entity — FEP and hunger |
| `hafen.char.skills()` | `hafen.char():skill():list()` | known skills |
| `hafen.char.skill(name)` | `hafen.char():skill():find(name)` | substring lookup — **was a bool, now the Skill** |
| `hafen.char.skillsAvailable()` | `hafen.char():skill():available()` | buyable skills (R8) — carries `:cost()` |
| `hafen.char.credos()` | `hafen.char():credo():list()` | the Credos tab |
| `hafen.char.experiences()` | `hafen.char():experience():list()` | lore and experiences seen |

## `hafen.client()` — client settings

> **Shipped in 039.10.** ✅ every row — and this was the API's one colon-on-the-namespace, so the
> section is now called like every other. The 18 option methods keep their spelling and gain the §2.9
> nil refusal; the keybinding registry's `get`/`set` pair is below.

| before | after | does |
|---|---|---|
| `hafen.client:options()` | `hafen.client():options()` | the Options handle (5 sub-handles) |
| `hafen.client:profiling()` | `hafen.client():profiling()` | the profiler read surface |
| `…:options():keybindings()` | unchanged | the keybinding registry |

`options()` and `profiling()` stay plural-free singular **handles**, not collections — you cannot address
into them.

## `hafen.craft()` — the recipe window *(OOP migration, spec §4.4)*

| before | after | does |
|---|---|---|
| `hafen.craft.current()` | `hafen.craft():current()` | **NEW** Craft entity, or nil (R8) |
| `hafen.craft.make(all)` | `hafen.craft():current():make(all)` | gated: press Craft / Craft All |

**Flagged for plan.md.** Moving `make` onto the entity changes the failure mode: with no craft window
open, `hafen.craft.make()` used to be a plain no-op and `hafen.craft():current():make()` indexes `nil`.
Either `:current()` returns an inert entity whose `:exists()` is false (the `hafen.kin(<unknown id>)`
precedent, D-056) or the page documents the guard. Decide before the task is written.

## `hafen.event()` — the bus  *(renamed from `hafen.events`, see "Section names" below)*

> **Shipped in 039.1.**

| before | after | does |
|---|---|---|
| `hafen.events.on(name, fn)` | `hafen.event():on(name, fn)` | subscribe; hands back a Subscription |

## `hafen.fight()` — the maneuver deck *(OOP migration, spec §4.6)*

| before | after | does |
|---|---|---|
| `hafen.fight.maneuvers(filter)` | `hafen.fight():maneuver():list(filter)` | **NEW** Maneuver collection |
| `hafen.fight.deck()` | `hafen.fight():deck()` | **NEW** DeckCard array — a layout, plural by R3 |
| `hafen.fight.summary()` | `hafen.fight():summary()` | **NEW** FightSummary entity |
| — | `hafen.fight():target()` | **NEW** the combat target — `:gob()` restores 017's regression |

## `hafen.font()` — font handles, a collection of FontHandle

> **Shipped in 039.8.** ✅ every row. The members are the built-ins this addon has named, so `:list()`
> and a string filter over the name come free; there is no `:add` and no `:remove` (D-060).

| before | after | does |
|---|---|---|
| `hafen.font(name)` | `hafen.font():get(name)` | a built-in font by name (engine-owned, D-060) ✅ |

## `hafen.ghost()` — client-only gobs, a collection of Ghost

> **Shipped in 039.8** — ✅ every row, with **one correction the in-game round forced**: the constructor
> is `:add(res, p)`, not `:add(res)`. The scene resolves the TILE under a gob as it enters it, so an
> entity with no place raises *waiting for map data* out of the caller's own chain — it is not an inert
> state but an unbuildable one, and §2.5's own rule puts a required argument on the constructor (D-127).

| before | after | does |
|---|---|---|
| `hafen.ghost.new(opts)` | `hafen.ghost():add(res, p)` + setters (R4) | create one ✅ |
| `hafen.ghost.list(filter)` | `hafen.ghost():list(filter)` | your addon's ghosts ✅ |
| `g:destroy()` | `hafen.ghost():remove(g)` | end one (R7 — the collection owns it) ✅ |

## `hafen.gob` — **DELETED into `hafen.world()`** *(spec §3.3, D-066)*

> **Shipped in 039.2.**

| before | after |
|---|---|
| `hafen.gob(id)` | `hafen.world():gob():get(id)` |

The **Gob object keeps every method and its own doc page**; only the entry point moves.

## `hafen.hook()` — interception

> **Shipped in 039.1.**

| before | after | does |
|---|---|---|
| `hafen.hook.input(…)` | `hafen.hook():input(…)` | L1: keyboard/mouse before the client |
| `hafen.hook.action(…)` | `hafen.hook():action(…)` | L2: an outgoing `wdgmsg` |
| `hafen.hook.message(…)` | `hafen.hook():message(…)` | L3: an incoming `uimsg` |
| `hafen.hook.grab(…)` | `hafen.hook():grab(…)` | take the mouse |

## `hafen.http()` — network (allowlisted)

> **Shipped in 039.1.**

| before | after | does |
|---|---|---|
| `hafen.http.get(url, opts, cb)` | `hafen.http():get(url, cb)` + setters (R4) | async GET |
| `hafen.http.post(url, opts, cb)` | `hafen.http():post(url, cb)` + setters | async POST |

`opts.headers` / `opts.timeout` become setters on the request object (R4). **`get`/`post` are HTTP
methods, not R2 accessors** — the `get`/`set` ban does not reach them.

## `hafen.json()` — serialisation

> **Shipped in 039.1.**

| before | after | does |
|---|---|---|
| `hafen.json.parse(s)` | `hafen.json():parse(s)` | text → a Lua value (a **value**, §2.8) |
| `hafen.json.encode(v)` | `hafen.json():encode(v)` | a Lua value → text |

## `hafen.kin()` — the kin roster, a collection of Kin

> **Shipped in 039.9.** ✅ every row. `:get(<number>)` is **never nil** (the D-056 asymmetry
> `hafen.world():gob():get(id)` also has); `:get(<name>)` is exact and misses to nil; `:add` hands back
> the **collection**, because there is no Kin yet — the server decides and `KinChanged` reports it.

| before | after | does |
|---|---|---|
| `hafen.kin()` | `hafen.kin():list()` | the roster ✅ |
| `hafen.kin(idOrName)` | `hafen.kin():get(idOrName)` | one kin — number by id, string by exact name ✅ |
| `hafen.kin():find(nameOrId)` | `hafen.kin():find(f)` | first match on the standard filter ✅ |
| `hafen.kin():add(secret)` | unchanged | gated: add by hearth secret ✅ |

## `hafen.log()` — the console

> **Shipped in 039.1.**

| before | after | does |
|---|---|---|
| `hafen.log(msg)` | `hafen.log():write(msg)` | print, tagged with your addon id — **641 Lua sites**, spec §8.1 |

## `hafen.map()` — the RECORDED map (on disk)

> **Shipped in 039.4** — five collections, and the Grid entity is now the one both halves hand back.

| before | after | does |
|---|---|---|
| `hafen.map.segment()` | `hafen.map():segment():current()` | **the segment you are in** (R8 — the row the first draft lost) |
| `hafen.map.segment(id)` | `hafen.map():segment():get(id)` | a specific segment |
| `hafen.map.segments()` | `hafen.map():segment():list()` | every known segment |
| `hafen.map.grid(gridId)` | `hafen.map():grid():get(gridId)` | one Grid by its server-published id |
| `hafen.map.markers.list(filter)` | `hafen.map():marker():list(filter)` | every marker matching the filter |
| `hafen.map.markers.nearest(filter)` | `hafen.map():marker():nearest(filter)` | the closest matching marker |
| `hafen.map.markers.add(name, x, y, opts)` | `hafen.map():marker():add(name, p)` + setters (R4) | drop a pin at a Position |
| `hafen.map.markers.remove(marker)` | `hafen.map():marker():remove(m)` | delete a pin |
| `hafen.map.icons(res)` | `hafen.map():icon():get(res)` | one icon category |
| `hafen.map.icons()` / `(filter)` | `hafen.map():icon():list(filter)` | the registry |
| `hafen.map.overlay(tag)` / `(tag, on)` | `hafen.map():overlay():get(tag)` → `:shown()` / `:hold()` / `:release()` | a display toggle — a **hold**, not a switch (D-097), so the verbs stop pretending to be a boolean setter |
| `hafen.map.overlays()` | `hafen.map():overlay():list()` | every display toggle and its state |

`hafen.map.icons` used to split its argument **by shape** — a string containing `/` meant a resource,
anything else meant a filter (D-093). `:get(res)` vs `:list(filter)` **deletes that split**: the verb
says which you meant, so the heuristic is no longer needed.

## `hafen.menugrid()` — the action menu, a collection of Pagina

> **Shipped in 039.9.** ✅ every row, with **one correction**: the old `:find(text)` returned *every*
> match, which §2.2 gives to `:list(filter)` — `:find` is the first-match verb everywhere else, and the
> shared collection carries exactly one of each. So the plural search is `:list(text)` and `:find` is
> its singular twin. The needle is the display name, and the match is now case-**sensitive**, like every
> other string filter in the API.

| before | after | does |
|---|---|---|
| `hafen.menugrid()` | `hafen.menugrid():list()` | the whole catalogue ✅ |
| `hafen.menugrid(key)` | `hafen.menugrid():get(key)` | one entry — a `/` means resource, else display name ✅ |
| `hafen.menugrid():find(text)` | `hafen.menugrid():list(text)` | every entry whose display name contains the text ✅ |
| `hafen.menugrid():roots()` | unchanged | the root-screen entries — plain array, plural by R3 ✅ |

## `hafen.meter()` — the HUD bars, a collection of Meter

> **Shipped in 039.9.** ✅ every row. **No `:get`**, for the same reason as `hafen.buff()`: a meter has
> only a server-published resource name several bars could share.

| before | after | does |
|---|---|---|
| `hafen.meter()` | `hafen.meter():list()` | every meter, HUD order ✅ |
| `hafen.meter(needle)` | `hafen.meter():find(needle)` | first whose resource name contains the needle ✅ |

## `hafen.party()` — the party *(OOP migration, spec §4.3)*

| before | after | does |
|---|---|---|
| `hafen.party.members()` | `hafen.party():list()` | **NEW** PartyMember collection, sequence order |
| `hafen.party.member(id)` | `hafen.party():get(id)` | one member, by gob id |
| `hafen.party.leader()` | `hafen.party():leader()` | the leader, or nil (R8) |

## `hafen.player()` — your character (a section of one, R1)

> **Shipped in 039.10.** ✅ every row. An unknown verb on the section object **throws** naming what
> does exist, as on every other section — it was the one that still read `nil`.

| before | after | does |
|---|---|---|
| `hafen.player():gob()` | unchanged | your own Gob |
| `hafen.player():name()` | unchanged | the local character's name |
| `hafen.player():worldToScreen(x, y)` | `hafen.player():worldToScreen(p)` | project a Position to a screen pixel — answers plain `{x, y}` px, **not** a Position |

## `hafen.quest()` — the quest log  *(renamed from `hafen.quests`; OOP migration, spec §4.5)*

| before | after | does |
|---|---|---|
| `hafen.quests.list(filter)` | `hafen.quest():list(filter)` | **NEW** Quest collection |
| `hafen.quests.selected()` | `hafen.quest():selected()` | the quest open in the log (R8) |
| — | `hafen.quest():get(id)` | one quest by id |

## `hafen.render()` — things drawn in the world at a fixed place

> **Shipped in 039.8** — ✅ every row, with the same `, p` correction as `hafen.ghost()` above (D-127).

| before | after | does |
|---|---|---|
| `hafen.render.sprite(opts)` | `hafen.render():sprite():add(imageAsset, p)` + setters | a flat image in the world ✅ |
| `hafen.render.object(opts)` | `hafen.render():object():add(meshAsset, p)` + setters | a glTF model in the world ✅ |
| `s:destroy()` / `o:destroy()` | `…:sprite():remove(s)` / `:object():remove(o)` | end one (R7) ✅ |
| — | `hafen.render():sprite():list(filter)` | your addon's sprites — the `ghost.list` shape, now on both ✅ |

## `hafen.slash()` — console commands

> **Shipped in 039.1.**

| before | after | does |
|---|---|---|
| `hafen.slash.register(name, fn)` | `hafen.slash():register(name, fn)` | route `:name args…` to `fn(args)` |

## `hafen.sound()` — audio, a collection of Sound

> **Shipped in 039.9.** ✅ every row. The two halves address **different sets** on purpose: `:get(name)`
> reaches any clip the game owns (sound resources are not enumerable, so a Sound exists on demand),
> while `:list()` is only what this addon still has in the air. Hence no `:add` (playing is
> `s:play(volume)`) and no `:remove` (silencing is `s:stop()`).

| before | after | does |
|---|---|---|
| `hafen.sound(name)` | `hafen.sound():get(name)` | the Sound for that resource name ✅ |
| `hafen.sound()` | `hafen.sound():list()` | your addon's still-playing Sounds ✅ |

## `hafen.speed()` — the movement selector

> **Shipped in 039.10.** ✅ every row. The write half of `:current(n)` keeps the `actions` gate and
> returns the section, so writes chain; `:name(nil)` is an error rather than a shorthand for the current one.

| before | after | does |
|---|---|---|
| `hafen.speed.get()` / `.set(n)` | `hafen.speed():current()` / `:current(n)` | read / write (gated) the speed, `0..3` — R2 collapses the pair |
| `hafen.speed.max()` | `hafen.speed():max()` | highest selectable |
| `hafen.speed.name(n)` | `hafen.speed():name(n)` | display name of speed `n` |

## `hafen.store()` — saved variables

> **Shipped in 039.10.** ✅ both rows, and the flagged question is settled: `:get(name)` hands back the
> **live persisted table**, asserted across a `:reload`. An **undeclared** name throws listing the declared
> ones (the set is closed by the manifest at load), and the old field spelling throws from a **per-owner**
> `__index` built off that manifest — a static retired table cannot know an addon's own variable names.

| before | after | does |
|---|---|---|
| `hafen.store.<name>` | `hafen.store():get(name)` | one declared persisted table |
| `hafen.store.flush()` | `hafen.store():flush()` | write changed tables now |

**Flagged for plan.md.** `hafen.store.<name>` is a *declared field*, not a verb — an addon writes
`hafen.store.cfg.foo = 1`. Under R1 that becomes `hafen.store():get("cfg").foo = 1`, and the returned
table **must be the live persisted table, not a copy**, or saving silently stops working. Assert it.

## `hafen.study()` — the study window *(OOP migration, spec §4.2)*

| before | after | does |
|---|---|---|
| `hafen.study.slots()` | `hafen.study():slot():list()` | **NEW** StudySlot collection |
| `hafen.study.summary()` | `hafen.study():summary()` | `{lp, attention, cost}` totals |

## `hafen.time()` — the game clock

> **Shipped in 039.1.**

| before | after |
|---|---|
| `hafen.time.clock()` | `hafen.time():clock()` |
| `hafen.time.dayFraction()` | `hafen.time():dayFraction()` |
| `hafen.time.isNight()` | `hafen.time():isNight()` |
| `hafen.time.season()` | `hafen.time():season()` |
| `hafen.time.moon()` | `hafen.time():moon()` |
| `hafen.time.yearFraction()` | `hafen.time():yearFraction()` |

## `hafen.timer()` — scheduling

> **Shipped in 039.1.**

| before | after | does |
|---|---|---|
| `hafen.timer.after(s, fn)` | `hafen.timer():after(s, fn)` | run once |
| `hafen.timer.every(s, fn)` | `hafen.timer():every(s, fn)` | run repeatedly |

`hafen.timer()` additionally answers `:list(filter)` / `:count(filter)` / `:find(filter)` over this addon's
live timers: a section that holds exactly one thing IS that thing (§2.1), and this is where 039.1's
`LuaCollection` is exercised. A timer has no name, so a string filter is refused; there is no `:remove`,
because a timer ends with `t:cancel()` and two spellings for one operation is the dual style R2 removes.


## `hafen.ui()` — the UI (the section; **the root moves**)

> **Shipped in 039.5** — the section, the root as `:root()`, and every lookup as a colon verb — the three
> builder rows (`window`, `widget`, `overlay`) in **039.6**, where they became verbs on the section whose
> product is configured by chained setters, and the last row, `skin`, in **039.7**: it became a Sheet of
> Rules, and with it the callable table is empty and the section is mounted plainly again. ✅ every row.

| before | after | does |
|---|---|---|
| `hafen.ui()` | `hafen.ui():root()` | the top of the client tree — **36 sites**, the collision |
| `hafen.ui(selector)` | `hafen.ui():find(selector)` | first match in tree order, or nil |
| `hafen.ui.all(selector)` | `hafen.ui():all(selector)` | every match, empty array never nil |
| `hafen.ui.node(id)` | `hafen.ui():node(id)` | the widget for a server widget id |
| `hafen.ui.at(x, y)` | `hafen.ui():at(x, y)` | the deepest widget under a root-coord point |
| `hafen.ui.mouse()` | `hafen.ui():mouse()` | the cursor in root coords |
| `hafen.ui.inventory()` | `hafen.ui():inventory()` | your main backpack grid |
| `hafen.ui.equipment()` | `hafen.ui():equipment()` | your worn-equipment grid |
| `hafen.ui.hand()` | `hafen.ui():hand()` | the Item on the cursor *(becomes an entity, §4.8)* |
| `hafen.ui.on(sel, ev, fn)` | `hafen.ui():on(sel, ev, fn)` | wait for `"appear"`/`"disappear"` |
| `hafen.ui.window(opts)` | `hafen.ui():window()` + setters (R4) | your own window |
| `hafen.ui.widget(opts)` | `hafen.ui():widget()` + setters | a bare rectangle |
| `hafen.ui.overlay(fn)` | `hafen.ui():overlay():onDraw(fn)` | a HUD overlay |
| `hafen.ui.skin{…}` / `(nil)` | `hafen.ui():sheet()` → Rule objects, `:install()` / `:drop()` | the stylesheet, §"Sheet" below ✅ |

## `hafen.world()` — the LIVE world

> **Shipped in 039.2**, and `:grid()` **in 039.4**: its members are the unified Grid entity, the very
> object `hafen.map():grid()` hands back.

| before | after | does |
|---|---|---|
| `hafen.gob(id)` | `hafen.world():gob():get(id)` | one object by id — **never nil**, see below |
| `hafen.world.gobs(filter)` | `hafen.world():gob():list(filter)` | every matching loaded object |
| `hafen.world.count(filter)` | `hafen.world():gob():count(filter)` | how many match |
| `hafen.world.nearest(filter)` | `hafen.world():gob():nearest(filter)` | closest match to the player |
| `hafen.world.within(r, filter)` | `hafen.world():gob():within(r, filter)` | matches within `r` world units |
| `hafen.world.tile(x, y)` | `hafen.world():tile(p)` | tileset id + resource name at a Position |
| `hafen.world.height(x, y)` | `hafen.world():height(p)` | terrain height there |
| — | `hafen.world():position(x, y)` | **NEW** build a Position from session world coords |
| — | `hafen.world():position(info)` | **NEW** rebuild one from a stored `{gridId, x, y}` |
| `hafen.world.grid(x, y)` | `hafen.world():grid():at(p)` | the grid at a Position — a **Grid**, see below |
| — | `hafen.world():grid():get(id)` | the same Grid by server id — the door `hafen.map()` also opens |
| — | `hafen.world():grid():list()` | the grids streamed in right now |
| `hafen.world.gridPos(x, y)` | **CUT** — a Position *is* the anchor | |
| `hafen.world.fromGridPos(a)` | **CUT** → `hafen.world():position(info)` | |
| `hafen.world.worldToTile(x, y)` | `p:tileCoord()` | moved onto Position |
| `hafen.world.tileToWorld(tx, ty)` | `hafen.world():tileToWorld(tx, ty)` | tile → world coord |
| `hafen.world.tileToGrid(tx, ty)` | `hafen.world():tileToGrid(tx, ty)` | tile → grid coord |
| `hafen.world.screenToWorld(sx, sy, fn)` | `hafen.world():screenToWorld(sx, sy, fn)` | async raycast; `fn` receives a **Position** |
| `hafen.world.snapPlace(x, y, fine)` | `hafen.world():snapPlace(p, fine)` | snap a Position to the placement grid |
| `hafen.world.snapAngle(a, fine)` | `hafen.world():snapAngle(a, fine)` | snap a facing |
| `hafen.world.placeGrid()` | **CUT** → `hafen.client():options():interface():posGran()` | see below |
| `hafen.world.placeAngle()` | **CUT** → `…:interface():angGran()` | see below |

**`placeGrid`/`placeAngle` are CUT as duplicates, and this is a defect the review found, not a rename.**
`WorldApi:291` reads `MapView.plobpgran` and `InterfaceOptions:55` reads **the same field**; likewise
`plobagran` at `WorldApi:310` and `InterfaceOptions:68`. Two doors onto one value is the dual style
D-013 forbids — and worse, **the units disagree**: `placeAngle()` hands back the raw `plobagran` while
`angGran()` hands back `180/plobagran` **degrees**. The options door survives because it also *writes*.
`snapPlace`/`snapAngle` stay: they are snapping arithmetic, not settings.

**The gob verbs fold into one read-only collection**, `hafen.world():gob()`. The first draft kept
`hafen.world():gob(id)` beside `:gobs(filter)` — which is precisely the singular/plural dual pair R3
kills at `seg:grid`/`seg:grids` and `grid:overlay`/`grid:overlays`, left standing in the one place it is
most used. A collection needs no `:add`/`:remove` to be a collection: `hafen.buff()`, `hafen.meter()` and
`hafen.actionbar()` are all read-only too. It also disambiguates `hafen.world():count(f)`, which never
said *count of what*.

**`:gob():get(id)` is NEVER nil**, and that must be stated on the page because living under *the live
world* invites the opposite reading. It is the shipped contract — `gob.md` today: *"always returns a Gob,
even for an id that is not loaded or never existed. That is what lets you anchor to a gob before it
streams in; `:exists()` is the liveness test."* — and it is the same deliberate asymmetry as
`hafen.kin():get(<unknown id>)` (D-056). The section a verb lives under is a naming fact, not a semantic
one: `hafen.map():marker():get(id)` does not change what a marker is either. `:nearest()` and `:find()`
still answer nil; `:list()` still answers an empty array.

**Events are unaffected.** `GobAdded` and `GobRemoved` hand the **Gob object itself** — no addon has ever
needed `hafen.gob(id)` in a handler, and `tagger:81` (`function(g) tagged[g:id()] = nil end`) is what
every one of them looks like. A removed gob's object keeps answering `:id()` with `:exists()` false, so
a `GobRemoved` handler reads exactly as it does today.

**`hafen.world():grid(x, y)` and `hafen.map():grid():get(id)` are different things with one word**, and
that is 037's LIVE/RECORDED split showing through rather than a collision: the live one is a *query by
position* answering the value `{id, gc}`, the recorded one addresses a **Grid entity** by the server's
grid id. Both pages must say so on the verb, since the word alone cannot.

## `hafen.wound()` — wounds  *(renamed from `hafen.wounds`; OOP migration, spec §4.7)*

| before | after | does |
|---|---|---|
| `hafen.wounds.list(filter)` | `hafen.wound():list(filter)` | **NEW** Wound collection |
| `hafen.wounds.has(needle)` | `hafen.wound():find(needle)` | first match — **was a bool, now the Wound** |

## `hafen.world()` and `hafen.map()`: how alike, and where not

The two sections are the **same domain read two ways** — LIVE (streamed around you, gone at logout) and
RECORDED (written to disk, survives). Where they hold the same concept they must spell it the same; where
they do not, forcing symmetry would invent surfaces the game does not have.

| concept | `hafen.world()` — LIVE | `hafen.map()` — RECORDED |
|---|---|---|
| **grid** | `:grid():at(x,y)` `:get(id)` `:list()` | `:grid():get(id)` |
| **tile at a point** | `:tile(p)` | `grid:tile(c)` |
| **height at a point** | `:height(p)` | `grid:height(c)` |
| **gob** | `:gob():get/list/find/count/nearest/within` | — the client records no gobs |
| **segment** | — a segment is a disk structure | `:segment():current/get/list` |
| **marker** | — a marker is recorded by definition | `:marker():list/nearest/add/remove` |
| **overlay mask** | — | `grid:overlay():get(tag)` |
| **minimap drawing** | — | `grid:image(lvl)` |
| **icon registry** | — | `:icon():get(res)` |
| **the bridge** | *(gone — a **Position** is durable by itself)* | *(gone — `marker:position()` is one)* |

**One Grid entity, two doors.** This is the part worth doing, and it is better founded than a tidy-up:
**both halves already key on the same server-published grid id** — `WorldApi:175` publishes
`MCache.Grid.id`, `MapApi:130` interns `LuaMapGrid` on the same `long`. So a grid is *one thing* whose
live and recorded halves are two reads, not two entities:

```lua
local g = hafen.world():grid():at(p)   -- or hafen.map():grid():get(id) — the same object
g:live()             -- streamed in right now?
g:exists()           -- written to the database?
g:tile(c)            -- recorded terrain, nil if this grid was never saved
g:segmentCoord()     -- which cell of the segment it is
g:position()         -- its upper-left corner, as a Position
```

037.2's headline result was that `grid:tile(c)` and `hafen.world.tile(x, y)` **agree** — two subsystems
derived independently landing on the same tileset. Under one entity that agreement stops being a
cross-check you have to remember to make and becomes a property of the object. It also makes the honest
case visible: a grid you are standing on that has not been saved yet is `:live()` true, `:exists()`
false, and every recorded read nil.

**What must NOT be made symmetric.** There is no `hafen.map():gob()` (the client records no gobs) and no
`hafen.world():marker()` (a marker is a disk record by definition). The asymmetry is a fact about the
game, not a gap in the API — D-092: a boundary is a decision on the page, not a hole in the code.

**Where the coordinate math lives.** `worldToTile`/`tileToWorld`/`tileToGrid`/`screenToWorld` stay on
`hafen.world()`: they convert **session** coordinate spaces, which are live by nature, and a recorded
Grid speaks segment coords instead.

## Section names: three plurals are renamed

> **Shipped in 039.1.**

`hafen.quests` → `hafen.quest`, `hafen.wounds` → `hafen.wound`, `hafen.events` → `hafen.event`.

Every other section is already singular — 025 deliberately hard-cut `hafen.buffs` for `hafen.buff`, and
`hafen.items` is gone. These three were the only survivors, and leaving them would reintroduce under
R3 exactly the wart R3 removes. **86 Lua sites** (quests 12, wounds 11, events 63). Reversible: it is a
naming call, not a mechanism, and it is listed in `spec.md` §8 for objection.

---

## Naming

The engine's field names leak into the API as abbreviations a reader cannot decode — `gc`, `tc`, `sc`,
`mtime`, `sdt`, `comp`. Three naming rules; the `nil` discipline and the Position type follow in their
own sections below.

**N1 — expand what a reader cannot decode; keep what is the game's own word.** `mtime` -> `modified`,
`sdt` -> `spawnData`, `comp` -> `composite`, `lvl` -> `level`. But `res`, `lp` and `fep` stay: those are
not abbreviations the API invented, they are what the engine and the game's own UI call these things.
**D-061** (the API's vocabulary comes from the engine, not the genre) governs which *concepts* exist —
it refuses "debuff" and "radar" because the engine has neither — and does not require the engine's
*spelling*.

**N2 — camelCase, always.** Already the majority (`isNight`, `gridPos`, `worldToScreen`,
`skillsAvailable`, `renderScale`, `masterVolume`) with five stragglers: `isplayer` -> `isPlayer`,
`isnew` -> `isNew`, `onmap` -> `onMap`, `rootpos` -> `rootPos`, `endkin` -> `endKin`.

**N3 — one word per concept.** The sweep found two dual names: `marker:dist()` / `gob:distance()`
(unified to `:distance()`), and `hafen.world.gridPos()` / `marker:anchor()`, which return the identical
`{gridId, x, y}` shape. The second is not renamed — it is **deleted**, because the Position type below
makes both unnecessary.

## `nil`: an error, except where it means something

`clearX()` was in the first draft to remove a live silent-destruction class — `w:skin(computeStyle())`
dropping your style, `gob:overlay(k, buildSpec())` removing the overlay. **R3/R7 already deleted both**:
an overlay goes with `gob:overlay():remove(k)`, a style with `w:rule():remove()`. Nothing is left that a
stray `nil` can destroy, so the `clear` verbs went with them — they were a `set`/`clear` pair in spirit,
which is the very thing R2 removes.

What survives is the precise rule, and it is small enough to enumerate:

| `nil` means | where | why it is safe |
|---|---|---|
| **undo your layer** (D-089) | `w:position(nil)`, `w:size(nil)`, `w:replace(nil)` | the widget returns to what the *user* had; visible, immediate, reversible |
| **none** | `ov:tint(nil)`, `sprite`/`object`/`ghost` `:tint(nil)` | "no tint" is a real value |
| **an error** | everywhere else | it is an accident, and there is nothing to undo |

That last row is the one that still earns its keep: `cat:show(cfg.enabled)` with a missing key, and
`opt:name(v)` across the 18 options, are **silent no-op writes** today — the call reads instead of
writing and nothing says so. Those properties have no undo, so refusing `nil` costs nothing.

**The one corner where `nil` still destroys** is `w:replace(nil)`: a `makeView()` that returns nil
undoes the substitution and destroys your view. It is visible (the window changes in front of you) and
reversible (call it again), which puts it in `position(nil)`'s class rather than `skin(nil)`'s — but
plan.md should carry it as a known edge, not discover it.

## The Position type — one `position()`, everywhere, durable

> **Shipped in 039.2.**

A position was a plain `{x, y}` table, which can only be **one** of the two things a position needs to
be: a point you can do arithmetic on, or a place you can save and send. An **object can be both**, and
everything else in this API is already an object.

```lua
local p = gob:position()

p:x()  p:y()                          -- session world components, when you need numbers
p:offset(0, 22)                       -- a NEW Position 22 south; the engine crosses grid boundaries
p:distance(other)                     -- engine-side
p:tileCoord()                         -- the tile it sits in       (was hafen.world.worldToTile)
p:durable()                           -- can it be saved? (see below)
p:info()                              -- {gridId, x, y} — the wire/store form

hafen.act():moveTo(p)                 -- every spatial verb takes a Position
hafen.store():get("cfg").home = p     -- persists as {gridId, x, y}: durable and shareable
```

**There is exactly one position verb in the whole API.** No `gridPosition`, no `sessionPosition`, no
`worldPos`, no `anchor`. `hafen.world.gridPos`, `fromGridPos` and `marker:anchor()` are **CUT** — the
Position *is* the anchor.

**Why the arithmetic moves into the engine, and why that is the point.** A grid is 100 tiles x 11 =
**1100 world units**. `p.y + 22` is correct on a continuous coordinate and a *bug* on a grid-relative
one: at `y = 1095` it yields 1117, past the grid edge, when the answer is a different grid id. The 38
arithmetic sites in the shipped addons — `moveTo(p.x, p.y + SOUTH)` at `walker:88`, the four-corner box
at `walker:102` — become `p:offset(...)`. That single move is what lets one `position()` be both
computable and durable, and it is why `atlas:85` (`(sc.x * SIDE) + wt.x`, flattening by hand) stops
being something every addon has to write.

**Durability: EXPLORED, not loaded.** Making a Position durable needs the grid id of the ground under
it, and the lookup is two steps:

1. the grid is streamed in -> `MCache` has the id;
2. otherwise, session coord -> segment coord via `sessloc` -> the recorded grid at that coord.

Step 2 is pure arithmetic and needs nothing streamed — `MapApi:487` says so outright: *"it answers for
any grid in the player's current segment **whether or not that ground is streamed in right now**"*. So a
Position is durable **anywhere you have been**, and fails only on ground you have never visited, which is
also ground you cannot act on. `:durable()` reports it and the docs must state the boundary.

**Two consequences that are real work, not phrasing.**

- **`hafen.store` and `hafen.json` must marshal it.** Today [`Json.java:31`] degrades a userdata to a
  quoted `tostring` in *forgiving* mode — which is exactly the mode the store uses — so a Position would
  persist as **garbage, silently**. They must emit `{gridId, x, y}` and reconstruct on read. Non-optional.
- **`:info()` and `:x()`/`:y()` do not report the same numbers**: `:info()` is the durable form (a grid
  id plus the *within-grid* offset), `:x()`/`:y()` are *session world* components. Both pages must say so.

### What is NOT a Position

> **Shipped in 039.2** — the lattice verbs keep their names, and a screen point stays a plain table.

A **lattice cell** is an index, not a place, and keeps its own name because the two are genuinely
different kinds of thing:

| verb | what | unit |
|---|---|---|
| `grid:segmentCoord()` | which cell of the segment this grid is | grids |
| `marker:segmentTile()` | which tile of the segment this marker is on | tiles |
| `grid:tile(c)` / `:height(c)` / `mask:covers(c)` | argument is a **within-grid** tile coord `0..99` | tiles |

**Screen pixels are not Positions.** `w:position()`, `w:rootPos()` and `worldToScreen` answer plain
`{x, y}` **px**. The verb is still `position()` — *where is this thing, in the space that thing lives
in* — and the entity tells you which space: a Gob lives in the world, a Widget lives on the screen. A
screen position has no durable form because the screen is not a place. This is safe rather than
ambiguous now that Position is a **type**: passing `w:position()` to `hafen.act():moveTo()` throws
instead of walking you somewhere wrong.

The stylesheet's geometry properties follow the verbs: the rule key **`pos` becomes `position`**
(`docs/addons/api/ui/style/geometry.md`), `size` and `anchor` unchanged.

**`grid:sessionCoord()` is not created.** The live `gc` it would have exposed has **no consumer** —
grep finds it only in the 037.1 suite that tests it, in none of the eleven example addons.


---

# Entities

## Gob — unchanged except its overlays

> **Shipped in 039.2** (`:position()`, `:isPlayer()`) **and 039.3** (the overlay rows below).

`:id() :exists() :facing() :name() :health() :moving() :speed() :speech() :icon() :kin() :info()`
unchanged. `:pos()` -> **`:position()`** (a Position), `:isplayer()` -> `:isPlayer()` (N2), and
`:distance(other)` stays — it is now the same verb Position carries.

| before | after | does |
|---|---|---|
| `gob:overlay()` | `gob:overlay():list()` | every overlay: yours first, then the game's |
| `gob:overlay(key)` | `gob:overlay():get(key)` | that one |
| `gob:overlay(key, spec)` | `gob:overlay():add(key)` + setters (R4) | attach or replace |
| `gob:overlay(key, nil)` | `gob:overlay():remove(key)` | remove it |

The overlay **spec table** (`draw text image model ghost color offset scale alpha tint a billboard sdt`)
becomes setters on the Overlay the `:add` returns — R4, and the maintainer's own worked example. `sdt` is
spelt `spawnData` and `offset` takes the same units it does today (screen px or world units by kind).

## Overlay (`ov`)

> **Shipped in 039.3** — the spec table's `a` is `:rotate(a)`, which the entity already carried, and
> `:offset` takes positional numbers (two on a screen kind, three on a world one) rather than a table.

`:key() :gob() :native() :kind() :res() :count() :exists() :info()` read;
`:tint(c) :alpha(a) :scale(s) :rotate(a)` — already R2-shaped; `ov:pos()` -> **`ov:position()`**.
`ov:tint(nil)` stays — "no tint" is a real value, not an accident (R5).

## Kin

`:id() :name() :group() :color() :online() :exists() :gob() :info()`; gated writes
`:rename(n) :endkin() :forget()`.
**`kin:setGroup(g)` -> `kin:group(g)`** — `:group()` already reads it, so R2 collapses the pair.
**`kin:endkin()` -> `kin:endKin()`** (N2). ✅ **039.9**

## Slot

`:index() :empty() :res() :name() :cooldown() :exists() :info()`; gated `:use(mods)`.
**`slot:set(res)` → `slot:res(name)`** — `:res()` already reads it, R2 again. ✅ **039.9**

## Pagina, Sound, Buff, Meter — unchanged  ✅ **039.9**, except `pag:isnew()` -> `:isNew()` (N2)

- Pagina: `:res() :name() :tooltip() :hotkey() :path() :parent() :children() :isNew() :exists() :info() :use()`
- Sound: `:res() :playing() :info() :play(volume) :stop()`
- Buff: `:res() :name() :amount() :duration() :number() :exists() :info()`
- Meter: `:res() :index() :value() :color() :segments() :exists() :info()`

`pag:children()` and `meter:segments()` stay plural — plain array reads (R3).

## Widget — the biggest entity

> **Shipped in 039.5**, except the `w:skin()` row, which is **039.7**'s (it becomes a Rule, not a rename);
> the thirteen builder setters below landed in **039.6**, each with its matching bare read, and `:parent(w)`
> gained its write half there. `:rootpos()` -> **`:rootPos()`** landed with the rest (N2); the `:info()`
> snapshot keeps its own `pos` key, which no row here renames.

Reads unchanged: `:type() :role() :res() :id() :children() :parent() :text() :items() :exists() :info()
:walk(fn) :at(coord) :style()`.

| before | after | does |
|---|---|---|
| `w:pos()` / `(x,y)` / `(nil)` | `w:position()` / `(x,y)` / `(nil)` | **pixels** within the parent — `nil` drops your layer (see below) |
| `w:rootpos()` | `w:rootPos()` | **pixels** in root coords (N2) |
| `w:size()` / `(w,h)` / `(nil)` | `w:size()` / `(w,h)` / `(nil)` | size — same three arities |
| `w:visible()` | `w:visible()` / `w:visible(b)` | is it visible — and the write, R6 |
| `w:show()` / `w:hide()` | `w:visible(true)` / `w:visible(false)` | **CUT** — R6 |
| `w:skin()` / `{…}` / `(nil)` | `w:rule()` → a Rule; `w:rule():remove()` undoes | this widget's own cascade level (D-077); removal is R7, not a `clear` verb ✅ **039.7** |
| `w:replace()` | `w:replacement()` | read the installed view |
| `w:replace(view)` | unchanged | install — the verb keeps the acting sense |
| `w:replace(nil)` | `w:replace(nil)` | undo — unchanged; the layer rule (R5) covers it |
| `w:pack()` | unchanged | shrink chrome to content (owned only) |
| `w:destroy()` | unchanged | end a widget you created (R7) |
| `w:onItemAdded(fn)` `:onItemRemoved(fn)` `:onDestroy(fn)` | unchanged | container lifecycle |

Window/widget **builder** setters (R4), replacing the 13-key `opts` table:
`:title(s) :parent(w) :position(x,y) :size(w,h) :font(h) :onDraw(fn) :onClick(fn) :onClose(fn) :onDrop(fn)
:onMouseMove(fn) :onMouseUp(fn) :onTick(fn) :onWheel(fn)` — each with a matching bare read. All thirteen
answer on an OWNED widget only; `:parent(w)` answers while the surface is still being built and refuses
once it is on screen, where moving one is `:position(x, y)`. The HUD overlay `hafen.ui():overlay()` MINTS
one rather than handing back a collection (a HUD painter has no key to `:get`), carries `:onDraw(fn)` /
`:onDraw()` and `:exists()`, and ends with `:destroy()` (R7) — the old handle's `:remove()` is retired.

## Sheet and Rule — **NEW** (replacing `hafen.ui.skin{…}`, 141 sites)

> **Shipped in 039.7**, with three things the first draft did not say. A rule is a **name for a level**, not
> the record: what it says lives on the sheet (or, for `widget:rule()`, in the per-widget map), so a handle
> survives `:remove()` and setting a property says that level again. `position` and `anchor` are one slot,
> so the later **setter** replaces the earlier and only a *loaded table* saying both is refused (there is no
> "later" among keys). And a Rule's vocabulary is **closed**: an unknown verb throws naming the properties
> that exist, which is D-072's answer to a misspelt property one shape along. ✅ every row.

| verb | does |
|---|---|
| `hafen.ui():sheet()` | your addon's sheet |
| `sheet:rule(selector)` | the Rule for that selector, interned per sheet; `rule:selector()` reads the key back |
| `sheet:load(parsedTable)` | install a whole sheet from **data** — the `theme.json` door (§2.8) |
| `sheet:install()` / `sheet:drop()` | apply / remove — and an edit to an installed sheet lands at once |
| `sheet:info()` | `{installed, rules}`, the only way to enumerate what a sheet names |
| `rule:remove()` / `rule:info()` | end this level / everything it says, or nil |
| `rule:sheet()` | climb back, for one-expression use |
| `rule:font(h) :color(c) :bg(…) :border(…) :pad(n) :position(…) :size(…) :anchor(…)` | the shipped properties, one setter each — the sheet key `pos` becomes `position` with the verb |

## Marker, Segment, Grid, Mask, IconCategory

> **Shipped in 039.4**, plus one entity the first draft did not foresee: a display toggle is a
> **OverlayToggle** object (`:tag() :where() :what() :shown() :held() :hold() :release() :info()`),
> because `:shown()/:hold()/:release()` are three verbs and had nowhere else to live.

- Marker: `:name() :type() :icon() :color() :segment() :exists() :info()`, plus
  **`:position()`** (was `:pos()` *and* `:anchor()` — the Position is both), **`:segmentTile()`**
  (was `:tc()`), **`:distance()`** (was `:dist()`, N3) and **`:onMap()`** (was `:onmap()`, N2)
- Segment: `:id() :markers(filter) :exists() :info()`, plus **`seg:grid():get(segmentCoord)` /
  `:list(area)`** (was `seg:grid(sc)` / `seg:grids(area)` — the old singular/plural pair is R3's dual style)
- Grid: `:id() :segment() :tile(c) :height(c) :image(level) :overlayImage(tag) :exists() :info()`, plus
  **`:segmentCoord()`** (was `:sc()`), **`:position()`** (was `:pos()`, now a Position), **`:modified()`**
  (was `:mtime()`), **`:live()`** (streamed right now?) and **`grid:overlay():get(tag)` / `:list()`**
  (was `grid:overlay(tag)` / `grid:overlays()`)
- Mask: `:tag() :grid() :covers(c) :count() :area() :exists() :info()`
- IconCategory: `:res() :name() :exists() :info()`; `cat:show()` / `:show(on)` and `cat:notify()` /
  `:notify(on)` are already R2-correct and gain only the R5 nil refusal

## Sprite, Object, Ghost — already R2-shaped

> **Shipped in 039.8.** ✅ every row, and each of the five "unchanged" verbs gained its READ arity, which
> is what made them read/write pairs rather than write-only. `:onClick(fn)` and `:exists()` joined them:
> the callback was a constructor key with nowhere else to go, and an entity with a lifetime carries
> `:exists()` (§2.2/§2.4). `sprite:billboard(b)` is the construction property that rebuilds (D-113).

`:rotate(a) :scale(k) :alpha(a) :tint(c) :clickable(b)` unchanged. **`:pos()` -> `:position()`** and
**`:move(x,y,a)` -> `:position(p [, a])`** — R2 collapses the read/write pair onto one name, and the
argument is a Position. `:show()`/`:hide()` -> `:visible(b)` (R6); `:destroy()` -> the collection's
`:remove()` (R7). **`g:setRes(res, sdt)` -> `g:res(res, spawnData)`** — `g:res()` already reads it (R2),
and `sdt` is expanded (N1).
Sprite `:image()`, Object `:mesh()` unchanged.

## Asset (and its typed faces)

> **Shipped in 039.8.** ✅ every row — the three asset verbs and the typed faces are untouched.

`a:type() a:path() a:dispose()`; `img:size()`; `mdl:bounds() mdl:info()`; `d:text()`.
`:dispose()` is kept by R7 — it frees a resource now, it is not a removal from a collection.

## FontHandle

> **Shipped in 039.8.** ✅ every row. `h:size()` gained its write half, and the setters are
> `:size :color :aa :bold :italic` — writable only on an unused `:derive()` draft (D-126).

`h:family() h:size()` unchanged; `h:derive{…}` → `h:derive()` + setters (R4, 43 sites).

## Handles: Timer, SlashCommand, Subscription

`:cancel()`, `:remove()`, `sub:off()` — all unchanged.

## Hook event objects (`ev`) — unchanged

`ev.msg ev.sender ev.target ev.args` fields; `ev:preventDefault() ev:resend() ev:send(t) ev:rewrite(t)`.
**These stay plain fields, not verbs**: an `ev` is a per-call value, not an interned entity.

## `g` — the draw context, unchanged

`g:color g:line g:rect g:frect g:prect g:poly g:image g:aimage g:text g:atext g:resource`.
`g:text{…}`/`g:atext{…}`'s rich-text option table is the one R4 case with **no** persistent object to
hang setters on — **plan.md must settle it**; the likely answer is positional arguments plus a
`hafen.font()` handle, since every option it takes is already expressible as one.

## Options handles — 18 methods, already R2-shaped

> **Shipped in 039.10.** ✅ every row.

`interface()` `video()` `audio()` `camera()` `client()`; each option is `:name()` / `:name(v)` already,
and they gain only the R5 nil refusal.

**Keybindings — the one place a `get`/`set` pair survived the first draft.** R2 forbids it:

| before | after | does |
|---|---|---|
| `kb:get(name)` / `kb:set(name, key)` | `kb:key(name)` / `kb:key(name, key)` | read / remap a binding (`"None"` unbinds) |
| `kb:register(name, fn)` | unchanged | declare a hotkey your addon owns |
| `kb:unregister(name)` | unchanged | drop one of yours |
| `kb:list()` | unchanged | every binding in the client |

---

# New entities this feature creates (spec §4)

| entity | from | key verbs |
|---|---|---|
| Attr | `hafen.char():attr()` | `:name() :base() :comp() :value() :info()` |
| Skill / Credo / Experience | `hafen.char()` | `:name() :res() :cost() :exists() :info()` |
| Food | `hafen.char():food()` | `:hunger() :energy() :feps() :info()` |
| StudySlot | `hafen.study():slot()` | `:res() :name() :lp() :attention() :exists() :info()` |
| PartyMember | `hafen.party()` | `:id() :position() :color() :leader() :gob() :exists() :info()` |
| Craft | `hafen.craft():current()` | `:name() :res() :inputs() :outputs() :make(all) :exists() :info()` |
| Quest / Condition | `hafen.quest()` | `:id() :title() :conditions() :done() :exists() :info()` |
| Wound | `hafen.wound()` | `:res() :name() :severity() :exists() :info()` |
| Maneuver / DeckCard / FightSummary | `hafen.fight()` | `:res() :name() :exists() :info()` |
| **Position** | every `:position()`, `hafen.world():position(x,y)` | `:x() :y() :offset(dx,dy) :distance(o) :tileCoord() :durable() :info()` — a **value** object, not interned |
| **Item** | `widget:items()`, `hafen.ui():hand()` | `:res() :name() :quality() :handle() :exists() :info()` — **intern key unsettled, §4.8** |

---

# Events (spec §2.11)

Subscription moves to `hafen.event():on(name, fn)`. Payloads that are still snapshots become objects:

| event | payload before | payload after |
|---|---|---|
| `FepChanged` | `Food` snapshot | Food entity |
| `StudyChanged` | `StudySlot[]` snapshots | StudySlot entities |
| `EquipChanged` | `Item[]` snapshots | Item entities |
| `WoundChanged` | `Wound[]` snapshots | Wound entities |
| `QuestAdded` / `QuestDone` | `Quest` snapshot | Quest entity |

Unchanged payloads: `OnLoad OnEnterWorld OnUpdate OnDisable` (—/`dt`), `GobAdded`/`GobRemoved` (Gob),
`MeterAdded/Removed/Changed` (Meter), `BuffAdded/Removed/Changed` (Buff), `ActionbarChanged` (Slot),
`KinChanged` (Kin[]), `MarkersChanged` (`{count}`), `GobOverlayAdded/Removed` (`{gob, key, native}`),
`GhostClicked`/`SpriteClicked`/`ObjectClicked` (`{thing, button, x, y}`) — the composite ones are
**values** carrying objects (§2.8), which is already correct.

**Event NAMES keep their plurals** (`MarkersChanged`) — an event name is a sentence about what happened,
not an accessor, so R3 does not reach it.

---

# Return types that change (allowed by spec §10: "spelling and return *type*")

| verb | was | becomes |
|---|---|---|
| `hafen.char.skill(name)` | boolean | the Skill, or nil |
| `hafen.wounds.has(needle)` | boolean | the Wound, or nil |
| `hafen.party.members()` etc. | snapshot tables | entities (all of spec §4) |

Both boolean-to-entity changes stay truthy-compatible for `if hafen.wound():find("x") then`, which is how
every shipped call site uses them — verified against `hello`, `walker` and the suites during the port.

---

# Coverage check

| group | count | source |
|---|---|---|
| sections before | 34 | `installHafen` |
| sections after | 33 | `hafen.gob` deleted into `hafen.world()` |
| sections renamed | 3 | `quests`→`quest`, `wounds`→`wound`, `events`→`event` (86 Lua sites) |
| section verbs re-spelled | ~120 | the tables above |
| entities before | 21 | shipped |
| entities created | 14 | spec §4 |
| constructors de-tabled | 8 | `window` `widget` `overlay` `sprite` `object` `ghost` `font:derive` `marker:add` — plus `skin` (a sheet, not a constructor) and `g:text` (a draw call, open) |
| `get`/`set` pairs collapsed by R2 | 5 | `speed`, `kin:setGroup`, `slot:set`, `g:setRes`, `kb:get`/`set` |
| position verbs, before | 4 | `:pos()` · `world.gridPos` · `world.fromGridPos` · `marker:anchor()` |
| position verbs, after | **1** | `:position()`, a Position object — computable **and** durable |
| arithmetic sites moving into the engine | 38 | `p.x + n` -> `p:offset(n, 0)` (a grid is 1100 world units) |
| duplicate doors CUT | 2 | `world.placeGrid`/`placeAngle` — same fields as `interface():posGran`/`angGran`, with a unit mismatch |
| singular/plural dual pairs collapsed by R3 | 4 | `map.segment`/`segments`, `seg:grid`/`grids`, `grid:overlay`/`overlays`, **`world.gob`/`gobs`** |
| events re-payloaded | 6 | of 26 |
| verbs CUT with no replacement | 2 | `w:show()` `w:hide()` (R6) |
| verbs CUT with an existing replacement | 2 | `world.placeGrid`/`placeAngle` → `options():interface()` |
| open questions for plan.md | 6 | Item identity (§4.8) · `g:text{…}`'s option table · `craft:make` on a nil `:current()` · `hafen.log` (§8.1) · Position allocation per call in a draw callback · whether the numeric converters `tileToWorld`/`tileToGrid` fold onto Position |
