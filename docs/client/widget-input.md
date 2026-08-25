# Widget input: where an event enters, and what it resolves against

> Split out of [widgets.md](widgets.md), which keeps the tree and the lifecycle seams; the read-only
> walk is [widget-introspection.md](widget-introspection.md). The **input** half: grabs, propagation,
> focus, the point queries, popups and drops. Lines are
> indicative —

## The doors, and the grab that comes first

| What | Where |
|---|---|
| Every entry from the OS | `UI.mousedown`/`mouseup`/`mousemove`/`mousewheel` · `keydown`/`keyup` — each builds one `Event` and calls `dispatch(root, ev)`. **`mousedown` sets `lcc` on EVERY press, before dispatching** (`mc` also tracks moves; `lcc` only presses), so `lcc` is an **exact correlator** for "was anything else clicked in between" — `FlowerMenu.added` places a ring at it, and's click token keys on it. ⚠️ `Coord` is **mutable** (`public int x, y`) and `lcc` starts as the shared `Coord.z`: `equals` is by value, but anything you *store* must be `new Coord(c)` |
| **A grab is checked BEFORE the tree** | `UI.dispatch` walks `grabs` (newest first — `grab` does `add(0, g)`) and returns on the first that handles, so a grabbed pointer **never reaches the root traversal** |
|...and it reaches its owner by `rootpos` | `PointerGrab` translates by `ev.c.add(ev.target.rootpos()).sub(wdg.rootpos())` ⇒ **`rootpos()` is the address the client uses to talk to a grabbed widget**; overriding `parentpos` on an ancestor redirects it (`xlate` would too, but that one also positions children in the draw loop) |
| The two grab helpers | `grabmouse` (filters to `MouseDownEvent`/`MouseUpEvent`/`MouseWheelEvent`/`CursorQuery` — **not** `TooltipQuery`/`MouseHoverEvent`) · `grabkeys` |
| **The listener hook runs BEFORE the widget's own method** | `Widget.handle` walks `listening` (`listen`/`deafen`, a `CopyOnWriteArrayList` of `EventHandler.Listener`) and **returns on the first that answers `true`**, reaching `Event.shandle` — which is what calls `mousedown`/`mouseup`/`mousemove`/`mousewheel` — only when none did |
| ...and a listener that answers `true` ends the dispatch | `Event.dispatch` is `w.handle(this)` then `propagate(w)`: a `true` from `handle` returns straight away, so the widget's own method never runs **and its children are never walked**. That is the whole cancel mechanism; there is no second flag |
| One `propagation` per event, and only one | `Event.propagate` clears the `propagate` flag and caches `phandled`, so a **second** `propagate(w)` on the same event re-walks nothing and simply repeats the first answer. `fpropagate` is the one that re-arms it |

## Propagation — three different walks, and they are not interchangeable

| Event | Walk |
|---|---|
| **Pointer (down/up/wheel, the queries)** | `PointerEvent.propagation` — `lchild→prev` (topmost-first), **skip `!visible()`**, `parent.xlate(child.c,true)` + rect-isect, leaf uses `checkhit`. Respect `xlate`: a naïve rect test is wrong inside a scrolling container |
| **MouseMove** | `MouseMoveEvent.propagation` — **broadcasts to every visible child with NO rect test**, handing each an out-of-box coordinate. That is how a control un-arms/un-hovers when the pointer leaves it (`IButton.mousemove` recomputes `checkhit` and `redraw()`s) |
| **MouseHover** | `MouseHoverEvent.propagation` — dispatches to **every** child (invisible included) carrying a per-child `hovering` flag; the first that handles it while `hovering` claims it. ⚠️ its `derive` ctor leaves `hovering` **false**, so anything dispatching a derived hover by hand must set it |
| **Focused key** | see below |
| **Global key** | `GlobKeyEvent.propagation` — `lchild→prev`, **no rect test and no `visible()` test**, and the first `globtype` that answers `true` stops the walk. A widget's own `globtype` runs **before** its children's, because `Event.dispatch` is `handle` (→ `shandle` → `globtype`) and only then `propagate`. So an invisible widget still eats its key, and hiding one does not free it |
| **Notice** (not an input event, same machinery) | `UI.NoticeEvent.propagation` — `from.child` **forward**, no rect test and no visibility test, first handler wins. Reached from `UI.msg(Notice)`, which is `dispatch(root, new NoticeEvent(msg))`; `shandle` tries `msg.handle(w)` and then `w instanceof UI.Notice.Handler` |

