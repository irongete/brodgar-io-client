# 074 — the engine survives a switch

## What and why

Three features prepared this one and none changed what a player sees. `071` made every session equal,
`072` gave the engine names for the three questions it used to ask an ambient field, `073` indexed its
state by session. **This is where the switch is thrown.**

Today `Sessions.tickrebind` calls `AddonManager.init(u)` on every anchor change, and `init` runs
`for(Addon a : addons) AddonRegistry.teardown(a); addons.clear();` before loading from disk again.
Measured in game: **11 ms**. The cost was never the problem. The problem is what those 11 ms *are*:
`Load` and `EnterWorld` fire again on every character switch, every Lua value an addon held is gone,
and **the addon cannot tell** — no event says the screen changed, and `Disable` does not distinguish a
tab from a logout from a `:reload`. The loss is silent, which is worse than the loss.

### The model: a layer, not a tenant

**The addon system is its own layer, above the sessions, and it never lives inside one.** It loads
once for the client, it is drawn above whichever session is on screen, and it reaches sessions through
`hafen.session()` — never the other way round. Adding a session does not give that session addons;
it fires an event the layer may react to.

That is a **real** layer and not a simulation of one. The alternative — leaving addon windows in a
session's widget tree and re-homing them on every switch — was rejected here: re-homing needs a
subtree walk, focus repair and grab release on every tab, machinery whose only purpose is to imitate
a layer that could simply exist. Its failures are quiet and recurring; a layer's are loud and once.

Two things follow that are not obvious:

- **The layer is drawn even with no session at all** — over the login screen. "Above everything"
  admits no exception, and a window that vanishes at a logout was living in a session after all.
- **Focus and mouse or key grabs survive a switch**, because nothing moves. A text field keeps its
  cursor across a tab.

### What the lifecycle becomes

An addon no longer enters the world; **a session does**. So the lifecycle splits in two: what happens
to the addon, once, and what happens to sessions, repeatedly.

| Event | Fires |
|---|---|
| `Load` · `Disable` | the addon is loaded and unloaded. **Once each, for the client** |
| `SessionAdded` | a session connects |
| `SessionEnteredWorld` | ...and its HUD is up. **This is what `EnterWorld` becomes** |
| `SessionSelected` | the screen changed to another session |
| `SessionDestroyed` | a session ends |

Tabbing to a session **already** in the world fires `SessionSelected` and nothing else. Tabbing is not
entering.

It also settles the four rows `073`'s census left **deferred to this feature**, and the answer is the
opposite of what deferring them assumed: `AddonManager.addons`, `autoDisabledWarn`, `clock` and
`resolveQueue` all become **process-wide**, because an `Addon` stopped belonging to a login the moment
it outlived a switch. That settles the CPU watchdog too, without touching it: one instance per addon
means the per-frame Lua budget counts what it always counted.

## Acceptance criteria

1. Switching character **does not reload the engine**. A Lua value set before a switch is still there
   after it; `Load` and `Disable` each fired exactly once; `engineReloads` reads `0` after any number
   of switches and `addonsLive` does not move on one.
2. The addon layer is **drawn above the session on screen**, and above the login screen when there is
   no session. An addon's own window is never re-parented: it keeps its place, its focus and any
   mouse or key grab across a switch.
3. The session event family exists — `SessionAdded`, `SessionEnteredWorld`, `SessionSelected`,
   `SessionDestroyed` — each carrying which session. The bus catalogue stays closed, so an unknown
   spelling still throws, and **`EnterWorld` is retired throwing the name that replaced it**.
4. `hafen.store()`'s per-character scope has a stated referent and **flushes when that session ends**,
   not when the addon is unloaded.
5. `docs/addons/api/event.md` is **under its 300-line ceiling**. It is 321 today and this feature adds
   to it, so the split its own subject line describes happens here.
6. The four census rows `073` deferred are settled and `census.md` says so.

## Out of scope

- **Feature `075`, the API cut.** Nothing of the old spelling survives loose: every session-shaped
  section moves under `hafen.session():current()` / `:get(user)`, and `hafen.ui()` splits — your own
  windows belong to the layer, the client's windows belong to a session. That is why this feature's
  event payload is an account **name**: `075` replaces it with the Session object, a hard cut nothing
  is released against.
- Per-session addon instances — decided against in the model.
- The `Fonts` subsystem.

## Docs impact

**Written**: `docs/addons/runtime.md` (when your code runs; what a `:reload` keeps, and the new fact
that a switch keeps everything) · `docs/addons/api/event.md` (the lifecycle table, the session family,
and the split criterion 5 requires) · `docs/addons/api/store.md` and `docs/addons/guides/saved-data.md`
(the per-character referent and when it flushes) · `docs/addons/api/ui/custom.md` (your windows live
in the layer) · `docs/addons/api/client/profiling/counters.md` (the two counters) ·
`docs/client/multi-session.md` and `docs/client/boot-and-loop.md` (the frame draws two trees).

**A contract change for authors, and it goes in `runtime.md` in those words**: the reload was hiding
mistakes. An addon that cached a widget handle from one session and used it under another could not
fail before, because the switch killed and rebuilt it. Now it can. *Your state survives a character
switch, and keeping it valid is therefore yours.*

**Derived set:**

```
grep -rniE "reload|EnterWorld|Disable|per character|session ends|torn down" docs/addons/
```

**Run and pasted by the first task before it revises anything** — `073` closed between this plan and
that task. What it must catch, known today: `runtime.md`'s "what a reload keeps, and what it drops"
and its load-order table; `api/event.md`'s four lifecycle rows and its `Disable` line naming three
causes; `api/store.md` and `guides/saved-data.md` on flushing at `Disable`; `api/ui/native.md` and
`api/ui/replace.md` on records dropped with the session; `api/vr/README.md` and `api/vr/ghosts.md` on
entities dropped at reload; `api/actionbar.md` on a hold not surviving to the next character;
`guides/events-and-timers.md` on `Update` and the frame.

## Context files

- `src/haven/UILoop.java` — 1, 2
- `src/haven/UI.java` — 1
- `src/haven/Widget.java` — 1
- `src/haven/Client.java` — 1 (the one real `dispatch`; the other two are no-ops)
- `src/io/brodgar/addon/UiApi.java` — 1
- `src/io/brodgar/addon/LuaWidget.java` — 1 (liveness, and the cross-tree `widget:parent(w)`)
- `src/io/brodgar/addon/LayerRoot.java` — 3 (the engine's pump: boot, `Update`, the timers, the budget)
- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3, 4
- `src/io/brodgar/addon/AddonRegistry.java` — 2, 3
- `src/io/brodgar/addon/Addon.java` — 2, 4
- `src/io/brodgar/addon/LuaEvent.java` — 3
- `src/io/brodgar/addon/Retired.java` — 3
- `src/io/brodgar/addon/StoreApi.java` — 4
- `src/io/brodgar/addon/Sandbox.java` — 2
- `src/io/brodgar/session/Sessions.java` — 1, 2, 3
- `specs/073-caches-know-their-session/census.md` — 2 (reads and updates)
- `docs/addons/runtime.md` — 2, 4
- `docs/addons/api/event.md` — 3
- `docs/addons/api/store.md` — 4
- `docs/addons/guides/saved-data.md` — 4
- `docs/addons/api/ui/custom.md` — 1
- `docs/addons/api/client/profiling/counters.md` — 2
- `docs/client/boot-and-loop.md` — 1
- `docs/client/multi-session.md` — 1
- `DOCUMENTATION.md` — 3
