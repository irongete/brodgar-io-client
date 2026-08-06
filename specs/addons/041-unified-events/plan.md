# 041-unified-events — Plan

> **No line limit** (maintainer instruction). The design lives in [spec.md](spec.md) and the exhaustive
> surface in [EXAMPLES.md](EXAMPLES.md); this file is the technical route and the traps.

## Approach

### The one mechanism: `Subs`

Everything reduces to one class. A **`Subs`** is a keyed multimap `String key → CopyOnWriteArrayList<Handler>`
plus a `fire(key, args…)` that walks it through `AddonManager.callLua` and reports whether any handler
cancelled. Every emitter owns one; nothing else in the feature has subscription logic.

| emitter | who owns the `Subs` | why there |
|---|---|---|
| the bus | one per `Addon` (replaces `Addon.subs`) | a bus event is addon-wide |
| `action` / `message` | one per `Addon`, separate instance | open key set, different dispatch path |
| a widget | one per **(addon, widget)** pair | several addons may subscribe to one native widget |
| a grab | one per grab | it dies with the grab |

**Per-(addon, widget) is not new.** `UiApi.Watch` is already exactly that record — "one addon's
subscription to a container's item lifecycle", created on first callback and dropped when the last one
goes. It generalises from three fixed slots to an arbitrary keyed map, and `pollWatches` keeps
driving the polled keys (`ItemAdded`/`ItemRemoved`/`Destroy`) unchanged.

This is [D-100](../decisions/architecture-api.md) — *the state belongs on the THING* — and it is what
keeps the new reach free: putting a listener on a native widget costs that widget's record and nothing
global, so there is no registry to sweep and no prune.

> **Naming, decided here rather than mid-task.** `AddonManager.Sub` **already exists**
> (`AddonManager.java:2135`) — it is today's bus-subscription record, and `Addon.subs` is a
> `List<AddonManager.Sub>`. The new `Subs` (the multimap) would sit one character away from it in the
> same package, which is a reading hazard for every later session. So `AddonManager.Sub` is **deleted**
> in 041.1, not left beside the new type: its role splits into an entry inside `Subs` and the Lua-facing
> `LuaSub`. No point in the feature has both names alive.

### `LuaSub` — the handle

Userdata over `(Subs, key, fn)` with one verb, `:off()`. Removal is by identity from the
copy-on-write list; a second call, or one after the widget died, finds nothing and returns — idempotent
by construction rather than by a flag. Teardown drops the owning `Subs` wholesale, so an addon still has
nothing to unsubscribe by hand.

### Profiling attribution — the split must survive the merge

`Addon.CATS = {"events", "timers", "draw", "hooks", "widgets"}` is the per-addon cost split
([D-052](../decisions/architecture-api.md), 019) and it is **published**: `ProfHandle` hands it to Lua as
`r.calls.hooks` / `r.cost.hooks`, and `docs/addons/api/client/profiling/attribution.md` defines each
category in prose. Today the five are distinguished by *which mechanism* dispatched — `onDraw` → `draw`,
`onClick`/`onTick`/`onDrop` → `widgets`, `hafen.hook()`'s three levels → `hooks`, the bus → `events`.

**After this feature every one of them is a `Subs.fire`**, so a naive single charge inside `fire` would
collapse the split to one category and silently flatten the profiler. The rule: **the category is a
property of the emitter and key, carried on the `Subs` (or passed to `fire`), never inferred from the
mechanism.** Concretely, and preserving today's numbers exactly:

| key | category |
|---|---|
| bus keys, `action`, `message` | `events` — except the three levels below |
| `Draw`, `Cell` | `draw` |
| `MouseDown`/`MouseUp`/`MouseMove`/`Wheel`, `Tick`, `Drop`, `Close`, `Destroy`, `ItemAdded`/`ItemRemoved`, the control keys | `widgets` |
| `action` / `message` / input, when the addon reached them through the old hook doors | *(gone — see below)* |
| grab `Move` / `Up` | `hooks` |

`hooks` does **not** empty out: hotkeys and slash commands stay in `HookApi` and keep charging it, and the
grab joins them. But its *meaning* narrows — "input, action and message hooks plus hotkeys and slash
commands" stops being true the moment input moves onto widgets and the two streams move onto the bus. That
sentence is in the docs tier, so **`api/client/profiling/attribution.md` is a page 041.6 must edit** even
though it is not an event page. Left alone it becomes a page that describes a mechanism the client no
longer has.

### `LuaEvent` — the `ev` object

One userdata class, a **per-shape methods table** chosen at construction (input · draw · cell · slider ·
grab-move · grab-up · action · message · overlay · clicked). The metatable's `__index` consults that
table and **throws naming the key on a miss**, which is [D-125](../decisions/architecture-api.md) for
free and is what makes `ev:buton()` fail where it is written.

