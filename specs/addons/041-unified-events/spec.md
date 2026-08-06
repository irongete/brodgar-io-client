# 041-unified-events — Spec

> **No line limit on this file** (maintainer instruction, 2026-08-06): the feature re-spells every
> reactive call site in the area, so the shape is settled here rather than discovered per task.
> The exhaustive surface — every emitter × every key, the before→after port map, and the worked
> examples — lives beside it in [EXAMPLES.md](EXAMPLES.md), which is what the maintainer validates
> before implementation starts and what each `/implement` session reads instead of guessing.

## 1. What & why

An addon is a set of callbacks, and today it has **three unrelated ways to install one**:

| door | cardinality | unsubscribe | naming |
|---|---|---|---|
| `hafen.event():on("GobAdded", fn)` | N subscribers | `sub:off()` | `PascalCase` |
| `hafen.hook():input/:action/:message` | N per message | `handle:remove()` | `lowercase` |
| `btn:onPress(fn)`, `inv:onItemAdded(fn)`, `w:onDraw(fn)` | **1 slot, replaces** | (none — overwrite) | `onCamelCase` verb |

Three cardinalities, three unsubscribe spellings, three naming conventions, for one concept. 039
imposed one grammar on every *read* in the API and deliberately did not touch this: the reactive
half kept the shapes each feature had picked for it. This is that half.

**The rule, in one sentence: `X:on(key, fn)` returns a subscription, `sub:off()` ends it, and
whether you say it to an object or to `hafen.event()` depends only on whether you are holding the
object.**

### 1.1 The four concrete defects, not just the inconsistency

1. **Input reaches three widgets out of ~625.** `hafen.hook():input(target, …)` accepts the string
   tokens `"mapview"`, `"gameui"` and `"root"` and nothing else. It is the only place in the UI
   half that addresses a widget by a magic string rather than by a handle or a
   [selector](../design/22-ui-selectors.md). **This is an API limit, not an engine limit** —
   `Widget.listen`/`deafen`/`handle` are `Widget` methods
   ([`widgets.md`](../../codebase/widgets.md)), so any widget can carry a pre-hook listener. An
   addon that finds a `Cupboard` by selector today cannot intercept a click on it.

2. **Two vocabularies for one gesture.** A widget you built answers `w:onClick(fn)`; the three
   native tokens answer `hafen.hook():input(t, "mousedown", fn)`. Same gesture, same engine seam,
   two spellings — and **`onClick` is a `mousedown`**, so the name is also wrong.

3. **`ev.sender` and `ev.target` are class-name strings.** An action or message handler is told
   `"MapView"`, not the widget. You cannot navigate from the event to the thing it is about, which
   is exactly what a handler usually wants next.

4. **A native widget has no input surface at all.** It refuses `:onPress` (not `Owned`, 040.1) and
   refuses the eight paint/input callbacks (not an `AddonWidget`), and it is not one of the three
   input tokens. So the one kind of widget an addon most often *finds* is the one it can least
   react to.

### 1.2 Why now, and why it is one feature

Nothing is released, so this is a hard cut with no deprecation window — the area's standing rule
([design/25](../design/25-uniform-api.md) §Boundaries). Doing it as three features would leave the
surface half-uniform through two of them, which is the mistake 039's own spec names: *uniform
syntax over non-uniform returns is a veneer*. The same applies to cardinality.

### 1.3 Measured scope

| corpus | figure |
|---|---|
| Lua files / lines | 64 files, 18,184 lines |
| addon folders (suites + examples) | 63 |
| `hafen.event` call sites (Lua + docs) | 125 |
| `hafen.hook` call sites | 82 (20 Lua · 25 docs · 37 Java) |
| per-widget callback verb sites | ~182 Lua · ~90 docs |
| distinct bus event names in use | 26 of 26 (88 `:on(...)` sites in `addons/**`) |
| docs pages touching the reactive surface | 33 of 76 |
| Java files touching subs/hooks/watches | 30 |

