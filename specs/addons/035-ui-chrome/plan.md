# 035-ui-chrome — Plan

## Approach

**Two engine seams, one style source, no per-frame Lua.** The resolved style already flows through 034's
`Fonts.treeStyles`/`combine`; C2 gives it two more consumers.

1. **A `Deco` fed by the sheet, swapped with `chdeco`.** A new `SkinDeco extends Window.DragDeco` reads the
   resolved `window.frame` rule and paints `drawbg`/`drawframe` from it, keeping `DefaultDeco`'s structure —
   close button, sizer, caption plate — and its **`iresize`/`contarea`** contract. A window is swapped when a
   rule names it and swapped **back** when none does, so the stock deco object returns and teardown is
   byte-for-byte. **Nothing in `haven.Window` is re-routed**: `chdeco` is a public seam dolda already provides.
2. **Where the swap is driven from.** The same generation the sheet already bumps: when `gen` moves, windows
   re-check whether they should carry a skin deco. This is a **per-window** decision, not per frame — the
   descent already visits them and `Window.tick` is the natural place; the swap itself must never run inside a
   draw.
3. **`IBox` for the window-less panels.** `IBox` is an interface, so a sheet-fed implementation drops in where
   the panels build theirs. Which panels are reachable is a **survey, not an assumption** (survey first, table
   second — 033.3's lesson).
4. **Geometry.** `pad` and a sliced border's insets feed `iresize`/`contarea`, the only place that decides where
   content starts. A window re-packs when the rule changes; a surface that cannot re-lay-out ignores the
   property (inert, never an error), and **which is which goes in the property × key table measured**.
5. **`widget:style()` grows `bg`/`border`/`pad`** under D-075 unchanged: it answers for the widget you point at.
   `border = {image = <asset>, slice = {l,t,r,b}}`, `bg = {color=…}` or `{image=…}`, `pad` a number — plain data,
   so a JSON theme can carry them (D-074: `hafen.asset` hands back the file, `hafen.json` parses it).

## Files to create / modify

- **create** `src/io/brodgar/addon/SkinDeco.java` — the sheet-fed `Deco`; and a sheet-fed `IBox` beside it.
- `src/haven/Window.java` — expected **`// addon:` one-liner only**, in `tick`, asking whether this window's
  deco should change. `drawbg`/`drawframe`/`iresize` are *not* edited: they belong to the deco being replaced.
- `src/haven/Fonts.java` — the resolved style carries the new properties; `combine` folds them per property
  (D-076) exactly as it folds `font`/`color`.
- `src/io/brodgar/addon/Sheet.java` — `window.frame` joins the site keys; the new properties validated (an
  unknown one still errors, D-072).
- `src/io/brodgar/addon/LuaWidget.java` — `style()` reports the three new properties.
- `docs/addons/api/ui.md` — the **property × key table** gains `bg`/`border`/`pad` columns and the
  `window.frame` row, plus the geometry caveats per key; `fonts.md`, `getting-started.md` follow.
- `addons/theme/` — a frame in `theme.json`; `addons/035-ui-chrome.1..4/` — one suite per task (`TESTING.md`).
- **`specs/codebase/gameui-windows.md`** already covers `Window`'s anim state machine and `fitwdg` (031); it is
  **extended** with the `Deco` contract (`iresize`/`contarea`/`chdeco`) rather than a new file.

## Risks & gotchas

*(prior art: `learnings/ui-widgets.md`, `specs/codebase/gameui-windows.md` — grepped, not read whole)*

- **`visible()` is animation-aware and `hide()` does not clear `visible`** (`animst` runs the show/hide/dest
  state machine). A deco swap must not fight it: swapping mid-animation, or during `reqdestroy`'s fade, is the
  obvious way to make windows flicker or strand a half-drawn frame.
- **A window draws its children through an offscreen buffer** (`Window.draw` → `drawbuf` → blit). The deco is
  *not* inside that buffer; anything assuming one clip/translate for both will paint in the wrong place.
- **The ctor's `sz` is the CONTENT size and the deco sizes the frame around it.** So `pad` changes the window's
  outer size for a fixed content — not the reverse. Getting this backwards silently shrinks every window.
- **`Window.show(boolean)` returns its own argument**, not "did it change" (031's correction). Do not use it as
  a did-anything signal when deciding to re-pack.
- **The caption is already routed** (F3a rebuilds the *whole* blur/tex furnace from the provider foundry). A new
  deco must keep doing that, or `window.title` silently stops working the moment a theme is installed.
- **The stock deco must come back as the same object**, not an equivalent one — the identity fast path is what
  makes an addon-less client byte-for-byte stock, and 033/034 both leaned on it.
- **Chrome has no raster cache.** Text got 026's cache; a frame is redrawn every frame, so a per-frame
  allocation here is a real cost. Measure against stock, and prefer engine-side painting from plain data.

## Discarded alternatives

- **Hardcoding texture paths in `haven.Window`'s constants (nurgling2's route)** — rejected: one skin, not
  selectable, not revertible, and it throws away a seam the engine already has.
- **A Lua `Deco` with an `onDraw` callback per window per frame** — rejected on cost at design time and again
  here: the engine paints from resolved data; Lua declares it.
- **Extending the `window` role to cover its deco** — rejected: the chrome is a child with role `nil`, and
  bending the classifier to hide that would make `w:role()` lie. `window.frame` names the site instead.
- **Editing `drawbg`/`drawframe` in `DefaultDeco`** — rejected: that is the class being *replaced*; editing it
  would mean skinned and stock chrome share code paths that must not diverge.
- **A geometry property that silently resizes surfaces that cannot re-lay-out** — rejected: inert-and-documented
  is the doctrine the emboss findings already set.
