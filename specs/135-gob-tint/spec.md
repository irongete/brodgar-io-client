# 135 — gob tint

## What & why

An addon that sorts the world for you — the ripe crop, the full box, the boar to avoid — can only mark
the object with a flat 2D label or painter at its projected point. What the eye reads fastest is the
object itself wearing the colour, shaded as the model is shaded: a green box, a red box, a yellow one.

The engine already draws that: every damaged object wears a red wash (`GobHealth`'s `MixColor`), and the
props this API stands in the world take one from [`e:tint(c)`](../../docs/addons/api/virtual/README.md).
A game object has no such verb, and the addon colouring the crop is looking at the *game's* crop.

**One verb, in the family it already has.** `gob:tint()` / `gob:tint(c)` / `gob:tint(nil)` joins
`gob:scale(k)` and `gob:visible(b)` as the third client-local, purely visual, unprotected write on a
game object, and spells exactly what `e:tint` spells: a colour laid over the object, its `a` the blend
strength, `nil` meaning none. Same word, same meaning, at both levels.

## Acceptance criteria

1. **`gob:tint(c)` lays a colour over the object's model**, shading kept: the blend the damage wash
   uses, so `{255, 0, 0, 96}` reads as a red-washed box and `{255, 0, 0}` (alpha `255`) as a flat red
   one. It hands the Gob back, so it chains with `:scale` and `:visible`.
2. **`gob:tint()` reads it back keyed** — `{r=, g=, b=, a=}`, the shape every colour reader answers —
   and `nil` for an object nobody tinted. **`gob:tint(nil)` clears it**, is legal, and leaves nothing
   behind: the object is drawn exactly as before, and the intent record for it is empty.
3. **A wrong argument is refused naming the colour grammar** — the one colour refusal every colour
   write raises — for a number, a string, a boolean, and a table that is not a colour.
4. **It composes with the game's own wash and with the other two writes.** A damaged object keeps its
   red wash under yours (two fragment stages, not one slot two writers fight over);
   `boar:tint(c):scale(2):visible(false):visible(true)` is a boar still tinted and still twice
   its size; `gob:info()` carries `tint` when one is laid over it and no key when none is.
5. **Where the write lands and how long it lasts are a size's rules exactly**: written to every
   character that can see the object, re-applied to a character that loads the object afterwards, dropped
   when the object leaves its last character's view, and put back everywhere by a `:reload` or a disable
   of the addon that wrote it. Last write wins between two addons.
6. **Nothing else about the object changes**: its footprint, its click, what it sends. A tinted object
   still takes a click and still answers every read.
7. **The page it lands on is under the ceiling.** `gob.md` is at 364 lines today; the three visual
   writes and the overlays pointer move to a sibling page, every inbound anchor re-pointed in the same
   task, and `gob.md` and the new page both end under 300.

## Out of scope

- **A tint on a part of a model** (the fruit and not the tree): the state sits on the gob's own render
  slot, so the whole model wears it, as the damage wash does.
- **A tint keyed by a stylesheet rule**: a rule reaches widgets, and a gob is not one. An addon writes
  the verb from `GobAdded`, as it writes `:scale`.
- **A desaturation or an outline glow**: taking colour *out* is another fragment operation, and an
  outline is a second pass. Each would be a verb of its own.
- **`e:tint`** on the standing entities is shipped and untouched.

## Docs impact

Pages written: `docs/addons/api/look.md` (new: Size, Drawn or not, Tint, Overlays pointer — moved out of
`gob.md`), `docs/addons/api/gob.md` (shrinks, points at it), `docs/addons/api/types/world.md` (`GobInfo`
gains `tint`), `docs/addons/api/README.md` (index row for the new page), `docs/addons/api/conventions.md`
(the `nil` table's *none* row gains `gob:tint(nil)`), `docs/addons/api/shapes.md` (`gob:tint()` among the
colour readers, `gob:tint(c)` among the writers), `docs/addons/api/threading.md` (the safe-write list
gains it).

Derived impact set — pages that name the surface without opening the new syntax:

```text
grep -rln "gob:visible\|gob:scale" docs/addons
→ flowermenu.md, gob.md, README.md, threading.md, types/world.md, virtual/README.md
grep -rn "#size-unprotected\|#drawn-or-not-unprotected" docs/ | grep -v "^docs/addons/api/gob.md"
→ threading.md ×2, types/world.md, virtual/README.md, flowermenu.md:150 — 5 anchors to re-point
  (flowermenu.md:8, native.md:281, widget.md:230 point at flowermenu's OWN anchor of that name — untouched)
grep -rn "tint" docs/addons | grep -v virtual/   → conventions.md:196, shapes.md:73-75, types/world.md:109
  (written above); font.md, drawing.md, keys.md, placing.md, README.md:138 — a raster's or a ghost's tint,
  discharged
```

`docs/client/world-3d.md` (143 lines, ceiling 150): the `SetupMod` row already maps the seam. One gotcha
joins it — a second `MixColor` on a gob REPLACES the damage wash, a `Slot` holding one state — or the
page splits if that line pushes it over.

## Context files

- `docs/addons/api/gob.md` — 1, 2
- `docs/addons/api/virtual/README.md` (the `:tint` rows, the `nil` line), `shapes.md` (Colours),
  `types/world.md`, `conventions.md` (the `nil` table), `threading.md` (the safe-write bullet),
  `README.md` — 1
- `docs/client/world-3d.md` (the `SetupMod` row, the shader recipe) · `DOCUMENTATION.md` — 1
- `src/io/brodgar/addon/GobScale.java` (the mould), `GobTint.java` (the attrib and its `Wash` state) — 1, 2
- `src/io/brodgar/addon/LuaGob.java` (the `scale`/`visible` verbs) — 1
- `src/io/brodgar/addon/GobIntent.java`, `UiApi.java` (`teardownGobScales`) — 2
- `src/io/brodgar/addon/AddonManager.java` (`colorArg`, `colorRefusal`, `color(Color)`, the `gobInfo`
  builder around `t.set("visible", …)`) — 1
- `src/haven/render/MixColor.java`, `src/haven/ColorMask.java`, `src/haven/render/sl/MiscLib.java`
  (`colblend`), `src/haven/GobHealth.java`, `src/haven/Gob.java` (`SetupMod`, `GobState`),
  `src/haven/render/State.java` (`Slot`, `instanced`), `src/haven/render/InstanceBatch.java` — 1
- `tools/docverbs.py`, `tools/refusalverbs.py` — 1, 2
