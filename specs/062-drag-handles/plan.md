# 062 — plan

## Approach

Three verbs, one mechanism underneath, and almost nothing new in `haven`. The gesture is a widget copied
from a widget that already works; what it writes is the layout level that already exists; the one thing the
engine genuinely does not do yet is put a hand-named level back after `GameUI` re-lays the screen out.

### The gesture widget

`LuaMouseGrab` is the model, and it should be read before anything is written. It is a **zero-size,
`visible()` widget** added to the tree, and the two halves of a drag reach it by two different doors:

- `arm(UI u) { grab = u.grabmouse(this); }` — `UI.grabmouse`'s predicate is `MouseDownEvent`,
  `MouseUpEvent`, `MouseWheelEvent` and `CursorQuery`, **and nothing else**. `UI.dispatch` walks `grabs`
  newest-first and returns on the first that handles, so a grabbed pointer never reaches the root
  traversal. That, and only that, is criterion 3's *the client's own click never fires underneath*.
- `public void mousemove(MouseMoveEvent ev)` — moves are **not** in the grab's predicate. They arrive
  because `MouseMoveEvent.propagation` **broadcasts to every visible child with no rect test**, handing
  each an out-of-box coordinate. That is why the widget must be `visible` and why zero size costs nothing,
  and it is also why the drag survives the pointer outrunning the handle and leaving the window.

`Window.drag` is the same split seen in the client's own code — `dm = ui.grabmouse(this)` plus a
`mousemove` that calls `move(...)` — so the shape is confirmed from both sides.

**New file `Gesture.java`**, package-private, carrying no Lua:

- One instance per live gesture, added to `ui.root`, holding: the target `Widget`, the mode (drag or
  resize), the list of armed owners it must write, the grab, and `doff` — the offset between the press and
  the target's own origin, so the target does not jump on the first move. `Window.drag(Coord off)` sets
  exactly this and `Window.doff` is the field to imitate.
- `mousemove` computes the new value **from the event's coordinate**, never by reading the target back,
  then writes (below) and returns.
- `mouseup` fires the key, writes the remembered slot if one is held, `grab.remove()`s, unlinks itself,
  and is done. `Up` ends the gesture; there is no other exit but teardown.

**Arming** is a `Widget.MouseDownEvent` listener on the **handle**, installed with `Widget.listen` and
dropped with `Widget.deafen` — the zero-edit seam `Layout.installDragListener` already uses on a target for
the anchor system. One listener per (handle, mode), refcounted over the owners that armed it, so two addons
arming the same pair install one listener and the second `:draggable(nil)` removes it.

### What a gesture writes

Nothing by hand. Each move does what `widget:position(x, y)` does today, which is worth quoting because it
is the whole contract in four lines (`LuaWidget`, the `position` verb):

```java
Moved rec = recordMoved(owner, w);
rec.wantPos = Layout.Anchor.at(to);     // DESIGN pixels
rec.posSeq  = Layout.nextSeq();
Layout.apply(w);
```

and the resize half writes `rec.wantSize` with `rec.sizeSeq`. Routing through `Layout.apply` is what
discharges most of the spec without new code:

| Criterion | Why it already holds |
|---|---|
| 4 — the level is what a gesture writes | `Moved.wantPos`/`wantSize` **is** the hand-named level; `UiApi.releaseMoved(owner, w, true/false)` is `:position(nil)`/`:size(nil)` |
| 5 — the off-screen clamp | `Layout.fit` routes to `UiApi.fitc`, but only when `w.parent` is `ui.root` or a `GameUI` — which is exactly where the client itself clamps |
| 6 — inert on a self-packing window | `Layout.apply` writes the **size half first and the position last**, because `Widget.resize` notifies `parent.cresize`, and the inventory's anonymous `Hidewnd` does `cresize(ch){pack();}` |
| 11 — `revert()` and teardown | `UiApi.teardownMoved(addon)` already puts every laid-out widget back |
| — the client's disk | `GameUI.savewndpos` reads `AddonWidgets.stockc(w)`, so a gesture never reaches `wndc-*` |

