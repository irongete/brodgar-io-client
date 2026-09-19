# 158 — own layout: the layout cascade holds on a surface of yours

## What & why

The layout cascade the docs describe — the verb (`widget:position(x, y)`, `widget:size(w, h)`)
above a sheet's `position`/`anchor`/`size` rule, above the stock place, `:position(nil)` and
`:size(nil)` dropping the verb's level — holds today only on a widget the client placed. On a
surface your addon built:

- A rule installed before the surface is built never reaches it: `Layout.apply` runs at the
  server's placement seam, at a sweep and for the followers of a moved widget, and arming a surface
  of yours runs none of it. A dock under `[name=myaddon/dock] = { anchor = {to = "screen", at =
  "left"} }` sits at the builder's default place until a sheet is installed somewhere.
- Its own anchor is not re-derived when it resizes (`dispatchResized`: screen anchors for a root,
  followers for the rest): a dock anchored `left` grows a row and keeps its old centre.
- The verb writes no level: `:position(x, y)` records nothing, `:position(nil)` finds nothing, and
  a rule that reaches the widget at a sweep overrides what the addon placed with no way back.

`geometry.md` promises the verb outranks any rule, a rule applies when a widget appears and an
anchor is re-derived when the widget resizes; `native.md` and `conventions.md` promise
`:position(nil)` falls back to a rule, then to the stock. This feature makes them true of a
surface of yours: a window, a bare widget, a column, a control, a mirror.

## Acceptance criteria

1. `:position(x, y)`, `:size(w, h)` and a control's `:size(w)` on a surface of yours write the
   hand-named level as on a borrowed widget: a rule naming it with a `position`, `anchor` or `size`,
   installed before or after the write, leaves the verb's place and box standing, at a sweep too.
   The fold is the write: a surface the root holds is clamped as a borrowed one (100 design pixels
   stay on screen), and a rule's `size` under a control's art lands on the art's box.
2. `:position(nil)` and `:size(nil)` on a surface of yours drop that level: the widget lands on the
   rule that names it, else on the stock — the builder's default place and box. Dropping what was
   never written changes nothing; a column's child still refuses both, naming the column. Three
   boxes are their own and a rule's `size` on them is inert, as on a column: a picture's, a
   mirror's (`:size(nil)`: their own box, as their pages say) and a packed surface's (`:pack()`
   forgets your size level; `:size(nil)` after it changes nothing).
3. A layout rule lands on a surface of yours when it is armed — the tick after the building
   statement, name, box and parent configured — for a window, a bare widget, a column, a control
   and a mirror alike, in the layer or a character's tree.
4. A surface's own anchor is re-derived when it resizes: by the verb, a rule's `size`, a pack, the
   client's corner grip. A widget anchored `bottomright` to another keeps its corner on that corner
   through its own resize.
5. `:name(word)` written after the surface armed reaches every installed rule at once, the layout
   half included, for the widget and its subtree: a rule naming it lands before `:name` returns,
   and a chain rule whose inner step it is starts matching below it. From a handler holding another
   tree's monitor it refuses, like every write.
6. Nothing changes for a borrowed widget, `remember(name)`, a `draggable` gesture, `revert()` or a
   column's children.

## Out of scope

- Renaming: `:name` stays write-once.
- A rule's `position` or `anchor` on a column's child, and a rule's `size` on a column: inert, as
  [column](../../docs/addons/api/ui/column.md) says.
- The stock of a borrowed widget the client re-laid (the ROADMAP's 062 line): untouched.
- Indexing the layout records (`plan.md`, *Discarded alternatives*).

## Docs impact

- `docs/addons/api/ui/style/geometry.md:26-27,31,66` — *One cascade*, *When it applies* (armed,
  named or resized), *`size` on a self-packing window* (a packed surface of yours, a picture, a
  mirror: inert), *Re-derived*: each now true of a surface of yours.
- `docs/addons/api/ui/native.md:27,37` — the `nil` row and *The cascade*: the stock of a surface of
  yours is the builder's default place and box.
- `docs/addons/api/ui/custom.md` — the `:position`/`:size` rows (a level, `nil` drops it, the clamp
  at the root), the `:pack()` row (forgets your size level), *Naming and dressing your own surfaces*
  (lands at once, layout included; refuses from another tree's handler).
- `docs/addons/api/ui/controls/display.md:36`, `docs/addons/api/ui/mirror.md:27` — a rule's `size`:
  inert.
- Derived impact set — `grep -rn "position(nil)\|when a widget appears\|the widget resized\|outranks any rule\|hand-named level" docs/addons`:
  `conventions.md:125` (*Undo your layer*: one clause), `writes.md:52` (stays true),
  `column.md:69,110` (true, and at arming), `keys.md:185`, `style/README.md:159`,
  `native.md:62,66,142`, `geometry.md:25,38,98`, `pixels.md:49` — read and discharged as true.

## Context files

- `src/io/brodgar/addon/LuaWidget.java` — 1, 2 (`position`/`size`/`pack`/`name` verbs, `Moved`, `recordMoved`, `levelFollows`, `rememberCapture`, `monitor`)
- `src/io/brodgar/addon/UiApi.java` — 1, 2 (`releaseMoved`, `stockPos`, `stockSizeArg`, `fitc`, `newUi`'s window, `armPending`)
- `src/io/brodgar/addon/Layout.java` — 1, 2 (`apply`, `applyHalf`, `fit`, `dispatchResized`, `derived`, `sweep`, `active`)
- `src/io/brodgar/addon/Sheet.java` — 2 (`styleOf`, `stockChanged`, `invalidateSubtree`)
- `src/io/brodgar/addon/AddonManager.java` — 2 (`layerTick`/`tick`, `drainResizedWidgets`)
- `src/io/brodgar/addon/AddonRegistry.java` — 1 (`STEPS`)
- `src/io/brodgar/addon/Gesture.java` — 1 (the level write)
- `src/io/brodgar/addon/Owned.java` — 1 (`minsz`, `of`)
- `src/io/brodgar/addon/Column.java` — 1 (`stacks`, `applied`)
- `src/io/brodgar/addon/AddonWidget.java` — 1 (`packed`, `repack`)
- `src/io/brodgar/addon/CImg.java`, `src/io/brodgar/addon/MirrorWidget.java` — 1 (the pin)
- `docs/addons/api/ui/style/geometry.md`, `docs/addons/api/ui/native.md`, `docs/addons/api/ui/custom.md`, `docs/addons/api/conventions.md` — 1, 2
- `docs/addons/api/ui/controls/display.md`, `docs/addons/api/ui/mirror.md`, `docs/addons/api/ui/edit.md` — 1
- `docs/client/widgets.md` — 1
