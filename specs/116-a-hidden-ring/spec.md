# 116 — a hidden ring

## What and why

An addon that acts on `FlowerMenuAdded` cannot stop the ring being painted. The event fires at the end
of `FlowerMenu.added()`, which runs under the tree's own monitor — the same one the frame loop takes for
`tick` and `draw` — so the addon's decision is already taken before the ring's first frame. What is
missing is the verb that says *do not paint this one*.

`s:flowermenu():visible(b)` is that verb: the pair `gob:visible(b)` is, with the radial menu where that
one has an object. An addon that picks a petal the instant the ring opens stops flashing a ring the
player never had a chance to use; one that decides a ring is not wanted stops showing it at all.

**A hidden ring is one the pointer cannot be over.** It stays open, still holds the mouse and the
keyboard, and still ends with `FlowerMenuRemoved` — but a click on it can only ever *end* it, never pick
a petal, and its `1`..`9` keys do nothing. That rule is what makes hiding safe: the player's own click
and Esc remain the way out of a ring an addon hid and did not decide, and no click can be spent on a
blind action. Everything else is unchanged, and the flag dies with the ring.

## Acceptance criteria

1. With a ring open, `:visible()` answers `true`; `:visible(false)` hands the section back and
   `:visible()` then answers `false`; **the ring is not painted at all** — not one frame.
2. With no menu open, `:visible()` answers `nil` and `:visible(b)` raises, naming the character and
   listing nothing to hide, as the other writes on this section do.
3. `:visible(0)`, `:visible("false")` and `:visible(nil)` each raise naming `b` and the two values it
   takes.
4. A hidden ring answers `:list()`, `:count()` and `:gob()` exactly as a painted one, and
   `:select(label)` still picks from it.
5. A click anywhere while a ring is hidden ends it with **nothing chosen** — `FlowerMenuRemoved`
   carries `nil` — and picks no petal.
6. A `1`..`9` key while a ring is hidden picks nothing; Esc ends it with nothing chosen.
7. `:visible(true)` on a hidden ring paints it again, mid-life.
8. The next ring is painted: hiding one leaves nothing behind, on that character or any other.
9. `w:visible(false)` and `w:visible(true)` on a matched `@FlowerMenu` raise naming
   `s:flowermenu():visible(b)`; `w:visible()` on one still reads.

## Out of scope

- **Asking the server not to open a ring.** This feature acts on a menu the client already has; nothing
  in the protocol declines one in advance. The boundary is the client's own paint.
- **Every other write through the widget door on a matched `FlowerMenu`** — `:position`, `:parent`,
  `:remember`. The hide is the one that gains a canonical spelling elsewhere, so the hide is the one
  that has to point at it.
- **`widget:visible(b)` anywhere else**, which is unchanged.

## Docs impact

`grep -rln "radial menu\|flowermenu\|flower menu" docs/` →
`api/conventions.md`, `api/craft.md`, `api/event/bus/character.md`, `api/event/bus/README.md`,
`api/flowermenu.md`, `api/kin.md`, `api/locale.md`, `api/README.md`, `api/session.md`,
`api/ui/style/surfaces.md`, `api/world.md`, `guides/permissions.md`, `guides/translating.md`,
`README.md`, `client/widget-input.md`.

Written: `api/flowermenu.md` (the verb, the input rule, the example). Revised: `api/README.md` and
`api/session.md`, whose one-line descriptions of the section say what it holds and stop at picking;
`api/ui/native.md` and `api/ui/widget.md`, which state that `w:visible(b)` answers on any native widget;
`client/widget-input.md`, which states the propagation visibility skip and not the dispatch one.
Discharged with reason: the rest, which name the ring for its captions, its font scope, its permission
keys or its events — none of which this feature moves.

**A same-page trap:** `api/flowermenu.md` says the menu answers *while it is on screen*. Painted and
open stop being the same thing here, and that sentence is the feature's own page saying otherwise.

## Context files

- `docs/addons/api/flowermenu.md` — 1, 2, 3
- `docs/addons/api/gob.md` — 1 (the `:visible(b)` pair this one mirrors, and its wording)
- `docs/addons/api/conventions.md` — 1
- `docs/addons/api/README.md`, `docs/addons/api/session.md` — 1
- `docs/addons/api/world.md` — 1, 2, 3 (`s:world():click(gob, 3)` and `:gob():within(radius, filter)`, the
  right-click every suite here raises its own ring with, and the objects it tries)
- `docs/addons/api/ui/native.md`, `docs/addons/api/ui/widget.md` — 3
- `docs/client/widget-input.md` — 2, 3
- `docs/client/network.md` — 2 (`FlowerMenu.choose`, the one door Esc and a click away share)
- `docs/client/boot-and-loop.md` — 1 (the monitor the command queue and the frame loop share)
- `src/haven/FlowerMenu.java` — 1, 2
- `src/haven/Widget.java` — 1, 2 (`hide`/`show`/`visible`, the draw loop's skip, `Event.dispatch`)
- `src/io/brodgar/addon/FlowerMenuApi.java` — 1, 2
- `src/io/brodgar/addon/LuaGob.java` — 1 (the boolean refusal to copy)
- `src/io/brodgar/addon/LuaWidget.java` — 3
- `DOCUMENTATION.md` — 1, 2, 3
- `tools/docverbs.py`, `tools/refusalverbs.py` — 3
