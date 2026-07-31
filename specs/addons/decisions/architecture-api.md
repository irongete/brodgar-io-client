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
