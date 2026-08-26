# 114 — GobAdded before the first drawn frame

## What & why

**An object reaches the screen before the event that announces it.** `OCache.add` fires its
`ChangeCallback` on a Loader thread; `MapView.Gobs.added` defers `addgob`, which puts the object in
the render tree on that same thread, while the addon layer's copy of the same news waits in
`SessionState.gobEvents` for the next UI step. The Loader routinely wins. So every addon that reacts
to an object arriving is late by at least one drawn frame: a hidden tree is visible for a frame, an
overlay attached on `GobAdded` pops in after the model. That frame is not an addon's to close.

**The fix is to hold, not to hurry.** The render add asks one question — *has the layer already
drained this object's event?* — and when the answer is no it throws `Loading`, which
`Loader.Future.run` already parks and re-queues on notify. The step drains, fires `GobAdded` with the
object as populated as it is today, and notifies; the Loader re-enters and adds it. `GobAdded` is
then guaranteed to have run before the object's first drawn frame, **for every addon and every
purpose** — not by moving the event, but by making the picture wait for it.

**`gob:visible(b)` ships with it**, because the guarantee needs a write that proves it. Today hiding
means `gob:scale(0.001)` — a sub-pixel model that still ticks and still draws. The gate is already
at the head of `Gob.added(RenderTree.Slot)`; not attaching the `Drawable` is one more branch in it,
and it lets this feature's suite hide through the API it ships rather than the maintainer's addon.

## Acceptance criteria

1. **The guarantee.** With an addon subscribed to `GobAdded`, no object is drawn before its handler
   has run. `[manual]` for the judgement (walk into unseen ground with a hider armed; no flash),
   automated through a new `render()` counter proving the hold engaged.
2. **Nothing is lost, duplicated or stranded.** Every object that arrives still fires `GobAdded`
   exactly once and still ends up drawn. A suite reconciles every firing over a bounded window against `s:world():gob():list()`.
3. **A handler reads what it reads today.** `gob:name()`, `gob:sdt()` and `gob:hitbox()` answer for a
   resource-drawn object at handler time, and stay `nil` for a `Composite` still loading: the hold costs no data.
4. **A write made in the handler is in force on the first drawn frame.** `gob:overlay():add(key)`
   from `GobAdded` no longer meets the *not renderable yet* refusal at all, because the object holds
   no slots to fail against.
5. **`gob:visible(b)`.** `gob:visible()` reads, `gob:visible(b)` writes and chains, the pair
   round-trips. An object set invisible is not drawn **and not clickable** — the pick follows the
   drawn geometry, which is already true of `gob:scale(0.001)` today. Setting it back re-attaches the
   `Drawable` to the slots the object already holds. It composes with `gob:scale(k)`. Like a size, it is written to every character that can see the object, it dies
   with the object, and a `:reload` or a disable puts back everything the addon hid.
6. **Disarmed by default.** With nothing subscribed to `GobAdded`, nothing is held and the engine
   path is unchanged. Verified by reading the site — a suite subscribes, so it cannot prove its own
   absence.
7. **Nothing can strand the world.** A grace timeout draws a held object anyway, and an object that
   never enters an `OCache` — a client-only gob, a `Virtual` — is never held at all.

## Out of scope

- **Replacing a gob's resource, model, texture or sound.** The boundary is the *decision*: this
  feature guarantees when an addon is asked and lets it answer *not drawn*, never *drawn as
  something else*. Replacement is a resource-resolution surface (`Session.CachedRes.set`,
  `Resource.Pool`), and it is whole on its own.
- **A name-keyed predicate the engine evaluates without Lua.** Faster than a hold and far narrower;
  the hold is what generalises to any handler.
- **Hiding the model while keeping the click.** One verb, one meaning: invisible is not there.

## Docs impact

Pages written: `docs/addons/api/gob.md` (`gob:visible`), `docs/addons/api/types/world.md` (the Gob
snapshot's new field), `docs/addons/api/event/bus/world.md` (the guarantee),
`docs/addons/api/threading.md` (the step's ordering promise),
`docs/addons/api/client/profiling/counters.md` (the `render()` row),
`docs/client/boot-and-loop.md` (`Loading` as a parking primitive, and `Waitable.Queue` behind it),
`docs/client/state.md` (the monitor an `OCache.ChangeCallback` fires under — the map toll 114.1 paid,
unforeseen here because the whole plan rests on that fact and no page stated it).

Derived impact set — `grep -rn "GobAdded" docs/` (20 hits), `grep -rn "next frame\|already in the
tree" docs/` (11 hits), `grep -rn "visible" docs/addons/api/` (1 hit). Three go stale, none of them
mentioning the new syntax:

- `docs/addons/api/overlay.md:107` — *"a gob the client cannot draw yet takes no overlay at all, so
  attach from `GobAdded` or a timer instead"*: the advice becomes a guarantee.
- `LuaGobOverlay.ensure`'s refusal text names `GobAdded` as the workaround for that same case.
- `docs/addons/api/gob.md:37` — *"**The two writes go the other way**"*: there are three.

`docs/client/world-3d.md` is **151 lines, over its 150 ceiling** (ROADMAP, filed 070). This feature
must not write there; if a task finds it must, it splits the page in that task.

## Context files

- `src/haven/Gob.java` — 1, 2, 3
- `src/haven/MapView.java` (`Gobs`, `addgob`) — 1
- `src/haven/render/RenderTree.java` (`TreeSlot.add`), `src/haven/RUtils.java` — 1, 3
- `src/haven/Loader.java`, `src/haven/Loading.java`, `src/haven/Waitable.java` — 1
- `src/haven/OCache.java` — 1
- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3, 4
- `src/io/brodgar/addon/GobScale.java`, `GobIntent.java`, `LuaGob.java`, `UiApi.java` — 3
- `src/io/brodgar/addon/LuaGobOverlay.java` (`ensure`'s refusal text) — 4
- `src/io/brodgar/addon/ProfHandle.java` — 2
- `docs/addons/api/gob.md`, `docs/addons/api/types/world.md` — 3, 4
- `docs/addons/api/event/bus/world.md`, `docs/addons/api/threading.md` — 4
- `docs/addons/api/client/profiling/counters.md` — 2
- `docs/addons/api/overlay.md` — 4
- `docs/client/boot-and-loop.md` — 1
- `DOCUMENTATION.md` — 4
