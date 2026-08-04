# Decisions — Architecture & API design

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-008 — The engine lives in `src/io/brodgar/addon/`; Lua is a stable facade ✅
**Decision.** Engine + bridge live in a new `io.brodgar.addon` package. Addons target a stable
Lua facade (`hafen.*`) and never touch `haven.*` directly. `haven`-package helpers are used only
where package-private access is required.
**Rationale.** Isolates upstream churn (P1); enables complete resource ownership/teardown (P2);
mirrors the non-invasive voice-integration pattern.
**See.** [01-architecture.md](../design/01-architecture.md).

### D-011 — Invasiveness is acceptable where it yields materially better features ✅
**Decision.** The earlier "minimize core edits" priority is **relaxed**. The addon system may edit
the `haven` core (add first-class hook points, hookable subclasses, dispatch instrumentation)
when doing so enables substantially better capabilities. Minimal-edit seams are still preferred
*when equivalent*, but are no longer a hard constraint.
**Rationale.** User direction: for the addon system, good features outweigh strict
non-invasiveness. Enables a proper hook/interception system (pre/post/replace, `preventDefault`).
**Consequences.** Supersedes the strict framing of [G10](../design/00-vision-scope.md); the invasiveness
ledger ([11-core-hooks.md](../design/11-core-hooks.md)) becomes a *record* of edits, not a *budget*. Still
keep edits centralized and tagged (`// addon:`) so upstream merges stay manageable.
**See.** [13-hooks-and-interception.md](../design/13-hooks-and-interception.md).

### D-012 — Reference-based reads, ONE flat calling style, unified under `hafen.gob` ✅
**Decision.** Entities are addressed by an explicit **reference**, like WoW unit tokens — not
implicit global state. A **GobRef** is a gob id (number) or a token (`"player"`, `"partyN"`,
`"target"`). There is **exactly one** calling style: a **flat accessor** `hafen.gob.<attr>(ref)`
(e.g. `hafen.gob.health("player")`) — **no** parallel handle/OO style for gobs ([D-013](architecture-api.md)).
All per-gob reads live under **`hafen.gob.*`**; `hafen.player`/`hafen.char`/`hafen.party` hold only
data with no per-gob equivalent. Reference accessors **re-resolve each call** (fresh; nil if gone);
**snapshots** (bulk enumeration via `hafen.world.gobs`) are point-in-time copies. **Items/widgets**
are addressed by **handles** (no stable token).
**Rationale.** Matches the WoW model the user referenced; a single canonical way ([D-013](architecture-api.md));
avoids stale-pointer bugs; fits Hafen (gobs have a stable `long id` and can despawn).
**Open.** The exact **token set** (`"mouseover"`? others) and item addressing — [Q-013](../DECISIONS.md).
**See.** [API-REFERENCE.md](../API-REFERENCE.md).

### D-013 — One canonical way per operation (no dual APIs) ✅
**Decision.** The Lua API exposes **exactly one** way to do each thing; we do not ship parallel
styles for the same operation (e.g. flat accessor vs handle-method for gob reads). For gob reads,
the chosen way is the flat reference accessor ([D-012](architecture-api.md)).
**Rationale.** User preference: *"no quiero ofrecer dos formas de hacer las cosas."* Smaller
surface, less to document, fewer ways to get subtly wrong.
**Consequences.** Handles still exist where they are the *only* natural model (items, windows) —
that is the one way for those, not a duplicate. Applies to all future API-surface decisions.

---

## Design-closure decisions (resolve all open questions)

### D-020 — Namespaced `hafen.*` only, no flat globals ✅ (closes Q-011)
API is namespaced (`hafen.gob.health(ref)`), no flat-global aliases (`GobHealth`). Avoids `_G`
pollution, is discoverable, and sandboxes cleanly. Terseness comes from the token, not the name.

### D-022 — Token set + items handle-only ✅ (closes Q-013)
Tokens: `"player"`, `"party1".."partyN"` (ordered by `Member.seq`), `"target"` (combat only).
`"mouseover"` **deferred** (needs hover hit-test tracking — a later phase). Items are addressed
**by handle only** (no `(container,slot)` alternative), honoring [D-013](architecture-api.md).

### D-047 — Addon hotkeys register UNBOUND; the user owns key assignment ✅ (maintainer, 2026-07-31)
**Decision.** `hafen.client:options():keybindings():register(name, fn)` takes **no default key**. An addon
names an *action*; the key is assigned by the user in Options ▸ Keybindings, where the addon's bindings already
appear in their own per-addon section. Registration passes [`KeyMatch.nil`](src/haven/KeyMatch.java) to
[`KeyBinding.get`](src/haven/KeyBinding.java:93) — the same "unbound until assigned" state the client's own
`ol-claim` ships with. A user's assignment persists under `keybind/addon/<addonid>/<name>` and survives
`:reload`, since `KeyBinding.get` returns the process-global binding and restores the stored key.
**Rationale.** Three independent reasons converge. (a) It is the **WoW model** this project emulates: addon
bindings appear in the Key Bindings panel unbound. (b) It is the only rule consistent with the **one-key-one-
action exclusivity** this fork added to [`KeyBinding.set`](src/haven/KeyBinding.java:45) — an addon shipping a
default would be asserting a claim the exclusivity rule is designed to arbitrate between *user* choices.
(c) An addon-chosen default **could not win a collision anyway**: registration goes through `KeyBinding.get`,
which never runs `set`'s unbind pass, so a default can never steal a key; and because
[`AddonRoot`](src/io/brodgar/addon/AddonRoot.java:38) is an early child of `ui.root` it is walked **last**, so
the client's binding matches first and the addon's hotkey would be **silently dead** — the worst failure mode,
invisible and unexplainable to the user. Unbound-by-default converts that silent dead key into an explicit,
visible "not assigned yet".
**Consequences.** `register` is 2-arg, matching the original spec; no addon ships a working hotkey out of the
box, so `bags`/`hello`/`widgetstack` need a one-time assignment after the 018.2 port and should advertise a
*suggested* key in their docs rather than claim one. `set(name, key)` remains available but writes the **user
override**, so an addon must not call it at load to fake a default — that would overwrite the user's own remap
on every login, which is precisely what `defkey` exists to prevent. The 018.2 port also **dropped the per-bind
Lua handle** (`:key()` / `:remove()`): the keybindings handle already answers both by name (`get`/`unregister`),
so keeping it would have been a second canonical way ([D-013](architecture-api.md)).
**See.** [D-013](architecture-api.md) (one canonical way), [018-client-options](../018-client-options/spec.md),
[hooks-hotkeys.md](../learnings/hooks-hotkeys.md) (dispatch order + registry mechanics).

### D-048 — `io.brodgar.addon` holds the addon system, not every class an addon can reach ✅ (maintainer, 2026-07-31)
**Decision.** The package a class lives in is decided by **who its clients are**, not by who can call it from
Lua. `io.brodgar.addon` holds (a) the addon *system* — [`AddonManager`](src/io/brodgar/addon/AddonManager.java),
`AddonRegistry`, `Addon`, `Sandbox`, `Manifest`, `AddonRoot` — and (b) the *Lua bridge*: the `*Api` classes,
every `Lua*` handle, the `*Options` subsystems and `OptionsMethod`. A **client capability** that merely happens
to have an addon consumer gets its own sibling package under `io.brodgar`, with only its Lua handle left behind
in `addon`. 019.1 applies this for the first time: the profiling engine is
[`io.brodgar.prof.Prof`](src/io/brodgar/prof/Prof.java), the Options panel is
[`io.brodgar.ui.ClientPanel`](src/io/brodgar/ui/ClientPanel.java), and only
[`ClientOptions`](src/io/brodgar/addon/ClientOptions.java) — and later `ProfHandle`/`ProfScope` — stays in
`addon`. `io.brodgar.voice` was already this shape; the rule just names it.
**Rationale.** `Prof`'s consumers are `UILoop`, `Widget`, `MapView` and the GL layer: 019 would have made the
hottest classes in `haven` depend on a package called "addon" for something that profiles the *client* and works
with no addon loaded. The seam is clean because it is real — **the engine is a client feature, the handle is the
addon surface** — and it is cheapest to cut while a class is new: `Prof` moved with three call sites, where after
019.7 it would have been five classes and dozens of probes.
**Consequences.** `Prof.init()` had to widen to `public` — crossing a package boundary always costs some
visibility, and that cost is the test of whether a move is real. The **flat** layout of `addon` itself stays:
16 of its top-level classes are package-private, so splitting the bridge into subpackages would force them all
public — worse encapsulation, not better. Existing tier-3 candidates (`Json`, `Gltf` + its mesh primitives,
`GhostGob`/`FollowMoving`) are **not** moved here; they are a separate refactor feature, planned on its own.
**See.** [D-011](architecture-api.md) (invasiveness), [D-008](architecture-api.md) (engine layout),
[019-profiling](../019-profiling/spec.md), [ROADMAP.md](../ROADMAP.md) (the tier-3 refactor).

