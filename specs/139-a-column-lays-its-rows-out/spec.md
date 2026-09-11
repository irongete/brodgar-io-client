# 139 — A column lays its rows out

## What & why

Every surface an addon builds is placed by hand — `:position(x, y)` per control, `y` added up in Lua —
and nothing greys a control out, so a group that depends on a switch cannot show it. This is the general
half of a richer UI, and what the options page (the next feature) is built with:

- **A column and a row lay their children out.** `hafen.ui():column()` places what is parented into it
  top to bottom and `hafen.ui():row()` left to right, in tree order, with a `:gap(n)` between children, the
  cascade's `padding` inside and a per-child `margin` around. Nesting is the vocabulary: a column in a
  column with a left padding is an indent; a row in a column is icon + checkbox on one line.
- **A widget you built can be disabled.** `w:enabled(false)` greys it, swallows its input and disables
  everything under it, so a dependent group follows its switch.

## Acceptance criteria

1. **The two builders.** `hafen.ui():column()` and `hafen.ui():row()` take no argument and hand back a
   Widget whose `:role()` reads `"column"` / `"row"` and whose `:type()` reads `"AddonWidget"` — it is a
   surface of yours, with `:name`, `:stock` and `Draw` like any bare widget. A child
   `:parent()`'d into one is placed along the axis in tree order; `:gap(n)` / `:gap()` is the room between
   two children, `0` by default; the cascade's `padding` (rule, `:stock`, `widget:rule()`) is the room
   inside. The box follows the content: `:size(w)` pins the width and leaves the height to the children,
   `:size(w, h)` pins both, `:size(nil)` lets it follow again; `:pack()` is refused naming that a column is
   packed by construction.
2. **Its children are its.** A hidden child takes no room and the rest close up; a child that resizes (a
   label whose text grows) or is destroyed re-lays the rest before the call returns; `:position(x, y)` and
   `:position(nil)` on a child are refused naming that its place is its order and `:parent(other)` takes it
   out; a rule's `position`/`anchor` on one is inert. A window and a borrowed widget are refused as
   children, naming why.
3. **`margin` is a property of the cascade.** `rule:margin(n)` / `rule:margin(l, t, r, b)`,
   `:stock{ margin = … }`, `widget:rule():margin(…)` and a theme's JSON write it; `rule:margin()` and
   `widget:style().margin` read it back as `{l=, t=, r=, b=}`; a site key refuses it as it refuses
   `position`. The column or row laying the widget out honours it — room around the child, added to the
   gap, never collapsed — and nothing else does, inert like `padding` on a surface that does not own its
   layout.
4. **`enabled` is a boolean property of a widget you built.** `w:enabled()` reads the widget's own flag,
   `true` from birth; `w:enabled(b)` writes it and chains. While it or any ancestor is disabled it is drawn
   dimmed, or in the `disabled` face a rule names on a button or a checkbox; a press on it is swallowed —
   neither it nor what lies beneath sees it — and no key reaches it, so `Pressed`, `Changed` and
   `Submitted` never fire; `:value(v)` and every write of yours still land. A borrowed widget answers
   the read and refuses the write, naming that its state is the client's. `:info()` gains `enabled`.
5. **They compose.** A column inside a `:scroll()` scrolls; a window `:pack()`s to the column inside it and
   re-packs when the column grows; a row of an image and a checkbox inside a column sits on one line; a
   column in a column with a left padding is indented by exactly that; a disabled column dims and silences
   its rows, and enabling it brings them back.

## Out of scope

- **Where a child sits across the axis** — children sit at the start edge at their own width; alignment
  and stretch are the next word, not half of this one.
- **Re-ordering children in place** — tree order is the order; another order is a rebuild.
- **`enabled` on the client's own widgets** — a borrowed control keeps the client's logic.
- **`margin` outside a column or row** — an absolutely placed widget has the verb; a margin there would be
  a second position.
- **The options page** — `opts:panel(fn)` and `control:bind(opt)` mount a column into Options ▸ AddOns;
  this feature is what they mount.
- **Grids** — two axes at once is a table, not a column.

## Docs impact

New: `docs/addons/api/ui/column.md`; `ui/writes.md` (the *Owned vs borrowed* table leaves `widget.md`,
at 349 lines, and `:enabled(b)` joins it). Modified: `ui/widget.md`, `ui/controls/README.md`,
`ui/README.md`, `api/README.md`, `ui/custom.md`, `ui/style/geometry.md`, `ui/style/keys.md`,
`ui/style/chrome.md`, `ui/style/surfaces.md`, `types` (the Widget snapshot), `docs/client/widgets.md`
(the layout gotchas read for this feature).

Derived impact set — `grep -rnE 'Everything else ignores|lays? (a )?widgets? out|greyed out|:pack\(\)' docs/addons/`:
`chrome.md:231` "Everything else ignores it" (a column honours `padding`); `geometry.md:4`,
`keys.md:133-134,238` "the three that lay widgets out" (four with `margin`); `surfaces.md:153` "greyed
out" by the client (or by you); 12 `:pack()` mentions, `custom.md:145` and `controls/README.md:112`
among them, where a column needs none.

## Context files

- `docs/addons/api/ui/{custom,widget,controls/README,controls/interactive,style/geometry,style/chrome,style/keys,pixels}.md`,
  `docs/addons/api/conventions.md`, `DOCUMENTATION.md` — every task
- `docs/client/widgets.md`, `docs/client/ui-controls.md`, `docs/client/widget-input.md` — 1, 3
- `src/io/brodgar/addon/CScrollport.java` — 1
- `src/io/brodgar/addon/Column.java` (`relayout`, the refusal texts), `docs/addons/api/ui/column.md` — 2, 3, 4
- `src/io/brodgar/addon/{AddonWidget,Owned,Controls,UiApi,LuaWidget}.java` — 1, 3
- `src/io/brodgar/addon/Layout.java` (`apply`, `applyHalf`) — 1, 2
- `src/io/brodgar/addon/Sheet.java`, `Chrome.java`, `LuaRule.java` (`padding` end to end, which `margin`
  mirrors) — 2
- `src/haven/Widget.java` (`add`, `contentsz`, `resize`, `cresize`, `show`, `handle`) — 1, 3
- `src/haven/{Button,CheckBox,GOut}.java`, `src/io/brodgar/addon/C*.java` (each adapter's `draw`) — 3
