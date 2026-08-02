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

### D-067 — a classifier answers only where the widget IS the thing; a render-site name classifies nothing

**Context.** `030-ui-selectors` needed a *role* per widget (`hafen.ui("inventory")`), and the obvious source of names
was `Fonts.SCOPES` — reuse the vocabulary `016-fonts` established rather than invent a second one. But fonts resolve
*scope → override* **at each render site**, a lookup a site performs on itself; a selector must answer the **inverse**
— *what role is THIS widget?* — and nothing in `haven` answers that. The classifier is new code, and it is the only
place where the mapping can be wrong.

**Decision.** One `instanceof` chain in ONE method ([`LuaWidget.role`](src/io/brodgar/addon/LuaWidget.java)), the
`typeName`/`text` discipline for fragile upstream knowledge, and the rule that decides every borderline case:
**prefer no answer to a wrong one.** A role is claimed only where the class genuinely *is* that thing
(`Inventory`/`Equipory` → `inventory`, every `Window` → `window`, …); anything unrecognised answers `nil`.

The consequence is that the promotion from `Fonts.SCOPES` is **not** 1:1, and the gap is not an oversight:
`window.title`, `heading`, `tooltip`, `world.nick` and `world.speech` name a **render site**, not a widget — a
caption belongs to `Window.Deco`, a tooltip is painted rather than placed, the world scopes live over the 3D view.
They stay **valid grammar** (one vocabulary shared with fonts; coverage can grow) and match nothing. Guessing that a
`Label` inside a `CharWnd` is a `heading` would be exactly the wrong answer this rule forbids.

**Measured.** Over a live HUD, 174 of 625 widgets classify (window 9 · inventory 5 · button 114 · label 33 ·
textentry 7 · chat 5 · menu 1); 451 answer `nil`. That ratio is the rule working — layout containers, scroll ports
and item icons are reached by `*`, `@Class` or `[res=]`, which is what those exist for.

**Consequences.** The role set grows by adding to one method, and an addon written against a role never breaks when
coverage grows; what must never happen is a role that starts matching a *different* widget. Upstream churn breaks
that one method, not addons. The same discipline governs `resName` (three real sources, `nil` otherwise).

