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

### D-012 — Reference-based reads, ONE flat calling style, unified under `hafen.gob` 💤 SUPERSEDED by D-044 (2026-07-30)
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

### D-022 — Token set + items handle-only ✅ (closes Q-013) — gob token set 💤 SUPERSEDED by D-044
Tokens: `"player"`, `"party1".."partyN"` (ordered by `Member.seq`), `"target"` (combat only).
`"mouseover"` **deferred** (needs hover hit-test tracking — a later phase). Items are addressed
**by handle only** (no `(container,slot)` alternative), honoring [D-013](architecture-api.md).
**Superseded (017-gob-oop, 2026-07-30).** `"player"` as a `hafen.gob("player")` token is gone — the
gob half of this entry is dead, replaced by `hafen.player():gob()` ([D-044](architecture-api.md)).
The items half (**handle only**, no `(container,slot)`) is untouched and still governs items.

### D-044 — Gob is OOP + hard cut: `hafen.gob(id)` factory, methods, no flat table ✅ (2026-07-30)
**Decision.** The Gob surface moves from the flat reference accessor (`hafen.gob.health(ref)`) to a
real OOP class: `hafen.gob(id)` is a factory returning an interned Gob; `gob:health()`/`gob:pos()`/
`gob:name()`/… are methods. A Gob wraps **only the id** — every method re-resolves against `OCache`
and answers `nil` if the gob is gone, so [D-012](architecture-api.md)'s freshness semantics carry
over verbatim; what changes is that the reference stops being an argument and becomes the object.
**Hard cut**: the flat `hafen.gob` table and the GobRef token strings are deleted outright — no
shim, no deprecation, one calling style ([D-013](architecture-api.md)). Mechanism: userdata (not a
table) via `LuaValue.userdataOf`, with a **per-addon** metatable (`__index` → shared methods,
`__tostring`, `__name`) — userdata keeps the interned object unforgeable and immutable from Lua (the
R1 handle pattern), where a table would let one addon function scribble on an object every other one
shares.
**Rationale.** [D-012](architecture-api.md) chose the flat style project-wide; for gobs specifically
the WoW-token model was outgrown once identity (`==`, `seen[gob]=true`) and a stashed handle tracking
a moving gob became things addon code needed to express, and a second parallel handle style would
have been exactly the dual API [D-013](architecture-api.md) forbids — so the flat style is retired
for gobs rather than duplicated.
**Consequences.** Supersedes [D-012](architecture-api.md) and the gob half of
[D-022](architecture-api.md) for gobs specifically; every other namespace stays flat, and that
coexistence is deliberate and transitional (see `hafen.kin`, `hafen.actionbar`, … as they migrate).
This mechanism — userdata + per-addon metatable + weak intern cache — is the template every later
OOP migration (`hafen.kin`, widgets, slots, sounds, buffs, meters) copies verbatim
([D-056](architecture-api.md)).
**See.** [D-012](architecture-api.md), [D-013](architecture-api.md), [D-045](architecture-api.md),
[D-046](architecture-api.md), [017-gob-oop](../017-gob-oop/spec.md),
[luaj-bridge.md](../learnings/luaj-bridge.md).

### D-045 — Entity identity by a per-addon, weak-value intern cache with a drained `ReferenceQueue` ✅ (2026-07-30)
**Decision.** Each `Addon` owns `Map<Long, WeakReference<LuaValue>>` for its interned Gobs (id →
handle), drained on every access rather than on a sweep timer — amortised, no timer thread.
**Weak values**, not weak keys: `WeakHashMap` is the wrong tool here, since the id (a boxed `Long`)
is not what should expire, the *handle* is. `hafen.gob(id)` is drain → cache hit → or mint + insert.
The cache lives in the addon's own env, so it dies whole on `:reload`/disable; nothing is static in
`AddonManager` (a static cache would survive a reload, reintroducing the stale-state trap addon
reloads exist to avoid).
**Rationale.** Interning is what makes `hafen.gob(id) == hafen.gob(id)` true and lets a handle be a
table key (`seen[gob] = true`), which the flat reference style ([D-012](architecture-api.md)) could
never offer since it never minted an object to begin with. Scoping the cache **per-addon** rather
than globally means no interned Lua value ever crosses a sandbox boundary
([D-017](security-sandbox.md)) — a global cache would leak one addon's object identity into another.
**Consequences.** `LuaGob` holds no reference to the underlying `haven.Gob`, so a stashed handle
never pins a despawned gob (or its resources) alive — the cache can only be asked for a **fresh**
lookup, never used to keep something around. This is the interning template every later entity
(`hafen.kin`, action-bar slots, paginae, sounds, buffs, meters) reuses verbatim
([D-056](architecture-api.md)), except where the population itself demands a different shape (the
widget tree's `WeakHashMap<Widget, WeakReference<LuaValue>>`, [D-064](architecture-api.md)).
**See.** [D-044](architecture-api.md), [D-017](security-sandbox.md), [D-056](architecture-api.md),
[D-064](architecture-api.md), [017-gob-oop](../017-gob-oop/plan.md).

### D-046 — Player is a composition anchor: `:gob()` only, no forwarded Gob methods ✅ (2026-07-30)
**Decision.** `hafen.player()` becomes callable, returning a Player object (`nil` before entering
the world). Player is a **minimal** object: `:gob()`, `:name()`, `:vitals()`,
`:worldToScreen(x,y)` — nothing else. `:exists()`/`:id()` are dropped: `player:gob()` returning
`nil`/non-nil already answers existence, and `gob:id()` already answers the id, once you have the
Gob. Player carries **no forwarded Gob methods** — there is no `player:pos()` shortcut for
`player:gob():pos()`.
**Rationale.** A forwarded `player:pos()` would coexist with `player:gob():pos()` as two ways to ask
the same question — exactly the dual style [D-013](architecture-api.md) forbids — and every method
added to Gob would then owe Player a matching forward or become an inconsistency. Composition
(`player:gob()` is the one door to everything Gob offers) keeps Player's surface fixed regardless of
how large Gob's grows.
**Consequences.** `hafen.player():gob():health()` is one call longer than a forwarded
`hafen.player():health()` would have been; accepted as the cost of one canonical way. The pattern
generalises: an object that is a composition ROOT over another entity exposes the door to it
(`:gob()`), never a duplicate of what is behind the door.
**See.** [D-044](architecture-api.md), [D-013](architecture-api.md), [017-gob-oop](../017-gob-oop/spec.md).

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
cut visible from Lua. The mechanism is [D-044](architecture-api.md)/[D-045](architecture-api.md)
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
**See.** [D-013](architecture-api.md), [D-044/D-045](architecture-api.md), [D-056](architecture-api.md),
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
**See.** [D-013](architecture-api.md), [D-045](architecture-api.md), [D-056](architecture-api.md),
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
**See.** [D-045](architecture-api.md), [D-056](architecture-api.md), [D-059](architecture-api.md),
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
**See.** [D-044](architecture-api.md), [D-098](architecture-api.md),
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

### D-102 — the END of a derived thing rides the event its SOURCE already raises; a "report" needs a reporter ✅ (2026-08-04)
**Decision.** A world-space `gob:overlay` — a client-only entity anchored to a target gob by a `FollowMoving`
— is destroyed from the tick that drains the client's own `OCache` removal, `LuaGobOverlay.gobGone(gob)`
running just before `GobRemoved` reaches Lua. The plan's design, "`FollowMoving` reports a lost target
instead of holding position", is refused.
**Rationale.** (2026-08-04, 038.2.) The plan was right that this is the one thing "an overlay dies with its
gob" actually costs. For the screen-space kinds the death is free — the record lives in a `GAttrib` and the
`Gob` takes it with it — but a world overlay's visual is *its own gob* in the scene, and nothing disposes it
because the target left `OCache`. (Worth writing down precisely: **`OCache.remove` does NOT call
`Gob.dispose()`**; it sets `removed` and drops the entry, so attribs go by GC. That is exactly why one half
is free and the other is not.) The trouble with "report" is the missing second half: `getc()` runs on the
render/loader threads and has nobody to report *to*. Giving it one means a list of anchored entities checked
every frame — which is the 5 Hz sweep 038.1 deleted, re-introduced at 60 Hz and wearing a different name.
The removal is already an event the client raises, already marshalled to the UI thread, and — because
D-100 put the store **on the gob** — it arrives holding the exact records, so the fix is O(1) and needs no
list of anything. `FollowMoving` keeps its hold-at-last-position, now bounded to the one frame between the
removal and the tick that drains it.
**Consequences.** No new core edit (the seam is the existing `GobRemoved` drain), no per-frame work, and the
in-game proof is a two-command round with a walk between it (`:t038-2 park` … `:t038-2 gone`), because only
a person can make a gob despawn; the deterministic half is the headless probe, whose fabricated `MapView`
*counts* scene adds and removes. Generally: *when a derived thing must end with something else, look for the
event that something already raises before inventing a watcher — a watcher is a sweep with better manners.*
**See.** [D-100](architecture-api.md), [D-095](architecture-api.md),
[038-gob-overlays](../038-gob-overlays/spec.md).

### D-103 — an ABSORBED mechanism keeps one door: the old option is refused AND the old listing stops handing it back ✅ (2026-08-04)
**Decision.** `follow =` on `hafen.render.sprite`/`object`/`hafen.ghost.new` and the handles'
`:follow`/`:offset` are a hard cut — the option **raises naming `gob:overlay`**, the methods read plain
`nil`. And the entity a world overlay owns is flagged `asOverlay`, so it never appears in
`hafen.ghost.list()`. There is no `overlay:move` either: an overlay's position *is* its gob's, and the only
thing an addon sets is the `offset`, by re-attaching under the same key.
**Rationale.** (2026-08-04, 038.2.) Cutting the old *option* is routine (nothing is released; a removed
symbol reads nil, a silently ignored one leaves a sprite standing at `0,0` on the far side of the world with
nothing to say why — D-072). The non-obvious half is the **listing**: an overlay's ghost is a real
`LuaGhost` in `Addon.ghosts`, so `hafen.ghost.list()` would have handed it back for free, and the handle it
hands back carries `:destroy()` and `:move()`. That is a second door onto one thing, and worse than a
duplicate: `:destroy()` would kill the visual behind a record that still reads as attached, and `:move()`
would fight the anchor every frame. Hiding it costs one boolean and one condition. The same reasoning
removes `overlay:move`: a verb that competes with the mechanism is not a convenience.
**Consequences.** `hafen.ghost.list()` means *the ghosts this addon placed*, which is now a sharper answer
than before; the composed verbs on an Overlay are the look and facing only (`:tint`/`:alpha`/`:scale`/
`:rotate`) plus the read `:pos()`. Also refused, for the same reason: `clickable`/`onClick` in an overlay
spec — the thing under an overlay is the gob, and clicking a gob is the client's own. Generally: *when one
mechanism absorbs another, close the old constructor **and** audit every existing read that could still hand
the new thing out under the old identity.*
**See.** [D-013](architecture-api.md), [D-072](architecture-api.md), [D-102](architecture-api.md),
[038-gob-overlays](../038-gob-overlays/spec.md).

### D-104 — an event's SCOPE follows its key's scope: owner-scoped where the name is private, broadcast where it is public ✅ (2026-08-04)
**Decision.** `GobOverlayAdded`/`GobOverlayRemoved` carry `{ gob, key, native }`, and who is told depends on
which half fired: an **addon's own** overlay (`native = false`) is reported **only to that addon**, while one of
the **game's** (`native = true`) goes to every subscriber.
**Rationale.** (2026-08-04, 038.3.) An overlay key is per addon (038.1), so a `native = false` event handed to
a bystander would name a key that addon **cannot read** — `gob:overlay(key)` answers nil for it, and
`gob:overlay()` never lists it. A name that addresses nothing is worse than no event at all: it invites a
handler to act on something it has no door to. A native key is a *resource name*, which every addon reads
identically, so there the broadcast is exactly right. This is `GhostClicked`'s shape one level down (an entity
private to its addon fires only to it) arrived at from the *key* rather than from the handle.
**Consequences.** The two halves of one read are two dispatch rules in one method, decided by whether the
event carries an owner. Teardown (`:reload`/disable) fires **nothing** — the only addon that could hear it is
the one going away — and a gob's death fires **synchronously** from the tick's `GobRemoved` drain, so an
overlay is never reported dying *after* the thing it was attached to. Generally: *before broadcasting an event,
ask whether every receiver can act on the name it carries; if not, the event is owner-scoped.*
**See.** [D-045](architecture-api.md), [D-100](architecture-api.md), [D-105](architecture-api.md),
[038-gob-overlays](../038-gob-overlays/spec.md).

### D-105 — where the read COLLAPSES, the event follows the KEY, not the engine object ✅ (2026-08-04)
**Decision.** A native overlay is a union over its resource name (D-101), so the engine seams report a change
of the **key set**, not of `Gob.ols`: the second overlay of a resource arriving is **not** an add, and one of
two leaving is **not** a removal. Counted after the engine's own mutation — first is `1`, last is `0`. The same
rule read from the other side makes a **replace** fire the removal *and* the add.
**Rationale.** (2026-08-04, 038.3.) An event that contradicts the read is worse than a missing one. Firing
`GobOverlayRemoved` for a key `gob:overlay(key)` still answers teaches a handler that its own map may drift
from the truth, which is precisely what the event exists to prevent. The replace is the mirror image: the key
survives but the thing under it is a different one, so a handler keeping its own set must see one leave and one
arrive or its counts drift the other way.
**Consequences.** The seam pays one `countNative` per engine add/remove, which is why the whole path is behind
a subscription flag. `ov:count()` is the surface where the collapsed multiplicity is still readable, and it
moves without an event — deliberately, because the *key* did not change. Generally: *when a read collapses
several engine objects into one name, the events over it are edges of the collapsed set, and its cardinality is
a value to read, not an event to fire.*
**See.** [D-093](architecture-api.md), [D-101](architecture-api.md), [D-104](architecture-api.md).

