# Subsystem: widget system (tree, create/destroy seams, introspection, drops)

> `file:line` anchors: tree, creation + destruction paths, introspection, drops, per-frame cost. Lines are indicative —
> the **class + method/field name is the stable anchor**. Max 70 lines.

## Core tree

| What | Where |
|---|---|
| Widget base (tree, `tick`, `draw`, input) | [`Widget`](src/haven/Widget.java) |
| Add/attach child | [`Widget.add`](src/haven/Widget.java:250), `add0` (~:236), `attach` (:220) |
| **Type registry (`@RName` → Factory)** ← replacement seam A | [`Widget.types`](src/haven/Widget.java:51), [`Factory`](src/haven/Widget.java:142), [`initnames`](src/haven/Widget.java:156), [`gettype3`](src/haven/Widget.java:169) |
| UI root / id map / dispatch | [`UI`](src/haven/UI.java): `root` (:48), `widgets`/`rwidgets` (:50), `bind`/`getwidget`/`widgetid` (:343–363) |
| **Server → widget create** | [`UI.NewWidget.run`](src/haven/UI.java:433), `newwidgetp` (:510) — its addon seam was **removed** in 032.2: it only recorded the server type string for the retired descriptor |
| **Server → widget place** ← the one addon seam | [`UI.AddWidget.run`](src/haven/UI.java:470) → `pwdg.addchild(...)` → `onWidgetPlaced(id, wdg)`, i.e. *after* the child is in the tree, so a `Selector` (`[title=]` included) already resolves |
| HUD placement switch (per type: inv/equ/chr/craft/…) | [`GameUI.addchild`](src/haven/GameUI.java:910) |
| Window chrome / CPU-buffered base | [`Window`](src/haven/Window.java:35) · [`SIWidget`](src/haven/SIWidget.java) |
| 2D drawing context | [`GOut`](src/haven/GOut.java) (image/text/rect/line/prect/chcolor) |
| **Image overloads (each a different thing)** | [`image(tex,c)`](src/haven/GOut.java:97) natural · [`(tex,c,sz)`](src/haven/GOut.java:117) **scaled** · [`(tex,c,ul,br)`](src/haven/GOut.java:124) natural + **clipped** · [`(tex,c,ul,br,sz)`](src/haven/GOut.java:140) both · [`rimage`](src/haven/GOut.java:170)/`rimagev`/`rimageh` **tile** |
| **9-slice is a first-class engine concept** | [`IBox`](src/haven/IBox.java:29) is an **interface** — `draw(g, tl, sz)` + six inset queries; [`Images`](src/haven/IBox.java:38) takes eight `Tex`es in the order `(ctl, ctr, cbl, cbr, bl, br, bt, bb)` where **`bl`/`br` are the LEFT/RIGHT edge bars**, not the bottom corners; [`Scaled`](src/haven/IBox.java:89) stretches the edges and **never paints the centre** |
| **Sub-rect texture view** | [`TexSI`](src/haven/TexSI.java:29) — `(parent, ul, br)`, sharing the parent's GPU texture. `Tex` coords are in **pixels** ([`Tex.crender`](src/haven/Tex.java:52)), so slicing an image costs eight small objects and **no** upload |
| Modal mouse capture (drag) | [`UI.grabmouse(Widget)`](src/haven/UI.java:575) / [`UI.grab`](src/haven/UI.java:538) |
| **Pre-hook a widget's own event handling** | [`Widget.listen`](src/haven/Widget.java:916)/[`deafen`](src/haven/Widget.java:922) (a `CopyOnWriteArrayList<EventHandler.Listener<?>>`) + [`Widget.handle(Event)`](src/haven/Widget.java:945) — runs every listener **before** `ev.shandle(this)` (the widget's own `mousedown`/etc.); a listener returning `true` short-circuits both the default handling *and* child propagation |
| **Server → widget destroy** ← lifecycle seam | [`UI.destroy(int)`](src/haven/UI.java:665) (shadow-children first, then a `DstWidget` command) → [`UI.destroy(Widget)`](src/haven/UI.java:622) = [`removeid`](src/haven/UI.java:603) (recursive unbind) **then** `reqdestroy()` |
| Leaving the tree ← the one addon seam | [`Widget.destroy`](src/haven/Widget.java:586) → [`remove`](src/haven/Widget.java:570) (`unlink()`, `parent.cdestroy(this)`, **`parent = null`**) → `ui.removed(this)` → `onWidgetRemoved(this)` (last statement, `// addon:`, 042.1) + `rdispose()` |
| **The override that breaks the sequence** | [`Window.reqdestroy`](src/haven/Window.java:609) — starts a hide *animation* (`animst = "dest"`) instead of removing; also [`Buff`](src/haven/Buff.java:190) |