`ev:sender()` / `ev:target()` mint the interned `LuaWidget` **lazily**, on first read, through the
existing weak two-axis cache ([D-064](../decisions/architecture-api.md)) — `UI.wdgmsg` is hot and most
handlers never ask.

Cancellation is a shared `boolean[] prevented` across the handlers of one fire, exactly as
`dispatchAction`/`dispatchMessage` do today. `fire` never short-circuits: OR accumulation, every handler
runs.

### Input on any widget

`Widget.listen(cls, handler)` / `deafen` are `Widget` methods, so the three magic tokens were only ever
an API restriction. The (addon, widget) `Subs` registers **one** `EventHandler` per event class on first
subscription to that key and deafens it when the last `Sub` for that key goes. A listener returning
`true` short-circuits the engine's own dispatch — which is what `ev:preventDefault()` now sets, replacing
the consume-by-return convention.

### Where the old code goes

`HookApi` keeps slash commands and the keybinding registry and loses everything else: L1/L2/L3 registries,
`dispatchAction`/`dispatchMessage` bodies move to the `Subs` of the two stream emitters,
`LuaInputHook`/`LuaActionHook`/`LuaMessageHook` are deleted, `LuaMouseGrab` keeps its widget half and
loses its Lua surface to `LuaGrab`. `AddonManager`'s eight `fireX` helpers keep their `hasSub` gate, which
becomes `Subs.has(key)`.

### Core `haven` edits: expected ZERO

Every seam is public already — `Widget.listen`/`deafen`/`handle`, the `UI.wdgmsg`/`UI.uimsg` taps that
exist, `UI.modflags()`, `UI.mc`. A task that finds it needs one states why before writing it.

### The corpus rule

**Every task ports the call sites it breaks, in the same task.** This is a hard cut with no aliases, so a
task that renames a spelling and leaves the corpus for later turns the whole regression red until the
port lands. 039 worked this way across sixteen tasks and it is why its suites stayed green throughout.

## Files to create / modify

**New**
- `src/io/brodgar/addon/Subs.java` — the keyed multimap, `fire`, the cancel accumulation, `has(key)`, the profiling category it charges
- `src/io/brodgar/addon/LuaSub.java` — the `:off()` handle (`AddonManager.Sub` is deleted, not kept beside it)
- `src/io/brodgar/addon/LuaEvent.java` — the `ev` object; per-shape method tables; unknown verb throws
- `src/io/brodgar/addon/LuaMouse.java` — the pointer entity (`:x` `:y` `:over` `:shift` `:ctrl` `:alt` `:grab`)
- `src/io/brodgar/addon/LuaGrab.java` — the grab as an emitter (`:on`, `:release`)

**Modified**
- `AddonManager.java` — the 4 `On`-prefixed lifecycle bus keys drop their prefix (PascalCase kept, the other 22 keys unchanged); `fire`/`fireTo`/`hasSub` and the eight `fireX` helpers onto `Subs`; the composite payloads become `LuaEvent`s
- `Addon.java` — `subs`/`hooks`/`actionHooks`/`messageHooks`/`mouseGrabs` collapse into the `Subs` set + the listened-widget registry; teardown order preserved
- `HookApi.java` — L1/L2/L3 removed; slash + keybindings stay
- `LuaWidget.java` — `:on(key, fn)`; the 16 `on*` verbs retired; the methods table routed through `Retired`
- `AddonWidget.java` — the eight callback slots become `Subs` keys; the consume-by-return convention removed
- `Controls.java` — `Press`/`Change`/`Submit`/`Select`/`Cell` fire through `Subs` instead of holding one slot
- `UiApi.java` — `Watch` generalised to `Subs`; `pollWatches` unchanged in shape; `:mouse()` becomes `LuaMouse`
- `Retired.java` — 26 bus names + 16 widget verbs + `hafen.hook` + the `:mouse()` table read
- `LuaMouseGrab.java` — keeps the capture widget, loses its Lua surface
- **Deleted**: `LuaInputHook.java`, `LuaActionHook.java`, `LuaMessageHook.java`

**Corpus** — `addons/**` (63 folders: every prior suite, 12 examples, frozen `hello`), ported task by task.

**Docs tier** — 33 of 76 pages. `api/event.md` rewritten whole; `api/hook.md` resolved (its three levels
move into `event.md`, its grab into `api/ui/`); `api/ui/widget.md`, `controls.md`, `lists.md`, `items.md`,
`custom.md`, `selectors.md`, `replace.md`; `guides/events-and-timers.md`, `custom-ui.md`, `debugging.md`,
`hotkeys-and-commands.md`; **`api/client/profiling/attribution.md`** (the `hooks` category's definition —
see *Profiling attribution* above); both "API at a glance" tables; `api/README.md`.

**Specs** — `specs/codebase/addon-engine.md` extended (the new file layout and the seams that moved);
`specs/addons/design/13-hooks-and-interception.md` marked superseded on its L1/L2/L3 addressing;
`decisions/architecture-api.md` gains this feature's decisions.