Roughly **330 Lua call sites** and **33 doc pages** are touched. That is the same order as 039 and
is handled the same way: a generated `Retired` table so every old spelling throws naming its
replacement, and a close that sweeps the whole roster at once rather than trusting each task to
have remembered. It is not, however, 330 *renames* — with keys staying PascalCase (§R5), 44 of the
88 `hafen.event():on(...)` sites already carry their final spelling today and are touched only for
the mechanism change (the closed-set throw, the five composite payloads) or left alone; the other
44 lose an `On` prefix. The re-spelling proper falls on the ~182 widget/control callback sites, the
82 `hafen.hook()` sites, and those 44 lifecycle bus sites.

## 2. The design

### R1 — one verb, one cardinality, one handle

```lua
local sub = X:on(key, fn)     -- N subscribers, registration order within an addon
sub:off()                     -- idempotent; a second call is a no-op, not an error
```

**N subscribers everywhere.** This retires the single-slot behaviour of `:onPress`/`:onChange`/the
eight callbacks, and with it their arity-as-the-verb *read* (`btn:onPress()` → the installed
handler). Two independent modules of one addon can now wire the same button without one silently
clobbering the other — the defect WoW patched years after the fact by bolting `HookScript` beside
`SetScript`, which is the precedent for not shipping the single slot in the first place.

R2 of [design/25](../design/25-uniform-api.md) (*a read is the bare noun, a write is the same name
with a value*) does **not** apply here and is not being broken: a subscription is not a property.
`:on(key, fn)` always registers and always returns a `Sub`; `:on(key)` is an error, not a read.

### R2 — the address decides the door

> **¿Tienes el objeto? `obj:on(...)`. ¿No? `hafen.event()`.**

| you hold | you write |
|---|---|
| a control you built | `btn:on("Pressed", fn)` |
| a native widget (by selector) | `cup:on("MouseDown", fn)` |
| a widget of your own | `w:on("Draw", fn)` |
| nothing — it is a client-wide fact | `hafen.event():on("GobAdded", fn)` |
| nothing — it is a message stream | `hafen.event():action():on("click", fn)` |

This is [D-066](../decisions/architecture-api.md) (*a thing that lives inside another is a relation
on it*) applied to notifications: **a `MouseDown` has an owner and belongs on it; a `GobAdded` has
none and belongs on the bus.** L2/L3 stay on the bus for exactly that reason — a `"click"` can come
from any widget and a `"set"` can go to any widget, so neither has an owner to hang off.

### R3 — cancelling is `ev:preventDefault()`, and no return value is ever read

One moment, DOM-shaped. **`before`/`after` was considered and rejected**: it was attractive while
the design still had a separate `hafen.event():client()` namespace, but once `:on` became the one
verb everywhere, a second and third verb in two sections would be the exception rather than the
symmetry that justified them.

Consequence worth stating on its own: **a handler's return value is never read.** Today
`AddonWidget`'s `onClick`/`onMouseUp`/`onMouseMove`/`onWheel`/`onDrop` consume the input by
`return true`, read as `callLua(...).arg1().toboolean()`. That becomes `ev:preventDefault()`, so
one mechanism cancels everywhere and a stray `return` in a handler can no longer change behaviour
by accident.

**The multi-handler cancel rule:** *any* handler calling `ev:preventDefault()` cancels, and **every
handler still runs**. This is the OR accumulation `dispatchAction`/`dispatchMessage` already
implement (`boolean[] prevented`), generalised — so the outcome never depends on registration
order, which is the only defensible answer once ordering between addons is undefined.

### R4 — one thing to say → the thing itself; more than one → an event object

```lua
btn:on("Pressed",   function() end)                 -- nothing to say
chk:on("Changed",   function(v) end)                -- the value
inv:on("ItemAdded", function(item) end)             -- the Item
w:on("Tick",        function(dt) end)               -- the delta
hafen.event():on("GobAdded", function(gob) end)     -- the Gob

w:on("MouseDown",    function(ev) end)              -- x, y, button, cancel
w:on("Draw",         function(ev) end)              -- g, w, h
slider:on("Changed", function(ev) end)              -- value, final
hafen.event():action():on("click", function(ev) end)
```

**One axis, mechanical, no exceptions.** It is not a new shape — it is the one the API already
follows almost everywhere (`GobAdded` hands over the Gob, `ItemAdded` the Item, `Changed` the
value), stated and then applied without exceptions.