**Destroy gotcha.** Unbind and unlink are **not** simultaneous: `removeid` runs first, and for a `Window` the
removal is deferred to the end of a fade. So a closing window has `getwidget(id) != wdg` while `hasparent(root)` is
still true and its reads still answer. Any liveness/lifecycle check over widgets needs the **two-branch** test — by
id when server-bound, by reachability otherwise — or it fires a whole animation late (or, on `hasparent` alone for
a client-only widget, not at all sooner). **And the id goes back to the pool**: `removeid` drops both map entries,
after which the server may issue the same number for a different widget — so a widget id is safe to *send* and
unsafe to *store*, because a stored one does not go stale, it silently comes to mean something else (D-138).

**`cdestroy`-override gotcha (why the addon seam is `remove`, not `cdestroy`).** 17 classes in `src/haven`
override `cdestroy` and **9 never call `super.cdestroy`** — `Bufflist`, `ChatUI`, `GameUI` itself, the
`Hidepanel`/`Polities`/`Zergwnd`/craft/`qq` inner classes, `QuestWnd`'s questbox and `WoundWnd`'s woundbox —
which is exactly the set of parents a widget-tree reader cares about (a buff, a quest row, a wound). The
same fact makes `Widget.childseq` (bumped only in `add0` and the base `cdestroy`) unusable as a change
counter for them. `Widget.remove()` itself is overridden nowhere and runs on every removal path (`UI.DstWidget.run`
→ `UI.destroy(Widget)` → `reqdestroy()` → `destroy()` → `remove()`, and any direct client call), so one
`io.brodgar.addon.AddonManager.onWidgetRemoved(this)` tap there — placed *after* `unlink()`/`cdestroy`/
`parent = null`/`ui.removed(this)`, so a handler sees the settled post-removal tree — is override-proof
where a `cdestroy` tap would silently lose 9 of 17 parents (D-179, 042.1). A widget that FADES instead of
unlinking immediately (`Buff.reqdestroy` sets `dest`, `Window.reqdestroy` sets `animst = "dest"`, both
followed by a ~0.35s animation) needs the same `onWidgetRemoved` call from a **second** call site, placed
at the moment the flag is set rather than waiting for the eventual `remove()` — the unlink is the animation
ending, not the thing ending (D-180, 042.2/042.7).