**Units.** `Moved.pos`/`size` hold the **stock** value in DEVICE pixels; `wantPos`/`wantSize` are DESIGN.
The gesture speaks design and converts at the edge with `Px.in`/`Px.out`, exactly as the verbs do. A
`Coord` is mutable and `UI.lcc` starts as the shared `Coord.z`, so anything stored is `new Coord(c)`.

**Two addons on one target (criterion 12).** The gesture writes **every** armed owner's record, each with
its own fresh `Layout.nextSeq()`. `LuaWidget.topWant(w, pos)` then resolves the same landed value whichever
level wins, so `:draggable(nil)` from either addon changes nothing on screen while still dropping only that
addon's binding. One gesture object per target, not per owner: the target moves once.

### The screen-resize seam — the one `// addon:` line

`GameUI.resize(Coord sz)` re-places its children unconditionally on every screen resize:

```java
chat.resize(sz.x - blpw - brpw);  chat.move(new Coord(blpw, sz.y));
if(map != null) map.resize(sz);
if(prog != null) prog.move(sz.sub(prog.sz).mul(0.5, 0.35));
beltwdg.c = new Coord(blpw + UI.scale(10), sz.y - beltwdg.sz.y - UI.scale(5));
```

A hand-named level is **not** in `Layout.derived` — that map holds stylesheet anchors — so neither
`Layout.dispatchResized` (which calls `moved`, filtered to `Anchor.WIDGET` targets) nor
`rederiveScreenAnchored` (filtered to `Anchor.SCREEN`) puts it back. `Widget.move` is not hooked anywhere
and `Layout`'s own javadoc says so deliberately: *"`move()` is never hooked, and is not a chokepoint anyway:
a drag writes `c` directly"*.

So: one `// addon:` line at the end of `GameUI.resize`, into `AddonWidgets`, which re-applies `Layout.apply`
for every `Moved` across every addon whose `wdg.parent == this`. Bounded by the held set — `LuaWidget.anyMoved`
is a volatile flag that is `false` on a stock client, so the cost of the line is one boolean read.

Two properties to keep: it runs **after** the client's own placement (so it overwrites, not the reverse),
and it must not recurse — `Layout.apply` may `resize` a child, which fires the geometry seam again.
`Layout.apply` is documented idempotent (a widget already where the cascade says gets no write at all),
which is what stops it.

### The two keys

`Dragged` and `Resized` join `WidgetSubs`' third family — the keys that *"need nothing installed at all"* —
beside `Pressed`/`Changed`/`Draw`. Concretely: **not** in `INPUT_KEYS` (they have no engine event class) and
**not** in `TREE_KEYS` (they ride no placement seam). `Gesture.mouseup` fires them through
`Addon.widgetSubsOrNull(owner, w)`, gated on `hasSub` like every other emitter, with an event built beside
`LuaEvent.grabMove(owner, x, y, mods)` — call it `LuaEvent.gesture(owner, x, y)`, answering `:x()`/`:y()`
and nothing else. Not cancelable, so no `preventDefault`.

They fire from `Gesture` and from nowhere else. That, and not a flag, is why criterion 10's *your own write
does not fire them* is true: `Layout.apply` has no path to `Subs.fire`.

### `remember(name)` — a slot beside the store, not inside it

`hafen.store` persists **only** declared `saved_variables` (`StoreApi.scopeJson` iterates
`a.manifest.savedVariables`, and `flush(a)` returns early when that list is empty). A remembered placement
must need no declaration, so it does not go in that file:

- **Where**: `savedata/<genus>_<char>/<id>.layout.json`, beside the addon's own `<id>.json`, using
  `StoreApi.storeFile`'s directory and `saveDir()`/`sanitize` unchanged. Per character, so the per-character
  scope rules apply verbatim.
- **Shape**: `{ "<name>": { "pos": {x, y}, "size": {x, y} } }` — DESIGN pixels, the space the level speaks.
  A half that no level holds is absent, not null.
