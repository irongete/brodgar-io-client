# Learnings — LuaJ, sandbox & the Lua bridge

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **LuaJ:** `ZeroArgFunction` accepts extra args, so `handle:off()` (method-call passes `self`) works
  with a `ZeroArgFunction` body. `error('x')` surfaces as a `LuaError` whose `getMessage()` includes
  `chunkname:line` (e.g. `@hello/main.lua:12: x`) — good for the isolation log. Chunk names: addons
  load with `@id/file` (a real path prefix), the REPL with `=lua` (verbatim).
- **The pre-1f addon envs were NOT sandboxed at all (1f-1).** `JsePlatform.standardGlobals()` bundles
  `JseIoLib` (`io`) AND **`LuajavaLib`** (`luajava.newInstance`/`bindClass` — arbitrary Java reflection),
  so any addon could reach straight into `haven.*`, bypassing the whole facade (P1). It does NOT include
  `debug` (that's `debugGlobals()`). The fix is a **constructive** whitelist (`new Globals()` + load only
  safe libs), NOT subtractive neutering — dangerous surfaces are then *absent*, not merely hidden, so you
  can't forget one.
- **LuaJ stdlib modules self-register in `package.loaded` on load**, so `g.load(new TableLib())` (and
  `StringLib`/…) throws `attempt to index ? (a nil value)` if `PackageLib` wasn't loaded first (they do
  `env.get("package").get("loaded")…`). So: load `PackageLib` too, then **strip** `require`/`module`/
  `package` in a hardening pass — the libraries are wanted, the require *machinery* is not. `JseBaseLib`
  must be first (it can't depend on package — `standardGlobals` loads it before PackageLib).
- **Watchdog without exposing `debug` (D-018 layer 1):** set `Globals.debuglib = new DebugLibSubclass()`
  **directly — do NOT `g.load(...)` it.** `LuaClosure.execute` calls `globals.debuglib.onInstruction(pc,
  v,top)` on every VM instruction, guarded **only** by `debuglib != null` (verified in bytecode). So
  assigning the field enables the per-instruction hook while **no `debug` table is ever installed** into
  Lua (D-017). Override `onInstruction` to decrement a per-call budget and `throw new LuaError(...)` on
  underflow → caught by the engine's per-callback isolation.
- **The null-`globals` NPE — override `traceback` (and `onCall`/`onReturn`):** an *assigned* (not loaded)
  DebugLib has a **null `globals` field**. LuaJ's default error path — `LuaClosure.execute` catch →
  `processErrorHooks` (when `running.errorfunc == null` && `debuglib != null`) → `debuglib.traceback(level)`
  → `callstack()` → `this.globals.running` — NPEs on **every** Lua error (incl. the watchdog's own throw!).
  Fix: override `traceback(int)→""` and no-op `onCall`/`onCall`/`onReturn` (both overloads). No call-stack
  is kept, so an empty traceback is honest; addon errors still carry LuaJ's `chunkname:line` on the message.
- **Budget must be RE-ARMED per call** (`Sandbox.arm(env)` before each `invoke`/`call`): the counter
  depletes as instructions run, so without a reset a long-lived env would eventually trip on innocent
  later code. Arm at the three entry points — `callLua` (handlers/timers/events), `Addon.run` (file body),
  REPL `eval`. Default cap 10 M ⇒ a tight infinite loop aborts in **~36 ms** (≈2 frames) — imperceptible
  for real callbacks, near-instant for a runaway. `-Dhaven.addon.insncap` overrides (`<=0` disables).
- **The `:lua` REPL is deliberately left UN-sandboxed** (full `standardGlobals()`, incl. `luajava`): it's
  the operator's own trusted console, and the sandbox constrains *shared addon code*, not the user. It IS
  watchdog-armed (typo protection: `:lua while true do end` aborts). So the REPL can do things a real addon
  can't — expected; don't mistake it for a sandbox hole when testing the API.
- **Soft per-tick CPU budget (D-018 layer 2) rides the ONE Lua choke point, `callLua` (1f-3).** Wrap
  `fn.invoke` in `System.nanoTime()` and accumulate into `Addon.tickLuaNanos`; `tick()` zeroes it at the
  top (AFTER the reload early-return, so a reload tick's one-off `OnLoad`/`OnEnterWorld` don't count) and
  `enforceSoftBudget()` evaluates at the bottom. It's the complement to layer 1's **per-call** instruction
  cap: layer 1 kills a single runaway callback (~36 ms), layer 2 catches an addon whose handlers each stay
  under the cap but **sum to** most of every frame. Use **consecutive** over-budget ticks with a **reset on
  any at-or-under tick** (strict `>` budget) so a heavy `OnEnterWorld` / one janky frame never trips it —
  only a *sustained* offender (default 10 ms × 30 ticks ≈ 0.5 s). **Auto-disable is session-only**: record
  a warning + run the 1f-2 `teardown` + drop from `addons`; do **NOT** touch the persisted enabled set (a
  reload/login re-tries; the user persist-disables via the panel). Enforce at **end of tick** so mutating
  the (CopyOnWrite) `addons` list is safe. The REPL owner is exempt for free — it's not in `addons`.
- **Two opposite arg conventions in the UI bridge — get them right (2a):** an addon's *callbacks* (`onDraw`,
  `onClick`, …) are invoked FROM Java via `fn.invoke(varargsOf(x,y,b))`, so **no `self`** — `onClick(x,y,b)`
  reads arg1..3 directly. But the *wrapper/handle methods* (`g:text(...)`, `win:move(...)`) are Lua
  **colon-calls INTO** my Java `VarArgFunction`s, so **`self` is arg1** and real params start at `arg(2)`.
  Mixing these up shifts every coordinate by one arg. Headless-verify the callback side (mouse dispatch with
  a recording Lua fn — no GOut needed); verify the wrapper side in-game (text/bars land at the right pixels).
- **`callLua` returns `Varargs` now (was `void`)** so input forwards can read the handler's return for
  "consume" (`.arg1().toboolean()`); every existing caller ignores the return, so it's a safe widening. Route
  ALL widget callbacks (draw/tick/mouse) through `callLua` — that's the single choke point that arms the
  watchdog (D-018 layer 1), isolates errors, and accounts CPU time. Don't call `fn.invoke` directly anywhere.
- **Draw callbacks escape the soft per-tick CPU budget (2a known gap):** `onDraw` runs in `UI.draw`, which is
  *after* `tick()` zeroes `tickLuaNanos` and *after* `enforceSoftBudget()` (both in the same frame's tick).
  So draw Lua time is wiped before it's ever evaluated → layer-2 auto-disable never sees a draw hog. The
  per-call **instruction cap (layer 1) still aborts** a single runaway draw. Folding draw time into the budget
  needs a frame-boundary accounting change (deferred); for now, document it and rely on layer 1 for draws.
- **LuaJ `isstring()` is TRUE for numbers (2b test gotcha).** A filter validation `filter.isfunction() ||
  filter.isstring()` accepts a **number** (Lua coerces number↔string), so `gobOverlay(3, fn)` treats `3` as
  the substring filter `"3"` — consistent with the existing `world.gobs` `matches()` (same `isstring()`), so
  it's intended, not a bug. A truly-invalid filter to reject is a table/boolean/nil (`isstring()` false).
  Caught by the headless test asserting rejection → fixed the *test* (use a table), not the code.
- **LuaJ canonicalises small whole doubles to LuaInteger (2e-1 test gotcha).** `LuaValue.valueOf(9.0)` returns a
  `LuaInteger`, so a `Long` of 9 marshalled via the double path reads back `.isint()==true` (the VALUE is exact —
  correct). The `>2^53`/precision caveat only bites large values: a `Long` above 2^31 (`1L<<40`) stays a
  `LuaDouble`. Assert the marshalled *value* (`.todouble()==9`), not the internal int/double tag, for small
  numbers — the tag is an implementation detail LuaJ is free to optimise.
- **4d — `optint(default)` is the clean way to do optional trailing Lua args.** `a.arg(3).optint(0)` returns the
  default for an ABSENT/nil arg and validates type for a present one (a string arg → a clear LuaJ "bad argument"
  error) — no `narg()` juggling. But **`isstring()` returns true for NUMBERS** in LuaJ (Lua coerces number→string),
  so a "must be a string" guard on a wdgmsg name only catches nil/table/bool, not a stray number — test the guard
  with a **nil** arg, not a number.
- **4g — LuaJ `isstring()` is TRUE for numbers (they coerce), so it does not reject a numeric arg.** A `rename(kin,
  5)` passes `name.isstring()` and coerces to `"5"` — consistent with the existing `flower`/`item` verbs, and fine
  (Lua-idiomatic). To actually REJECT a non-string, test a non-coercible type (a table/bool) — `{}` → `isstring()`
  false. Mirror image: `isnumber()` is true for a numeric STRING (`"5"`) but false for `"x"`. Know which coercions
  your validation lets through before writing the test's expected error.
- **V6: build `atan2` from `math.atan` (1-arg) + quadrant fixes rather than depend on `math.atan2`.** It's an optional
  stdlib function (absent in some Lua 5.1 / LuaJ builds); the 1-arg `math.atan` + `math.pi` are always present.
  Headless-verify the quadrants in a real `Sandbox.create()` env (use `.invoke()` for multi-return chunks — `.call()`
  returns only the first value, which silently passes a `== expected` on the first case and fails the rest).
- **R1: a bridge-owned handle with no server id carries its Java ref as an OPAQUE USERDATA, not via a global id-map.**
  Widgets/items/gobs re-resolve through an int ref + `UI.getwidget` because they can go stale; an image can't (it's a
  pure client asset the bridge owns), so there's nothing to re-resolve — the reference travels *in* the handle. Storing
  it as `LuaValue.userdataOf(luaImage)` (the **same facade-safe pattern `LuaMarshal` uses** for hook args) is P1-clean:
  no metatable ⇒ no Java method reachable from Lua, and unforgeable ⇒ the sandbox omits `luajava`. `g:image` reads the
  handle-table's userdata field → casts → draws. Simpler than a registry (no counter, no map teardown; GC'd with the env).
