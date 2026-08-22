# 098 — the vr snapshot: tasks

Shipped as **one task with one suite**, in the maintainer's own session, alongside
[097](../097-three-edges-three-words/tasks.md) — the same re-audit's other leftover, sharing no code and
no page with it.

- [ ] **098 — the vr snapshot.** `conventions.md` states the rule without qualification: a point-in-time
      copy is what `:info()` gives you, and every live object in the API answers it. The **vr entities were
      the one family that did not** — nothing in `VrApi` or `LuaWorldEntity` set an `info` verb, and
      `types.md` carried no entity shape — so logging what an addon had standing cost ten calls per entity.
      `e:info()` now carries the ten shared readers plus the one or two only that kind answers, **each key
      spelled the way its verb is**: `kind` `position` `rotate` `scale` `alpha` `visible` `clickable`
      `exists` `drawn` always, `tint` once one is laid over it, `anchor` and `offset` only for one that
      follows a gob — **absent exactly where `:offset()` itself raises** — and `res` / `mesh` /
      `image` + `facing` / `facing` per kind. The place comes out **flat**, as the `{gridId, x, y}` table
      `p:info()` answers, read *through* that verb rather than rebuilt beside it. `panel:screen(x, y)` has
      no field: it projects a point you pass in, so there is nothing to photograph. The per-kind half is an
      **abstract** `LuaWorldEntity.infoInto(LuaTable)`, so a fifth kind cannot be added without answering
      the question. Additive throughout: nothing retires, nothing changes shape.
      *Its suite* stands a ghost and a panel and checks **every shared field against the very verb that
      reads it** rather than against a constant, that the place is a plain table whose `gridId` matches
      `e:position():info()`, that a key is **absent when the thing it names is** and appears once it is,
      that each kind contributes its own verbs **and only those**, that an argument is refused naming the
      one-at-a-time reads, that the returned table **does not go on updating** while the verb does, and
      that a removed entity still reads and says it is gone.
      `[manual]`: one — that nothing of the suite's is left standing in the world.
      *Audit*: `audit/ns-vr.md` F3. **Not a row in `INVENTORY.md`.**

## Result

**Closed without the in-game run, on the maintainer's instruction.** `:t098` was never executed, so the box
above stays unticked. `e:info()` has never returned a table in a live client.

Verified headlessly only: a clean build from an empty `build/classes`, both checkers green (`:info()` is in
the entity's own `closedIndex` vocabulary, and every verb the vr pages name resolves against it), and the
suite parses under LuaJ.

Not verified: that the ten shared fields agree with the verbs they mirror at runtime, that `position`
comes through `p:info()` intact, that `tint` and `anchor` really are absent until set. Every one of those
is a check in the archived suite, and none of them has run.

## Reported at the close, not changed

**`audit/ns-vr.md` F2 and F4 are findings with no row and are not discharged here.** F2 —
`hafen.vr():pointer(key, x, y, a)` returning a bare boolean where every other write returns the receiver —
and F4 — a standing widget not being drawable from another character, with the page silent about it — are
both outside this finding. Recorded so a later reader does not go looking for them under 098.
