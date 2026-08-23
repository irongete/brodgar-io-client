# 103 — Drawn over a widget

## What & why

`gob:overlay()` answers "what is drawn at this game object?" and `hafen.ui():overlay()` answers it for
the screen. Between them sits the receiver nothing answers for: **one widget**. An addon that wants a
number on an item icon, a mark on a button, a bar under a slot has three ways to reach it and none of
them works — a native widget has no `Draw` subscription, a stylesheet rule on anything that is not a
window, panel, button or field is inert, and a control adopted into a widget whose class overrides
`draw(GOut)` is never painted at all. What is left is a screen-wide painter that re-derives every
rectangle every frame from a tree it has to search.

This feature adds the third receiver, in the vocabulary the other two already speak:
`widget:overlay()`, a collection keyed by your own name, whose members paint after the widget they
hang on, inside its box, and die with it.

It also adds the read that makes an item icon worth decorating: **`widget:item()`**, the item an icon
draws. Today the icon and the item are two facts with no verb between them — `widget:items()` walks a
container's icons and cannot answer for one, so an addon handed an icon by a selector subscription
can read where it is and not what it is.

## Acceptance criteria

Each is checked by the suite of the task that ships it.

1. `widget:item()` answers the Item an icon draws — the same
   interned object its container's `:items()` lists — and `nil` on every other widget.
2. `widget:overlay()` is the standard collection (`:add` `:get` `:remove` `:list` `:count` `:find`) on
   any widget, owned or borrowed; keys are per addon; `:add` on a live key replaces; `:list()` is the
   draw order.
3. An overlay paints **after** its widget, translated and clipped to that widget's own box, and paints
   nothing while the widget or any ancestor is hidden.
4. It dies with the widget — `:exists()` false, the collection empty — and `:reload` or disabling the
   addon takes every one of them off every tree.
5. Two kinds: `:draw(fn)` and `:text(s)`. An overlay says exactly one; a second, different kind raises
   naming the first; a bare one paints nothing.
6. The text kind never enters Lua at the draw: with one up, the text cache's **miss** count does not
   move across frames while its hit count does.
7. The text kind is placed and dressed by `:anchor(ax, ay)`, `:offset(x, y)`, `:color(c)`, `:font(h)`
   and `:background(c)`, each with a bare read of the same name.
8. Every page touched is inside its ceiling, and every link and anchor on it resolves.

## Out of scope

The boundary, and what lies past it:

- **The reverse read.** An icon answers what it draws; an item still answers *where* it is with
  `:cell()` and `:slots()`, and nothing hands back the icons drawing one item. Past the boundary
  because it is a relation with its own question (a worn item has two icons), not the other half of
  this read.
- **A `:text` kind on `hafen.ui():overlay()`.** The screen is not a box with corners, so the anchor
  that places a label on a widget or a gob has nothing to mean there.
- **Bus events for widget overlays.** Nothing but an addon attaches one, and the widget's own
  `Removed` is already the edge; the gob's two events exist because the game attaches those.
- **Input.** What is under an overlay is the widget, and clicking a widget already has its verb.
- **`hafen.ui():overlay()` itself** — neither retired into the root widget's collection nor moved out
  from under the addon layer.

## Docs impact

`grep -rln "hafen\.ui():overlay\|HUD overlay\|custom.md#overlays" docs/` →
`api/README.md`, `api/asset.md`, `api/menugrid.md`, `api/overlay.md`, `api/ui/custom.md`,
`api/ui/drawing.md`, `api/ui/pixels.md`, `api/vr/README.md`, `api/vr/ghosts.md`, `api/vr/sprites.md`,
`api/world.md`, `guides/custom-ui.md`.

Written: **new** `api/ui/overlay.md` (the UI overlays, both receivers) · `api/ui/custom.md` (its
Overlays section moves out) · `api/ui/widget.md` (`:item()`, `:overlay()`) · `api/ui/items.md`
(`:item()`) · `api/ui/drawing.md` (the third callback that receives `g`) · `api/ui/README.md` +
`api/README.md` (the new leaf) · `api/overlay.md` and `guides/custom-ui.md` (the receiver they name
as the only other one) · `api/ui/style/README.md` (the edge section, where "not a rule" now has an
answer) · every remaining anchor link above · `docs/client/widgets.md` (the draw seam and the
`super.draw` trap; over its ceiling, so it splits).

## Context files

- `src/io/brodgar/addon/LuaWidget.java` — 1, 3, 4
- `src/io/brodgar/addon/LuaItem.java` — 1
- `src/io/brodgar/addon/LuaGobOverlay.java` — 3, 4
- `src/io/brodgar/addon/UiApi.java` — 3, 4
- `src/io/brodgar/addon/LuaGOut.java` — 4
- `src/io/brodgar/addon/Addon.java`, `AddonRegistry.java`, `LuaCollection.java` — 3
- `src/io/brodgar/addon/Px.java`, `Args.java`, `AddonManager.java` — 3, 4
- `src/haven/Widget.java`, `src/haven/UI.java` — 3
- `src/haven/WItem.java` — 1, 3
- `docs/client/widgets.md` — 3
- `docs/addons/api/ui/custom.md`, `api/ui/README.md`, `api/README.md` — 2
- `docs/addons/api/overlay.md` — 2, 3, 4
- `docs/addons/api/ui/widget.md`, `api/ui/items.md` — 1
- `docs/addons/api/ui/drawing.md`, `api/ui/pixels.md`, `guides/custom-ui.md` — 2, 4
- `DOCUMENTATION.md` — 2, 3, 4
