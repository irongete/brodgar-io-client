# 126 — plan

## Approach

### Containment, and where the quarantine happens

`AddonManager.callLua` grows a third `catch`, after the two it has. It catches `Throwable`, and it
**rethrows two kinds**: `ThreadDeath`, and anything caught while `Thread.currentThread().isInterrupted()` —
the quit path interrupts the thread held in `Client.mt`, and a containment that swallowed that would keep
the client alive past its own exit. Everything else is contained.

The handler must stay allocation-light: it may be running just after an `OutOfMemoryError`, so it logs a
constant-shaped line and files the addon, and does not build a report. `trace(t)` keeps printing the Java
stack to stdout, which is the only evidence an `Error` leaves.

**The quarantine is deferred.** `autoDisable` calls `AddonRegistry.teardown(a)` and `addons.remove(a)`, and
its own javadoc says it is called "from `enforceSoftBudget` at end of tick, so mutating `addons` here is
safe". `callLua` is called from inside that iteration, and from off-thread handlers. So `callLua` records
`(addon, reason)` on a new `ConcurrentLinkedQueue` — the shape `autoDisabledWarn`, already a
`ConcurrentHashMap`, sets the precedent for — and `enforceSoftBudget` drains it at end of tick and calls the
existing `autoDisable` for each. One announcement path, one safe point, one panel string; nothing new is
invented and `AddonRegistry`'s `auto-disabled (…)` row renders it already.

The `:lua` REPL owner (`consoleOwner`) is not in `addons` and `enforceSoftBudget` exempts it. A queued
quarantine naming it is logged and dropped, never torn down: taking the REPL away from the maintainer
because a typed expression overflowed is the opposite of the point.

### The instruction budget under concurrent entry

`threading.md` states that an action handler, an inbound message handler and the step can each be running
one addon's Lua at once, and `AddonManager.stepping` is not the serializer — its javadoc says it "guards no
state, and nothing else ever takes it". So `Watchdog.remaining`, one plain field per environment, is shared
by concurrent entries: arming one resets the other's budget and decrements are lost.

`onInstruction` is the hottest callback in the client, so the budget is **thread-confined, with the UI
thread on a field and everyone else in a map**:

- `long uiRem` — touched only by `UILoop.th`, which is `public final` and is how the client already asks
  "am I the thread that ticks and draws". No `volatile`, no CAS: confinement, not synchronisation.
- `ConcurrentHashMap<Thread, long[]> off` — the rare off-thread entries. `arm()` puts a holder in it,
  and the entry is removed in `callLua`'s `finally` so a dead Loader thread leaves nothing behind.

`onInstruction` costs one reference compare against `UILoop.th` on the common path. `UILoop.th` is assigned
last in its constructor, so an early entry reads it null, fails the compare and takes the map — correct,
merely slower, and only during boot.

### The two caps

`Json` threads a `depth` argument through `write`/`writeTab` and refuses past `DEFAULT_MAX_DEPTH`, the same
cap and the same `-Dhaven.addon.json.maxdepth` property `parse` reads. It follows the **cycle rule already
there**: strict mode (`hafen.json():encode`) raises, forgiving mode (the store's flush, the `:lua` result)
writes a `"<too deep>"` placeholder exactly as it writes `"<cycle>"`. A saved-variables flush must not fail
because a table got deep.

`Gltf` gets its bounds before its allocations: `count` is validated against a cap and the product is
computed in `long` before any `new float[…]`; a `bufferView`'s `buffer` index is range-checked instead of
indexing with the `-1` default; index accessor values are checked against the vertex count. Every refusal
goes through the existing `err(name, …)` helper, so it reads as this model's error and names what is wrong.

For the node graph, the parser **enforces glTF's own invariant** — a node has at most one parent — with a
`seen` array beside the existing `visiting` cycle flag. A document that reaches a node twice is invalid and
is refused naming that, which is what `models.md` already promises for an unsupported feature. This keeps
`visiting` for the cycle case and adds no budget to tune.

## Files to create or modify

| File | What |
|---|---|
| `src/io/brodgar/addon/AddonManager.java` | `callLua`'s `catch(Throwable)`; the pending-quarantine queue; the drain in `enforceSoftBudget` |
| `src/io/brodgar/addon/Sandbox.java` | `Watchdog`'s thread-confined budget; `arm()`/`disarm()` |
| `src/io/brodgar/addon/Json.java` | `depth` through `write`/`writeTab`; strict raises, forgiving placeholders |
| `src/io/brodgar/addon/Gltf.java` | bounds before allocation; the one-parent invariant |
| `docs/addons/runtime.md` | what an `Error` does; the budget under concurrent entry |
| `docs/addons/api/json.md` | `encode`'s depth cap, beside `parse`'s |
| `docs/addons/api/virtual/models.md` | what a malformed or hostile model does |
| `docs/client/boot-and-loop.md` | gotcha: `UILoop` catches only `InterruptedException` |
| `addons/126-a-failure-stops-at-the-addon.1` … `.5` | one suite per task |

## Risks and gotchas

- **`autoDisable` is `private static` and mutates `addons`.** Calling it from `callLua` corrupts the
  iteration the step is inside. The queue exists for exactly this and is not decoration.
- **Catching `Throwable` can swallow the quit.** `Client.EventQueue.event` and the `q` command both
  `interrupt()` the thread in `Client.mt`; `AddonManager.quiet()` already closes Lua to every thread but the
  one leaving. The rethrow rule above is what keeps that door open.
- **`OutOfMemoryError` recovery is not guaranteed.** Containment is still strictly better than ending the
  UI thread, but the handler allocating a message could throw a second one. Keep it constant-shaped.
- **`UILoop.th` reads null early** (its own gotcha (a) in `boot-and-loop.md`). The map fallback covers it.
- **A glTF node under two parents is an instance, not a duplicate** — the same mesh with a different world
  matrix. Anything that skips the second visit silently drops geometry.
- **`Json.write` serves the store and the REPL, not only `encode`.** A cap that raises in every mode turns
  a deep table into a lost saved-variables flush.
- **`Sandbox.INSN_CAP <= 0` disables the cap** (`arm` writes `Long.MAX_VALUE`). The new shape keeps that.

## Discarded alternatives

- **A per-addon lock around every entry into Lua** — it would fix the counter and LuaJ's own state at once,
  and it deadlocks: an inbound message handler already holds its tree's `UI` monitor and would want the
  addon's, while the step holds the addon's and takes a tree's inside the addon's Lua. That inversion is the
  shape this client deadlocks in, and `threading.md` refuses two trees at once for the same reason.
- **`ThreadLocal<long[]>` read on every instruction** — correct and simple, and it costs a
  `ThreadLocal.get()` per VM instruction on the client's hottest callback. Confinement to the one thread
  that runs almost every entry gets the same correctness for a reference compare.
- **Making `Watchdog.remaining` `volatile`** — it removes the visibility half and leaves the semantic half:
  the two entries still share one budget, so arming one still resets the other.
- **Memoizing visited nodes in `Gltf.walk`** — it bounds the walk and it is wrong: a node reached by two
  paths is a legitimate instance with its own baked transform, and skipping it drops that geometry from the
  model with no error.
- **A total-expansion budget for the node walk** — it bounds the work without deciding whether the document
  is valid, so a legal deep model and an abusive one fail the same way, and the cap needs tuning. The glTF
  invariant answers both without a number.
- **Repopulating `Refusal.MOVED`/`KEYS`** — not this feature's ground, and not a defect: `11bf2871c` emptied
  them deliberately because nothing is released. It belongs to the contract feature, and as comment drift.