### D-049 — Profiling is ONE switch: the checkbox, `:profile` and Lua all arm the client's own profiler ✅ (maintainer, 2026-07-31)
**Decision.** [`Prof.arm(boolean)`](src/io/brodgar/prof/Prof.java) is the single writer of the profiling state,
and it writes three things in one place: the hot-path field `Prof.on`, the `"profiling"` preference
(`Utils.setprefb`) and [`UILoop.profile`](src/haven/UILoop.java:40) — the client's own flag that makes it build
`uprof`/`rprof`/`gprof` frames. The Options ▸ Client checkbox, `hafen.client:options():client():profiling(v)`
and the console `:profile on|off` all route through it, so no two of them can ever disagree. The console command
was rewired for this ([D-013](architecture-api.md): one canonical way); the side effect is that `:profile` now
persists the pref, which it did not before.
**Rationale.** 019 reads the client's *existing* frame trees rather than building a parallel profiler, so a
second switch would mean a state where Lua thinks profiling is on while the trees are not being built — the
worst failure mode for a profiler is disagreeing with itself. Writing the field and the pref in one statement
follows what `OptWnd` already does for `MapView.invcamx`; a pref-only write would be a no-op until restart, and
a field-only write would not survive one. `Prof.on` is a plain `static volatile boolean`, deliberately not a
`Config.Variable` — a probe must cost one branch the JIT can hoist, not a deref plus an unbox.
**Consequences.** Arming is **next-frame**: `UILoop.Frame` decides in its constructor whether to build profile
objects, so the frame during which the switch flips has no tree — documented in the API reference, not a bug.
The pref is restored once per JVM from `AddonManager.init` (session init), since the panel is in-game only
([D-004](lifecycle.md)) and everything 019 profiles is the in-game frame loop.
**See.** [D-013](architecture-api.md), [019-profiling](../019-profiling/spec.md),
[boot-and-loop.md](../../codebase/boot-and-loop.md) (the profiling machinery).

