# Subsystem: widget input — where an event enters, what it resolves against

> Split out of [widgets.md](widgets.md) (044.5), which keeps the tree, the traversal seams and the read-only
> walk. The **input** half: grabs, propagation, focus, the point queries, popups and drops. Lines are
> indicative — the **class + method/field name is the stable anchor**. Max 70 lines.

## The doors, and the grab that comes first

| What | Where |
|---|---|
| Every entry from the OS | [`UI.mousedown`/`mouseup`/`mousemove`/`mousewheel`](src/haven/UI.java:893) · [`keydown`](src/haven/UI.java:882)/[`keyup`](src/haven/UI.java:888) — each builds one `Event` and calls `dispatch(root, ev)` |
| **A grab is checked BEFORE the tree** | [`UI.dispatch`](src/haven/UI.java:626) walks `grabs` (newest first — [`grab`](src/haven/UI.java:552) does `add(0, g)`) and returns on the first that handles, so a grabbed pointer **never reaches the root traversal** |
| ...and it reaches its owner by `rootpos` | [`PointerGrab`](src/haven/UI.java:572) translates by `ev.c.add(ev.target.rootpos()).sub(wdg.rootpos())` ⇒ **`rootpos()` is the address the client uses to talk to a grabbed widget**; overriding [`parentpos`](src/haven/Widget.java:535) on an ancestor redirects it (`xlate` would too, but that one also positions children in the draw loop) |
| The two grab helpers | [`grabmouse`](src/haven/UI.java:589) (filters to `MouseDown`/`MouseUp`/`MouseWheel`/`CursorQuery` — **not** `TooltipQuery`/`MouseHoverEvent`) · [`grabkeys`](src/haven/UI.java:597) |

## Propagation — three different walks, and they are not interchangeable

| Event | Walk |
|---|---|
| **Pointer (down/up/wheel, the queries)** | [`PointerEvent.propagation`](src/haven/Widget.java:1063) — `lchild→prev` (topmost-first), **skip `!visible()`**, `parent.xlate(child.c,true)` + rect-isect, leaf uses `checkhit`. Respect `xlate`: a naïve rect test is wrong inside a scrolling container |
| **MouseMove** | [`MouseMoveEvent.propagation`](src/haven/Widget.java:1149) — **broadcasts to every visible child with NO rect test**, handing each an out-of-box coordinate. That is how a control un-arms/un-hovers when the pointer leaves it (`IButton.mousemove` recomputes `checkhit` and `redraw()`s) |
| **MouseHover** | [`MouseHoverEvent.propagation`](src/haven/Widget.java:1195) — dispatches to **every** child (invisible included) carrying a per-child `hovering` flag; the first that handles it while `hovering` claims it. ⚠️ its `derive` ctor leaves `hovering` **false**, so anything dispatching a derived hover by hand must set it |
| **Focused key** | see below |

## Focus — bookkeeping, and the delivery chain that is NOT the same thing

| What | Where |
|---|---|
| Bookkeeping | [`setcanfocus`](src/haven/Widget.java:653) (**permanent** — sets `autofocus` too) · [`newfocusable`/`delfocusable`](src/haven/Widget.java:664) (bubble to the nearest `focusctl`) · [`findfocus`](src/haven/Widget.java:685) (last **visible** `autofocus` child) · [`setfocusctl`](src/haven/Widget.java:700)/[`setfocustab`](src/haven/Widget.java:707) |
| Who is a controller | [`RootWidget`](src/haven/RootWidget.java:41) and [`GameUI`](src/haven/GameUI.java:265) call `setfocusctl(true)`; every [`Window`](src/haven/Window.java:100) does via `setfocustab(true)` |
| Taking it | [`Widget.setfocus`](src/haven/Widget.java:623) — a `focusctl` records `w`, **anything else forwards to its parent**, so focus bubbles through non-controllers unchanged. [`Window.mousedown`](src/haven/Window.java:462) propagates FIRST and calls `parent.setfocus(this)` + `raise()` only if a child took the click; [`TextEntry.mousedown`](src/haven/TextEntry.java:203) does its own `parent.setfocus(this)`. [`Window.added`](src/haven/Window.java:119) takes focus on every (re-)attach |
| **Delivering it** | [`FocusedKeyEvent.propagation`](src/haven/Widget.java:1264) — a `focusctl` hands the key to its ONE `focused`; **anything else offers it to every VISIBLE child in turn**. So "who is focused" is a *path* from `ui.root`, not a widget |

