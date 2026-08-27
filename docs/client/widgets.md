# The widget system: the tree, and the create and destroy seams

> Covers: tree, creation + destruction paths, re-homing and focus. The per-frame tick and draw
> traversals are [widget-draw.md](widget-draw.md), and the read-only walk over a live tree —
> `children`, the hit test, `xlate`/`parentpos` — is
> [widget-introspection.md](widget-introspection.md).

## Core tree

| What | Where |
|---|---|
| Widget base (tree, `tick`, `draw`, input) | `Widget` |
| Add/attach child | `Widget.add`, `add0`, `attach` — `attach(UI)` is what sets the public `Widget.ui` field, recursing the whole subtree, and `add0` calls it only where the child's is still null. So **every widget carries the `UI` whose tree it is in**, and the one to lock while writing a widget is `w.ui` rather than any field naming the session on screen ([multi-session.md](multi-session.md)). It is null on a widget that has never been added to a tree, and `remove()` does **not** take it back |
| **Type registry (`@RName` → Factory)** ← replacement seam A | `Widget.types`, `Factory`, `initnames`, `gettype3` |
| UI root / id map / dispatch | `UI`: `root`, `widgets`/`rwidgets`, `bind`/`getwidget`/`widgetid` |
| **Server → widget create** | `UI.NewWidget.run`, `newwidgetp`  — it carries no addon seam: the only one it had recorded a server type string nothing reads |
| **Server → widget place** ← an addon seam | `UI.AddWidget.run` → `pwdg.addchild(...)` → `onWidgetPlaced(id, wdg)`. ⚠️ This is the **server's message handler**, so it sees only what the server places, and it fires the instant the *parent* takes the child — which for a subtree built before it is hung is before the child is in any tree |
| **Anything → the tree** ← the universal addon seam | `Widget.add0` → `onWidgetEntered(child)` (last statement, `// addon:`). The one point **every** widget passes, whoever added it — the mirror of `remove()` on the way out |
| Re-order a sibling | `Widget.raise`, `lower` — `unlink()` + `link()`/`linkfirst()`, and **each takes `synchronized(ui)` itself**, unlike the rest of the tree writes, which lean on the caller's. So they are the two a consumer may call holding nothing |
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

**Entry gotcha — `UI.AddWidget` is the server's door, not the tree's.** Widgets the client mints for itself
never pass it: `Inventory.addchild`/`Equipory.addchild` mint a `WItem` per item and `add()` it, `GameUI.updhand`
mints the cursor's `ItemDrag`, `GItem.addchild` mints a `ContentsWindow`. `Widget.add0` is the one call all of
them share, and it is where a "a widget entered the tree" signal has to live. Note the asymmetry that made this
easy to miss: the *removal* signal has always been on `Widget.remove`, which is universal.

**Entry gotcha — `add0` runs with the tree's monitor already held.** `Widget.add` wraps `add0` in
`synchronized(ui)` whenever the parent has a `UI`, on whatever thread reached it — a Loader thread applying
a server update as often as the UI thread — so `added()`, `attached()` and anything hooked at the end of
`add0` all run inside that block. Taking a **second** tree's monitor from there is the ABBA
`UILoop.Frame.tick` is written to avoid ([multi-session.md](multi-session.md)): the frame takes the layer's
and the session's one at a time, and this path holds one already. A consumer that has to reach further than
the tree it was handed records the widget and acts on the tick.

**Item gotcha — `GItem.info()` builds once per arrival, and everything downstream hangs off that build.**
`ItemInfo.buildinfo` throws `Loading` until the tooltip (`uimsg "tt"`, which nulls the cached list) has
arrived *and* the resource that renders it has loaded, so `info()` caches into `GItem.info` and enters its
build block **once per arrival and once per revision, never per draw**. Two consequences. The build runs on
whichever thread asked first — the UI thread drawing the icon, or any reader — holding whatever that thread
holds, so it is subject to the entry gotcha above. And `Fonts.gen()` changing nulls the cache (a `Tip` may
rasterise in its constructor), so a theme change rebuilds the same words.

**Entry gotcha — parented is not the same as up.** A subtree is routinely assembled before it is hung: a chest's
grid is given to its window and the window is added afterwards. Between the two, `hasparent(root)` is false for
everything in it, so anything handed such a widget and reading it gets "not in the tree" — true at that instant
and false a moment later. `add0` fires for the child whether or not the chain reaches the root, so a consumer
that needs a *usable* widget must test `hasparent(ui.root)` itself and wait for the ancestor's own entry, which
passes the same seam.

**`attached()` is NOT a reliable hook.** It looks like the natural "now I am really in the tree" callback, and it
propagates to the subtree — but it is `protected` and overridden in 8 places, and `PView.attached()` (the base of
`MapView`) **does not call `super`**, so the flag is never set for it and no descendant of the 3D view is ever
notified. Anything that must not be skipped goes in `add0`, which nothing overrides.

**Field-name gotcha — a new field on `Widget` can be shadowed by a subclass that already has the name.**
Several subclasses declare public fields of their own with obvious names, and `MapWnd.overlays` (a
`Collection<String>` of map-overlay tags) is one: a field added to `Widget` under that name compiles into a
silent shadow wherever the subclass is in scope, and only a *type* mismatch makes javac say so. Give a field
added to this class a name nothing in `src/haven` already uses, and grep before adding it.

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
`super.resize(sz)` and so reaches a tap placed in the base method, but **`Window`** dispatches straight to
its own `resize2` (deco/chrome sizing) and does **not**, so a seam placed only in `Widget.resize` silently
misses it. A resize consumer is therefore called from TWO sites — `Widget.resize` and `Window.resize` — the
same "a notification a subclass can skip is not a seam" lesson `Widget.remove`/`cdestroy` already taught,
applied to a second method. **`Tabs` is not a `Widget` at all** ([panels](ui-panels.md)): its own
`resize(Coord)` folds over its tab list and every `Tab` in it *is* a widget that reaches the base method, so
what carries no seam is the coordinator — which has no place in the tree for a selector to name it by.

## Re-homing and focus

| What | Where |
|---|---|
| **The tick and draw traversals** | [widget-draw.md](widget-draw.md) — the two recursion seams, where the screen `GOut` is built, and what a frame allocates |
| **Re-homing a widget** | `unlink()`  + `parent.cdestroy(w)` + `parent = null`, then `neu.add(w, at)`. All public |
| Focus bookkeeping **and delivery** | [widget-input.md](widget-input.md) — including why `hasfocus` is the wrong read |
| What `added()` can do to you | `Window.added` — `parent.setfocus(this)` **and** `initanim()` (a show transition). Both re-run on a re-home, since `add0` calls `added()` again |

**Gotcha — `Widget.remove()` is a DEATH NOTICE, not a detach.** It ends with the `onWidgetRemoved` seam (above),
whose consumers fire a destroy notice, a selector disappearance and the end of a replacement — all by
**identity**, none checking liveness — and it calls `setcanfocus(false)`, which does not come back. So a
`remove(); other.add(w)` pair reports three deaths and un-focuses a widget that is alive one line later. Use the
re-home row above (plus `delfocusable` if `canfocus`); `ui.removed(w)` is skipped on purpose, since it only drops
`UI.Grab`s the still-live subtree should keep.
