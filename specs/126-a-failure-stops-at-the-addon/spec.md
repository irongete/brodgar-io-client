# 126 — a failure stops at the addon

## What and why

`runtime.md` promises that an error inside a handler, a timer or a draw callback "is logged with your
addon's id and isolated, so it takes down neither your other handlers nor another addon nor the client",
and seven other pages repeat that promise. It holds for `LuaError` and `RuntimeException`, which
`AddonManager.callLua` catches. **It does not hold for `Error`.** Nothing under `io.brodgar` catches
`Throwable` but `ProfHandle`, and `UILoop` catches only `InterruptedException` — so a `StackOverflowError`
or an `OutOfMemoryError` raised under an addon's Lua leaves the choke point, leaves the step, leaves the
frame loop, and ends the Haven UI thread. The addon's own `pcall` never sees it either.

Three paths reachable from ordinary Lua raise one today:

- `Json.writeTab` guards reference cycles and counts no depth, while `Json.parse` counts depth at both its
  recursive sites. A deep acyclic table overflows the stack inside `hafen.json():encode`.
- `Gltf` reads an accessor's `count` off the document and reserves `new float[count * comps]` before any
  cap, in `int` arithmetic that overflows; a `bufferView` with no `buffer` is read with a `-1` default used
  straight as a subscript. `MAX_VERTS` and `MAX_PRIMS` are counted in `walk` *after* a primitive is decoded,
  so they do not stand in front of the allocation.
- `Gltf.walk` marks `visiting[ni]` on entry and clears it on exit, which detects a cycle and not a revisit.
  A node DAG is re-walked once per path, and a chain of mesh-less nodes never reaches the branch where the
  two caps are counted, so a small file expands without bound.

The same choke point carries a second defect. `Sandbox.Watchdog.remaining` is a plain, non-`volatile`
`long` armed by `arm()` and decremented by `onInstruction()`, one per addon environment — while
`threading.md` states that an action handler, an inbound message handler and the step "can be running your
Lua while the step is running your Lua too". Under that concurrency the two entries share one counter:
arming one resets the other's budget and decrements are lost. The per-call instruction cap `runtime.md`
documents is therefore not per call.

## Acceptance criteria

1. An `Error` raised under an addon's Lua is contained at `callLua`. The client keeps running, no other
   addon is affected, and the failing addon is quarantined with a reason its AddOns panel row shows.
2. The quarantine happens at the safe point the CPU watchdog already uses — end of tick, outside the
   iteration over `addons` — never inline inside the call that failed.
3. A quarantine is announced the way the CPU watchdog's is: logged with the addon's id, and readable in
   the panel until the next load.
4. `hafen.json():encode` refuses a table nested deeper than its cap with a Lua error naming the cap and
   the limit, instead of overflowing the stack. `encode` and `parse` answer to the same depth cap.
5. A `.glb` whose accessor would allocate past the vertex cap raises a Lua error naming the cap, before
   the allocation. A `bufferView` with no `buffer`, and an index that falls outside the vertex count,
   raise errors naming what is wrong rather than an anonymous Java exception.
6. A `.glb` loads in time proportional to its node count. One whose node graph reaches a node twice is
   refused naming that node, as the invalid document it is; one that is a legal deep single-parent chain
   still loads.
7. The instruction budget is a full budget per entry into Lua even when two threads are inside one addon's
   environment at once.
8. Every page that states the isolation promise is revised or explicitly discharged with its reason.

## Out of scope

- **The soft per-tick CPU budget's measurement window.** `specs/ROADMAP.md` already states that it measures
  `Update`, surface `Update` and timers alone. That is *what it measures*; this feature is *whether a
  failure is contained*, and the two do not overlap.
- **Caps on the other `Error`-capable paths this audit did not verify** — the image decode in `AssetApi`
  and the locale regexes in `Catalogue`. Containment here is universal by construction, so those paths stop
  killing the client the moment task 1 lands; what they still lack is a cap of their own, which belongs to
  the surface that owns them. The boundary is: this feature contains everything and caps the three paths it
  read.
- **Restoring a quarantined addon without `:reload`.** The existing auto-disable lasts until the next load
  and this uses the same rule, so nothing new is owed here.

## Docs impact

Pages this feature writes:

- `docs/addons/runtime.md` — what an `Error` does, and the instruction budget under concurrent entry.
- `docs/addons/api/json.md` — `encode`'s depth cap, stated where `parse`'s already is.
- `docs/addons/api/virtual/models.md` — what a malformed or hostile model does, against the page's own
  "fails with an error that names the feature".
- `docs/client/boot-and-loop.md` — a gotcha: `UILoop` catches only `InterruptedException`, so anything a
  seam lets past it ends the UI thread.

Derived impact set — the isolation promise stated in prose, away from the pages above:

```text
$ grep -rn "isolated\|takes down neither" docs/
docs/addons/api/event/README.md:42       isolated: the error is logged and it breaks neither your other handlers nor the client.
docs/addons/api/font.md:97               (a different subject: a font's own drawing — not this promise)
docs/addons/api/menugrid.md:253          A handler that errors is isolated: it is logged, and it breaks…
docs/addons/api/timer.md:32              An error inside `fn` is logged and isolated, and it does not cancel the timer…
docs/addons/guides/debugging.md:21       They are isolated: the one callback dies, the rest of your addon and the…
docs/addons/guides/debugging.md:136      an isolated error is a logged line, not a stopped client.
docs/addons/guides/events-and-timers.md:29  A handler that errors is isolated: the error is logged…
docs/addons/runtime.md:76                …isolated, so it takes down neither your other handlers nor another addon…
```

Seven of those eight state the promise this feature makes true; `font.md:97` is a different subject and is
discharged on sight. Each is revised or discharged by the task that owns the surface, per
`DOCUMENTATION.md` §11.6.

## Context files

- `src/io/brodgar/addon/AddonManager.java` — 1, 2 (`callLua`, `autoDisable`, `enforceSoftBudget`,
  `autoDisabledWarn`, the step's `catch(RuntimeException)`)
- `src/io/brodgar/addon/Sandbox.java` — 1, 2 (`Watchdog`, `arm`, `INSN_CAP`)
- `src/io/brodgar/addon/Addon.java` — 1, 2
- `src/io/brodgar/addon/AddonRegistry.java` — 1, 2 (`teardown`, the panel's status string)
- `src/io/brodgar/addon/Json.java` — 3
- `src/io/brodgar/addon/Gltf.java` — 4, 5
- `src/io/brodgar/addon/AssetApi.java` — 4, 5 (read only: `newMesh`, how a parse failure reaches Lua)
- `src/haven/UILoop.java` — 1, 6 (read only: the frame loop's catches)
- `docs/addons/runtime.md` — 1, 2, 6
- `docs/addons/api/json.md` — 3
- `docs/addons/api/virtual/models.md` — 4, 5
- `docs/addons/api/asset.md` — 4, 5 (read only: `mdl:info()`, `:remove` — the door a model suite loads through)
- `docs/addons/api/threading.md` — 2 (read only: the concurrency it states)
- `docs/client/boot-and-loop.md` — 6
- `DOCUMENTATION.md` — 6