**See.** [22 · the selector grammar](../030-ui-selectors/spec.md), [D-061](architecture-api.md) (the API's vocabulary
comes from the engine), [D-063](architecture-api.md) (an entity's key is what the engine publishes),
[21-fonts.md](../design/21-fonts.md) (the scope table this inverts).

### D-068 — a discovery primitive answers for what is already there, not only for what happens next

**Context.** `hafen.ui.onWidgetCreate(fn)` was a **creation feed**: it fired from the widget-placement seam, for
widgets placed from that moment on. That is the correct shape for an event and the wrong shape for the question
addons actually ask — *"give me the cupboard"* — because the most common moment an addon starts asking is a
`:reload`, and a `:reload` recreates nothing. Every window already open was invisible to the addon that had just
been edited to look for it. `hello` carried a comment apologising for exactly this, and `replace` had already been
forced to work around it with a one-off scan at registration ([`scanForReplace`](src/io/brodgar/addon/UiApi.java)).

**Decision.** `hafen.ui.on(selector, "appear"|"disappear", fn)` (030.2) makes that scan **the rule, not a
workaround**: registering walks the live tree once and fires `appear` for every current match, inside the
registration call. "Appear" therefore means *"is in the tree, as of now"* rather than *"entered the tree while you
were watching"* — a state, reached either way, not an edge. `disappear` records those same matches silently, which
is what lets a window opened before the subscription still report its close.

**Consequences.** An addon never writes the "was it already there?" branch, which was the single most repeated
piece of boilerplate in the old descriptor idiom — `hello`'s inventory handoff is now nine lines with no fallback
door, and it survives `:reload`. The cost is one tree walk per subscription, paid once at registration, against a
per-tick diff of the whole tree (the discarded alternative) — and it is the same walk `hafen.ui.all` already does.
The rule generalises to anything else that hands out live entities: **B3**'s replacement, **C**'s stylesheet and
**E**'s layout all address a tree that exists before the addon does. What a discovery primitive must never do is
make the addon responsible for the difference.

**See.** [D-024](#d-024) (the descriptor this replaces), [D-012](architecture-api.md) (one entry point per distinct
input), [D-056](architecture-api.md) (arity is the verb), [`030-ui-selectors/plan.md`](../030-ui-selectors/plan.md)
§5, [learnings/ui-widgets.md](../learnings/ui-widgets.md) (why `disappear` fires at the server destroy).

### D-069 — an addon's hide is authoritative: hiding a native window takes that window's toggle

**Context.** `bags` replaces the inventory, and the stock one came back on Tab and sat on top of it — the felt bug
`031-window-lifecycle` exists for. The cause is not in `bags`: `MenuCheckBox` calls `setgkey`, so the key fires the
button's own click, and both land in the private [`GameUI.togglewnd`](src/haven/GameUI.java:1482), which flips
`visible` on the very window the addon hid. **An addon's `w:hide()` was a suggestion the client overruled one
keypress later.** Seven call sites, one private method, and its tick read by the equally private `wndstate`.

**Decision.** Hiding a native window **takes its toggle**, and 029's restore list is the ownership record — no new
list, no new state. `togglewnd` and `wndstate` each gain one `// addon:` line *inside the body* asking
[`AddonWidgets`](src/haven/AddonWidgets.java) whether an addon owns the window; ownership is matched by **widget
identity** against the CURRENT owners only (`AddonManager.addons` + the `:lua` REPL). With nothing standing in for
the window the toggle is **swallowed** and the tick answers `false` — the honest report of what is on screen.
**There is no verb**: ownership follows the hide, and it is released by the same restore (`w:show()`, disable,
`:reload`). Rejected: a public `w:onToggle(fn)` (API for something nothing has to ask for), an engine-side
open/closed boolean per owned window (two states that drift, and the checkbox is what lies when they do),
rebinding `kb_inv` from Lua (the button bypasses the keybinding entirely, and it would edit the user's own config
to fix a window problem), and hooking `Window.show()` (far broader, and it fights every internal `show()` the
client makes for its own reasons).

**Consequences.** The generic rule this settles is **an addon's write to a native widget must survive the client's
own reflexes, or it is not a write at all** — and the cheapest way to make it survive is to ask at the *one* place
the reflex funnels through, rather than to fight it at seven. Cost is on the frame path (`ACheckBox.state` is a
per-frame `Supplier` on six checkboxes), so the lookup is a global-empty volatile read plus indexed walks that
allocate nothing; a client with no addon behaves byte-for-byte as before. Because ownership is derived from the
hide record rather than stored, a stale owner cannot eat the key — a disabled addon, a relog and 030's fading
corpse all fall through to stock behaviour, a dead Tab being strictly worse than a stock Tab. What this does NOT
do is take the *key*: `kb_inv` still fires its normal click, which now asks the window's owner. The half left
open — giving the swallowed toggle something to **drive** — is `replace`'s, since it is the only place that knows
both the window it hid and the view that stands in for it.

**See.** [D-009](#d-009) (wrap, don't reimplement), [D-011](architecture-api.md) (invasiveness where it enables
better features), [D-041](#d-041) (no pinning — ownership is derived, not held),
[`031-window-lifecycle/plan.md`](../031-window-lifecycle/plan.md),
[`specs/codebase/gameui-windows.md`](../../codebase/gameui-windows.md) (the seam and its seven call sites),
[learnings/ui-widgets.md](../learnings/ui-widgets.md).

### D-070 — a restore replays what the user was SEEING, not what the widget was

**Context.** 029 recorded, per hidden native widget, the visibility it had *before* the addon touched it, and
teardown replayed that (`Hidden.origVisible`) — the right answer at the time, because the alternative on the table
was a blind `show()`, which hands the user a window they never opened (the `Hidewnd` wrappers are created hidden).
031 then gave a hidden window's **toggle** to its owner (D-069) and 031.2 gave that toggle a **view** to drive, at
which point "what was the widget?" and "what was the user looking at?" stopped being the same question: an addon
whose custom inventory is open, then disabled, should leave the *stock* inventory open — even though the wrapper it
hid was hidden at the time.

**Decision.** **One rule, no branches: the window ends up as the user was seeing it** (maintainer, 2026-08-02).
Teardown, `replacer:remove()` and a server destroy all restore visibility as `view != null && view.visible()` —
the addon's stand-in was on screen ⇒ the stock window opens; nothing was on screen ⇒ it stays closed. `origVisible`
is **deleted**, not kept as a fallback: a second remembered state is a second thing to drift, and the canonical
unbound case (hiding the inventory wrapper, which is hidden from birth) evaluates to exactly the same answer, so
the branch bought nothing. The corollary on the live side is the same principle: `wndstate` answers
`view.visible()` rather than a bookkeeping boolean, so the menu tick cannot disagree with the screen. Rejected: an
engine-side open/closed flag per owned window, and keeping `origVisible` for the unbound case (two rules, and the
one the maintainer would hit is whichever they did not read).

**Consequences.** `replace` stops keeping a hide record of its own — it joins the one `Addon.hiddenNative` entry
`w:hide()` makes and binds its view to it, so there is exactly one record per window and no second copy to keep in
step. Ordering becomes load-bearing where it was not: a rule that *reads a live object* must run before that object
is destroyed, so `teardownHidden` now precedes `destroyWidgets`. And a real behaviour change ships with it: a bare
`w:hide()` on something that WAS visible now stays hidden through a `:reload` instead of coming back — correct
under the rule (nothing was standing in for it), and recoverable, because the toggle handed back with it is what
opens it again. **One window, one owner** follows from the same place: a toggle can drive one view, so a second
addon hiding an already-hidden native widget is refused, naming the first (`w:hide()` throws; `replace`, which runs
on the engine's placement path and must not throw into it, logs and skips).

**See.** [D-069](#d-069) (hiding takes the toggle — this gives it something to drive),
[D-009](#d-009) (wrap, don't reimplement), [`031-window-lifecycle/spec.md`](../031-window-lifecycle/spec.md),
[learnings/ui-widgets.md](../learnings/ui-widgets.md),
[`specs/codebase/gameui-windows.md`](../../codebase/gameui-windows.md).

### D-071 — a relationship's lifetime is watched on the relationship, not on a bookkeeping object beside it

**Context.** `hafen.ui.replace` created a `LuaModel` per replacement and watched *that* for the server destroying
the window (`pollModels`: `getwidget(id) != wdg`). 032.1 turned replacement into `w:replace(view)`, a verb on the
entity — and the verb **adopts nothing**: there is no model to create, because 031.2 had already collapsed the two
records into one and the substitution *is* the single `Addon.hiddenNative` entry (the window it hid + the view
bound to it). So the question became: what does destroy-detection key on when the bookkeeping object is gone?

**Decision.** **Key it on the record that IS the relationship** (maintainer, 2026-08-02), and funnel every way a
substitution can end through one expression. `pollReplaced` sweeps the hide records that have a view bound, with
the same two-branch liveness test the teardown already uses (`stillHidable`: by server id when the window has one,
by tree reachability for a client-side wrapper) and gated on the `anyHidden` volatile the toggle seam maintains, so
a client that replaces nothing pays one read per tick. `w:replace(nil)`, the sweep and the teardown all call the
same `endReplacement`: apply the D-070 rule, drop the record, destroy the view — in that order, since the rule
reads the view's visibility. Rejected: having the verb mint a `LuaModel` just so the existing poll would fire (a
bookkeeping object whose only purpose is to be watched is a second record to keep in step — the exact thing D-070
deleted), and per-substitution `onDestroy` callbacks (the addon can already subscribe to any widget).

**Consequences.** The decisive evidence arrived as a bug the maintainer found in-game: after `w:replace(view)`, a
`:reload` restored the stock inventory correctly **and left the custom window floating on top of it**. Restoring
the window is only *half* of an ending, and the other half lived somewhere else entirely — `teardownHidden`
restored, `destroyWidgets` killed the view a moment later. For a loaded addon those two run back to back, so the
split was invisible; the `:lua` REPL owner **survives a reload** and has no `destroyWidgets` leg, so there the half
never ran. The general rule: **an ending split across two places will be half-done wherever only one of them runs**
— so `teardownHidden` now ends the substitution whole, and the kill is idempotent (`AddonWidget.kill` guards)
precisely so the three paths can overlap without ordering rules between them.

**See.** [D-070](#d-070) (one record, one rule — this is its lifetime half),
[D-069](#d-069) (hiding takes the toggle), [D-009](#d-009) (wrap, don't reimplement),
[`032-replace-verb/spec.md`](../032-replace-verb/spec.md),
[learnings/widget-replacement.md](../learnings/widget-replacement.md).

### D-075 — a read answers for the thing you point at, so a SITE rule is not a widget's style

**Context.** 034.1 made the stylesheet's **tree keys** resolve per widget and added `widget:style()` to read the
result — the read the whole feature is tested through (030's `:res()` lesson, applied before the fact). The
question it forced: the sheet also holds **site keys** (`*`, `button`, `chat`, … — 033), and the cascade the
feature documents is *per-instance → most specific tree rule → site rule → `*` → stock*. Should `w:style()`
report the site levels too, so the read shows the whole cascade?

**Decision.** **No — `w:style()` folds the TREE keys alone**, and answers `nil` when none of them names the
widget. A site key resolves where its text **draws**, not on a widget: a window contains buttons, labels and
chat, each rendered at its own site, so "the site rule for this widget" has no single true answer and would be a
guess dressed as a read. The same holds downward — a site key also reaches text no widget owns (a tooltip is
painted, not placed), which is precisely why the two key classes exist (D-067). The cascade's site half is real,
but it is settled at the **draw**, where the site is known; the read answers the half that is a property of the
widget itself.

**Consequences.** `nil` keeps one meaning on this read — *no rule names this widget* — instead of two, and it
stays the same value the draw path tests for when deciding whether to open a frame at all, so the read and the
draw cannot drift. The honest cost is that `w:style()` is **not** "what this text will look like": with
`["*"] = {font=body}` installed, every label draws in `body` and `label:style()` is still `nil`. The docs say so
in the same breath as the read. The alternative — falling back to the widget's own role-as-scope, else
`"default"` — was rejected twice over: it makes every widget non-`nil` the moment a `*` rule exists (the
identity fast path's contract read backwards), and for any container it reports one of the several sites drawing
inside it.

**See.** [D-067](#d-067) (a classifier answers only where the widget IS the thing — the same rule, one level
up), [D-072](../decisions/architecture-api.md) (forgive what the API will later understand),
[`034-ui-stylesheet-tree/spec.md`](../034-ui-stylesheet-tree/spec.md),
[learnings/fonts.md](../learnings/fonts.md).