- **(017) LuaJ userdata DOES take a metatable, and that makes it the right OOP handle.** `LuaValue.userdataOf(obj,
  mt)` + `mt.__index = <methods table>` gives real methods; verified in the 3.0.1 bytecode: `LuaUserdata.get(k)`
  is `m_metatable != null ? gettable(this,k) : NIL`, and `set(k,v)` errors `"cannot set <k> for userdata"` unless
  a `__newindex` handles it — so the object is **immutable from Lua for free**, which a LuaTable handle is not
  (one addon function could scribble on an object every other one shares). `__tostring`/`__name` work; and
  `tojstring()` is `String.valueOf(m_instance)`, so a plain Java `toString()` already yields a sane `tostring`.
  Equality: `raweq` is `this == val || (same metatable && m_instance.equals(...))`, and `hashCode()` delegates to
  the instance — so interned userdata is reliable both as `==` and as a **table key**.
- **(017) A callable TABLE (`__call`), not a bare function, is what makes a hard cut visible from Lua.**
  `hafen.gob = <function>` would make `hafen.gob.health` throw "attempt to index a function"; an empty
  `LuaTable` with `mt.__call` makes `hafen.gob(id)` work AND `hafen.gob.health` read as plain `nil` — so the
  removed flat API fails the way a *missing* field fails, and `if hafen.gob.health then` is a usable feature
  probe. Note `__call` receives the table as arg1: real params start at `a.arg(2)`.