**A notice off the HUD is shown but never logged, which is not the same as lost.** `UI.root` is a
`RootWidget`, which **is** a `UI.Notice.Handler`, so the notice walk always has a handler waiting at the
top. `Notice.Handler.msg(NoticeEvent)` — the interface's own default — tries `ev.propagate(this)` first and
falls back to `msg(Notice)` only when nothing below took it. `GameUI` is what takes it in-world:
`GameUI.msg(UI.Notice)` renders `lastmsg` **and** appends a `ChatUI.Channel.Message` to `syslog`, the chat's
*System* channel. `RootWidget.msg(UI.Notice)` renders `lastmsg` alone. So a tree with no `GameUI` under it —
the login screen, or any `UI` built by `bgui` — still draws the timed root line and still plays the sfx
through `ui.sfxrl`, and simply has **no scrollback**: the line goes with `msgtime` and is recoverable from
nowhere.

**`Window.handle` is the one widget that rewrites the pointer path.** With a `deco`, an ungrabbed
`PointerEvent` passing `checkhit` is answered `true` **whatever happens below** — a window swallows every
press over its own box — and `super.handle(ev)` is what decides whether the children are walked at all:
`Window.mousedown` does the `ev.propagate(this)`, and takes `parent.setfocus(this)` + `raise()` only when a
child answered. With `deco == null` the answer is the propagation's own. So a **listener** on a decorated
`Window` is the one place a walk to its children can be stopped, and it is also why an unhandled press over
a window never reaches what is behind it.

A `Deco` is `z(-100)` and the content sits above it, so the topmost-first `lchild→prev` walk offers the
press to the **content first**: a press inside a `Window`'s content area never reaches the caption drag
(`DragDeco.mousedown` → `Window.drag` → `ui.grabmouse`), and a press on the caption never reaches the
content.

**The action bar's keys are registry bindings (fork).** `GameUI.Belt.globtype` matches `GameUI.kb_belt` and
`kb_beltpg`, twelve each, shared by both bar widgets and defaulting to the number row. `Belt.keylabel`
prints whichever key is bound rather than a baked-in digit, so the corner of a button cannot disagree with
the key that fires it.

**It does not test `visible()`, and that is a decision rather than an omission** — for the page keys as
much as the button keys. The walk above visits invisible widgets, so gating on visibility is one line and
reads as obviously right: a bar you cannot see stops eating two rows of keys, and a page nobody can see is
invisible state that silently changes what every button key presses.

The argument that settles it is **when the gate can fire at all**. This bar is hidden only because something
is standing in for it, and a stand-in draws the page — it reads `curbelt` through `s:actionbar():page()`. So
the one case the gate exists for is the one case where the page *is* on screen, and gating it turns 24
bindings the keybind panel lists into bindings that do nothing, with nothing in the panel to say so. With
nothing standing in, the bar is visible and the gate never fires. A binding that is listed does what it
says; the way to have a row back is to unbind it.

Upstream this was two hardcoded rows and no bindings at all: `NKeyBelt.globtype` claimed `VK_0..VK_9` and
`FKeyBelt.globtype` `VK_F1..VK_F12`, each branching on `ev.code` alone and reading `ev.mods` only for the
Alt bit that turns the page — so `Ctrl+3` did not merely fail to reach anything below, it **fired button 3
and stopped**. Neither row was in the registry, so no panel listed it and nothing could be bound over it
(see [services.md](services.md) on the keybind panel). Matching is exact now, and every one of the 24 keys
is in Options ▸ Keybindings ▸ Action bar.

## Focus — bookkeeping, and the delivery chain that is NOT the same thing

