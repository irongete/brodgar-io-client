# 139 — tasks

- [x] **139.1 — A column lays its rows out.** `AddonWidget.axis`; `hafen.ui():column()` / `:row()` in
      `UiApi` through `attach`; `Column.relayout` on `add`/`cresize`/`cdestroy`, on the `visible` write and
      on `Layout.apply` (position half skipped under a column); `:gap(n)`/`:gap()`; the cascade's `padding`
      as the inner room; `:size(w)` pins the width, `:size(w, h)` both, `:size(nil)` follows again;
      `:role()` `"column"`/`"row"`. Refused, each naming the fix: `:position(x, y)`/`:position(nil)` on a
      child ("its place is its order — `:parent(other)` takes it out"), `:pack()` on a column ("packed by
      construction"), a window or a borrowed widget as a child. `UiApi.rebuild` keeps a rebuilt control's
      slot. Docs: `ui/column.md`, rows in `ui/controls/README.md`, `ui/README.md`, `api/README.md`; the
      `:pack()`/`:size(w)` sentences in `custom.md` and `controls/README.md`; `chrome.md#padding`'s fourth
      surface; `docs/client/widgets.md` gains the `add0`/`cresize`/`link` gotchas; `tools/docverbs.py`
      maps `col`/`row`.
      *Its suite* builds a column of three labels a tick after `:t139` and reads geometry back: the
      second's `y` is the first's `y + h`, then `+ gap` after `:gap(6)`; hiding the second moves the third
      up; a longer `:text` on the first pushes the rest down before the next line runs; destroying the
      first puts the second at the top; a row places `x` the same way; `:stock{padding = 8}` puts the
      first child at 8; `:size(120)` pins the width and the height still follows, `:size(nil)` lets the
      width go; a sheet rule's `position` on a child leaves it where the column put it; the four refusals
      fail *and* name the fix; `:role()` and `:type()` read as stated.
      <!-- extra context: src/io/brodgar/addon/CScrollport.java (the container adapter to mirror for the
      parent redirect and pending gate) -->

- [x] **139.2 — `margin` is the room around a row.** `Chrome.MARGIN` beside `PADDING`, parsed by the shared
      insets parser; `Sheet.Props`/`Resolved`/`SKey` fields; the `"margin"` arm of the data loader;
      `LuaRule` `margin(n | l, t, r, b)` / `margin()`; `widget:style().margin`; `:stock{ margin = … }`; a
      site key refuses it with `Sheet.layoutable`'s site message, `widget:rule()` takes it. `Column.relayout`
      adds a child's margin around it, gap between, never collapsed; everywhere else it is inert. Docs:
      `column.md` (a margin section), `geometry.md` (the fourth beside the three), `keys.md` (the
      tree-key-only set, line 133 and the table at 238).
      *Its suite* puts two labels in a column with `:gap(4)`, gives the second `:rule():margin(16, 2, 0, 3)`
      and asserts its `x` is 16 and its `y` is the first's bottom + 4 + 2, and the third's `y` is the
      second's bottom + 3 + 4; `rule:margin()` reads back `{l = 16, t = 2, r = 0, b = 3}` and
      `widget:style().margin` the same; a `:stock{ margin = 6 }` child sits at 6; a tree rule from Lua
      and the same rule loaded from JSON both land; `["*"]:margin(4)` fails naming a render site;
      a label outside any column keeps its `:position()` under a margin.

- [x] **139.3 — A widget you built can be disabled.** `Owned.enabled()`/`enabled(boolean)` on `Owned.State`
      and `AddonWidget`; the effective walk up `parent` over `Owned` ancestors; ONE tagged line at the top
      of `Widget.handle(Event)` (a press swallowed, a move and a key passed by, queries untouched);
      `setcanfocus(false)` while disabled; the topmost disabled widget draws inside `g.chcolor(DIM)`;
      `Button.disable(b)`; `CheckBox.chromeState()` (`// addon:`) overridden by `CCheck`; `w:enabled()` /
      `w:enabled(b)` in `LuaWidget`, refused on a borrowed widget naming the client; `:info().enabled`.
      Docs: `ui/writes.md` (the *Owned vs borrowed* table out of `widget.md`, nine anchor links
      re-pointed, `:enabled(b)` added), `widget.md` read rows, `surfaces.md:153`, `types`,
      `docs/client/widgets.md` (`handle` runs listeners first; `chcolor()` resets, `reclip` copies).
      *Its suite* builds a column with a button, a checkbox and an entry: `:enabled()` is `true` at birth;
      `col:enabled(false)` reads `false` and chains, and the checkbox's own `:enabled()` stays `true`;
      `:info().enabled` follows the flag; `check:value(true)` lands while disabled and `:value()` reads it;
      `entry:value("x")` lands; a borrowed window reads `true` and its write fails naming the client;
      `col:enabled(true)` restores. A `Pressed` handler logs `"PRESSED"`.
      `[manual]`: press the greyed button — expect: no `PRESSED` line, and the window does not drag.
      `[manual]`: look at the three — expect: dimmed, the box and the entry included.

- [x] **139.4 — A panel is columns inside columns.** No new verb: this task proves the composition the
      options page will be built from, and writes it as `column.md`'s worked example and
      `guides/custom-ui.md`'s panel section. A column in a `:scroll()`; a window packed around a column;
      a row of `:image()` + `:check()` on one line; a column nested with a left `padding` as an indent; a
      disabled group with its switch outside it.
      *Its suite* builds that panel: the scroll's bar (one of `sp:children()`) has `:range().max > 0`
      once thirty rows are in; `win:size()` grows by exactly a row's height when a label is added to the
      column inside it after `:pack()`; the image and the checkbox of one row share a `y`; the nested
      column's first child sits 16 further right than its parent's; `group:enabled(false)` leaves every
      child's own `:enabled()` `true` and `:info()` on the group reads `false`.
      `[manual]`: tick the box inside the greyed group — expect: nothing; flip the switch above it and
      tick again — expect: it ticks, and a `TICKED` line appears.
