# 089 — A word means one thing II: the character sheet

Discharges: A-058, A-059, A-060, A-061, A-062, A-063, A-064, A-065, A-066, A-067, A-068, A-069,
A-070.

All thirteen are rows in `audit/INVENTORY.md`'s own 089 block. Where a row offered two spellings the
row's own choice was taken; where a Step-0 decision had chosen, that choice was taken. Nothing here
was added to them.

## What and why

One spelling, several meanings, on the surface a user reads most. Two rows were `severe` because the
wrong guess was **silently** wrong.

`man:available()` was a **count** (`LuaManeuver`'s `act.a`) where `sp:available()` is a boolean and
`s:char():skill():available(f)` was an **array**. In Lua `0` is truthy, so
`if man:available() then deck:add(man) end` took the branch with none dealable, and nothing raised.

`credo:quest()` was `progress("quest", 2)` — the quests done toward the pursued credo — while
`c:quest()` on a Condition hands back a Quest. So `s:quest():get(credo:quest())` compiled, ran, and
addressed a quest **by a count**.

`:level()` meant tree depth, a credo's rank and a `{cur, max}` fill. `slot` meant a deck index, an
action-bar button, a study curiosity and an equipment position. Three collections carried a flag their
member should not have. `buff:duration()` was a `0..1` fraction named like a length of time.
`c:name()` on a Craft read the **recipe** while its own snapshot field is `recipe`. Three booleans were
`is`-prefixed and twenty-six were not. And `s:craft()` held exactly one thing and was not that thing.

## Where the audit or a decision had already chosen

| Row | The audit offered | Taken |
|---|---|---|
| A-058 | `man:dealable()` **or** `man:count()` | `:dealable()` — the row names it |
| A-060 | `contents:fill()`, "with the best case for keeping its name" | `:fill()` — the row names it |
| A-063 | `buff:durationFraction()` **or** `:remaining()` | `:remaining()` — the row names it |
| A-069 | "Either rule is defensible; having neither is the finding" | the bare adjective — **D1** |
| A-070 | flatten **or** keep `:current()` and state the exception | flatten — **D4** |

**A-058's arity.** The row is written `:buyable(f)`, so the filter stayed an argument of the partition
rather than moving onto `:list(f)`: what you filter is the buyable ones, and the collection it hands
back then answers the whole quartet over exactly that set.

**A-061 renamed the verb, not the base.** `card:index()` still answers the raw 0-based deck index;
**A-071** (090) is what makes every `:index()` 1-based.

**A-061 did not rename a type.** `study.md`'s `## A slot` heading and `types.md` §StudySlot stay: the
row renames the collection verb, and the member is still a `StudySlot`. Four inbound anchors survive.

## One row was already true

**A-064** — *"`slot:cooldown()` documented against the A-034 unit convention"* — was discharged by
085.1 when A-034 landed: `actionbar.md`'s blockquote already reads *"a
[`0..1` fraction](shapes.md#units)"* and `shapes.md` §Units lists the verb. **Struck as already true**,
and its row carries that reason.

## What shipped

1. **`:available()` means one thing.** `man:dealable()` is the count, beside the `used` the same table
   already carried. `s:char():skill():buyable(filter)` is a **`LuaCollection`** over the same
   predicate, so `:count()`, `:find()` and `:list()` all answer where `:available():count()` threw.
   `sp:available()` is unchanged.
2. **`credo:questsDone()`** is the count, beside `credo:questTotal()`. `credo:questId()` untouched.
3. **`:level()` is gone**: `w:depth()`, `credo:rank()`, `contents:fill()`.
4. **`slot` means the action bar**: `card:index()`, `s:study():curiosity()`. `item:slots()` keeps the
   word, being a list of names.
5. **No distinguished member carries a flag.** `member:leader()`, `q:selected()` and
   `credo:pursuing()` are deleted; each retires to the identity comparison, exact because the objects
   are interned. The collection's verb of the same name stays. The snapshot fields stay.
6. **The collection-level `s:char():credo():cost()` is deleted**, retiring to
   `s:char():credo():pursuing():cost()`.
7. **`slot:hold()`, `buff:remaining()`, `c:recipe()`, `cond:tooltip()`.**
8. **`conventions.md` states the boolean rule**, and `hafen.time():night()`, `gob:player()`,
   `pag:unseen()` follow it.
9. **`s:craft()` IS the open recipe.** The eight verbs answer on the section, each re-reading
   `ActApi.makewindow(user)`; `:exists()` is false and every read is `nil` or empty with nothing open;
   `:current()` retires through `Retired.movedObj`; `Permission.CRAFT_MAKE.lua` reads
   `session:craft():make`.
10. **Eighteen `Retired` rows**, and **five existing messages** that named a verb this feature renames
    were corrected — the 085.7 class of defect, caught while making the change rather than after.

## Verified

`:t089` — **9 pass, 0 fail, 2 manual**, both manuals confirmed by the maintainer. Clean
`ant hafen-client` after `rm -rf build/classes`. No addon under `addons/` calls a renamed verb.
`conventions.md` lands at 300, its ceiling. No retired spelling survives anywhere under `docs/`.
