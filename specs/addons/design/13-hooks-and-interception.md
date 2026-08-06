# Hooks & Interception (replacing/altering client logic)

> **LEVELS 1-3's ADDRESSING SUPERSEDED by [041-unified-events](../041-unified-events/spec.md)**: `hafen.hook`
> is gone as a whole (the `input`/`action`/`message` proposal below and the `Level 1-3` sections that
> describe them are history) — input on any widget is `widget:on("MouseDown"/"MouseUp"/"MouseMove"/"Wheel",
> fn)`, an outbound action is `hafen.event():action():on(msg, fn)`, an inbound message is
> `hafen.event():message():on(msg, fn)`, all three over the ONE `X:on(key, fn)` grammar every emitter in the
> area now shares. The **mechanism** each level named (`Widget.listen`/`deafen` for L1, the `UI.wdgmsg`/
> `UI.uimsg` choke points for L2/L3) is unchanged and still what backs the Lua surface — only the address
> changed, from a section of its own to `X:on(key, fn)` on whatever the address actually is (spec §R2). The
> priority/ordering question ([Q-012](../DECISIONS.md), below) is answered too: registration order within
> one addon, undefined between addons, safe because *any* handler's `preventDefault` cancels and *every*
> handler still runs (spec §R3) — there is no priority to configure. **Level 4 (method/full-logic
> replacement) is untouched**: never built, still on the ROADMAP, and this page is still where its design
> lives.
>
> **Status:** 🟡 Draft (Level 4 only — Levels 1-3 shipped, see above) · **Spec:** AddOns
> **Related:** [08-widget-replacement.md](08-widget-replacement.md), [09-events-catalog.md](09-events-catalog.md), [06-lua-api.md](06-lua-api.md), [DECISIONS.md](../DECISIONS.md) (D-011)

How an addon **intercepts, alters, or replaces** client behaviour — not just observes it. The
motivating example: *"when I click on the MapView to move, I want to `preventDefault`, run my own
logic (maybe something intermediate), and then issue the click myself."* This is the WoW
`hooksecurefunc` + full-replacement model combined with the JS `event.preventDefault()` model.

