# 117 — plan

## Approach

Two moves, in that order: make the invariant impossible to break, then make breaking it survivable.

### 1. The attrib map is written under the gob's own monitor (`Gob`)

Synchronize the private funnel `Gob.setattr(Class, GAttrib)` — which `setattr(GAttrib)` and `delattr`
both pass through — and `delattr` itself, which reads `attr` before calling it. Make `Gob.attr` a
`ConcurrentHashMap`.

The two do different jobs and both are needed. **The monitor is write atomicity**: `setattr` is a
read-modify-write across two structures — `attr.remove(ac)`, `RUtils.multirem` of the displaced
attrib's slots, `RUtils.multiadd` of the new one, `attr.put(ac, a)`, `prev.dispose()` — and two
writers interleaving in it leave a gob whose attrib map and whose render slots disagree. **The map
is read safety**: `Gob.added(RenderTree.Slot)`, `Gob.eqpoint`, `ModSprite`'s own walk and
`SpeakerIcon.nameTex` all read `attr.values()` from threads holding no monitor, and a weakly consistent iterator never throws where
a `HashMap`'s does.

**The lock order is the thing that must not change, and this keeps it.** The order in force is
**gob, then tree**: `MapView.Gobs.addgob` takes `synchronized(ob)` and only then calls
`slot.add(ob.placed)`; `OCache.add`, `remove`, `ladd` and `lrem` hold `synchronized(ob)` across
their callback loops; `Gob.deferred` runs each queued task under `synchronized(this)`. `setattr`
already reaches tree locks through `RUtils`, so synchronizing it goes the same way and adds no edge.
`OCache.GobInfo.apply` calls it already holding the monitor — Java monitors are reentrant, so that
path is unchanged.

**`Gob.added(RenderTree.Slot)` is not synchronized.** `RenderTree.TreeSlot.add` calls it while
holding the tree, so a monitor there would be tree-then-gob, the inversion. It needs none: every
caller already holds it, which is exactly what makes the walk safe once the writers do.

### 2. A dropped render add is not for ever (`MapView.Gobs`)

- **Bound the catch.** `addgob`'s `try` around `slot.add(ob.placed)` catches
  `RenderTree.SlotRemoved` alone. Add a `RuntimeException` arm that removes the gob from `adding`
  and issues a `Warning` naming its id and resource. `Gobs.added(Gob)` defers through
  `Loader.defer(Runnable, T)`, the `capex = false` overload, so today anything but `Loading` is
  rethrown out of `Loader.Future.run` and kills the thread. `Loading` must go on escaping — that
  parking is the retry.
- **Give `Gobs` a reconcile**, the one `SessionGobs` already has, on the same 0.25 s timer, called
  from `MapView.tick` inside the existing `synchronized(glob.map)` block beside `terrain.tick()`.
  Both halves: a gob in `oc` and in neither `current` nor `adding` is added; a gob in `current` the
  `oc` no longer holds is removed. `SessionGobs.tick()` becomes `super.tick()` plus its own
  `skipgob`/`vis` conditions rather than a replacement, so the two cannot disagree about the rule.

The eviction half is new to both: `SessionGobs.tick` evicts on `skipgob(ob) || !vis(ob)` and never
asks whether the `oc` still holds the gob at all.

## Files to create/modify

| File | What |
|---|---|
| `src/haven/Gob.java` | synchronize the `setattr` funnel and `delattr`; `attr` → `ConcurrentHashMap` |
| `src/haven/MapView.java` | `Gobs.addgob` bounded catch + `Warning`; `Gobs.tick()`; `SessionGobs.tick()` calls it; the call site in `MapView.tick` |
| `docs/client/state.md` | the monitor rule on the attrib-map row and who walks it from where; a row for what a dropped render add costs and what recovers it |
| `docs/addons/api/threading.md` | one line under *What runs beside you*: a gob write is safe from either group |
| `addons/117-an-object-the-client-has-is-drawn.1/`, `.2/` | the two suites |

