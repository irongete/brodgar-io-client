# Decisions — Widgets, UI & interception

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-009 — Widget replacement is "wrap, don't reimplement" ✅
**Decision.** To replace native UI, keep the real server-bound widget as a hidden **model** and
present a custom **view**, delegating interactions to the real widget. Do not reimplement the
server protocol.
**Rationale.** Protocol fidelity for free; robust against content/version differences. Verified
that hidden widgets still receive `uimsg`/`addchild` (delivery is by id, not visibility).
**See.** [08-widget-replacement.md](../design/08-widget-replacement.md).

### D-021 — Hook priority: integer priority, first-preventDefault-wins ✅ (closes Q-012)
Handlers carry an optional integer `priority` (default 0), registration-order tiebreak. First
`preventDefault` skips the default; later pre-hooks still run unless one calls `stopPropagation`;
post-hooks all run. Warn when two addons both `preventDefault` the same point. See
[13-hooks-and-interception.md](../design/13-hooks-and-interception.md).

### D-024 — Widget-replacement targeting via a descriptor ✅ (closes Q-008)
The replace handler receives a **widget descriptor** `{id, type, place, caption, parentType}`
(place-string + caption + parent type) so the addon decides per-instance which window it is
(main inventory vs a cupboard). See [08-widget-replacement.md](../design/08-widget-replacement.md).

### D-038 — Addon widgets receive drops via an `onDrop` callback ✅ (maintainer, 2026-07-26)
**Decision.** `hafen.ui.widget`/`hafen.ui.window` gain an optional `onDrop = fn(x, y, drop)` callback, and
[`LuaWidget`](src/io/brodgar/addon/LuaWidget.java) **implements [`haven.DropTarget`](src/haven/DropTarget.java)**.
When the client's drag-drop machinery drops a "thing" over the widget, the bridge forwards it to Lua as a
**neutral descriptor** in widget-local pixels; returning **truthy consumes** the drop (like the other input
callbacks). v1 handles the **`dropthing` path only**, whose "thing" is a
[`MenuGrid.Pagina`](src/haven/MenuGrid.java:63) (a menu-grid action) — delivered as
`{ kind = "pagina", res = "<resource name>" }`. The engine already dispatches menu-grid drops generically —
[`MenuGrid.mouseup`](src/haven/MenuGrid.java:588) calls `DropTarget.dropthing(ui.root, ui.mc, dragging)`, and the
`Drop` event walks the widget tree via `PointerEvent.propagation`, calling `dropthing` on the first `DropTarget`
under the cursor — so a bridge widget receives paginae with **zero `haven` core edit**.
**Rationale.** Lets addons build **drop targets** (custom action bars, ability shelves, quick-slots) reusing the
game's own drag gesture, with no new UI verbs to learn. A **neutral descriptor** (not a live Java object) keeps it
**ungated** — a resource name is plain data, already exposed all over the read API
([`hafen.actionbar.slot`](../API-REFERENCE.md) etc.) — and forward-compatible: a later menu-ability primitive can also
hand the addon a resolvable handle without changing the callback shape.
**Scope / limits.**
- v1 = **menu-grid paginae** only. Item drag-drop uses the separate `DTarget.drop(Coord, Coord)` / `iteminteract`
  path, **not** `dropthing` — out of scope (a later slice may add item drops).
- **No activation.** `onDrop` gives you *what* was dropped, not the power to *fire* it. Firing a dropped action
  needs the deferred **menu-ability primitive** (a resolvable ability handle + a gated `use`) — planned separately.
- **id-only paginae** (server-`id`, non-`Indir` — the `fl&2` case in
  [`MenuGrid.uimsg`](src/haven/MenuGrid.java:615)) have **no stable resource name**, so the descriptor carries
  `kind` only (`res` absent); such a drop is usable in-session but **not reliably persistable** across relogs. Most
  real abilities are resource-based, so this is a corner case.
**Alternatives.** Hand Lua the live `Pagina` object — rejected (leaks a mutable Java handle into the sandbox, and
conflates "what was dropped" with "the ability primitive" that is deliberately deferred). A dedicated
`hafen.ui.dropTarget(...)` widget type — rejected (a callback on the existing widget is the one canonical way, D-012).
**Consequences.** Additive in `io.brodgar.addon`: `LuaWidget implements DropTarget` + read `onDrop` from opts + a
`Pagina`→descriptor marshal; `newUi` passes opts through unchanged. **Zero `haven` core edit.** Ungated.
**See.** [07-ui-and-drawing.md](../design/07-ui-and-drawing.md), [D-040](widgets-ui.md) (widget `mods`), [D-039](widgets-ui.md)
(`g:resource`, the icon you draw from the dropped `res`), the deferred menu-ability primitive.

