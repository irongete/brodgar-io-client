# The tick and draw traversals: how a frame reaches a widget

> Covers: the two recursion seams, what an override of `draw` does and does not carry to its children,
> where the screen `GOut` is built, and what a frame allocates. Split out of [widgets.md](widgets.md),
> which keeps the tree and the create/place/destroy seams.

## The two recursion seams

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
| Per-widget decoration seam (fork) | `// addon:` `Widget.addonovs` (null on a widget carrying none) + the call in the child loop placed **after** `wdg.draw(g2)` and **outside** `Fonts.frame(wdg)`, with the root's twin in `UI.draw` after `root.draw(g)`. The `g2` it is handed is already translated and clipped to that child's box |

**Gotchas.** Draw iterates `child`→`next` (bottom-first), hit-testing `lchild`→`prev` (topmost-first) — opposite,
both correct. `draw` skips `!visible` children; **tick does not** (`TickEvent.visible` goes false for the subtree).
The root is reached by neither seam (its "parent" is `UI`), so anything wrapped per widget misses it unless
`UI.draw`/`UI.dispatch` is handled separately. `TickEvent.shandle` tolerates a widget removing itself mid-tick —
hence the cached `next` — and a widget may equally rebuild **its own** child list in its `tick`: `handle`
(→`shandle`, the widget itself) runs **before** `propagation`, which then re-reads `from.child` fresh. That is
what makes `Window.tick` a legal place to `chdeco` (035).

**`super.draw` gotcha — a subclass decides whether its children are painted at all.** `Widget.draw(GOut)` is
`draw(g, true)`, which **is** the child loop and nothing else. So a class that overrides `draw(GOut)` without
calling `super.draw(g)` paints itself and then paints **no child**, and many of the overrides in `src/haven`
do exactly that — `WItem` among them, which is the one that costs the most time to find, since an item icon
is the first widget anyone wants to put something on top of. Nothing else about such a widget shows it: its
children are added, ticked, hit-tested and listed by `children()` as usual, and only the picture is missing.
Adding a child is therefore not a general way to draw over an arbitrary widget.

**The style frame is per widget and nests.** `Fonts.frame(wdg)` is opened around `wdg.draw(g2)` in the loop
above, so it covers that widget's own text *and* its whole subtree while its siblings resolve under whatever
the level above them carries —
and `Fonts.gen()` therefore differs between draw sites **within one frame**, which is what makes it a key
component rather than a generation to compare against. The root's own frame is `UI.draw`'s, since the loop
never reaches the widget it starts from.

## The 2D draw target

| What | Where |
|---|---|
| **Where the screen `GOut` is built** | `UILoop.display`: `basestate()` (a `BufPipe` `.prep`ped with `wnd.fbstate()` — the framebuffer state the toolkit window itself supplies) `.prep` blend + `States.Viewport` + `Ortho2D` + `FrameInfo`, `buf.clear(...)`, then `new GOut(buf, base, wnd.sz())` → `ui.draw(g)` under `synchronized(ui)`. **The 3D scene is inside that traversal** (the MapView is a widget), so this ONE `Render` carries the whole frame in order — see [world-3d.md](world-3d.md) for drawing a subtree into a texture instead |
| Clipping a child's target | `GOut.reclip(c, sz)` (strict) / `reclipl` (loose) — the child loop picks by its own `strict` flag, and a strict box is what cuts a child's drawing off at its own edge |
| 2D drawing itself | `GOut` — image/text/rect/line/prect/chcolor; the overloads and the 9-slice contract are in [widgets.md](widgets.md#core-tree) |

## Per-frame allocation (garbage, not time)

- **A `GOut` per visible child per frame**: `Widget.draw` `reclip(l)` → `GOut.reclip2` `new GOut(this)` →
  ctor `def2d.copy()` → `BufPipe.copy`. ≈ 0.5 kB/widget (a `State[]` of `numslots()`, ~60)
  ⇒ **~0.4 MB/frame** at ~700 widgets.
- **Nothing 2D is cached across frames**: `drawp` builds `Model`+`VertexArray`+`float[]` per call,
  `image(BufferedImage)` a whole `TexI`, `atext` is render→tex→blit→dispose **per call** (a `Label` dodges
  it by holding its `Text`; immediate-mode cannot). True of every `haven` draw site; the fork's own draw
  path holds its rendered `Text` in a bounded LRU instead, and so never reaches `atext`.
- Sums to <1 MB/frame while `UILoop.framealloc` reads **~11 MB** — the bulk is **not** here. `haven/render/gl`
  (`BGL` = one `Command` per GL call; `GLDrawList` = incremental) has **no subsystem file**: pay that toll first.