Two things this deliberately is *not*:

- **Not "cancelable ⇒ object".** An earlier draft used that, which is two axes (cancelability *and*
  arity) and puts `w:on("MouseDown", function(ev)…)` next to `w:on("Draw", function(g, w, h)…)` —
  a reader then has to know which keys cancel before they know the callback's shape. `Draw` carries
  an `ev` and answers no `:preventDefault()`; cancelability is a property of the key, listed in
  `EXAMPLES.md` §1.1, not something the shape encodes.
- **Not a performance rule.** The earlier draft justified plain values for `Draw` by the per-frame
  allocation 026 removed. Measured, that is ~600 small objects/second against the ~11 MB/frame the
  client already allocates — **0.015%**, not observable. The claim did not survive being checked and
  is not what decides this.

The rule reaches three callbacks that would otherwise have kept loose arguments — `Draw`
(`:g()` `:w()` `:h()`), `Cell` (`:g()` `:item()` `:w()` `:h()`) and a slider's `Changed`
(`:value()` `:final()`) — and simplifies one: `MarkersChanged` hands over the count itself instead
of a one-field wrapper.

### R5 — keys are PascalCase with no `On` prefix, and a closed set throws

`:on` already says *on*, so the redundant prefix goes: `Load`, not `OnLoad`. Everywhere else,
PascalCase is already the bus's own convention (`GobAdded`, `MeterChanged`, …), so this feature
extends it to the other two doors rather than picking a fourth spelling: `Pressed`, not `onPress`;
`MouseDown`, not `mousedown`. One convention replaces the three in the table at the top — and
because it is the bus's own convention, **the bus itself needs almost no rename**: measured against
the corpus, 44 of its 88 `hafen.event():on(...)` call sites already say `GobAdded`/`MeterChanged`/…
and stay exactly as they are; only the other 44, on the four lifecycle keys (`OnLoad`/
`OnEnterWorld`/`OnUpdate`/`OnDisable`), lose their `On` prefix. What changes for the bus is the
mechanism underneath (§R1 internals, the closed-set throw, the five composite payloads becoming
objects) and those four names — not a corpus-wide re-spelling.

Whether an unknown key throws follows [D-129](../decisions/architecture-api.md) — *a set closed at
load refuses an unknown key while a set the world fills answers nil*:

- **Widget keys and bus keys are CLOSED** → an unknown one **throws, listing the keys that widget
  or the bus does answer** ([D-125](../decisions/architecture-api.md)). This is a change: today
  `hafen.event():on("GobAdded ", fn)` is accepted and simply never fires, which is documented
  leniency and the most common silent addon bug there is.
- **`action` and `message` keys are OPEN** → any string is accepted and may never fire. A `wdgmsg`
  name is protocol, not a catalogue the client owns; refusing an unknown one would refuse a
  legitimate message the server introduces tomorrow.

### R6 — `ev:sender()` and `ev:target()` are widget handles

Interned per addon through the existing weak two-axis cache
([D-064](../decisions/architecture-api.md)), so `ev:sender():type()`, `ev:sender():parent()` and
`ev:sender():on(…)` all work. The class-name string is still reachable as `ev:sender():type()`, so
nothing is lost and the string comparison every current handler does keeps working after a
one-token edit.

### 2.1 What stays exactly as it is

- **`hafen.ui():on(selector, "appear"|"disappear", fn)`** — delegation. You do not hold the handle
  yet; that is the whole point of it, and it is why this one takes three arguments where every
  other `:on` takes two. It already returns the widget to the handler, which is where the
  handle-first half then begins.
- **The threading model.** Everything continues to run on the UI thread or under `synchronized(ui)`,
  through `AddonManager.callLua` (watchdog-armed, error-isolated, CPU-accounted). No seam moves
  threads.
- **The `hasSub` gate.** Per-addon payload minting stays gated on that addon having a live
  subscription to the key, so a busy spawn stream still costs nothing for addons that do not listen.
- **Ownership and teardown.** A subscription is owned by its addon and released on
  `:reload`/disable. There is still no cleanup to remember.
