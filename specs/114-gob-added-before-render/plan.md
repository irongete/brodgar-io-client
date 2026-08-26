# 114 — plan

## Approach

Three parts, and the third is what the first two buy.

**1. The pending flag.** `Gob` gains `addonpend`, a `volatile boolean` tagged `// addon:`. It is set
in `AddonManager`'s `OCache.ChangeCallback.added` — the same site that already enqueues the
`GobEvent` — and only when an addon holds a `GobAdded` subscription. It is cleared in
`drainGobEvents`, per **copy**, after `settleGob` has fired.

Two facts make this safe, and both are load-bearing:

- **`OCache.add` runs its whole callback loop inside `synchronized(ob)`, and `Gobs.addgob` opens with
  `synchronized(ob)`.** So no render add of that copy can begin before every callback has run: the
  flag is set before anything can read it. `ladd` has the same shape.
- **The flag is per `Gob`, not per gob id.** One object is as many `Gob`s as there are sessions that
  see it, each added to its own tree, and `settleGob` fires one client-wide event into the *first*.
  A later session's copy therefore gets no event — but its queue entry still exists, which is exactly
  where `GobIntent.applyTo(ge.gob)` already runs. Clearing the flag on `ge.gob` during that same walk
  releases every copy, and a copy the layer never queued (a client-only gob, an `OCache.Virtual`) is
  never set and so never held.

`drainGobEvents` currently collects touched **ids**; it must also keep the `Gob`s, and clear them
*after* the settle loop, so no copy is released before the event has fired.

**2. The waiter.** A `Loading` subclass in `io.brodgar.addon` whose `waitfor(Runnable,
Consumer<Waitable.Waiting>)` delegates to one `Waitable.Queue` held by the layer; `boostprio` returns
false, there being nothing to boost. `drainGobEvents` ends with `wnotify()`. `Loader.Future.run`
already catches `Loading`, registers on it and re-queues the task, so nothing new waits anywhere.
`Gob.updwait` is the in-tree precedent for a `Waitable.Queue` used this way.

**3. The gate**, at the head of `Gob.added(RenderTree.Slot)`, before `slot.ostate(curstate())`:
throw the waiter while `addonpend`; then attach every `RenderTree.Node` attrib **except** the
`Drawable` while `addoninvis`.

`RenderTree.TreeSlot.add` catches a `RuntimeException` out of `n.added(ch)`, calls `ch.remove()` and
rethrows — and `Placed.added` → `curplace()` already throws `Loading` through that same unwind when
the ground under an object is not in, so this path is exercised in production today. `addgob` catches
only `SlotRemoved`, so the `Loading` reaches the `Loader.Future`; `ob` stays in `Gobs.adding`, which
is precisely what the guard at the top of `addgob` re-checks on the retry.

**4. `gob:visible(b)`** is a second `volatile boolean` on `Gob` read by that same gate, with the
owner kept in `GobIntent` beside `scaleOwner` — the same two-tier shape `GobScale` already uses, so a
session that loads the object afterwards draws it hidden too, and `UiApi.teardownGobScales`'s
`GobIntent.dropOwner` + per-gob revert extends rather than doubling. Writing it on an object already
in a tree takes the revoke path: `RUtils.multirem(d.slots)` to hide, `RUtils.multiadd(gob.slots, d)`
to show — the helpers `Gob.setattr` itself uses for exactly this.

**5. The counter.** `render()` gains `gobsHeld`, an `AtomicLong` bumped by the gate, read through
`ProfHandle`. Without it the feature's central claim has no automated witness at all.

## Files to create/modify

| File | What |
|---|---|
| `src/haven/Gob.java` | `addonpend`, `addoninvis`, the gate at the head of `added(RenderTree.Slot)` — one `// addon:` block |
| `src/io/brodgar/addon/AddonManager.java` | set the flag in the `ChangeCallback`; clear it in `drainGobEvents`; the `Loading` subclass, the `Waitable.Queue`, the `wnotify`, the deadline sweep, the counter |
| `src/io/brodgar/addon/GobIntent.java` | the `visible` half of a `Record`, in `applyTo` and `dropOwner` |
| `src/io/brodgar/addon/LuaGob.java` | the `visible` verb and its refusals |
| `src/io/brodgar/addon/UiApi.java` | teardown puts back what an addon hid |
| `src/io/brodgar/addon/ProfHandle.java` | the `render()` row |
| `docs/client/boot-and-loop.md` | `Loading` as a **parking primitive**, `Waitable.Queue` behind it, `Gob.updwait` as precedent — upstream only |
| `docs/addons/api/event/bus/world.md`, `threading.md` | the guarantee, and the step's ordering promise |
| `docs/addons/api/client/profiling/counters.md` | `gobsHeld` |
| `docs/addons/api/gob.md`, `types/world.md` | `gob:visible`, the snapshot field, and the *two writes* sentence at `gob.md:37` |
| `docs/addons/api/overlay.md` | the `GobAdded` advice at :107 becomes a guarantee; `LuaGobOverlay.ensure`'s refusal text with it |

## Risks & gotchas

- **A session dying between enqueue and drain strands its copies.** `AddonManager.gobRescans`
  already counts session deaths and drives a rescan; that pass must clear `addonpend` on anything the
  dead session held. A wall-clock deadline on top, swept in `layerTick`, is the belt: a held object
  is released regardless after it, so no defect in the layer can leave the world undrawn.
- **One shared `Waitable.Queue` wakes every parked add on every drain.** Bounded by objects in
  flight, which is single digits; per-gob queues would trade that re-check for an allocation per
  arriving object.
- **The gate sits on every entry into a render tree**, `SessionGobs` and `MapView.Plob` included.
  That is why the flag, not the id, is the key — a gob nothing queued is never held.
- **An invisible object leaves the clickmap**, the pick following drawn geometry. Stated as the
  verb's meaning, and already true of `gob:scale(0.001)`.
- **`docs/client/world-3d.md` is at 151 lines, over its 150 ceiling.** Do not write there; the seam
  is mapped in `multi-session.md`. A task that must write there splits the page in that task.

## Discarded alternatives

- **Fire `GobAdded` synchronously from `OCache.ChangeCallback`** — that is Lua on a Loader thread
  holding the gob's monitor, while the frame takes the UI monitor and then the gob's in `OCache.ctick`:
  the one lock inversion the client's single direction exists to forbid, and LuaJ's `Globals` is one
  interpreter every addon shares.
- **Move the render-tree add onto the UI thread** — `slot.add(ob.placed)` throws `Loading` from
  `Placed.curplace()` when the ground is not in, so the frame would block on loads or re-poll,
  rewriting `Gobs.adding` and its `Loader.Future` restarts on the hottest path in the client.
- **A thread of its own for all addon Lua** — every read hands back a live interned object
  (`gob:name()` reads through to the live `Gob`), so each would race the tick; and an answer the
  Loader needs in microseconds has to be precomputed anyway, which lands back on declaring.
- **A name-keyed predicate evaluated without Lua** — answers only what is a pure function of the
  resource name, and the first object of an unseen name still races.
- **`gob:hide()` / `gob:show()`** — two names for one property and no read at all, against *arity is
  the verb, a bare adjective for a boolean*; `widget:visible(b)` already spells this property.
- **Keep the hitbox on an invisible object** (Replica's `HitboxAttribute`) — "not drawn but still
  clickable" is two facts under one name, and a shape-only surface is its own feature.
- **A `Waitable.Queue` per gob** — precise wakeups bought with an allocation per arriving object.
