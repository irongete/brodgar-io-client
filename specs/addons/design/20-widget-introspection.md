# Widget Introspection (walk the guts of any widget)

> **Status:** 🟡 Draft · **Spec:** AddOns · **Series:** W (Widget introspection)
> **Slices:** **W1** — the `WidgetNode` tree ([D-041](../decisions/widgets-ui.md)); **W2** — hit-testing / the WoW
> `/framestack` enabler ([D-042](../decisions/widgets-ui.md)).
> **Related:** [08-widget-replacement.md](08-widget-replacement.md) (adopt/replace — the wrap model),
> [14-widget-tree-reads.md](14-widget-tree-reads.md) (typed adapters — the other half),
> [DECISIONS.md](../DECISIONS.md) ([D-041](../decisions/widgets-ui.md)/[D-042](../decisions/widgets-ui.md)), [the API reference](../../../docs/addons/api/README.md),
> [07-ui-and-drawing.md](07-ui-and-drawing.md)

## The problem this solves

Many high-value windows in Hafen are **server-driven widget trees** built from registered Java
`Widget` classes — the Barter Stand, containers, crafting, vendor-like UIs. Their contents
(products, prices, buttons) arrive as **child widgets**, not as a single blob. Today an addon can:

- **adopt** a server widget as a hidden model and read its `WItem` children ([08](08-widget-replacement.md), 3b), and
- read a **fixed set of typed surfaces** (vitals/buffs/char/FEP/action bar) via bespoke Java
  adapters ([14](14-widget-tree-reads.md)).

But it **cannot walk an arbitrary widget's children to arbitrary depth** — so anything that is not a
`WItem` (a price `Label`, a `Buy` `Button`, a nested sub-container) is invisible to Lua. To expose,
say, `products()`/`price()`/`buy()` for the barter stand, the only path today is **a new Java
adapter per window** — Java work that repeats for every UI you want to touch.

This series adds the **generic, read-only structural read**: from any widget, enumerate its children,
their children, and so on — the "see the whole guts of a widget" primitive. With it, a maintainer
who has read the upstream widget class can build the adapter (`barterstand`, …) **in pure Lua**, and
never write Java per window again.

**This complements [14](14-widget-tree-reads.md); it does not replace it.** The typed adapters stay
for *hot, event-driven* reads and for *private/protected* fields (reflection lives in Java). This
series is the *generic, on-demand, public-structure* reader that any addon can drive.

## How widgets and ids actually work (verified — the design pivots on this)

- **Children:** every `Widget` exposes its child list via public
  [`Widget.children()`](src/haven/Widget.java:1742) (tree order); position/size/visibility are the
  public `Widget.c` / `Widget.sz` / [`Widget.visible()`](src/haven/Widget.java). Class identity is
  `getClass().getSimpleName()`. **All public — zero core edit to read the tree.**
- **Server id — the crucial distinction:** [`Widget.wdgid()`](src/haven/Widget.java:560) returns the
  widget's **server id** (via [`UI.widgetid`](src/haven/UI.java:588)), or **`-1` if the widget is not
  server-bound** (not in [`UI.rwidgets`](src/haven/UI.java:51)). Server-created widgets (windows,
  inventories, and *some* nested containers) are bound and have an id; **client-only sub-widgets
  (Labels, most Buttons) are not** — they live purely on the client.
- **Why the id matters for actions:** an outbound `wdgmsg` is routed by
  [`Widget.wdgmsg`](src/haven/Widget.java:741) **up the parent chain** to
  [`UI.wdgmsg`](src/haven/UI.java:667) → [`rawWdgmsg`](src/haven/UI.java:680), which looks the
  **sender's** id up in `rwidgets`. **A `wdgmsg` from an unbound sender (`id < 0`) is dropped with a
  warning** ([UI.java:681-685](src/haven/UI.java:681)).

  > **Consequence (correct the intuition):** you do **not** "send a `wdgmsg` to the Buy button."
  > That button is client-only; when clicked, *its own code* calls **its server-bound parent's**
  > `wdgmsg("buy", …)` with a semantic message name. To act, you target the **node that has an
  > `:id()`**, with the message + args the button would have sent (learned from the upstream class).

So the introspection API must, per node, report **both** the structure (type/children/text) **and
whether the node is server-bound + its id** — the id is what makes a node actionable.

## The API: opaque `WidgetNode` handles ([D-041](../decisions/widgets-ui.md))

A **read-only, facade-safe** tree of opaque handles. No raw `haven.Widget` ever crosses into Lua
([P1](01-architecture.md) / [D-017](../decisions/security-sandbox.md)); a `WidgetNode` is a Lua handle backed by a Java
`LuaWidgetNode` that holds the widget reference **inside Java** and resolves lazily.