## Risks & gotchas

Prior art: `learnings/hooks-hotkeys.md`, `learnings/luaj-bridge.md`, `learnings/ui-widgets.md`.

- **A retired verb that reads as plain `nil`.** 039 hit this **three times** (`LuaGob`, `LuaOverlay`,
  `hafen.player()`): an entity metatable pointing `__index` straight at its methods table never reaches
  `Retired`. `LuaWidget` is exactly that shape and is retiring 16 verbs. **Assert the refusal, never the
  dispatch** — each time it was found only because a suite checked that the old spelling *throws*.
- **A native widget survives `:reload`; a listener on it must be deafened.** The 2c rule
  (`learnings/hooks-hotkeys.md`) applied to three widgets and now applies to any: the Lua layer rebuilds
  while MapView/GameUI/root/an `Inventory` do not, so an undeafened handler fires into a torn-down env.
  Teardown must walk the listened-widget registry, and `LuaWidget.kill()`'s `dead` flag still silences a
  late callback racing it.
- **`Thread.holdsLock(ui)` must survive the move.** A MapView `"click"` is sent from the **render**
  thread inside `synchronized(ui)`; a naive "am I the UI thread" test would wrongly skip the hook. Keep
  the `holdsLock` guard on the action path and the *no* guard on the message path (that seam is only ever
  reached under the monitor).
- **Colon-call arity.** `w:on(key, fn)` arrives with `self` as arg 1 and the real arguments at `arg(2)`
  (`learnings/luaj-bridge.md`). `ev:send(t)` was already caught by this once — a `OneArgFunction` reads
  `self`, not the payload.
- **`draw`'s `ev` must be as inert as its `g`.** `LuaGOut` is bound for the duration of the callback and
  dead afterwards; an `ev` stashed by an addon must answer the same way rather than hand out a stale
  brush. Reuse the existing bind/unbind discipline, do not invent a second one.
- **`destroy` needs the two-branch liveness test.** A closing `Window` is unbound before it is unlinked
  (`specs/codebase/widgets.md`), so a check on `hasparent` alone fires a whole fade-out late and a check
  on the id alone misses a client-only widget. `pollWatches` already does this; do not simplify it.
- **The intern cache stays weak on both axes.** `ev:sender()` must not pin a widget
  ([D-064](../decisions/architecture-api.md)); a strong reference here would quietly resurrect D-041's
  no-pin problem on the hottest path in the client.
- **`luac -p` exits 0 on a syntax error.** The corpus port is ~330 sites over 64 files; read the parser's
  output, never its status (039).
- **A scripted port cannot see an indirect call.** 039 found `pcall(hafen.json.parse, x)` invisible to its
  sweep and, worse, its rewriter changed the very spelling a refusal test pinned. Grep for the bare names
  as well as the call forms, and re-read the suites that assert refusals.
- **A suite's baseline must be measured on the right side of a lazy tick** (040.9, recurring in 040.12).
  Any tree-count or subscription-count assertion in this feature's suites has the same hazard.

## Discarded alternatives

- **`before`/`after` on action/message** — attractive while a separate `client()` namespace existed; once
  `:on` became the one verb everywhere it would have been the exception, not the symmetry.
- **Post-hooks** ([ROADMAP](../ROADMAP.md), [D-021](../decisions/widgets-ui.md)) — the same reasoning; the
  ROADMAP entry is updated rather than deferred.
- **"Cancelable ⇒ event object"** — two axes, so a reader must know which keys cancel before they know the
  callback's shape. Replaced by the one-axis arity rule (spec R4).
- **Justifying loose `draw` arguments on allocation** — measured at ~0.015% of what the client already
  allocates per frame. The claim did not survive checking and is not what decides the shape.
- **Keeping the single slot and adding a second verb for extra handlers** — literally WoW's
  `SetScript` + `HookScript`, whose second half cannot unsubscribe. The defect is the reason for N.
- **`:once()` / `:listeners()`** — dropped by the maintainer as scope creep, not deferred.
- **Keeping `hafen.hook()` alive for `grab` alone** — a section named after a concept that no longer
  exists, for one verb.
- **`hafen.ui():grab()` / `grabMouse()`** — the first cannot say what it grabs, the second is a compound
  name invented to answer that. `mouse():grab()` lets the receiver answer it and the verb keep the
  engine's word ([D-061](../decisions/architecture-api.md)).
- **Absorbing `hafen.ui():at(x, y)` into the mouse** — it takes an arbitrary point; only the cursor case
  belongs on the pointer.
- **One `LuaEvent` subclass per shape** — ten near-identical classes where one class with a per-shape
  methods table gives the same refusal behaviour and one place to change.
- **Priority/ordering** — left on the ROADMAP. The OR cancel rule makes undefined inter-addon order safe
  rather than merely unspecified, which downgrades priority from a correctness gap to a convenience.