- **(017) Interning Lua handles: `Map<K, WeakReference<LuaValue>>` + a `ReferenceQueue`, NEVER `WeakHashMap`.**
  `WeakHashMap` is weak *keys* — the wrong axis; the handle is the value. The key + the dead `WeakReference`
  survive collection, so without a **drain on every access** (`while((r = queue.poll()) != null)`) a per-tick
  world sweep leaks tens of thousands of map entries per session. The `WeakReference` subclass must carry its
  own key to unmap itself, and the drain must check `live.get(key) == thatRef` before removing (a fresh handle
  may already have replaced it). Headless proof: 20 000 ids → `System.gc()` → one more access → 1 entry left.
- **(017) The intern cache belongs on the `Addon`, never `static` on the hub.** Per-addon means no Lua value
  crosses a sandbox boundary (D-017) and the cache dies whole with the env on `:reload`/disable — a static one
  would outlive the reload (the C1 console-command trap in a new costume). The per-addon metatable falls out of
  the same rule, and it is what keeps two envs' handles for the *same* id distinct.
- **(019.4) NEVER name a captured local `name` inside an anonymous `VarArgFunction` — LuaJ's `LibFunction`
  declares `protected String name`.** An inherited field **shadows an enclosing method's local** of the same
  name, so `create(owner, final String name)` + `new VarArgFunction() { … begin(owner, name) … }` silently reads
  LuaJ's null field, not the captured value. It compiles, it runs, and it registers under a `null` key; the
  crash surfaces far away and unrecognisably (`LuaString.valueOf` → `NPE: String.toCharArray() because
  <parameter1> is null`) in whatever later walks that map. Cost: a whole test round. Rename the parameter (`nm`)
  — or declare a **local** `String name = a.arg(2).checkjstring()` *inside* the body, which shadows the field
  and is what every other bridge closure in the package happens to do, which is why only this one was bitten.
  Same trap for `opcode` (also `LibFunction`). Note the asymmetry that misleads you while debugging: a name
  passed as a **method argument** (`measure(owner, name, fn)`) is fine — only closure bodies read the field.
