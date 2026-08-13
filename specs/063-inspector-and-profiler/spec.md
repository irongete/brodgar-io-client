# 063 — the inspector and the profiler

## What & why

The client ships sixteen demo addons. Two of them are **tools** — `widgetstack`, the widget inspector,
and `profiler`, the frame window — and the other fourteen are illustrations of a surface that its own
reference page already states. The fourteen go. What stays has to work, and today neither does: **a
click on one of our own windows lands 39 device pixels above where it was drawn.**

`hafen.ui():window()` builds a `haven.Window` chrome around an `AddonWidget` content leaf
(`UiApi.newUi`). `Draw` fires from the **content** (`AddonWidget.draw`), in content-local pixels; but
`widget:on("MouseDown", fn)` installs its `Widget.listen` hook on the interned root, which for a window
is the **chrome** (`WidgetSubs.installInput`), in outer pixels. The two origins differ by
`Window.contarea().ul` = `tlm + dsmrgn` = `UI.scale(27, 39)`. So the Profiler's tab bar, drawn at content
`y < 18`, can never be clicked, while clicking the *caption* switches tabs; and WidgetStack's first stack
rows sit under the title bar, so every attempt to drag the window opens another Inspector. The same seam
also swallows `ev:preventDefault()`: `Window.handle` propagates to its children whatever the listener
answered.

Both tools are then given the read they most obviously lack. `widget:res()` names a widget whose *code*
came from a resource, an item, or a meter — it says nothing about the widget that simply **shows a
picture**, which is most of the client's chrome. A new `widget:picture()` names it, and the inspector
grows a line for it and for every other read that answers on the hovered widget.

## Acceptance criteria

1. On a window built by `hafen.ui():window()`, `MouseDown`/`MouseUp`/`MouseMove`/`Wheel` report the
   coordinates `Draw` paints in: `ev:x(), ev:y()` is the pixel `g:text(s, x, y)` writes at.
2. A press on that window's caption, frame or close button reaches **no** handler of yours — dragging
   the title moves the window and opens nothing.
3. `ev:preventDefault()` on those four keys is honoured on a window that has chrome.
4. `widget:picture()` answers the resource name of the picture a widget shows —
   `"gfx/hud/wnd/lg/cbtnu"` on any window's close button — and `nil` where it holds none. Any argument
   raises, naming `widget:res()` and `widget:image()` as the two neighbours it is not.
5. WidgetStack's inspector reports every read that answers on the hovered widget — `:picture()`,
   `:tooltip()`, `:value()`, `:range()`, `:rows()`, `:items()`, `:focused()`, `:style()` and the rest —
   and prints nothing for a read that answers `nil`.
6. The Profiler's six tabs switch on a click **on the tab**, and the caption switches none.
7. `ls addons/` is `profiler` and `widgetstack`, and nothing under `docs/`, `DOCUMENTATION.md`,
   `CLAUDE.md`, `specs/ROADMAP.md`, `.claude/commands/` or `src/io/brodgar/` names a retired demo.

## Out of scope

- **Driving a click from Lua.** Still impossible (ROADMAP candidate, filed 061). The gesture stays the
  maintainer's hand; what changes is that the suite *judges* it instead of asking for a verdict.
- **What `[res=]` matches.** `widget:res()` is untouched; `:picture()` is a second, separate read.
- **A picture the widget composes or paints without holding one** (`Inventory.invsq`, a meter's bar):
  `:picture()` answers `nil` there, and says so on its page.
- **A borrowed native window.** Its input keys keep speaking that window's own coordinates.
- **Controls** (`hafen.ui():button()` and friends): no chrome, no offset, nothing changes.

## Docs impact

Written: `docs/addons/api/ui/custom.md` (the coordinate rule), `api/ui/widget.md` (the read table and
the input keys), `api/ui/selectors.md` (`res` vs `picture`, and the inspector section), `examples.md`
(down to two), `docs/client/widget-input.md` (`Widget.listen` runs **before** propagation, and
`Window.handle` propagates regardless), `docs/client/services.md` (what `Resource.Image` caches and
where the identity is lost), `docs/client/ui-chrome.md` (a row for the content origin an addon sees).

Derived impact set — the fourteen retired names swept across the prose, not the new syntax:

```
grep -rlnE "\b(atlas|bags|cupboard|hello|hogtest|menubutton|netdemo|optionstest|planner|
  stockfilter|tagger|theme|timers|walker)\b" docs/ DOCUMENTATION.md CLAUDE.md specs/ROADMAP.md .claude/commands/
```

→ 40 files (many are the ordinary words *theme*, *timers*, *tagger*). Narrowing to a named addon —
a backticked name or an `addons/<name>` link — gives **37 lines** across `docs/addons/api/**` (20),
`examples.md`, `docs/addons/README.md`, `runtime.md`, `getting-started.md`, four `guides/` pages,
`docs/README.md`, `DOCUMENTATION.md` (the rule at §"a page names a bundled addon"), and
`specs/ROADMAP.md` (the `planner` line). Three more sit in Java doc comments naming `hello`:
`LuaGOut.java` (×2) and `AssetApi.java`.

## Context files

```
src/io/brodgar/addon/WidgetSubs.java              — 1
src/io/brodgar/addon/AddonWidget.java             — 1
src/io/brodgar/addon/UiApi.java                   — 1
src/io/brodgar/addon/LuaWidget.java               — 1, 3
src/io/brodgar/addon/Controls.java                — 3
src/haven/Window.java                             — 1
src/haven/Widget.java                             — 1
src/haven/Resource.java                           — 3
src/haven/Img.java, IButton.java, ICheckBox.java, Avaview.java — 3
docs/addons/api/ui/custom.md                      — 1, 4
docs/addons/api/ui/widget.md                      — 1, 3, 4
docs/addons/api/ui/selectors.md                   — 2, 3, 4
docs/addons/examples.md                           — 2, 4
docs/addons/README.md, runtime.md, getting-started.md, guides/**, docs/README.md — 2
docs/addons/api/**  (the 20 lines the grep above names)                          — 2
docs/client/widget-input.md, ui-chrome.md         — 1
docs/client/services.md, ui-controls.md           — 3
DOCUMENTATION.md, CLAUDE.md, specs/ROADMAP.md, .claude/commands/implement.md     — 2
src/io/brodgar/addon/LuaGOut.java, AssetApi.java  — 2
addons/profiler/main.lua                          — 1 (verification only)
addons/widgetstack/main.lua                       — 4
```
