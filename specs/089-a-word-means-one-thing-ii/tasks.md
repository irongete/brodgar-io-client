# 089 — A word means one thing II: tasks

Shipped as **one task with one suite**, in the maintainer's own session rather than through
`/implement`: thirteen renames and deletions over one surface, where splitting them would have meant
thirteen partial trees and no single command that proves the feature.

- [x] **089 — A word means one thing II: the character sheet.** Thirteen rows over the surface a user
      reads most, two of them silently wrong. `man:available()` was a **count** where `sp:available()`
      is a boolean, and `0` is truthy in Lua, so `if man:available() then` fired with none dealable;
      `credo:quest()` was a count named like an object, so `s:quest():get(credo:quest())` addressed a
      quest by a number. `man:dealable()` and `s:char():skill():buyable(filter)` — the second now a
      **`LuaCollection`**, so `:count()` answers where `:available():count()` threw — take the count
      and the partition, and `sp:available()` keeps the predicate. `credo:questsDone()` sits beside
      `credo:questTotal()`. `:level()` leaves the API as `w:depth()`, `credo:rank()` and
      `contents:fill()`. `slot` becomes the action bar's alone: `card:index()` (the **base** stays
      0-based; A-071 in 090 moves it) and `s:study():curiosity()`. The three member flags —
      `member:leader()`, `q:selected()`, `credo:pursuing()` — are **deleted** for the identity
      comparison the interning makes exact, and the collection-level `s:char():credo():cost()` with
      them. `slot:hold()`, `buff:remaining()`, `c:recipe()` and `cond:tooltip()` say their unit and
      their source. `conventions.md` states the bare-adjective rule (**D1**) and
      `hafen.time():night()`, `gob:player()`, `pag:unseen()` follow it. And **`s:craft()` IS the open
      recipe** (**D4**): `LuaCraft.section` mounts the eight verbs, each re-reading
      `ActApi.makewindow(user)`, `:exists()` false with nothing open, `:current()` retired through
      `Retired.movedObj`, and `Permission.CRAFT_MAKE.lua` now `session:craft():make`. Eighteen
      `Retired` rows, plus **five existing messages corrected** where they named a verb this feature
      renames.
      *Its suite* — `089-a-word-means-one-thing-ii.all`, run as `:t089` — proves each rename the way a
      rename is proved: the new spelling answers what the old one did, the old one **raises and names
      its replacement**, and the words the renames free still mean what they were freed for. The
      refusals need nothing of the world (they are `Retired` rows firing off metatables); the reads are
      **scored** over what the run reached, since a wound, a credo, a buff and a deck card each need
      the client in a state. The deletions are proved by the comparison that replaces them:
      `s:party():leader() == member` **and** `s:party():get(m:id()) == m`, which is what interning
      buys and what makes a per-member flag removable.
      `[manual]`: two — a recipe open, and the consent dialog's `craft.make` line.
      *Audit*: **A-058** (`audit/ns-speed.md` F1, `ns-fight.md` F1, `ns-char.md` F3) · **A-059**
      (`ns-quest.md` F3, `ns-char.md` F1) · **A-060** (`ns-wound.md` F3) · **A-061** (`ns-fight.md` F4)
      · **A-062** (`ns-actionbar.md` F3) · **A-063** (`ns-buff.md` F2) · **A-064** (`ns-actionbar.md`
      F2), **struck as already true** · **A-065** (`ns-craft.md` F3) · **A-066** (`ns-quest.md` F4) ·
      **A-067** (`ns-party.md` F1, `ns-quest.md` F1, `ns-char.md` F2) · **A-068** (`ns-char.md` F5) ·
      **A-069** (`ns-time.md` F2 · **D1**) · **A-070** (`ns-craft.md` F1 · **D4**).

## Result

`:t089` — **9 pass, 0 fail, 2 manual**, both manuals confirmed. Clean build after
`rm -rf build/classes`.

Thirteen rows ticked and struck in `audit/INVENTORY.md`; **A-064** carries its strike reason (already
true, discharged by 085.1 when A-034 landed). The open count went **60 → 47**.

**No row of another feature's block was implemented here**, and none of these was implemented
elsewhere: 090 owns the index base (A-071, A-072) and 091 owns the array-to-collection conversions
(A-073 … A-084). **No `specs/ROADMAP.md` line is covered by this scope.**