**`hasfocus` is not the read you want.** It is only maintained *below* a controller that has focus itself, and
nothing ever sets `ui.root.hasfocus` — so it is `false` on almost everything that is in fact typing. Walk the
path instead (`io.brodgar.addon.LuaWidget.focusPath`, `widget:focused()`).

## The three per-frame queries AT A POINT

| What | Where |
|---|---|
| Hover, once per tick | [`UILoop.Frame.tick`](src/haven/UILoop.java:490) → [`UI.mousehover(ui.mc)`](src/haven/UI.java:911) |
| Tooltip + cursor, once per draw | [`UILoop.display`](src/haven/UILoop.java:318) → [`drawtooltip`](src/haven/UILoop.java:113) → [`UI.tooltip`](src/haven/UI.java:942) · [`drawcursor`](src/haven/UILoop.java:183) → [`UI.getcurs`](src/haven/UI.java:933) |
| The query objects | [`TooltipQuery`](src/haven/Widget.java:1357) (also carries `last`, the widget that answered on the previous frame) · [`CursorQuery`](src/haven/Widget.java:1390). Both keep a `root` reference through `derive`, so a **derived** query writes `ret`/`from` back on the original — which is what lets a query be re-dispatched from somewhere else and still be read at the call site |
| Answering one | [`Widget.tooltip(TooltipQuery)`](src/haven/Widget.java:2028) — children first, then `checkhit` on itself, so the deepest tip-bearing widget wins |

## Popups — three sites, all hard-wired to the flat root

A dropdown's list and a right-click menu are **not** children of the widget they belong to: they add themselves
to `ui.root` and place themselves at their owner's `rootpos()`. Both halves were spelled out at each site —
[`SDropBox.SDropList.add`](src/haven/SDropBox.java:59), [`SListMenu.addat`](src/haven/SListMenu.java:167),
[`BuddyWnd`'s flower menu](src/haven/BuddyWnd.java:427) (which passes `ui.mc` in root coords). Fork: all three
now go through `// addon:` [`Widget.popuproot()`](src/haven/Widget.java:553) + `parentpos(popuproot())`, which
IS `ui.root`/`rootpos()` for any widget that is not standing in the 3D world (044.5). ⚠️ `SDropBox` places its
drop arrow ONCE in its constructor (`adda(..., 1.0, 0.5)`, right-aligned against the built width) and overrides
`resize` nowhere — a resized dropbox leaves its arrow outside its box, clipped and unhittable.

## Drop & modifier seams

| What | Where |
|---|---|
| **Drop dispatch (the source)** | [`MenuGrid.mouseup`](src/haven/MenuGrid.java:588) → `DropTarget.dropthing(ui.root, ui.mc, dragging)`; `dragging` = a [`MenuGrid.Pagina`](src/haven/MenuGrid.java:63) |
| Generic drop interface + tree walk | [`DropTarget`](src/haven/DropTarget.java:29) (`dropthing(Coord,Object)`); `Drop` event via `PointerEvent.propagation` — calls the first `DropTarget` under the cursor |
| **The ITEM drop is a different family** | [`DTarget`](src/haven/DTarget.java:29) (`drop`/`iteminteract`), dispatched by [`ItemDrag.mousedown`](src/haven/ItemDrag.java:60) as `ui.dispatchq(parent, …)` — from the dragged item's OWN parent (the HUD), so it never starts at `ui.root` and never passes `MapView`. `b==1` drops, `b==3` interacts; `Inventory.drop` reads `ul = ev.c.sub(src.doff)`, so the slot is derived from the coordinate the traversal hands it |
| Pagina → `{kind,res}` · modifiers · the native empty slot (NOT a `.res`) | [`Pagina.res().name`](src/haven/MenuGrid.java:77) (Loading-guarded; res-vs-id split like the [belt `dropthing`](src/haven/GameUI.java:224)) · `ui.modflags()` · [`Inventory.invsq`](src/haven/Inventory.java:34) `TexI` (code-built) + [`sqsz`](src/haven/Inventory.java:33) |

**Fork seams (`// addon:`)** — `Widget.popuproot()` + the three popup sites; `UI.tooltip`/`getcurs`/`mousehover`
each ask `AddonManager.surfaceQuery` first, so a widget standing in the 3D world (invisible on the flat UI by
design, and in its own pixels) answers instead of being walked past — both 044.5. `MapView`'s four pointer
entries route a press to a standing panel before the world sees it (044.4, [world-3d.md](world-3d.md)). Both
drop families ask `AddonManager.surfaceDrop` first (044.6) and go on falling through unless a widget
**accepted** it; `DropTarget.drophover`'s highlight is deliberately not routed.
