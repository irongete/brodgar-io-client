# Reading a widget: introspection and hit-testing

> Covers: the read-only walk over a live tree — what a widget is, where it is, what it says, and
> which one is under a point. The tree itself and its lifecycle seams are
> [widgets.md](widgets.md); input delivery is [widget-input.md](widget-input.md).

| What | Where |
|---|---|
| Child list (tree order) · finding one by type | `Widget.children()` returns a `Children` view of the **direct** children — a live `AbstractSequentialList` whose iterator walks `child`/`next` as it is asked, holding nothing and copying nothing — but `Widget.children(Class)` is **recursive over the whole subtree** despite reading like its sibling — its own comment says it "should be renamed to `rchildren`". A lazy `Set` walking `child`/`next`/`parent`, so `ui.root.children(FlowerMenu.class)` finds the one open menu wherever the server parented it, and the walk is per-iteration rather than cached. It also **excludes the receiver** — the iterator opens by stepping to `child` and only tests `cl.isInstance` on what it stepped to — so a `WItem` asked for `children(WItem.class)` answers **empty** and only the container above it answers for that icon. `findchild(Class)` is the deprecated first-hit form of the same walk |
| Server id (`-1` = not server-bound) | `Widget.wdgid()` → `UI.widgetid` (looks up `rwidgets`) |
| Pos/size/visibility/parent/class + root box | `Widget.c`, `sz`, `visible()`, `parent`, `getClass().getSimpleName()`, `rootpos()` |
| **Text source (best-effort, one switch)** | `Label.texts` (public `String`); button captions / `TextEntry` per type; unknown → none |
| Why an unbound widget cannot act | `Widget.wdgmsg` bubbles to `UI.wdgmsg`→`rawWdgmsg`; **unbound sender (`id<0`) is dropped** |
| **Cursor position (root coords)** | `UI.mc` (public `Coord`) |
| **Hit-test walk (the one to mirror)** | `PointerEvent.propagation` — `lchild→prev` (topmost-first), skip `!visible()`, `parent.xlate(child.c,true)`+rect-isect; leaf uses `checkhit` |
| **Coord translation (scroll offsets)** | `Widget.xlate` / `rootxlate` — a hit test must respect these, not a naïve rect test |
| **Parent-relative `c` ⇄ root coords** | `Widget.parentpos(in)` — `parent.xlate(parent.parentpos(in).add(c), true)`, recursing to `in`; `rootpos()` is `parentpos(ui.root)`. Folds every level's `xlate` in, so it is the only correct crossing of a scrolling container. **`c` is relative to the PARENT**: a screen-space answer becomes a `c` by subtracting the parent's own `parentpos(root)`. Prefer `parentpos(u.root)` over `rootpos()` where the `UI` is already in hand — the latter reads the widget's own `ui` field |
| **`parentpos(in)` when `in` is not an ancestor** | It recurses on `parent` looking for `in` and never tests for the top, so a widget of **another tree** — or one already out of this one — walks off the root into `parent == null` and throws `NullPointerException`. Every caller guards with `hasparent(in)` first, which is why a cross-tree read reads as **inert rather than loud**: ask a widget for its place in a root that is not its own, `hasparent` says no, and the caller hands back nothing at all. The two roots cover the same rectangle (the row below resizes both), so the answer actually wanted from a widget of another tree is `parentpos(thatWidget.ui.root)` — root coordinates compare directly across the two |
| **The root's size, and who changes it** | `UILoop.Frame.tick` compares `ui.root.sz` with the OS window size **every iteration** and calls `ui.root.resize(sz)` when they differ — and does the same for `layer.root` in the block above it, so **every root in the client is the screen**; `Widget.resize` then cascades `presize()` to the children and notifies `parent.cresize`. **Has an addon seam**: `onWidgetResized(this)` (`// addon:`), the last statement of `resize`, after the `Utils.eq` no-op guard and the `presize`/`cresize` cascade — the root resizing is just another resize through this one tap |

⚠️ **Every walk on this page is unguarded, and a race ends one early in silence.** `Widget.next`, `prev`,
`child`, `lchild` and `parent` are plain fields — not `volatile` — and `Widget.unlink()` finishes by setting
`next = null` and `prev = null` on the widget it took out. A walker sitting on that widget then reads a null
`next` and simply **stops**: `children()`, `children(Class)`, `getchild(Class)` and `findchild(Class)` answer
short, or `null`, with nothing thrown and nothing logged, and a caller that treats `null` as "there is none"
acts on it. The mutator is not only the frame's thread — `UI.CommandQueue.execute` defers every server widget
op to a **Loader** thread, and `NewWidget.run`, `AddWidget.run` and `DstWidget.run` each open with
`synchronized(UI.this)` — so a walk whose answer must not be short takes that same monitor, and takes it
itself: `Widget.raise` and `Widget.lower` are the only tree writes that take it for you, and no reader does.
