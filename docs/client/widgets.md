# The widget system: tree, create and destroy seams, introspection, drops

> Covers: tree, creation + destruction paths, introspection, drops, per-frame cost.

## Core tree

| What | Where |
|---|---|
| Widget base (tree, `tick`, `draw`, input) | `Widget` |
| Add/attach child | `Widget.add`, `add0`, `attach` — `attach(UI)` is what sets the public `Widget.ui` field, recursing the whole subtree, and `add0` calls it only where the child's is still null. So **every widget carries the `UI` whose tree it is in**, and the one to lock while writing a widget is `w.ui` rather than any field naming the session on screen ([multi-session.md](multi-session.md)). It is null on a widget that has never been added to a tree, and `remove()` does **not** take it back |
| **Type registry (`@RName` → Factory)** ← replacement seam A | `Widget.types`, `Factory`, `initnames`, `gettype3` |
| UI root / id map / dispatch | `UI`: `root`, `widgets`/`rwidgets`, `bind`/`getwidget`/`widgetid` |
| **Server → widget create** | `UI.NewWidget.run`, `newwidgetp`  — its addon seam was **removed**: it only recorded the server type string for the retired descriptor |
| **Server → widget place** ← the one addon seam | `UI.AddWidget.run` → `pwdg.addchild(...)` → `onWidgetPlaced(id, wdg)`, i.e. *after* the child is in the tree, so a `Selector` (`[title=]` included) already resolves |
| HUD placement switch (per type: inv/equ/chr/craft/…) | `GameUI.addchild` |
| Window chrome / CPU-buffered base | `Window` · `SIWidget` |
| 2D drawing context | `GOut` (image/text/rect/line/prect/chcolor) |
| **Image overloads (each a different thing)** | `image(tex,c)` natural · `(tex,c,sz)` **scaled** · `(tex,c,ul,br)` natural + **clipped** · `(tex,c,ul,br,sz)` both · `rimage`/`rimagev`/`rimageh` **tile** |
| **9-slice is a first-class engine concept** | `IBox` is an **interface** — `draw(g, tl, sz)` + six inset queries; `Images` takes eight `Tex`es in the order `(ctl, ctr, cbl, cbr, bl, br, bt, bb)` where **`bl`/`br` are the LEFT/RIGHT edge bars**, not the bottom corners; `Scaled` stretches the edges and **never paints the centre** |
| **Sub-rect texture view** | `TexSI` — `(parent, ul, br)`, sharing the parent's GPU texture. `Tex` coords are in **pixels** (`Tex.crender`), so slicing an image costs eight small objects and **no** upload |
| **Input: grabs, propagation, focus, point queries, popups, drops** | [widget-input.md](widget-input.md) — split out of this file |
| **A press that grabs may not test coordinates** | `Button.mousedown` depresses, plays its sfx and grabs for **any** mousedown handed to it; only `mouseup` checks `isect`. ⚠️ A second mousedown while one is outstanding re-enters it and **overwrites `d`, orphaning the first grab forever** — a client-wide interceptor until restart. Anything synthesising a press must release it immediately |
| **The CPU-buffered face is private, and `redraw()` is the only signal** | `SIWidget.surf` (a `Tex`, nulled by `redraw()`, rebuilt on the next `draw`). `Button` calls it on press, on arm/disarm as the pointer crosses, and on `disable` — none of which shows in the widget's place, size, visibility or caption. Fork: `SIWidget.redrawing()` (`// addon:`) |
| **Pre-hook a widget's own event handling** | `Widget.listen`/`deafen` (a `CopyOnWriteArrayList<EventHandler.Listener<?>>`) + `Widget.handle(Event)` — runs every listener **before** `ev.shandle(this)` (the widget's own `mousedown`/etc.); a listener returning `true` short-circuits both the default handling *and* child propagation |
| **Server → widget destroy** ← lifecycle seam | `UI.destroy(int)` (shadow-children first, then a `DstWidget` command) → `UI.destroy(Widget)` = `removeid` (recursive unbind) **then** `reqdestroy()` |
| Leaving the tree ← an addon seam | `Widget.destroy` → `remove` (`unlink()`, `parent.cdestroy(this)`, **`parent = null`**) → `ui.removed(this)` → `onWidgetRemoved(this)` (last statement, `// addon:`) + `rdispose()` |
| **A subtree leaving with it** ← the second addon seam | `Widget.rdispose` — children first, then `dispose()` on itself, then `onWidgetDisposed(this)` (last statement, `// addon:`). `dispose()` is overridden widely, `rdispose()` **nowhere**, so this is the one call every descendant of a destroyed widget makes |
| **The override that breaks the sequence** | `Window.reqdestroy` — starts a hide *animation* (`animst = "dest"`) instead of removing; also `Buff` |
| **Sizing to content** | `Widget.pack()` is `resize(contentsz())`, and `contentsz()` is the max bottom-right (`c.add(sz)`) over the **visible** children — so a leaf packs to `(0, 0)`. `Window` overrides `contentsz()` (see [chrome](ui-chrome.md)). `resize` returns early on an equal box, then cascades `presize()` to the children and calls `parent.cresize(ch)` — **both are empty in `Widget`, and `Window` overrides neither**, so resizing a window's child triggers no relayout of the window |
| **Relative placement** | `Widget.Position` (a `Coord` subclass) with `getpos(name)`/`pos(name)` — the anchors `"ul"`/`"ur"`/`"br"`/`"bl"`/`"mid"` and their content-local `"c…"` twins, `pos` throwing where `getpos` answers `null`. `Position.add`/`sub`/`x`/`y` have `adds`/`subs`/`xs`/`ys` twins that `UI.scale` the argument, which is how the client writes a design-pixel offset. `addhlp`/`addhl` lay a row of children out, vertically centred on the tallest |
| **The UPWARD walk, and its ~30 callers** | `Widget.getparent(Class)` — a plain `w = w.parent` loop. `getparent(GameUI.class)` is how a widget finds the HUD it belongs to: `Inventory.mousewheel` (the shift-wheel bulk transfer) dereferences it **unguarded**, while `GItem`/`WItem.contparent` and `Equipory.drawslots` guard and fall back. Fork: it steps across a standing widget's surface to where that widget was |

