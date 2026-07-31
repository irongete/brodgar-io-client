# Subsystem: widget system (tree, creation seams, introspection, drops)

> `file:line` anchors for the widget tree, the server→widget creation path, read-only
> introspection and the drop/modifier seams. Line numbers are indicative; the **class +
> method/field name is the stable anchor**. Max 70 lines.

## Core tree

| What | Where |
|---|---|
| Widget base (tree, `tick`, `draw`, input) | [`Widget`](src/haven/Widget.java) |
| Add/attach child | [`Widget.add`](src/haven/Widget.java:250), `add0` (~:236), `attach` (:220) |
| **Type registry (`@RName` → Factory)** ← replacement seam A | [`Widget.types`](src/haven/Widget.java:51), [`Factory`](src/haven/Widget.java:142), [`initnames`](src/haven/Widget.java:156), [`gettype3`](src/haven/Widget.java:169) |
| UI root / id map / dispatch | [`UI`](src/haven/UI.java): `root` (:48), `widgets`/`rwidgets` (:50), `bind`/`getwidget`/`widgetid` (:343–363) |
| **Server → widget create** ← replacement seam A | [`UI.NewWidget.run`](src/haven/UI.java:433), `newwidgetp` (:510) |
| **Server → widget place** ← replacement seam B | [`UI.AddWidget.run`](src/haven/UI.java:470) → `pwdg.addchild(...)` |
| HUD placement switch (per type: inv/equ/chr/craft/…) | [`GameUI.addchild`](src/haven/GameUI.java:910) |
| Window chrome (title/drag/close) | [`Window`](src/haven/Window.java:35) |
| CPU-buffered widget base (complex panels) | [`SIWidget`](src/haven/SIWidget.java) |
| 2D drawing context | [`GOut`](src/haven/GOut.java) (image/text/rect/line/prect/chcolor) |
| Modal mouse capture (drag) | [`UI.grabmouse(Widget)`](src/haven/UI.java:575) / [`UI.grab`](src/haven/UI.java:538) |

## Tick & draw traversal (the two recursion seams)

| What | Where |
|---|---|
| **Tick root** | [`UI.tick`](src/haven/UI.java:371) → `dispatch(root, new Widget.TickEvent(delta))` → [`UI.dispatch`](src/haven/UI.java:617) → `ev.dispatch(to)` |
| **Tick recursion point (the ONE seam)** | [`Widget.Event.dispatch`](src/haven/Widget.java:844) — `handle(this)` then `propagate(w)`; [`TickEvent.dispatch`](src/haven/Widget.java:917) overrides it to carry `visible` |
| Tick descent / leaf call | [`TickEvent.propagation`](src/haven/Widget.java:909) walks `from.child`→`next`; [`shandle`](src/haven/Widget.java:928) calls `w.tick(ev)` |
| **Draw root** | [`UI.draw`](src/haven/UI.java:386) → `root.draw(g)` (direct — **not** through a dispatch), then `afterdraws` |
| **Draw recursion point (the ONE seam)** | [`Widget.draw(GOut,boolean)`](src/haven/Widget.java:771) child loop — `xlate`+`reclip(l)`, `CPUProfile.begin(wdg)`, `Fonts.frame(wdg)`, `wdg.draw(g2)` |
| `gtick` (render hand-off) | [`UI.gtick`](src/haven/UI.java:382) → [`GTickEvent`](src/haven/Widget.java:936) — a **separate** pass, inside the same `utick` phase |
| Frame phases around them | [`UILoop.Frame.tick`](src/haven/UILoop.java:459) (`dwait`/`stick`/`utick`), [`display`](src/haven/UILoop.java:480) (`draw`) |
| Per-widget cost probe (fork) | `// addon:` `Widget.prof` + `profadd` ([`Widget`](src/haven/Widget.java:53)), the two seams above, and the root bracket in `UI.draw` |

**Gotchas.** Draw iterates `child`→`next` (bottom-first) while hit-testing iterates `lchild`→`prev`
(topmost-first) — opposite orders, both correct. `draw` skips `!visible` children; **tick does not** — an
invisible widget is still ticked, with `TickEvent.visible` turned off for its subtree. The root is reached by
neither seam (its "parent" is `UI`), so anything measured or wrapped per widget misses it unless `UI.draw` /
`UI.dispatch` is handled separately. `TickEvent.shandle` tolerates a widget removing itself mid-tick, which is
why the loop caches `next` before dispatching.

## Introspection & hit-testing (read-only walk)

| What | Where |
|---|---|
| Child list (tree order) | [`Widget.children()`](src/haven/Widget.java:1742) (returns a `Children` view — copy under `synchronized(ui)`) |
| Server id (`-1` = not server-bound) | [`Widget.wdgid()`](src/haven/Widget.java:560) → [`UI.widgetid`](src/haven/UI.java:588) (looks up `rwidgets`) |
| Pos / size / visibility / parent / class | `Widget.c`, `Widget.sz`, [`Widget.visible()`](src/haven/Widget.java), `Widget.parent`, `getClass().getSimpleName()` |
| **Text source (best-effort, one switch)** | [`Label.texts`](src/haven/Label.java:34) (public `String`); button captions / `TextEntry` per type; unknown → none |
| Why an unbound widget cannot act | [`Widget.wdgmsg`](src/haven/Widget.java:741) bubbles to [`UI.wdgmsg`](src/haven/UI.java:667)→[`rawWdgmsg`](src/haven/UI.java:680); **unbound sender (`id<0`) is dropped** ([:681](src/haven/UI.java:681)) |
| **Cursor position (root coords)** | [`UI.mc`](src/haven/UI.java:54) (public `Coord`) |
| **Hit-test walk (the one to mirror)** | [`PointerEvent.propagation`](src/haven/Widget.java:981) — `lchild→prev` (topmost-first), skip `!visible()`, `parent.xlate(child.c,true)`+rect-isect; leaf uses [`checkhit`](src/haven/Widget.java:794) |
| **Coord translation (scroll offsets)** | [`Widget.xlate`](src/haven/Widget.java:482) / [`rootxlate`](src/haven/Widget.java:504) — a hit test must respect these, not a naïve rect test |
| Root-space box | [`Widget.rootpos()`](src/haven/Widget.java:496) |

## Drop & modifier seams

| What | Where |
|---|---|
| **Drop dispatch (the source)** | [`MenuGrid.mouseup`](src/haven/MenuGrid.java:588) → `DropTarget.dropthing(ui.root, ui.mc, dragging)`; `dragging` = a [`MenuGrid.Pagina`](src/haven/MenuGrid.java:63) |
| Generic drop interface + tree walk | [`DropTarget`](src/haven/DropTarget.java:29) (`dropthing(Coord,Object)`); `Drop` event via `PointerEvent.propagation` ([`Widget`](src/haven/Widget.java:981)) — calls the first `DropTarget` under the cursor |
| Pagina → `{kind,res}` descriptor | [`Pagina.res().name`](src/haven/MenuGrid.java:77) (Loading-guarded); res-vs-id split like the [belt `dropthing`](src/haven/GameUI.java:224) |
| Modifier-flags source | `ui.modflags()` ([`UI`](src/haven/UI.java)) |
| Native empty-slot look (NOT a `.res`) | [`Inventory.invsq`](src/haven/Inventory.java:34) `TexI` (code-built) + [`Inventory.sqsz`](src/haven/Inventory.java:33) |

## Gotchas

- **Widget creation runs off the UI lock** (Loader) before attach/bind — do tree work in
  `added()`/`attached()`. `Widget.add` links directly (does NOT route through `addchild`).