> **Invasiveness note ([D-011](../decisions/architecture-api.md)).** The addon system may edit the `haven` core where
> it yields materially better features. This document therefore favors **first-class hook
> instrumentation** over the minimal-edit approach used elsewhere. The zero-edit seams are still
> called out (they're a bonus), but we are not constrained to them.

## The hook model

Three kinds of hook, per hook point:

- **Pre-hook** — runs *before* the default. Can `preventDefault()` (skip the default), mutate the
  arguments, and/or call `default()` itself. Can `stopPropagation()`.
- **Post-hook** — runs *after* the default (like `hooksecurefunc`). Observe/react; cannot cancel
  (it already happened).
- **Replace** — a pre-hook that always prevents the default and supplies its own behaviour.

The **hook event object** (`ev`) passed to handlers:

| Member | Meaning |
|---|---|
| `ev:preventDefault()` | skip the default behaviour |
| `ev:default()` | run the original default now (optionally after mutating args) |
| `ev:stopPropagation()` | consume so other widgets/addons don't see it (input hooks) |
| `ev.args` | the arguments (mutable where meaningful) |
| `ev.sender` / `ev.target` | the widget involved |
| `ev:resend()` / `ev:send(args)` | (action hooks) re-issue the action, optionally modified |

## Interception levels

Pick the level by *what* you want to intercept. For the MapView-move example, **Level 2 (action)**
is usually the right one, because the resolved world coordinate only exists after the hit-test.

### Level 1 — Input / gesture hooks  *(built-in seam, ZERO core edit)*

**Great news: `Widget` already has a listener mechanism that is a pre-hook with `preventDefault`.**

- [`Widget.listen(Class<E>, EventHandler)`](src/haven/Widget.java:856) / [`deafen`](src/haven/Widget.java:862)
  register/remove a typed event listener.
- [`Widget.handle(Event)`](src/haven/Widget.java:885) runs listeners **before** the default: if any
  listener returns `true`, it **short-circuits and the widget's own handler is never called**
  ([`Event.dispatch`](src/haven/Widget.java:838) → `handle` → else `shandle` = default).

So an addon listener returning `true` on a `MouseDownEvent` **is** `preventDefault` — the
widget's `mousedown` never runs. No core edit required; we just expose `listen`/`deafen`.

> ⚠ **Return-true also blocks child dispatch (audit).** `Event.dispatch` returns immediately when
> `handle` is true, so a `listen` on a **parent** that returns `true` suppresses not just that
> widget's own handler but also **descendant** dispatch — `preventDefault` on a container is also a
> `stopPropagation` to its children.
>
> ⚠ **Higher-priority seam: `UI.grab` (audit).** [`UI.dispatch`](src/haven/UI.java:610) consults a
> global **grabs** list *before* dispatching to the target widget ([`UI.grab`](src/haven/UI.java:536),
> `grabmouse`/`grabkeys` :573/:581) — a pre-empt point **above** `Widget.listen`, used e.g. by
> MapView camera-drag. Consider exposing it as a modal "grab" mode of `hafen.hook.input`.
>
> ⚠ **L2/L3 caveats (audit).** `ev:resend()` (L2) re-enters the *same* `UI.wdgmsg` choke point, so
> a hook must guard against re-entrant re-dispatch. An L3 hook that rewrites `ev.name` must **intern**
> the string — `Widget.uimsg` compares message names with `==` on interned strings, so a
> non-interned rewrite silently falls through to "unhandled".

```lua
-- fires before MapView's own mousedown; return-true (preventDefault) suppresses it
hafen.hook.input(mapview, "mousedown", function(ev)
  -- ev.button, ev.x, ev.y  (SCREEN coords; world coord is not resolved yet at this point)
  ev:preventDefault()          -- MapView.mousedown will not run
  -- ...intermediate logic...
  ev:default()                 -- optionally run the original now (issues the normal click)
end)
```

Backing: `listen`/`handle` above; `MapView.mousedown` at [:2028](src/haven/MapView.java:2028).
Caveats: **per-instance** (get the instance via `GameUI.map`, or let the engine auto-attach on
widget creation, [08](08-widget-replacement.md)); at `mousedown` time the **world coordinate is
not yet known** (MapView runs an async hit-test → [`Click.hit`](src/haven/MapView.java:2007)).

### Level 2 — Action hooks (outbound `wdgmsg`)  *(one core edit — recommended for the move example)*

Every player action is a [`Widget.wdgmsg`](src/haven/Widget.java:737) that funnels through the
single choke point [`UI.wdgmsg(sender, msg, args)`](src/haven/UI.java:665). Instrumenting it with
one hook call gives a **universal outbound-action interceptor** where the arguments are already
**fully resolved** (for a move: the world coord and any clicked gob):

```lua
-- fires when MapView is about to send the "click" action to the server
hafen.hook.action("click", function(ev)
  -- ev.sender == the MapView widget; ev.args == {pc, worldCoord, button, mods [, gob...]}
  local w = ev.args[2]                    -- resolved world coordinate
  if myCondition(w) then
    ev:preventDefault()                   -- do NOT send the move to the server
    doSomethingIntermediate(w)
    ev:resend()                           -- ...then issue the click myself (or ev:send(newArgs))
  end
end)
```

This is the exact analog of intercepting the network request a UI click would trigger. It is the
cleanest place for *"intercept my move, do X, then move"* because the destination is known.
One edit: a hook call at the top of `UI.wdgmsg` ([:665](src/haven/UI.java:665)).

### Level 3 — Message hooks (inbound `uimsg`)  *(one core edit)*

Symmetrically, server→client updates funnel through [`UI.uimsg(id, msg, args)`](src/haven/UI.java:702)
→ [`Widget.uimsg`](src/haven/Widget.java:677). A hook here lets addons **suppress or rewrite server
UI updates** (e.g. drop a message, remap a value) before the widget applies them.

```lua
hafen.hook.message("inv", function(ev)   -- ev.target widget, ev.name, ev.args (mutable)
  -- return/preventDefault to swallow it, or mutate ev.args to rewrite it
end)
```
Caveat: runs on a Loader thread inside `synchronized(ui)` (server-message application), not the
render tick — keep handlers light and non-blocking.

### Level 4 — Method / full-logic replacement  *(hookable subclass)*

To replace behaviour that isn't an input event or a message — or to override a specific method
like `Click.hit` — install a **hookable subclass** via the factory-override seam from
[08-widget-replacement.md](08-widget-replacement.md):

```
HookableMapView extends MapView {
  protected void ... /* overridden method consults the addon hook chain, then optionally super */
}
```
Registered through `Widget.types` so the server's request for that type builds the hookable
version. `hafen.hook.method(type, name, fn)` exposes it: `fn(ev, default)` decides whether to call
`default()`. This is the most powerful level (arbitrary logic replacement) at the cost of a
per-widget Java subclass.

> **Not recommended:** runtime bytecode instrumentation (Javassist/ASM/Java agent) to hook
> arbitrary methods without editing them. Possible, but heavy and fragile; explicit hook points +
> hookable subclasses cover the real needs.

## Worked example — the MapView move-click

| Goal | Level | How |
|---|---|---|
| Cancel the gesture entirely before any hit-test | 1 (input) | `hook.input(mapview,"mousedown")` → `ev:preventDefault()` |
| Intercept the *move that goes to the server*, run logic, then move | 2 (action) | `hook.action("click")` → `preventDefault` + logic + `ev:resend()` |
| Change how a click maps to an action (rewrite coords/target) | 2 (action) | mutate `ev.args` then `ev:send(...)` |
| Replace MapView's click resolution wholesale | 4 (method) | `HookableMapView` overriding `Click.hit` |

For *"do something intermediate, then call the click myself"*, Level 2 is the fit: the world
coordinate is resolved in the args, you drop the original send, do your work, and re-send.

## Priority & ordering ([Q-012](../DECISIONS.md))

Multiple addons may hook the same point. Needs a defined policy:
- Registration carries an optional **priority**; handlers run in priority order.
- **First `preventDefault` wins** (default is skipped); later pre-hooks still run unless one calls
  `stopPropagation()`.
- Post-hooks all run (order by priority).
Exact semantics are open — see [Q-012](../DECISIONS.md).

## Threading, ownership, teardown

- **Input (L1)** and **action (L2)** hooks run on the **UI thread**; **message (L3)** hooks run on
  a Loader thread under `synchronized(ui)`. All must be non-blocking (watchdog applies,
  [04-engine.md](04-engine.md)).
- Every hook is **owned by the addon** ([P2](01-architecture.md)): the bridge tracks it and
  releases it on disable/reload — `listen` hooks via [`Widget.deafen`](src/haven/Widget.java:862),
  action/message hooks by unregistering from the engine's dispatcher, hookable subclasses by
  restoring the original factory. See [05-lifecycle-and-reload.md](05-lifecycle-and-reload.md).

## Proposed Lua API (`hafen.hook`)

```lua
hafen.hook.input(target, event, fn [, opts])    -- L1: target = widget handle or type name
hafen.hook.action(msgName, fn [, opts])         -- L2: outbound wdgmsg
hafen.hook.message(msgName, fn [, opts])        -- L3: inbound uimsg
hafen.hook.method(type, methodName, fn [, opts])-- L4: hookable subclass
-- opts = { priority = 0, post = false }        -- post=true → post-hook
```
Each returns a handle with `:remove()` (also auto-removed on teardown).

## Backing summary

| Piece | Where |
|---|---|
| Built-in listener pre-hook | [`Widget.listen`](src/haven/Widget.java:856) / [`deafen`](src/haven/Widget.java:862) / [`handle`](src/haven/Widget.java:885) |
| Event dispatch (listener-before-default) | [`Widget.Event.dispatch`](src/haven/Widget.java:838), `shandle` (:821) |
| Consume / stop propagation | listener returns `true`; [`Event.stop`](src/haven/Widget.java:814) |
| Outbound action choke point | [`UI.wdgmsg`](src/haven/UI.java:665) |
| Inbound message choke point | [`UI.uimsg`](src/haven/UI.java:702) → [`Widget.uimsg`](src/haven/Widget.java:677) |
| MapView click flow | [`mousedown`](src/haven/MapView.java:2028) → `Click` → [`Click.hit`](src/haven/MapView.java:2007) → `wdgmsg("click", …)` |
| Method replacement seam | `Widget.types` factory override ([08](08-widget-replacement.md)) |