- **When it is read**: `StoreApi.restorePerChar()` already runs once per world entry and fills every
  addon's per-character tables **before** `EnterWorld` fires. The layout file loads in the same pass, into
  a per-addon map. `w:remember(name)` then reads that map, and if an entry exists performs exactly the
  verbs' write (`wantPos`/`wantSize` + seq + `Layout.apply`). Called before the scope exists, it has
  nothing to apply and says so rather than applying an empty record (criterion 9).
- **When it is written**: `Gesture.mouseup`, into the map, then through the same throttle
  `StoreApi.autosave` uses. `hafen.store():flush()` must write it too, which means `flush(a)`'s early
  return on an empty `savedVariables` needs the layout file checked beside it — a small, deliberate edit,
  not an accident.
- **`remember(nil)`** removes the entry and rewrites the file. **Teardown does not**: `UiApi.teardownMoved`
  and `AddonRegistry`'s disable path drop the *levels* and the *bindings* and leave the file alone. This is
  the one place the layer's "put everything back" instinct is wrong, and the code needs a comment saying so.
- **Order** (criterion 9): a `w:position(x, y)` written after `:remember` wins because it is the later
  `posSeq`. Nothing special implements this; it falls out of the seq fold.

### The verbs, and the refusal texts the suites assert

All three follow the `position` idiom in `LuaWidget`: `Widget w = live(handle(self, "<verb>"))`, arity by
`a.narg()`, an explicit `nil` handled by hand because it carries a meaning, `self` returned so writes chain.
A **stale target** is the 029.2 silent chaining no-op, as every other write here is; a **stale handle** is an
argument you passed, so it raises. The texts:

- `widget:draggable(h) expects a Widget — the handle the user presses to drag this one. widget:draggable() reads it, widget:draggable(nil) drops it`
- `widget:draggable(h): that handle has left the tree`
- `widget:draggable(h) with the window ITSELF is what a Window's caption already does — pass a handle of your own (build one with :parent(win)) to drag it from somewhere else`
- `widget:remember(name) expects a string`
- `widget:remember("<name>"): this addon already remembers <type> under that name — one name, one widget`

`:draggable()` reads back a Widget handle with `LuaWidget.of(owner, w)`, which interns per addon, so
`chat:draggable() == grip` is identity and the suite can assert it with `==`.

## Files to create/modify

- **`src/io/brodgar/addon/Gesture.java`** (new) — the grab widget, arm/release, the per-target arbitration,
  the two key fires, the remembered write-back.
- `LuaWidget.java` — the three verbs beside `position`/`size`, their refusals, `LuaWidget.of` for the read.
  `Moved` is unchanged; this feature adds no field to it.
- `Layout.java` — the entry `Gesture` writes through, and the re-apply `GameUI.resize` calls.
- `WidgetSubs.java` — `Dragged`/`Resized` in the no-listener family, and in the `:on` refusal's key list.
- `LuaEvent.java` — `gesture(owner, x, y)`, beside `grabMove`/`grabUp`.
- `StoreApi.java` — the layout file: load in `restorePerChar`, write in `flush` and `autosave`, delete on
  `remember(nil)`.
- `Addon.java` — the armed bindings and the remembered map, beside `movedNative`.
- `UiApi.java` — release bindings on teardown **without** touching the layout file.
- `src/haven/GameUI.java` + `src/haven/AddonWidgets.java` — the one `// addon:` line at the end of `resize`.
- **Docs**: `api/ui/native.md` (the section, the h1, the opening *three writes*), `api/ui/widget.md` (three
  owned/borrowed rows, the two keys in the subscription table, *arity is the verb*), `api/ui/mouse.md`,
  `guides/permissions.md`, `guides/saved-data.md`, `api/store.md`, plus the derived set in `spec.md`.
- **`docs/client/gameui-windows.md`** — the page owns the position store and the toggle path and says
  nothing about `GameUI.resize` re-placing `chat`/`beltwdg`/`prog`/`map` on every screen resize. That is the
  fact this feature had to read `src/` to learn and criterion 7 turns on it. One row, same table.

