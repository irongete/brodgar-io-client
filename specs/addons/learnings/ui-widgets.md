# Learnings — Custom UI, overlays & introspection

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **`Window.reqdestroy()` is async** (hide animation) → for synchronous teardown call
  `destroy()`/`remove()` directly.
- **`UI.drawafter` is one-shot** (cleared each frame) → the engine keeps its own persistent overlay
  list and paints from the addon-root widget.
- **`Widget.add(child, coord)` does NOT route through `addchild` — it links the child DIRECTLY** (`add0`:
  `child.parent=this; child.link()`). `Scrollport` only overrides `addchild` (the server-widget path) to
  route into its `cont`, so `scrollport.add(row, c)` would make the row a direct child of the *port*
  (unscrolled). Add scroll content via **`scrollport.cont.add(row, c)`** (`cont` is public; `Scrollcont`
  overrides one-arg `add` to also bump the scrollbar). General rule: `add`/`add0` = client-built tree
  (direct child); `addchild(Widget, Object...)` = the server-attach seam some containers re-route.
- **Panels built in a ctor have `ui == null` until added to their parent** (voice pattern): `add(...)`
  inside `new AddonPanel(...)` runs before the panel is attached to `OptWnd`, so children get `parent` but
  no `ui`; when `OptWnd.add(panel)` runs, `add0` calls `child.attach(ui)` which recurses the whole subtree.
  So building rows/scroll content in the ctor is fine — the tree attaches as a unit later. A panel that
  wants live data with **no teardown obligation** should **poll the facade in `tick()`** (like this one and
  the read-only bits of the voice panel) rather than register a listener — nothing to unregister → no leak.
  Only register+unregister (voice's `VoiceListener`) when you need push, and mirror it in `destroy()`.
- **The input event model is object-based (this fork), not `(Coord, int button)`:** `Widget.mousedown(
  MouseDownEvent ev)` where `ev.c` is the **widget-local** Coord and `ev.b` the button; `MouseUpEvent` same;
  `MouseMoveEvent` has `ev.c` (returns void); `MouseWheelEvent` has `ev.c`, `ev.a` (amount int), `ev.s`
  (double); key events (`KeyDownEvent`/`KeyUpEvent extends KbdEvent`) carry `code`/`mods`/`c` (char). A
  handler **returning `true` consumes** (mouse/wheel/key are boolean; mousemove is void). These nested types
  are inherited by a `Widget` subclass, so `LuaWidget` references `MouseDownEvent` unqualified.
- **Custom windows = composition, not a `LuaWindow` subclass (D-013 one-way, DRY):** a plain `haven.Window`
  already gives the title bar, **drag** (`DefaultDeco` → `Window.drag`/`grabmouse`), and a close button — so
  `hafen.ui.window` builds `new Window(size, title)` and adds ONE `LuaWidget` content child at `Coord.z`
  (the deco offset is handled by `Window.xlate`). All the Lua-forwarding lives in `LuaWidget` alone; the spec's
  separate `LuaWindow` would duplicate it. **Redirect `Window.reqclose(Runnable)`** — its default is
  `wdgmsg("close")`, which a client-side (server-unbound) window can't send (it falls through to `ui.root` and
  is dropped) — to fire `onClose` then destroy.
- **`Window` draws its children every frame via an offscreen buffer** (`Window.draw` → `drawbuf` →
  `super.draw` into `gbuf`, then blits) — so a `LuaWidget` content child's `onDraw` fires every frame with a
  GOut already clipped/translated to its area; drawing at `(0,0)` hits the content top-left. No caching to
  worry about. The window ctor's `sz` is the **content** size; the deco sizes the frame around it.
- **The GOut wrapper must be inert outside `onDraw`:** hold the live `GOut` in a `LuaWidget` field set right
  before the `onDraw` call and nulled in a `finally`; every wrapper method early-returns if it's null. This
  is the spec's "resets state per callback" — an addon that stashes `g` in a timer can't draw into a stale/
  wrong context. Build the wrapper table **once** in the ctor (closures over the widget), not per frame.
- **Owned custom UI teardown (2a):** a new `Addon.widgets` (`List<LuaWidget>`) is the P2 registry, alongside
  `subs`/`timers`. `teardown` (relog/`:reload`/disable/CPU-auto-disable) calls `destroyWidgets` **under
  `synchronized(ui)`** — `LuaWidget.kill()` sets a `dead` flag (silences any late callback) then destroys its
  **root** (the window chrome for a window, else itself), which cascades to children. Track the LuaWidget
  content (not the window) + give it a `root` ref, so one list type covers both `window` and `widget`. A
  bridge-created widget is safe to attach off the UI thread because `Widget.add` locks on `ui`.
