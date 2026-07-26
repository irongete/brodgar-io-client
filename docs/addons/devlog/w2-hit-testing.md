# W2 — hit-testing: `hafen.ui.at`/`mouse` + `node:rootpos` (the WoW `/framestack` enabler)

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + **12 headless `hitTest`
> checks** + LuaJ parse of all 8 addons. **In-game verified ✅** (stack + highlight on a Barter Stand; the
> widgetstack v0.2.0 click-to-inspect inspector works). Ships the **`widgetstack`** example addon.
> **Design:** [specs/addons/20-widget-introspection.md](../../specs/addons/20-widget-introspection.md) §W2,
> [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.ui`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md), decision **D-042** (this slice) on top of
> **D-041** (the `WidgetNode` + `:same` it extends), plus **D-017** (facade-safe) / **D-025**
> (`hafen.act.raw`, the act path) / **D-031** (a dedicated example addon, not folded into `hello`).

The second and final slice of the **W-series**. [W1](w1-widget-node.md) gave the generic tree walk +
`:parent()`; W2 adds the two reads that find *what widget is under the cursor* — the only pieces missing
from a WoW `/framestack` clone — and ships that clone as the `widgetstack` example addon. **Zero `haven`
core edit** — every backing is already public.

## The surface

```lua
hafen.ui.mouse()        -- { x=, y= }   the cursor in root coords (public UI.mc)
hafen.ui.at(x, y)       -- the DEEPEST WidgetNode under that point, or nil
node:at(coord)          -- same, but hit-testing only within this node's subtree ({x=,y=} root coords)
node:rootpos()          -- { x=, y= }   the node's top-left in root coords (with :size() = a highlight box)
```

## Design points

### `at()` mirrors the engine's own pointer dispatch (D-042) — not a naïve rect test

The whole value of `/framestack` is that it reports the widget a real click would hit. A plain
"is the cursor inside `pos..pos+size`?" gives **wrong** answers inside scrolled lists (a `Scrollport`
offsets its content via `xlate`) and for custom hit shapes (`checkhit` overrides). So `AddonManager.hitTest`
replicates [`Widget.PointerEvent.propagation`](../../src/haven/Widget.java:981) — the exact walk the engine
uses to route a click — plus the leaf `checkhit` the engine's tooltip walk
([`Widget.tooltip`](../../src/haven/Widget.java:1906)) applies:

```java
private static Widget hitTest(Widget from, Coord c) {          // c in `from`'s local coords
    for(Widget wdg = from.lchild; wdg != null; wdg = wdg.prev) {   // topmost-first (last child on top)
        if(!wdg.visible())
            continue;                                              // skip hidden subtrees
        Coord cc = from.xlate(wdg.c, true);                       // child pos in parent-local (scroll-aware)
        if((wdg.sz != null) && c.isect(cc, wdg.sz)) {
            Widget hit = hitTest(wdg, c.sub(cc));                 // descend into child-local coords
            if(hit != null)
                return hit;
        }
    }
    return from.checkhit(c) ? from : null;                        // no child claimed it: is it in OUR hit area?
}
```

It returns the deepest hit; `from` itself when the point is in its own hit area but no child claims it; or
`null` when the point misses `from` entirely. Because it starts at `ui.root` (whose default `checkhit`
fills the screen), a point over "nothing" faithfully resolves to the **root** widget — the WoW
`WorldFrame`/`UIParent` behaviour. The `widgetstack` addon suppresses the highlight box in that case
(box would be the whole screen) but the node is still returned honestly.

- **`hafen.ui.at(x, y)`** starts the walk at `ui.root` (the point is already root-local).
- **`node:at(coord)`** converts the root-coord point into node-local via
  [`Widget.rootxlate(c)`](../../src/haven/Widget.java:504) (`c - rootpos()`) and starts the walk there —
  so the same `hafen.ui.mouse()` value works for both. `coord` is a `{x=,y=}` table (parsed by the new
  `coordArg` helper; a non-table / non-number is a clear `LuaError`).

### `node:rootpos()` — the highlight box

[`Widget.rootpos()`](../../src/haven/Widget.java:496) is the node's top-left in root coords; with
`:size()` it gives the rectangle to outline the hovered widget (WoW's green box). Read under
`synchronized(ui)`, like every W1 accessor.

### `onMouseOver` is deliberately NOT added

A hover callback on a [`LuaWidget`](../../src/io/brodgar/addon/LuaWidget.java) fires only for the addon's
**own** widgets; `/framestack` must inspect **arbitrary native** widgets. WoW itself doesn't hook per-frame
hover — it hit-tests the cursor against the whole tree. So the primitive is a **coordinate hit-test**, and
hover is just **polling `ui.mc` on `OnUpdate`**. A generic native-widget `onMouseOver` would require
hooking the engine's pointer-dispatch path (invasive) and buys nothing over the poll — out of scope (D-042).

### The `:same` efficiency guard (why W1 has `:same`)

`OnUpdate` fires every frame, but the hovered widget changes only when the mouse moves onto a *different*
one. So `widgetstack` caches the last hovered leaf and **bails early** when it hasn't changed — no tree
walk, no window rebuild, per frame. [`:same`](w1-widget-node.md) (D-041) is what makes that possible:
each `at()` mints a *fresh* handle and client-only leaves have **no `:id()`**, so `==`/`:id()` can't
compare identity — `:same` compares the underlying `Widget` by reference. A per-rebuild counter shown in
the window proves it (it does **not** tick while the cursor sits still — the W2 DoD).

### Read-only, ungated — acting still goes through the gate

Reading the cursor + geometry is client-side data that never reaches the server, so `mouse`/`at`/`rootpos`
are **ungated** (like the rest of the read API). To *act* on a resolved node, read its **server-bound**
`:id()` and pass it to the already-gated [`hafen.act.raw`](../api/actions.md) (D-025) — **no new action
surface, no new gate** here.

### Threading

`hitTest` runs under `synchronized(ui)` (the caller — `nodeAt` / the `node:at` accessor — takes the monitor
before the recursion), so a walk never races tree mutation — the same discipline as every W1 access and the
spec-14 adapters.

## Exact code changes

- **[`src/io/brodgar/addon/AddonManager.java`](../../src/io/brodgar/addon/AddonManager.java):**
  - `hafen.ui` gains `mouse()` → `nodeMouse()` and `at(x, y)` → `nodeAt(owner, x, y)`.
  - `nodeHandle` gains `:rootpos()` and `:at(coord)` accessors (both nil once the node is stale).
  - new W2 helpers: `nodeMouse` (`UI.mc` → `{x=,y=}`), `nodeAt` (`hitTest` from `ui.root` under the `ui`
    monitor), `hitTest` (the engine-mirroring recursion above), `coordArg` (`{x=,y=}` → `Coord`).
- **New [`addons/widgetstack/`](../../addons/widgetstack/)** — the `/framestack` clone (below).

**Zero `haven` core edit** — all backings public
([`UI.mc`](../../src/haven/UI.java:54), [`Widget.lchild`/`prev`/`c`/`sz`](../../src/haven/Widget.java:38),
[`visible()`](../../src/haven/Widget.java), [`xlate`](../../src/haven/Widget.java:482),
[`checkhit`](../../src/haven/Widget.java:794), [`rootpos()`](../../src/haven/Widget.java:496),
[`rootxlate`](../../src/haven/Widget.java:504)).

## The `widgetstack` example addon (D-031 / D-042)

A dedicated addon (`addons/widgetstack/`, **not** folded into `hello`) that is the `/framestack` clone and
the standing harness for W2 (it also re-exercises W1's `parent`/`children`/`type`/`id`/`text`/`size`). On
`OnUpdate` it reads `hafen.ui.mouse()` + `hafen.ui.at(...)`, applies the `:same` guard, walks `:parent()` to
the root, shows the stack (type / #id / 'text' / WxH, root at top, the leaf tinted green) in a
`hafen.ui.window`, and outlines the hovered widget via a `hafen.ui.overlay` using `:rootpos()`+`:size()`.
`:widgetstack` toggles the window; **Ctrl+Shift+F** (a persisted `hafen.key.bind`) freezes the stack so you
can mouse into the window to read it.

**Click-to-inspect (v0.2.0, maintainer request).** Every stack row is **clickable** (`onClick` maps the
click `y` to a row → the `WidgetNode` that row was built from) and opens an **Inspector** window with that
widget's full details: `:type()`/`:id()`, `:pos()`/`:size()`/`:rootpos()`, `:visible()`, `:text()`, a
clickable **parent** link (`:parent()`), and the clickable **child list** (`:children()`). Clicking a child
descends, clicking the parent ascends — each opens a further (cascaded) inspector window, so you can browse
the whole tree. It is **pure Lua over the shipped W1/W2 surface — no new API and no Java change**: the
inspector reads everything live off the node each frame (children/parent re-fetched on click so they're
always current), and the row/child hit-tests use fixed line heights shared between `onDraw` and `onClick`.
Everything is bridge-owned — `:reload`/disable tears the stack window + every inspector window + the overlay
+ hooks down, and `WidgetNode`s are transient (not owned-registry entries), so nothing leaks.

## Verification

- `ant hafen-client` → **BUILD SUCCESSFUL** (one pre-existing `URL` deprecation warning in `LuaHttp`).
- **12 headless `hitTest` checks** (via reflection over a hand-built `Widget` tree): topmost-first sibling
  wins; deepest-descent into a grandchild; a point in a parent but no child → the parent; a point outside
  everything → the root fall-back; an invisible child is skipped (and returns once shown); a `checkhit=false`
  widget is click-through (falls through to its parent); a **scrolled** subtree (custom `xlate`) resolves the
  child only where the content actually is; a subtree hit-test misses cleanly when the point is outside.
- **LuaJ parse** of all 8 addon `.lua` files (`widgetstack` + the 7 unchanged — regression).

**In-game verified ✅:** with `widgetstack` active, hovering the UI shows the live stack of widgets under the
cursor + a green outline on the hovered one (confirmed on a **Barter Stand**); the per-rebuild counter does
not tick while the cursor sits still (the `:same` guard); Ctrl+Shift+F freezes it; and — v0.2.0 — clicking a
stack row opens a working Inspector window whose parent/child links browse the tree. `:reload` leaks nothing.

## Deferred

A generic native-widget `onMouseOver` event (out of scope, D-042 — invasive, useless for arbitrary
widgets); a built-in `:inspect` helper (the `widgetstack` addon covers the need in pure Lua).
