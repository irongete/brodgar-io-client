# UI Selectors (one way to point at a part of the UI)

> **SPELLINGS SUPERSEDED by [25-uniform-api.md](25-uniform-api.md)** (shipped as 039-uniform-api): the
> selector grammar below is untouched and every selector string still means what it says here, but the three
> doors it is reached through are now colon verbs on the section object — `hafen.ui():find(sel)`,
> `hafen.ui():all(sel)`, `hafen.ui():on(sel, ev, fn)` — because one expression could not be both the
> namespace and the root widget. Read the spellings below as the shapes of that time.
>
> **Status:** 🟢 Design closed — shipped as [030-ui-selectors](../030-ui-selectors/spec.md) · **Spec:** AddOns
> **Series:** B (the UI run), feature **B2** · **Surface:** `hafen.ui(sel)` · `hafen.ui.all(sel)` · `hafen.ui.on(sel, ev, fn)` · `widget:role()`
> **Decisions:** [D-056](../decisions/architecture-api.md) (arity is the verb), [D-063](../decisions/architecture-api.md)
> (an entity's key is what the engine publishes), [D-067](../decisions/widgets-ui.md) (a classifier answers only where
> the widget IS the thing), [D-068](../decisions/widgets-ui.md) (a discovery primitive answers for what is already there)
> **Related:** [20-widget-introspection.md](20-widget-introspection.md) (the tree walk + hit-testing this rides),
> [21-fonts.md](21-fonts.md) (the scope names promoted into roles), [08-widget-replacement.md](08-widget-replacement.md)
> (the placement descriptor, and why `replace` stays out), [the API reference](../../../docs/addons/api/ui.md)

## The problem this solves

