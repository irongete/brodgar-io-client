# 094 — The widget and the thing: tasks

Shipped as **one task with one suite**, in the maintainer's own session: eleven rows over one asymmetry,
where splitting them would have left a tree in which some domain objects could name their widget and some
could not — which is the defect itself.

- [x] **094 — The widget and the thing.** The widget tree and the domain objects are two address spaces
      that met in **one place and one direction** (`widget:items()`), so everything an addon wants to place
      relative to the client's own UI was unreachable: a selector reaches the *window* by role and the thing
      inside it is a domain object the selector language cannot address. **`:widget()` on Buff, Meter,
      StudySlot and Kin** is the crossing back — three of them *are* their widget, and a Kin is a list row
      reached through `SListBox.getcur`. **Four facts a widget could not say about itself** land beside it:
      `w:session()` (the tree it stands in, `nil` in the layer), `w:events()` (the list `widgetKeys` already
      built to make the refusal), `w:owned()` (a snapshot field only) and `w:is(sel)` (the predicate, named
      by **D3**). **The wound tree walks down** — `wound:children()` and `s:wound():roots()`, both
      collections off one shared `Source`. Then `gob:party()`, `store():list()` on both halves, `h:type()`
      on every font handle, `hafen.ui():role()` as a self-describing collection, and
      `s:menugrid():get(id)` taking the caller's own short id.
      *Its suite* asserts the crossing lands, is interned, and **round-trips** — `m:widget():session():user()`
      is the character it came from; that `w:owned()` splits a window you built from one of the client's;
      that `w:events()` lists what `:on()` accepts **and only that**; that `w:is(sel)` differs from
      `:match(sel)`, which is *inclusive of `w`*; that every wound root has no parent and every child names
      its parent back by identity; and that the five small ones answer.
      `[manual]`: one — the suite draws a red outline from `meter:widget():rootPos()` and `:size()`, and
      whether it lands **on the bar** is the judgement no program can make.
      *Audit*: **A-104** (`audit/ns-ui.md` F7) · **A-105** (`16-consumer-evidence.md` W2) · **A-106** (W3) ·
      **A-107** (W5) · **A-108** (W6 · **D3**) · **A-109** (`ns-wound.md` F2) · **A-110** (`ns-party.md` F4) ·
      **A-111** (`ns-store.md` F2) · **A-112** (`ns-font.md` F3) · **A-113** (`ns-ui.md` F8) · **A-114**
      (`ns-menugrid.md` F3).

## Result

`:t094` — **5 pass, 0 fail, 1 manual**, confirmed. Clean build from an empty `build/classes`. Fourteen
pages updated.

Eleven rows ticked and struck; the open count went **15 → 4**. **One carries a correction to the audit's own
count:**

- **A-104 names five types and there can only be four.** `Slot` is out: the action bar is one widget that
  paints all 144 slots itself (`FKeyBelt.draw` → `belt[slot].draw(g.reclip(...))`), so `slot:widget()` would
  answer the same bar for every index. `actionbar.md` carries the reason and the way round.

**No row of another feature's block was implemented here.** **No `specs/ROADMAP.md` line is covered** —
074's layer half is the *other* half of A-104's finding and stays queued.

## Reported at the close, not changed

**`docs/addons/api/ui/widget.md` is 309 lines against a 300 ceiling.** It was 305 before this feature: the
four new verbs are four table rows and a row is a line, so there is no compression that keeps it. This
feature did not push it over, but it grew it — its split belongs with `ui/native.md`'s, already a
`specs/ROADMAP.md` candidate filed 074.