### D-050 — A profiling read is a snapshot TABLE, and an absent key means "not measured" ✅ (maintainer, 2026-07-31)
**Decision.** `hafen.client:profiling()` is a handle with verbs (`:reset()`, later `:scope()`/`:measure()`), but
everything its read verbs answer is a plain Lua **table** — a frozen measurement, no identity, no write path
(the snapshot-vs-handle split `docs/addons/api/conventions.md` already ships, the same way `gob:pos()` answers a
value). Inside those tables, **a quantity that was not measured is an absent key, never a `0`**: `frame()` has
no `scene` until the named render passes of 019.6 give the 3D scene a boundary, and `gpuMs`/`gpuFrameno` are
absent until the first GL timestamp comes back — in `history`, an unresolved frame simply has no `gpuMs`.
Alongside it: the phase breakdown uses the client's **own** part names (`dwait/stick/utick/draw/aux/wait`, plus
the render thread's `tick/draw/swap/finish` as a second table), and `addons` is the D-018 `tickLuaNanos`
accounting **read**, not re-measured, through a `LongSupplier` `AddonManager` registers — so `io.brodgar.prof`
still does not depend on the addon system.
**Rationale.** The one loop this feature exists to keep cheap is the consumer's: drawing a 600-sample frame
graph is 600 table lookups as tables and 600 bridge invocations as objects, for no capability gained. A `0` for
an unmeasured quantity is actively worse than nothing — it renders as "free" in exactly the graph the profiler
is for, and it is indistinguishable from a real idle frame. Re-timing what the client already names would give
two sources of truth that drift apart; the price is that the `render` group **lags ~1 frame** (that profile
closes a frame on the next frame's fence), which is documented rather than corrected.
**Consequences.** Consumers test `if f.gpuMs then` rather than `> 0`. Off, the read verbs answer an **empty
table**, not `nil`, so addon code needs no branch at all. The GPU number `frame()` reports is the newest one
that has *arrived*, with `gpuFrameno` saying which frame it belongs to — it trails `frameno` by a handful of
frames by construction.
**See.** [D-049](architecture-api.md), [D-018](security-sandbox.md), [019-profiling](../019-profiling/spec.md),
[boot-and-loop.md](../../codebase/boot-and-loop.md).

### D-051 — The profiling COUNTERS are pull-only and answer while disarmed ✅ (maintainer, 2026-07-31)
**Decision.** `hafen.client:profiling()` holds two kinds of verb, and they obey different rules.
`:frame()`/`:history()` are *frame sampling* — armed only, fed by the end-of-frame handoff ([D-049](architecture-api.md)).
`:memory()`, `:net()`, `:loader()` and `:render()` (019.3) are **pull-only counters**: they answer whether
profiling is on or not, they arm nothing, and they add **no counting whatsoever**. Every number in them is one
the client already maintains and then throws away into a `:stats on` format string; the work is exposing it as
a **structured getter beside the existing `stats()` method**, which is left byte-for-byte untouched. Where a
counter group must be internally consistent (`Loader`, `Defer`), the getter returns all of it under **one lock,
in one call** (`int[] statcounts()`), never one getter per field.
**Rationale.** These are the numbers an addon most wants at 1 Hz from a HUD, and gating them behind the master
switch would force a user to pay the frame-sampling cost to read a counter that is free. Formatting-only means
there is exactly **one source of truth** with the HUD — the acceptance test is literally "compare item by item
with `:stats on`" — and no way for the fork to drift from upstream's accounting. Per-field getters would let a
consumer see a queue that emptied between two reads as "queued 0, busy 0" with the work still in flight.
**Consequences.** The fork's `haven` edits for this tier are additive one-liners tagged `// addon:`, no
behaviour change and nothing to unwind. Reads are inherently a little stale (render counters ~1 frame, net
counters ~1 packet) — documented, not synchronised. Absent-key-means-not-measured ([D-050](architecture-api.md))
carries over wholesale: no session ⇒ `:net()` is empty, no `MapView` ⇒ no scene keys, non-GL environment ⇒ no
`vram`/`programs`. `allocPerFrame` stays the client's **own** EWMA (one source of truth with the `Mem:` line)
and is therefore absent until `:stats on` has computed it — putting it on the frame loop would mean a
`freeMemory()` every frame, armed or not, which is the always-on cost 019 exists to avoid.
**See.** [D-049](architecture-api.md), [D-050](architecture-api.md), [019-profiling](../019-profiling/spec.md),
[world-3d.md](../../codebase/world-3d.md), [network.md](../../codebase/network.md).

### D-052 — Per-addon cost is the WATCHDOG's measurement, split — never a second timer ✅ (maintainer, 2026-07-31)
**Decision.** `p:addons()` (019.4) reports what each addon's Lua cost by reading the accounting
`AddonManager.callLua` has always kept for the D-018 soft budget, not by measuring anything a second time.
`owner.tickLuaNanos += delta` stays **byte-for-byte what it was**; the split is an *addition* inside the same
`finally` — `if(Prof.on) { owner.catNanos[cat] += delta; owner.catCalls[cat]++; }` — behind a mandatory
**category** argument (`events`/`timers`/`draw`/`hooks`/`widgets`) that every call site must choose. The frame
*closes* at the top of the next tick, the instant `tickLuaNanos` already holds one whole frame and is about to
be zeroed: `profRoll()` moves it into the row's "last completed frame" figures there and nowhere else. Custom
**scopes** (`p:scope(name)` / `p:measure(name, fn)`) live in a per-addon map on the `Addon`; their handles are
real (not a shared no-op) even while disarmed, and the `Scope` record is created lazily on the first armed
`begin()`.
**Rationale.** A second timer around the same Lua call would double the probe cost on the hottest bridge path
*and* give two numbers for one thing — the outcome a profiler exists to prevent. Keeping `tickLuaNanos`
untouched is not tidiness: it is what guarantees the watchdog auto-disables at exactly the same point as before
019 (verified — `hogtest` trips on the identical message and tick count). Rolling at the next tick rather than
at end-of-frame is what makes `p:addons().total` *identically* `p:frame().addons` with no reconciliation code.
Live scope handles follow the rest of `hafen.*`, where a stashed handle never goes stale — a no-op singleton
would silently stay dead for a handle taken before the checkbox was ticked.
**Consequences.** A category is mandatory, so a new `callLua` site cannot be added without classifying it (a
default would quietly land in the wrong bucket forever). Nested Lua (a callback that re-enters the bridge) is
charged to **both** brackets, exactly as the watchdog has always charged it, so `cost` can sum slightly above
`ms` on a re-entrant frame — `ms` is the number to trust, and this is documented in the surface. The `:lua`
REPL gets a `(console)` row and joins the frame roll-up (its Lua time is real frame cost); the watchdog still
exempts it. Scope `ms`/`calls` are this-frame figures, so a reader a few frames later sees 0 and must read
`msPeak`/`msAvg` — the demo in `hello` prints both for that reason.
**See.** [D-018](security-sandbox.md), [D-049](architecture-api.md), [D-050](architecture-api.md),
[019-profiling](../019-profiling/spec.md), [luaj-bridge.md](../learnings/luaj-bridge.md).

### D-053 — Per-widget cost is a FIELD on `Widget`, self-clearing by frame stamp ✅ (maintainer, 2026-07-31)
**Decision.** `p:widgets()` (019.5) is backed by one `// addon:` field on `Widget` — `public long[] prof`,
lazily allocated and only while armed — holding **inclusive** nanos for tick and draw plus the **sum of the
children's inclusive** time. Self time is `inclusive - children`, derived at snapshot time, never measured.
The array carries `Prof.gen` (a frame counter advanced once per armed frame in `Prof.frame`) in slot 0 and
**zeroes itself on the first write of a new frame**: there is no sweep and no registry of live widgets. The
probes sit at the two traversal seams and nowhere else — `Widget.draw(GOut,boolean)`'s child loop, and
`Widget.TickEvent.dispatch`, which carries the running child sum as a field saved/restored around each
recursion — plus one bracket in `UI.draw` for the root, the only widget no parent can time. Per-type
roll-up, the `top` list, class names and owner attribution (via `LuaWidget`) all happen in the Lua read verb.
The **budget gate the task carried was not triggered**: the field stayed, so `:widgets()` ships full per-widget
detail rather than the type-level fallback.
**Rationale.** `Widget` is the client's most-instantiated class and the probe runs for every widget, every
tick and every draw. An `IdentityHashMap<Widget,long[]>` — the obvious alternative — is a hash lookup per
widget per frame, which is precisely what makes the naive version unaffordable at the ~530 widgets a logged-in
client holds; a field is one reference, `null` and untouched while disarmed. Measuring inclusive and deriving
self is the standard single-thread trick and costs one extra `long` per stack frame instead of a second
measurement pass. Frame-stamping rather than sweeping is what makes "a closed window's rows disappear" free:
finding the widgets to clear would cost more than the whole probe.
**Consequences.** Off-state cost is one `Prof.on` read per parent per frame in `Widget.draw` and one per
`TickEvent.dispatch` — 019.7 measures it for real. The sums **reconcile exactly**: every non-root widget's
inclusive time appears once positively in its own row and once negatively in its parent's child sum, so the
self times telescope to the root's inclusive (verified in-game: 4.5821 ms of rows against 4.5821 ms of root).
`tickMs` is the tick traversal **only** — `gtick`, the hover query and resize live in the `utick` phase but are
not per widget — so the tree total sits a little under that phase; documented rather than papered over. A
widget not ticked or drawn in the current or previous frame is **absent**, not zero (the [D-050](#) rule again).
`top` is capped at 20 rows, so an addon widget too cheap to rank is visible only in its `byType` row.
**See.** [D-011](architecture-api.md), [D-049](architecture-api.md), [D-050](architecture-api.md),
[D-052](architecture-api.md), [019-profiling](../019-profiling/spec.md),
[widgets.md](../../codebase/widgets.md), [profiling.md](../learnings/profiling.md).

### D-054 — Named render passes nest under the client's own `draw` part, and report SELF time ✅ (maintainer, 2026-07-31)
**Decision.** `p:passes()` (019.6) ships a **fixed, curated** list of three named sections —
`shadow` (`MapView.drawsmap` → the whole `smap.update` call), `scene` (`MapView.maindraw` →
`PView.maindraw`, the 3D draw list) and `ui2d` (`ui.draw(g)` in `UILoop.display`) — each with CPU and
GPU time side by side. Two shape decisions inside that: (a) their `GPUProfile` parts hang **under** the
frame's own `draw` part (captured in `UILoop.Frame.display` and handed to `io.brodgar.prof.Passes`),
not beside it; (b) each pass reports **self** time — its span minus the passes nested inside it —
the same inclusive/self split [D-053](architecture-api.md) uses for widgets. Every seam brackets its
pass in `try`/`finally`. Alongside them, `p:gl()` adds the only **armed-only** counters in the feature
(draw calls, program binds, vertices, triangles), counted at the two per-frame *dispatch* seams —
`GLDrawList.draw`'s slot walk and `GLRender.draw`/`Applier` — with per-slot vertex and triangle counts
computed once at slot-compile time.
**Rationale.** `GPUProfile.Part.part()` closes the previous sibling when it opens the next, so hanging
a pass beside `tick`/`draw`/`swap` would have **truncated the client's own `draw` part** and changed
what `Profwnd` and `:profile on` show — the one thing 019 must not do. Nesting adds three rows and
disturbs nothing. Self time is forced by the same nesting in the *code*: `shadow` and `scene` run
inside the widget draw because the `MapView` **is** a widget, so inclusive rows would double-count the
scene and sum above the frame. Subtracting makes `ui2d` mean what its name says and makes the three
disjoint. The counters are armed-only because — unlike everything in `p:render()` ([D-051](architecture-api.md))
— nothing counts them today; counting inside `BufferBGL`'s replay would be exact but paid by every
client, armed or not.
**Consequences.** Verified in-game: shadows on → `shadow` 0.273 / `scene` 2.077 / `ui2d` 0.613 ms of a
3.172 ms GPU frame (93 %); shadows off → `shadow` **0.000**, frame 2.110 ms. The frame falls by 1.06 ms,
**more** than `shadow` reported, and correctly so: 0.27 ms is the shadow-map render (the depth-only
second geometry pass — 1387 → 887 draw calls), the other ~0.76 ms is `scene`, whose shaders stop
sampling the shadow map. Naming the passes is what makes that decomposable. The CPU and GPU columns
measure different things — CPU is command *recording*, so `shadow`/`scene` are ~0.04 ms while `ui2d` is
milliseconds of real widget work. `passes()` reports the newest frame whose timestamps **resolved**
(the [D-050](architecture-api.md) late-arrival rule again), so both columns describe one frame. The list
stays fixed forever: every boundary is a real GL timestamp query, and per-draw-call attribution is out
of scope permanently. A pass left open would leave a query that never completes and
`GPUProfile.check()` drains **in order**, so it would stall every later frame's timing — hence the
`try`/`finally` at every seam.
**See.** [D-011](architecture-api.md), [D-050](architecture-api.md), [D-051](architecture-api.md),
[D-053](architecture-api.md), [019-profiling](../019-profiling/spec.md),
[world-3d.md](../../codebase/world-3d.md), [profiling.md](../learnings/profiling.md).

### D-055 — Profiling's own cost is measured by control frames, modelled per tier, and the model wins when the measurement cannot resolve ✅ (maintainer, 2026-07-31)
**Decision.** `p:overhead()` (019.7) accounts for what profiling costs in three non-overlapping layers.
(a) The end-of-frame fold is **timed directly** — one `nanoTime` pair in `Prof.frame` — because it is the
only place this feature does real work; it runs in `framedone`, *after* `CPUProfile.end` closed the frame,
so no control frame could ever see it. (b) Every probe bumps a counter it was touching anyway, and the
per-hit cost is **calibrated once** at arm time in two shapes (`unitBracket` = a `nanoTime` pair, `unitAccum`
= an accumulate inside an existing bracket). (c) The last frame of every 64 is a **control frame**: probes
disarmed, `UILoop.profile` left set, so what it removes is exactly *our* instrumentation. The switch is
therefore split in two — `Prof.sampling` (the master, what the checkbox and `armed()` show) and `Prof.on`
(per-frame, what every probe reads). Cost is attributed to **five** tiers — `frame`, `addons`, `widgets`,
`passes`, `gl` — one per independently armable probe set.
**Rationale.** The ≤5% budget of the spec is only enforceable if a failing tier can be *identified*, so
per-tier attribution is not a nicety. Timing each probe would roughly double the cost being reported, hence
counting plus calibration. And the model alone is not enough: it cannot see cache effects, JIT deopt or GPU
query stalls, which is what the control frames are for. Control frames compare **work** time (frame ms minus
`wait` and `dwait`), because under vsync or a frame cap the total is pinned to the cap and the client absorbs
extra cost by idling less — the total would show a delta of zero no matter what the probes cost.
**Consequences.** The estimator is **paired and median-based**: each period yields one delta (median armed
work of that period minus its control frame) and the reported figure is the median of those. The first
implementation used two running means and reported the overhead as **−1.05 ms** on a live session — frame
work time is spiky and the signal is a fraction of a percent of it. It must also clear its own error bar
(`spread/√n`) before superseding the model; a median at +0.004 ms with a ±0.13 ms bar has measured nothing,
and letting it drive the total would swap a rough number for a random one. So **`measuredMs ≤ 0` is the
normal, expected outcome** and does not mean profiling made the client faster — the model then has the say,
and both figures are reported so the fallback is never silent. Verified on synthetic spiky data: a planted
0 ms reads unresolved, 0.5 ms → +0.536, 2.0 ms → +2.18, tiers summing exactly to the total. Two smaller
consequences of the per-frame switch: a control frame writes **no ring slot** and does not bump `Prof.gen`
(a frame graph with a hole every 64 samples is worse than one 1/64 sparser, and per-widget totals would
otherwise age out), and `Addon.profRoll` takes a `probed` flag so the category split holds its last measured
frame rather than rolling in a row of zeroes. Measured in-game: **0.038 ms of a 7.05 ms frame = 0.54%**,
with `widgets` 61% of it (657 probes/frame), against a 5% budget and a 2% target.
**See.** [D-049](architecture-api.md), [D-051](architecture-api.md), [D-052](architecture-api.md),
[D-053](architecture-api.md), [D-054](architecture-api.md), [019-profiling](../019-profiling/spec.md),
[profiling.md](../learnings/profiling.md), [boot-and-loop.md](../../codebase/boot-and-loop.md).

### D-056 — Arity is the verb on the namespace: `hafen.x()` is the collection, `hafen.x(key)` is the entity ✅ (maintainer, 2026-07-31)
**Decision.** A namespace that is at once a **collection** and a set of **addressable entities** becomes a
single **callable table** whose argument count picks the meaning: `hafen.kin()` returns the roster (a plain
array of Kin objects in the window's own sort order, carrying `:find`/`:list`/`:add` on a shared metatable
so `#` and `ipairs` stay exact), and `hafen.kin(idOrName)` returns one interned Kin — a **number** matching
by id and a **string** by exact case-insensitive name, tested `isnumber()` first. The entity's own verbs,
gated ones included, hang off the object and return **self** so they chain
(`hafen.kin("Bob"):setGroup(3):rename("Bobby")`). The flat `hafen.kin.*` table is deleted outright
([D-013](architecture-api.md)); indexing the namespace reads as plain `nil`, which is what makes the hard
cut visible from Lua. The mechanism is [D-044](../017-gob-oop/plan.md)/[D-045](../017-gob-oop/plan.md)
verbatim: userdata + a per-addon metatable, and a per-`Addon` weak-valued intern cache with a drained
`ReferenceQueue`.
**Rationale.** 017 established `hafen.gob(id)` as a callable namespace but had no collection to express;
kin has both, and inventing a second entry point (`hafen.kin.roster()`, or a handle you must `:list()`)
would have re-introduced the dual style [D-013](architecture-api.md) forbids. Arity costs nothing to
learn, keeps one name per subsystem, and makes `hafen.kin()[1]` the idiom the ROADMAP already commits to
for `hafen.party()[1]:gob()`. `isnumber()` must be tested first because **LuaJ's `isstring()` is true for
numbers** — the reverse order silently resolves `hafen.kin(42)` as the *name* `"42"`.
**Consequences.** This is now the shape every remaining OOP migration follows (party, fight, markers…).
Two asymmetries are deliberate and documented: `hafen.kin(<unknown id>)` still mints an object (its
`:exists()` is `false`, so a stashed handle survives a kin leaving and rejoining the roster), while
`hafen.kin("<unknown name>")` and `roster:find` answer `nil` — a name that matches nobody has no id to
wrap. And the **array is a snapshot at call time while the objects in it are live**: a kin added after the
call is not in that array, but every Kin inside it keeps tracking renames, regroups and online flips
([D-012](architecture-api.md)). A function filter passed to `roster:list` receives a **Kin object**, not a
snapshot table — the `hafen.world` shape, now no longer unique to it.
**See.** [D-012](architecture-api.md), [D-013](architecture-api.md), [D-017](security-sandbox.md),
[D-027](actions-permissions.md), [020-kin-oop](../020-kin-oop/spec.md),
[017-gob-oop](../017-gob-oop/spec.md), [luaj-bridge.md](../learnings/luaj-bridge.md).

### D-057 — A fixed-index collection's iteration view is 1-based, and the entity carries its own key ✅ (maintainer, 2026-08-01)
**Decision.** When [D-056](architecture-api.md)'s callable namespace wraps a **fixed-index** collection — the
action bar, whose slots ARE their 0..143 game index — `hafen.actionbar(n)` stays the **one** way to address a
slot (bounds-checked: out of range throws, unlike `hafen.kin(<unknown id>)`), and `hafen.actionbar()` is only
the **iteration view**: a plain **1-based** array of all 144 slots, no metatable, no `:find`/`:list`. Its
position is a position, not an index — it hands back the very same interned objects, so
`hafen.actionbar()[1] == hafen.actionbar(0)`. The entity therefore carries its own key: `slot:index()`
answers the 0-based game index from the handle alone, so nothing ever reconstructs it from `i - 1`.
**Rationale.** The obvious alternative — key the array 0..143 so `[n]` *is* the game index — cannot work in
LuaJ: key `0` lands in the hash part, so `#` reads 143, `ipairs` silently skips slot 0, and `__len` is not
consulted for tables (see [luaj-bridge.md](../learnings/luaj-bridge.md)). Every addon would then have to know
to write `for n = 0, 143` and the length would lie. The maintainer's objection settled which side gives:
two ways to *address* a slot would be the dual style [D-013](architecture-api.md) forbids, but a collection
you can walk is not a second address — `hafen.kin()` already established the array-as-view. So the array
became 1-based (`#` = 144, `ipairs` exact) and the index moved onto the object, where it cannot drift.
**Consequences.** The rule generalises to every remaining fixed-index migration (party slots, equipment
slots): **address 0-based through the namespace, iterate 1-based, ask the object for its key.** A method
beyond the spec's list (`:index()`) is the price, and it is what keeps the two numbering schemes from ever
being confused in addon code. `hafen.actionbar()` builds 144 interned objects per call — cheap (a bounded
map, weak values) and worth it for a dense, never-sparse array.
**See.** [D-013](architecture-api.md), [D-044/D-045](../017-gob-oop/plan.md), [D-056](architecture-api.md),
[021-actionbar-oop](../021-actionbar-oop/spec.md), [luaj-bridge.md](../learnings/luaj-bridge.md).

### D-058 — Verify a subsystem has *content* before designing an API over it: there is no `hafen.music` ✅ (maintainer, 2026-08-01)
**Decision.** The audio feature is `hafen.sound` **alone**. The planned Track section — `hafen.music(name)`
with `:play([volume],[loop])/:stop/:playing/:volume(v)`, `hafen.music()` for the singleton player, and the
`// addon:` master-volume/state seam in `haven/Music.java` — was built to spec in 024.3 and then **removed
whole**: `LuaMusic.java` deleted, `Addon.tracks` dropped, `haven/Music.java` reverted to pristine, leaving
**zero core edits** in the feature. `hafen.music` is deliberately absent (not flattened, not stubbed), and
`haven.Music` is left entirely alone. This number takes the slot the *dropped* singleton-player decision was
going to occupy; nothing in the API is a singleton yet.
**Rationale.** Not design — **content**. `haven.Music` is a MIDI player (`javax.sound.midi`,
[`Music.play`](../../../src/haven/Music.java:138)) driven by exactly one caller: `RootWidget`'s `"bgm"`
server message ([RootWidget.java:134](../../../src/haven/RootWidget.java:134)). This server never sends it —
**132,777 cached resource files hold zero `midi` layers**, against 36 `audio` ones, all sfx. What the
maintainer hears as music is an [`ActAudio.Ambience`](../../../src/haven/ActAudio.java:248) loop on the
**`amb`** channel, published by world resources and governed by Options ▸ Audio ▸ "Ambient volume" — the
same path as the crickets. The code was correct and the API shape was right; it sat over a dead path, and
**an API over a subsystem with no content answers `nil` forever**. Only an in-game test could show it: the
class exists, compiles, and its javadoc reads like a working feature.
**Consequences.** The rule for every remaining migration: before specifying a surface, prove the subsystem
is *reached* on this server — grep the resource cache for the layer type, and find the caller that would
feed it — not merely that the client class exists. Ambient audio is a `RenderTree.Node` with a lifetime, not
a clip handle; if it is ever wanted it is **its own feature** (`hafen.ambience`), designed against
`ActAudio.Ambience.Glob`, not retrofitted into `hafen.sound`. `docs/addons/api/audio.md` states the absence
and the reason so nobody re-proposes it.
**See.** [D-011](architecture-api.md), [D-013](architecture-api.md), [024-audio-oop](../024-audio-oop/spec.md),
[process-method.md](../learnings/process-method.md), [services.md](../../codebase/services.md).

### D-059 — Playback parameters are call arguments, not entity state ✅ (maintainer, 2026-08-01)
**Decision.** `sound:play([volume])` takes the volume as the **first argument of the play call**; there is no
`sound:volume(v)` and a Sound stores no level. Omitted ⇒ `1.0`; outside `0..1` raises a `LuaError` naming the
method (`AudioOptions`' own convention). The rule generalises: **when an entity is interned, anything that
describes one *use* of it is an argument, and only what describes the entity itself is state.**
**Rationale.** Interning (D-045) is what makes `hafen.sound("sfx/msg") == hafen.sound("sfx/msg")` and lets a
handle be a table key — but it also means two unrelated parts of an addon (or the same addon at two moments)
hold the *same object*. A stored volume would leak across them: a HUD module dropping its notification blip
to `0.2` would silently quieten the alarm another module plays from the same clip, with nothing in either
call site to explain it. The chainable `:volume(0.2):play()` reads nicer in one line and is a bug in two.
**Consequences.** `:play(0.2)` is one quiet blip and changes nothing for the next caller; `LuaSound.volume`
is one shared validator taking the method name for the message. The same test applies to every future verb —
per-call options (a repeat count, a fade, a pan) are arguments; identity is state. The counter-case is the
*user's* configured level, which is not per-call and correctly lives elsewhere:
`hafen.client:options():audio()`.
**See.** [D-013](architecture-api.md), [D-045](../017-gob-oop/plan.md), [D-056](architecture-api.md),
[D-060](architecture-api.md), [024-audio-oop](../024-audio-oop/spec.md).

### D-060 — `:exists()` belongs to entities with a lifetime, not to name-keyed handles ✅ (maintainer, 2026-08-01)
**Decision.** A Sound has **no `:exists()`**, breaking the pattern every other entity section follows
(`gob:exists()`, `kin:exists()`, `pag:exists()`). The rule: `:exists()` is offered **iff** the entity's
identity is a handle onto something with a lifetime that can end while the addon holds it. A resource name
has no lifetime, so there is nothing to answer.
**Rationale.** `:exists()` everywhere else answers *staleness* — the gob left the sight radius, the kin was
forgotten, the pagina left the menu — which is the one question a stashed handle cannot answer for itself.
On a Sound it would silently change meaning to "does this resource name resolve", i.e. a typo check, and it
**cannot answer it honestly**: resources resolve off the UI thread, so a fresh name would report `false`
until the loader lands and `true` after — a race dressed as a predicate. (My first draft had exactly that.)
Symmetry with the other sections is not worth a method whose answer is wrong for the first few hundred ms.
**Consequences.** A bogus name is simply **silent** — `:play()` raises nothing, the client logs one line, and
Lua never sees the miss; validating a user-configured resource name would need an honest async answer (a play
callback reporting the resolve), which is out of scope. Read the rule forward: before copying a method across
sections, check that the *question it answers* still exists there. `hafen.sound()` (the live set) covers what
addons actually ask — what am I playing right now.
**See.** [D-045](../017-gob-oop/plan.md), [D-056](architecture-api.md), [D-059](architecture-api.md),
[024-audio-oop](../024-audio-oop/spec.md), [threading.md](../learnings/threading.md).

### D-061 — the API's vocabulary comes from the engine, not from the genre ✅ (maintainer, 2026-08-01)
**Decision.** Two renames in one rule. The buff meter is **`:duration()`**, not `:cooldown()` — the action
bar's identical `GItem.MeterInfo` 0..1 read stays `:cooldown()` there. And the word **"debuff" is gone**
from the whole area (docs, `addons/`, `src/io/brodgar/`, the specs): `hafen.buff()` returns buffs, full stop.
**Rationale.** Both were WoW glosses we brought with us. The engine knows `Buff` and `Bufflist` and publishes
**no positive/negative flag** — the one `CharWnd` constant named `debuff` is an unrelated red `Color` for
reduced numbers — so a `hafen.buff` surface that says "debuff" promises a distinction the client cannot make.
The same meter deserves the same treatment in reverse: what it *measures* differs by section — on a slot it is
the wait until you may act again, on a buff it is the run that is left — so one shared Java read is exposed
under the name true to each side, and neither gets an alias to the other (D-013).
**Consequences.** `buff:info()`'s key is `duration`; `CharApi.buffEqual` had to follow it, or `BuffChanged`
would have silently stopped seeing meter changes. Read forward: before naming a method, ask what the engine
calls the thing and what the value means *here* — a name borrowed from another game (or another section) is a
promise the API may not be able to keep.
**See.** [D-013](architecture-api.md), [D-056](architecture-api.md), [D-057](architecture-api.md),
[025-buffs-oop](../025-buffs-oop/spec.md), [widget-tree-reads.md](../learnings/widget-tree-reads.md).

### D-062 — performance goes behind the existing surface as a cache, not in front of it as a handle ✅ (maintainer, 2026-08-01)
**Decision.** `g:text`/`g:atext` were re-rasterising a texture per line per frame (~0.28 ms, 019.8). The fix is
an **invisible, per-addon, bounded cache** of the rendered `Text` inside `LuaGOut` — zero API change, zero addon
edit, zero core edit — and **not** the explicit text handle (`hafen.render.text` + a blit) the ROADMAP had
weighed against it. The handle is discarded, not deferred.
**Rationale.** The ROADMAP framed the two as rivals on the volatile case, where a content-keyed cache misses
every frame. They **tie** there: a handle must re-rasterise on `t:set` for exactly the same reason. Everywhere
else the cache wins outright — it fixes every addon already written, including ones nobody will revisit, where
a handle only helps code rewritten to use it. And the asymmetry that decides it is reversibility: a cache is an
implementation detail that can be retuned, bounded differently or removed, while a handle is **permanent public
contract** with a lifetime, a teardown story and a second way to draw text (D-013: one canonical way).
Measured, the invisible version recovered the whole enabled-vs-disabled cost (130 → 220–240 FPS, 88.8% hits).
**Consequences.** The addon layer now **owns GPU textures across frames**, which the immediate-mode design had
never done: eviction, teardown and the `:reload` sweep must each dispose, and the fast path had to stop calling
`GOut.atext` (whose last line is a `dispose`) so there is exactly one lifecycle and one disposer. Being
invisible, the cache must be **observable instead of documented-away**: `p:textcache()` (D-051, pull-only)
exists so an addon author can see hits, misses, bytes and the caps, and the docs state plainly that a string
which changes every frame is re-rasterised every frame — a hidden optimisation you cannot measure is a hidden
performance cliff. Read forward: when a performance problem can be solved *behind* an existing surface, that
beats a new surface even when the new surface is theoretically faster on some workload — the cache is
removable, the contract is not.
**See.** [D-013](architecture-api.md), [D-039](widgets-ui.md), [D-050](architecture-api.md),
[D-051](architecture-api.md), [026-text-cache](../026-text-cache/spec.md),
[ui-widgets.md](../learnings/ui-widgets.md).

### D-063 — an entity's key is what the engine publishes about it, not what the genre calls it ✅ (maintainer, 2026-08-01)
**Decision.** `hafen.meter(needle)` searches the meter's **`IMeter.bg` resource name**, a string the *server*
publishes, and the docs quote the names as **observed on this server** while naming `:res()` as the way to
re-derive them on any other. There is no client-side alias map (`"hp"`/`"stamina"`/`"energy"` → res), and no
fixed vitals triple: `hafen.meter()` is every bar in the `place == "meter"` slot, however many that is.
**Rationale.** The old `hafen.player():vitals()` hard-coded the first three meters as hp/stamina/energy **by
tree position** — a client-side guess about content the client does not own. It was silently lossy: mounting
adds two more bars (`häst`, `mount`), which the triple could never see, and any future bar would be dropped
the same way. An alias map would have kept the same assumption behind a friendlier spelling and broken on the
same day. Searching the published name is the only key that survives the server changing its mind — and one of
the real names being non-ASCII is the proof that these strings are content, not API.
**Consequences.** `"hp"` is **not a key this code knows**: it is a substring that happens to identify a bar
here, so the docs must (and do) teach the discovery step — `:res()` on a live client — instead of publishing a
dictionary that would read as contract. The lookup is therefore a substring match with no trimming and no
normalisation, and the docs advise an ASCII needle. The sibling of D-061 one level up: D-061 is about the
API's *vocabulary*, this is about an entity's *identity*. Read forward: when the engine already publishes an
identifier, expose the search over it — shipping your own names for someone else's data is a promise you
cannot keep.
**See.** [D-013](architecture-api.md), [D-056](architecture-api.md), [D-061](architecture-api.md),
[027-meters-oop](../027-meters-oop/spec.md), [widget-tree-reads.md](../learnings/widget-tree-reads.md).

### D-064 — an intern cache's key strength follows the population it keys, not the pattern it copies ✅ (2026-08-02)
**Decision.** The Widget intern cache (029.1) is a **`WeakHashMap<Widget, WeakReference<LuaValue>>`** — weak on
**both** axes — and not the `IdentityHashMap<K, WeakRef<handle>>` + drained `ReferenceQueue` that `LuaGob`,
`LuaKin`, `LuaSlot`, `LuaPagina`, `LuaSound`, `LuaBuff` and `LuaMeter` all share. The strong-key shape stays
correct for those; it is simply not portable to an entity whose population is a whole tree.
**Rationale.** The strong-key caches are bounded by *how few of the thing there is*: a handful of meters, one
buff bar, 144 slots. An entry outlives its widget only until the next access drains the queue, and keeping a
removed `Buff`/`IMeter` readable is a *feature* (that is what makes a `MeterRemoved` payload worth having).
A widget tree is the opposite population: thousands of nodes, churning as windows open and close. Strong keys
there would pin every destroyed widget — and its whole subtree — until some later access happened to drain,
which is exactly the **D-041 no-pin rule** the transient `WidgetNode` was built to honour. `haven.Widget`
overrides neither `equals` nor `hashCode`, so `WeakHashMap` gives identity keying *and* weak keys with no
custom map: the deviation costs one line, not a mechanism.
**Consequences.** The map value must be a `WeakReference`, never the handle itself — a `WeakHashMap` whose
value strongly reaches its own key never expires an entry, which would have re-created the pin it was chosen
to avoid. There is no `ReferenceQueue` and no drain: `WeakHashMap` expunges on use, and a value cleared while
its widget is still alive is simply replaced on the next lookup. Nothing to tear down, so the cache is
deliberately **not** an owned-resource registry (`Addon.widgetObjs` is not `Addon.widgets`). Read forward:
when copying an interning cache, ask how many of the keyed thing can exist at once and whether an entry
outliving its subject is a feature or a leak — the answer, not the precedent, picks the map.
**See.** [D-041](widgets-ui.md), [D-044/D-045](architecture-api.md), [D-012](architecture-api.md),
[029-widget-oop](../029-widget-oop/plan.md), [ui-widgets.md](../learnings/ui-widgets.md).

### D-065 — per-entity state that a weak intern cache would lose must be DERIVED, not stored ✅ (2026-08-02)
**Decision.** The Widget entity's **provenance** — OWNED (the addon created it with `hafen.ui.window{}`/
`widget{}`) vs BORROWED (a native widget, or another addon's) — is computed from the widget tree on every
ask (`LuaWidget.ownedContent`), never written onto the handle when it is minted. A widget is owned by an
addon exactly when it *is*, or directly contains, that addon's `AddonWidget` whose recorded `root` is it.
**Rationale.** The cache behind the entity is weak on both axes ([D-064](architecture-api.md)): a Lua value
the addon stops holding is collected, and the next `hafen.ui.at(x, y)` over the same widget mints a *fresh*
handle. A flag set at creation time would therefore be true for as long as the addon happened to keep the
value and silently false afterwards — a bug that reproduces only under GC pressure, i.e. never on the
maintainer's machine and always on a busy one. Interning makes handles interchangeable **only** if they
carry no state of their own; the moment one does, `==` stops meaning "same entity". The tree already knew
the answer (an `AddonWidget` records its owner and its root), so the derivation is a field read, not a
search — and it falls out **per-addon** for free, which a global registry would have had to encode.
**Consequences.** Anything the entity answers must be re-derivable from the engine object it wraps: that is
why `:info().owned` is a snapshot field rather than a stored one, and why the write verbs check ownership
per call instead of at mint time. The one thing that genuinely cannot be derived — *did this addon hide
that native widget?* — is deliberately kept **off** the handle, in the addon's own restore list
(`Addon.hiddenNative`), where its lifetime is the addon's and not the garbage collector's. Read forward:
before adding a field to an interned handle, ask what happens when the cache re-mints it; if the answer is
"wrong value", the field belongs to the wrapped object, to the owner, or to a snapshot — not to the handle.
**See.** [D-064](architecture-api.md), [D-044/D-045](architecture-api.md), [D-041](widgets-ui.md),
[029-widget-oop](../029-widget-oop/spec.md), [ui-widgets.md](../learnings/ui-widgets.md).

### D-066 — a thing that lives INSIDE another is a relation on it, not a section of its own ✅ (2026-08-02)
**Decision.** Items are read from their **container**: `widget:items()`, the same shape as `widget:children()`,
answering on any `Inventory`/`Equipory`/window that has `WItem`s under it. The `hafen.items` section is a **hard
cut** (D-013) — `inventory()`/`equipment()` became widget *lookups* (`hafen.ui.inventory():items()`), `hand()`
moved to `hafen.ui.hand()` (the cursor item is not a widget), and `find()` got no replacement at all: a
name/res substring filter over one array is a Lua one-liner over `:items()`.
**Rationale.** (Maintainer, 2026-08-02.) In Hafen there is no inventory model outside the widget tree —
`GameUI.maininv` is an `Inventory` exactly like a chest's, a belt's or the study window's. A section named
`hafen.items` therefore had to pick *one* container to be about, and it picked the player's; every other
container was reachable only through `hafen.ui.adopt(id)`, which **hid the window** to give you a readable
handle. That is the privilege this feature exists to remove: a section over one privileged instance is a
statement about the API's author, not about the game. As a relation the same verb covers every container that
exists now and every one added later — verified in-game the first time it ran, where one `root():walk()`
returned the backpack, an open cupboard, the `Belt` and the study inventory, three of which no adapter had ever
been written for.
**Consequences.** `:items()` shapes each entry the way its container does (an `Equipory` adds `slot` + the slot
name; everything else the grid cell) — the container is the context, so it supplies it. The lifecycle verbs
follow the items onto the same entity (`:onItemAdded/:onItemRemoved/:onDestroy`), which is what let the
`adopt` model handle be deleted outright rather than renamed. The Item *shape* did not change: one
`CharApi.itemSnapshot` still produces it, so `item.handle` still addresses the live `GItem` for the gated
`hafen.act.item` (D-022). Read forward: before giving something its own `hafen.*` section, ask what it lives
inside; if the answer is a single engine object, it is a verb on that object's entity, and a section would only
privilege whichever instance you happened to build the section around.
**See.** [D-012/D-013](architecture-api.md), [D-022](architecture-api.md), [D-009](widgets-ui.md),
[029-widget-oop](../029-widget-oop/spec.md), [ui-widgets.md](../learnings/ui-widgets.md).

### D-072 — forgive what the API will later understand; refuse what it never will ✅ (2026-08-02)
**Decision.** In a two-part surface where one part's vocabulary is still **growing** and the other's is
**complete**, they get opposite failure modes. `hafen.ui.skin{ [selector] = {props} }` (033.1) accepts a
**tree key** — `@Inventory`, `window[title=Cupboard]`, the `window`/`inventory` roles — parses it, and then
does **nothing**: silently inert, never an error, because C1b will resolve exactly that key and a sheet written
for it must load today, unstyled, rather than blow up. An unknown **property** in a rule (`fnt = h`) is an
**error** naming the ones that exist. Bad *grammar* stays an error on both sides — the key goes through the
same `Selector` parser `hafen.ui(sel)` uses, so `"nope"` gets the same message in both places.
**Rationale.** (2026-08-02, 033.1.) The two look like the same "unknown name" case and are not. A key that does
not resolve **has a future meaning already designed** — the C1a/C1b split is a task boundary, not a semantic
one, so erroring on it would make the *implementation schedule* visible in the API and punish an author for
being early. A misspelt property has no future meaning to wait for: `fnt` is never going to mean anything, so
silence would leave a typo indistinguishable from a working rule for as long as it takes to notice the font
never changed. Erring toward silence where a value is *not yet* understood and toward noise where it *cannot*
be is the only combination that never lies about which of the two happened.
**Consequences.** The rule generalises past this feature: a growing key space (selectors, roles, resource
names, event names, scopes) forgives; a closed value space (property names, option keys, enum arguments)
refuses. It also says how to *tell them apart* — ask "is there a designed later meaning for this exact
string?", not "is this in my table?". Cost: a sheet whose keys are all inert applies cleanly and appears to do
nothing, so the honest place to learn what resolved is the docs' site-key table, and a future task adding a
diagnostic (a count of inert rules) is compatible with this decision rather than a reversal of it. Read
forward: whenever a feature is *sliced*, check whether the slice line is visible from Lua — if it is, that is
usually an error message that should have been silence.
**See.** [D-012](architecture-api.md), [D-067](widgets-ui.md), [033-ui-stylesheet](../033-ui-stylesheet/spec.md),
[fonts.md](../learnings/fonts.md).

### D-074 — a loader hands back the file, not an interpretation of it ✅ (2026-08-02)
**Decision.** `hafen.asset` gained a fourth type (033.3): `.json`/`.txt` → `"data"`, whose one verb is
`:text()` — the file's contents as a UTF-8 string. It does **not** parse. Turning that text into a table is
`hafen.json.parse`, in its own namespace, and the two compose in one line:
`hafen.json.parse(hafen.asset("theme.json"):text())`. The same rule read backwards is why the three older
types *do* hand back a decoded object: a `TexI`, an AWT `Font`, parsed glTF geometry are the file in the only
terms the client can use it — there is no second namespace that owns "what a PNG means".
**Rationale.** (2026-08-02, 033.3.) Two forces pointed the same way. **One canonical way**: a parsing
`hafen.asset(".json")` would make `hafen.json.parse` the *second* door to a Lua table from JSON, and the first
one invisible in the call. And **interning**: an asset is interned per resolved path, so a parsed table would
be one **mutable** object handed to every re-load of that path — addon code that edits its own config would be
editing what the next reader sees, a shared-state bug with no syntax to warn you. A string cannot be edited
behind your back, so interning stays honest for free. The test that separates the cases is *"is there already
a namespace that owns this interpretation?"* — for JSON there is; for a PNG there is not.
**Consequences.** The door that was missing is now the one a **theme** comes through: the bundled `theme`
example addon's whole look is a `theme.json`, its Lua naming no surface, font, size or colour — the spec's
"the sheet is data" criterion met by a real file rather than by a table literal in Lua. Read forward: a future
`.csv`/`.ini`/`.toml` extension is the same shape (text out, a parser namespace beside it), and if one ever
*needs* to return structure it must return a fresh value per call, not the interned one. Cost: an addon pays
one extra call and one extra failure mode (a `pcall` around `parse` for a malformed file), which is the right
place for it — the loader succeeded, the content was wrong.
**See.** [D-012/D-013](architecture-api.md), [D-036](network-data.md), [D-060](architecture-api.md),
[028-asset-loader](../028-asset-loader/spec.md), [033-ui-stylesheet](../033-ui-stylesheet/spec.md).

### D-093 — an entity's identity is the key a NAME can address; a compound engine key collapses to it ✅ (2026-08-03)
**Decision.** `hafen.map.icons` (037.1) keys a minimap icon category on its **icon resource name** alone,
although the engine's own key is `GobIcon.Setting.ID` = (resource name, sub-id). A resource whose published
code enumerates icon *variants* therefore has several `Setting`s under one category: a **read answers true
when ANY of them carries the flag** and a **write sets ALL of them**, so the round trip stays exact and the
entity keeps one string identity — `hafen.map.icons(res) == hafen.map.icons(res)`, and `seen[cat]` works.
**Rationale.** (2026-08-03, 037.1.) D-063 says an entity's key is what the engine publishes, and the engine
publishes a *pair*. But half of that pair is an opaque `Object[]` decoded out of a resource's own message —
there is no string that addresses it, so honouring it fully would mean an entity Lua could hold but never
*name*, and `hafen.map.icons(<what?>)` would have no first argument. The retired `hafen.radar` had already
answered the question without stating it: `setVisible(filter, on)` flipped every setting the filter matched,
so ALL-write is the shipped behaviour, not a new compromise. And on the minimap the variants **are** one
thing to a player — "draw boars" is the question being asked. ANY-read plus ALL-write is the pair that makes
the round trip a fixed point: write `v`, and every variant reads `v` back.
**Consequences.** The rule generalises: *when the engine's key is a tuple whose tail no name can express,
collapse to the head and make the verbs total over the collapsed set*. The cost is a real one and is in the
docs — a resource whose variants disagreed loses that disagreement the first time an addon writes to the
category, which is why the entity is a `res` and not a `Setting` object. Interning on the Java `Setting`
would have been wrong for a second, independent reason: the loader thread **swaps the settings map
wholesale** and mints fresh `Setting`s as icons resolve, so a handle held across one swap would write to an
orphan — the same eviction hazard segments and grids have (037.2).
**See.** [D-063](architecture-api.md), [D-056](architecture-api.md), [D-061](architecture-api.md),
[D-066](architecture-api.md), [037-map-database](../037-map-database/spec.md).

### D-094 — an intern key follows the engine's OWN stability: a published id where the object is rebuilt, object identity where it is not ✅ (2026-08-03)
**Decision.** 037.2 interned three new entities and split them two ways. A **Segment** and a **Grid** are
keyed on the id the engine publishes (D-063) — a segment lives in a `BackCache(5)` and a grid in a weak
`CacheMap`, so the same segment comes back as a *different Java object* after an eviction. A **Marker** is
keyed on a **per-session ref this bridge mints** over `IdentityHashMap`, because `MapFile` publishes no
addressable id for one — and that is correct here for the opposite reason: a `MapFile.Marker` is loaded
once and thereafter **mutated in place** (a segment merge rewrites its `seg`/`tc` fields), so its Java
identity is the stable thing and nothing else is.
**Rationale.** (2026-08-03, 037.2.) D-063 reads as "never intern on Java identity", and taken literally it
has no answer for an engine object with no id. The rule underneath it is narrower and more useful: *ask what
the engine does to the object*. If it re-mints it (a cache that evicts and reloads, a map the loader swaps
wholesale — D-093's `Setting`s), identity is a lie and you must key on what it publishes. If it never
re-mints it, identity is the only truth available and a bridge-minted ref over it is exact. Getting this
backwards is invisible until the cache turns over, which is precisely when a stale handle would start
writing to an orphan.
**Consequences.** The marker ref is **session-scoped** and dies with the session (`MapApi.resetMarkers`),
which is honest: markers are re-read from disk at login and a ref must not outlive the objects it names.
The two kinds of key coexist in one namespace with no visible difference — every entity re-resolves through
one funnel on every call, so a stale handle answers `nil` and `:exists() == false` rather than lying, which
is what makes the key choice an implementation detail instead of a contract.
**See.** [D-063](architecture-api.md), [D-064](architecture-api.md), [D-093](architecture-api.md),
[037-map-database](../037-map-database/spec.md).

### D-095 — a read of a STORED world kicks the load and answers nil; it never blocks and never takes a callback ✅ (2026-08-03)
**Decision.** Every `hafen.map` read that needs data off the disk — `seg:grid(sc)`, `grid:tile(c)`,
`marker:anchor()` for another segment — **starts the load and returns `nil`**; the caller reads again next
tick and gets it. No callback argument, no "ready" event, no blocking wait. The lock is taken with
`tryLock` and never waited on (`MiniMap.resolve`'s own rule): `MapFile`'s write lock is held across disk
I/O on its processor thread, so waiting for it would stall the UI thread for a read that is allowed to
answer `nil` anyway.
**Rationale.** (2026-08-03, 037.2.) `hafen.world.fromGridPos` already had this shape and it was never
written down. The alternative — a callback per read, `screenToWorld`'s shape — was rejected on the concrete
case the feature exists for: a minimap panel walks a dozen grids per frame and would become a tree of
callbacks whose completion order is the disk's. Answering `nil` makes the *frame* the retry loop, which is
what a drawing addon already has.
**Consequences.** `nil` is deliberately overloaded — "there is no grid there" and "not yet" are the same
answer — and that is affordable only because re-asking is free and correct in both cases; `grid:info().loaded`
is there when a caller genuinely needs to tell them apart. The rule has an explicit **exception**, and the
exception is the test of it: the marker list takes the blocking read lock, because an empty marker list is
a *lie* a caller cannot distinguish from "no markers", where a `nil` grid is a documented "ask again". So
the rule is not "never block" — it is *answer nil only where nil already means something the caller must
handle*.
**See.** [D-050](architecture-api.md), [D-094](architecture-api.md),
[037-map-database](../037-map-database/plan.md).

### D-096 — a client-local id that a MERGE re-bases is worse than one that goes stale: it is a view, never a stored position ✅ (2026-08-03)
**Decision.** The map database's own position — a segment id plus a segment tile coord — is **read-only**:
an addon may look at it, compare it within a session and draw with it, and must never save or send it. The
position that leaves the client is the `{gridId, x, y}` anchor, which is what `hafen.world.gridPos()`
already returned and what `marker:anchor()` was added to produce. The API says so structurally, not only in
prose: the anchor is the only shape both halves of the coordinate system accept.
**Rationale.** (2026-08-03, 037.2.) The client's own rule for session-local data is "it goes `nil` and you
re-resolve" — `rc`, `fromGridPos`, a widget id. A segment id breaks that rule in the dangerous direction:
`MapFile` mints it with `rnd.nextLong()`, and when two explored areas turn out to touch, `merge` re-bases
the loser's grids **and rewrites every marker inside it in place**. A stored `{seg, tc}` therefore does not
fail — it silently names a different place. A grid id is the server's, identical for every player, and no
merge moves it.
**Consequences.** `marker:anchor()` is the feature's deliverable rather than a convenience, and its two
halves differ (synchronous through `sessloc` in the current segment, D-095's asynchronous database read
elsewhere) because only the anchor, not the marker, can be persisted. Generally: *before exposing an
identifier, ask not whether it is stable but how it fails* — one that goes missing is safe to hand out with
a caveat, one that is silently rewritten needs a converter and a refusal.
**See.** [D-094](architecture-api.md), [D-095](architecture-api.md),
[037-map-database](../037-map-database/spec.md).

### D-097 — a REF-COUNTED client toggle is a HOLD an addon takes and releases, never a switch it flips ✅ (2026-08-04)
**Decision.** Where the client already counts how many things want a thing displayed, the API exposes
**taking and releasing a hold**, not setting a state. `hafen.map.overlay(tag, true)` adds this addon's
`+1` and `overlay(tag, false)` takes it away; the take is **idempotent** (one hold per addon per tag) and
teardown releases each hold exactly once. The **read** is the client's own answer — *is this displayed at
all* — never *do I hold it*; the record is published separately (`hafen.map.overlays()[n].held`).
**Rationale.** (2026-08-04, 037.3.) `MapView.oltags` is a multiset (`enol`/`disol`/`visol`), and the
counters are the user's own menu checkbox, the server's `flashol` (which flashes a claim on for a few
seconds), and us. "Off" is therefore not a state an addon *can* express: an assignment would have to guess
whose count to discard. Idempotence is not politeness but arithmetic — a second `enol` would leave the
count standing after the single `disol` a teardown can make, i.e. an overlay drawn forever with no owner.
The map window's `realm` is a plain `Collection<String>` instead, so its hold additionally records whether
the tag was already there and a release skips the removal if it was; same verb, same undo, no branch in the
API. This is D-069 one subsystem along — *an addon's write over client-owned state is an owned resource* —
with the difference that a hidden window has ONE owner while a ref count has many, so the answer is a hold
rather than a seizure.
**Consequences.** The record (`Addon.overlayHolds`) is the `hiddenNative` shape and rides the same three
paths: `AddonRegistry.teardown`, the REPL owner's release on `:reload`, and a session reset that *forgets*
rather than releases (the `MapView` a hold named is gone after a relog, so `apply` is guarded on the side
still being the same live object). Generally: *before designing a write, ask whether the engine stores a
value or a count* — a value can be assigned and restored, a count can only be joined and left.
**See.** [D-069](widgets-ui.md), [D-072](architecture-api.md),
[037-map-database](../037-map-database/spec.md).

### D-098 — a DERIVED resource is keyed by what it PRODUCED, and bounded, because nothing else ends it ✅ (2026-08-04)
**Decision.** Where the API hands back an owned resource the client **computed** out of engine state
rather than one an addon loaded from a file, three things follow together: the intern key is the
**product**, not the receiver that asked for it; the cache is **bounded** and disposes what falls off;
and the handle is **not an asset**, however identical its Lua surface. `grid:image(lvl)` is keyed by
(segment, level, level-coord) — so the four grids under one level-1 zoom grid share ONE picture — never
by the grid that asked; `Addon.mapImages` is an access-ordered LRU whose eviction runs
`AssetApi.disposeImage`; and a drawing never appears in `hafen.asset()` and its `:path()` is a
description.
**Rationale.** (2026-08-04, 037.4.) An asset has a natural end: the addon shipped N files and disposes
N textures. A derived resource has none — a panel scrolling a continent would mint a `TexI` per grid
forever, which is a GL leak by a door the R1 teardown does not watch, so the *bound* is part of the
design and not a tuning constant added later. And keying on the receiver would have been the obvious
mistake: `grid:image(1)` for two neighbours is literally one `ZoomGrid`, so a per-grid key renders the
same bitmap twice and holds two textures for one picture. The third clause is the honest half: the
handle carries the same `LuaImage`, so `g:image`, `hafen.render.sprite` and the stylesheet's
`bg = {image = …}` all take it for free (D-043's "no new code" again) — but calling it an asset would
put a thing with no file in `hafen.asset()` and give it a `:path()` no loader accepts.
**Consequences.** Asking is what keeps a picture alive, so the documented shape is *re-ask every frame*
(which the load model already required, D-095) rather than *stash the handle*. `MapImages.teardown` runs
**before** `AssetApi.teardownAssets` in `AddonRegistry.teardown` so the cache is empty before the images
it names are freed, and the REPL owner gets the same call on `:reload` and on relog. Generally: *before
handing back a computed resource, ask what would ever free it* — if the answer is "the caller
remembers", the API owes it a bound.
**See.** [D-095](architecture-api.md), [D-063](architecture-api.md), [D-043](fonts.md),
[037-map-database](../037-map-database/spec.md).

### D-099 — a CATEGORICAL cost claim is a property of the code's shape, never a branch inside the callback ✅ (2026-08-04)
**Decision.** When an addon (or a feature) claims it costs *zero* of some measured category, the zero must
be **structural**: the callback is not installed at all. `atlas` builds its panel with `onDraw = pins and
drawPins or nil` and `:atlas pins` **rebuilds the window** rather than flipping a flag the draw callback
tests, because an `onDraw` that only reads a boolean is still a callback the engine invokes every frame and
still increments `calls.draw`. The same rule read backwards is what makes the claim assertable at all: the
*other* state of the same addon must be able to read non-zero.
**Rationale.** (2026-08-04, 037.5.) The measurement that closes 037 is "a recorded map on the screen costs 0
draw and 0 widget callbacks of ours", and it is only true because a grid drawing is an image handle the
stylesheet hands to the engine (D-098/D-043). A flag inside a draw callback would have made the claim false
by one line — and false in the way that is hardest to see, since the panel would look identical and the
counter would read 1 instead of 0 with no visible cause. This is 036.4 and 037.4's lesson ("a zero is only
as good as the scene it is measured in") carried one step further: the scene must be able to read non-zero,
**and** the zero must not depend on a runtime condition, or it is a coincidence of the current state rather
than a property of the design.
**Consequences.** A cost round asserts a pair, not a number: the engine-painted state at 0 and the Lua-drawn
state above 0, read out of **one** sample so the two halves are comparable. An example addon that
demonstrates a cost therefore ships both states as separate constructions, which is why `:atlas pins` is
documented as the other half of the measurement rather than as a decoration. Generally: *if a claim of "this
costs nothing" survives only while a flag is false, it is a measurement of the flag.*
**See.** [D-098](architecture-api.md), [D-095](architecture-api.md), [D-043](fonts.md),
[037-map-database](../037-map-database/spec.md).

### D-100 — the state belongs on the THING, and a sweep is the shape of having put it elsewhere ✅ (2026-08-04)
**Decision.** When an addon attaches something to a game object, the record lives **on that object** (inside
the shared `LuaGobOverlay` attrib, partitioned per addon), not in a list on the `Addon` that a sweep matches
against the world. `gob:overlay(key, spec)` names the gob, so nothing is searched: the attach is O(1) and the
draw pass paints the records it is already standing in. What that deletes is not one loop but a whole
category — `hafen.ui.gobOverlay`'s filter list, its throttled 5 Hz world sweep, the per-frame re-match inside
the draw, **and** the pruning an addon-side `gobId -> record` map would have needed.
**Rationale.** (2026-08-04, 038.1.) The old surface registered a *predicate* over the world, so the engine
had to keep asking every gob whether it still matched — twice, at two different rates, with the answers
disagreeing in between. Once the state is an attribute of the gob, the engine ends it for us: `Gob.dispose()`
disposes every `GAttrib` and a gob dropped from `OCache` takes its attribs with it, so "an overlay dies with
its gob" costs **no code at all** — no `GobRemoved` handler, no prune, and no decision about whether to keep
a record "in case it comes back" (a felled tree never does). The ROADMAP had this backwards: it listed a
*per-gob match cache* as future polish, i.e. a cache for a search that should not exist.
**Consequences.** The declarative form is gone and is not coming back as a second door: "every player gets a
label" is a `GobAdded` handler plus a loop over what is already there, and that trade is deliberate. Teardown
becomes the ONE caller that must find an addon's records across gobs — a single sweep of the object cache at
`:reload`/disable, i.e. at a rare moment instead of 5 times a second. The general rule: *a sweep in an API is
usually a symptom — ask what the state would have to be attached to for the engine to end it for you.*
**See.** [D-044](../decisions/architecture-api.md), [D-098](architecture-api.md),
[038-gob-overlays](../038-gob-overlays/spec.md).

### D-101 — where a name cannot separate two engine objects, publish the COLLAPSE rather than reaching for the id ✅ (2026-08-04)
**Decision.** The game's own overlays are keyed by their **resource name**, and where a gob carries several
overlays of one resource they collapse to a single Overlay entity whose `:count()` publishes the
multiplicity. The alternative on the table — `Gob.Overlay.id`, via `findol` — is refused.
**Rationale.** (2026-08-04, 038.1.) This was the plan's one open point, and it was **measured, not assumed**
(032.1's precedent): the suite compares the raw `gob:info().overlays` list against the keyed read over every
live gob, and the first in-game run answered **13 of 33 gobs with overlays carry two of one resource, worst
by four**. So the collapse is ordinary and had to be faced. It is still not an argument for the id: that id
is `-1` whenever the server gave none, so it collides in exactly the same places, it is a *number* where
every other key in this API is a name (`gob:overlay(3)` addresses nothing a reader can recognise), and it
does not survive a re-add. What settles it is that a native overlay is **read-only** — there is no operation
a finer identity would enable — so a union loses nothing except the count, and the count can simply be said.
This is D-093 ("an entity's identity is the key a NAME can address") with 037.3's mask precedent, plus the
half neither of them needed: the collapse is *visible* instead of implicit.
**Consequences.** `ov:count()` answers 1 for an addon's own overlay and N for a native union, and
`gob:info().overlays` stays as the raw, uncollapsed view for anyone who wants it. The suite keeps the census
and turns it into an invariant — `sum(ov:count())` must reconcile with the raw list on every gob — with a
second line asserting the collapse is *real where the player is standing*, or the reconciliation proved
nothing stronger than `1 == 1`. Generally: *before collapsing a compound engine key, measure how often the
collapse bites, then publish what it hides.*
**See.** [D-093](architecture-api.md), [D-072](architecture-api.md),
[038-gob-overlays](../038-gob-overlays/spec.md).
