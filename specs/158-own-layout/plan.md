# 158 — own layout: plan

## Approach

**One cascade for every widget, owned or borrowed.** The fold exists — `Layout.apply` → `applyHalf`
reads `Sheet.styleOf(w)` under `LuaWidget.topWant(w, pos)` (the verb's level), writes the winner and
falls to `UiApi.stockPos`/`stockSizeArg` when nothing names the half. A surface of yours is kept out
at three doors; the feature opens them.

**158.1 — the verb is a level on a surface of yours, and the fold is its write.** The owned branch
(`own`, `content != null`) of `LuaWidget`'s `position` and `size` verbs becomes the borrowed one:
inside the monitor block it records the level (`recordMoved`, `wantPos = Layout.Anchor.at(to)` /
`wantSize = to`, `Layout.nextSeq()`) and writes nothing to the widget but `AddonWidget.packed =
false` on a size; `Layout.apply(w)` below the block is the write — `Gesture` and `rememberApply`
already do. **The direct write goes**: `applyHalf` records the stock at the first touch off the
widget (`w.c`, `sizeArg(w)`), so a write before it makes the verb's own place the stock. The fold
lands the same coordinate — `fit` clamps at the root as for a borrowed widget; `custom.md` says so —
ends in `Column.applied` and cascades the followers; `levelFollows` leaves the verbs. The one-number
`:size(w)` on a control records `wantSize = {w, Px.out(min.y)}`; a column keeps its pins. **The size
half floors an owned control at `Owned.minsz()`** per axis after `Px.in` — device pixels, `0`
unconstrained — so the exact-minimum write lands the art's own device height (058.4) and a rule's
`size` under the art lands on the box rather than clipping it. **The size half skips a surface whose
box is its own**: `Column.stacks`, widened to a `packed` `AddonWidget`, a `CImg` and a
`MirrorWidget`. Those three never enter the record — their `:size` arities keep their direct write
and `Layout.moved`, the pin is their level, a rule's `size` on them inert as on a column.
**`:pack()` on a surface of yours forgets this addon's size half**, `wantSize` and stock `size`
alike, no fold: the box is the content's from here on (139.4), so a later `:size(w, h)` records the
packed box as the stock. A borrowed window's pack stays the level `edit.md` says.

**158.2 — a rule lands when the surface is armed, named or resized.** `UiApi.armPending` calls
`Layout.apply(c.rootw())` after `c.armed()`, gated on `Layout.active()` as `placed` is: the tick
after the building statement, name, box and parent configured, holding no monitor (`layerTick` and
`tick(UI)` call it outside their blocks). `Layout.dispatchResized` applies `w` itself when `derived`
holds it, before the followers; the seam drains on the tick and `apply` is idempotent, so the resize
it may cause is the last. The `name` verb, after `nameSet`, calls `Sheet.named(w)` —
`invalidateSubtree(w)` under the widget's monitor and `Sheet.class`, `stockChanged`'s narrow path
widened to the subtree because a name is a selector step a chain rule matches through — then
`Layout.applySubtree(w)`: collected under `w`'s monitor, applied one widget at a time holding none,
as `sweep(UI)` does. The drawing half lands on the next draw, the layout half before `:name`
returns. `:name` thus refuses from a handler holding another tree's monitor, like every write
(112.2); `custom.md`'s naming section says so.

## Files to create/modify

- `src/io/brodgar/addon/LuaWidget.java` — 158.1: the owned branch of `position`/`size` records and
  applies, the direct write and `levelFollows` gone; `pack` forgets the size half; 158.2: `name`
  invalidates and applies.
- `src/io/brodgar/addon/Layout.java` — 158.1: the size half's guard (packed, picture, mirror) and
  the art floor; 158.2: `dispatchResized` applies the widget's own anchor; `applySubtree(w)`.
- `src/io/brodgar/addon/UiApi.java` — 158.1: the pack's forget, beside `releaseMoved`; 158.2:
  `armPending` applies.
- `src/io/brodgar/addon/Sheet.java` — 158.2: `named(Widget)`.
- `docs/addons/api/ui/style/geometry.md`, `docs/addons/api/ui/native.md`,
  `docs/addons/api/ui/custom.md`, `docs/addons/api/conventions.md`,
  `docs/addons/api/ui/controls/display.md`, `docs/addons/api/ui/mirror.md` — 158.1 (the level, the
  stock, the clamp, the pack, the three inert boxes), 158.2 (*When it applies*, *Re-derived*, the
  name and its refusal).
- `addons/158-own-layout.1/`, `addons/158-own-layout.2/` — the suites.

## Risks & gotchas

- **The stock is the fold's to read**: nothing writes the widget between the level and `apply` but
  `packed = false`; `Column.childChanged` and `repack` run from `apply`.
- **`movedNative` is the teardown's restore list**; a surface of yours restores nothing by order —
  `AddonRegistry.STEPS` kills `widgets` before `moved windows`, and `stillMovable` reads
  `hasparent(root)` for an owned widget's `id` −1. `recountMoved`/`anyMoved` must stay exact.
- **`anyMoved` goes up for good** once an addon positions its own surfaces, one record per
  positioned child, hundreds, scanned by identity. `placed`, `textHalf`, `dropStock` and
  `pruneRemoved` pay a few scans per widget placed or destroyed — a hundred-item container is under
  a millisecond, once — and `sheet:install()` already sweeps every tree whenever a layout rule
  exists. Accepted; `p:frame()` arbitrates.
- **Stale comments** — 144.3 in `rememberCapture`/`chromeDragged`, 153.1 in
  `chromeResized`/`levelFollows`, `Moved`'s class doc, the verbs' "no layer, no cascade" — are
  rewritten.
- **`apply` runs below the monitor block** (112.6): a follower may stand in another tree; the arming
  tick's `apply` on a surface inside a client window takes that character's monitor, and neither
  step holds one.
- **`Sheet.named` takes the widget's monitor first, then `Sheet.class`** — `styleOf`'s order
  (`register` comment).

## Discarded alternatives

- **Keeping the direct write beside the level**: `applyHalf` records the stock off the widget at the
  first touch, so the write it followed would be the stock.
- **Exempting a surface of yours from `fit`**: a window the user cannot grasp is what `fitwdg`
  prevents; a child inside a surface of yours is not clamped, and an off-screen surface is hidden.
- **An owned branch in `Layout.resize`**: `Window.resize` takes the content box and the 153.1
  override sizes the canvas to it — `w.resize(to)` already lands where `sizeArg` reads.
- **A record for a picture or a mirror**: their box is the source's, re-read on every `:source(h)`,
  so a first-touch stock is stale by the next picture; the pin is their level.
- **Applying a `size` rule to a packed surface, then re-packing**: the repack resizes, the resize
  seam re-applies, and the two alternate tick by tick.
- **Indexing `movedNative`, or a borrowed-only `anyMoved`**: a second structure kept in step at
  eight sites, for scans the profiler cannot see.
- **Queueing `:name`'s halves to the step when `wouldNest(w)`**: the layout half would land a frame
  late, the late name the verb exists to land at once; every other write refuses from there, naming
  the same fix.
- **Applying at attach** (`UiApi.attach`): the widget has no name, no box and its default parent
  then; the arming tick follows the building statement.
- **Applying the rule from every owned `:size(w, h)`** without a level: the rule's box would fight
  the verb's, the precedence the docs deny.
- **Re-checking a name like a late caption** (the placement seam's pending list): that list is the
  server's windows; the addon writes a name at a moment it knows.
- **Leaving `:position(nil)` a no-op on a surface of yours**: `native.md`, `geometry.md`,
  `conventions.md` and `writes.md` say the opposite; the sweep's no way back is what this feature
  ends.
- **`sheet:install()` after building the surface** (multi-session today): it works, but the addon
  pays for the client's gap on every load, and nothing covers a later surface, a resize or a late
  name.
- **A per-frame fold of every owned widget**: `geometry.md` promises "never per frame", and the
  three moments a place can change are seams the client has.