### D-039 — `g:resource(name, x, y[, w, h])` draws an engine `.res` image in the draw wrapper ✅ (maintainer, 2026-07-26)
**Decision.** Add **`g:resource(name, x, y [, w, h])`** to the [`LuaGOut`](src/io/brodgar/addon/LuaGOut.java) draw
wrapper — blit an **engine resource's default image layer** (the `.res` icon) by **resource name**, optionally
scaled into a `w × h` box. It is the sibling of `g:image`: `g:image` draws the addon's **own** PNGs (via
`hafen.render.image`, D-034), whereas `g:resource` draws the **client's own `.res` art** (action icons, hud pieces,
…). The name is resolved **asynchronously and cached** (one `Indir<Resource>` per name via
[`Resource.remote().load(name)`](src/haven/Resource.java:866)); a draw is **`Loading`-guarded** — it draws nothing
until the texture is ready, then blits from cache (the client's own idiom — cf.
[`MenuGrid.draw`](src/haven/MenuGrid.java:461) swallowing `Loading`). Realizes the original spec-07 surface line
`g:image(res, …)` "draw a Tex/resource image".
**Rationale.** Addons need to render **real game icons** to match native UI — e.g. draw the icon of an action whose
`res` name arrived via [`onDrop`](DECISIONS.md#d-038). A single **by-name** primitive is more canonical (D-012, "one
way") than a family of special-case verbs (`g:slot`, `g:ability`, …).
**Scope / limits.**
- Draws the **static default image layer** (`Resource.imgc`), **not** a live [`GSprite`](src/haven/GSprite.java): no
  animation and **no cooldown sweep / info overlays**. The rich per-ability draw (icon + `PagButton.meter` wedge +
  overlays, à la [`PagBeltSlot.draw`](src/haven/GameUI.java:134)) belongs to the deferred **menu-ability primitive**
  (a future `g:ability`), not here.
- The **native empty-slot square** [`Inventory.invsq`](src/haven/Inventory.java:34) is a **code-built `TexI`**, not a
  plain `.res`, so it is **not** reachable through `g:resource`; an addon draws its own slot background (a
  `g:frect`/`g:rect`), or a small dedicated helper may expose it later. `g:resource` covers any real `.res`.
**Alternatives.** Special-case verbs `g:slot`/`g:ability` (my first sketch) — rejected in favour of one generic
by-name verb (D-012). A blocking `Resource.loadtex(name)` in the draw — rejected (would stall the render thread; the
async-cache + `Loading`-guard is the client's own idiom).
**Consequences.** Additive in `LuaGOut` + a tiny name→`Indir`/`Tex` cache on the bridge (bridge-owned, dropped on
reload/disable). **Ungated** — client-side pixels that never reach the server, exactly like `g:image` (D-034).
**Zero `haven` core edit** (`Resource.remote().load` + the image layer + `GOut.image` are all public).
**See.** [07-ui-and-drawing.md](../design/07-ui-and-drawing.md) §"The GOut drawing wrapper",
[17-custom-rendering.md](../design/17-custom-rendering.md), [D-034](rendering.md), [D-038](widgets-ui.md).

### D-040 — Widget mouse callbacks carry a `mods` table ✅ (maintainer, 2026-07-26)
**Decision.** [`LuaWidget`](src/io/brodgar/addon/LuaWidget.java) passes a trailing **`mods = {shift, ctrl, alt}`**
table (booleans, from `ui.modflags()`) as the **last argument** of its mouse callbacks:
`onClick(x, y, button, mods)`, `onMouseUp(x, y, button, mods)`, and — for consistency —
`onMouseMove(x, y, mods)` / `onWheel(x, y, amount, mods)`. **Additive and back-compatible**: existing handlers that
ignore the extra argument are unaffected.
**Rationale.** An addon must know the modifier state **at press time** to branch on it — e.g. **Shift+drag to move**
a bar vs. a plain click to use a slot — but today the callbacks pass only `(x, y, button)`, so the modifier is
invisible until too late. Reuses the existing `modsTable(ui.modflags())` helper and **mirrors** the `mods` already
delivered by `hafen.hook.grab` (`{shift, ctrl, alt}`), so one modifier shape is used everywhere (D-012).
**Alternatives.** A separate `hafen.ui.mods()` global reader — rejected (racy vs. the event, and a second way to read
the same thing). A raw integer bitfield — rejected (the boolean table matches `hafen.hook.grab`).
**Consequences.** Additive in `LuaWidget` (build the table from `ui.modflags()` at each mouse forward).
**Ungated. Zero `haven` core edit.**
**See.** [07-ui-and-drawing.md](../design/07-ui-and-drawing.md) §Input, [13-hooks-and-interception.md](../design/13-hooks-and-interception.md)
(`hafen.hook.grab` `mods`), [D-038](widgets-ui.md).

### D-041 — Generic read-only widget-tree introspection via opaque `WidgetNode` handles ✅ (maintainer, 2026-07-26)
**Decision.** Add a **generic, read-only** way to walk *any* widget's children to arbitrary depth (the
**W-series**, spec [20](../design/20-widget-introspection.md)). Two entry points return an opaque, stale-graceful
**`WidgetNode`**: `hafen.ui.root()` (the top of the whole client tree) and `hafen.ui.node(id)` (by server widget
id — a `desc.id`, a model's `:raw()`, or another node's `:id()`; `nil` if unresolved). Each node exposes
`:type()` (class simple name), **`:id()` (server id, or `nil` when the widget is not server-bound)**, `:children()`
(array of child nodes, tree order), `:parent()`, `:pos()`, `:size()`, `:visible()`, `:text()` (best-effort, one
localized switch), `:walk(fn)` (depth-first), and **`:same(other)`** (reference identity — `true` iff both handles
wrap the same live widget; nil-safe). `:same` is the identity primitive: each `node`/`at` call mints a *fresh* handle
and client-only children have no `:id()`, so it is the only reliable "same widget as before?" check (the per-frame
guard `widgetstack` needs, [D-042](widgets-ui.md)). **Read-only and facade-safe** — no `haven.Widget` crosses into
Lua ([D-017](security-sandbox.md)); the node is a Lua handle over a Java `LuaWidgetNode` that holds the widget inside Java
and resolves lazily.
**Rationale.** Lets an addon **discover any window's structure from Lua** — no `.res` decompiling, no per-window
Java. The maintainer reads the upstream widget class once, then writes the adapter (`barterstand.products()`/
`price()`/`buy()`, or any window) **in pure Lua** over `:children()`/`:text()` + `hafen.act.raw`. Complements the
[14](../design/14-widget-tree-reads.md) typed adapters (which stay for hot/event-driven reads and private-field reflection);
this is the generic, on-demand, **public-structure** reader. **`:id()` is the pivot:** it is what makes a node
actionable — a `wdgmsg` from an unbound sender is dropped ([UI.java:681](src/haven/UI.java:681)), so you act on the
**server-bound** node with the message a client-only button would have sent, not on the button.
**Scope / limits.**
- **Read-only.** No mutation of widget internals (fragile, desyncs from the server — [D-009](widgets-ui.md) spirit);
  legitimate mutation goes through the gated `hafen.act.raw` on a server-bound node. **No new gate here** — reading
  the tree is ungated client-side data; sending `wdgmsg` stays in the Phase-4 actions tier.
- **`:text()`** is best-effort over a known type set ([`Label.texts`](src/haven/Label.java:34), button captions,
  `TextEntry`, …), localized in one Java method; an unknown type returns `nil`, never throws.
- **Whole-tree reachable** via `root()`/`:parent()` — consistent with the ungated read API (all client-side, never
  reaches the server).
- Nodes are **transient** (not owned-registry entries): a `GobRef`-style lazy handle that checks liveness per access
  and nulls its ref once the widget dies (no pinning, no teardown hook, no leak).
**Alternatives.** A Java adapter per window (rejected as the default — repeats Java per UI; this reader lets adapters
be pure Lua). Handing Lua the raw `Widget` (rejected — [D-017](security-sandbox.md)). A mutating node API (rejected —
fragile; mutation belongs to the gated action tier at most). Registering every node for teardown (rejected — a deep
tree mints thousands; borrow-and-check-liveness matches `GobRef`).
**Consequences.** Additive in `io.brodgar.addon` (a new `LuaWidgetNode` + the `hafen.ui.root`/`hafen.ui.node`
globals + `model:node()` sugar); all backings public ([`Widget.children()`](src/haven/Widget.java:1742)/
[`wdgid()`](src/haven/Widget.java:560)/`c`/`sz`/`visible()`/`getClass`, [`Label.texts`](src/haven/Label.java:34)) →
**zero `haven` core edit. Ungated.**
**See.** [20-widget-introspection.md](../design/20-widget-introspection.md), [08-widget-replacement.md](../design/08-widget-replacement.md)
(adopt/replace — the wrap model), [14-widget-tree-reads.md](../design/14-widget-tree-reads.md) (typed adapters — the other
half), [D-025](actions-permissions.md) (`hafen.act.raw`).

### D-042 — Coordinate hit-testing (`hafen.ui.at` + `hafen.ui.mouse` + `node:rootpos`) — the `/framestack` enabler ✅ (maintainer, 2026-07-26)
**Decision.** Extend the W-series (**W2**, spec [20](../design/20-widget-introspection.md)) with the two reads that let an addon
find *what widget is under the cursor* — a WoW-`/framestack` clone: **`hafen.ui.mouse()`** (→ `{x,y}`, the cursor in
root coords, from public [`UI.mc`](src/haven/UI.java:54)); **`hafen.ui.at(x, y)`** and **`node:at(coord)`** (→ the
**deepest** [`WidgetNode`](DECISIONS.md#d-041) under a point, or `nil`); and **`node:rootpos()`** (→ `{x,y}` top-left
in root coords, from public [`Widget.rootpos()`](src/haven/Widget.java:496), for a highlight box). `at()` **mirrors the
engine's own pointer dispatch** [`PointerEvent.propagation`](src/haven/Widget.java:981): children walked `lchild→prev`
(**topmost-first**), `!visible()` skipped, descend by `parent.xlate(child.c,true)` + rect-intersect, and honour
[`checkhit`](src/haven/Widget.java:794) at the leaf — so it returns exactly the widget a real click would hit.
**Rationale.** W1 gives the tree + `:parent()` walk (the stack); the only missing piece for `/framestack` is *finding
the leaf under the mouse*. **`onMouseOver` is the wrong tool and is deliberately NOT added:** a hover callback on a
[`LuaWidget`](src/io/brodgar/addon/LuaWidget.java) fires only for the addon's **own** widgets, whereas `/framestack`
inspects **arbitrary native** widgets — WoW itself hit-tests the cursor against the whole tree rather than hooking
per-frame hover. Hover is then just **polling `ui.mc`** each tick (optionally gated behind a `hafen.hook.grab`/hotkey
toggle) — no new event needed.
**Scope / limits.**
- **Faithful, not naïve:** `at()` must reuse the engine walk (`xlate` for scroll offsets, `checkhit` for
  non-rectangular hit areas), or it mis-resolves inside scrolled lists / custom hit shapes. A plain
  `pos..pos+size` rect test is rejected.
- **Read-only, ungated.** Reading cursor + geometry is client-side data; nothing reaches the server. (Acting on the
  resolved node still goes through the gated `hafen.act.raw` on its `:id()`, [D-041](widgets-ui.md).)
- **No generic native `onMouseOver`.** That would require hooking the engine's pointer-dispatch path (more invasive)
  and adds nothing over the poll here — deferred unless a concrete need appears.
**Alternatives.** A per-widget `onMouseOver` event on native widgets (rejected — invasive pointer-dispatch hook, and
useless for *arbitrary* widgets). A naïve rectangle hit-test (rejected — wrong under scroll/`checkhit`). Exposing raw
`ui.mc`/`Widget` objects (rejected — [D-017](security-sandbox.md); the facade returns plain tables + `WidgetNode` handles).
**Example addon (`widgetstack`).** W2 ships **`addons/widgetstack/`** — the `/framestack` clone: on `OnUpdate(dt)` it
reads `hafen.ui.mouse()` + `hafen.ui.at(...)`, walks `:parent()` to the root, and shows the stack (type/#id/'text'/WxH
per level) in a window + outlines the hovered widget via `:rootpos()`+`:size()`. **Efficiency guard (maintainer
requirement):** it caches the last hovered leaf and **bails early** (`if leaf:same(last) then return`) so the tree
walk + window rebuild happen **once per hover change, not per frame** — `:same` ([D-041](widgets-ui.md)) is what makes
that guard possible (client-only leaves have no `:id()`). This addon is the in-game harness for W2 (and re-exercises
W1). *(A slash toggle + an optional `hafen.hook.grab` freeze key are addon-side niceties, not new API.)*
**Consequences.** Additive in `io.brodgar.addon` (`hafen.ui.mouse`/`hafen.ui.at` globals + `LuaWidgetNode.at`/
`rootpos`/`same`); backings all public (`UI.mc`, `Widget.xlate`/`checkhit`/`visible()`/`rootpos()` + reference identity
+ the `lchild/prev` walk mirrored in the bridge) → **zero `haven` core edit. Ungated.**
**See.** [20-widget-introspection.md](../design/20-widget-introspection.md) §W2, [D-041](widgets-ui.md) (the `WidgetNode` +
`:same` it extends), [09-events-catalog.md](../design/09-events-catalog.md) (`OnUpdate`), [07-ui-and-drawing.md](../design/07-ui-and-drawing.md)
(`hafen.hook.grab` for a freeze toggle).
