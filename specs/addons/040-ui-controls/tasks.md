# 040-ui-controls — Tasks

<!-- LINE LIMIT LIFTED for this feature by the maintainer's explicit instruction at /plan time. The
     template's 60-line ceiling does NOT apply to this file. One-off for 040; not a precedent.
     One task = one session: compiles, ships its own suite under addons/040-ui-controls.<X>/, and is
     verified in-game on its own with `:t040-<X>`. Suites stand ALONE (D-085): each re-asserts what
     its own claims rest on rather than leaning on an earlier task's suite. -->

- [x] **040.1 — The ownership contract, and `hafen.ui():button()` as its first consumer.** Extract `Owned`
      (owner, root, dead) from `LuaWidget.isOwn`; `AddonWidget implements Owned` unchanged; `ownedContent`
      tests the interface; widen the owned registry so teardown reaches a control. Ship `:button()` →
      `Button` with `:text(s)` and `:onPress(fn)`, plus the adapter-naming convention every later task
      copies. **Suite proves**: a button is built, is in the tree, `:type()` is `Button`, `:role()` is
      `button`, a selector finds it; the owned verbs answer on it (`:position`, `:size`, `:visible`,
      `:destroy`) and refuse on a *native* button of the same class; `:text()` round-trips; a builder
      constructed with an argument is refused (R4); teardown leaves no orphan (tree counted before/after).
      `[manual]`: press it and read the printed line; confirm it looks like the client's own button.
      <!-- extra context: `src/haven/Button.java`, `src/haven/SIWidget.java` -->

- [x] **040.2 — The face setter, and `IButton`.** `:image(up, down [, hover])` on `:button()`, completing it
      as an `IButton`: the pending-rebuild rule (D-113 × D-121) — legal while the surface is pending, refused
      once armed, naming that a face is chosen while the control is built. Faces take `hafen.asset` handles
      and engine resource names. **Suite proves**: `:image` before arming swaps the class (`:type()` is
      `IButton`) while the **Lua handle stays `==` the same object**; `:image` after arming is refused naming
      the rule; `:text(s)` after arming still works on a text button (`Button.change`); two- and three-image
      forms both build; a bad handle is refused saying why. `[manual]`: hover and press, confirm the three
      faces.
      <!-- extra context: `src/haven/IButton.java`, `docs/addons/api/asset.md` -->

- [x] **040.3 — Display: `:label()` / `:image()` / `:separator()` / `:progress()`.** `Label` with `:text`,
      `ILabel` via the 040.2 face setter, `Img` with `:source(h)`, `HRuler`, and `Progress` with `:value()`
      (0..1). **Suite proves**: each is built and placed; `:text(s)` on a label **changes its `:size()`**
      (the self-resize is asserted, not assumed); `:value()` round-trips on progress and a write outside
      0..1 is refused; a control with no value (`label`, `separator`, `image`) reads `:value()` as `nil`
      rather than throwing; `:source(nil)` is refused (R5). `[manual]`: read the four on screen.
      <!-- extra context: `src/haven/Label.java`, `ILabel.java`, `Img.java`, `Progress.java`, `HRuler.java` -->

- [x] **040.4 — `:check()`, and the `:value()`/`:onChange()` spine.** `CheckBox` with `:text`, `:value`,
      `:onChange`, and `ICheckBox` (four faces) through the 040.2 setter. This is where the value spine is
      first written, so it is where its rules are pinned. **Suite proves**: `:value()` round-trips both ways;
      a **programmatic `:value(v)` does NOT re-enter `:onChange`** (the feedback-loop check); `:value(nil)`
      is refused (R5); `:onChange` set twice keeps the later handler only; the four-face form builds.
      `[manual]`: click it and read the line; confirm the tick looks native.
      <!-- extra context: `src/haven/CheckBox.java`, `ICheckBox.java`, `ACheckBox.java` -->

- [x] **040.5 — `:radio()` as ONE control.** `:rows{…}` builds the buttons, `:value(label)` checks one,
      `:onChange(fn)` fires on a user pick; the stack is laid downward from the control's own `:position`,
      one row height apart, and `RadioGroup`/`RadioButton` never surface. **Suite proves**: three rows build
      three buttons under one parent; `:value()` reads the checked label and `:value("x")` for an unknown
      label is refused naming the rows; `:size()` covers the whole stack; re-`:rows{}` replaces the set;
      an empty `:rows{}` is an empty control, not an error. `[manual]`: click the second option, confirm the
      first clears.
      <!-- extra context: `src/haven/RadioGroup.java` -->

- [x] **040.6 — `:slider()` and `:scrollbar()`.** `HSlider` with `:range(min, max)`, `:value(n)` and
      **`:onChange(v, final)`** — one callback over the engine's `changed()`/`fchanged()` pair — plus a bare
      `Scrollbar` with the same three verbs. **Suite proves**: `:value` clamps into `:range` rather than
      accepting an out-of-range write; changing `:range` re-clamps a value that no longer fits; a
      programmatic write does not re-enter the handler; `:range(nil)` is refused. `[manual]`: drag it —
      confirm `final = false` lines while dragging and exactly one `final = true` on release.
      <!-- extra context: `src/haven/HSlider.java`, `Scrollbar.java` -->