| What | Where |
|---|---|
| Bookkeeping | `setcanfocus` (**permanent** — sets `autofocus` too) · `newfocusable`/`delfocusable` (bubble to the nearest `focusctl`) · `findfocus` (last **visible** `autofocus` child) · `setfocusctl`/`setfocustab` |
| Who is a controller | `RootWidget` and `GameUI` call `setfocusctl(true)`; every `Window` does via `setfocustab(true)` |
| Taking it | `Widget.setfocus` — a `focusctl` records `w`, **anything else forwards to its parent**, so focus bubbles through non-controllers unchanged. `Window.mousedown` propagates FIRST and calls `parent.setfocus(this)` + `raise()` only if a child took the click; `TextEntry.mousedown` does its own `parent.setfocus(this)`. `Window.added` takes focus on every (re-)attach |
| **Delivering it** | `FocusedKeyEvent.propagation` — a `focusctl` hands the key to its ONE `focused`; **anything else offers it to every VISIBLE child in turn**. So "who is focused" is a *path* from `ui.root`, not a widget |

**`hasfocus` is not the read you want.** It is only maintained *below* a controller that has focus itself, and
nothing ever sets `ui.root.hasfocus` — so it is `false` on almost everything that is in fact typing. Walk the
path instead.

## The three per-frame queries AT A POINT

| What | Where |
|---|---|
| Hover, once per tick | `UILoop.Frame.tick` → `UI.mousehover(ui.mc)` |
| Tooltip + cursor, once per draw | `UILoop.display` → `drawtooltip` → `UI.tooltip` · `drawcursor` → `UI.getcurs` |
| The query objects | `TooltipQuery` (also carries `last`, the widget that answered on the previous frame) · `CursorQuery`. Both keep a `root` reference through `derive`, so a **derived** query writes `ret`/`from` back on the original — which is what lets a query be re-dispatched from somewhere else and still be read at the call site |
| Answering one | `Widget.tooltip(TooltipQuery)` — children first, then `checkhit` on itself, so the deepest tip-bearing widget wins |

## Popups — three sites, all hard-wired to the flat root

A dropdown's list and a right-click menu are **not** children of the widget they belong to: they add themselves
to `ui.root` and place themselves at their owner's `rootpos()`. Both halves were spelled out at each site —
`SDropBox.SDropList.add`, `SListMenu.addat`,
`BuddyWnd`'s flower menu (which passes `ui.mc` in root coords). Fork: all three
now go through `// addon:` `Widget.popuproot()` + `parentpos(popuproot())`, which
IS `ui.root`/`rootpos()` for any widget that is not standing in the 3D world. ⚠️ `SDropBox` places its
drop arrow ONCE in its constructor (`adda(..., 1.0, 0.5)`, right-aligned against the built width) and overrides
`resize` nowhere — a resized dropbox leaves its arrow outside its box, clipped and unhittable.

## Drop and modifier seams

| What | Where |
|---|---|
| **Drop dispatch (the source)** | `MenuGrid.mouseup` → `DropTarget.dropthing(ui.root, ui.mc, dragging)`; `dragging` = a `MenuGrid.Pagina` |
| Generic drop interface + tree walk | `DropTarget` (`dropthing(Coord,Object)`); `Drop` event via `PointerEvent.propagation` — calls the first `DropTarget` under the cursor |
| **The ITEM drop is a different family** | `DTarget` (`drop`/`iteminteract`), dispatched by `ItemDrag.mousedown` as `ui.dispatchq(parent, …)` — from the dragged item's OWN parent (the HUD), so it never starts at `ui.root` and never passes `MapView`. `b==1` drops, `b==3` interacts; `Inventory.drop` reads `ul = ev.c.sub(src.doff)`, so the slot is derived from the coordinate the traversal hands it |
| Pagina → `{kind,res}` · modifiers · the native empty slot (NOT a `.res`) | `Pagina.res().name` (Loading-guarded; res-vs-id split like the belt `dropthing`) · `ui.modflags()` · `Inventory.invsq` `TexI` (code-built) + `sqsz` |

**Fork seams (`// addon:`)** — `Widget.popuproot()` + the three popup sites; `UI.tooltip`/`getcurs`/`mousehover`
each ask `AddonManager.surfaceQuery` first, so a widget standing in the 3D world (invisible on the flat UI by
design, and in its own pixels) answers instead of being walked past — both. `MapView`'s four pointer
entries route a press to a standing panel before the world sees it). Both
drop families ask `AddonManager.surfaceDrop` first and go on falling through unless a widget
**accepted** it; `DropTarget.drophover`'s highlight is deliberately not routed.