- **Tier.** The whole reactive surface is ungated and stays ungated: subscribing observes, and
  cancelling cancels the client's own behaviour rather than sending anything.

### 2.2 The mouse becomes an entity, and the grab hangs off it

`hafen.hook():grab{move, up}` needed a home once `hafen.hook()` is retired. Looking for one surfaced
that `hafen.ui():mouse()` — today a `{x=, y=}` table read — is really **the pointer**, with four
things to say and only one of them exposed.

```lua
local m = hafen.ui():mouse()

m:x()  m:y()                  -- where the cursor is (was: the {x=, y=} table)
m:over()                      -- the widget under it (was: hafen.ui():at(m.x, m.y))
m:shift() m:ctrl() m:alt()    -- NEW: UI.modflags() is public and reached Lua nowhere
m:grab()                      -- take the pointer
```

It becomes an entity of the shape `hafen.player()` already has — the section's one thing *is* the
object. **`hafen.ui():at(x, y)` is not absorbed**: it takes any point, so it is not about the mouse;
only the cursor case moves, and it moves because the docs kept spelling it out by hand
(`hafen.ui():at(hafen.ui():mouse().x, hafen.ui():mouse().y)` is a line in
`docs/addons/api/ui/style/README.md`).

**Two of these are new capabilities, deliberately.** `:shift()`/`:ctrl()`/`:alt()` expose a public
engine method (`UI.modflags()`) that no `hafen.*` verb reached — modifier state was readable only
from inside a handler that happened to be handed it — and `:over()` composes two existing verbs.
Both are small, and both are here rather than in a later feature because this one is already
reshaping the surface they sit on. Modifiers are **three flat verbs, not a `mods` table**: three
booleans behind a table would be three dot-reads, which §2.3 exists to remove.

```lua
local g = hafen.ui():mouse():grab()
g:on("Move", function(ev) end)   -- ev:x() ev:y() ev:shift() ev:ctrl() ev:alt()
g:on("Up",   function(ev) end)   -- …plus ev:button(); auto-releases
g:release()                      -- end it early
```

**The config table is cut.** A grab constructs something with a lifetime — you hold it and
`:release()` it — so R4 of [design/25](../design/25-uniform-api.md) applies squarely: *no `opts`
table survives on anything that is constructed.* An earlier draft of this spec claimed the table was
per-call scope like `g:text`'s ([D-143](../decisions/architecture-api.md)) and therefore exempt; that
was wrong, because `g:text`'s table dies with its call and a grab does not. And once it is a thing
you hold, R2 above answers the rest: **¿tienes el objeto? `obj:on(...)`.**

Hanging it off `mouse()` also settles the naming: the receiver says what is being grabbed, so the
verb keeps the engine's own word (`UI.grab`, `UI.grabmouse`, `interface Grab` —
[D-061](../decisions/architecture-api.md)) with none of the *"grab what?"* ambiguity that a bare
`hafen.ui():grab()` would have had to answer with a compound name.

`hafen.ui()` rather than `hafen.world()`: what is captured is **the pointer**, which is the UI's.
Dragging something across the ground is the common use, not the mechanism, and it still pairs with
`hafen.world():screenToWorld` exactly as before.

There is no race between taking the pointer and subscribing — Lua runs on the UI thread and
synchronously, so nothing dispatches between two statements of one handler.

### 2.3 Every payload member is a colon verb

`ev.msg`, `ev.sender` and `ev.args` are fields on a plain Lua table today, built by
`LuaActionHook` before 039 existed and never reached by it. They become `ev:msg()`, `ev:sender()`,
`ev:args()`, because R2 of design/25 already decides it and because **one object must never mix `.`
and `:`** — that is the single most common Lua footgun and `ev` would be the only place in the API
that demanded remembering which member is which.

