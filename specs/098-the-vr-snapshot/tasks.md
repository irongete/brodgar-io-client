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

**`audit/ns-vr.md` F2 and F4 were listed here as open. They are not, and were not.** The line is
corrected rather than deleted, because the mistake is the useful part: both were read off the audit's
finding list without being checked against the tree, which is the one habit this whole sweep keeps
punishing.

- **F2** — `hafen.vr():pointer(key, x, y, a)` reading as a noun and returning a bare boolean — shipped
  earlier in the sweep. The verb is `hafen.vr():click(key, x, y [, a])`, `Retired` carries a `moved` row
  giving the noun-to-verb reason, and `vr/widgets.md` documents the boolean **and what `false` means**:
  the point was on no panel, so the client's own world click goes through untouched.
- **F4** — a standing widget not drawable from another character, with the page silent about it — also
  shipped. `vr/widgets.md` has a section of its own, *"It stands with the character you stood it from"*,
  stating the limit in the present tense and separating `panel:exists()` from `panel:drawn()`.
  `VrApi.rehome`'s javadoc points at that section, and the code does what both say.

Both were cited by rows in `audit/INVENTORY.md` and closed with them. Nothing of `ns-vr` is open.