### D-106 — a queue whose handlers write back into it is drained ONE FRAME'S WORTH, never to empty ✅ (2026-08-04)
**Decision.** The gob-overlay event queue is drained by the count standing in it when the tick begins. An event
a handler causes is delivered on the **next** frame.
**Rationale.** (2026-08-04, 038.3.) This is the first event on this bus whose handler can trivially cause the
same event: a `GobOverlayAdded` handler that re-attaches under the same key produces a removal and an add
(D-105), so `while(poll() != null)` never terminates. Not a hang the addon watchdog catches either — each
individual `callLua` is short and returns; it is the *engine's* loop that never ends. Bounding the drain turns
the pathological addon into a slow loop that is visible in the profiler, priced against its own budget, and
interruptible by `:reload`, instead of a frozen client. **Falsified**: unbounding the drain hung the probe JVM.
**Consequences.** An addon may observe its own write one frame later than it made it, which is already the
model (both events are queued, since `addol` runs on the loader threads and nothing may call into Lua from
there). Generally: *the moment a queue's consumers can produce for it, "drain until empty" is an unbounded
loop wearing a for-statement — snapshot the length.*
**See.** [D-018](security-sandbox.md), [D-102](architecture-api.md), [038-gob-overlays](../038-gob-overlays/spec.md).

