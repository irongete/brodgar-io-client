# 139 — plan

## Approach

**A column is a bare surface with an axis.** `AddonWidget` — the class behind `hafen.ui():widget()` — gains
one field, `axis` (`NONE` / `COLUMN` / `ROW`); `hafen.ui():column()` / `:row()` mint one with it set and
hand it to the same `UiApi.attach` as `:widget()`. Everything a panel needs already lives there — `:name`,
`:stock{bg, border, padding}`, the `Draw` fire — and `LuaWidget.role()` answers the axis as `"column"` /
`"row"`. The layout is a static helper, `Column.java`: walk the visible children in tree order, place each
at padding + its margin along the axis, advance by its size + margin + `gap`, and resize the surface to
the content unless a `:size` pinned it.

**Laid out on the events that change it, never per frame** — `geometry.md`'s discipline for an anchor.
Each event is already a seam: a child entering (`AddonWidget` overrides `Widget.add(T)`, which
`add(T, Coord)` and `adda` reach; `add0` is private), resizing (`Widget.resize` → `parent.cresize(ch)`,
empty in `Widget`), leaving (`Widget.cdestroy`), hiding (`LuaWidget`'s `visible` write, the only writer
for an owned child), and a rule moving (`Layout.apply(w)`, run on install, release, sweep and placement —
re-lay `w` when it is a column and `w.parent` when that is one). `Layout.applyHalf`'s position half skips
a widget whose parent is a column: that is what makes a rule's `position`/`anchor` inert there.