The same applies one level down: the five composite bus payloads
(`GobOverlayAdded`/`Removed`'s `{gob, key, native}`, the three `*Clicked`'s
`{ghost, button, x, y}`, `MarkersChanged`'s `{count}`) are plain tables today and become objects
with verbs. Leaving them as tables while `ev` moved would only relocate the inconsistency.

**A single value stays a single value**: `chk:on("Changed", function(v) end)` receives `v`, and
`Draw`/`Tick`/`Cell` receive their arguments directly (R4 above). An event object for one boolean
would be ceremony, not consistency.

## 3. Acceptance criteria

Verified in-game by the maintainer, each through the task's own self-checking suite per
[TESTING.md](../TESTING.md).

- [ ] **One verb.** `:on(key, fn)` returns a subscription on every emitter — a built control, a
      native widget found by selector, an addon's own widget, `hafen.event()`,
      `hafen.event():action()` and `hafen.event():message()`. A suite asserts a `Sub` comes back
      from each, and that `sub:off()` stops delivery and is idempotent on a second call.
- [ ] **N subscribers.** Two handlers registered on one key both fire, in registration order;
      `off()` on the first leaves the second firing.
- [ ] **Cancel is uniform.** `ev:preventDefault()` cancels on an input key, on an `action` key and
      on a `message` key; with two handlers on one key, cancelling in either one cancels and both
      still run.
- [ ] **Input on any widget.** A native widget obtained by selector answers
      `:on("MouseDown", fn)`, the handler fires on a real click, and `ev:preventDefault()`
      suppresses the widget's own handling. (The click itself is a `[manual]` line; the
      registration, the handle identity and the refusal path are asserted.)
- [ ] **Closed keys throw, open keys do not.** `btn:on("Presed", fn)` throws naming `Button` and
      listing its keys; `hafen.event():on("OnLoad", fn)` throws naming `Load`;
      `hafen.event():on("GobAdded ", fn)` throws (unknown key, trailing space); `hafen.event():action():on(
      "anythingatall", fn)` is accepted.
- [ ] **`ev:sender()`/`ev:target()` are handles.** An `action` handler reads `ev:sender():type()` and
      `ev:sender():parent()`; a `message` handler reads `ev:target():type()`.
- [ ] **No payload is read with a dot.** Every member of `ev` and of the five composite bus payloads
      answers as a colon verb, and the retired field access is asserted gone (`ev.msg == nil`).
- [ ] **The mouse is an entity.** `hafen.ui():mouse()` answers `:x()`, `:y()`, `:over()`,
      `:shift()`, `:ctrl()`, `:alt()` and `:grab()`; the old table read is asserted gone
      (`hafen.ui():mouse().x == nil`). `:over()` agrees with `hafen.ui():at(m:x(), m:y())`, and
      `hafen.ui():at(x, y)` still answers for an arbitrary point.
- [ ] **The grab is an emitter.** `hafen.ui():mouse():grab()` takes no arguments, hands back a grab
      that answers `:on("Move", fn)`, `:on("Up", fn)` and `:release()`; the old
      `hafen.hook():grab{move, up}` throws naming it. (The drag itself is a `[manual]` line — one
      that also confirms the map does not pan while the grab is held; the construction, the refusal
      of an argument, `:release()` and the release-on-teardown are asserted.)
- [ ] **Every retired spelling throws naming its replacement.** `hafen.hook`, all 16 `on*` widget
      verbs, and the 4 `On`-prefixed lifecycle bus names (`OnLoad`/`OnEnterWorld`/`OnUpdate`/
      `OnDisable`) — swept in one pass at the close, not per task. The other 22 bus names are
      unchanged and carry nothing to retire.
- [ ] **The corpus is ported and green.** Every prior suite (`:t033-1` … `:t040-13`) and all 12
      example addons run on the new surface; the full regression is green.
- [ ] **The docs tier is the contract.** All 33 affected pages re-spelled to area `docs`'s standard
      (`specs/docs/design/style-guide.md` §12 checklist reported), `api/hook.md` resolved,
      `api/event.md` rewritten, both "API at a glance" tables updated.
- [ ] Each task ships its self-checking addon per `specs/addons/TESTING.md`; its run is all
      `[pass]` (plus any `[manual]` line the maintainer confirms) and every prior suite still is.

## 4. Out of scope

- **`:once(key, fn)` and `:listeners(key)`** — raised while designing and dropped by the maintainer
  as scope creep. Not deferred with a plan; simply not built.
- **Hook priority / explicit ordering** ([D-021](../decisions/widgets-ui.md), still on the
  ROADMAP). Order within an addon is registration order; between addons it stays undefined. The
  R3 cancel rule is what makes that safe rather than merely unspecified.
