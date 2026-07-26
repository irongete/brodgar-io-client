# W1 — `hafen.ui.root()`/`node(id)` + the `WidgetNode` handle

> **Status:** ✅ Implemented; compile (`ant hafen-client` → BUILD SUCCESSFUL) + **7 headless facade-safe
> round-trip checks** (`LuaWidgetNode.resolve`) + LuaJ parse of all addons. **In-game verified ✅** —
> `hafen.ui.root():walk(...)` dumped the full client tree incl. an open **Barter Stand** (`Window #79` →
> `Shopbox #80..84` + `Button #85 'Break ownership'`); server-bound nodes reported `:id()`, client-only
> ones `nil`. The first run surfaced blank types for anonymous subclasses → `:type()` now climbs to the
> nearest named superclass (below). W1 ships **no example addon** (maintainer's request).
> **Design:** [specs/addons/20-widget-introspection.md](../../specs/addons/20-widget-introspection.md),
> [specs/addons/api-reference.md](../../specs/addons/api-reference.md) (`hafen.ui`),
> [specs/addons/code-map.md](../../specs/addons/code-map.md), decision **D-041** (the `WidgetNode` tree),
> plus **D-012** (one canonical way) / **D-017** (facade-safe) / **D-025** (`hafen.act.raw`, the act path).

The first slice of the **W-series** (maintainer-requested widget introspection): a **generic, read-only**
way to walk *any* server widget's children to arbitrary depth from Lua. Where `adopt`/`replace` (Phase 3)
target one known widget and the [spec-14](../../specs/addons/14-widget-tree-reads.md) typed adapters expose a
fixed set of surfaces, W1 lets a maintainer who has read the upstream widget class build a window adapter
(`barterstand.products()/price()/buy()`, or any UI) **in pure Lua** — never a new Java adapter per window.
**Zero `haven` core edit** — every backing is already public.

## The surface

Two entry points (two distinct inputs, D-012) + `model:node()` sugar:

```lua
hafen.ui.root()        -- WidgetNode for ui.root — the top of the WHOLE client tree (walk DOWN)
hafen.ui.node(id)      -- WidgetNode for a server widget id (a desc.id / model:raw() / node:id()); nil if unresolved
model:node()           -- sugar for hafen.ui.node(model:raw()) on an adopted model
```

The `WidgetNode` handle: `:type()`, `:id()` (server id, **nil if not server-bound**), `:children()`,
`:parent()`, `:pos()`, `:size()`, `:visible()`, `:text()` (best-effort), `:walk(fn)` (depth-first; return
`false` to prune), `:same(other)` (reference identity — the per-frame guard W2 needs).

## Design points

### Facade-safe, opaque, lazy (D-041 / D-017 / P1)

No raw `haven.Widget` ever crosses into Lua. A node is a Lua handle table of closures over a new Java
[`LuaWidgetNode`](../../src/io/brodgar/addon/LuaWidgetNode.java) that holds the widget **inside Java**. The
handle table also carries the `LuaWidgetNode` as an **opaque userdata** (the `KEY` field) so `:same(other)`
can `resolve` the *other* handle back to its wrapped widget and compare by reference — the same round-trip
[`LuaImage`](../../src/io/brodgar/addon/LuaImage.java)/[`LuaMarshal`](../../src/io/brodgar/addon/LuaMarshal.java)
use (no metatable → no Java method reachable; unforgeable — the sandbox omits `luajava`). **7 headless
checks** verify the round-trip: handle-table → node, raw userdata → node, and nil / foreign table / foreign
userdata / plain string → `null` (can't be forged), plus distinct-node identity.

### Transient, not owned (D-041)

Unlike models/ghosts/widgets, a `WidgetNode` is **not** registered in the addon's owned-resource registry
(P2) — a deep tree would mint thousands of nodes per inspection. It is a **`GobRef`-style lazy handle**:
cheap to make, it borrows the engine-owned widget and **checks liveness on every access** in
`AddonManager.nodeLive`, via [`Widget.hasparent(ui.root)`](../../src/haven/Widget.java:1666) (a destroyed
widget has its `parent` nulled by [`Widget.remove`](../../src/haven/Widget.java:534), so it is no longer
reachable from the root). Once stale, `nodeLive` **nulls the node's `wdg` reference** so a stashed node
can't pin a dead subtree in memory, and every accessor returns `nil`/empty. **No teardown hook, no leak** —
so `:reload` leaks nothing by construction (there is nothing registered to grow).

### `:id()` is the pivot for acting

Reading the tree is ungated client-side data. `:id()` is [`Widget.wdgid()`](../../src/haven/Widget.java:560)
(`-1` → `nil`): a server-bound widget has an id, a client-only sub-widget (most Labels/Buttons) does not.
To *act*, read a **server-bound** node's `:id()` and pass it to the already-gated
[`hafen.act.raw(id, msg, …)`](../../docs/addons/api/actions.md) (D-025) with the message a client-only button
*would have sent* (an unbound sender's `wdgmsg` is dropped by the engine). **No new action surface, no new
gate** here.

### `:type()` — climb past anonymous subclasses

`AddonManager.nodeType(Widget)` is `getClass().getSimpleName()` — but Hafen builds a great many widgets as
**anonymous subclasses** (`new TextEntry(...) {...}`, `new Button(...) {...}`), whose simple name is the empty
string. The first in-game run confirmed this: a hearth-secret field dumped as `'znoG2Syr'` with a blank type,
the inventory window as `'Inventory'`, etc. So for an anonymous/local class we climb to the nearest **named**
superclass (that field → `"TextEntry"`, that window → `"Window"`) — the useful identity for building an adapter.
Falls back to `"?"` only in the impossible no-named-ancestor case.

### `:text()` — the one upstream-volatile bit, localized in one switch

`AddonManager.nodeText(Widget)` reads text out of a *known set* of widget types —
[`Label.texts`](../../src/haven/Label.java:34), [`Button.text`](../../src/haven/Button.java:48) (`Text.text`),
[`Window.cap`](../../src/haven/Window.java:78), [`TextEntry.text()`](../../src/haven/TextEntry.java:211) — all
public. An unknown type returns `null` (→ Lua `nil`), never throws. Like the spec-14 adapters, that fragile
knowledge is confined to this one method: upstream churn breaks it, not addons.

### Threading

Every access runs on the UI thread under the `ui` monitor (same discipline as
[`LuaModel`](../../src/io/brodgar/addon/LuaModel.java) and the spec-14 adapters). `:children()` and `:walk`
copy the child list under `synchronized(ui)` before handing the array to Lua, so a walk never races tree
mutation. `:walk` runs each `fn(node, depth)` isolated + watchdog-armed via `callLua`, reusing the *same*
handle for the current node and minting fresh handles for children (a node that goes stale mid-walk simply
stops descending — its `children()` is empty).

## Exact code changes

- **New [`src/io/brodgar/addon/LuaWidgetNode.java`](../../src/io/brodgar/addon/LuaWidgetNode.java)** — the
  opaque, facade-safe holder: a mutable `wdg` reference (nulled once stale), the `KEY` userdata field, and
  the static `resolve(LuaValue)` (handle-table or raw userdata → node, else `null`).
- **[`src/io/brodgar/addon/AddonManager.java`](../../src/io/brodgar/addon/AddonManager.java):**
  - imports `haven.Button`, `haven.Label`, `haven.Text`, `haven.TextEntry`.
  - `hafen.ui` gains `root()` → `nodeRoot(owner)` and `node(id)` → `nodeById(owner, id)`.
  - `modelHandle` gains `:node()` → `nodeHandle(m.owner, new LuaWidgetNode(m.wdg))`.
  - new W1 section: `nodeRoot`, `nodeById`, `nodeLive` (liveness + ref-nulling), `nodeSame`, `xyTable`
    (`Coord` → `{x=,y=}`), `nodeText` (the type switch), `nodeHandle` (the handle builder — all 10 methods),
    and `nodeWalk` (depth-first with the `false`-prunes contract).

**Zero `haven` core edit** — all backings public
([`Widget.children()`](../../src/haven/Widget.java:1742)/[`wdgid()`](../../src/haven/Widget.java:560)/
`c`/`sz`/[`visible()`](../../src/haven/Widget.java:1961)/`parent`/`hasparent`/`getClass`, the four text
fields, [`UI.getwidget`](../../src/haven/UI.java)/`root`).

## Verification

- `ant hafen-client` → **BUILD SUCCESSFUL** (one pre-existing `URL` deprecation warning in `LuaHttp`).
- **7 headless checks** on `LuaWidgetNode.resolve` (the facade-safe round-trip + forgery rejection).
- **LuaJ parse** of all 7 addon `.lua` files (regression — no addon changed).

**No example addon (maintainer's request)** — a deviation from PLAN §6, spelled out in the spec. Verify
in-game from the `:lua` REPL: open a real server window (e.g. a **Barter Stand**), then
`hafen.ui.root():walk(...)` dumps its full nested tree with `type`/`id`/`text` per node (server-bound nodes
report an `:id()`, client-only ones `nil`); a node captured before the window closes reports stale (`nil`)
accessors afterward; `:reload` leaks nothing.

## Deferred to W2

Hit-testing (`hafen.ui.at`/`mouse` + `node:rootpos`/`node:at`) and the `widgetstack` example addon (the WoW
`/framestack` clone) — the reads that find *what is under the cursor*, built on W1's tree + `:parent()`
walk + the `:same` guard.