**`margin` mirrors `padding` end to end.** `Chrome.MARGIN`, a second `Chrome.Pad`-typed property through
the same insets parser (`Chrome.parsePadding`'s body, renamed for both), a field on `Sheet.Props`,
`Sheet.Resolved` and `SKey`, the `"margin"` arm beside `"padding"` in the data loader, `LuaRule`'s
`margin` verb, and `widget:style().margin` read-back. A site key refuses it through `Sheet.layoutable`'s
first message only — `widget:rule():margin` is legal, unlike `position`. Only `Column.relayout` reads it.

**`enabled` is a flag on `Owned`, cut at one seam, dimmed at the draw.** `Owned` gains `enabled()` /
`enabled(boolean)`, carried by `Owned.State` (every adapter) and by `AddonWidget`. The effective state walks
`parent` for the first `Owned` that is disabled — a borrowed ancestor never disables. Input is cut by ONE
tagged line at the top of `Widget.handle(Event)`, the one method every event of every widget passes
before its listeners: a `MouseEvent` that is not a `MouseMoveEvent` returns `true` (swallowed: no listener,
no `mousedown`, no child walk, no fall-through to what lies beneath); a key returns `false` (skipped; the
focused key never arrives anyway, because disabling calls `setcanfocus(false)`); queries pass, so a tooltip
still answers. Dimming: the topmost disabled widget of a chain — own flag off, parent's effective on — draws
with `g.chcolor(DIM)` around its `super.draw`; `GOut.reclip` copies the state into every child's `GOut`, so
a disabled column dims its subtree in one call and no child dims twice. `Button` also takes its native `disable(b)`, so the `disabled` face the sheet already names is worn;
`CheckBox.draw` asks its state through a new protected `chromeState()` (`// addon:`) that `CCheck`
overrides to answer `"disabled"`.

## Files to create/modify

- `src/io/brodgar/addon/AddonWidget.java` — `axis`, `add`/`cresize`/`cdestroy` overrides, `enabled`;
  `Column.java` (new) — `relayout(AddonWidget)`, `childChanged(Widget)`, the refusal texts;
  `UiApi.java` — the two builders, and `rebuild` keeping a rebuilt control's slot.
- `src/io/brodgar/addon/LuaWidget.java` — `role`, `parent(w)` refusals (window, borrowed), `position`
  refusal on a stacked child, `size(w)`/`size(nil)`/`pack` on a column, `visible` → `Column.childChanged`,
  `enabled` verb, `info().enabled`, `gap` verb.
- `src/io/brodgar/addon/Layout.java` — skip the position half under a column; re-lay on `apply`.
- `src/io/brodgar/addon/{Chrome,Sheet,LuaRule}.java` — `margin`.
- `src/io/brodgar/addon/Owned.java`, `Controls.java`, `C*.java` — the flag, the effective walk, the dim;
  `src/haven/Widget.java` (`handle`, one line) and `CheckBox.java` (`chromeState`), both `// addon:`;
  `tools/docverbs.py` — `col`/`row` spellings → `widget`.
- Docs: `docs/addons/api/ui/column.md` (new), `ui/writes.md` (new, the *Owned vs borrowed* table out of
  `widget.md` + `:enabled`), `ui/widget.md`, `ui/custom.md`, `ui/controls/README.md`, `ui/README.md`,
  `api/README.md`, `ui/style/{geometry,keys,chrome,surfaces}.md`, `types` (snapshot),
  `docs/client/widgets.md` (the gotchas below that are the engine's).

## Risks & gotchas

- **`Widget.add0` is private; `add(T)` is the door** — `add(T, Coord)` and `adda` reach it. Override it,
  re-lay after `super.add`; the coordinate the caller passed is overwritten, which is the contract.
- **`Window.resize` never reaches `cresize`** — one more reason a window is refused as a child; a label's
  `settext` resizes through `Widget.resize`, so `cresize` fires for it.
- **`contentsz()` skips invisible children and `pack()` is `resize(contentsz())`.** The column's own
  measure is `Column.relayout`'s, so `:pack()` on it is refused rather than run against the wrong measure.
- **`UiApi.rebuild` appends.** A face setter kills the old widget and `parent.add(neww, at)`s the new, and
  `Widget.link` appends at the end of its z — in a column that moves the control to the end. Record
  `oldw.prev` before the kill and re-link after it, inside the block `rebuild` already holds.
- **`Layout.apply` holds `LuaWidget.monitor(w)` and takes `Sheet.class` inside it** — the established
  order. `Column.relayout` reads `Sheet.styleOf` for the column and each child, so it runs under the
  column's monitor and never under a second tree's.
- **`Layout.applyHalf` records `stockPos` at the first touch.** A stacked child never enters that fold —
  the skip comes first — so `:position(nil)` has nothing to restore and is refused like `:position(x, y)`.
- **`GOut.chcolor()` resets to the slot default, not to the previous colour.** Safe because `reclip`
  hands each child a copy; the parent's `GOut` is untouched by a child's reset.
- **`Button.disable(true)` monochromises the raster and drops presses on its own** — keep it; the `handle`
  cut is what stops the listeners. `dis` is private, `disable(boolean)` public.
- **`MouseMoveEvent` broadcasts to every visible child, rect-test or none.** The cut returns `false` for a
  move, or a disabled control would eat every move on screen.
- **A `[manual]` is unavoidable for the press** — nothing delivers a click from Lua (ROADMAP, filed 061);
  the suite proves the flag and the silence of `Changed`, the maintainer presses once.

## Discarded alternatives

- **`hafen.ui():stack()`** — "stack" is an item stack wherever the pages say the word.
- **One builder with `:horizontal(b)`** — the axis is a building-time fact, and the selector vocabulary
  needs a word per axis; a role reads back what a boolean would have hidden.
- **A control adapter (`CColumn` beside `CScrollport`)** — a control has no `:name`, `:stock` or `Draw`, so
  a panel could carry no background and no theme could reach it; a bare surface has all three.
- **`:pad(…)` and `:margin(…)` as verbs on the widget** — `padding` lives in the cascade, and a second
  spelling beside it is the dual style the grammar forbids; the hand-named level is `widget:rule()`.
- **Collapsing margins** — arithmetic `:position()` cannot read back.
- **Re-laying in `tick()`** — `geometry.md` promises layout is applied on the events that change it, never
  per frame; every change of a child already passes a seam.
- **`enabled()` reading the effective state** — `visible()` reads the own flag and `tvisible` is the walk;
  the same shape, so a child of a disabled column reads `true`.
- **Overriding `handle` in the fifteen adapters** — fifteen sites for one rule; one tagged line in
  `Widget.handle`, the method every event passes, is the smaller core edit.
- **Skipping a disabled widget in `PointerEvent.propagation` like an invisible one** — the press would
  fall through to what lies beneath, and a disabled control still occupies its place.
- **`enabled` on borrowed widgets** — the client's own logic keeps running on server messages, so a
  greyed native control would still change; a feature of its own.