- **(019.4) `callLua` swallowed the Java stack of an engine-side exception; print it (to stdout) or debug blind.**
  A `LuaError` carries file:line and needs nothing more, but when a bridge call throws a Java exception the Lua
  message names only where the addon *called in*. Print `e.getCause()`'s stack — but **exclude `haven.Loading`**:
  in this client "the resource isn't here yet" is control flow, thrown constantly while the map streams in, and
  a stack per occurrence buries the log the print exists to clarify. One line in `catch`, one `instanceof` guard.
- **(019.8) LuaJ 3.0.1's `string.format` is NOT C's: `%f`/`%g`/`%e` ignore the PRECISION and `%s` ignores the
  WIDTH.** `("%.2f"):format(10.8521999…)` returns `10.852199999987988` (the raw double, i.e. `tostring`), and
  `("%-16s"):format(x)` pads nothing; only `%d` honours a width (`%5d` → `"    7"`). So every formatted number
  an addon shows is unreadable and every space-padded column is misaligned — and it looks like a *font* problem
  in a window, which is where the hour goes. Round by hand (`math.floor(v*10^d+0.5)`, re-attaching the trailing
  zeros `tostring` drops) and pad by hand (`string.rep(" ", n-#s)`, which is exact in a mono font). Verify
  format assumptions against the shipped jar, not against Lua's manual: `java -cp lib/brodgar/luaj-jse-3.0.1.jar
  lua <script>` is a 5-second check. Bitten in `addons/profiler` AND retroactively in `addons/hello`'s 019 dumps.
- **(021.1) A LuaTable keyed from 0 lies about its length, and `__len` will not save you.** Key `0` lands in the
  **hash** part, so a "dense 0..143" array answers `#t == 143` and `ipairs` starts at 1 — slot 0 is silently
  skipped by every idiomatic loop. LuaJ 3.0.1's `LuaTable.len()` returns the raw array length **without
  consulting the metatable**, so a `__len` metamethod (legal in 5.2+) is simply ignored for tables; only
  userdata gets it. So an engine-side 0-based index CANNOT be the Lua array key: expose the array 1-based and
  put the real index on the element (`slot:index()`, [D-057](../decisions/architecture-api.md)). Cheap to
  confirm before designing around it — a 15-line `Globals` harness with `assert(#t == 144)` and an `ipairs`
  count runs headless off `lib/brodgar/luaj-jse-3.0.1.jar` in seconds, no client, no login.