## Risks & gotchas

- **`ConcurrentHashMap` refuses a null value.** `setattr(ac, null)` is the delete path; confirm it
  reaches `attr.remove(ac)` only and that the `attr.put(ac, a)` beside it stays inside its
  `a != null` arm. `attr` is already declared as `Map`, and `attrclass` cannot answer null, so the
  swap is one word — a null value is the only way it breaks anything silently. Iteration ORDER
  changes with the implementation: `Gob.eqpoint` returns the first `EquipTarget` it walks past, so a
  gob carrying two would answer the other one.
- **`Gob.attr` is package-private and read by reflection from outside `haven`** (`docs/client/state.md`);
  `SpeakerIcon.nameTex` walks it. Nothing may assume the concrete `HashMap`.
- **The monitor is now held across a `Loading`.** `RUtils.multiadd` throws it while a `Drawable`'s
  sprite resolves; `setattr` catches, restores `prev` and rethrows. `Gobs.addgob` already holds the
  monitor across that call, so the shape is not new — but confirm `Gob.defer`'s retry, which takes
  the monitor too, still parks rather than spins.
- **The reconcile must guard on `adding` as well as `current`**, or it adds a second slot for a gob
  whose deferred `addgob` has not run — read back as one gob's overlay painting twice a frame.
- **Test membership against the snapshot, never `getgob`.** `OCache.iterator()` walks
  `objs.values()` plus the `local` collections while `getgob` reads `objs` alone, and `objs` is a
  `MultiMap`, so one id can yield several gobs. An eviction asking `getgob` would drop every gob a
  caller put there with `ladd`. Key on the `Gob` object, as `current` does, never on the id.
- **`SessionGobs` must keep its culling.** The base cannot evict on a rule of its own while the
  member's view adds a second pass on top — put the question behind one overridable predicate (does
  THIS view hold this gob) that `SessionGobs` extends with `skipgob` and `vis`. Get it wrong and the
  merged view draws the member's whole `OCache`, which is the cost that culling bought back.
- **The eviction half needs the gob monitor.** `Gobs.drop` documents that every other caller of
  `removed(Gob)` holds it, because `TickList` serializes a gob's subtree on that monitor and an
  unsynchronized slot removal tears it out from under a sprite's autotick.
- **Keep the reconcile off the dormant path.** `MapView.tick` returns before `sessiontick()` when
  `dormant`; `Gobs.addgob` returns early on a null `slot`, so a dormant view would churn futures.

## Discarded alternatives

- **Take the monitor at each call site** — the API is about to hand these writes to addon code, and a
  rule kept by remembering it is one the verb written next year forgets; the funnel cannot be.
- **`ConcurrentHashMap` alone** — stops the exception and leaves the race: `setattr` spans the map
  and the render slots, so two writers still interleave into a gob whose attribs and slots disagree.
- **Synchronize `Gob.added(RenderTree.Slot)` as well** — `RenderTree.TreeSlot.add` calls it holding
  the tree, which is the one lock order this client forbids.
- **Copy `attr.values()` before walking it there instead** — an allocation per gob per slot add on
  the hottest path in the client, to make one reader safe while every writer stays wrong.
- **Catch `Throwable` in `addgob`** — an `Error` is not a dropped add, and running on after the
  renderer has gone is worse than stopping.
- **Retry the failed add at once** — a real defect would spin a Loader thread on it; the slow
  reconcile picks it up at walking pace and the `Warning` is what says it happened at all.
- **A set of failed gobs to skip** — a third piece of state to keep in step with `current` and
  `adding`, answering what the `OCache` already answers.
- **Fix `SpeakerIcon.sweep` alone** — it is the writer that fired, not the defect: `GobScale.apply`
  and `LuaGobOverlay.ensure`/`prune` carry it too, and the voice feature deletes `sweep` anyway.