**Listener gotcha.** `listening` is copy-on-write, so a handler may `deafen`/`listen` its OWN widget from
inside `handle(Event)` — the current dispatch finishes against its old snapshot, and the next event sees the
change (`io.brodgar.addon.WidgetSubs` relies on exactly this to swap one engine listener for a fresh one
without racing the dispatch that triggered the swap). `listen`/`deafen` are per-instance, so a native widget
that outlives a Lua-layer reload keeps whatever was registered on it until something explicitly `deafen`s it.
**And it fires BEFORE the widget's own handling**, not after: `Widget.handle(Event)` checks `listening` before
`ev.shandle(this)`, so a listener that reacts to `MouseMoveEvent` on a widget being dragged sees that widget's
position from *before* this event's `mousemove()`/`move()` runs — one event stale if it reads `c` inline. A
consumer that needs the settled result marshals onto the next tick instead of reading synchronously (042.10,
`io.brodgar.addon.Layout`'s drag-anchor re-derive: enqueued the same way as a resize, drained after
`UILoop.Frame.tick`'s `loop.dispatch(ui)` — the input pass — already ran for that frame).

**Resize gotcha, the same shape as the destroy one.** `Widget.resize(Coord)` is overridden in 17 classes;
**15 call `super.resize(sz)`** and reach a tap placed in the base method, but **`Window`** (dispatches straight
to its own `resize2` — deco/chrome sizing) and **`Tabs`** (folds over its own tab list) **do not**, so a seam
placed only in `Widget.resize` silently misses both. `io.brodgar.addon.AddonManager.onWidgetResized` is
therefore called from TWO sites — [`Widget.resize`](src/haven/Widget.java:1534) and
[`Window.resize`](src/haven/Window.java:428) (`Tabs` left as a known, narrow gap: no title/res a selector
would realistically target it by) — the same "a notification a subclass can skip is not a seam" lesson
`Widget.remove`/`cdestroy` already taught (D-179), applied to a second method.

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

**Gotchas.** Draw iterates `child`→`next` (bottom-first), hit-testing `lchild`→`prev` (topmost-first) — opposite,
both correct. `draw` skips `!visible` children; **tick does not** (`TickEvent.visible` goes false for the subtree).
The root is reached by neither seam (its "parent" is `UI`), so anything wrapped per widget misses it unless
`UI.draw`/`UI.dispatch` is handled separately. `TickEvent.shandle` tolerates a widget removing itself mid-tick —
hence the cached `next` — and a widget may equally rebuild **its own** child list in its `tick`: `handle`
(→`shandle`, the widget itself) runs **before** `propagation`, which then re-reads `from.child` fresh. That is
what makes `Window.tick` a legal place to `chdeco` (035).

## Introspection & hit-testing (read-only walk)

| What | Where |
|---|---|
| Child list (tree order) | [`Widget.children()`](src/haven/Widget.java:1742) (returns a `Children` view — copy under `synchronized(ui)`) |
| Server id (`-1` = not server-bound) | [`Widget.wdgid()`](src/haven/Widget.java:560) → [`UI.widgetid`](src/haven/UI.java:588) (looks up `rwidgets`) |
| Pos/size/visibility/parent/class + root box | `Widget.c`, `sz`, `visible()`, `parent`, `getClass().getSimpleName()`, [`rootpos()`](src/haven/Widget.java:496) |
| **Text source (best-effort, one switch)** | [`Label.texts`](src/haven/Label.java:34) (public `String`); button captions / `TextEntry` per type; unknown → none |
| Why an unbound widget cannot act | [`Widget.wdgmsg`](src/haven/Widget.java:741) bubbles to [`UI.wdgmsg`](src/haven/UI.java:667)→[`rawWdgmsg`](src/haven/UI.java:680); **unbound sender (`id<0`) is dropped** ([:681](src/haven/UI.java:681)) |
| **Cursor position (root coords)** | [`UI.mc`](src/haven/UI.java:54) (public `Coord`) |
| **Hit-test walk (the one to mirror)** | [`PointerEvent.propagation`](src/haven/Widget.java:981) — `lchild→prev` (topmost-first), skip `!visible()`, `parent.xlate(child.c,true)`+rect-isect; leaf uses [`checkhit`](src/haven/Widget.java:794) |
| **Coord translation (scroll offsets)** | [`Widget.xlate`](src/haven/Widget.java:482) / [`rootxlate`](src/haven/Widget.java:504) — a hit test must respect these, not a naïve rect test |
| **Parent-relative `c` ⇄ root coords** | [`Widget.parentpos(in)`](src/haven/Widget.java:522) — `parent.xlate(parent.parentpos(in).add(c), true)`, recursing to `in`; `rootpos()` is `parentpos(ui.root)`. Folds every level's `xlate` in, so it is the only correct crossing of a scrolling container. **`c` is relative to the PARENT**: a screen-space answer becomes a `c` by subtracting the parent's own `parentpos(root)`. Prefer `parentpos(u.root)` over `rootpos()` where the `UI` is already in hand — the latter reads the widget's own `ui` field |
| **The root's size, and who changes it** | [`UILoop.Frame.tick`](src/haven/UILoop.java:485) compares `ui.root.sz` with the OS window size **every iteration** and calls `ui.root.resize(sz)` when they differ; [`Widget.resize`](src/haven/Widget.java:1534) then cascades `presize()` to the children and notifies `parent.cresize`. **Has an addon seam since 042.10**: `AddonManager.onWidgetResized(this)`, the last statement of `resize`, after the `Utils.eq` no-op guard and the `presize`/`cresize` cascade — the root resizing is just another resize through this one tap |

## Drop & modifier seams

| What | Where |
|---|---|
| **Drop dispatch (the source)** | [`MenuGrid.mouseup`](src/haven/MenuGrid.java:588) → `DropTarget.dropthing(ui.root, ui.mc, dragging)`; `dragging` = a [`MenuGrid.Pagina`](src/haven/MenuGrid.java:63) |
| Generic drop interface + tree walk | [`DropTarget`](src/haven/DropTarget.java:29) (`dropthing(Coord,Object)`); `Drop` event via `PointerEvent.propagation` ([`Widget`](src/haven/Widget.java:981)) — calls the first `DropTarget` under the cursor |
| Pagina → `{kind,res}` descriptor | [`Pagina.res().name`](src/haven/MenuGrid.java:77) (Loading-guarded); res-vs-id split like the [belt `dropthing`](src/haven/GameUI.java:224) |
| Modifier flags · native empty-slot look (NOT a `.res`) | `ui.modflags()` · [`Inventory.invsq`](src/haven/Inventory.java:34) `TexI` (code-built) + [`sqsz`](src/haven/Inventory.java:33) |

## Per-frame allocation (garbage, not time)

- **A `GOut` per visible child per frame**: [`Widget.draw`](src/haven/Widget.java:827) `reclip(l)` →
  [`GOut.reclip2`](src/haven/GOut.java:437) `new GOut(this)` → ctor `def2d.copy()` ([:52](src/haven/GOut.java:52))
  → [`BufPipe.copy`](src/haven/render/BufPipe.java:62). ≈ 0.5 kB/widget (a `State[]` of `numslots()`, ~60) ⇒
  **~0.4 MB/frame** at ~700 widgets.
- **Nothing 2D is cached across frames**: [`drawp`](src/haven/GOut.java:223) builds `Model`+`VertexArray`+`float[]`
  per call, [`image(BufferedImage)`](src/haven/GOut.java:80) a whole `TexI`, [`atext`](src/haven/GOut.java:212) is
  render→tex→blit→dispose **per call** (a `Label` dodges it by holding its `Text`; immediate-mode cannot).
  Still true of `haven` — **no longer true of addon text**: `LuaGOut` holds the rendered `Text` behind
  `g:text`/`g:atext` in a per-addon bounded LRU (026.1), so that path no longer calls `atext` at all.
- Sums to <1 MB/frame while `UILoop.framealloc` reads **~11 MB** — the bulk is **not** here. `haven/render/gl`
  (`BGL` = one `Command` per GL call; `GLDrawList` = incremental) has **no subsystem file**: pay that toll first.