## Risks & gotchas

- **A `Widget.listen` handler reads one event stale.** `Widget.handle(Event)` checks `listening` *before*
  `ev.shandle(this)`, which is what runs `Window.mousemove` → `move(...)`. `Layout.installDragListener`
  marshals onto `AddonManager.onWidgetResized`'s queue for exactly this reason. `Gesture` sidesteps it by
  computing from `ev.c` and never reading the target back — but the arming listener must not read geometry
  either.
- **`ui.grabmouse` does not carry moves.** Copy `LuaMouseGrab`'s comment (*"zero size; visible … so
  broadcast `MouseMoveEvent`s reach it"*), not just its code; a later reader will otherwise "fix" the
  visibility and break the drag off-window.
- **The inventory wrapper cannot be resized from outside.** The anonymous `Hidewnd` does
  `cresize(ch){pack();}` and `Widget.resize` notifies `parent.cresize(this)`, so the window re-packs
  **before the call returns**. Criterion 6's *inert* is that path, not an error branch. `equwnd` has no
  such override and resizes normally — use both in the suite.
- **`Window.visible()` is animation-aware** and a fading-out window already reads `false`; `Window.animating()`
  is the fork's own read. A gesture must not resurrect a window that is on its way out.
- **`w:size(w, h)` sets a window's CONTENT size while `:size()` reads the OUTER box.** The resize gesture
  drives the same asymmetry and must not invent a third meaning; `LuaWidget.sizeArg` is where the stock
  outer box is recorded for the undo.
- **`flush(a)` returns early when `savedVariables` is empty.** An addon that only remembers declares no
  variables at all, so the layout file would silently never be written. This is the easiest bug in the
  feature to ship.
- **`StoreApi.charScope` is `null` until `restorePerChar`.** Every layout path must tolerate it, and
  criterion 9 asks the verb to say so rather than apply an empty record.

## Discarded alternatives

- **A `corner` argument on `resizable`** — the client's own resize never moves the origin (`Window.resize`
  sizes only, which is why its single grip is the bottom right), so corners would buy a vocabulary, a floor
  and a per-corner anchor decision for a gesture no window in the game offers.
- **An engine-derived remember key, so the name could be omitted** — the only derivable key is
  type-and-path, and its failure is silent and crossed: one window's place applied to a different window of
  the same type, a session later, with nothing on screen connecting the two.
- **`remember()` paired with a `restore()`** — the only correct moment to restore is the moment you arm, so
  the second verb's only valid call site is the line after the first. That is a step, not a verb, and
  `widget.md`'s *there is no `:move()`* paragraph already refuses the shape.
- **Leaving saving to `hafen.store` and a `Dragged` handler** — correct, and it makes every addon write the
  same ten lines of field-by-field packing. When every consumer writes the same code, the API is unfinished.
- **A reserved name inside the addon's own `<id>.json`** — `scopeJson` writes declared names only and keeps
  a write-skip cache keyed on that serialization; a smuggled key would fight both.
- **Writing a gesture into the client's own `wndc-*` prefs** — a `setprefc` outlives the addon, so
  uninstalling would leave the HUD rearranged. `AddonWidgets.stockc` exists to prevent exactly that.
- **Hooking `Widget.move` instead of the one line in `GameUI.resize`** — it is on every drag of every
  window in the client, and `Layout`'s javadoc already rejected it once for the anchor system.
- **Doing it in Lua over `m:grab()`** — an addon cannot reach `UiApi.fitc`, the root-to-parent conversion or
  the `GameUI.resize` re-apply, and it cannot see a release the grab swallows.
- **Refusing `draggable` on every `Window`** — it would also refuse dragging a window by a grip of your own,
  which is the case the feature exists for. Only `win:draggable(win)` duplicates the caption.
- **One shared gesture per target instead of writing every armed owner's level** — the second addon's
  `w:position()` would then answer where *it* last wrote rather than where the widget is.
- **A `draggable` stylesheet property** — a gesture binds one named pair; a rule describes a kind.