**Destroy gotcha — the subtree is not removed.** `destroy()` is `remove()` on itself plus `rdispose()`, which
recurses `dispose()` only: no descendant is unlinked, runs `remove()`, reaches the removal seam or fires
`cdestroy`, while `hasparent(root)` correctly goes false for all of them. `rdispose` is their one shared call.

**Destroy gotcha.** Unbind and unlink are **not** simultaneous: `removeid` runs first, and for a `Window` the
removal is deferred to the end of a fade. So a closing window has `getwidget(id) != wdg` while `hasparent(root)` is
still true and its reads still answer. Any liveness/lifecycle check over widgets needs the **two-branch** test — by
id when server-bound, by reachability otherwise — or it fires a whole animation late (or, on `hasparent` alone for
a client-only widget, not at all sooner). **And the id goes back to the pool**: `removeid` drops both map entries,
after which the server may issue the same number for a different widget — so a widget id is safe to *send* and
unsafe to *store*, because a stored one does not go stale, it silently comes to mean something else.

**...and the death notice arrives BEFORE the death.** `Window.reqdestroy` fires `onWidgetRemoved` as the
fade *starts* (the early signal), while the window is still linked and still full; the seam only
**enqueues**, so consumers run a tick later. In that gap a dying widget passes every structural test —
`hasparent(root)`, `parent == x`, its children — and anything that *acts* on the removal (rather than merely
reporting it) must carry its own "on its way out" flag from the tap, not re-derive it at the drain. By drain
time a closed container's grid and items are already unlinked, so the still-live window reads **0 items**: a
snapshot taken from the removal handler is always empty.

**`cdestroy`-override gotcha (why a removal seam belongs on `remove`, not `cdestroy`).** Most `src/haven`
classes that override `cdestroy` **never call `super.cdestroy`** — `Bufflist`, `ChatUI`, `GameUI` itself,
`GameUI`'s `Hidepanel`/`Polities`/`Zergwnd`/craft/`qq` inner classes, `QuestWnd`'s questbox and
`WoundWnd`'s woundbox —
which is exactly the set of parents a widget-tree reader cares about (a buff, a quest row, a wound). The
same fact makes `Widget.childseq` (bumped only in `add0` and the base `cdestroy`) unusable as a change
counter for them. `Widget.remove()` itself is overridden nowhere and runs on every removal path (`UI.DstWidget.run`
→ `UI.destroy(Widget)` → `reqdestroy()` → `destroy()` → `remove()`, and any direct client call), so one tap
there — placed *after* `unlink()`/`cdestroy`/`parent = null`/`ui.removed(this)`, so it sees the settled
post-removal tree — is override-proof where a `cdestroy` tap silently loses most of those parents. A widget
that FADES instead of unlinking (`Buff.reqdestroy` sets `dest`, `Window.reqdestroy` sets `animst = "dest"`,
both ~0.35 s) needs a **second** call site at the moment the flag is set: the unlink is the animation
ending, not the thing ending.