- **Post-hooks** (observe after the default ran) — the ROADMAP's other hook round-out item.
  Explicitly decided against in R3, so this feature **closes** that half of the ROADMAP entry
  rather than deferring it.
- **L4 method replacement / hookable subclasses** ([design/13](../design/13-hooks-and-interception.md)
  §Level 4) — never built, unchanged by this.
- **New events.** The bus catalogue is re-spelled, not extended. `SkillsChanged` and per-slot
  `EquipChanged` stay on the ROADMAP. *(The two new **reads** on the mouse entity —
  `:shift()`/`:ctrl()`/`:alt()` and `:over()`, §2.2 — are not events and are deliberately in scope.)*
- **Moving `hafen.render`/`hafen.ghost` under `hafen.world`** — a separate ROADMAP entry; the three
  `*Clicked` events are re-spelled in place.
- **Changing what any event *means*.** Payloads, timing, owner-scoping ([D-104](../decisions/architecture-api.md))
  and the marshalling rules are untouched. This is a re-spelling plus one new reach (input on any
  widget), not a re-design of the bus.

## 5. Context files

- [`EXAMPLES.md`](EXAMPLES.md) — **read first**: the complete key catalogue, the before→after port
  map, and the worked examples. The non-hallucination reference for every `/implement` session.
- [`design/13-hooks-and-interception.md`](../design/13-hooks-and-interception.md) — the standing
  hook design; this feature keeps its three levels and re-addresses them.
- [`design/25-uniform-api.md`](../design/25-uniform-api.md) — the area grammar this extends to the
  reactive half; R2/R4 and the `Retired` mechanism.
- [`design/09-events-catalog.md`](../design/09-events-catalog.md) — the original bus catalogue and
  its marshalling rules.
- [`design/22-ui-selectors.md`](../design/22-ui-selectors.md) — the selector grammar
  `hafen.ui():on` keeps.
- [`specs/codebase/widgets.md`](../../codebase/widgets.md) — `Widget.listen`/`deafen`/`handle`,
  `Event.dispatch`, the destroy two-branch test, the draw/tick recursion seams.
- [`specs/codebase/network.md`](../../codebase/network.md) — the `UI.wdgmsg` / `UI.uimsg` choke
  points L2/L3 sit on.
- `src/io/brodgar/addon/HookApi.java` — L1/L2/L3 registries, dispatch and teardown; the file this
  feature mostly dissolves.
- `src/io/brodgar/addon/AddonManager.java` — the bus (`fire`/`fireTo`/`hasSub`/the per-payload
  `fireX` family), the tick pump, `callLua`, and the `onWdgmsg`/`onMessage`/`onGlobKey` seams.
- `src/io/brodgar/addon/AddonWidget.java` — the eight paint/input callback slots and their
  consume-by-return convention.
- `src/io/brodgar/addon/LuaWidget.java` — the widget entity, its methods table, and
  `onItemAdded`/`onItemRemoved`/`onDestroy` + the `Watch` record.
- `src/io/brodgar/addon/Controls.java` — the `Press`/`Change`/`Submit`/`Select`/`Cell` capabilities
  and their dispatch.
- `src/io/brodgar/addon/UiApi.java` — selector subscriptions, `pollWatches`, the widget registries.
- `src/io/brodgar/addon/Retired.java` — the retired-name mechanism this feature feeds.
- `src/io/brodgar/addon/Addon.java` — `subs`, `hooks`, `actionHooks`, `messageHooks`,
  `slashCommands`, `mouseGrabs` and the teardown order.
- `docs/addons/api/event.md`, `docs/addons/api/hook.md` — the two pages that merge.
- `docs/addons/guides/events-and-timers.md` — the guide that teaches the model.
- [`039-uniform-api/`](../039-uniform-api/) — prior art for a corpus-wide re-spelling: the
  `Retired` table generated from an inventory, and the close that sweeps it in one pass.
- [`040-ui-controls/`](../040-ui-controls/) — the capability-dispatch pattern (`Controls.Press` et
  al.) the control keys replace, and its roster-sweep close.