`029-widget-oop` gave `hafen.ui` **one entity**: a window you create, a native window you find, the deepest widget
under the cursor and the container `replace` hands your callback are all the same Widget object. What it did not
give was a way to **name** one. Every lookup was positional or by id — `hafen.ui.at(x, y)`, `hafen.ui.node(id)`,
`hafen.ui.inventory()` — so "the Cupboard window" could only be reached by walking the tree and testing by hand,
and "wait until a Cupboard opens" needed a second, unrelated vocabulary (`onWidgetCreate`'s placement descriptor).

A selector is the missing addressing form: **a string that names a widget the same way whether you are looking one
up, listing them all, or waiting for one to appear.** Everything downstream of B2 consumes it — B3's replacement,
C's `hafen.ui.skin` stylesheet, D's window chrome, E's layout — which is why it ships before any visual property
exists.

## The grammar

Deliberately tiny, CSS-shaped because that is the mental model everyone already has:

```
selector := ( '*' | role )? refiner*
refiner  := '@' ClassName | '[' ('title'|'res') '=' value ']'
```

| Part | Meaning |
|---|---|
| `*` | any widget, including one no role classifies |
| a **role** | what the widget *is* (the table below) |
| `@Class` | its [`LuaWidget.typeName`](../../../src/io/brodgar/addon/LuaWidget.java) — the nearest **named** class |
| `[title=…]` | the caption of the nearest **enclosing window** — exact |
| `[res=…]` | a **substring** of the widget's resource name |

Refiners may appear in any order and each at most once; anything else errors naming the offending part, and a bad
role lists every valid one. There are **no descendant selectors** (`window[title=X] button`) in v1 — they multiply
resolution cost and are rarely needed. A selector is parsed **once**, at the call or at subscription time, into an
immutable value object whose `matches(Widget)` is a predicate over **one** widget; parsing never happens per node,
and the same object serves the lookup, the collection, the events and (later) C's stylesheet.

`title` is **exact** and `res` is a **substring**, each following the convention already set for its kind of key:
`replace{caption=…}` matches a caption exactly, while `hafen.meter(needle)` and the gob-overlay string filter match
a resource name by `contains`. A res name is a path an author has to type; a caption is the whole human label.

## Roles — the classifier is the heart of the feature

Most role names are **not invented here**: they are the [`Fonts.SCOPES`](../../../src/haven/Fonts.java) names
`016-fonts` established and routed one surface at a time. The promotion is **not 1:1** — of its 11 entries,
`"default"` is a cascade fallback whose selector twin is `*`, the other 10 carry over verbatim, and **two roles are
new**: `window` (the frame itself — fonts only ever needed its title) and `inventory`. Reusing those names is what
keeps this from becoming a second vocabulary.

But those scopes give the **vocabulary, not the mechanism**. Fonts resolve *scope → override* **at each render
site**; a selector must answer the **inverse** — *what role is THIS widget?* — and nothing answered that before.
The answer is one `instanceof` chain in ONE Java method, `LuaWidget.role(Widget)`, following the same discipline
`typeName`/`nodeText` already use for fragile upstream knowledge: upstream churn breaks that one method, not addons.

| Role | Classifies |
|---|---|
| `window` | `Window` and every subclass (incl. the `Hidewnd` the client wraps the inventory in) |
| `inventory` | `Inventory`, `Equipory` — *every* open container, not just yours |
| `button` | `Button`, `IButton` |
| `label` | `Label` |
| `textentry` | `TextEntry` |
| `chat` | `ChatUI`, `ChatUI.Channel` |
| `menu` | `MenuGrid`, `FlowerMenu` |

**Five promoted names classify nothing, and say so** ([D-067](../decisions/widgets-ui.md)): `window.title`,
`heading`, `tooltip`, `world.nick`, `world.speech` name a **render site**, not a widget — a window's caption is
drawn by `Window.Deco`, a tooltip is painted rather than placed, and the world scopes live over the 3D view. They
stay valid grammar (one vocabulary, shared with fonts; coverage can grow) and match nothing, because guessing that
a `Label` is a `heading` is exactly the wrong answer. The rule the classifier follows everywhere: **never a wrong
answer in place of no answer** — an unrecognised widget is `nil`.

Measured over a live HUD: **174 of 625 widgets classify** (window 9 · inventory 5 · button 114 · label 33 ·
textentry 7 · chat 5 · menu 1). That is the rule working, not a gap; reach the rest with `*`, `@Class` or `[res=]`.

## Two matching rules that are easy to get wrong

Both are settled inside the matcher rather than at each call site, because getting either wrong ships a selector
engine that looks right and never matches.

- **`[title=]` matches the nearest enclosing `Window`'s caption, not the widget's own.** A bare widget the engine
  wraps in a titled window — an `Inventory` inside a `Hidewnd "Inventory"` — has **no `cap` of its own**, and 029
  found the same shape from the other side (`hafen.ui.inventory()` is the *grid*; its window is `:parent()`, one
  hop up). Matching the widget's own caption would make `inventory[title=Cupboard]` — the single most obvious
  selector a user will write — silently never match. Verified in-game: `window[title=Cupboard]` → the window,
  `inventory[title=Cupboard]` → its grid, and `grid:parent() == wnd`.
- **`@Class` goes through `typeName`, never `getSimpleName()`.** Hafen builds a great many widgets as anonymous
  subclasses, whose simple name is the empty string. `@Class` is an **exact** match on the nearest named class — no
  superclass walk, so `@Window` is not a `CharWnd`. "Any window" is the `window` role.

## What actually carries a `res` — the spec's own promise, refuted

The spec expected [D-063](../decisions/architecture-api.md)'s stable key to shine on `.res`-published windows whose
caption is localized or absent. **In-game it is not true here**: of 625 widgets exactly **69** carry a resource — 62
`WItem`s, the 3 `IMeter`s, and the chat channels whose code ships inside a res (`ui/rchan`, `ui/vlg`,
`ui/provinces`). **No window on this server carries one.** So the honest rule the docs state is: `[title=]` is the
only key for windows, `[res=]` the correct one for everything item-shaped, and `w:res()` is how you find out which
you are holding — never a client-side alias list.

`:res()` was **not in the plan** and had to be added: without it `[res=]` was unobservable from Lua, so it could be
neither written nor verified.

## Events — the same name, for a widget that does not exist yet

`hafen.ui.on(sel, "appear"|"disappear", fn)` hangs off the existing `onWidgetPlaced` seam and hands the callback
the **Widget entity**, so the thing you wait for is named with the SAME selector you would look it up with, and `==`
(plus a Lua table keyed by the entity) carries state across the two events. `hafen.ui.onWidgetCreate` and its
`{id,type,place,caption,parentType}` descriptor are a **hard cut**.

Three properties define the semantics:

1. **Registration scans the live tree** ([D-068](../decisions/widgets-ui.md)), so `appear` fires for what is already
   open. "Appear" therefore means *is in the tree as of now* — a **state**, reached either way, not an edge. The old
   observer could never fire for an existing widget, which is why every `:reload` lost every open window.
2. **`disappear` is about being real, not about being drawn.** `UI.destroy` unbinds the id and *then* calls
   `reqdestroy()`, which `Window` overrides to start a **fade-out** — so a closing window lingers in the tree,
   unbound and fully readable, for the whole animation (observed: `disappear` fired with `exists=true` and
   `text=Cupboard`). On reachability alone every close would lag by its fade. At `disappear` the entity is a **key
   to match**, never a last chance to read.
3. **Neither event is about visibility.** A window the client merely hides (the inventory's Tab toggle) never left
   the tree, so it fires neither.

**Cost scales with widget creation, not with frames.** `Selector` splits into `matchesStructure` (role/class, fixed
for a widget's life) plus the refiners: a widget that fails the structural half can never start matching, while one
that passes it and carries a `[title=]`/`[res=]` refiner may simply not have its caption yet — a caption arrives by
`uimsg`, a resource resolves asynchronously — so it queues a `PendingMatch` re-checked for a bounded **20 ticks**
(`-Dhaven.addon.selrecheck=`). A late-captioned Cupboard fires exactly once. Idle cost is one `isEmpty()` per
placement and per tick. The rejected alternative was a per-tick diff of matching widgets, which would cost a full
tree walk every frame per subscription.

## Cost, and the one rule the docs must state

`hafen.ui.all("*")` is **one** walk testing each node — never a deep helper per node, which is O(n²) (029.4 hit
exactly that and had to prune `hello`'s container scan). Measured: **0.08 ms for 625 widgets**. Once per event, or
once when the hover changes, that is nothing; sixty times a second it is a real slice of the frame budget. Because
entities are **interned**, holding the result costs nothing and stays `==`-comparable — so the documented rule is
**hold your result; do not re-select every frame**, and no selector cache is needed (rejected as premature).

## The inspector ships with the feature, not after it

A selector system without one is unusable: **nobody guesses a widget's role.** The inspector goes into
`widgetstack`, already the `/framestack` clone, hanging off the same hover guard (029's `==`). Its bottom panel
answers role (or an honest `nil`) · class · `[title=]` · `[res=]`, then **every selector built from those parts that
actually matches**, most specific first (ranked role 1 · `@Class` 2 · `[title=]` 4 · `[res=]` 8), each with its
match count and this widget's index among them.

**The list is self-validating**: a candidate is offered only after `hafen.ui.all()` resolved it and found this very
widget inside. That is what lets the offered line promise what it promises, and what forces the honest form —
`hafen.ui("…")` only when the widget is the **first** match, `hafen.ui.all("…")[i]` otherwise — and it absorbs any
drift between a Lua re-derivation of a rule and the engine's own. `*` is omitted: it says nothing and it is the one
walk that interns the whole tree. Cost is 3 walks for a plain widget, 7 with a title, ≤15 for an item, all on a
hover **change**.

**The surprise it makes obvious:** hovering a window's frame never gives you the window. The chrome is a **child**
widget (`@DefaultDeco`, role `nil`) — the hit-testing face of "a caption belongs to `Window.Deco`". `[title=]` still
resolves *through* it, so the deco is addressable.

## Scope & honest limits

- **No descendant selectors**, no attribute operators beyond `=`, no pseudo-classes. Addable later.
- **No visual property.** B2 ships the vocabulary and the matcher; `hafen.ui.skin` is C.
- **`replace(selector, fn)` is deferred to B3, and not for size.** `replace` matches a *descriptor* at
  **placement** time whose `place`/`parentType` are the **server** parent (a live `maininv.parent` is already a
  client-side `Hidewnd`), so a live-tree selector cannot express `context="main"`. Converting it is a semantic
  change, not a rename — which is why the descriptor survives exactly there and nowhere else.
- **`inventory()`/`equipment()` are not cut.** They answer "the player's own"; the `inventory` role answers "every
  open container". Different questions. `hand()` stays a snapshot — the cursor item is not a widget.
- **The classifier is incomplete by design** and the role set grows. What must never happen is a role matching the
  *wrong* widget.

## Verification (as shipped)

Each grammar element resolved in-game against real windows; an open Cupboard matched by title through both the
window and its grid; a malformed selector's error read back; `all("*")` measured with
`hafen.client:profiling()`; one `appear`/`disappear` pair per open/close with a `[title=]` selector, including the
late-caption case firing exactly once; the inspector's offered line pasted into `:lua` returning the same widget
(`==`). `hello` re-checks the whole contract once per login.