- **HUD overlays = re-queue ONE `UI.drawafter` per tick (2b).** `UI.drawafter` is one-shot (`UI.draw` clears
  `afterdraws` after painting), so you can't register-and-forget. But `UI.draw` runs the afterdraws **after
  `root.draw(g)`**, i.e. above the whole HUD — exactly where a HUD overlay must land (a `ui.root` sibling
  added *before* `GameUI` draws *under* it, the 2a asymmetry). So: keep a persistent per-addon overlay list,
  and each `tick` (which precedes draw) `ui.drawafter(oneStaticAfterDraw)` when the list is non-empty; the
  afterdraw paints the whole list with the full-screen root `GOut`. Re-queuing the SAME static instance each
  frame is fine (cleared each draw; one tick per draw → one entry). Don't register each overlay as its own
  drawafter (they'd be one-shot); register one pump that iterates the list.
- **Gob overlays generalise `SpeakerIcon` — and need ZERO `haven` edit (2b).** A world-space overlay is a
  `GAttrib implements RenderTree.Node, PView.Render2D` attached with `gob.setattr(...)`; the render tree pins
  it over the gob in every camera and disposes it with the gob. Its `draw(GOut, Pipe state)` runs from
  `PView.draw → list2d.draw` (`ScreenList`, the 2D pass) — which is **inside the normal `ui.draw` widget
  traversal** (MapView *is* a Widget), so it's on the UI thread, same frame, never racing the tick. Project
  the anchor with `Homo3D.obj2view(new Coord3f(0,0,z), state, Area.sized(g.sz())).round2()` (z≈15 = head
  height, the buddy-name-label anchor). **Unlike `SpeakerIcon`** (which lives in `haven` to *reflect* into
  another gob's `Info` name texture), a generic overlay reads nothing package-private: `GAttrib`'s ctor +
  `gob` field are public, and `Gob.setattr/getattr/delattr`, `PView.Render2D`, `RenderTree.Node` (its
  `added`/`removed` are empty defaults `GAttrib` overrides), `Homo3D.obj2view`, `Area.sized`, `GOut.sz()` are
  all public → the overlay attrib lives in `io.brodgar.addon`, zero-edit.
- **One SHARED gob-overlay attrib per gob serves all addons (2b).** `getattr` keys by exact class, so you
  can't attach a per-addon subclass. Instead attach **one** `LuaGobOverlay` per gob that holds **no** addon
  state; its `draw` calls back into `AddonManager.paintGobOverlays(gob, …)`, which re-checks **every** addon's
  filter for that gob and paints the matches. Two addons overlaying the same gob need one attrib; removing one
  addon's overlay just stops painting it (the idle attrib draws nothing). Detach idle attribs wholesale only
  when **no** addon wants gob overlays (handle `:remove()` / teardown) — reload-safe because a `:reload` keeps
  the same session/OCache (the attribs would otherwise linger on persistent gobs). `detachGobOverlays` mutates
  render slots under `synchronized(ui)` (like `destroyWidgets`) since teardown can run off the UI thread.
- **Dynamic (Lua) filters → THROTTLE the gob sweep; attach-only (2b).** `SpeakerIcon.sweep` runs every frame
  with a cheap Java filter (`isPlayer`). A gob overlay's filter is arbitrary Lua, so evaluating it for all
  ~150+ gobs at 60 fps is wasteful — rate-limit the sweep (default 5 Hz, `-Dhaven.addon.gobsweepsec`). The
  sweep only ATTACHES (a gob that later stops matching keeps an idle attrib; the per-frame draw re-checks the
  filter and paints nothing). The sweep runs in `tick`, so its filter time IS covered by the soft CPU budget
  (D-018 layer 2) — the expensive all-gobs part is watched. **Draw** callbacks (HUD + gob, like 2a `onDraw`)
  run in `ui.draw` after the tick's budget window, so their time escapes the soft budget (same gap as 2a); the
  hard per-call instruction cap still applies. So: expensive filtering = budgeted; drawing = instruction-capped.
- **Extract the `g` wrapper once, share it everywhere (2b, DRY / D-013).** 2a's GOut wrapper was inline in
  `LuaWidget`; 2b needs the identical surface for HUD + gob overlays. Pull it into a standalone
  `io.brodgar.addon.LuaGOut` (`bind(GOut)`/`unbind()`, inert when unbound) and have `LuaWidget`, the HUD pump,
  and `LuaGobOverlay` all use it — one canonical `g:text/atext/rect/frect/line/prect/color` everywhere, no
  drift (the same reasoning as the shared `readEquipment` in 1d-4). Refactoring `LuaWidget` onto it kept
  behaviour identical (the 2a in-game draw was the check; the refactor is pure extraction).
- **4c — a floating dialog must be a `ui.root` child; parenting it to the panel clips it (maintainer feedback).**
  I first parented the consent to the **panel** for free lifecycle (a child of a `hide()`-den panel is neither
  drawn nor clickable — `TickEvent`/pointer propagation — and cascades on destroy). But a panel-child window drags
  in the panel's coord space and **visibly runs outside the AddOns window** — it doesn't behave like a normal
  floating window. Fix: add it to **`ui.root`** (`ui.root.adda(dlg, ui.root.sz.div(2), 0.5, 0.5)` + `dlg.raise()`)
  so it drags freely and sits on top. The lifecycle you gave up (auto-hide/destroy) comes back cheaply from the
  **panel's `tick`**: `if(consent != null && consent.parent != null && !tvisible()) consent.destroy();`. That runs
  even while the panel is hidden — `TickEvent.propagation` ticks the whole tree regardless of `visible` (the
  `AddonRoot`-pump fact), and `OptWnd` is only **hidden**, never destroyed, on close (`GameUI.togglewnd` →
  `wnd.show(!visible())`). Lesson: don't trade the RIGHT window semantics for cheap bookkeeping — top-level is
  correct for a floating dialog; a one-line tick guard is the small price.
- **4c — a client-side `Window` has no server: redirect `reqclose` to `destroy`, and destroying from a button's
  own `action` is safe.** `Window`'s default `reqclose` sends `wdgmsg("close")` (there's no server for an addon-
  built window — the 2a lesson), so the ✕ does nothing unless you `reqclose(this::destroy)`. And a button whose
  `action` destroys its own window is safe: `Button.mouseup` does `d.remove(); redraw();` **then** `click()` last,
  so nothing touches the button after the handler returns (order verified in `haven.Button`).
- **V5b: a filled triangle needed only a `LuaGOut` addition, not a `haven` edit — `GOut.drawp`/`tx` are public.**
  `g:poly(x1,y1,...)` calls the public `GOut.drawp(Model.Mode.TRIANGLE_FAN, verts)` (the same primitive `fellipse`
  uses) and adds the public `GOut.tx` per vertex so it lines up with `g:line`/`g:frect`. Before reaching for a core
  edit to draw something new, check whether the `GOut` primitive is already public — often the draw wrapper can grow
  in our own package.
- **U1 — a widget becomes a drop target with ZERO core edit, because the engine already dispatches drops
  generically.** `MenuGrid.mouseup` calls `DropTarget.dropthing(ui.root, ui.mc, dragging)`, and the resulting
  `DropTarget.Drop` event walks the tree via `PointerEvent.propagation`, calling `dropthing` on the first
  `DropTarget` under the cursor — so just `implements DropTarget` + override `dropthing` on `LuaWidget` is enough.
  The `cc` handed to `dropthing` is **already widget-local** (the event derives child-local coords as it descends,
  exactly like the mouse events), so no manual `rootpos` subtraction is needed. Return `false` for a thing you don't
  handle so the engine keeps looking for another target.
- **The resource-based vs. id-only pagina split is the persistability line, and it's readable off `pag.id`.** A
  resource-based menu action stores its own resource `Indir` as `pag.id` (`id = pag.res` in `MenuGrid.uimsg`), so
  `pag.id instanceof Indir` ⇒ include the (stable, persistable) `res` name; an id-only pagina (`fl&2`) has only a
  per-session server number ⇒ deliver `kind` alone. Resolve the name through `pag.res.get().name` **guarded against
  `Loading`** (res absent until the icon streams in) — the same discipline as every other resource-name read.
- **`g:resource` needs no per-addon cache — engine resources are already globally cached.** `Resource.remote()`
  dedups `.load(name)`, and each `Resource.Image` caches its own `Tex`, so a static name→`Indir` map on `LuaGOut`
  is purely to skip the per-frame lookup, holds only lightweight global refs, and leaks nothing; clearing it on
  `:reload` is for faithfulness (D-039), not correctness. The draw must swallow `Loading` (draw nothing this frame,
  then blit once ready) — the client's own `MenuGrid.draw` idiom — so a not-yet-streamed icon never throws into the
  render thread.
- **W1 — `:type()` must climb past anonymous subclasses.** Hafen builds a huge number of widgets as anonymous
  inner classes (`new TextEntry(...){}`, `new Button(...){}`), and `getClass().getSimpleName()` is `""` for those —
  the first in-game tree dump showed blank types beside real text (`'znoG2Syr'`, `'Inventory'`). `nodeType` walks up
  to the nearest **named** superclass, which is the useful identity for building an adapter. `:text()`'s `instanceof`
  checks already saw through the anonymity (a subclass IS-A `TextEntry`), so only the name needed the fix.
- **W1 — a `WidgetNode` checks liveness with `hasparent(ui.root)`, not an id.** Models detect death by
  `ui.getwidget(id) != wdg`, but that only works for **server-bound** widgets; a `WidgetNode` must also cover
  **client-only** children (no id). The robust, id-free test is reachability: `Widget.remove()` nulls the widget's
  `parent`, so a destroyed widget is no longer reachable from `ui.root` — `w.hasparent(u.root)` (O(depth), the
  `GobRef`-per-access discipline) returns false. On the first stale access, **null the node's `wdg` field** so a
  stashed node can't pin a dead subtree in memory (the D-041 no-pin rule). Because nodes are never registered, there
  is literally nothing to tear down — `:reload` leaks nothing by construction (unlike models/ghosts/widgets).
- **The `:same` identity primitive rides the same opaque-userdata round-trip as `LuaImage`.** Each `node`/`children`/
  `walk` mints a *fresh* handle table and client-only leaves have no `:id()`, so Lua-side `==` and `:id()` can't
  answer "same widget as before?". The handle table carries its `LuaWidgetNode` as an unforgeable opaque userdata
  (KEY field, no metatable — the sandbox omits `luajava`); `:same` resolves the *other* handle back to its node and
  compares the wrapped `Widget` by reference (a stale node is never `:same` as a live one). This is exactly the
  facade-safe pattern `LuaImage`/`LuaMarshal` already use — reuse it whenever a Lua handle needs a hidden Java backref.
- **`node:text()` is the one upstream-volatile method — keep it a single switch.** Text lives in different public
  fields per widget (`Label.texts`, `Button.text.text`, `Window.cap`, `TextEntry.text()`); confining that knowledge
  to `nodeText(Widget)` means upstream churn breaks one method, not the API, and an unknown type just returns nil.
  Note `CheckBox.lbl` is **package-private** → deliberately not read (would need a `haven` accessor; not worth it for
  a best-effort read that's allowed to return nil).
- **W2 — mirror the engine's pointer dispatch for hit-testing; a rect test is a trap.** `hafen.ui.at` must reuse the
  exact walk of [`PointerEvent.propagation`](src/haven/Widget.java:981) — children `lchild→prev` (topmost-first, since
  the last child draws on top), `!visible()` skipped, descend by `from.xlate(child.c,true)` (NOT `child.c` directly —
  a `Scrollport` overrides `xlate` to apply the scroll offset), and `checkhit(c)` at the leaf (a widget can override
  it for a non-rectangular hit area). A naïve "is the cursor in `pos..pos+size`?" gives *wrong* answers inside scrolled
  lists and for custom hit shapes — the whole point of `/framestack` is to report the widget a click would actually
  hit. The engine's own tooltip walk ([`Widget.tooltip`](src/haven/Widget.java:1906)) is the template: `propagate`
  (walk children) first, then `checkhit` on self as the fall-back — so `hitTest` returns the deepest child, else self,
  else null. All backings are public (`lchild`/`prev`/`c`/`sz`/`xlate`/`checkhit`/`rootpos`) → zero core edit.
- **W2 — `node:at` and `hafen.ui.at` share one hitTest by converting to node-local up front.** `hafen.ui.at(x,y)`
  starts the recursion at `ui.root` with the point as-is (root coords == root-local). `node:at(coord)` takes the SAME
  root-coord point and converts it to the node's local frame with [`Widget.rootxlate(c)`](src/haven/Widget.java:504)
  (`c - rootpos()`) before starting the recursion at that node — so a caller passes `hafen.ui.mouse()` to either
  unchanged. `rootpos`/`rootxlate` already thread `xlate` up the chain, matching `hitTest`'s `xlate`-down, so scroll
  offsets stay consistent between the two directions.
- **W2 — the `:same` guard is why W1 shipped `:same`, and it's load-bearing for `OnUpdate` cost.** `widgetstack` reads
  `mouse()`+`at()` every frame but must NOT re-walk the tree + rebuild the window every frame. The guard is
  `if leaf and last and leaf:same(last) then return end` — each `at()` mints a fresh handle and client-only leaves
  have no `:id()`, so reference identity (`:same`) is the *only* reliable "unchanged since last frame?" test. Design
  the cheap per-frame primitive (`at`) alongside the identity primitive (`:same`) that lets callers throttle it.
- **(019.8) `g:text` is the expensive call in an addon window — by ~50x over geometry — because it re-rasterises
  a texture EVERY frame.** [`LuaGOut.drawText`](src/io/brodgar/addon/LuaGOut.java:298) ends in
  render→`tex()`→blit→`dispose()` (the stock path, [`GOut.atext`](src/haven/GOut.java:212), is the same minus the
  markup parse), so a line of text costs an AWT layout + raster + a GL texture create/upload/delete per frame,
  ~0.28 ms. The profiler addon measured its own draw and proved the split: **360 graph primitives = 0.11 ms vs
  ~20 text lines = 5.6 ms**. Budget an immediate-mode window by its *line count*, not its pixel count; the
  client's own UI dodges this entirely because a `Label` builds its `Text` once and keeps it. The fix, if ever
  wanted, is a text HANDLE the addon holds (what `Label` does) rather than an invisible cache — a cache keyed by
  the string misses on every frame whose digits changed, which in a profiler is all of them.
- **(026) The invisible cache WON, and the 019.8 prediction above was wrong on its own terms.** The entry
  above proposed a text HANDLE over "an invisible cache" because a cache "misses on every frame whose digits
  changed". True — and irrelevant: on exactly those volatile strings a handle must re-rasterise too (`t:set`
  costs what `Text.render` costs), so the two TIE on the case that was supposed to decide it, and the cache
  wins everything else by needing no addon edit and no permanent contract. Shipped as a per-addon LRU of the
  rendered `Text` in [`LuaGOut.Cache`](src/io/brodgar/addon/LuaGOut.java), keyed
  `(string, FontHandle identity, Fonts.gen())`, bounded 512 entries / 8 MiB, evicting with `dispose()`.
  Measured with `hello` alone, ~35 s after login: **62844 hits + 7893 misses = 88.8%**, and in-game FPS went
  **130 → 220–240** against a 220 addon-disabled baseline, i.e. the whole enabled-vs-disabled delta was text
  rasterisation. The 88.8% is a *permanently full, permanently evicting* cache (7381 evictions) and that is
  the design working: the misses are one never-before-seen string per frame, which no cache can help.
  Independent re-measure of the thing being cached: a stress toggle drawing 32 fresh strings a frame took 240
  → 80 FPS = **0.26 ms a line**, against 019.8's 0.28 ms measured a different way.
- **(026) A generation counter is only a "clear" signal if it is frame-global — `Fonts.gen()` is not.** The
  plan copied `Label`'s `fontgen` compare (one int, moved ⇒ drop everything). Wrong here: while a per-instance
  font frame is open (F5, `node:setFont`, inside `Widget.draw`'s child loop) `gen()` XORs that override's stamp
  in, so it differs BETWEEN DRAW SITES WITHIN ONE FRAME — a widget inside the frame vs. a HUD overlay outside
  it. Clear-on-move would have cleared the cache on every alternation: strictly worse than no cache. Putting
  `gen()` IN THE KEY costs the same one int per draw, is correct under F5, and still invalidates an
  install/move/reset for free (fresh keys; the stale generation's entries fall out of the LRU). Before treating
  a generation as a clear, check whether it can change *within* a frame.
- **(026) Cap tuning: a cap that can never bind is decoration, and a cap below one frame's working set is
  worse than no cache.** A full cache of ordinary HUD text measured 512 entries / 7.91 MiB = ~15.8 KiB an entry
  (a ~256×16 raster rounded to powers of two), so the provisional 16 MiB byte cap could never be reached before
  the 512-entry one — it bounded nothing. It came down to 8 MiB, where the two caps meet at the measured
  average width, so narrow text is bounded by count and wide text by bytes (which is the point of having two).
  The entry cap did NOT come down to the ~40-line working set: a cap below one frame's distinct strings evicts
  every entry before its next use and pays eviction + dispose ON TOP OF the rasterisation it failed to save.
  Headroom above the working set is what keeps a text-heavy addon off that cliff; the byte cap prices it.
- **(026) Once you cache a `Tex`, you must be its ONLY disposer.** The pre-026 paths disposed every texture the
  same frame (`GOut.atext` and the rich path both), so the fast path had to STOP calling `GOut.atext` — a
  private `render(str, fh)` does that method's own three lines minus the `dispose()`. `TexI.dispose()` drops the
  `ColorTex` and `st()` would silently re-upload it, so a double-free does not crash: it shows up as a slow GPU
  leak. The three drop sites (LRU eviction, `AddonRegistry.teardown`, the `:reload` sweep of the `:lua` REPL,
  which is not an addon and never gets teardown) are the whole ownership surface, and `total.bytes` returning
  to 0 with every addon disabled is the check that proves it.
- **(029.1) Interning the widget tree turned out to be ~19× CHEAPER than the handles it replaced — the plan's
  performance risk was backwards.** The gate was "a full `root():walk()` becomes a map insert per node, the
  per-frame allocation class 026 was written to kill". Measured on a synthetic 2000-widget tree, per node: the
  OLD `nodeHandle` **513 ns** (it minted a `LuaTable` *plus fifteen anonymous closures* for every node it
  touched), a bare interned cache HIT **27 ns**, a cold insert **201 ns** — one full walk went 1.03 ms → 0.05 ms
  steady, 0.40 ms cold. The lesson is not "interning is free": it is that the thing being compared against was
  never cheap. A closure-table handle is one allocation per METHOD; userdata + a metatable built once per addon
  is one allocation per OBJECT. Measure the incumbent before you budget for the replacement.
- **(029.1) `WeakHashMap` only helps if the value cannot reach the key.** The cache is
  `WeakHashMap<Widget, WeakReference<LuaValue>>`, and the inner `WeakReference` is load-bearing: the handle
  userdata holds its `Widget` strongly (that is how a stashed handle keeps reading), so storing the handle
  directly as the map value would make every entry self-referential and immortal — the exact pin D-041 forbids,
  reintroduced by the map chosen to prevent it. The headless check that proves it is not "does the map shrink"
  but "does a destroyed, unreferenced widget become weakly unreachable" (`WeakReference` probe + `System.gc()`
  loop); the entry then goes on the next access, since `WeakHashMap` expunges on use, not on a timer.
- **(029.1) Interning collapses an identity guard to `==`, and `nil == nil` collapses the second branch too.**
  `widgetstack`'s per-frame hover guard was `if leaf and last and leaf:same(last) then return end` followed by a
  separate `if not leaf and not last then return end` for "still hovering nothing". Once entities are interned
  both are one line: `if leaf == last then return end`. `:same()` existed *only* because nothing was interned —
  when a section goes OOP, look for the identity helper it can now delete, not just the reads it gains.
- **(029.2) A weak intern cache forbids per-handle state — derive it or lose it.** OWNED-vs-BORROWED was going
  to be a flag set when `hafen.ui.window{}` minted the entity. It cannot be: the cache is weak on both axes
  (D-064), so the handle is collected the moment Lua drops it and the next `hafen.ui.at(x,y)` mints a fresh
  one — with the flag gone. Derivation was already sitting in the tree: an `AddonWidget` records its owner and
  its `root`, so "is `w` owned by addon A" is "is `w`, or a direct child of `w`, A's live content whose root is
  `w`" — two field reads, correct forever, and per-addon for free (D-065). The general rule: an interned handle
  may hold only what it can re-derive, or `==` stops meaning "same entity".
- **(029.2) Arity-as-verb needs a rule for the dead case, and the answer is "chain silently".** `:pos()` reads,
  `:pos(x,y)` writes — but a stale widget has nothing to move, and a write is not a question, so erroring would
  force every call site to guard `:exists()` first. A write on a stale entity is a **no-op that still returns
  self**; the OWNED check runs only on a live widget, because a dead widget's provenance is no longer knowable
  from the tree (it left it). Reads keep answering `nil`/empty; only `:exists()` always answers.
- **(029.2) The chrome, not the content, is the entity — and `pack()` distinguishes them.** `hafen.ui.window{}`
  builds two widgets: a `haven.Window` and the addon's `AddonWidget` inside it. The Lua entity is interned on
  the **root** (the chrome, or the bare widget when there is no chrome), because that is what the addon
  positions, shows and destroys; `:size(w,h)` still resizes the **content** and then repacks the chrome around
  it. `content != root` is the whole "is this a window?" test — no `isWindow` flag survives.
- **(029.3) Containers are everywhere, and an adapter-per-container would never have found them.** The first
  in-game run of `widget:items()` — one `hafen.ui.root():walk()` filtering on `:type()=="Inventory"` — returned
  four live containers: the backpack (`Inventory#9`), an open `Cupboard` (22 items), the `Belt` window, and the
  **study** inventory. Three of those had no read surface at all, and nobody had asked for them; they came free
  the moment the verb moved onto the container instead of naming one (D-066). When a section is about "the
  player's X", grep the tree for the widget class first — the count is the argument.
- **(029.3) Reading a container never needed the window; `adopt` charged for a capability it did not have.**
  `Widget.children(WItem.class)` is a deep traversal on a *live* widget, so a cupboard's items read fine with its
  window open, visible and clickable — proved in-game, and proved the other way round in the same line
  (`Inventory#9 … visible=false` still answering its 14 items, because a hidden server widget stays bound to its
  id). `hafen.ui.adopt` hid a window purely to hand back a handle; once every widget IS a handle, hiding and
  reading are orthogonal and hiding goes back to being an explicit, restorable act (029.2).
- **(029.3) `hafen.ui.inventory()` is the GRID, not the window — the window is one hop up.** `GameUI.maininv` is
  the `Inventory` inside the `Inventory` `Hidewnd` the client shows on Tab, so `hafen.ui.inventory():hide()` hides
  the grid (exactly what `adopt` used to hide) and `:parent():hide()` hides the whole window (what `replace`
  hides). Both land on the same restore list, so either is undone on `:reload`/disable — but the two rules stay
  deliberately unmerged, and a doc that says "hide the inventory" is ambiguous until it says which.
- **(029.3) A per-widget subscription IS its registration — that is the whole hasSub gate.** Item add/remove is a
  `WItem` create/`cdestroy`, not a `uimsg`, so it can only be polled; but a poll over "every widget" is
  unaffordable. Keying the record on *having a callback* (`Addon.itemWatches`, created by the first
  `:onItemAdded/:onItemRemoved/:onDestroy` and dropped when the last is cleared) makes the unsubscribed case cost
  one `isEmpty()` and needs no separate `:watch()`/`:unwatch()` verb. It also fixes the lifetime question: the
  record holds a strong `Widget` ref, which is fine precisely because an addon asked for it and the next tick that
  finds the widget gone fires `onDestroy` and drops it. Teardown drops the records **without** firing — a
  `:reload` is not a destroy.
- **(029.3) A container's death test is two-branch, like the restore list's.** `getwidget(id) == wdg` covers only
  server-bound widgets, and `:onDestroy` must work on a client-only one too, so the poll tests by id when the
  widget had one at subscribe time and by `hasparent(ui.root)` otherwise — the same shape `UiApi.stillHidable`
  needed in 029.2, for the same reason. Whenever a rule is written against "the server widget", check whether the
  surface can also be handed a client-only one.
- **(029.4) Never call `:items()` from inside a `:walk()` — prune to the container types instead.** `:items()`
  is `children(WItem.class)`, a **deep** traversal, so asking every node "do you hold items?" is O(n²) over a
  2000-widget tree (and every enclosing window double-counts the grid inside it). `hello`'s container scan walks
  once and returns `false` (prune) on `:type() == "Inventory"`/`"Equipory"`, which is both cheap and exact —
  below a grid there is nothing but its own items. Generally: a relation verb that hides a subtree traversal is
  safe per call and quadratic per walk.
- **(030.1) `[title=]` must resolve against the ENCLOSING window, or the most obvious selector never matches.**
  The client wraps bare widgets in titled windows — an `Inventory` inside a `Hidewnd "Inventory"`, a cupboard grid
  inside a `Window "Cupboard"` — and the wrapped widget has **no `cap` of its own**. Matching a widget's own caption
  would make `inventory[title=Cupboard]`, the single most obvious selector a user will write, silently return `nil`
  forever. `Selector.windowTitle` therefore walks `parent` up to the nearest `Window` (counting the widget itself),
  which makes `window[title=X]` and `inventory[title=X]` both work and name *different* widgets of the same window.
  Proved in-game on an open Cupboard: the window, then its grid with 21 items and `grid:parent() == wnd`.
- **(030.1) No window in this client carries a resource — `[res=]` is for items, meters and res-published code.**
  The plan expected `[res=]` to be the language-proof key for a `.res`-published *window* (D-063). It is not: all
  nine open windows (`OptWnd`, `MapWnd`, `Zergwnd`, `Hidewnd`, `CharWnd`, `ContentsWindow`, a plain `Window`, …) are
  client-side Java classes with no resource behind them, so `:res()` is `nil` for every one. Of 625 live widgets
  exactly 69 carry a res: 62 `WItem`s (`gfx/invobjs/…`), the 3 `IMeter`s (`gfx/hud/meter/hp`) and the chat channels
  whose **code ships inside a resource** (`ui/rchan`, `ui/vlg`, `ui/provinces`). So in practice `[title=]` is the
  only key for windows and `[res=]` the correct one for everything item-shaped — the opposite of the spec's promise.
  Check what a key can actually address on a live client before writing it into an acceptance criterion.
- **(030.1) `Resource.classres(cl)` is unusable as a cheap "what res is this widget from?" — it blocks or throws.**
  For a class defined by a `ResClassLoader` it is fine, but for a `get-code` copy carrying `@FromResource` it does
  `remote().loadwait(name, version)` (a **blocking** fetch), and for an ordinary class it throws. Reading the res
  identity of an arbitrary widget — which a `[res=]` matcher does for every node of the tree — must instead test
  `getClass().getClassLoader() instanceof Resource.ResClassLoader` → `getres().name`, else
  `Resource.ResClassLoader.getsource(cl).name()` (the annotation, no load), climbing superclasses because anonymous
  subclasses are the norm. A convenience helper written for one-off use is not automatically a per-node predicate.
- **(030.1) One tree walk that tests each node, never a deep helper per node — and the cost is small.**
  `hafen.ui.all(sel)` walks `child`/`next` once under the `ui` monitor (the same order `Widget.children()` yields)
  and applies a pre-parsed `Selector` per node; the selector string is parsed at the call, never inside the walk.
  Measured in-game: **0.08 ms for `all("*")` over 625 widgets**, which is once-per-event cheap and per-frame
  expensive — hence the docs rule "hold your result", which costs nothing because entities are interned.
- **(030.2) A closing `Window` is unbound from its id BEFORE it leaves the tree — and stays readable for the whole
  fade.** [`UI.destroy(Widget)`](src/haven/UI.java:622) is `removeid(wdg); wdg.reqdestroy();`, and
  [`Window.reqdestroy`](src/haven/Window.java:609) **overrides** the default (`remove()` + `rdispose()`) to start a
  hide *animation* (`animst = "dest"`) — the widget leaves the tree only when the animation ends, several frames
  later. So between the two there is a real interval where `ui.getwidget(id) != wdg` but `wdg.hasparent(ui.root)` is
  still true, `:exists()` answers **true** and `:text()` still returns the caption. This is why the **two-branch**
  death guard (by id when server-bound, by reachability otherwise) is load-bearing and not merely tidy: on the
  reachability branch alone, every window's `disappear`/`onDestroy` would lag by its close animation. Consequence
  for any lifecycle event over widgets: it fires when the widget stops being **real**, not when it stops being
  **drawn**, and the entity handed to the handler may still answer its reads — document it as a key to match, never
  as a last chance to read (a client-only widget, with no id, really is gone by then).
- **(030.2) Split a matcher into the half that can change and the half that cannot.** A widget's role and class come
  from its Java type and are fixed for its life; a caption arrives by `uimsg` and a resource resolves async, so both
  can be absent at placement and present a tick later. `Selector.matchesStructure` + `late()` is what lets the
  placement seam say "this can never match" versus "this does not match *yet*" — only the latter is queued for a
  bounded re-check (20 ticks), so a `.res`/late-captioned window fires exactly once instead of never. Without that
  split the choice is a permanent re-check list or a silently missed match.
- **(030.3) A window's chrome is a CHILD widget, so hovering the frame never gives you the window.**
  `hafen.ui.at()` over a Cupboard's border/title bar resolves to its **decoration** (`@DefaultDeco`, role `nil`),
  not to the `Window` — the same fact that makes `window.title` classify nothing (a caption is drawn by
  `Window.Deco`, 030.1) seen from the hit-testing side. `[title=]` still resolves *through* it, because the rule is
  "the nearest enclosing `Window`", so the deco is perfectly addressable as `@DefaultDeco[title=Cupboard]` — but any
  tool that turns a hover into "the widget you meant" must expect the deco and offer the parent hop. Verified
  in-game: the selector inspector reports exactly that, and the offered selector round-trips.
- **(031.1) `MenuCheckBox.setgkey` means the key and the menu button are ONE click — there is no keyboard path to
  intercept separately.** `setgkey(KeyBinding)` stores `kb_gkey`; a matching `GlobKeyEvent` reaches the widget's
  own `gkeytype`, which `ACheckBox` overrides to `click()` ([ACheckBox:63](src/haven/ACheckBox.java:63)). So Tab
  and the mouse both run the checkbox's `click` closure and land in the private
  [`GameUI.togglewnd`](src/haven/GameUI.java:1482) — seven call sites, one method. Consuming the key would leave
  the button working and vice versa; the seam is the method, never the binding. Its tick is the equally private
  `wndstate`, read through [`ACheckBox.state`](src/haven/ACheckBox.java:37), a `Supplier<Boolean>` called from
  `draw` — i.e. **per frame, on six checkboxes**, so anything hung there must be allocation-free.
- **(031.1) The inventory/equipment wrappers are `Hidewnd`s created hidden**
  ([GameUI:957](src/haven/GameUI.java:957)) and their close only *hides* them, so "put the window back" is never
  a blind `show()` — that hands the user a window they never opened. Replaying the visibility actually recorded
  at hide time is the only correct restore, which is exactly what 029's `Hidden.origVisible` already stored.
  Note also that `replace` does **not** use that list: it hides via its own `LuaModel.hideTarget` record, so a
  rule written over `Addon.hiddenNative` covers `w:hide()` and *not* `replace` until the two are joined.
- **(031.2) That last point is now REFUTED in its second half — `origVisible` was the wrong thing to remember.**
  "Replay the visibility recorded at hide time" is only right while nothing stands in for the window. Once the
  toggle drives a **view** (D-069 → D-070), the question teardown must answer is *what was the user seeing*, not
  *what was the widget*: a custom inventory that is open, on an addon that is then disabled, must leave the STOCK
  inventory open — even though the wrapper it hid was hidden at the time. The one rule
  (`view != null && view.visible()`) collapses to the same answer in the canonical unbound case, which is why the
  031.1 record looked correct: hiding the `Hidewnd` wrapper reads `origVisible == false` and the rule reads
  `false`. **A remembered state that agrees with a derived one on every case you have tested is not evidence the
  state is needed** — delete it and derive, or it becomes the second thing that can drift. (The first half of the
  031.1 note stands: it is still never a blind `show()`.) `replace` was joined to `Addon.hiddenNative` in the same
  change, so there is one record per window instead of two.
- **(031.2) `Window.visible()` is animation-aware, and that is what makes the menu tick honest.**
  `Window` overrides it to `visible && ((animst == null) || (animst == "show"))`
  ([Window:556](src/haven/Window.java:556)) — `hide()` does NOT clear the `visible` field, it starts a fade and
  lets the tick call `super.hide()` when the anim finishes. So a window that is fading out already reads
  `visible() == false`, which is why `wndstate` can read a view's `visible()` directly with no debounce and no
  bookkeeping boolean. The flip side is the 030 corpse rule from the other direction: *drawn* and *real* are
  different axes, and `visible()` answers the first.
- **(031.2) A private helper you cannot call is sometimes cheaper to re-derive than to expose.**
  `togglewnd` finishes with `raise`/`fitwdg`/`setfocus`, and `GameUI.fitwdg` is private. Widening it would have
  been a third core edit on a feature that promised two one-liners; the clamp is four lines of arithmetic over
  public `Widget.c`/`sz`/`parent` (`fitmarg = UI.scale(100)`), so it was re-derived in `UiApi.fitView` — and
  applied against the *view's own parent*, which is the more correct frame anyway, since an addon window may hang
  off `ui.root` rather than the HUD. Re-derive when the logic is small, stable and arithmetic; expose when it is
  behaviour you would be forking.
- **(032.1) `hafen.ui.window{}` silently ignores unknown opts keys — which reads exactly like a broken feature.**
  The size is `size = {w, h}` and the draw callback is `onDraw`; a plausible-looking `{title=…, w=300, h=200,
  draw=…}` produces a **default-sized 200x140 window that never draws**, with no error anywhere. It cost a full
  round-trip with the maintainer during 032.1 verification — the empty window was blamed on the new verb, which was
  working perfectly. `AddonWidget`'s `fn(opts, key)` returns `null` for an absent *or* misnamed callback and
  `newUi`'s `optint` falls back to the default, so nothing has anything to complain about. When handing anyone a
  one-off `:lua` line, copy the opts from `docs/addons/api/ui.md` rather than typing them from memory.
- **(035.1) `Window.deco` is a swappable CHILD, and `chdeco` is the whole seam.** `public Deco deco` +
  `public void chdeco(Deco)` ([Window.java:131](../../../src/haven/Window.java:131)); `Deco extends Widget` is
  abstract with `iresize(Coord)`/`contarea()`, `DragDeco` adds the caption drag, and `DefaultDeco` — the stock
  one — owns `drawbg`, `drawframe`, the close `IButton`, the sizer, `checkhit` and **the geometry**. `chdeco`
  reads the OLD deco's `contarea()` first, `reqdestroy()`s it, adds the new one, `resize2(psz)`, then shifts the
  window's own `c` by the content-area delta — so an equal-geometry swap moves and resizes **nothing**
  (asserted: `sz`, `c`, `ca().ul`, `ca().sz()` all unchanged across install and restore). Corollaries that cost
  time: the displaced deco is **destroyed**, so "put the same object back" is not available (D-078); `makedeco()`
  is `protected`, so only `haven`-package code can call it, but `new Window.DefaultDeco(lg).dragsize(ds)` is
  public and rebuilds exactly what it would have; and `uimsg "dhide"` already does `chdeco(makedeco())`, which is
  the engine's own proof the seam is live-swappable.
- **(035.1) Not every window's deco is `DefaultDeco`, and the difference is invisible from `instanceof`.**
  `MapWnd.makedeco()` returns `new DefaultDeco(true).dragsize(true)` (still exactly that class, so it IS
  skinnable, and `dragsize` must be carried across a swap or the resize grip vanishes); `MapWnd.compact(true)`
  sets the deco to **null**; `GItem.ContentsWindow` swaps between a `HoverDeco` and a `DefaultDeco` on its own,
  per state, so anything that re-skins per frame must expect its deco to change underneath it. Test
  `getClass() == DefaultDeco.class`, never `instanceof` — a subclass built itself for a reason.
- **(035.1) A widget may change its OWN child list inside its `tick`.** The tick traversal is
  `UI.tick` → `dispatch(root, TickEvent)` → `Event.dispatch` → `w.handle(ev)` → **`shandle` (the widget itself)
  BEFORE `propagation` (its children)**, and `propagation` re-reads `from.child` fresh while capturing each
  `next` before dispatching. So a `chdeco` inside `Window.tick` is safe in both directions: the old deco is
  unlinked before the child loop starts, the new one simply ticks this frame. `TickEvent.propagation` also
  ignores `visible` entirely — **hidden windows still tick**, which is what lets a restore reach a window nobody
  can see.
- **(035.1) The frame band is 18x30 logical px, and an addon's border image is not DPI-scaled.** A window's
  content starts at `Window.tlm = UI.scale(18,30)` with `brm = UI.scale(13,22)` at the far end; a 9-slice from
  `hafen.asset` draws at its image's own pixel size, like every other addon image. So a themed border reads
  *thinner* than the stock chrome it replaced, and anything it does not cover is the offscreen buffer
  `Window.draw` clears to `FColor.BLACK_T` — a transparent-black band, not a background (D-079).
- **(035.1) `IBox` is an interface and `TexSI` makes 9-slice free.** `IBox.draw(g, tl, sz)` with
  `IBox.Images`/`Scaled` taking **eight** `Tex`es in the order `(ctl, ctr, cbl, cbr, bl, br, bt, bb)` — where
  `bl`/`br` are the LEFT and RIGHT edge bars, not the bottom corners (those are `cbl`/`cbr`), an off-by-one
  waiting to happen. `Scaled` stretches the edges and never paints the centre. Slicing one loaded image into
  eight `TexSI` sub-rect views shares the parent's single GPU texture, so a border uploads nothing and owns
  nothing to dispose — much better than eight `TexI`s from `getSubimage`, which would leak per `:reload`.
- **(035.2) `Window`'s geometry flows ONE way, and `iresize` is the only place it turns around.**
  `Window.resize(sz)`/`resize2` feed the deco the **content** size; the deco decides the window's outer size
  (`this.sz = deco.sz`) and where content starts (`contarea()`). So padding a window makes it **bigger** — the
  reverse (treating `isz` as the outer size) silently shrinks every window to its content, which is exactly what
  the falsification produced: 8 red. `DefaultDeco.iresize` is `content + mrgn*2 + tlm + brm`, i.e. an inner
  margin and outer frame insets, and a themed deco only has to substitute its own numbers into it (D-080).
- **(035.2) Re-laying out a live deco means copying what `chdeco` does, not calling `resize` and hoping.**
  `chdeco` reads the old `contarea()` **first**, re-runs the layout, then absorbs the difference into the
  window's own `c` — which is why an install anchors the *content* rather than the window's corner. A repack
  that skips that last line moves every window by the pad whenever the rule changes, and the drift is invisible
  until you assert the position (3 red). `Window.c` is public, so a deco can do it from outside.
- **(035.2) A window's chrome resolves through the WINDOW, so any cascade level that names the window reaches
  its frame.** `Fonts.styleFor("window.frame", wnd)` folds `wnd`'s own tree rule / `widget:skin` over the site
  stack, so `w:skin{border=…}` themes exactly one window — and 035.1's shipped docs table, which said a tree key
  and `widget:skin` were **inert** for `bg`/`border`, was wrong on the day it shipped. Corrected in
  `api/ui.md#what-each-key-accepts`. What *is* inert is a rule matching anything that is not a window, including
  a widget **inside** one: `styleFor` asks about that one widget, and there is no ancestor walk.

- **(035.3) `Frame.around` does NOT put the content inside the frame — the frame is a SIBLING drawn after it.**
  `Frame.around(parent, area)` does `parent.add(new Frame(...))`: the widgets being framed stay children of
  `parent`, and the frame is appended *after* them, so it draws **on top** of the content it appears to
  contain. `Frame.with(child)` is the opposite (`ret.add(child)` — a real child), and `Frame.addin` is a third
  shape (resizes the child, then adds it to `parent`). The stock 9-slice never noticed the difference, because
  an `IBox` paints only its four edges and its middle is transparent — but anything that FILLS the interior
  (035.3's `bg`) buries the rows on an `around` frame and not on a `with` one. Both spellings are in constant
  use, often in the same window (`SAttrWnd` uses `around` for its attribute list; `GameUI` uses `with` for the
  portrait). **Never assume a `Frame`'s visual children are its tree children** — and note that "does it have
  children" is a *real* distinction that is still the wrong thing to branch on, because it is an accident of
  the call site rather than anything an addon author can predict (D-083).
- **(035.3) `IBox` is an interface, and its six measuring methods are read at CONSTRUCTION.**
  `btloff`/`ctloff`/`bbroff`/`cbroff`/`bisz`/`cisz` feed `Frame`'s constructor (`sz.add(box.bisz())`),
  `getpos`, `xlate`, `checkhit` and `addin`, and `SListMenu`'s own layout — all at build time. So a box swapped
  in later can paint differently but must **measure identically**, or the frame moves while its contents do
  not (D-084). This is the exact mirror of `Window.Deco`, where `iresize`/`contarea` are re-run on demand and a
  replacement deco therefore *can* change the geometry (D-080).
- **(035.3) Two `Frame` subclasses paint their own frame and must be routed separately**: `ProxyFrame` and
  `Partyview.MemberView` both override `drawframe` to `g.chcolor(color)` + `box.draw(...)` (a server-set tint
  and the party colour). `MapWnd.ViewFrame` overrides `draw` but calls `super.draw`, so it inherits whatever
  the base does. A grep for `IBox` alone misses the first two — grep for the field name (`box.draw`) as well.

- **(036.1) A native window that PACKS AROUND ITS CONTENT cannot be resized from outside — and it is not an
  error, it is the client winning the race inside your own call.** `GameUI` builds the inventory's wrapper as an
  anonymous `Hidewnd` with `cresize(ch) { pack(); }` (`GameUI.addchild`, `place == "inv"`), and
  `Widget.resize` ends with `parent.cresize(this)` — so `Window.resize2`'s `deco.iresize(sz)` makes the **deco**
  call its parent back, which packs the window to `contentsz()` **before `w.resize(to)` returns**. Read
  `:size()` back and it never moved. `equwnd` has no such override and resizes fine. Generalisation: this is
  D-084's panel rule one level up — *a size applies where the surface can re-lay itself out* — so `pos` always
  lands while `size` does not overrule a window that owns its own. Do not "fix" it; document it as inert.
- **(036.1) A window's size ARGUMENT is not its size.** `Window.resize(sz)` takes the **content** size and
  derives the outer box from the deco (`this.sz = deco.sz`), so `:size()` reads the outer box while
  `:size(w,h)` sets the content one. Anything that must restore a window exactly has to record `csz()`, not
  `sz` — record the *argument that reproduces the state*, not the state, and the undo is the write's exact
  inverse for a window and a bare widget alike.
- **(036.1) Moving `c` is all hit-testing needs.** `hafen.ui.at()` mirrors the engine's own pointer dispatch off
  `Widget.c`, so a real move is found at its new place with no other change — which is the concrete reason a
  draw-time offset was refused: it would have drawn the widget where no click could reach it.

- **(036.2) A cascade that WRITES needs its own lock order, and the rule is: never hold the sheet's lock while
  touching the tree.** The draw pass has established `ui` → `Sheet.class` (the per-widget fold is resolved under
  the UI monitor), so the layout sweep — which walks the tree and calls `move`/`resize` — must take `ui` and
  *then* the sheet's, i.e. it runs **outside** `Sheet.skin`/`forget`'s own `synchronized` block. A sweep called
  from inside `rulesChanged()` would have been the one path in the client able to invert that order. Generalise:
  when a resolver gains a side effect, the side effect goes at the call site, not inside the resolution.
- **(036.2) Applying a sheet SYNCHRONOUSLY is worth the tree walk.** The alternative — mark dirty, sweep on the
  next tick — costs nothing at runtime but makes every suite (and every addon) that reads a value back after
  `hafen.ui.skin{}` need a timer, which is exactly the staging that 035.3 spent two rounds deleting. A rule
  change is rare and the walk is ~600 cache-hit folds; a rule that has moved a window has moved it by the time
  `skin{}` returns, like one that recoloured it.
- **(036.2) A per-widget record that a RULE can mint must be pruned, not just torn down.** The verbs made one
  record per widget an addon touched by hand — a handful. A rule makes one per *matching* window, forever, each
  holding a strong reference to a widget that will close: the same leak F5 recorded for styled widgets, arriving
  by a different door. The tick prunes records whose widget has left the tree (the `stillHidable`/`matchLive`
  two-branch test again), gated on the same volatile the whole layer already reads.

- **(036.3) `Widget.parentpos(in)` is the conversion every anchor rests on**, and `rootpos()` is just
  `parentpos(ui.root)` — which reads the widget's own `ui` field, one more thing to be null on a widget that is
  halfway anywhere, so a layer that already holds the `UI` should call `parentpos(u.root)` itself (guarded by
  `hasparent`). It folds each level's `xlate` in, so it is also the only correct way to cross a scrolling
  container. A widget's `c` is **parent-relative**: converting a screen-space answer back means subtracting the
  parent's own root position, and forgetting that reddens everything by the HUD's offset at once.
- **(036.3) `UI.scalef` is `static final`, loaded once at class init** (`Utils.getprefd("uiscale", …)`), and the
  Options slider says *requires restart* — so **nothing can observe a UI-scale change at runtime**. A spec that
  asks for "assert it survives a rescale" is asking for something this client cannot do; what a rescale actually
  changes is the sizes a layout reads, and *that* is drivable (resize the target, resize the root in a probe).
- **(036.3) The root is resized from the frame loop, not by an event**: `UILoop` compares `ui.root.sz` with the
  OS window size every iteration and calls `ui.root.resize(sz)` when they differ; `Widget.resize` then cascades
  `presize()` to the children. There is no hook to subscribe to — which is why anything deriving from the screen's
  size polls it.

- **(036.4) A layout in a theme FILE needs no adapter, and that is a property of handles.** `theme`'s JSON→sheet
  mapper exists for exactly two values — a font's `face` and an image path — because those are **handles**;
  a colour array, a slice's insets, a `pad`, an anchor's corner and offset, a `pos`/`size` pair are all already
  the sheet's own shapes and travel verbatim. So the chrome half of a theme cost three lines of Lua and the
  layout half cost none: `hafen.json.parse(text)` **is** the sheet. The rule to carry: when adding a property,
  ask whether it can be spelt in JSON — if it can, the file support is free, and if it cannot, it is because the
  value is a handle to something the client owns.
- **(036.4) An anchor HOLDS and a plain `pos` LETS GO — the observable difference an addon's layout profile
  rests on.** Anchored widgets sit in `Layout.derived` and are re-derived every tick, so dragging one snaps it
  back; a `pos` is written once and never polled, so the user can drag it afterwards. `theme`'s `:theme save`
  turns each anchor into a pin (`pos` at where the window is now, kept in `hafen.store`) and that alone makes
  the HUD draggable again — no mode, no flag, just the other spelling of the same property. A rule saying both
  is refused, so the pin must *replace* the anchor in the rule, not sit beside it (dropping that one line makes
  the whole sheet throw and the theme install nothing).
- **(038.3) An event that mirrors a collapsed read must be edge-triggered on the KEY, and the seam pays a count
  for it.** `Gob.ols` is the engine's list, but `gob:overlay()` collapses the game's overlays by resource name
  (D-101), so hooking `addol`/`remove` naively would fire an add for the second `foo` while `gob:overlay("foo")`
  had been answering all along. Each seam therefore counts the resource *after* the engine's own mutation and
  fires only on 1 (first) or 0 (last) — D-105. Live figures for the cost: a normal session put **119–559**
  native overlay events through the queue between two runs of one suite (mostly `sfx/terobjs/tick` and
  `sfx/tiles/horse/hstep`), which is why the whole path sits behind a "does anybody subscribe" volatile read.
- **(038.3) The console (`:lua`) is a different addon, which makes it the in-game proof of an owner-scoped
  event.** Asserting "we are NOT told about another addon's overlay" is unassertable from inside one suite. The
  `[manual]` is a COMMAND: `:lua hafen.player():gob():overlay("cross-lua", {text = "x"})`, then re-run the suite
  and watch its foreign-event counter stay 0 — a real cross-addon check in one line, and the same trick 038.1
  used for the per-addon key partition.
- **(039.3) `FollowMoving.off` is `volatile` and can be written LIVE**, so an anchored world overlay moves in
  place rather than needing a re-attach. 038.2 left it "set once, at create" and its own comment said so; the
  field was already the right shape and only the two writers were missing — the entity's desired `followOff`
  (read by a create that has not published yet) and the attrib on the published gob, which the placement pass
  picks up on the next frame with no lock (`RenderApi.overlayOffset`). Write BOTH: a ghost's create is
  deferred, so writing only the live attrib loses the offset on a visual that streams in a beat later.
- **(039.3) `RenderApi.overlayEntity` takes a spec TABLE, so a record-driven builder synthesizes a minimal
  one.** When `gob:overlay`'s spec table became setters the record had to build the visual from its own
  fields, and re-implementing `luaTint`/`luaScale`/`luaAlpha`'s parsing to do it would have been a second
  reading of the same options. The cheap path is to synthesize a table carrying ONLY the construction keys
  (`image`/`model`/`ghost`, `billboard`, `sdt`) and then apply the look through the live setters
  (`overlayScale`/`overlayAlpha`/`overlayTint`/`overlayRotate`) the entity already had — which also makes a
  rebuild preserve the look for free, because the record replays exactly what it replays on a first build.
- **(039.3) A per-gob record read by the DRAW pass and written by Lua setters needs `volatile` fields and a
  paint filter that skips an incomplete one.** The 038 record was immutable, so `paintRecords()` could hand
  its snapshot straight to the painter; a mutable one is written on the UI thread and read one frame later, so
  every configured field is `volatile` (each is a whole value in either). And the filter must skip a record
  with no kind as well as a world-space one — otherwise a bare overlay reaches `gwrap.label(g, null, ...)`,
  which is a null label in the render pass rather than a missing picture.
- **(039.3) `Retired.methodIndex` is per ENTITY and has to be installed on each one.** 039.2 learned it for
  `LuaGob`; `LuaOverlay` was the second instance, and its metatable still pointed `__index` straight at the
  methods table — so `ov:pos` read as plain nil however complete the retired table was. Every entity whose
  verbs this feature re-spells needs the same one-line swap, and the check is a suite line asserting the
  MESSAGE, not the absence.
- **(039.6) An anonymous subclass is how you change a `haven` widget's behaviour without renaming it.** The
  window builder needs the CHROME to skip its draw while the content is unarmed, and the only seam is
  `Window.draw`. A named `AddonWindow extends Window` would have made `w:type()` read `"AddonWindow"` and
  broken every `@Window` selector, `window` role and deco lookup in the client — but `LuaWidget.typeName`
  climbs past anonymous classes (it was written for `haven`'s own `new TextEntry(...) {...}` idiom), so
  `new Window(sz, "") { public void draw(GOut g) { ... } }` overrides the behaviour and keeps the name. The
  anonymity is load-bearing, not stylistic; the same trick is available for any `haven` widget the bridge
  builds.
- **(039.6) `LuaWidget.live()` treats "not under `ui.root`" as DEAD, and it nulls the handle's reference.**
  Anything that wants a widget to exist before it is in the tree fights that, and loses quietly: every setter
  in a builder chain reads the handle as stale, becomes the 029.2 silent chaining no-op, and the entity is
  permanently dead because `live()` clears `n.wdg` on the way out. That single line is why the pending
  surface is attached at once (D-119) rather than held back — a design that needs an exception in `live()`
  is a design fighting the identity model, not extending it.
- **(039.6) A callback slot that the tick/draw passes read and Lua writes is one volatile array, not eight
  volatile fields.** 039.3's rule (a record read by the draw and written by setters needs `volatile`) applied
  to eight callbacks would be eight declarations and eight chances to forget one; a `volatile LuaValue[]`
  replaced on write (`clone()`, set, assign) gives the same visibility through one field, and the setters
  become a single loop over `AddonWidget.CALLBACKS` in `LuaWidget` instead of eight near-identical blocks.
  The array of names is then also what the suite iterates, so "all thirteen return self" is two verdict
  lines rather than twenty-six.
- **(039.14) A container's item CELLS are not its items: one `GItem` can wear several `WItem`s.**
  `Inventory.addchild` builds one `WItem` per item; `Equipory.addchild` builds **one per equipment slot the
  item fills** (`args` is a list of ep indices, `wmap` maps the item to that collection), so a two-slot
  weapon is drawn twice and `children(WItem.class)` returns it twice. That was invisible while
  `widget:items()` handed back copies and became a defect the moment the entries were interned — two `==`
  entries for one thing. Read items by de-duplicating on `WItem.item`; read the places off the item.
- **(039.14) The equipment window names all but one of its slots, and the name comes from a resource.**
  `Equipory.etts[i]` is filled only when `gfx/hud/equip/ep<i>` has an image layer; in this fork slot 16 has
  none (23 slots, 22 names), and the server does place items there. So a slot-name lookup that returns nil
  for "no published name" makes a worn item's place unreadable — and `#slots == 0`, which is what *not worn*
  looks like. The in-game round is the only place this shows: a probe wearing hand-placed gear never lands
  in that slot by accident.
- **(039.14) The cursor item IS a bound widget, unlike everything else about it.** `GameUI.addchild` with
  `place == "hand"` does `add((GItem)child)`, so the item on the cursor is a real server-bound `GItem` under
  the HUD with a widget id — `GameUI.vhand` is only the `WItem` that draws it. It therefore interns, ages and
  goes stale exactly like a container's item; what it lacks is a container, so it is the one item with no
  cell and no slot.
- **(040.1) `SIWidget` caches its rasterised face, and `Widget.resize` does not invalidate it.** Every
  image-backed control (`Button`, `IButton`, `CheckBox`, …) draws through `SIWidget.draw(GOut)`, which builds
  a `TexI` from `draw(BufferedImage)` **once** and blits it thereafter; `redraw()` is what disposes it, and
  `Widget.resize(Coord)` never calls it. So an adapter that does not override `resize` gives you a control
  whose box moved and whose picture did not — a bug that looks like a layout bug and is a cache bug. One
  `resize` override calling `redraw()` per adapter, and it is not optional.
- **(040.1) `Button`'s two-argument constructor sends the server a message, and its three-argument one
  guesses its own height.** `new Button(w, text)` sets `action = () -> wdgmsg("activate")` — harmless on a
  client-only widget (the message dies at `ui.root`) but exactly the wrong default for a control that must be
  client-side by construction, so take the `Runnable` overload with `null` and override `click()`. And
  `largep(w)` decides *short vs tall* by comparing the width against the button's own **UI-scaled** images:
  the same default width builds the plain button on one client and the tall decorated one on another. Pass
  `lg` explicitly, or a default has no fixed look.
- **(040.1) Every window's chrome carries a native `IButton`, which is what makes "find a widget I do not
  own" reliable in a suite.** `Window.DefaultDeco.cbtn` is the close box: a real `IButton`, added by the deco,
  owned by nobody. A suite that has just built a window of its own therefore always has a borrowed
  `role == "button"` in the tree to assert a refusal against — no dependence on which client windows happen to
  be open, which is the difference between a check that runs and a check that is skipped on a quiet login.
- **(040.1) Nineteen adapters cannot share a base class, so share an OBJECT and a default-method mixin.** Each
  control adapter must extend the `haven` class it wraps, so the ownership state (owner, self, root, dead,
  pending) lives in one small holder the adapter keeps as a field, and the contract's methods are `default`
  implementations over it (Java 8 allows them at `source 1.8`). An adapter's whole ownership boilerplate is
  then a field and a getter, which is what keeps "one adapter per control" from meaning thirty lines each.
- **(040.2) `IButton.checkhit` reads PIXELS, and it bounds the point by `sz` rather than by the picture.** It
  checks `c.isect(Coord.z, sz)` and then samples the up image's alpha raster at `c` — fine in the engine,
  where nothing ever resizes an `IButton`, and an exception raised **from the input pass on a mouse move**
  the moment an API hands out `:size(w, h)`. The adapter overrides `checkhit` to bound by the face it was
  built with. The general shape: a hit test that indexes an array is a crash the *caller* can cause, so an
  adapter that widens who can call it owns the bound.
- **(040.2) `Utils.imgsz` is package-private, and the widget's own `sz` after `super(...)` is the same value.**
  An adapter in `io.brodgar.addon` cannot call it; `IButton`'s constructor already passed it to `super`, so
  reading `sz` in the adapter's own constructor costs nothing and needs no core edit. (Compile-time only, but
  it is the sort of thing that invites a pointless `// addon:` widening.)
- **(040.2) Replacing a live widget means moving every map keyed on it — enumerate them, do not wait for the
  symptom.** The face setter swaps a `Button` for an `IButton` under the same Lua handle, and four maps are
  keyed on the widget object: the per-addon Widget intern cache (whose `wdg` field must ALSO be re-pointed,
  or the value the author is chaining goes stale mid-statement), the per-addon `widget:rule()` Rule cache, the
  per-widget style level in the sheet, and the sheet's resolution cache (which is dropped, not moved — the
  new widget is a different class and resolves differently). A `LuaRule` needed no fixing because it holds
  the *handle* and resolves it at every read, which is the property that made re-pointing the field enough.
- **(040.2) A suite that has to observe the ARMING boundary needs two phases, and a timer is the whole
  mechanism.** Everything a slash command builds is still pending when the command returns — the arming tick
  has not run — so "refused once it is on screen" cannot be asserted in the same pass that builds it.
  `hafen.timer():after(0.5, phase2)` with the summary printed at the end of phase 2 is the shape; nothing
  about it needs a schedule between suites (035.3's rule stands: no suite starts itself).
- **(040.2) `Resource.loadrimg(name)` on the LOCAL pool fails fast, so it is safe on the UI thread.** A
  missing name raises `Resource.NoSuchResourceException` in ~10 ms (jar source then file source, both miss);
  it is `loadwait`, so a *remote* name would be a different story. `.layer(imgc)` can also answer **null**
  for a resource with no image layer — that is a null check, not an exception, and a `.scaled()` on it is the
  NPE. Measured headlessly before shipping the verb, precisely because "the client hangs on a typo" is the
  failure this would have had.
- **(040.3) An `I`-prefixed class is not always the picture variant of its base — read the constructor before
  building an adapter for it.** `IButton`/`ICheckBox` genuinely swap in pixel faces, so it was a reasonable
  pattern-match to expect `ILabel` to be `Label`'s picture form. `ILabel(String, Text.Furnace)` is instead a
  label whose font is baked once and never live-restyled (the opposite of `Label`'s live restyle on a
  stylesheet override) — no picture at all. Caught only by opening `ILabel.java`, after the plan/api-sketch
  had already committed to the symmetry in prose. `hafen.ui():label()` ships `Label` only (D-151).
- **(040.3) A `float` field read back through `LuaValue.valueOf` is not bit-exact against a decimal literal —
  pick a value exact in both.** `Progress.a` is a `float`; `0.35f` widened to `double` is
  `0.34999999...`, so `p:value(0.35); p:value() == 0.35` would read FALSE in Lua despite a correct round
  trip. Powers-of-two fractions (`0.25`, `0.5`, `0.75`, …) are exact in both `float` and `double`, so a suite
  asserting exact equality on a float-backed value should reach for one of those rather than adding an
  epsilon helper.
- **(040.3) A picture's content setter needs no rebuild — check which field the engine actually made
  `final` before reaching for D-148's machinery.** `IButton`'s faces are `final`, which is what forces the
  button-face setter to swap the whole widget (D-148). `Img.setimg(Tex)` is a live, public,
  post-construction setter with nothing `final` about the picture, so `widget:source(h)` on
  `hafen.ui():image()` just calls it directly (D-152) — no pending check, no second engine class, no maps to
  re-key. The one cost is a placeholder `Tex` at construction (`Img`'s only constructor takes one), built
  from `TexI.mkbuf(Coord.of(1, 1))` and never actually seen: the control paints nothing while pending, and
  the ordinary `hafen.ui():image():source(h)` chain replaces it before the first frame that would.
- **(040.4) A `Window`'s own `mousedown` still touches ITSELF after its child's click returns — destroying
  the window from inside that click is not safe by default.** `Button`'s activation is provably safe to
  destroy-from (`Button.mouseup` releases its grab and calls `click()` LAST, so nothing about the button
  itself runs afterward) — but that says nothing about a `CheckBox`, whose `mousedown` calls `click()`
  directly, nor about the *containing* `Window`: `Window.mousedown` is `if(ev.propagate(this)) { parent
  .setfocus(this); raise(); }`, and that `parent.setfocus` runs on the WINDOW after the checkbox's click
  returns. A `:onChange` handler that calls `window:destroy()` synchronously unlinks the window
  (`Widget.remove()` nulls `parent`) while `Window.mousedown` is still mid-method, and `parent.setfocus`
  NPEs on the UI thread. Caught by the maintainer clicking a demo checkbox three times, not by the 22-check
  automated suite (pure Lua counters can't see a real mousedown dispatch). Fix: defer the destroy a tick
  (`hafen.timer():after(0, fn)`) rather than call it inline. Generalise: "safe to destroy from your own
  callback" is a property of the SPECIFIC call chain the docs verified (`Button.mouseup`'s ordering), not of
  callbacks in general — a new firing point (mousedown vs mouseup, a different containing widget) needs its
  own check, not an inherited assumption.
- **(040.5) When the engine gives no `set()`/`state()` split, a programmatic write cannot reuse the "real"
  mutator — it has to call the SAME leaf method the user path calls, one level lower than where the two
  paths join.** `ACheckBox` splits state-read (`state`) from state-write (`set`), which is what let 040.4's
  `:value(v)` bypass the engine's own consumer field entirely. `RadioGroup` has no such split: `check(int)`,
  `check(String)` and a user's `RadioButton.mousedown` all funnel into ONE method, `check(RadioButton)`,
  which unconditionally fires the group's `changed(int, String)` hook — the exact hook a Lua `:onChange`
  handler sits on. There is no lower-level "just flip the visual, skip the hook" call in the public API.
  The fix is not to find a missing seam but to skip the shared method entirely: call
  `RadioButton.changed(boolean)` — the same leaf `check(RadioButton)` itself calls — directly on the two
  affected buttons from the adapter's own `:value(v)`, keeping the adapter's own label→button map instead of
  reading the engine's (`private`) one. Generalise: before assuming a feedback-loop guarantee needs a new
  `haven` core edit, check how many of the engine's own entry points already converge on one leaf method —
  calling that leaf directly, from outside, is sometimes cheaper than either an edit or the "real" mutator.
- **(040.5) `RadioButton` is a non-static INNER class of `RadioGroup`, not a sibling top-level class — its
  qualified name from another package is `RadioGroup.RadioButton`, and the only way to mint one is the
  factory method, never `new`.** The spec/plan's "`RadioGroup`+`RadioButton`" phrasing (mirroring
  `Button`/`IButton`, two top-level classes) reads as two files; `RadioButton`'s constructor is
  package-private and the class itself lives inside `RadioGroup.java` with no file of its own — `add(lbl,
  c)` is the one door. This falls out naturally once the file is opened, but it means a subclass of
  `RadioGroup` in another package can still mint buttons (through `add`, which runs with `RadioGroup`'s own
  access even when called via a subclass instance) without ever writing `RadioGroup.RadioButton` on the
  left of a `new`.
- **(040.6) `Scrollbar`'s `ctl` field must stay `null`, or `draw()` silently overwrites every addon write
  each frame.** `Scrollbar(int h, Scrollable ctl)` — the constructor `Scrollport` uses — wires `min`/`max`/
  `val` to a live `Scrollable`, and `Scrollbar.draw` re-reads all three from `ctl` **every frame** whenever
  it is non-null: `if(ctl != null) { min = ctl.scrollmin(); max = ctl.scrollmax(); val = ctl.scrollval(); }`.
  The adapter always takes the OTHER constructor, `Scrollbar(int h, int min, int max)`, which leaves `ctl`
  `null` — so an addon's `:range(min, max)`/`:value(v)` writes are the only thing ever touching those three
  fields. Using the `Scrollable` constructor "for convenience" would have made `:range`/`:value` appear to
  work in a suite (which reads the fields back immediately) and then silently revert on the very next drawn
  frame, a bug an automated check running same-tick cannot see at all.
- **(040.6) `HSlider.mouseup` fires `fchanged()` on every drag release, even one that never moved the
  thumb.** `mousedown` calls `update(ev.c)` once (which may or may not change `val`), then grabs the mouse;
  `mouseup` unconditionally calls `fchanged()` whenever a grab was active (`drag != null`), regardless of
  whether `val` changed during it. So "exactly one `final = true` on release" holds even for a plain click
  with no drag distance — the manual check's expected count does not depend on the thumb actually having
  moved, only on a mouse-down-then-up sequence having happened over the control.
- **`Scrollbar` gives the bridge only ONE hook (`changed()`), never a release-time second one the way
  `HSlider` has `fchanged()`** — `mouseup` just releases the grab. This is not a bridge choice; it is why
  `hafen.ui():scrollbar()`'s `:onChange(fn)` calls `fn(v)` with no trailing flag where the slider's calls
  `fn(v, final)` (D-157) — there is no second engine event to carry a flag about.
- **(040.7) Retiring a verb for ONE control can silently break every OTHER addon that calls it
  generically — found in-game, not by the suite, because the suite only exercises its own task.**
  `entry:text()` was first retired on BOTH arities (read and write), mirroring how `:onChange`/`:value`
  refuse on a control that has none. But `:text()` is not that kind of verb: `docs/addons/api/ui/widget.md`
  already publishes it as a best-effort, NEVER-THROWING read across every text-bearing widget, and the
  shipped `widgetstack` example walks the hovered widget up to the root calling `n:text()` on each one for
  its inspector — exactly the contract that promise exists for. The very first login threw a `widgetstack`
  handler error the moment the suite's own entry control was hovered. The fix: only the WRITE
  (`entry:text(s)`) retires, naming `:value(s)`; the READ keeps answering exactly as before on every widget,
  `CEntry` included. Generalise: before retiring a verb's READ half for one control, grep every OTHER addon
  in `addons/` (not just this feature's own suites) for generic, class-agnostic calls to it — a tree-walking
  introspector is the shape of caller most likely to be silently broken, and it is never in the task's own
  suite to catch.
- **(040.9) `SListBox` builds its row widgets LAZILY, from `update()` on the next tick — not synchronously
  inside `:rows(t)` — and a suite's tree-size baseline has to be measured on the same side of that tick as
  its final count, or it reddens over widgets that were never a leak.** The first in-game run of the
  `:list()` suite failed its teardown check by exactly 6: `base` was captured right after building a
  *persistent* demo list (the one the `[manual]` line needs), but that list's 3 rows' 2 widgets each
  (an `ItemWidget` wrapper plus its `TextItem`/`IconText` content) had not been built yet at that instant —
  they appeared between `base` and the final count, on the very next frame, and stayed there for the rest of
  the run. Fixed by deferring the whole rest of the suite one tick (`hafen.timer():after(0.5, phase1)`)
  so `base` is measured once every list built *before* it — including ones outside the teardown scope
  entirely — has already had its lazy build. Generalise: a suite that measures `treeCount()` as a baseline
  must let every `SListBox`/model-backed control already on screen finish its FIRST `update()` first, not
  just the ones the teardown check itself covers.

- **(040.10) A missing `implements` on an interface a class already satisfies by shape compiles fine and
  fails silently at dispatch.** `CMenu` had `onSelect()`/`onSelect(fn)` methods matching `Controls.Select`
  exactly, but the class's `implements` clause never listed `Select` — so `c instanceof Select` in
  `Controls.onSelect` was always false, and the FIRST `hafen.ui():menu():onSelect(fn)` anywhere (the demo
  addon's own line, before a single suite assertion ran) threw "has nothing to select" and aborted the whole
  `run()`. `ant hafen-client` gave no warning: Java does not require declaring an interface a class happens
  to implement structurally, so nothing catches the omission short of exercising the dispatch. Worth a glance
  whenever a new adapter is added beside an existing capability interface — the class declaration is the one
  place a copy-paste of another adapter's method bodies can silently drop the contract that makes them mean
  anything.

- **(040.10) `Window.mousedown` raises itself AFTER `ev.propagate` returns, so anything a descendant's click
  adds to `ui.root` DURING that propagation ends up UNDER the window a moment later.** `SDropBox`'s open
  popup (`SDropList`) is added as the last child of `ui.root` from inside `drop.click()`, reached while the
  click is still propagating down through the window's own children; `Window.mousedown`
  (`if(ev.propagate(this)) { parent.setfocus(this); raise(); }`) then re-appends the window itself —
  including its own opaque `drawbg`, tiled across its whole rectangle every frame — ON TOP of the popup that
  was just added. This is not suite-specific: it happens on every click that opens a dropdown inside ANY
  window, native or addon-built, whenever the popup's screen area overlaps the window's rectangle (which it
  usually does, since the popup opens right below the control). No `haven` core edit needed to fix it: queue
  the popup (found structurally — `SDropBox`'s own field for it is private even to a subclass) and re-raise
  it from `AddonManager.tick()`, in the SAME slot `UiApi.armPending()` already occupies. That slot runs AFTER
  a frame's input dispatch has fully finished (the same guarantee `armPending()` already relies on — "a
  window built in an input handler is on screen in the very frame it was asked for") and BEFORE that frame's
  draw, so the fix lands in the SAME frame the click did: no visible flicker, confirmed in-game.

- **(042.10) `Widget.listen`'s handler fires BEFORE the widget's own default handling, not after — so it
  cannot be used to read a value that handling is about to write.** `Widget.handle(Event)` checks `listening`
  first and only falls through to `ev.shandle(this)` (which for a `MouseMoveEvent` on a dragged `Window` is
  what calls `mousemove()` → `move(...)`) if nothing in `listening` short-circuits. A listener that reacted to
  the event by reading the target's OWN position inline (`Layout`'s drag-anchor re-derive, watching a
  `MouseMoveEvent` on the widget being dragged so an anchored follower tracks it) read the position from
  BEFORE this event's `move()` ran — permanently one input event stale for the whole drag, which read in-game
  as a small, constant lag rather than an obvious break. `WidgetSubs`'s own input keys (041.3) never hit this
  because they hand the event's own PAYLOAD to Lua (`ev.c`, `ev.b`) rather than re-reading the widget
  afterward. The fix, generalisable to any future "observe a native widget's own handling" listener: don't
  read the result inline — enqueue onto the SAME tick-drained queue a resize/removal notify already uses
  (`AddonManager.onWidgetResized`, here), so the read happens after `UILoop.Frame.tick`'s input pass has
  actually finished for that frame (`threading.md`'s matching entry has the frame-loop ordering that makes
  this land the same frame, not a frame later).
- **(042.10) A per-tick fold over a heterogeneous set covers every member for free; replacing it with
  targeted event taps does not, unless every member's SHAPE is checked.** `Layout.redrive()` walked every
  entry in `derived` each tick and simply re-`apply`d whatever was still alive — it never needed to know
  WHY an entry might have moved, so a `{to = "screen"}` anchor and a `{to = <widget>}` anchor were the same
  work. Replacing it with `Layout.moved(w)` (re-derive whatever is anchored to WIDGET `w`) covered the widget
  case but silently dropped the screen case: `Anchor.SCREEN` carries no `target()`, so `moved`'s `to != WIDGET`
  filter skips it by construction, and a screen-anchored widget simply stopped tracking the game window's own
  resize — invisible in an automated suite (nothing but a real OS-level window resize exercises it) and
  caught only by an in-game manual check. When a fold-based mechanism is replaced by shape-specific taps, each
  shape the fold used to treat uniformly needs its OWN tap, not just the most obvious one.
- **(044.1) Re-homing a widget must not go through `Widget.remove()` — that call is a DEATH NOTICE, not a
  detach.** [`Widget.remove()`](src/haven/Widget.java:569) ends with the 042.1 seam
  `AddonManager.onWidgetRemoved(this)`, whose drain fires `widget:on("Destroy")`, a selector subscription's
  `disappear`, and `dispatchReplacedRemoved` (which ENDS a live `w:replace()` substitution). None of those consult
  liveness — they match on identity — so a `remove(); newparent.add(w)` pair reports three deaths for a widget that
  is alive one line later. It also calls `setcanfocus(false)`, which is permanent: the widget would come back
  unfocusable. The correct re-home is the two things a re-home actually is:
  `if(w.canfocus) old.delfocusable(w); w.unlink(); old.cdestroy(w); w.parent = null; neu.add(w, at);` — every one
  of those is `public` (`unlink`, `cdestroy`, `delfocusable` are all reachable from another package). `ui.removed(w)`
  is skipped deliberately: it only drops `UI.Grab`s owned by the subtree, which a widget that is still alive should
  keep. **Rule:** before reusing an engine lifecycle method for a lifecycle it does not name, grep what it NOTIFIES
  — the observers were written for the meaning, not for the mechanics.

- **(044.4) `Button.mousedown` takes a `ui.grabmouse` and does NOT test coordinates — an orphaned one is a
  client-wide interceptor.** [`Button.mousedown`](src/haven/Button.java:254) depresses, plays its sfx and grabs
  the mouse for *any* mousedown it is handed; only [`mouseup`](src/haven/Button.java:264) tests
  `ev.c.isect(Coord.z, sz)`. While a grab is outstanding **every** pointer event in the client goes to it
  ([`UI.dispatch`](src/haven/UI.java:626) checks `grabs` before the root traversal), so a second mousedown
  re-enters `mousedown`, **overwrites `d`, and leaks the first grab permanently** — leaving a button that
  depresses on every click anywhere, flat UI included, until the client restarts. Anything that synthesises a
  press must release it in the same statement; never hold one across a timer.
- **(044.4) `SIWidget` caches its rasterised face in a PRIVATE field, and `redraw()` is the only signal it
  changed.** [`SIWidget.surf`](src/haven/SIWidget.java:31) is nulled by `redraw()`, which `Button` calls on
  press, on arm/disarm as the pointer crosses it, and on `disable`. None of that shows in the widget's place,
  size, visibility or caption — so anything caching a widget's picture (a spatial surface) needs
  `SIWidget.redrawing()` to see it. On the flat UI the question never arises: the screen is redrawn every frame.
- **(044.4) A bare `hafen.ui():widget()` paints NOTHING — it is a container, not a surface.** No background, no
  border, only its children; stood in the world it reads as a title floating over the terrain. The stylesheet
  cannot rescue it either: `bg` replaces a surface the client *already* paints and never invents one
  ([D-083](../decisions/widgets-ui.md#d-083)). A panel that should look like a panel is a
  `hafen.ui():window()`.
- **(044.4) A window places its children in INNER coordinates and nothing converts them to outer ones.**
  [`Window.xlate`](src/haven/Window.java:445) adds `deco.contarea().ul` at draw/hit time while the child's own
  `c` stays inner, and no `hafen.*` verb exposes the inset. A surface pixel is an *outer* pixel, so anything that
  must aim at a child of a standing window measures the offset instead: two 1-D sweeps of `hafen.ui():at()`
  across the window on the flat UI *before* it stands (down the middle for the rows, across those for the
  columns) give the child's box exactly, on whatever chrome the client happens to wear.
- **(044.5) `hasfocus` is the wrong read for "does this widget have the keyboard" — focus is a PATH, not a flag.**
  `Widget.hasfocus` is only maintained *below* a controller that has focus itself, and **nothing ever sets
  `ui.root.hasfocus`**, so it stays `false` on essentially everything that is in fact typing. The real answer is
  the walk `Widget.FocusedKeyEvent.propagation` performs: from `ui.root`, a `focusctl` hands the key to its ONE
  `focused` child, and anything else offers it to every VISIBLE child in turn. `LuaWidget.focusPath` (behind
  `widget:focused()`) mirrors exactly that. Corollary for 044: a `WidgetSurface` needed **no** focus seam at all
  — it is a plain non-`focusctl` child of the root, so `Widget.setfocus` forwards straight past it and the key
  comes back down the same chain, which is why a text entry standing in the 3D world takes the keyboard with
  nothing added anywhere.
- **(044.5) `Window.mousedown` propagates to its children FIRST and takes focus only if one of them consumed the
  click.** `if(ev.propagate(this)) { parent.setfocus(this); raise(); return true; }`. So a click on a window's
  own background does NOT focus it, and a click on a text entry inside it focuses BOTH (the entry via its own
  `parent.setfocus(this)`, then the window on the way back out). Anything reasoning about "who got focus from
  this click" has to account for both halves.
- **(044.5) `dropdown:size(w, h)` leaves the drop arrow behind — a pre-existing 040.10 gap, found by a suite that
  could not click the arrow.** `widget:size` on an owned control is a plain `Widget.resize`, but `SDropBox`
  places its arrow ONCE in its constructor via `adda(makedrop(), Coord.of(sz.x, sz.y/2), 1.0, 0.5)` — right-
  aligned against the width it was BUILT with — and overrides `resize` nowhere. Resize it smaller and the arrow
  sits outside its own parent's box: clipped in the draw, and unreachable by any hit test (`LuaWidget.hitTest`
  and `PointerEvent.propagation` both rect-test the child against the parent before descending). Symptom is a
  dropdown that simply cannot be opened. Same shape as every other "the constructor placed it" control.
- **(044.5) `MouseMoveEvent.propagation` broadcasts to EVERY visible child with no rect test** — unlike
  `PointerEvent.propagation`, which rect-tests and stops at the first that handles. That is how a control
  un-hovers/un-arms when the pointer leaves it (`IButton.mousemove` recomputes `checkhit` and `redraw()`s). And
  `MouseHoverEvent.propagation` goes further still: it dispatches to every child, invisible ones included,
  carrying a per-child `hovering` flag — but its `derive` constructor leaves `hovering` **false**, so anything
  dispatching a derived hover by hand must set it or it un-hovers the very widget the pointer is on.
- **(044.6) A `Window`'s hide is a FADE, and re-homing it mid-transition CANCELS the hide.** `Window.hide()`
  with a parent does not clear `visible`: it starts `trans.hide(...)` and sets `animst = "hide"`, and only the
  tick that finishes the animation calls `super.hide()`. (`visible()` answers false at once — it reads
  `visible && ((animst == null) || (animst == "show"))` — so the widget LOOKS hidden while the raw field is
  still true.) Re-adding it in that window runs `Window.added()` → `initanim()`, which sees the raw `visible`
  and starts a SHOW animation, so the hide is silently undone. Anything that re-parents a window — standing it
  in the world, taking it back — must therefore act on the SETTLED state, not on one taken half a second
  earlier; a suite that hid and removed in the same statement read the window back visible and was right to.
- **(044.6) A standing window's own `c` is a transient, and only the RECORD is trustworthy.** The surface pins
  its content at `Coord.z` every tick (`WidgetSurface.tick`, so a title-bar drag is inert), but one round read
  it back as `0,-10` — once, never again, and the writer was never identified (`Window.chdeco` is the only
  self-`c` write in `Window`, and every candidate is a one-shot the pin then corrects). The same round reported
  the inventory returning to a wrong screen position after a `:reload`, also not reproduced afterwards. Two
  things came out of it that hold regardless: `haven.Coord` is **mutable** and the client hands the same shared
  `Coord.z` object to many widgets at once (`GameUI.maininv.c` IS that object), so a record that keeps the
  reference is a record something else can move — copy it, both when recording and when handing it back; and
  never assert an internal pin from Lua when what the feature claims is that the RECORD survives.
- **(047.1) `Widget.add0` LINKS the child before it calls `added()`, so a widget is already reachable from
  the root inside its own `added`.** [`add0`](../../../src/haven/Widget.java:272) runs `child.parent = this;
  child.link(); child.added();` in that order — which is what makes an event fired at the end of `added()`
  usable: a handler that calls back into the API (`hafen.flowermenu():list()`, a recursive
  `ui.root.children(Class)` walk) finds the widget that is announcing itself. Fire *before* `link()` and the
  same handler reads an empty world. The corollary is the trap: `added()` is also where a widget still
  MUTATES itself, so "the widget exists" and "the widget is finished" are different moments.
- **(047.1) `FlowerMenu.added()` REPLACES `opts` with a longer array, so any seam earlier than its last line
  ships an incomplete petal set.** The fork's `addVoicePetal` builds a new `Petal[]`, copies the old one into
  it and assigns — it does not append in place. Hooking the constructor, or the top of `added`, reads the
  server's petals and silently drops the client-side one. Same class as 042's D-179/D-180 second sites: when
  a set is assembled in a method, the only complete moment is that method's END.
- **(047.1) A subclass that overrides the widget's ACTION method and never calls `super` is why a close seam
  hangs off `uimsg`.** [`BuddyWnd`'s anonymous `FlowerMenu`](../../../src/haven/BuddyWnd.java:430) overrides
  `choose(Petal)`, runs a local `Runnable`, and calls `uimsg("act", num)` / `uimsg("cancel")` **by hand** —
  it never reaches `FlowerMenu.choose` and sends no `"cl"` at all. So `choose` is not where a menu ends;
  `uimsg` is, and it is the one seam the client-side menu shares with the server's. Its `destroy()` override
  *does* call `super.destroy()`, which is what lets a `destroy()` fallback cover both. Read the subclass
  before picking a seam on the base class — an override that skips `super` is invisible from the base.
- **(047.1) A menu OUTLIVES its own close by up to 0.75 s, so "is one open" and "has one been chosen" are
  different questions.** `uimsg("act")`/`"cancel"` start a `Chosen` (0.75 s) or `Cancel` (0.25 s) `NormAnim`
  and only `ui.destroy` at `s == 1`. The widget is therefore still in the tree throughout — so a section that
  answers "the open menu" keeps answering during the fade, and a close event read back through that section
  is not yet empty. Document which of the two a read means rather than assuming they coincide.