**Listener gotcha.** `listening` is copy-on-write, so a handler may `deafen`/`listen` its OWN widget from
inside `handle(Event)` — the current dispatch finishes against its old snapshot, and the next event sees the
change — which is what lets a consumer swap one listener for a fresh one without racing the dispatch that
triggered it. `listen`/`deafen` are per-instance, so a native widget
that outlives a Lua-layer reload keeps whatever was registered on it until something explicitly `deafen`s it.
**And it fires BEFORE the widget's own handling**, not after: `Widget.handle(Event)` checks `listening` before
`ev.shandle(this)`, so a listener that reacts to `MouseMoveEvent` on a widget being dragged sees that widget's
position from *before* this event's `mousemove()`/`move()` runs — one event stale if it reads `c` inline. A
consumer that needs the settled result marshals onto the next tick instead of reading synchronously —
draining after `UILoop.Frame.tick`'s `loop.dispatch(ui)`, the input pass, has already run for that frame.

**Resize gotcha, the same shape as the destroy one.** Nearly every `Widget.resize(Coord)` override calls
`super.resize(sz)` and so reaches a tap placed in the base method, but **`Window`** (dispatches straight to
its own `resize2` — deco/chrome sizing) and **`Tabs`** (folds over its own tab list) **do not**, so a seam
placed only in `Widget.resize` silently misses both. A resize consumer is
therefore called from TWO sites — `Widget.resize` and
`Window.resize` (`Tabs` left as a known, narrow gap: no title/res a selector
would realistically target it by) — the same "a notification a subclass can skip is not a seam" lesson
`Widget.remove`/`cdestroy` already taught, applied to a second method.

## Tick and draw traversal (the two recursion seams)

| What | Where |
|---|---|
| **Tick root** | `UI.tick` → `dispatch(root, new Widget.TickEvent(delta))` → `UI.dispatch` → `ev.dispatch(to)` |
| **Tick recursion point (the ONE seam)** | `Widget.Event.dispatch` — `handle(this)` then `propagate(w)`; `TickEvent.dispatch` overrides it to carry `visible` |
| Tick descent / leaf call | `TickEvent.propagation` walks `from.child`→`next`; `shandle` calls `w.tick(ev)` |
| **Draw root** | `UI.draw` → `root.draw(g)` (direct — **not** through a dispatch), then `afterdraws` |
| **Draw recursion point (the ONE seam)** | `Widget.draw(GOut,boolean)` child loop — `xlate`+`reclip(l)`, `CPUProfile.begin(wdg)`, `Fonts.frame(wdg)`, `wdg.draw(g2)` |
| `gtick` (render hand-off) | `UI.gtick` → `GTickEvent` — a **separate** pass, inside the same `utick` phase |
| Frame phases around them | `UILoop.Frame.tick` (`dwait`/`stick`/`utick`), `display` (`draw`) |
| Per-widget cost probe (fork) | `// addon:` `Widget.prof` + `profadd` (`Widget`), the two seams above, and the root bracket in `UI.draw` |

**Gotchas.** Draw iterates `child`→`next` (bottom-first), hit-testing `lchild`→`prev` (topmost-first) — opposite,
both correct. `draw` skips `!visible` children; **tick does not** (`TickEvent.visible` goes false for the subtree).
The root is reached by neither seam (its "parent" is `UI`), so anything wrapped per widget misses it unless
`UI.draw`/`UI.dispatch` is handled separately. `TickEvent.shandle` tolerates a widget removing itself mid-tick —
hence the cached `next` — and a widget may equally rebuild **its own** child list in its `tick`: `handle`
(→`shandle`, the widget itself) runs **before** `propagation`, which then re-reads `from.child` fresh. That is
what makes `Window.tick` a legal place to `chdeco` (035).

## The 2D draw target, re-homing and focus

| What | Where |
|---|---|
| **Where the screen `GOut` is built** | `UILoop.display`: `basestate()` (a `BufPipe` + `FragColor.defcolor` + `DepthBuffer.defdepth`) `.prep` blend + `States.Viewport` + `Ortho2D` + `FrameInfo`, `buf.clear(...)`, then `new GOut(buf, base, wnd.sz())` → `ui.draw(g)` under `synchronized(ui)`. **The 3D scene is inside that traversal** (the MapView is a widget), so this ONE `Render` carries the whole frame in order — see [world-3d.md](world-3d.md) for drawing a subtree into a texture instead |
| **Re-homing a widget** | `unlink()`  + `parent.cdestroy(w)` + `parent = null`, then `neu.add(w, at)`. All public |
| Focus bookkeeping **and delivery** | [widget-input.md](widget-input.md) — including why `hasfocus` is the wrong read |
| What `added()` can do to you | `Window.added` — `parent.setfocus(this)` **and** `initanim()` (a show transition). Both re-run on a re-home, since `add0` calls `added()` again |

