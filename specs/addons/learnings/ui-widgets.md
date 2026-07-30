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
