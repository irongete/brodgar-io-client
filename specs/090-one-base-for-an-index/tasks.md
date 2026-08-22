# 090 — One base for an index: tasks

Shipped as **one task with one suite**: two rows, one rule, and a change that is silent for every
existing caller — splitting it would have left a tree where `:index()` and `:get()` disagreed.

- [x] **090 — One base for an index.** `:index()` becomes the **1-based position** in `:list()`
      everywhere, `:wire()` carries the raw number the server's own message uses, and `:get(n)` takes
      the number `:index()` answers — so `s:actionbar():list()[n] == s:actionbar():get(n)` holds.
      `slot:index()` is `1..144` and `slot:wire()` the raw game index; `sp:index()` is `1..4` and
      `sp:wire()` the raw `0..3`, with `s:speed():get` and `:set` both moving to the position;
      `card:index()` is its place in `:deck()` and `card:wire()` the raw deck index. `meter:index()`
      does not move — it was already the position and a meter has no wire number. The Speed snapshot
      carries `index` and `wire`, so `info().index == :index()`; DeckCard's `slot` field keeps the
      client's own word and the raw number. **`:get(0)` raises and names the change**, which is the
      only place a reshape can teach, since `Retired` has no name to key on.
      *Its suite* proves the invariant at both ends and in the middle rather than asserting it once,
      and round-trips `:index()` through `:wire()` and back. It asks the four calls an addon written
      before this makes to raise **and name the change** — `:get(0)` must say both "1-based position"
      and "slot:wire()" — while `s:speed():get(0)` stays a plain `nil` miss, as its page has always
      said. Then that nothing else moved: Slot identity, the name key, and the quartet.
      `[manual]`: one — put something on the first action-bar slot and read `:get(1)` back.
      *Audit*: **A-071** — *"`:index()` is the 1-based position in `:list()` everywhere; `:wire()`
      carries the raw number"* · **A-072** — *"`s:actionbar():get(n)` takes the same number `:index()`
      answers, so `:list()[n] == :get(n)`"* (`audit/ns-actionbar.md` F1, `audit/ns-meter.md` F2).

## Result

`:t090` — **5 pass, 0 fail, 1 manual**, the manual confirmed:
`s:actionbar():get(1):res()` answered `"paginae/act/push"`.

Two rows ticked and struck; the open count went **47 → 45**. Two arrears of 089 were corrected in
passing — `LuaDeckCard`'s `handle(self, "slot")` and `s:fight():deck()`'s refusal, both naming a verb
089 had renamed. Neither needs an id: they are 089's own surface, and 089's rows are about the rename
that created them.

**A-073's count was found wrong while surveying 091**, and its row now says so: it reads "13" while
`04-collections-and-queries.md` lists seventeen under "Convert (13)", of which one shipped with 088.1
and six are carried by A-075, A-076 and A-077. The coverage is complete; only the number was short.

**No `specs/ROADMAP.md` line is covered by this scope.**