- **(021.1) `LuaValue.userdataOf` interning gives table-key identity for free — including across two entry
  points.** Because `hafen.actionbar()` fills its array from the same per-`Addon` weak-valued cache
  `hafen.actionbar(n)` uses, `hafen.actionbar()[1] == hafen.actionbar(0)` is true without any `__eq`
  metamethod, and `seen[slot] = true` works. Worth asserting in the same headless harness: an accidental
  fresh `userdataOf` per call passes every read test and fails only where an addon dedupes.
- **(024.2) A weakly-interned handle cannot hold state — the state belongs in the cache, keyed the same way.**
  The intern cache is weak-*valued* on purpose (an addon that drops its Sound must not pin it), so the userdata
  can be collected and re-minted **while its clip is still sounding**: any `List<Audio.CS>` living on the handle
  would silently reset mid-playback and `hafen.sound()` would under-count. Playback state therefore sits in a
  per-`Addon` map keyed by the same resource NAME the intern cache uses, and the handle stays a pure address.
  **Rule: interned userdata is an identity, not a record.** If a section needs per-entity state that outlives a
  Lua reference, hang it off the owner keyed by the intern key — and it comes with a bonus, since that map is
  exactly what the collection form (`hafen.sound()`) and the teardown sweep both need to walk.
- **(025.1) Inside an anonymous `LuaFunction`, a bare static call can bind to a `LuaValue` method.**
  `LuaFunction` inherits `name()` from `LuaValue`, so a helper `static String name(Buff b)` on the
  enclosing class is SHADOWED inside `new VarArgFunction() { ... }` — `name(b)` does not compile against
  the intended overload (or worse, quietly resolves to something else when arities line up). Write it
  fully qualified (`LuaBuff.name(b)`) at those call sites and leave a comment saying why, since it looks
  like gratuitous qualification. The same trap waits for any helper named `type`/`len`/`get`/`call`/
  `tostring` — the `LuaValue` surface is wide, so prefer distinct helper names when adding new ones.
- **(028.1) `pcall` works over a CALLABLE TABLE, so a `hafen.x(...)` namespace is still probe-able.**
  LuaJ's `BaseLib.pcall` only requires a non-nil value and then `invoke`s it, and `LuaValue.invoke` routes a
  table through its `__call` metamethod — so `pcall(hafen.asset, "fonts/demo.ttf")` behaves exactly like
  `pcall` over a function. This is what lets `hello` keep probing for an optional bundled file after the
  namespace stopped being a plain function table (`hafen.font.load` → `hafen.asset`). Worth knowing before
  converting any namespace to the D-056 callable shape: no caller that wrapped it in `pcall` breaks.
- **(028.1) The `LuaValue`-method shadowing trap (025.1) bites hardest on `load`.** A loader's natural name
  IS `load`, and `LuaValue.load(LuaValue)` exists — so `load(owner, path)` inside `new VarArgFunction(){…}`
  fails to compile with a *misleading* "method load in class LuaValue cannot be applied to given types".
  Qualify it (`AssetApi.load(...)`) and comment why; `FontApi` had carried exactly that comment since F1.