### D-107 — when a config table becomes chained setters, the action moves off the constructing call ✅ (2026-08-04)
**Decision.** `hafen.http():get(url, cb)` hands back the request **without sending it**. The request goes out
on the next tick, so `req:header(name, value)` and `req:timeout(ms)` chained onto it are applied first; a
setter called after it has gone throws, and a request cancelled in the same call is never sent at all.
**Rationale.** (2026-08-04, 039.1.) R4 replaces an options table with setters on the returned object, and that
changes *when* the object is complete: with a table the constructor received everything it would ever know, so
sending inside the call was correct. With setters the object is finished one or more calls later, and the old
code path submitted it to a pool thread that reads `timeout` and `headers` — a data race whose loser is
silent, since a request that went out with the default timeout looks exactly like one that took the setter.
Deferring by one tick costs nothing against network latency and makes the setters mean what they say. The
scheduler already ran on the tick (`HttpApi.drainHttp`), so this is a queue and a drain, not a new mechanism.
**Consequences.** Every later builder in this feature inherits the rule: the verb that *acts* cannot be the
verb that *constructs*, or the setters between them are decoration. Generally: *de-tabling a constructor is
not a syntax change — it moves the moment the object is complete, and anything the constructor used to do
immediately has to move with it.*
**See.** [D-013](architecture-api.md), [D-099](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-108 — a mechanism built for later tasks ships with a consumer, or it ships unproven ✅ (2026-08-04)
**Decision.** `hafen.timer()` is both the section object and a **collection** of that addon's live timers
(`:list/:count/:find` beside `:after`/`:every`), so the collection type built in the machinery task has a real
home in the same task. It carries no `:remove` — a timer ends with `t:cancel()`, and two spellings for one
operation is the dual style the grammar removes.
**Rationale.** (2026-08-04, 039.1.) The machinery task's whole job is to build once what thirty sections will
consume, and its acceptance is a suite that asserts through the API it just shipped. A collection type with no
section using it cannot be asserted at all: the refusals that define it (`#coll`, `coll[1]`, a string filter
over nameless members) are unreachable from Lua, and it would land in the first section that needs it with its
first test. Of the eight sections here it is the only honest fit — a section that holds exactly one thing IS
that thing, and what `hafen.timer()` holds is the addon's timers, already tracked per addon and already torn
down per addon. `hafen.http()` was rejected for it: its `get`/`post` are HTTP methods, and a collection's
`:get(key)` would collide.
**Consequences.** One row of surface that no page asked for, in exchange for a type that arrives at task two
already proven. Generally: *a shared mechanism with no first consumer is a design, not an implementation —
give it the smallest honest one in the same task, or do not claim it is built.*
**See.** [D-056](architecture-api.md), [D-085](process.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-109 — a value with two forms derives, never converts, and offers no equality it cannot keep ✅ (2026-08-04)
**Decision.** A **Position** holds *either* a session world coordinate *or* a durable anchor (a
server-published grid id plus the offset inside that grid) and derives the other on demand. Neither form is
primary: `gob:position()` and `hafen.world():position(x, y)` mint the first, the store and
`hafen.world():position(saved)` mint the second, and both answer the same verbs. A place recorded in a
segment the character is not standing in keeps `:info()`/`:durable()` and answers **nil** for `:x()`,
`:y()`, `:offset()` and `:tileCoord()`. There is **no `__eq`**: two Positions are equal only if they are the
same object, and the page says to compare what one *names* — `:info()`, `:tileCoord()`, `:distance()`.
**Rationale.** (2026-08-04, 039.2.) The type exists because a place must be two things a plain `{x, y}` can
only be one of, so "convert one into the other at construction" would have thrown half of it away at the
door: a Position built from a saved anchor whose grid is elsewhere has no session coordinate at all, and
converting eagerly would have made it `nil` — which is exactly the silent loss the store marshalling exists
to prevent. `__eq` was refused for a measurable reason rather than a stylistic one: the store round trip is
`ul + (wx - ul)`, which is exact to the tile and **not** to the last bit of a double, so an equality over the
numbers would report two names for one place as different — and an equality that compares the *anchors*
instead would report the same place as different whenever one side happens to be located and the other not.
The acceptance criterion itself says "resolves to the same **tile**", which is the honest granularity.
**Consequences.** Every verb documents its own nil, and the two "not the same numbers" facts (`:info()` is
within-grid, `:x()` is session world) are stated on the page rather than inferred. Generally: *a value with
two representations derives lazily and publishes the boundary where one of them is unavailable; and it
offers `==` only if it can keep it for every pair the API can hand you.*
**See.** [D-063](architecture-api.md), [D-094](architecture-api.md), [D-092](process.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-110 — a read that asks WHERE something is must not put traffic on the wire ✅ (2026-08-04)
**Decision.** The live half of a Position's durability lookup is a **plain lookup** into the streamed-grid
map (`AddonWidgets.loadedGrid`), never `MCache.getgrid`. The engine's own accessor *requests* the grid from
the server on a miss and throws; here a grid being absent is the **answer**, and the recorded map is asked
next.
**Rationale.** (2026-08-04, 039.2.) `p:durable()` and `p:info()` are ordinary reads an addon may run in a
loop — a panel asking about a dozen saved places, a sweep probing outward for explored ground. Through
`getgrid` each of those would have queued a map request for ground nobody is standing near, up to five
re-sends apiece, for a question that was only ever *about* that ground. The suite's own outward sweep is the
case that made it concrete: twelve probe points, each a request the player never asked for.
**Consequences.** One more accessor in the one `haven` file that carries this surface, and a read model that
is genuinely free. Generally: *asking where a thing is is not asking for it — a locating read looks at what
the client already has and answers nil, and only a verb that means "fetch this" may make the client fetch.*
**See.** [D-095](architecture-api.md), [D-011](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-111 — a PREDICATE about the stored world answers from memory, because "not yet" and "no" are one word ✅ (2026-08-04)
**Decision.** `:durable()` resolves a segment coord to a grid id through a new one-line accessor,
`MapFile.Segment.gridid(sc)`, which reads the segment's own coord→id map — already wholly in memory once the
segment is loaded. It deliberately does **not** go through `Segment.grid(sc)`, the {@code Indir} every other
recorded read uses.
**Rationale.** (2026-08-04, 039.2.) D-095's load model — kick the load, answer nil, ask again next tick — is
right for a read that hands back *data*, because nil is readable as "ask again". A **boolean** has no such
spelling: `false` from a still-loading grid and `false` from ground never visited are the same word, and the
first is a lie about ground the character has walked over. Going through the `Indir` would also have made a
durability check pay a `Defer` disk read for tiles it never looks at. The id is the one thing the segment
already knows without touching the disk, so the predicate is exact and immediate.
**Consequences.** A second `haven` seam (tagged `// addon:`), and `:durable()` is a fact rather than a
timing artefact — which is what let the suite assert *"explored but not streamed is durable"* as a plain
check instead of a staged one. Generally: *before applying a load model, ask what shape the answer is — a
value may say "not yet", a boolean cannot, so a predicate over stored state must be answerable from what is
already in memory or it must not be a predicate.*
**See.** [D-095](architecture-api.md), [D-109](architecture-api.md), [D-011](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-112 — a builder that ATTACHES is attached INERT, and what it draws is the completion ✅ (2026-08-04)
**Decision.** `gob:overlay():add(key)` attaches an overlay **at once** — it exists, it is in the collection,
it fires `GobOverlayAdded` — and it **draws nothing** until one of `:draw/:text/:image/:model/:ghost` names
what it is. An overlay says exactly one thing, so a second, different kind throws naming the first; the way to
make it say another is `:add` on the same key, which is already a replace.
**Rationale.** (2026-08-04, 039.3.) D-107 answered the *sending* case by deferring the action a tick. An
attachment cannot be deferred that way: the caller holds the returned object and configures it through several
statements, and a frame may fall between any two of them. The alternative — hold the record out of the store
until it is complete — reintroduces the silent failure the grammar exists to remove, because an overlay you
forgot to give a kind would simply never appear, with nothing to read back and nothing to blame. Attaching a
record with no kind makes the incomplete state *visible* (`ov:kind()` is nil, `:exists()` is true) and makes
"a half-configured thing never paints" a property of the shape rather than a rule: the draw pass skips a
record that has not said what it draws, because there is nothing there to draw.
**Consequences.** The events fire on the `:add`, one frame before a handler runs, so a handler still reads a
fully configured overlay — the queue (D-106) is what buys that, not a rule. Generally: *when a config table
becomes setters on a thing that is ATTACHED rather than sent, attach it inert and let the payload verb be the
completion — an incomplete thing you can read beats a complete one you never got.*
**See.** [D-107](architecture-api.md), [D-100](architecture-api.md), [D-106](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-113 — a setter that changes how the visual is BUILT rebuilds it; every other setter is live ✅ (2026-08-04)
**Decision.** On a world-space overlay the visual is milled when the kind setter names it. Two setters change
*which* visual is milled — `:billboard(b)` picks a camera-facing blit over an upright quad, `:spawnData(sdt)`
picks a resource variant — and setting either after the overlay already stands **rebuilds** it; set them in
the same statement as the kind and it is built once. Everything else (`:scale :alpha :tint :rotate :offset`)
is applied to the live entity and *also* remembered on the record, so a rebuild comes back looking the same.
The new visual is built before the old one is let go, and a setter that raises restores the record's own
fields, so a bad asset handle or a missing map view leaves the overlay exactly as it was.
**Rationale.** (2026-08-04, 039.3.) A config table was read once, so the distinction never had to exist:
everything arrived together and construction-only versus live was invisible. With setters the two genuinely
differ, and the three ways out are all worse than rebuilding. Requiring the construction properties *before*
the kind inverts the natural reading order (`:image(a):billboard(true)` would throw). Refusing them after the
kind makes the natural order illegal. Deferring the whole build to the next tick moves a "there is no map
view" error off the call site that caused it, which is exactly what 038 built the eager parse to avoid. A
rebuild costs one flicker in the uncommon case and keeps every order legal and every error local.
**Consequences.** `FollowMoving.off` became live again (it had been set only at create), which is what makes
`ov:offset` move an anchored thing in place instead of needing a re-attach. Generally: *de-tabling a
constructor splits its keys into the ones that choose the object and the ones that dress it — name the split,
rebuild for the first, and keep the second live, or the setters quietly mean different things.*
**See.** [D-107](architecture-api.md), [D-112](architecture-api.md), [D-102](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-114 — on a departed owner a creation RAISES and a removal is INERT ✅ (2026-08-04)
**Decision.** `gob:overlay():add(key)` on a gob that is gone **throws**, naming `gob:exists()`. `:remove(key)`
there is a clean no-op, as is removing a key that was never attached, and `:list()`/`:get()` answer an empty
collection and nil. The old four-arity verb answered `nil` for all of them.
**Rationale.** (2026-08-04, 039.3.) The two are not symmetric even though they read that way. A removal on a
departed gob has already happened — the overlay died with the gob, which is this feature's whole claim — so
there is nothing to report and nothing a caller could do differently; that is D-084's *inert, never an error*.
A creation has nowhere to go: it returns an object the caller is about to chain setters onto, and answering
nil turns the very next `:text(...)` into "attempt to index a nil value" one line later, which is the failure
mode the retired-name table exists to prevent. Raising at the `:add` names the gob and the test for it.
**Consequences.** An addon attaching from a `GobAdded` handler for a gob that despawned in between now sees an
error rather than a silent nil; it is `pcall`-able and the message says to read `gob:exists()` first.
Generally: *a verb that hands back something to be chained cannot answer nil on a miss — either it raises, or
the chain fails somewhere that no longer names the cause.*
**See.** [D-084](widgets-ui.md), [D-100](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-115 — a set that CANNOT be enumerated refuses to be, naming the two verbs that do work ✅ (2026-08-04)
**Decision.** `hafen.map():grid()` and `seg:grid()` are collections whose `:list()`, `:count()` and `:find()`
**throw**, naming `:get(id)` / `:get(sc)` and `seg:grid():list(area)`. Every other collection in the API
enumerates; these two say out loud that they will not.
**Rationale.** (2026-08-04, 039.4.) The recorded map is every grid the character has ever walked over — tens
of thousands, each a file. There are exactly three things a collection can do when it cannot answer "all of
them": hand back an empty array, hand back everything, or refuse. Empty is a **lie** a caller cannot tell
from "no grids"; everything is a thousand disk reads nobody asked for; the refusal is the only one that
leaves the reader better informed than before they called. It is also what the engine itself does — `MiniMap`
never enumerates either, it walks the grid coords of the rectangle it is drawing — so the API is publishing
the client's own access pattern rather than inventing a cheaper-looking one.
**Consequences.** `LuaCollection.Source.members()` may throw, and `:list`/`:count`/`:find` propagate it
unchanged, so one message covers all three with no machinery. A collection is therefore not a promise that
every §2.3 verb applies — the ones that do not are a *documented refusal*, exactly as `#coll` is. Generally:
*when a set is too large or too expensive to enumerate, the accessor that would enumerate it is where you say
so; an empty array is the one answer that cannot be distinguished from the truth.*
**See.** [D-072](widgets-ui.md), [D-095](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-116 — a REF-COUNTED hold is not a property, so it gets verbs rather than an arity ✅ (2026-08-04)
**Decision.** The client's four claim/province display switches become an **entity**,
`hafen.map():overlay():get(tag)`, whose verbs are `:shown()` (is it drawn, by anyone), `:held()` (do I hold
it), `:hold()` and `:release()`. The old `hafen.map.overlay(tag)` / `(tag, on)` pair is cut.
**Rationale.** (2026-08-04, 039.4.) Arity as the verb says `x:name()` reads what `x:name(v)` wrote — and that
was never true here. D-097 established that `MapView.oltags` is a multiset shared with the user's own checkbox
and the server's claim flash, so an addon can only add its `+1` and take it away: `(tag, false)` cannot turn an
overlay off, and `(tag)` answers *the screen* rather than *your write*. Two different questions were being
spelled as one property, and the arity form was actively claiming the opposite. Three verbs make the asymmetry
unmissable and cost nothing, because a hold was already an owned resource with a teardown.
**Consequences.** A settings UI reads `:where()`/`:what()` off the same object instead of a snapshot row, and
`:held()` versus `:shown()` is now a distinction the page cannot fail to make. Generally: *before spelling
something as a property, check that the read is the read OF that write — where it is not, the shape is lying
and no amount of documentation makes it stop.*
**See.** [D-097](architecture-api.md), [D-069](widgets-ui.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-117 — a section whose verbs move over SEVERAL tasks is mounted with the un-moved ones still on it ✅ (2026-08-04)
**Decision.** `hafen.ui` becomes the section object while `window`, `widget`, `overlay` and `skin` stay plain
fields on its callable table, until the tasks that own them cut them. `Section.mount` takes the pre-populated
table; a field is found by `rawget`, so it never reaches the retired-name `__index` and the two halves need no
rule between them.
**Rationale.** (2026-08-04, 039.5.) "A hard cut is ONE task or it is a broken client" is about a *cut*, and
these four are not renames: the builders lose their `opts` table for chained setters and the sheet becomes a
Sheet of Rules. Moving them now would spell 130 `skin{` sites `hafen.ui():skin{`, then spell all 130 again a
task later — churn that proves nothing and reviews as noise. The alternative, deleting them until their task
lands, leaves the client unable to draw a window for two tasks.
**Consequences.** A section can be half-migrated without a transitional alias, a shim or a deprecation
period, because the un-moved half is not a compatibility layer — it is simply the code that has not been
touched yet. The rule that makes it safe is the table lookup order, so it costs one overload and nothing at
runtime. Generally: *when a subsystem's cut is genuinely several cuts, migrate it one verb-group at a time and
let the untouched verbs keep their old shape — the transitional state is the OLD code, never new code written
to be thrown away.*
**See.** [D-013](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-118 — the retired-name table carries what THIS migration renamed, and nothing else ✅ (2026-08-04)
**Decision.** `hafen.ui.root` is not in the retired-name table, even though `hafen.ui():root()` is now the
live spelling of the tree's top. It was cut by an earlier feature and reads as plain `nil`, and it goes on
reading `nil`.
**Rationale.** (2026-08-04, 039.5.) The table is generated from one feature's before/after inventory, which is
what makes its coverage mechanical: a row in the inventory with no entry is a porting error nobody is told
about, and that check only works if membership means exactly one thing. Admitting names retired by earlier
features makes the table a general obituary — unbounded, unverifiable, and in direct tension with the
`__index` contract that anything *not* in it reads `nil` so a feature probe (`if hafen.something then`) keeps
working. A shipped suite asserts `hafen.ui.root == nil` as a hard-cut contract, and that assertion is correct.
**Consequences.** Someone typing a spelling three features dead still gets "attempt to call a nil value", and
that is the accepted cost: the table exists to make *this* port possible, not to be a museum. Generally: *a
guiding refusal is scoped to the migration that owns it — when the scope is a mechanical check, widening it
for kindness destroys the check.*
**See.** [D-013](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-119 — a builder is attached INERT and skips its DRAW; holding it out of the tree buys nothing and costs a capability ✅ (2026-08-04)
**Decision.** `hafen.ui():window()`/`:widget()` add their surface to the tree at once and mark it *pending*:
every lookup finds it, every setter answers on it, and it **paints nothing** until `UiApi.armPending()` clears
the flag on the next `AddonManager.tick`. The window chrome is skipped by an **anonymous** `Window` subclass,
so `LuaWidget.typeName`'s climb past anonymous classes keeps `w:type()` reading `Window`.
**Rationale.** (2026-08-04, 039.6.) The other candidate — hold the surface out of the tree until the arming
tick — was built first and reverted. It is strictly stronger on paper (not drawn, not hit-tested, not laid
out) and that extra strength *is the defect*: "find the widget I just built" stops working, which 039.5's own
suite asserts (`hafen.ui():at()` on a probe created in the same statement) and which any addon may reasonably
do. It also made `live()` kill a pending handle — every setter in the chain a silent no-op — needing a
`pendingRoot` walk to paper over. That is a capability spent to buy a guarantee about *painting* that skipping
the draw already gives in full. D-112 answered the same question for an overlay and answered it this way.
**Consequences.** A surface is clickable one frame before it is visible, which is the honest cost and lasts
exactly one frame. `:parent(w)` becomes a real re-home (`remove()` + `add()`) rather than a field write, and
is therefore refused once the surface is on screen. A named `Window` subclass would have renamed the widget
for every selector in the client, so the *anonymity* is load-bearing rather than stylistic. Generally: *when
two mechanisms both deliver a guarantee, prefer the one that removes less — a stronger invariant that also
removes an ability is not stronger, it is a different feature.*
**See.** [D-112](architecture-api.md), [D-107](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-120 — a set whose members have no key is a BUILDER, not a collection ✅ (2026-08-04)
**Decision.** `hafen.ui():overlay()` **mints** a new HUD painter, where `gob:overlay()` and
`hafen.map():overlay()` hand back collections. The object it returns carries `:onDraw(fn)`/`:onDraw()`,
`:exists()` and `:destroy()` (R7), and the old handle's `:remove()` is a retired row naming `:destroy()`.
**Rationale.** (2026-08-04, 039.6.) §2.3 makes a set you can *address into* a collection, and the addressing
is the point: `gob:overlay()` keys on the overlay key, `hafen.map():overlay()` on the display tag. A HUD
painter is anonymous — there is no key, so `:get()` has no question to answer and `:add()`/`:remove()` would
be `:destroy()` spelled through a table that exists only to hold them. A collection of anonymous members
offers `:list()` and nothing else, which is a handle list wearing a collection's name.
**Consequences.** One word, `overlay`, has two shapes in the API, and the section it hangs on says which:
`hafen.ui()` builds, the entity-owned ones collect. That is legible because the three things `hafen.ui()`
builds — window, widget, overlay — all end the same way, with `:destroy()`. Generally: *the collection verbs
are earned by having a key; without one, a "collection" is a list of handles and the honest shape is a builder
whose product you hold.*
**See.** [D-115](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-122 — a selector-keyed MAP becomes a document you edit, plus the two verbs that say whether it is in force ✅ (2026-08-05)
**Decision.** `hafen.ui.skin{…}` becomes `hafen.ui():sheet()`: a per-addon **Sheet** object holding
`sheet:rule(selector)` levels, with `:install()` and `:drop()` as the only pair that says whether the
document is applied. An edit to an installed sheet lands **at once**, so there is no re-apply verb;
`sheet:load(rules)` edits the whole document from **data** and does not install by itself; and whether it is
installed is **derived** (`owner.skin != null`), never a flag on the object.
**Rationale.** (2026-08-05, 039.7.) R4 turns a config table into chained setters, but a stylesheet is not a
constructor's named arguments — it is a *map*, and a map has no chained spelling at all. What it does have is
a two-phase life the old call collapsed into one: you say what the look is, and separately it is the look.
Splitting them is what makes `:rule(sel)` addressable and `:load(t)` possible; keeping the applied state
derived is what makes teardown correct for free, since a `:reload` drops an addon's sheet without asking this
object and a stored flag would then be a second answer that had already gone stale.
**Consequences.** The 141 call sites become `sheet:rule(...)` chains or one `:load(t)`, and the capability
036.4 measured — a whole theme for zero lines of Lua — survives as the one door for a whole document rather
than as the shape of every call. "Install again to change one rule" disappears with it: the document is the
thing you keep. Generally: *where a surface is a MAP rather than an argument list, make the map an object you
edit and let a verb pair carry the moment it takes effect — and derive that moment from the engine, never
store it beside it.*
**See.** [D-107](architecture-api.md), [D-123](architecture-api.md), [033-ui-stylesheet](../033-ui-stylesheet/spec.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-123 — a handle to a level of a cascade is a NAME for it, not the record ✅ (2026-08-05)
**Decision.** `LuaRule` holds only its binding — `(sheet, selector)` or the widget — and every read and write
goes through it to where the level actually lives (the sheet's own map, or `Sheet`'s weak per-widget one). So
`rule:remove()` ends the level and the handle keeps working: a setter on it simply says that level again, and
`rule:info()` answers `nil` in between. The two bindings are ONE type with one verb set.
**Rationale.** (2026-08-05, 039.7.) 021.1's rule is that an interned userdata is an identity, not a record,
and D-065 pays it forward: state a weak cache could lose is derived, not stored. A widget's own level is held
weakly against a widget that will close, so a Rule that *carried* the properties would disagree with the
store the moment it was re-minted — and the alternative, refusing a removed handle, buys nothing a caller
wants: naming a level and then saying it is the ordinary way to write one.
**Consequences.** `sheet:rule(sel)` can be interned per selector without the sheet having to prune anything,
`widget:rule()` can be interned per widget with weak keys on both axes, and the same object serves a matched
rule and a hand-named one — which is why the two levels of the cascade read identically in Lua. Generally:
*where a handle names a level of a cascade rather than owning a lifetime, make it a NAME: it can then outlive
its own removal, and the store stays the single truth.*
**See.** [D-065](architecture-api.md), [D-077](fonts.md), [D-122](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-125 — a CLOSED vocabulary throws for an unknown verb, where an open entity reads nil ✅ (2026-08-05)
**Decision.** `LuaRule` and `LuaSheet` mount `Retired.closedIndex`: an unknown verb **throws** naming the
verbs that exist, instead of reading `nil` like every other entity's metatable.
**Rationale.** (2026-08-05, 039.7.) D-072 made a misspelt style *property* an error while an unresolved
*key* stayed inert, because a property has no future meaning to wait for. When the properties became verbs
that reasoning had to move with them, or the rule would silently lose its one guarantee: reading `nil` turns
`rule:colour(…)` into "attempt to call a nil value" one character later, which names the typo but not the
eight names it could have been. The precedent is already in the codebase — `Section` and `LuaCollection`
throw for the same reason, and for the same kind of surface.
**Consequences.** Two shapes now coexist deliberately: an entity whose surface may grow reads `nil`, so a
feature probe (`if w.something then`) stays honest, while a value whose verb set IS its whole grammar
refuses. The line between them is whether the vocabulary is closed, and the message says which names exist.
Generally: *let a closed vocabulary refuse and an open one answer nil — silence is only honest where the name
might mean something later.*
**See.** [D-072](architecture-api.md), [D-118](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-128 — a set the ENGINE owns and you cannot name is still a collection, and it carries no `:get` ✅ (2026-08-05)
**Decision.** `hafen.buff()` and `hafen.meter()` are collections with `:list(f)`, `:count(f)` and `:find(f)`
and **no `:get`** — asking for one throws *"has no verb 'get'"*, and the needle that used to address them
(`hafen.buff("poison")`) is now the filter `:find` takes. `hafen.kin()`, `hafen.actionbar()`,
`hafen.menugrid()` and `hafen.sound()`, whose members *do* have a key, keep `:get`.
**Rationale.** (2026-08-05, 039.9.) D-120 answered the keyless case with *a set whose members have no key is a
BUILDER*, and that answer is right for what the addon **mints**: an anonymous HUD painter has no key because
nothing else in the world claims it, so a collection would be a handle list. The buff bar is the other half of
the same question and comes out opposite — the addon mints nothing, the engine owns every member, and *how
many buffs do I have* and *is one of them poison* are real questions with real answers. So the keylessness
takes away exactly one verb rather than the shape. It also had to: two buffs can share a resource and a `"ch"`
uimsg **replaces** a live buff's resource under it, so a name is not an identity even for one instant — a
`:get("poison")` would be `:find` wearing a word that promises an address.
**Consequences.** `LuaCollection.Source.addressable()` already gated `:get`, so the refusal costs nothing and
its message names `:find`. The old lookups become `:find(needle)` and stay truthy-compatible, which is how
every shipped call site used them. Generally: *keylessness removes the verb that needs a key, and nothing
else; whether the shape is a collection or a builder is decided by who OWNS the members, not by whether they
can be named.*
**See.** [D-120](architecture-api.md), [D-060](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-129 — a set closed at LOAD refuses an unknown key; a set the world fills answers nil ✅ (2026-08-05)
**Decision.** `hafen.store():get(name)` **throws** for a name the addon's `manifest.json` does not declare,
and the message lists the names it does. That is the opposite of `hafen.kin():get(name)` or
`hafen.menugrid():get(key)`, where a miss is plain `nil`.
**Rationale.** (2026-08-05, 039.10.) D-125 drew the line at *closed vocabulary refuses, open one reads nil*,
and stated it over a value's verb set. A key set can be closed the same way, and the test is **when the set is
fixed**: an addon's saved variables are read out of its manifest before its first file runs and never grow, so
a name that is not there at load will not be there later — it is a typo, and nothing else. A kin roster or a
menu catalogue is filled by the world *while the addon runs*, so a miss genuinely means *not yet* and nil is
the honest answer (D-095's rule, one level up). The old spelling made this cheap to get wrong: `hafen.store
.cfgg` read nil and blew up on the next index with a message naming neither the variable nor the manifest.
**Consequences.** The refusal is worth a line of Java precisely because it can enumerate the alternatives —
*"declares no saved variable of that name. Declared: "cfg", "layout""* is a fix, not a diagnosis. Generally:
*a key set fixed before the addon runs may refuse; one the world is still filling may not, and which you have
is decided by whether "not yet" is a possible answer.*
**See.** [D-125](architecture-api.md), [D-095](architecture-api.md), [D-056](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-130 — where the retired NAMES are the addon's own, the refusal is built per owner ✅ (2026-08-05)
**Decision.** §2.10's retired-name table is static data shared by every sandbox, and `hafen.store` cannot use
it: the spellings that must throw are `hafen.store.cfg`, `hafen.store.layout` — one per saved variable the
*addon* declared. So `Section.mount` takes the callable table's `__index` as an argument, and `StoreApi` builds
one per owner off the manifest, falling through to `Retired.sectionIndex` for everything else.
**Rationale.** (2026-08-05, 039.10.) This is the only section in the whole migration whose *access pattern*
changed rather than its spelling — a declared field became a verb — so it is the only one whose retired names
are data rather than vocabulary. Leaving them out would have made the single densest silent failure in the
port: `hafen.store.cfg.foo = 1` is the line every persisting addon in the corpus wrote, and with the field gone
it reads `nil` and fails one character later as *attempt to index a nil value*, naming nothing. The
alternative — registering each addon's names into the static table at install — would have made a
process-global map grow with every reload and leak the previous session's addons into the next.
**Consequences.** One overload, and the two halves compose without a rule between them: the per-owner index
answers for what the manifest declares and delegates the rest, so `hafen.store.flush` still throws from the
static table and `hafen.store.anything_else` still reads plain `nil`. Generally: *a refusal keyed on data the
owner supplied is built where that data lives, not appended to the vocabulary everyone shares.*
**See.** [D-118](architecture-api.md), [D-129](architecture-api.md), [D-002](filesystem-build.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-131 — where the server PARTITIONS one set, one entity type covers it and the partition is a read ✅ (2026-08-05)
**Decision.** Known and buyable skills are one `Skill` type with `:known()`; acquired, available and the
pursued credo are one `Credo` type with `:acquired()` and `:pursuing()`. The collection lists one group and a
verb reaches the other (`:skill():available()`, `:credo():pursuing()`) — a distinguished sub-list is a verb on
the collection (§2.3), never a second type.
**Rationale.** (2026-08-05, 039.11.) The flat readers had a shape per group — `skills()` gave `{name,res}`,
`skillsAvailable()` gave `{name,res,cost}`, `credos()` gave two arrays plus a differently-shaped `pursuing`
table — so the same thing was three tables that could not be compared. Buying a skill or taking up a credo
moves it between groups *without making it a different skill*, and that is precisely the moment an addon
cares: a type per group would break `==` exactly there, and a handle stashed before the buy would go stale
for a change that is not a removal. The server agrees — it partitions with a `has` flag on the same record.
**Consequences.** `:known()`/`:acquired()` flip under a live handle and every other read keeps working;
`:cost()` is the number the server published for either group rather than a field only one shape had; and the
pursued credo compares equal to the same credo found in `:list()`, so its five progress reads are properties
of *that member* and answer `nil` on all the others. Generally: *a flag the engine keeps on one record is a
read on one entity — do not turn it into two types the caller has to reconcile.*
**See.** [D-094](architecture-api.md), [D-132](architecture-api.md), [D-056](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-132 — an entity with no id of its own is keyed by what it DERIVES from, and does not exist until that resolves ✅ (2026-08-05)
**Decision.** `Skill` and `Credo` intern on the server's own token (`nm`); `Experience`, which carries no
token at all, interns on its **resource name**, and a lore entry whose resource has not resolved yet is simply
not in `:list()` — it appears on a later read.
**Rationale.** (2026-08-05, 039.11.) D-094 says the intern key follows the engine's own stability, and here the
engine has none to offer: `SkillWnd` replaces `skg.csk.items`, `skg.nsk.items`, `credos.ccr`/`ncr` and
`exps.seen.items` **wholesale** on each `csk`/`nsk`/`ccr`/`ncr`/`exps` uimsg, so Java identity churns on data
the addon did not change and a stashed handle would die on every resend. The token survives every swap, which
makes it the key for two of the three. The third has only a resource, and the alternative to waiting for it —
minting on the record's identity while unresolved and re-keying later — would hand out two objects for one
piece of lore and make `==` mean *read in the same beat*.
**Consequences.** The whole tab is readable a beat after it builds, which is the same beat the rest of the
character sheet takes, so nothing new is asked of the caller; `exp:res()` always answers because it *is* the
key, while `:name()` (the resource's tooltip) may still be resolving. Generally: *a derived key is worth
waiting for — an entity that appears late is cheaper than an identity that changes.*
**See.** [D-094](architecture-api.md), [D-131](architecture-api.md), [D-063](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-133 — a second entity is worth minting only where it answers a question its target cannot ✅ (2026-08-05)
**Decision.** `hafen.fight():target()` hands back an **Opponent** (`:id() :gob() :exists() :info()`), not the
combat target's Gob directly — and the Opponent carries nothing beyond those four. In particular it has no
`:position()`, because that would be a second door onto `:gob():position()`; a party member keeps one because
a member's position is genuinely a different read.
**Rationale.** (2026-08-05, 039.12.) Handing back the Gob is the obvious shape and loses the one question the
Gob cannot answer: `gob:exists()` says *is this creature in the world*, and *are you still fighting it* is a
different fact with a different lifetime — a rabbit that runs out of view still exists and the fight may be
over, and the fight may end while the rabbit stands there. So the entity earns its place on `:exists()` alone.
That same test then decides what does **not** go on it: `opp:position()` would answer exactly what
`opp:gob():position()` answers, and two spellings for one read is D-013's dual style. `member:position()`
passes the test where `opp:position()` fails it — the roster keeps the **last-known** place, so it answers
after the gob has gone out of view and the gob's own read has stopped.
**Consequences.** The Opponent is four verbs and a page paragraph saying so: who, and the gob answers the
rest. No combat statistics reach the API, which is a boundary stated in the present tense rather than a gap.
Generally: *before minting an entity in front of one that already exists, name the question only the new one
can answer — and put nothing on it that the old one already answers.*
**See.** [D-013](architecture-api.md), [D-060](architecture-api.md), [D-094](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-134 — a member of a LAYOUT is keyed by its place, not by what is standing in it ✅ (2026-08-05)
**Decision.** A `DeckCard` interns on the deck **slot** (the raw 0-based index), not on the maneuver dealt
there. An emptied hotkey keeps answering `:slot()` and `:key()` — properties of the place — while
`:maneuver()`, `:res()`, `:name()` and `:used()` go `nil` together and `:exists()` goes false.
**Rationale.** (2026-08-05, 039.12.) D-094 asks what the engine keeps stable, and a layout has two candidates
that look alike: the slot and its occupant. `FightWnd.order[]` is a fixed-length array whose entries are
reassigned — loading another school rewrites every occupant while the slots stay exactly where they are, and
the slot index is also what the write path sends. Keying on the occupant would make "the maneuver on key 1" a
new object each time the school changed, which is the one moment a hotkey panel wants to re-read rather than
re-resolve. It also gives `:exists()` something true to say: *the slot is filled*.
**Consequences.** `hafen.fight():deck()` omits empty slots, so an empty card is reachable only through one you
were already holding — and it still tells you which hotkey it is, which is why the gap is never ambiguous. The
maneuver a card holds is reached with `card:maneuver()` and is the *same object* the maneuver collection hands
out, so the two projections of the window share their members instead of duplicating them. Generally: *when a
set is a layout, the place is the identity and the occupant is a read.*
**See.** [D-094](architecture-api.md), [D-057](architecture-api.md), [D-128](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-135 — a bag of scalars is an entity when it is a WINDOW'S fields, and a plain read when it is derived ✅ (2026-08-05)
**Decision.** `hafen.fight():summary()` is a **FightSummary entity** with `:exists()` and `:info()`, while
`hafen.study():summary()` stays a plain table. Its five verbs expand the engine's unreadable field names
(`:maxActions() :used() :deckSize() :saveCount() :activeSave()`) and `:info()` keeps the engine's spelling.
**Rationale.** (2026-08-05, 039.12.) The two summaries look like the same shape — a handful of numbers about a
window — and are not. Study's totals are **derived**: they are the sum over the slots, so there is nothing
behind them that can exist or stop existing, and an object would carry a lifetime it does not have. Fight's
five are **fields of a widget** created at login and replaced on relog, so `:exists()` is a real question and
a panel can hold the object across frames instead of re-reading a table every one. N1 then applies because the
names are a client programmer's, invisible to the player: `nact` and `usesave` are not the game's own words,
which is what D-061 protects — and `:info()` keeping them means nothing a reader had is lost.
**Consequences.** `sum:used()` is the same total the window paints and equals the sum of `man:used()` over the
collection, which is a cross-check a suite can make. Generally: *ask whether the numbers have a source that
can end; if they are computed from something else the caller can already reach, they are a value.*
**See.** [D-060](architecture-api.md), [D-061](architecture-api.md), [D-094](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-136 — a distinguished member reads NIL, and an inert entity would break every guard already written ✅ (2026-08-05)
**Decision.** `hafen.craft():current()` is **`nil` while no recipe is open**, and the guard stays on the caller:
`local c = hafen.craft():current(); if c then …`. It is not an inert Craft whose `:exists()` is false.
**Rationale.** (2026-08-05, 039.13.) The feature's own §2.2 already answers this — *a distinguished member*
(`:current()` `:selected()` `:leader()` `:available()`) *returns the entity, or nil* — and the whole feature is
built that way already (`hafen.fight():summary()`, `:target()`, `hafen.char():food()`). What settles it beyond
consistency is the measured cost of the alternative: `if hafen.craft.current() then` is the line every crafting
addon in the corpus already writes, and an entity that is always truthy turns each of those guards into one that
**passes and then reads nothing** — `c:name()` nil, `#c:inputs()` zero — which is the silent failure this grammar
exists to delete, introduced by the fix meant to prevent one. D-056's `hafen.kin(<unknown id>)` precedent does not
reach here: that entity is minted from a key the CALLER supplied, so there is something to be inert *about*; an
open recipe that is not open has no key at all.
**The premise the plan carried was false, and checking it made the decision smaller.** `spec.md` §4.4 and
`plan.md` §4.5 both say `hafen.craft.make()` *"was a plain no-op"* with no window. It was not: it threw
*"no crafting window open (open a recipe first)"*. So moving `make` onto the entity does not replace a silent
no-op with an index error — it replaces one guiding error with a less guiding one, and the retired-name row
carries the guidance instead (`hafen.craft.make` throws naming `hafen.craft():current():make(all)` **and** saying
that `:current()` is nil while no recipe is open).
**Consequences.** Every existing `if current() then` keeps its meaning across the port; a stashed Craft reports
`:exists() == false` once another recipe is opened, because a recipe is a fresh window rather than a change to
this one; and `c:make()` on such a handle refuses by name rather than crafting whatever is open now. Generally:
*before inventing an inert value for "not there", read what the callers already do with nil — a truthiness test
is a contract, and an always-truthy answer silently breaks it.*
**See.** [D-056](architecture-api.md), [D-060](architecture-api.md), [D-114](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-137 — a sub-record is an ENTITY where its PLACE outlives the array, and a value where the whole set is replaced with its owner ✅ (2026-08-05)
**Decision.** A quest's objectives are **Condition entities**, interned on the quest id plus the objective's own
description. A recipe's slots are **plain value tables** (`{res, name, num, opt}`), and so are its quality inputs
and its tools. Both are reached through a plural array read (§2.3's second half): `q:conditions()`, `c:inputs()`.
**Rationale.** (2026-08-05, 039.13.) Both look like "a small record inside a bigger one", and the engine tells
them apart. The `"conds"` message rebuilds the objective array but looks each entry up **by its description** and
carries the existing record over, mutating only its state — so *this objective of that quest* is precisely what
survives, its `:status()` flips from `"pending"` to `"done"` under a handle you are already holding, and watching
one objective is the reason to reach past the quest at all. The `"inpop"` message **destroys and rebuilds every
input widget**, even for a one-slot update, and a different recipe is a different window entirely; "input slot 2"
therefore means nothing across a change, there is no key to address one by, and there is nothing to ask about one
but its four fields. That is §2.8's *a table used as a VALUE is untouched*.
**Consequences.** The test is not "is it small" but *does a handle to it stay meaningful after the array is
replaced* — D-134's layout rule (keyed by its place) read from the other side. A Condition therefore carries
`:exists()`, which goes false when the player deselects the quest and true again when they open it, while a
CraftSpec carries nothing at all. Generally: *mint an entity where the engine itself carries a record across a
resend; where it throws the record away, so does the API.*
**See.** [D-094](architecture-api.md), [D-132](architecture-api.md), [D-134](architecture-api.md),
[D-135](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-138 — an entity addressed by a RE-USED id is keyed by the OBJECT, and its write resolves through the object ✅ (2026-08-05)
**Decision.** An **Item** is interned on the item widget itself, never on `:handle()` — the server widget id it
is addressed by on the wire. `hafen.act():item(item, verb)` takes the entity and resolves the item *through the
object it holds*; a widget id and a table carrying one are both **refused**, naming the Item.
**Rationale.** (2026-08-05, 039.14.) D-094 says the intern key follows the engine's own stability, and asks what
the engine does to the object. Here it does something no other subsystem does: it **hands the id back**. A
widget id is released by `UI.removeid` the moment the item is destroyed and the server is free to give the same
number to the next widget it creates — so a handle keyed on that number does not go stale, which is the failure
mode every other entity here has and can report. It **retargets**: it goes on resolving, to a different item,
and a gated write through it moves something the caller never named. That is not a worse version of "gone", it
is a different category of failure — silent, and destructive at exactly the tier the permission gate exists to
guard. Keyed on the item, the resolve can only answer *this item* or *nothing*.
**Consequences.** `:exists()` is a real question and the reads split by what they are: `:res()`, `:name()`,
`:num()`, `:wear()` and `:quality()` go on answering what the item **was** — which is what makes a stashed
`onItemRemoved` payload worth holding — while `:cell()`, `:slots()` and `:handle()` go empty, because *where it
is* is precisely what it no longer has. The escape hatch keeps working and stays honest: `hafen.act():raw` still
takes a number, and the documented spelling reads it off the item at the moment it is sent
(`item:handle()`), rather than stashing one. Measured end to end headlessly — id re-used by another item, the
stale handle still naming the old one, the write refused, the new occupant untouched — and **not** assertable
from a suite, which meets the permission gate first. Generally: *before keying an entity on an id, ask not only
whether the id is stable but whether it is RE-ISSUED; a stale reference can be reported, a recycled one cannot.*
**See.** [D-022](architecture-api.md), [D-063](architecture-api.md), [D-094](architecture-api.md),
[D-132](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-139 — a container hands each item ONCE, and where it sits is a property of the item ✅ (2026-08-05)
**Decision.** `widget:items()` de-duplicates on the item, so a worn item filling several equipment slots is one
entry. Where it sits is **two verbs, not one shape**: `item:cell()` is the `{x, y}` grid cell and `item:slots()`
names the equipment slots it fills. The old snapshot's single `pos` field (a table in a backpack, a string in
the equipment window) and its numeric `slot` are retired, naming both.
**Rationale.** (2026-08-05, 039.14.) An `Equipory` draws one item once per slot it occupies, so the pre-entity
list held two entries for one thing — harmless while they were copies and a lie the moment they are interned,
since `#items` would count two of something you own one of and `==` would be true across them. And a field whose
type depends on which container answered is D-093's split-the-argument-by-shape heuristic in the return
position: two verbs say which you meant, so no rule about types survives.
**Consequences.** The container lifecycle follows: `:onItemAdded`/`:onItemRemoved` diff the **items**, not the
cells that draw them, so a two-slot item is one add. **The corollary was found in-game and it is the part worth
remembering**: a set that must not under-report has to be able to name every member, and the equipment window
publishes a display name for all but one of its places — so naming only what it names dropped a worn item's
place entirely, and `:slots()` read empty, which is what *not worn* reads. An unnamed slot falls back to the
engine's own identifier for it. Generally: *when a read means "where is this", completeness is the contract —
a place the client cannot NAME is still a place, and omitting it makes the empty answer ambiguous.*
**See.** [D-013](architecture-api.md), [D-093](architecture-api.md), [D-138](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-140 — a value only PUBLISHED CODE knows is read by class NAME, not by adopting the class ✅ (2026-08-05)
**Decision.** `item:quality()` ships, and it is read by walking the item's tooltip info for a class *named*
`ui/tt/q/qbuff`'s, then reading its `q` field reflectively — no `get-code` copy, no `@FromResource` version pin.
**Rationale.** (2026-08-05, 039.14.) `learnings/client-limits.md` recorded typed quality as something the client
does not have, and that is true of `haven`: there is no `Quality` type to compare against, because the number
lives in a class that ships **inside a resource**. Two routes reach it. Adopting the class (the `ui/obj/buddy`
recipe) makes it typed and fast, and pins a version: when the server ships a revision the pin stops matching,
the resource's own class is loaded instead — a different `Class` with the same name — and every read answers
`null` for every item, *silently*. Reading by name cannot be revised out of correctness; it can only stop
finding the field, which is what `nil` already means everywhere in this API. `gob:kin()` makes the same trade in
its fallback, for the same reason.
**Consequences.** The learning is amended rather than left to mislead: what the client cannot give is a quality
**type**, not the quality. The rule generalises past this field — *ask what a wrong answer costs before choosing
between a pinned adoption and a named lookup: adoption buys speed and types and pays in a failure mode that is
invisible; a named lookup pays a reflective read and fails to nil.* Adoption stays right where the code must be
**changed** (the F3d font override), which is a thing a name lookup cannot do at all.
**See.** [D-043](fonts.md), [D-061](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md).

### D-141 — A set whose KIND has no name refuses a string filter; a MEMBER whose name has not arrived yet is skipped ✅
**Decision.** The shared filter (`nil` / string substring / predicate) meets two different absences of text and
must answer them differently. `LuaCollection.Source.named()` declares the **kind**: `false` — the default —
makes a string filter a refusal naming the forms that work ([D-115](architecture-api.md)), because a buff bar,
a party member, a segment and the grid database have no name and matching nothing would be a lie.
`Source.needle(member)` stays per **member**, and `null` there now means *this one's name has not arrived yet*:
that member simply does not match, and the call that contains it does not throw.
**Rationale.** (2026-08-05, 039.15.) One `null` meant both, so `hafen.world():gob():count("terobjs/tree")` —
`getting-started.md` step 6, the first addon anyone writes — raised as soon as one loaded gob's `Drawable` was
still resolving, while `:nearest("terobjs/tree")` one verb away skipped it, because it runs the older
`AddonManager.gobMatches`. Two filter paths meaning different things is exactly what `keeps`'s own contract
forbids, and *"not yet" is not "no"* is already this API's rule for a read
([D-095](architecture-api.md)) — a read that answers `nil` must not make its caller throw.
**Consequences.** The default is the **refusing** one on purpose: a source that supplies a needle and forgets
`named()` fails loudly on the next string filter rather than quietly matching nothing. The pair is reconciled
by count (26 `needle()` ↔ 26 `named()`); the 6 sources with neither keep D-115's refusal untouched. The rule
generalises past filters — *when one sentinel answers two questions, the one it answers wrongly is the one
nobody wrote a test for*: this shipped in 039.2 and survived thirteen tasks, because no addon and no suite used
a string filter on the gob collection (`hello`, `tagger` and `walker` all reach for `:nearest`/`:within`), and
because the failure needed an unresolved gob in view at that instant. No page was wrong and no spelling
changed: every page already described the behaviour `:nearest` had.
**See.** [D-095](architecture-api.md), [D-115](architecture-api.md), [D-128](architecture-api.md),
[039-uniform-api](../039-uniform-api/spec.md).

### D-142 — The manifest `description` is the AddOns panel's page, not the addon's changelog ✅
**Decision.** Every example addon's `manifest.json` `description` becomes **one accurate present-tense
paragraph** saying what that addon demonstrates, matching its entry on the published examples page. The
accreted version log each one had been carrying is deleted rather than corrected.
**Rationale.** (2026-08-05, 039.16.) `AddonPanel` renders `description` as the row's **tooltip** — the one
thing a player reads before deciding to enable an addon, and for a network addon the same tooltip that lists
the hosts it may reach. What was in it was a running changelog of every slice since V1, still teaching
`hafen.ghost.new{res, x, y}` and the `:move`/`:pos`/`:setRes` handle verbs this feature retired: `hello`'s ran
to **26,086 characters**, `theme`'s to 3,532. Every task of this feature left them alone while its own rows
were in flight, which is right; at the close that stops being deferral and becomes a decision to ship a
tooltip that is both unreadable and wrong. The same field already has a **measured** hazard on that path —
`AddonPanel` wraps it deliberately, because an unwrapped long description becomes a texture wider than
`GL_MAX_TEXTURE_SIZE` and the GL upload kills the render thread on hover — so length here was never free.
**Consequences.** Twelve manifests rewritten and version-bumped, ~48,000 characters of accreted log deleted:
the diff is the record, which is where a version history belongs. Frozen `hello` is edited under the one rule
that allows it (this feature genuinely broke it), and the edit **shrinks** it. The rule generalises to any
metadata field a later feature adds: **a field that carries prose is on the docs sweep**, and the test is
whether a reader could believe it — which here is not hypothetical, since the client draws it. The finding
that made the decision is worth keeping too: the first reading of the code said nothing rendered it, because
the grep stopped at `src/io/brodgar/addon/*.java` and the panel lives in `.../addon/ui/`.
**See.** [D-013](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md),
[TESTING.md](../TESTING.md) (frozen `hello`, and the one case an edit is allowed for).

### D-143 — R4 governs a BUILDER, so a draw call's trailing table is per-call SCOPE and stays ✅
**Decision.** `g:text(str, x, y, opts)` and `g:atext(str, x, y, ax, ay, opts)` keep their trailing
`{font = h, color = {r, g, b, a}}`. It is the only named-argument table left in the API, and it is not an R4
violation: R4 is about a **builder**, and a draw call builds nothing.
**Rationale.** (2026-08-05, 039.16.) The feature's own spec flagged this as open because it looked like the
eight constructors beside it, and it is not one. Two things separate it. There is **no object to hang setters
on** — a `g` is handed to one callback for one frame, so `g:font(h)` would be context state that outlives the
call it was written for, and every caller would have to unset it: the config table with extra steps and a leak.
And both keys are exactly **per-call scope of state the context already carries**: `opts.color` composes with
`g:color` as a `g:color` around the call would, `opts.font` overrides the widget's default for this call only.
So `g:color` is not a second door onto one property (D-013's dual style) but the *unscoped* form of the same
one — which is why both can exist without the API saying the same thing twice.
**Consequences.** The only alternative that was live — two optional positional trailing values — is strictly
worse at the call site: unnamed, order-dependent, and no more typeable than the table. The rule generalises:
**where a table's keys scope ONE call of a per-frame value, it is data, not named arguments** (§2.8's line,
read from the other side), and the test is whether there is something that outlives the call to configure. It
also closes the last of the six questions the feature opened, so `API.md`'s inventory has no orphaned row.
**See.** [D-013](architecture-api.md), [D-107](architecture-api.md) (what a config table costs when there IS
an object), [039-uniform-api](../039-uniform-api/spec.md) §5.5, [§2.8](../039-uniform-api/spec.md).

### D-167 — one `Subs` per emitter, and the profiling category is DATA on it, never inferred ✅
**Decision.** Every notification in the area is delivered by one class, [`Subs`](src/io/brodgar/addon/Subs.java):
a keyed multimap `key → handlers` with `fire`, an OR-accumulated cancel and the `has(key)` gate. Who owns one
decides the address an addon writes — the bus owns one per `Addon`, a widget one per (addon, widget), a grab
one per grab. The `Addon.CATS` split each key charges is **carried on the `Subs`** (`Subs.Cats`, per key), not
chosen inside `fire`.
**Rationale.** (2026-08-06, 041.1.) The mechanism is the easy half; the category is the half that would have
broken silently. Before 041 the five columns of `p:addons()` were distinguished by *which mechanism*
dispatched — `onDraw` → `draw`, the eight widget callbacks → `widgets`, `hafen.hook()` → `hooks`, the bus →
`events`. After 041 every one of them is a `Subs.fire`, so a single charge inside `fire` would collapse the
split to one column and the profiler would go on reporting confidently. Making it per-key data is also what
the emitters actually need: one widget's `Subs` carries `Draw` (`draw`) and `MouseDown` (`widgets`) at once.
**Consequences.** `AddonManager.Sub` is deleted rather than left beside `Subs` — one character apart in one
package is a reading hazard for every later session — and [`LuaSub`](src/io/brodgar/addon/LuaSub.java) is both
the Lua handle and the list entry, which makes `:off()` idempotent **by construction** (removal by identity
from a copy-on-write list) instead of by a flag. `fireTo` stops walking an addon's whole subscription list per
event and looks up one key. Each later task inherits the category question already answered: it declares what
its keys cost, and cannot accidentally flatten the split.
**See.** [D-052](architecture-api.md) (the five categories), [D-100](architecture-api.md) (the state belongs on
the THING), [041-unified-events](../041-unified-events/plan.md) (*Profiling attribution*).

### D-168 — a retired event KEY is an argument, so it is refused at the door, in its own table ✅
**Decision.** `Retired` gains a second table for retired **event keys**
([`Retired.eventKey`](src/io/brodgar/addon/Retired.java)), keyed `"<emitter>|<key>"`. An emitter's `:on`
consults it **before** it checks its own vocabulary, so `hafen.event():on("OnLoad", fn)` throws *'OnLoad' is
now 'Load'* rather than falling into the generic unknown-key refusal.
**Rationale.** (2026-08-06, 041.1.) Every retired spelling until now was a **name** — a field read — so the
refusal could hang off an `__index` and fire at the line that wrote it. A key is a **string argument**: there
is nothing to index and no metamethod to attach, so the only place the refusal can live is the call that
accepts it. The order matters as much as the table: with the vocabulary checked first, all four moved
lifecycle keys report as "unknown event", which is true and useless.
**Consequences.** The `Retired` mechanism now has three kinds (section, verb, key) and its class comment says
so. 041.4's sixteen widget verbs are the *name* kind and need no new machinery; 041.5's `hafen.hook` likewise.
The generalisation for any later hard cut: **ask what the old spelling grammatically IS** — a name can be
refused where it is read, a value can only be refused where it is accepted.
**See.** [D-125](architecture-api.md), [D-129](architecture-api.md), [039-uniform-api](../039-uniform-api/spec.md)
§2.10 (the `Retired` table), [041-unified-events](../041-unified-events/spec.md) §R5.

### D-169 — a closed vocabulary LISTS what it answers when the list is short, and POINTS when it is not ✅
**Decision.** A closed key set's refusal names the offending key and then either **lists** the keys that
emitter does answer, or **points** at the catalogue page — by the length of the list. A widget answers five or
six keys and lists them; the bus answers 26 and says *see `docs/addons/api/event.md` for the catalogue*.
**Rationale.** (2026-08-06, 041.1.) The feature's spec (§R5) says *listing the keys* and its `EXAMPLES.md` §4
writes the bus's message as a pointer; both are right for their own emitter and the rule is what reconciles
them. A refusal is read in the chat log, one line wide: a list of five is the answer, a list of 26 is a wall
the reader skips, and it would be re-emitted on every mistyped key.
**Consequences.** 041.4 lists (a widget's keys), 041.1 points (the bus's). The threshold is not a number to
remember but the same question every message answers — *does the reader learn more from the list or from
where the list lives*. It also keeps the shipped error text and the shipped docs pointing at each other, which
is what makes the docs tier the contract rather than a copy.
**See.** [D-125](architecture-api.md) (a closed vocabulary throws naming what exists),
[041-unified-events](../041-unified-events/EXAMPLES.md) §4 (the exact messages).

### D-170 — a retired SECTION VERB is checked at the section's own `__index`, not left to the generic refusal ✅
**Decision.** [`Section.meta`](src/io/brodgar/addon/Section.java)'s `__index` consults
[`Retired.message`](src/io/brodgar/addon/Retired.java) (keyed `"hafen.<section>():<verb>"`) **before** it falls
back to the generic *"has no verb"* error. `hafen.hook():action(msg, fn)` now throws naming
`hafen.event():action():on(msg, fn)` rather than the section merely saying it has no such verb.
**Rationale.** (2026-08-06, 041.2.) `hafen.hook():action`/`:message` are the first colon verbs the uniform
grammar (039) ever *removed* from a section that keeps existing — every prior retirement either killed a whole
section or renamed a dotted pre-039 field, both of which `Retired`'s existing two kinds already covered. A verb
gone from a still-live section had no door to hang the message on until this one.
**Consequences.** The dotted pre-039 form (`hafen.hook.action`) and the colon form
(`hafen.hook():action`) are two separate `Retired` rows for one move, because they fail at two different
metamethods (`hafen`'s own `__index` vs. the section's). A later feature that moves a verb OFF a section that
survives follows the same two-row pattern; one that deletes the section outright still only needs the one
`Retired.put("hafen.<section>", …)` row 039 already established.
**See.** [D-168](architecture-api.md) (the sibling case for an event KEY), [039-uniform-api](../039-uniform-api/spec.md)
§2.10 (the `Retired` table), [041-unified-events](../041-unified-events/spec.md) §R2.

### D-171 — a Lua argument table is sized by its HIGHEST INDEX, never by `#t`, once a `nil` can be a real argument ✅
**Decision.** [`LuaMarshal.luaToArgs`](src/io/brodgar/addon/LuaMarshal.java) sizes the `Object[]` it builds by
scanning the table's keys for the largest positive integer present, not by Lua's `#` operator — capped at 256
(a stray high index is refused as a likely typo, not allocated).
**Rationale.** (2026-08-06, 041.2, found in-game.) A chat `"msg"` uimsg arrives as `(nil, line)` — a **null
sender is how the client marks the player's own line** — and a `nil` inside a Lua table is a hole: `#t` is free
to read 0 there. `ev:rewrite(ev:args())`, the identity round trip a handler writes without thinking twice about,
silently applied an EMPTY argument list on exactly that message. `#t` had been adequate for every argument table
built *by hand* in the corpus so far (039's), where nobody writes a leading `nil` on purpose; the first table
built from *live server data* broke it on the very first login.
**Consequences.** A *leading or middle* `nil` now survives `ev:send`/`ev:rewrite`. A *trailing* `nil` still
cannot be expressed — `{x, nil}` and `{x}` are the same table in Lua, which is a property of the language, not a
gap in this fix — and is documented as such rather than routed around. The general lesson: **never size a
Lua→Java argument array by `#t` once the table can hold real data instead of only what an addon typed in** —
`t:length()` is a convenience for the hand-written case, not a substitute for scanning when the table's origin
is a live protocol message.
**See.** [041-unified-events](../041-unified-events/spec.md) §2.3 (the payload marshalling this rides on),
`specs/codebase/services.md` (`ChatUI`'s uimsg arg shapes, added this task).

### D-172 — an OWN widget's input rides the same `Widget.listen` pre-hook a NATIVE one does, not a second mechanism ✅
**Decision.** [`AddonWidget`](src/io/brodgar/addon/AddonWidget.java) drops its `mousedown`/`mouseup`/
`mousemove`/`mousewheel` overrides (and the `onClick`/`onMouseUp`/`onMouseMove`/`onWheel` slots they read)
entirely. `widget:on("MouseDown"/…, fn)` on a widget you *built* now installs the exact same
[`WidgetSubs`](src/io/brodgar/addon/WidgetSubs.java) engine listener a widget you merely *found* gets —
`Widget.listen`/`deafen`, not a Java-side dispatch loop over a per-slot callback array.
**Rationale.** (2026-08-06, 041.3.) The spec's own claim is *"the same keys work on an own widget with one
vocabulary"* — two dispatch paths converging on one Lua verb would still be two mechanisms behind a single
name, which is the veneer 039's spec explicitly warns against (uniform syntax over non-uniform machinery). An
`AddonWidget`'s own `mousedown` override was itself only ever a hand-rolled special case of what
`Widget.listen` already does generically (a pre-hook that may short-circuit the widget's default) — so
deleting it is not a workaround, it is recognising that the general mechanism already covered the specific one.
**Consequences.** `AddonWidget.CALLBACKS` shrinks from eight slots to four (`onDraw`/`onTick`/`onDrop`/
`onClose` — 041.4's own four keys, untouched here); D-040's per-callback `mods` table goes with the slots it
rode on, since the unified `ev` for `MouseDown`/`MouseUp`/`MouseMove`/`Wheel` carries none (EXAMPLES §1.1) —
an addon that needs modifier state at press time has nothing to reach for until the mouse entity (041.5) puts
`:shift()`/`:ctrl()`/`:alt()` somewhere reachable at any time instead. `Subs` gained one new, opt-in piece to
make the unification pay for itself: `Subs.Idle`, notified when a key's last live handler is gone, is what
lets a widget's `Subs` deafen the ONE engine listener it installed per event class rather than leaving it
firing into an empty handler list for the widget's remaining lifetime.
**See.** [041-unified-events](../041-unified-events/spec.md) §R2 (¿tienes el objeto? `obj:on(...)`),
[design/25-uniform-api.md](../design/25-uniform-api.md) (uniform syntax over non-uniform returns is a veneer).

### D-173 — a section with nothing left in it is deleted whole, not kept around as an empty shell ✅ (2026-08-06, 041.5)
**Decision.** `hafen.hook()` is removed as a Lua-reachable name entirely once its last verb (`:grab`) moves
elsewhere — [`HookApi`](src/io/brodgar/addon/HookApi.java) keeps only slash commands and the keybinding
registry, mounted directly under `hafen.slash`/`hafen.client:options():keybindings()`, and reading bare
`hafen.hook` throws at the `hafen` table's own `__index` (one [`Retired`](src/io/brodgar/addon/Retired.java)
row, `"hafen.hook"`) before any dotted or colon sub-spelling is ever reached.
**Rationale.** By 041.5 `hafen.hook()` had exactly one verb left (`:grab`) — input left in 041.3, action/
message in 041.2. A section object that exists only to hold one verb is a compound name (`hafen.hook():grab`)
standing in for what could be a direct one (`hafen.ui():mouse():grab()`), and keeping the shell around "in
case something else needs it later" is exactly the kind of speculative surface [D-011](#d-011)/no-backward-
compat guidance rejects — nothing else was ever going to need it, because everything that could have lived
under `hafen.hook()` already had a better address once §R2 (¿tienes el objeto?) was applied to it.
**Consequences.** One `Retired` row covers `hafen.hook`, `hafen.hook()`, and every `:input`/`:action`/
`:message`/`:grab` attempt through it — they all die at the same section-level read, so there is no separate
message to maintain per retired verb once the section itself is gone. A later feature that empties a section
down to one verb should ask the same question — does the verb belong on the section, or does the section now
exist only to hold it? — rather than assuming a section, once created, is permanent.
**See.** [D-170](#d-170) (a verb retired off a surviving section), [041-unified-events](../041-unified-events/plan.md)
*Discarded alternatives* ("keeping `hafen.hook()` alive for grab alone").

### D-174 — a widget's closed vocabulary is a property of WHAT IT IS, computed fresh, not a fixed catalogue ✅ (2026-08-06, 041.4)
**Decision.** [`LuaWidget.widgetKeys`](src/io/brodgar/addon/LuaWidget.java) computes the set of keys a widget
answers to `:on(key, fn)` **per call**, from what that widget concretely is — a control capability first (at
most one of five: `Press`/`Change`/`Submit`/`Select`/`OnCell`), the five universal keys every widget has, the
item pair on anything that is not one of the sixteen control adapters, the surface four
(`Draw`/`Tick`/`Drop`/`Close`) on an owned `AddonWidget` alone — rather than a static map keyed by class or a
single closed list shared by every widget.
**Rationale.** The keys a `Button` answers and the keys a `Label` answers are genuinely different closed sets
(D-125 says closed sets throw listing what they DO answer), and the set is not fixed at compile time in any
one place — it is the union of independent, orthogonal facts about the widget (is it a control? which
capability? is it a container? is it this addon's own surface?). A hand-maintained per-class table would be
a second place, beside the `instanceof` chains that already answer each of those questions, that a later
control or capability could forget to update.
**Consequences.** Adding a sixteenth control or a new capability costs one more `instanceof` branch in
`widgetKeys`, never a new table entry to remember elsewhere; a widget's answered vocabulary is provably
consistent with what dispatch actually does, because both read the same facts. The refusal a mismatched key
throws is therefore always accurate for THAT widget, not a generic list that happens to be true most of the
time.
**See.** [D-125](#d-125) (a closed vocabulary throws naming what it answers), [D-172](#d-172) (own input rides
the native door), [041-unified-events](../041-unified-events/EXAMPLES.md) §1.1-§1.4.

### D-175 — a notification CAPABILITY is a pure marker once N-subscriber `Subs` carries every fire ✅ (2026-08-06, 041.4)
**Decision.** [`Controls.Press`/`Change`/`Submit`/`Select`/`OnCell`](src/io/brodgar/addon/Controls.java) —
the marker interfaces that say *"this control fires `Pressed`"* etc. — carry no state and no callback slot
any more. Before 041.4 each also held the single installed Lua handler and called it directly; now every fire
is `Controls.fire`, which looks up the widget's own `Subs` (`WidgetSubs`, without minting one if nobody is
listening) and lets it dispatch to however many handlers are actually subscribed.
**Rationale.** The old shape mixed two concerns in one interface — "does this control fire this
notification at all" (a fact worth asking, e.g. from `widgetKeys`) and "here is the ONE handler for it" (the
single-slot cardinality R1 retires everywhere else). Once N subscribers is the rule for every key in the
area, the second half has nowhere left to live except the widget's own `Subs` — the same door every other
key already goes through — so the marker collapses to the answer to the first question alone.
**Consequences.** `Controls.image`/`checkImage`/`rowHeight`/`cell` — every rebuild-carry site that used to
copy a stored `onPress`/`onChange`/`onSelect`/`onCell` handler across a rebuild — drop those lines outright:
a `:on()` subscription cannot exist yet at rebuild time regardless (041.3's own rule, a subscription is
registered in its own statement after the builder chain finishes), so there was never anything for the
marker to carry. A future control capability is a marker from the start, not a slot that gets hollowed out
later.
**See.** [041-unified-events](../041-unified-events/spec.md) §R1 (N subscribers everywhere), [D-172](#d-172).

### D-176 — a widget's Lua identity is keyed on its ROOT, not the object that happens to draw ✅ (2026-08-06, 041.4)
**Decision.** [`AddonWidget`](src/io/brodgar/addon/AddonWidget.java)'s four fire sites (`Draw`/`Tick`/`Drop`/
`Close`) key their `Subs` lookup on `rootw()` — the widget `hafen.ui():window()`/`:widget()` actually interned
the Lua handle on — never on `this`.
**Rationale.** (Found by 041.4's own `[manual]` round, not the automated suite.) `hafen.ui():window()` interns
its Lua handle on the CHROME, one level above the content `AddonWidget` that actually draws and ticks; a bare
`hafen.ui():widget()` has no such split, so `this == rootw()` there and the bug was invisible until a
`:window()`-built widget was tested. Keying the fire sites on `this` silently missed every subscription a
`window()`-built widget's handle had ever registered, because the `Subs` a `:window()`'s `widget:on(...)` call
installs lives on the CHROME's `WidgetSubs`, not the content's.
**Consequences.** `learnings/hooks-hotkeys.md`'s general lesson from this: pick an automated-coverage case
that does NOT make two different objects happen to be equal (the bare-widget case coincidentally has
`this == rootw()`, which is exactly why the suite's own automated checks did not catch it — only the
`[manual]` Draw-demo round, built against a `hafen.ui():window()`, did). A widget entity with two levels
(chrome vs. content) must always be tested at BOTH, not just the simpler one that happens to collapse them.
**See.** [D-064](#d-064) (the intern cache's own two-axis shape), [041-unified-events](../041-unified-events/plan.md)
*Risks & gotchas*.

### D-177 — a payload answering several MUTUALLY EXCLUSIVE nouns is one shape, and the nouns that don't apply read nil ✅ (2026-08-06, 041.7)
**Decision.** [`LuaEvent.Shape.CLICKED`](src/io/brodgar/addon/LuaEvent.java) is ONE shape for
`GhostClicked`/`SpriteClicked`/`ObjectClicked` alike, not three. Its methods table answers `:ghost()`,
`:sprite()` and `:object()` on every CLICKED event; only the one matching
[`clickKey()`](src/io/brodgar/addon/LuaWorldEntity.java) (the entity that was actually clicked) returns the
handle, and the other two return `nil` rather than throwing.
**Rationale.** D-125's closed-vocabulary rule says an unlisted VERB throws — but `:sprite()` on a
`GhostClicked` is not an unlisted verb, it is a listed one whose DATA does not apply this time, the same
distinction `Shape.INPUT` already draws for `:button()` (down/up only) and `:amount()` (wheel only), each
`nil` where the concrete gesture does not carry them (EXAMPLES.md §1.1). Three near-identical shapes differing
only in which one noun is populated would be the "one `LuaEvent` subclass per shape" alternative the feature
already rejected for the SAME reason (spec's *Discarded alternatives*): more places to keep in sync for no
behavioural difference a caller could tell apart from the single-shape answer.
**Consequences.** A handler that does not know which of the three events it is inside (a shared listener
registered on all three bus keys) can safely try all three noun-verbs and use whichever answers non-nil,
without a type check first — the same pattern `ev:button()`/`:amount()` already established for input.
**See.** [D-125](#d-125), [041-unified-events](../041-unified-events/EXAMPLES.md) §2 (the event object),
[041-unified-events](../041-unified-events/plan.md) *Discarded alternatives* ("one `LuaEvent` subclass per
shape").

### D-178 — a change is announced at the MOMENT it happens, and the seam goes where the write LANDS, not where the message arrived ✅ (2026-08-06, 042.1)
**Decision.** The addon layer's per-frame poll stage (`AddonManager.tick`'s `pollTreeAdapters` and its
eleven-site inventory) is replaced task by task with taps at the client's own four moments of change: the
server pushes a value (`onUimsg`), a widget enters/leaves the tree (`onWidgetPlaced`/`onWidgetRemoved`),
geometry changes (`Widget.resize`), and a still-loading value resolves (`Waitable`). Where the message that
announces a change and the write that actually makes it happen are two different moments — the belt's
deferred `glob.loader.defer(...)` paths are the sharpest case — the seam goes at the WRITE, not the message.
**Rationale.** A per-frame diff costs the same whether anything changed or not, and it costs it whether or
not any addon is loaded (`AddonManager.init` wires the adapters and the tick pump unconditionally). The
client already publishes every moment this feature needs; the poll stage was re-deriving what it could have
been told. **Supersedes** the standing rule in `learnings/widget-tree-reads.md` ("if a widget mutation is
`loader.defer`-red, use `poll()`, not the uimsg tap") — the corrected rule is *put the notify where the write
lands*, which for the belt means inside the `loader.defer` lambda, immediately after the assignment.
**Consequences.** Cost becomes proportional to *changes*, not *frames*: an idle client with a listening
addon pays nothing between real changes, and a listening client stops paying a 144-slot diff every frame for
a belt that changed twice this session. Gating each poll on `hasSub` (the cheaper-seeming alternative) was
rejected because it only helps the idle case — the *listening* client, the one this system exists for, would
still pay the diff every frame.
**See.** [042-event-driven-reads](../042-event-driven-reads/spec.md), [042-event-driven-reads](../042-event-driven-reads/plan.md).

### D-182 — a value that is still loading is WAITED ON, not re-read; a build with no queue of its own is retried ONCE on the notify that its source landed ✅ (2026-08-06, 042.1)
**Decision.** `Resolve` (`src/io/brodgar/addon/Resolve.java`) wraps `Loading`'s `Waitable.waitfor` with
retry-on-notify (a retry that itself throws a *different* `Loading` re-registers on the new one, bounded —
past that the value stays unresolved until something else announces it, never a fallback poll), marshalling
onto the UI-thread tick (`wnotify()` runs on whichever thread finished the load), and per-`Addon` ownership
(every `Waiting` lives in the owning addon's resource registry, cancelled on `:reload`/disable, P2). A bare
`new Loading(...)` throws `UnwaitableEvent` — reported to the caller as a refusal, never chased by a hidden
retry.
**Rationale.** `Loading implements Waitable`, so the client already answers "tell me when this resolved";
nothing in `io.brodgar` used it before this task. The alternative — re-reading a `Loading`-guarded value
every tick until it resolves — is exactly the per-frame poll shape D-178 exists to delete, just moved one
layer down.
**Consequences.** `Resolve` ships in 042.1 as pure infrastructure: every read `MeterAdapter` needed was
already `Loading`-guarded to `nil` with no retry required (D-092's stated boundary — see 027.2's finding that
`:res()` may legitimately be `nil` at fire time and must not be delayed to "fix"), so this task gives
`Resolve` no functional consumer. First real consumer lands with a task that has an actual bounded retry to
make (an item's derived `info()`, a world entity waiting on its ground to stream in).
**See.** [D-092](process.md), [042-event-driven-reads](../042-event-driven-reads/plan.md) §M2.

### D-184 — a section is named for WHOSE the thing is, not for the mechanism that draws it — so two sections that differ only by mechanism collapse into one ✅ (maintainer, 2026-08-08, 043.1)
**Decision.** `hafen.ghost()` (client-only `.res` props) and `hafen.render()` (`:sprite()`/`:object()` — the
addon's own PNGs and glTF models) are **deleted into one section, `hafen.vr()`**, whose verbs are its
collections: `hafen.vr():ghost()`, `:sprite()`, `:object()`. Both old spellings are `Retired` **section** rows
naming it (`src/io/brodgar/addon/Retired.java`), so the refusal fires on the field read and beats every dotted
or colon sub-spelling that used to hang off them. The kinds are **registered** through one helper in
`VrApi.installVr` rather than branched on, so a fourth (`:widget()`, 044) is one line.
**Rationale.** `hafen.render` was named after a *mechanism*, while the other places a drawn thing can live are
named after the *place* — the ROADMAP's own standing complaint. But the axis that actually separates these from
the gobs in `hafen.world()` is not **where** they are (both are in the 3D world) but **whose** they are: nothing
under `hafen.vr()` ever reaches the server. That is why the ROADMAP's proposed `hafen.world():sprite()` was
rejected — it would make your fake props siblings of real gobs. And once the section is named for ownership, the
split between "a `.res` the game shipped" and "a PNG you shipped" is a difference of *asset source*, not of
kind: the same `LuaWorldEntity` core, the same handle vocabulary, the same teardown. Two sections for one thing
is the shape D-103 already refuses one level down.
**Consequences.** `RenderApi.java` is renamed `VrApi.java` — the retired names are pure data in `Retired`, so no
thin raiser file is needed and one class keeps owning the world-entity core. `GhostGob`/`SpriteQuad`/
`MeshSprite` do **not** move: they are engine-side visuals, and only the Lua-facing section changed. Nothing
renders differently — the whole feature is a relocation, which is the property every `:t043-*` suite checks.
This supersedes the **namespace half** of [D-034](rendering.md#d-034) (its loader half was already cut by 028.1
/ [D-013](#d-013)); [D-029](virtual-entities.md#d-029) and [D-034](rendering.md#d-034)'s SAFE-tier ruling is
untouched and now reads as one rule for the whole section.
**See.** [043-vr-namespace](../043-vr-namespace/spec.md), [D-103](#d-103), [D-034](rendering.md#d-034),
[044-spatial-ui](../044-spatial-ui/spec.md) (the fourth collection).

### D-185 — where a derived thing has no record on its SOURCE, index it by the source's key — an index written at create/destroy is not a sweep ✅ (2026-08-08)
**Decision.** A `hafen.vr()` entity anchored to a gob by `:add(what, gob)` — one an addon placed freely, not
one a `gob:overlay()` record owns — is destroyed from a `Map<Long, List<LuaWorldEntity>>` in `VrApi`, keyed by
the target's gob id, looked up by `anchorGone(id)` on the same `GobRemoved` drain that already runs
`LuaGobOverlay.gobGone`. The two mechanisms stay disjoint: an overlay's entity is never in the index.
**Rationale.** (2026-08-08, 043.2.) [D-102](#d-102) says the end of a derived thing rides the event its source
already raises, and it could say "and needs no list of anything" because [D-100](#d-100) had put the store **on
the gob** — the removal arrives holding the exact records. Making the anchor an argument breaks that premise
without touching the principle: an entity `hafen.vr():sprite():add(img, gob)` placed has no record on the gob to
be handed. The alternatives were a walk of every addon's entity lists on every gob removal (gobs leave the cache
constantly while you move — O(entities) per removal, for a case that is almost always empty) or a `GAttrib` on
the gob purely to hold a back-pointer, which is D-100's store rebuilt for one field. **The distinction that
matters is not "index vs no index" but WHEN it is touched**: this one is written only when an anchored entity is
created or destroyed and read only when a gob leaves `OCache`, so there is no per-frame work and nothing walks
it to *find* anything — which is the shape D-100 deleted. Cleared in `AddonManager.init()` beside the other
session queues, because a gob id means a different gob next login.
**Consequences.** `asOverlay` stops being inferred from `tgt != 0` and becomes an explicit `make*` parameter —
anchored and overlay-owned are now two different things, which is exactly what "the anchor is an argument"
means. The proof is the recorded event, not a later snapshot: walking back makes the server re-send the same
object under the same id (`learnings/testing-tooling.md`). Generally: *a principle that says "ride the event"
survives the loss of its O(1) store; what it forbids is the sweep, not the map.*
**See.** [D-102](#d-102), [D-100](#d-100), [D-103](#d-103),
[043-vr-namespace](../043-vr-namespace/spec.md).

### D-186 — a write the engine would silently undo is REFUSED, naming the verb that does work ✅ (2026-08-08)
**Decision.** `:position(p)` on a `hafen.vr()` entity that follows a gob raises, naming `:rotate(a)` (which
still writes) and `hafen.vr():<kind>():add(what, p)` (which places one that stands still). The **read**
`:position()` is untouched and answers the gob's live point.
**Rationale.** (2026-08-08, 043.2.) Once the anchor is an argument, one read/write pair spans two kinds of
entity, and for the anchored kind the write has no effect: a `FollowMoving` supplies the point to
`Gob.Placed` every frame, so `e.rc` is overwritten before anyone can observe it. Accepting it would hand back
the handle for chaining and change nothing — the failure that takes longest to diagnose, because nothing
reports it and the value even reads back correctly for one frame. The competing shape, "the write silently
detaches the anchor", was rejected for the same reason [D-103](#d-103) refuses two doors: `:add(what, p)`
already means *stands still*, and a setter that quietly changes what kind of thing you are holding is a second
spelling of a constructor.
**Consequences.** The read/write pair stays ONE name (§2.2) — what varies is which half an anchored entity
answers, and it says so. This is the `overlay:position()` rule (038.2, read-only because an overlay's position
is its gob's) arriving at the free anchor, which is the right outcome: the two are the same entity now.
Generally: *when a property's write would be undone by whoever really owns the value, refuse it and name the
owner — a silent no-op is worse than an error, and worse than not having the verb.*
**See.** [D-185](#d-185), [D-103](#d-103), [D-114](#d-114),
[043-vr-namespace](../043-vr-namespace/spec.md).

### D-187 — a verb that existed only for a KIND leaves with that kind; it moves to the thing's new home, it does not simply go ✅ (2026-08-08)
**Decision.** When `gob:overlay()`'s three world kinds moved to `hafen.vr()`, the verbs that only ever served
them moved too. `:scale`/`:alpha`/`:tint`/`:rotate`/`:billboard`/`:spawnData`/`:position` were already on the
entity handle, so those rows simply retire naming it. `ov:offset`'s **three-number world form** was not: it had
no counterpart, so it becomes `<entity>:offset(x, y, z)` (world units, z up) on the handle, refused on a FREE
entity naming `:position(p)`. `ov:offset(x, y)` keeps the screen-pixel meaning and a third argument raises.
**Rationale.** (2026-08-08, 043.3.) The task's stated win was that `ov:offset` stops meaning two things — but
"one meaning" is achieved equally by deleting the world meaning outright, and that would have been a silent
capability loss: `followOff` and the volatile `FollowMoving.off` already existed and were reachable **only**
through the door being removed, so the port of `tagger`'s pin and `:hello follow` would have dropped both from
~1.6 tiles overhead to the gob's feet. The feature's own out-of-scope line says a thing placed after it must
look identical to one placed before, so the offset had to land somewhere; the handle is where the rest of the
world verb set already lived. The refusal on a free entity is [D-186](#d-186) mirrored: each of the two anchors
gets exactly one verb that means *where*, and the other names it.
**Consequences.** A relocation's port surface is not just call sites — it is the fields the deleted door was the
only writer of. Grepping for those is what turns "the verb is gone" into "the verb moved". Generally: *before
deleting a door, look at what only that door could write; a capability with no other entrance disappears with
it, and no compiler and no test will say so.*
**See.** [D-186](#d-186), [D-188](#d-188), [D-103](#d-103),
[043-vr-namespace](../043-vr-namespace/spec.md).

### D-188 — a thing anchored to another is LISTED there read-only and ADDRESSED through its owner — one read, one door ✅ (2026-08-08)
**Decision.** A `hafen.vr()` entity `:add(what, gob)` anchored to a gob appears in that gob's
`gob:overlay():list()` as a read-only entry — `ov:native()` false, `ov:kind()` naming the collection that owns
it (`"sprite"`/`"object"`/`"ghost"`), `ov:res()`/`:count()`/`:info()` answering — while **every** write through
it raises naming that collection, as does `:add` onto its key and `:remove` of it. Its key is generated
(`vr#<n>`, from a per-entity serial), because a thing standing in the world has no name of its own.
**Rationale.** (2026-08-08, 043.3.) 038 faced the same tension and answered it the other way: it **hid** the
anchored entity from the collection it was registered in, so that `gob:overlay()` was the one door
([D-103](#d-103)). That kept one door at the cost of one read — `hafen.vr():sprite():list()` lied about what the
addon had placed. Reversing which side is hidden is strictly better because the two properties are not actually
in tension: **a door is a write, a list is a read.** An entry that refuses every write is not a second door; it
is the answer to "what is at this gob?", which is the identity `gob:overlay()` keeps. And it costs no new
bookkeeping — the by-target index [D-185](#d-185) built for the despawn answers the list in O(1) too, which is
why a second reader of the same shape was worth having rather than a `GAttrib` back-pointer.
**Consequences.** `asOverlay` disappears entirely: with no second creator there is nothing to flag, and the
collections list everything they placed. `LuaOverlay` gains a third origin beside mine/native, and the refusal
messages carry the kind, so a caller is told the exact collection rather than "read-only". Generally: *when one
object belongs to two questions, hide the WRITE from one of them, never the object from the read — a complete
read with refusals is honest, an incomplete read is a lie you cannot see.*
**See.** [D-103](#d-103), [D-185](#d-185), [D-187](#d-187), [D-102](#d-102),
[043-vr-namespace](../043-vr-namespace/spec.md).

### D-189 — a section-wide switch is a SECOND boolean beside the thing's own, never a write over it ✅ (2026-08-08)
**Decision.** `hafen.vr():visible(b)` takes an addon's whole section off screen and puts it back. It is one flag
on the addon, and an entity is in the scene when **its own** `:visible()` says so **AND** the section's does —
two independent booleans, ANDed at the two moments a scene slot is added or removed. So switching the section
back on restores *what was visible*, not everything: an entity hidden on its own handle stays hidden, a
`:visible(b)` written while the section is off is remembered and takes effect when it returns, and a thing
placed while the section is off is created, listed, and simply stays out of the scene until it comes back.
Nothing is destroyed by either write — the gob, the transform and the handle all survive, so `:exists()` stays
true and every verb answers throughout.
**Rationale.** (2026-08-08, 043.4.) Two shapes were rejected. The first has the switch **write** each entity's
own flag and keep a saved list to restore from: that list is a second copy of state, and it is stale the moment
an entity is created or destroyed while the switch is off — and "hide one, hide the section, show the section"
loses which of the two hid it. The second is a single flag consulted at **draw** time: cheap to write, but it
puts a per-frame branch on the render path for a state that changes by hand, which is exactly the shape
[D-099](#d-099)/[D-100](#d-100) exist to refuse. The AND costs nothing, because it is read precisely where the
slot is added or removed — already the only place either write does anything.
**Consequences.** The two `:visible` verbs read **different** things, and must: `<entity>:visible()` is that
entity's own answer and `hafen.vr():visible()` is the section's. Neither is "is it on the screen right now", and
no verb claims to be — which is why the suite's two visual checks are `[manual]` rather than assertions. The
same shape admits a fourth kind for free ([044](../044-spatial-ui/)): the switch walks the registered kinds, not
a branch over three. Generally: *a bulk switch over things that each already carry the same property does not
write that property — it stands beside it, and the effective value is the AND.*
**See.** [D-099](#d-099), [D-100](#d-100), [D-184](#d-184),
[043-vr-namespace](../043-vr-namespace/spec.md).

### D-190 — a two-valued property whose third value is coming is a NAMED MODE, and the absent name RAISES rather than aliasing ✅ (2026-08-08)
**Decision.** `sprite:billboard(b)` became `sprite:facing(mode)`, taking `"fixed"` (an upright world quad) or
`"screen"` (a constant-size blit that squares up to the camera). `"camera"` — a world quad that turns to the
viewer, keeping its world size, perspective and occlusion — is **refused exactly like any other unknown word**,
naming the two that work, until [044](../044-spatial-ui/) builds it. The mode is stored as the string itself, on
the entity and in a saved layout, not as a boolean with a lookup at the edges.
**Rationale.** (2026-08-08, 043.5.) A boolean can only ever answer *which of my two*, so the moment a third
member of the same family exists the flag has to be replaced anyway — and the replacement is a rename of every
call site, which is what made doing it here rather than in 044 free: 043 was porting those sites regardless.
Shipping `"camera"` as an alias for today's screen blit was rejected outright: for one feature's length the word
would name the visual it is precisely *not*, and every reader who tried it would learn the wrong thing and keep
it. A refusal that names the two working modes teaches the boundary instead, and costs one line
([style-guide §7](../../docs/design/style-guide.md) calls the same thing a boundary, not history).
**Consequences.** The mode being a string is what makes a persisted layout round-trip it as itself — `planner`'s
stored `billboard` boolean became a `facing` string with no shim, since nothing is released — and what lets
044 add a value rather than re-open a shape.
`:facing` stays a **construction** property that re-mills the visual in place ([D-113](#d-113)); only the number
of legal values changed. Generally: *when a property's domain is a family rather than a switch, name the members
— and let the member that does not exist yet raise, because an alias is a wrong answer that survives the fix.*
**See.** [D-113](#d-113), [D-184](#d-184), [D-189](#d-189),
[043-vr-namespace](../043-vr-namespace/spec.md), [044-spatial-ui](../044-spatial-ui/spec.md).

### D-194 — a property that lives on the SHARED core reaches every kind on that core, whole ✅ (044.3, 2026-08-08)
**Decision.** `:facing(mode)` was a sprite's property. 044.3 needed its third value, `"camera"`, on a **standing
widget** as well — and rather than give the widget the one mode it was asked for, the property moved down onto
[`LuaWorldEntity`](src/io/brodgar/addon/LuaWorldEntity.java) with **all three of its values**, so a sprite and a
standing widget answer the same `"fixed"`/`"camera"`/`"screen"` and the same refusal. The kinds that do *not* have
the property — a ghost, an object — expose no `:facing` verb at all rather than a stubbed or partial one, because
a model already meets the viewer from every side. Each kind supplies only `visual(gob, mode)`, one method saying
what it looks like in each mode.
**Rationale.** A shared core is a claim about vocabulary, not just about plumbing: two kinds on one core that
answer *different subsets* of one verb are two vocabularies wearing one name, and the reader has to learn which
subset each has — the thing [D-013](#d-013) exists to prevent. The partial alternative was concretely worse here:
`"screen"` would have raised on a widget for exactly one feature's length, and 044.4 (input, "all three modes")
would then have had to widen the domain — re-opening precisely the shape [D-190](#d-190) made a *value* so it
would never be re-opened. Widening a domain later is also the migration the codebase keeps paying for, while
shipping it whole cost one `Drawable` (`LuaSurfaceBillboard`, [`LuaSpriteBillboard`](src/io/brodgar/addon/LuaSpriteBillboard.java)'s
twin over a render target instead of a PNG).
**Consequences.** `:facing` is built once (`VrApi.facingVerb`) and installed on the two handles that have it;
`setEntityFacing` is kind-agnostic and swaps the `Drawable` in place ([D-113](#d-113)), so for a standing widget
the surface, its texture and the widget inside it are the **same objects** before and after — a mode change costs
a quad, not a re-stand. The rule generalises: *when a property moves onto a shared core, move its whole domain, and
give the kinds that do not have it no verb rather than a subset.* The corollary is that a kind joining the core
later either answers the whole property or declines it — there is no third position.
**See.** [D-190](#d-190), [D-113](#d-113), [D-013](#d-013), [D-193](rendering.md#d-193),
[D-185](#d-185), [044-spatial-ui](../044-spatial-ui/spec.md).

### D-210 — a client-local write over what the GAME drew lives on the engine's own object, ends with it, and is undone when its author leaves ✅ (046.1, 2026-08-09)
**Decision.** `gob:scale(k)` — the first WRITE on the Gob handle — keeps its value in a
[`GobScale`](src/io/brodgar/addon/GobScale.java) `GAttrib implements Gob.SetupMod` on the `Gob` itself, exactly
where `haven.GobHealth` keeps a crack texture. Three consequences follow from that one placement and are the
decision: the size **ends with the loaded object** (a gob that unloads returns as a new `Gob`, so re-applying on
`GobAdded` is the addon's, and nothing is persisted anywhere); a gob has **one** size, so the attrib records the
last writer and last write wins rather than layering factors or refusing a second owner; and the write is
**ungated**, on `gob:overlay()`'s footing, because it changes nothing the server, the client or another addon
owns. The ungating is paid for by `UiApi.teardownGobScales`, one walk of the object cache on `:reload`/disable
that reverts only what the departing addon set.
**Rationale.** [D-100](#d-100) already says the state belongs on the thing; this is what that buys on an object
the *game* owns. Propagation costs no seam at all — `Gob.ctick`'s private `updstate()` rebuilds `GobState` from
`setupmods` every tick and pushes `slot.ostate` only when `Utils.eq` says it differs — so the whole feature edits
no `haven` file. That is also why the `Location.scale(k)` op must be **cached per value**: `Location` does not
override `equals`, so a fresh op each tick would re-push every scaled gob's render state forever. The cache is
the mechanism, not an optimisation. Persisting the size across an unload was considered and refused: it would
need an addon-side map keyed by gob id, a prune the attrib gets for free, and a re-application the addon can
write in one line from a subscription it already has.
**Consequences.** Writing exactly `1` **removes** the attrib rather than leaving one that means "nothing", so
"back to the original size" and "carries none of this state" are the same sentence. The mechanism would carry
alpha, tint and visibility on a native gob unchanged — that is deliberate room, not a promise; each is its own
question about writing over what the game drew. The same shape is available to any future client-local property
of a game object: put it on the gob, cache the op, record the owner, and add one line to the teardown list.
**See.** [D-100](#d-100), [D-194](#d-194), [D-211](#d-211), [D-206](virtual-entities.md#d-206),
[046-gob-scale](../046-gob-scale/spec.md).

### D-211 — one verb, two kinds: the direct argument REFUSES where the options-table sibling clamps ✅ (046.1, 2026-08-09)
**Decision.** `gob:scale(k)` refuses `0`, a negative, a non-finite and a non-number, each naming the rule it
broke. Its `hafen.vr()` siblings answer the same `:scale` and **clamp** the same range (`VrApi.clampScale`, to
`0.01..100`). The two are left different on purpose, and the difference is written down in both places rather
than reconciled by making one behave like the other.
**Rationale.** The vr factor's original door is an **options table** handed to `:add` — a bag of optional keys
parsed together, where a refusal has no call site of its own to land on and would take down a whole placement
over one field. `gob:scale(k)` is a direct argument to a verb whose entire job is that argument, so the loudest
possible failure is the one at the line that caused it. Clamping the direct form would be worse than merely
inconsistent: a size is one of the few numbers whose wrong value does not *look* wrong — `0` collapses the model
to a point and a negative flips every triangle's winding — so a silently corrected `0` is a bug with no symptom,
which is the same argument [`Args`](src/io/brodgar/addon/Args.java) makes for refusing an explicit `nil`.
**Consequences.** "One verb, two kinds" is a claim about the **vocabulary** — same name, same meaning, same
read/write arity — not about identical argument policing, and [D-194](#d-194) is unaffected: no kind answers a
*subset* of the property. Both pages say what their own form does. If the vr entities ever gain a direct
`:scale` door that is not part of a table parse, that door refuses too.
**See.** [D-194](#d-194), [D-210](#d-210), [D-072](#d-072), [046-gob-scale](../046-gob-scale/spec.md).

---

### D-212 — a lifecycle PAIR is made exactly-once by a record keyed on the thing, and every ending door calls one method ✅ (047.1, 2026-08-09)
**Decision.** `FlowerMenuOpened`/`FlowerMenuClosed` promise *every open is followed by exactly one close*, and a
radial menu can end through three unrelated doors: the server's `uimsg("act")`, its `uimsg("cancel")`, and the
widget simply dying. The promise is not kept by reasoning about those three. It is kept by a record keyed on the
**menu itself** — [`FlowerMenuApi.live`](src/io/brodgar/addon/FlowerMenuApi.java), a weak map whose *key presence*
means "opened has fired and closed has not" — with all three doors calling one `closed(menu, label)`: the first to
arrive takes the key out and fires, the rest find nothing and are inert. The open seam is idempotent the same way.
**Rationale.** The alternative is a flag per closing path plus a rule about which wins, which is a case analysis
that grows every time a fourth way to end is discovered — and one *was*: the fork's client-side voice petal
cancels the server's menu and handles itself, so it arrives at the `"cancel"` door carrying a real choice. With
the record in place that cost one line (`choosing()` writes the label into the same entry) instead of a fourth
branch. Keying on the thing rather than on a "current menu" field also survives two menus at once without the
second one silently orphaning the first's close. Weak keys mean a session that ends without destroying its
widgets leaves nothing behind, and identity semantics come free because `Widget` does not override `equals`.
**Consequences.** Adding a seam is adding a call, never a rule: a fifth ending door is one line and cannot
double-fire. The `label` argument is *what this door knows*, `null` meaning "ask the record" — so the
authoritative `"act"` label wins where the server committed one, and the recorded one covers everything else. The
same shape fits any future open/close pair over an engine object with more than one ending (a window, a dialog, a
targeting mode). It says nothing about *where* the open fires: that is the separate question of when the thing is
complete, and here it is the END of `added()`, because the petal array is replaced inside it.
**See.** [D-102](#d-102), [D-105](#d-105), [D-100](#d-100), [047-flowermenu](../047-flowermenu/spec.md).

---

### D-214 — a value the server never sends is a CORRELATION the client makes, and it answers nil wherever that correlation cannot vouch ✅ (047.3, 2026-08-09)
**Decision.** `hafen.flowermenu():gob()` names the object a radial menu was opened on. The server sends no such
thing — a menu arrives as a bare `"sm"` widget carrying captions and nothing else — so the answer is built
client-side by [`ClickToken`](src/io/brodgar/addon/ClickToken.java): a press records `(gob, UI.lcc)`, the menu
claims it **once** at the end of `added()`, and the claim is kept only when the recorded point equals the point
the ring is placed at. A claim that does not match spends the token anyway, a press on the ground replaces it,
and a time window backstops the case the point cannot rule out. Everything the correlation cannot vouch for —
an inventory item's menu, the Kin window's own, a menu the player's next click intervened on — answers `nil`.
**Rationale.** The correlator is not a heuristic dressed up as one: `UI.mousedown` assigns `lcc` on **every**
press before dispatching anything, and `FlowerMenu.added()` places the ring at exactly that value. So equality
is a *proof* that no other interaction happened in between, and every way of opening a menu that this feature
does not know about invalidates itself — which is what lets the rule be written once instead of as a growing
list of "and also not when…". The shipped precedent, `VoiceTarget`, attributes a click to a menu by a **time
window alone** and is correspondingly approximate; this is that shape made exact. The alternative — asking the
server, or enumerating the openers — is respectively impossible and unbounded.
**Consequences.** The page states *why* it is `nil`, not just *when*: a reader who knows the answer comes from
the click can predict the nil cases themselves, which is the difference between a documented rule and a list to
memorise. **A second rule falls out and generalises: state that DESCRIBES a transient lives as long as the
transient is readable, not as long as its event window is open.** The attribution is therefore held in a weak
map separate from [D-212](#d-212)'s pairing map — that one loses its key at the close, this one dies with the
widget — because `:list()`/`:count()` keep answering through the 0.25–0.75 s closing animation, and a `:gob()`
that went `nil` at the close would have the three reads describing different menus. The same token shape is
what a later `:item()` (which inventory item opened the menu) would reuse unchanged.
**See.** [D-212](#d-212), [D-213](actions-permissions.md#d-213), [D-101](#d-101), [D-103](#d-103),
[047-flowermenu](../047-flowermenu/spec.md).