### Entry points (how you get a root node)

```lua
hafen.ui.root()        -- WidgetNode for ui.root — the top of the WHOLE client tree (discovery)
hafen.ui.node(id)      -- WidgetNode for a server widget id, or nil if it doesn't resolve
```

`hafen.ui.node(id)` takes a **server widget id** — a `desc.id` from
[`hafen.ui.onWidgetCreate`](08-widget-replacement.md), a model's `:raw()`, or any node's `:id()`.
`hafen.ui.root()` needs no id and lets you walk **down** to any open window. Two entry points for two
distinct inputs (top-of-tree vs. by-id) — not two ways to do the *same* thing ([D-012](../decisions/architecture-api.md)).
`model:node()` is available as sugar for `hafen.ui.node(model:raw())` on an adopted model.

### The `WidgetNode` handle

Read accessors (each returns `nil`/empty if the node is **stale** — the widget was destroyed):

| Method | Returns | Backing |
|---|---|---|
| `node:type()` | class simple name, e.g. `"Inventory"`, `"Label"`, `"Button"` | `getClass().getSimpleName()` |
| `node:id()` | server widget id (int), or **`nil` if not server-bound** | [`Widget.wdgid()`](src/haven/Widget.java:560) (`-1`→`nil`) |
| `node:children()` | array of child `WidgetNode`s, in tree order (empty if leaf) | [`Widget.children()`](src/haven/Widget.java:1742) |
| `node:parent()` | parent `WidgetNode`, or `nil` at the root | `Widget.parent` |
| `node:pos()` | `{x=,y=}` — position within the parent (widget-local px) | `Widget.c` |
| `node:size()` | `{x=,y=}` | `Widget.sz` |
| `node:visible()` | boolean | `Widget.visible()` |
| `node:text()` | best-effort text for text-bearing widgets, else `nil` | localized `nodeText(Widget)` switch |
| `node:walk(fn)` | depth-first visit — `fn(node, depth)`; return `false` to prune the subtree | pure recursion over `children()` |
| `node:same(other)` | `true` iff both handles wrap the **same live widget** (nil-safe: `:same(nil)` → `false`) | reference identity of the wrapped `Widget` |

`node:same(other)` is the **identity primitive**: because each `hafen.ui.node`/`:at` call mints a
*fresh* handle, and because client-only children have **no `:id()`** to compare, `:same` is the only
reliable "is this the same widget as before?" check — the per-frame efficiency guard the `widgetstack`
addon relies on (§W2). It compares the underlying Java widget by reference (a stale node is never
`:same` as a live one).

`node:text()` is the one **upstream-volatile** bit: it reads the text out of a *known set* of widget
types ([`Label.texts`](src/haven/Label.java:34), button captions, `TextEntry`, checkbox labels, …).
Like the [14](14-widget-tree-reads.md) adapters, that fragile knowledge is **localized in one Java
method** — upstream churn breaks that one switch, not addons; an unknown type simply returns `nil`.

### Acting on a node — reuse the existing gated tier (no new action surface)

This series is **read-only**. To *act*, read the node's `:id()` and pass it to the existing gated
[`hafen.act.raw(id, msg, …)`](../the API reference (docs/addons/api/)) ([D-025](../decisions/actions-permissions.md)):

```lua
local win  = hafen.ui.root():find(function(n) return n:type() == "Window" and n:text() == "Barter Stand" end)
local buy  = -- the server-bound stall/box node you learned from the upstream class
if buy:id() then hafen.act.raw(buy:id(), "buy", index) end   -- gated by the "actions" permission
```

No new decision or gate is introduced here: reading the tree is ungated client-side data; **sending
the `wdgmsg` stays in the Phase-4 actions tier** exactly as before. `:find`/`:collect` are one-liners
over `:walk` an addon can keep in its own Lua (kept out of the Java surface to stay minimal, D-012).

## What it powers

- **Pure-Lua window adapters.** A maintainer reads the upstream widget class once, then writes
  `barterstand.products()/price()/buy()` (or any window) as a Lua module over `:children()`/`:text()`
  + `hafen.act.raw`. **No Java per window.**
- **A live inspector.** Dump any open window's tree from the `:lua` REPL (or a future `:inspect`
  helper) to discover structure without decompiling `.res` or reading Java.
- **Surgical view edits over `replace`.** Combined with [08](08-widget-replacement.md), an addon can
  locate a specific child and hide/redraw around it instead of the whole window.

