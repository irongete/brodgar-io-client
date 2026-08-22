# 089 — A word means one thing II: plan

## Approach

Thirteen rows, shipped as one unit with one suite. Every change is a row's own replacement text; the
work was the seams, not the design.

**The verb, not the snapshot.** `types.md`'s rule — *"A snapshot field keeps the client's own
spelling; the live read is the verb"* — meant every site had to be read as `m.set(` (the verb),
`extra.set(` (the collection's verb) or `t.set(` (the snapshot field), because a bare `grep '"level"'`
finds all three. `PartyMember` keeps `leader`, `Credo` keeps `quest`/`level`/`pursuing`, `DeckCard`
keeps `slot`, `Buff` keeps `duration`, `Craft` keeps `recipe`. Only the live-read columns moved.

**Every rename is `Retired`'s free case.** No argument shape, return shape or payload moved, so a
retired spelling raises at the line that wrote it, for a call and for a field read alike.

**The two structural ones.** `LuaSkill.collection`'s `extra.available` built a `LuaTable` array with
`LuaCollection.keeps`; it became `buyable` and a `LuaCollection.create` over the same walk of
`tokens(user, false)`, with the caller's filter bound into the new collection's `Source.members()`.
And `ActApi.craft` stopped building a one-verb section: `LuaCraft.section(owner, user)` mounts the
eight verbs directly, each resolving `ActApi.makewindow(user)` for itself, each keeping its
`Section.self` guard, and `make` keeping its permission gate first.

## Files

**Bridge (16):** `LuaManeuver`, `LuaSkill`, `LuaCredo`, `LuaWound`, `LuaContents`, `LuaDeckCard`,
`CharApi`, `LuaPartyMember`, `LuaQuest`, `LuaSlot`, `LuaBuff`, `LuaCondition`, `LuaCraft`, `WorldApi`,
`LuaGob`, `LuaPagina`, plus `ActApi`, `Permission` and `Retired`.

**Pages (19):** `craft.md` (rewritten), `char.md`, `fight.md`, `wound.md`, `party.md`, `quest.md`,
`time.md`, `buff.md`, `study.md`, `actionbar.md`, `menugrid.md`, `gob.md`, `conventions.md`,
`shapes.md`, `types.md`, `ui/items.md`, `guides/permissions.md`, `guides/custom-ui.md`,
`guides/reading-the-world.md`.

## Gotchas found while doing it

**`Section.meta` keys `Retired` on `hafen.<nm>():<verb>`, not on the section's spelling.** Two rows
were written against the wrong key and would never have fired: the study one was
`session:study():slot` and had to become `hafen.study():slot`. A collection is different again —
`LuaCollection.meta` uses `coll.name + ":" + key`, so `session:char():skill():available` and
`session:char():credo():cost` were right. **Three receivers, three key shapes**; check which one the
verb sits on before writing the row.

**After the flatten, `s:craft():name()` fell through to the generic refusal.** The `craft:name` row
keys on the `LuaCraft` object's entity name, and nothing hands one out any more, so the natural
mistake got *"session:craft() has no verb 'name'"* and no fix. `put("hafen.craft():name", …)` was
added so `Section.meta` finds it.

**Five existing `Retired` messages named a verb this feature renames** —
`section("time", …, "isNight", …)`, `gob:isplayer`, `pagina:isnew`, and the two `hafen.craft.*` rows,
which taught `c:name()` and `session:craft():current():make(all)`. This is 085.7's defect class: a
refusal that names a fix that no longer works. Caught by grepping the retired verbs *inside*
`Retired.java` before landing, not after.

**`Section.self` returns a `Section`, not the receiver.** The first build failed on
`LuaValue me = Section.self(a.arg1(), …)`; the receiver is `a.arg1()` and `Section.self` is called for
its check alone.

**`credo:levelTotal` and `contents:text` are false positives** for `credo:level` and `c:text()`. Grep
the whole spelling.

**`conventions.md` was at 300 before this feature and A-069 adds a rule to it.** The rule went where
the page already discussed booleans, compressed to land the page back at exactly 300 — rather than
split a page whose opening sentence is one subject, which `DOCUMENTATION.md` §9 says is the worse
trade and which 085 already refused for `types.md` (A-031).

## Discarded alternatives

- **`man:count()` beside `man:used()`** — the finding offers it; the row names `:dealable()`, and
  `count` is the collection quartet's word, so it would read as "how many maneuvers".
- **Keeping `contents:level()`** — the finding grants it the best case of the three. It went anyway,
  because the row's purpose is that `:level()` leaves the API; one survivor keeps the word teaching
  nothing.
- **`:buyable()` with the filter on `:list(f)` instead of on the partition** — cleaner grammar, and
  the row is written `:buyable(f)`. The row's arity was kept.
- **Renaming `study.md`'s `## A slot` heading** — the row renames the collection verb; the member is
  still a `StudySlot` in `types.md`, and four inbound anchors point at that heading.
- **Renaming `slot:cooldown()`** — A-064 asks only that it be documented against the unit convention,
  which 085.1 already did. A rename would cost a row and buy a longer name.
- **`pag:new()`** — a property that "is new" reads badly against a verb that makes one; the row's own
  reason for `:unseen()`.
- **Keeping `:current()` on `s:craft()` and stating the exception** — the finding's second option;
  **D4** chose the first, because both `craft` and `flowermenu` hold exactly one thing per session and
  `craft.md` already told the reader not to hold a Craft across recipes.
- **Renaming `credo:questId()` to `credo:quest()` in the same pass** — `audit/ns-quest.md` F3 names it
  and then says *"that is a second step and can wait; the rename alone removes the trap."* No row
  carries it.
- **Changing `card:index()`'s base while renaming it** — A-071 (090) makes every `:index()` 1-based;
  doing half here would leave `card:index()` 1-based while `slot:index()` and `sp:index()` are not.
- **Deleting the `extra` verbs instead of the member flags** — the distinguished member is the half
  that works and the half `s:speed()` keeps.
- **Splitting `conventions.md`** — see the gotcha above.