- **(028.3) An explicit `nil` argument to a D-056 callable namespace is the COLLECTION form, not an error.**
  The `__call` handlers all branch `if(key.isnil()) return <the collection>` — and LuaJ cannot distinguish
  "called with no argument" from "called with an argument that happens to be `nil`" (both arrive as `NIL` at
  `a.arg(2)`). So `hafen.asset(maybeNil)` quietly hands back the *list* instead of raising, and the same is
  true of `hafen.gob`/`kin`/`buff`/`meter`/`sound`/`menugrid`/`actionbar`. It cannot be fixed by checking
  `a.narg()` either — a Lua caller writing `f(x)` with `x == nil` genuinely passes one argument in some
  paths and none in others, so the check would be inconsistent rather than correct. Treat it as contract:
  **document that an explicit `nil` is the collection form** and tell callers to test the variable before
  passing it. The failure it causes is silent (a table where an object was expected), so it is worth a line
  in every callable section's page — 028.3 put it in `asset.md`.
- **(029.1) Do not name a static helper `type` in a class whose methods live in anonymous LuaJ function
  subclasses.** `OneArgFunction` extends `LuaValue`, which already has a `type()`; inside the anonymous body
  `type(w)` resolves against the *inherited* method set first and fails with `Widget cannot be converted to
  int` — a type error that says nothing about the shadowing that caused it. The same trap is waiting for any
  helper named after a `LuaValue` member (`len`, `call`, `get`, `set`, `method`, `tostring`). Name the helper
  for the domain (`typeName(Widget)`), not for the Lua method it backs.
- **(030.2) An interned userdata is a valid, identity-stable Lua TABLE KEY** — `state[widget] = …` works, which is
  what makes a two-event API (`appear`/`disappear`) usable without inventing an id. LuaJ's `LuaUserdata` overrides
  both `hashCode()` (→ the wrapped Java object's) and `raweq` (→ same metatable + `m_instance.equals`), and our
  wrapper types (`LuaWidget`, …) override neither, so both reduce to Java identity — and interning means the same
  widget always yields the same `LuaValue` anyway. Verified against `luaj-jse-3.0.1.jar` with `javap` rather than
  assumed: a wrapper that DID override `equals`/`hashCode` (e.g. one keyed by a snapshot's fields) would silently
  collapse distinct entities into one table slot.
- **(035.4) LuaJ 3.0.1's `string.format` IGNORES a float precision — `%.3f` prints the whole double.**
  `("%.3f"):format(1.5739000000039027)` returns `1.5739000000039027`, not `1.574`. `%d` and its width/zero
  flags (`%03d`) *are* honoured, and `%s` prints LuaJ's own shortened `tostring` (`1.5739`) — which is a
  different number of digits than you asked for, so it is not a fix either. Any suite line carrying a
  measurement must therefore round in integer arithmetic:
  `local t = math.floor(x * 1000 + 0.5); ("%d.%03d"):format(math.floor(t / 1000), t % 1000)`. Nothing errors
  and nothing fails — the verdict is still correct, it is merely 17 digits of noise in the block the
  maintainer pastes back — which is exactly why it survives every review that is not a real run.
- **(037.2) Validate `istable()` BEFORE reading a field off an argument — `LuaValue.NIL.get("x")` throws.**
  The natural spelling `LuaValue x = c.get("x"), y = c.get("y"); if(!c.istable() || …) throw new LuaError(<a
  helpful message>)` never reaches the helpful message when the caller simply forgot the argument: the
  `get` on `NIL` throws LuaJ's own *"attempt to index ? (a nil value)"* first, and the addon author gets a
  stack trace instead of the shape they wanted. Test the container, then read it. Caught headlessly by the
  refusal check for `grid:height()` — a "guiding error" is only guiding if the guard runs before the read.
- **(037.2) In LuaJ a numeric STRING answers `isnumber()`, so "was a number passed?" is `type() ==
  TNUMBER`.** 037.1's `LuaIconCat` already knew the reverse of this (it tests `isnumber()` *before*
  `isstring()` to catch an index). The 64-bit-id arguments need the other direction: `hafen.map.grid(id)`
  must **refuse a number** (a Lua double cannot carry a grid id) while accepting the decimal *string* the
  API itself hands out — and `"48133101501480977"` answers `isnumber() == true`. Only `v.type()`
  distinguishes them.