## W2 — Hit-testing: the WoW `/framestack` enabler ([D-042](../decisions/widgets-ui.md))

WoW's `/framestack` lets you hover **any** UI element and see the tree of frames under the cursor.
W1 already gives the tree + the `:parent()` walk to build the stack; W2 adds the **two small reads**
that let an addon find *what is under the cursor* — the only missing pieces.

**`onMouseOver` is the wrong tool** (and is deliberately **not** added). A hover callback on a
[`LuaWidget`](src/io/brodgar/addon/LuaWidget.java) fires only for the addon's **own** widgets;
`/framestack` must inspect **arbitrary native** widgets. WoW itself doesn't hook per-frame hover for
this — it **hit-tests the mouse position against the whole tree**. So the primitive is a
**coordinate hit-test**, not a per-widget event.

### The two reads

```lua
hafen.ui.mouse()        -- { x=, y= } — the cursor in root coordinates
hafen.ui.at(x, y)       -- the DEEPEST WidgetNode under that point, or nil
node:at(coord)          -- same, but hit-testing only within this node's subtree
node:rootpos()          -- { x=, y= } — the node's top-left in root coords (for a highlight box)
```

- **`hafen.ui.mouse()`** — reads the engine's public [`UI.mc`](src/haven/UI.java:54) (the pointer
  position in root coords). Zero-cost.
- **`hafen.ui.at(x, y)` / `node:at(coord)`** — the faithful hit-test. It **mirrors the engine's own
  pointer dispatch** [`PointerEvent.propagation`](src/haven/Widget.java:981): iterate children
  `lchild → prev` (**topmost-first** — last child is drawn on top), **skip `!visible()`**, descend by
  `parent.xlate(child.c, true)` + a rectangle intersect, and at the leaf honour
  [`checkhit(c)`](src/haven/Widget.java:794) (so a widget with a **non-rectangular** hit area, or a
  **scrolled** container via `xlate`, resolves exactly as a real click would). It returns the deepest
  hit as a `WidgetNode`; the addon walks `:parent()` up for the full stack.

  > **Why mirror the engine, not a naïve rect test:** a plain "is the cursor inside `pos..pos+size`?"
  > gives *wrong* answers inside scrolled lists (Scrollport offsets via `xlate`) and for custom hit
  > shapes (`checkhit` overrides). Reusing the engine's walk makes `at()` report the widget that would
  > actually receive the click — the whole point of `/framestack`.