- [x] **040.7 — `:entry()`.** `TextEntry` with `:value(s)`, `:onChange(fn)` per keystroke and
      `:onSubmit(fn)` on Enter; **`entry:text()` retires** through the existing `Retired` table, throwing and
      naming `:value()`. **Suite proves**: `:value()` round-trips; `entry:text()` throws with `value` in the
      message; `:onSubmit` is distinct from `:onChange`; a programmatic write does not re-enter either.
      `[manual]`, and this one is **not optional**: click the field, type, and confirm the character does
      **not** also reach the game — no movement, no hotkey, no chat.
      <!-- extra context: `src/haven/TextEntry.java`, `ReadLine.java`, `src/io/brodgar/addon/Retired.java` -->

- [ ] **040.8 — `:scroll()`.** `Scrollport` + its `Scrollbar`, with `:parent(sp)` redirecting into the port's
      inner container. **The trap this task exists to not fall into**: `Widget.add` does not route through
      `addchild`, and `Scrollport` only overrides `addchild` — so a plain `add()` drops the child beside the
      bar instead of inside the scrolling area, and it looks almost right. **Suite proves**: a child parented
      to the port is inside `cont` (asserted through `:parent()` from the child, not by eye); content taller
      than the port makes the bar live and `:value()` on the bar scrolls it; content shorter leaves it inert;
      a child is clipped to the port's box. `[manual]`: wheel over it and confirm it scrolls.
      <!-- extra context: `src/haven/Scrollport.java`, `Scrollbar.java` -->

- [ ] **040.9 — The row bridge, and `:list()`.** `LuaRows` implementing `SListWidget`'s `items()` /
      `makeitem(...)` over a Lua array, with the client's ready-made rows — `TextItem.of` for a string,
      `IconText.of` for `{icon =, text =}` — then `SListBox` with `:rows`, `:rowHeight`, `:value`,
      `:onChange`. The bridge ships with its consumer (D-108). **Suite proves**: a string table renders rows
      and an icon table renders icon rows; a **mixed** table takes the right row per element; `:rows{}` is an
      empty list, not an error; replacing the table re-renders; `:value()` reads the selection and `:value(x)`
      sets it; `:rowHeight` defaults and overrides. `[manual]`: click a row, confirm the highlight and the
      printed line.
      <!-- extra context: `src/haven/SListWidget.java`, `SListBox.java` -->

- [ ] **040.10 — `:dropdown()` and `:menu()`.** `SDropBox` (`:value`/`:onChange`) and `SListMenu`
      (`:onSelect`, no value — it fires and holds nothing), both over the 040.9 bridge. Settle here how
      ownership attaches when the engine's `of(...)` factories hand back anonymous subclasses — subclass
      instead, or attach `Owned` another way, and say which in `HANDOFF.md`. **Suite proves**: the dropdown
      opens, `:value()` reads the pick and a programmatic write moves it without re-entering `:onChange`; the
      menu has **no** `:value()` (reads `nil`) and `:onSelect` carries the row; both refuse `:rows(nil)`;
      both are owned (the 040.1 provenance check, re-asserted here on the anonymous-subclass path).
      `[manual]`: open the dropdown, pick an entry; press a menu row.
      <!-- extra context: `src/haven/SDropBox.java`, `SListMenu.java` -->

- [ ] **040.11 — `:grid()`.** `GridList` with `:rows`, `:cell(w, h)` and **`:onCell(g, item, w, h)`** — the
      one model-backed control that draws rather than builds rows, so it takes the `LuaGOut` wrapper the API
      already ships. **Suite proves**: cells lay out across the width and wrap; `:cell` changes the layout;
      `:onCell` receives the same `g` object `:onDraw` does (asserted by calling a `g` verb inside it);
      an empty `:rows{}` draws nothing and does not throw; a `:onCell` that errors is isolated and does not
      kill the frame. `[manual]`: confirm the icons are laid out in a grid.
      <!-- extra context: `src/haven/GridList.java`, `src/io/brodgar/addon/LuaGOut.java` -->

- [ ] **040.12 — `:table()`.** `TableBox` with `:rows`, `:rowHeight` and `:columns{…}` (title, width, and an
      `of(row)` accessor per column) over `ColSpec.of`. **Suite proves**: columns render with their headings
      and widths; `of(row)` is called per cell; a column table missing a required key is refused naming it;
      re-`:columns{}` replaces the set; `:rows{}` with columns set is an empty table, not an error.
      `[manual]`: read the table on screen, confirm headings and alignment.
      <!-- extra context: `src/haven/TableBox.java` -->

- [ ] **040.13 — The close: the example addon, the docs, and the completeness sweep.** One example addon
      building a real panel (the worked one in `api-sketch.md` is the model) with an accurate one-paragraph
      manifest `description` (D-142), referenced from `examples.md`; the docs tier finished — the new
      `controls.md` page, the `README`/`widget`/`custom`/`selectors` updates and both "API at a glance"
      rows — meeting area `docs`'s §12 checklist; `specs/codebase/ui-controls.md` written and indexed in
      `codebase-map.md`. **Suite proves**, in one pass over the whole roster: every one of the 16 builders
      exists and is callable bare; every one refuses an argument (R4); every documented verb answers on the
      controls the page says it answers on and reads `nil` on the others; every retired spelling throws
      naming its replacement; and a `:reload` gives back a tree with no control left in it. `[manual]`: run
      the example addon's panel and confirm it works end to end.