**Gotcha — `Widget.remove()` is a DEATH NOTICE, not a detach.** It ends with the `onWidgetRemoved` seam (above),
whose consumers fire a destroy notice, a selector disappearance and the end of a replacement — all by
**identity**, none checking liveness — and it calls `setcanfocus(false)`, which does not come back. So a
`remove(); other.add(w)` pair reports three deaths and un-focuses a widget that is alive one line later. Use the
re-home row above (plus `delfocusable` if `canfocus`); `ui.removed(w)` is skipped on purpose, since it only drops
`UI.Grab`s the still-live subtree should keep.

## Introspection and hit-testing (read-only walk)

| What | Where |
|---|---|
| Child list (tree order) · finding one by type | `Widget.children()` returns a `Children` view of the **direct** children (copy under `synchronized(ui)`), but `Widget.children(Class)` is **recursive over the whole subtree** despite reading like its sibling — its own comment says it "should be renamed to `rchildren`". A lazy `Set` walking `child`/`next`/`parent`, so `ui.root.children(FlowerMenu.class)` finds the one open menu wherever the server parented it, and the walk is per-iteration rather than cached. It also **excludes the receiver** — the iterator opens by stepping to `child` and only tests `cl.isInstance` on what it stepped to — so a `WItem` asked for `children(WItem.class)` answers **empty** and only the container above it answers for that icon. `findchild(Class)` is the deprecated first-hit form of the same walk |
| Server id (`-1` = not server-bound) | `Widget.wdgid()` → `UI.widgetid` (looks up `rwidgets`) |
| Pos/size/visibility/parent/class + root box | `Widget.c`, `sz`, `visible()`, `parent`, `getClass().getSimpleName()`, `rootpos()` |
| **Text source (best-effort, one switch)** | `Label.texts` (public `String`); button captions / `TextEntry` per type; unknown → none |
| Why an unbound widget cannot act | `Widget.wdgmsg` bubbles to `UI.wdgmsg`→`rawWdgmsg`; **unbound sender (`id<0`) is dropped** |
| **Cursor position (root coords)** | `UI.mc` (public `Coord`) |
| **Hit-test walk (the one to mirror)** | `PointerEvent.propagation` — `lchild→prev` (topmost-first), skip `!visible()`, `parent.xlate(child.c,true)`+rect-isect; leaf uses `checkhit` |
| **Coord translation (scroll offsets)** | `Widget.xlate` / `rootxlate` — a hit test must respect these, not a naïve rect test |
| **Parent-relative `c` ⇄ root coords** | `Widget.parentpos(in)` — `parent.xlate(parent.parentpos(in).add(c), true)`, recursing to `in`; `rootpos()` is `parentpos(ui.root)`. Folds every level's `xlate` in, so it is the only correct crossing of a scrolling container. **`c` is relative to the PARENT**: a screen-space answer becomes a `c` by subtracting the parent's own `parentpos(root)`. Prefer `parentpos(u.root)` over `rootpos()` where the `UI` is already in hand — the latter reads the widget's own `ui` field |
| **The root's size, and who changes it** | `UILoop.Frame.tick` compares `ui.root.sz` with the OS window size **every iteration** and calls `ui.root.resize(sz)` when they differ; `Widget.resize` then cascades `presize()` to the children and notifies `parent.cresize`. **Has an addon seam**: `AddonManager.onWidgetResized(this)`, the last statement of `resize`, after the `Utils.eq` no-op guard and the `presize`/`cresize` cascade — the root resizing is just another resize through this one tap |

## Per-frame allocation (garbage, not time)

- **A `GOut` per visible child per frame**: `Widget.draw` `reclip(l)` → `GOut.reclip2` `new GOut(this)` →
  ctor `def2d.copy()` → `BufPipe.copy`. ≈ 0.5 kB/widget (a `State[]` of `numslots()`, ~60)
  ⇒ **~0.4 MB/frame** at ~700 widgets.
- **Nothing 2D is cached across frames**: `drawp` builds `Model`+`VertexArray`+`float[]` per call,
  `image(BufferedImage)` a whole `TexI`, `atext` is render→tex→blit→dispose **per call** (a `Label` dodges
  it by holding its `Text`; immediate-mode cannot).
  Still true of `haven` — **no longer true of addon text**: `LuaGOut` holds the rendered `Text` behind
  `g:text`/`g:atext` in a per-addon bounded LRU, so that path no longer calls `atext` at all.
- Sums to <1 MB/frame while `UILoop.framealloc` reads **~11 MB** — the bulk is **not** here. `haven/render/gl`
  (`BGL` = one `Command` per GL call; `GLDrawList` = incremental) has **no subsystem file**: pay that toll first.