- **`node:rootpos()`** — [`Widget.rootpos()`](src/haven/Widget.java:496), the node's top-left in root
  coords; with `:size()` it gives the rectangle to draw a **highlight box** over the hovered widget
  (like WoW's green outline).

### The `widgetstack` addon (W2 ships it — pure Lua)

This series' one example addon (**`addons/widgetstack/`**) is the `/framestack` clone itself: a dev
tool that, while active, shows the tree of widgets under the cursor in a floating window and outlines
the hovered widget. It runs on **`OnUpdate(dt)`** (the engine tick pump,
[09](09-events-catalog.md)) — the WoW-`OnUpdate` analog.

**The efficiency guard (the point of `:same`).** `OnUpdate` fires every frame, but the hovered widget
changes only when the mouse moves onto a *different* one. So the addon caches the last hovered leaf
and **bails early when it hasn't changed** — no re-walk, no window rebuild, per frame:

```lua
local state = { last = nil }        -- the leaf we last built the stack for

hafen.events.on("OnUpdate", function(dt)
  local m    = hafen.ui.mouse()
  local leaf = hafen.ui.at(m.x, m.y)          -- deepest widget under the cursor (or nil)

  -- GUARD: same widget as last frame? → continue (skip the rebuild entirely)
  if leaf and state.last and leaf:same(state.last) then return end
  if not leaf and not state.last then return end     -- still hovering nothing
  state.last = leaf                                    -- hover changed → remember it

  -- (only reached on a CHANGE) rebuild the stack: leaf → root
  local stack, n = {}, leaf
  while n do stack[#stack+1] = n; n = n:parent() end
  widgetstack_window:setStack(stack)                  -- update the display model + mark dirty
end)

-- the window's onDraw renders `stack` (indent = depth: type / #id / 'text' / WxH per level)
-- and outlines the hovered leaf: local p, s = leaf:rootpos(), leaf:size()
```

So the expensive work (tree walk + window relayout) happens **once per hover change**, not once per
frame — exactly the `continue`-when-unchanged pattern requested. The only per-frame cost is
`mouse()` + `at()` + one `:same()` compare, all cheap.

**Hover = polling `ui.mc`** on `OnUpdate` — no new pointer event, **no core edit**. A generic
native-widget `onMouseOver` would require hooking the engine's pointer-dispatch path (more invasive)
and buys nothing over the poll here — so it is **out of scope** ([D-042](../decisions/widgets-ui.md)). Nice-to-haves
for the addon (not new API): a slash toggle (`:widgetstack` via `hafen.slash`) to show/hide, and an
optional **freeze** key (`hafen.hook.grab`/a hotkey) so the stack holds still while you move the mouse
into the window to read it.

### Scope

Read-only, **ungated**, **zero `haven` core edit** — every backing is public (`UI.mc`, `Widget.xlate`/
`checkhit`/`visible()`/`rootpos()`, the `lchild/prev` walk mirrored in the bridge). `at()`/`mouse()`/
`rootpos()` return `nil` gracefully when nothing is hit or the node is stale.

## Ownership, lifetime & threading

- **Transient, not owned-registry entries.** Unlike models/ghosts/widgets, a `WidgetNode` is **not**
  registered per-node in the addon's owned-resource registry ([P2](01-architecture.md)) — a deep tree
  would mint thousands of nodes per inspection. It is a **`GobRef`-style lazy handle**: cheap to make,
  it borrows the engine-owned widget and **checks liveness on every access**. Once it detects the
  widget was destroyed, it **nulls its internal reference** (so a stashed node can't pin a dead
  subtree in memory) and all accessors return `nil`/empty. No teardown hook, no leak.
- **Facade-safe.** The `haven.Widget` stays inside Java; Lua holds only the opaque handle
  ([P1](01-architecture.md)/[D-017](../decisions/security-sandbox.md)). No reflection reaches Lua.
- **Threading.** All reads run on the **UI thread under the `ui` monitor** (same discipline as
  [`LuaModel`](src/io/brodgar/addon/LuaModel.java) and the [14](14-widget-tree-reads.md) adapters);
  `children()` is copied under `synchronized(ui)` before the array is handed to Lua, so a node walk
  never races tree mutation.

## Scope & honest limits

1. **Read-only.** No mutating the widget's internal Java state (setting a label's text, a value) —
   it desyncs from the server (the source of truth) and is fragile ([D-009](../decisions/widgets-ui.md) spirit).
   Mutation, where legitimate, goes through `hafen.act.raw` on a **server-bound** node.
2. **`:text()` is best-effort** over a known type set, localized in one switch — an unknown widget
   returns `nil`, never throws.
3. **Whole-tree reachable.** `hafen.ui.root()` + `:parent()` expose the entire client widget tree
   (chat, HUD, other windows), not just an adopted subtree. This is consistent with the **ungated**
   read API — it is all client-side data that never reaches the server. Actions remain separately
   gated.
4. **Structure is upstream-defined.** Which child is the price vs. a spacer is knowledge the addon
   author supplies (from the upstream class); the API gives you the tree, not the semantics. Upstream
   refactors can move children — the addon's Lua adapter is the maintenance surface (as with the
   Java adapters, [14](14-widget-tree-reads.md)).
5. **Client-only nodes have no `:id()`** → they are readable but **not** directly actionable; act on
   their nearest server-bound ancestor with the right message (see above).

## Verification

**W1 ships NO example addon** (maintainer's request) — a deviation from the standard per-task
procedure (/implement): verify from the **`:lua` REPL** in-game. Open a real server window
(e.g. a Barter Stand), then walk and dump its tree:

```lua
hafen.ui.root():walk(function(n, d)
  hafen.log(string.rep("  ", d) .. n:type()
    .. (n:id()   and (" #" .. n:id())   or "")
    .. (n:text() and (" '" .. n:text() .. "'") or ""))
end)
```

**W1 DoD:** the dump shows the window's full nested structure with `type`/`id`/`text` per node;
server-bound nodes report an `:id()` and client-only ones report `nil`; a node captured before the
window closes reports stale (`nil`) accessors afterward; `:reload` leaks nothing (no owned-registry
growth, since nodes aren't registered).

**W2 ships the `widgetstack` example addon** (the `/framestack` clone above) — the standard in-game
harness for this slice (it exercises `mouse`/`at`/`rootpos`/`same` **and** W1's `parent`/`type`/
`text`). **W2 DoD:** with `widgetstack` active, hovering the UI shows the live stack of widgets under
the cursor in its window (type / #id / 'text' / WxH per level) and outlines the hovered widget;
hovering an item in a **scrolled** inventory resolves the one a click would hit; the display rebuilds
**only when the hovered widget changes** (the `:same` guard — verifiable by a per-rebuild log counter
that does *not* tick every frame while the cursor sits still); `:reload` leaks nothing.
