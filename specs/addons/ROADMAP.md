# ROADMAP — future work (high level)

> Blocks not yet built, each with a pointer to its design doc. When a block starts, `/plan`
> turns it into an `NNN-` folder and removes it from here. Order is a suggestion, not a queue.
> (The original implementation plan and coverage-gap audit are fully built and retired; the
> per-item client backings they recorded live on in each block below and in the `NNN-` folders.)

## Sandbox & watchdog hardening — [design/12-security-and-permissions.md](design/12-security-and-permissions.md)
Deferred from 1f/2a: per-env Lua **string metatable** (`LuaString.s_metatable` is process-global —
a hostile addon could tamper with it); a controlled **addon-folder-only `require`**; and the
**draw-time CPU budget gap** (draw callbacks run in `UI.draw` after the per-tick soft-budget
window, so only the per-call instruction cap guards a runaway draw).

## Hook system round-out — mostly RESOLVED by 041 — [design/13-hooks-and-interception.md](design/13-hooks-and-interception.md)
**[041-unified-events](041-unified-events/) settles two of the three.** *Post-hooks* (observe after the
default ran) are **decided against**, not deferred: `before`/`after` was designed and dropped once `:on`
became the one verb everywhere, since two extra verbs in two sections would be the exception rather than
the symmetry that justified them. *Widget-type input targets* are **superseded by something better** — 041
puts input on **any widget handle** (`w:on("MouseDown", fn)`), which needs no factory seam at all because
`Widget.listen` is already a `Widget` method; the three magic string tokens go with it.
What is left here: **hook priority/ordering** (integer priority, first-preventDefault-wins — D-021). 041
deliberately leaves order undefined *between* addons and registration-ordered *within* one, and makes that
safe rather than merely unspecified with its OR cancel rule (any handler cancels, every handler still
runs), so a priority system is now a convenience rather than a correctness gap. Also still unbuilt: **L4**
method replacement / hookable subclasses (needs the factory seam from
[design/08-widget-replacement.md](design/08-widget-replacement.md)).

## Lifecycle conveniences — [design/05-lifecycle-and-reload.md](design/05-lifecycle-and-reload.md)
Deferred from 1f-2/1f-3: **`:reload <id>`** (single-addon reload) and **live enable/disable**
(apply without a full addon-layer reload; today it's WoW apply-on-reload, D-006).

## Item handles & rich item data — mostly ABSORBED by 039 — [design/06-lua-api.md](design/06-lua-api.md)
Deferred from 1c-3: item **`quality`/`contents`** and per-item accessor functions (item handles instead of
snapshots). **[039-uniform-api](039-uniform-api/) task 039.14 takes the handles half** — `widget:items()` and
`hafen.ui():hand()` stop handing back snapshots and hand back an interned **Item** entity — and it carries the
question that was always the hard part: an item has **no stable content id**, it is addressed by a server widget id
that is *reused* when an item moves, so a stale handle must refuse its writes rather than act on whatever now holds
that id. `quality`/`contents` ride along only if `learnings/client-limits.md` says the protocol gives them; that file
already records typed quality as something the client does **not** get, so check before promising it.
What is left here after 039: finer event granularity deferred from 1d-4 — **per-slot `EquipChanged`**, a
**`SkillsChanged`** event.

## `hafen.render` / `hafen.ghost` belong under `hafen.world` — DEFERRED again by 039
The maintainer's framing, and 038 makes it visible rather than causing it: after 038 there are exactly
three places a drawn thing can live — **in the world at a fixed place** (`hafen.render.sprite/object`,
`hafen.ghost`), **on a gob** (`gob:overlay`, which follows by definition — there is no `follow` option
left anywhere), and **on the HUD** (`hafen.ui.overlay`). The first of those is named after the
*mechanism* (rendering) while the other two are named after the *place*, which is the inconsistency.
037 already split LIVE (`hafen.world`) from RECORDED (`hafen.map`) on exactly this axis, so the shape
of the move is known. Not started, and it is a rename of two whole sections plus their docs. **[039-uniform-api](039-uniform-api/)
gave both the new shape but deliberately NOT the move** (its spec §10): unlike `hafen.gob` — which 039 *had* to
resolve, because the shape change forced a decision on what a no-argument `hafen.gob()` means — these two already
have a coherent form and want only a relocation, so doing both at once would make neither reviewable. After 039 the
move is cheap: `hafen.render()` and `hafen.ghost()` are ~64 Lua sites between them, and D-066 (a thing that lives
inside another is a relation on it) is the rule that decides it, exactly as it decided `hafen.gob` and 037's markers.

## Render polish — [design/17-custom-rendering.md](design/17-custom-rendering.md)
Possible R-series follow-ons (animated glTF was explicitly out of the static-subset scope,
[design/18-custom-models-gltf.md](design/18-custom-models-gltf.md)).
*(2b's other two deferrals — per-overlay **anchor/offset** and a per-gob match cache for the
gob-overlay sweep — went to [038-gob-overlays/](038-gob-overlays/), which gives the first one and
deletes the sweep the second was polish on.)*

## Minor client subsystems (the old audit's "A12" tier) — design on demand (`specs/codebase/`)
No design doc yet; each would get one when picked up: **Screenshooter** (programmatic screenshot),
**custom cursors** (`UI.Cursor`/`CursorQuery` — Lua widgets can't answer a `CursorQuery`),
**`GSettings`** (read/write client graphics settings), **`Polity`** (village/realm), **`NewsFeed`**,
**`Cal`** (calendar), **`DynresWindow`** (server-published custom windows — a special replacement
case), **`Partyview`** (the on-screen party HUD, distinct from the `Glob.party` roster we read).

## Package layout: finish the tier-3 split (opened by D-048) — [decisions/architecture-api.md](decisions/architecture-api.md)
019.1 established the rule — `io.brodgar.addon` is the addon *system* + its Lua bridge, and a **client**
capability with an addon consumer gets its own sibling package (`prof`, `ui`, like `voice`) with only its
handle left behind. It moved `Prof` and `ClientPanel` because they were new and cheap; the standing
candidates were **not** touched and want their own feature: **`Json`** (used by `Manifest` and `StoreApi`,
not just Lua), **`Gltf`** + its mesh primitives (`MeshSprite`, `SpriteQuad`), and possibly
**`GhostGob`/`FollowMoving`**. Per candidate the question is the same: does the engine move out with the
handle staying, or is it addon machinery wearing a generic name? Constraints: zero behaviour change, zero
`docs/addons/api/` edits, `addon` itself stays **flat** (16 package-private classes), and anything that must
widen to `public` purely to survive the move is evidence against that move.

## Allocation profiling — the natural 019 follow-up — [019-profiling/](019-profiling/)
019 answers "who costs TIME". Nothing answers "who costs GARBAGE", and the client's own `allocPerFrame`
estimate reads **~11 MB/frame** — a GC sawtooth that stalls a frame every ~2 s (diagnosed with 019 itself, see
`learnings/profiling.md`). Reading the widget layer accounts for well under 1 MB of it, so the rest must be
measured, not guessed. `com.sun.management.ThreadMXBean.getThreadAllocatedBytes()` is a TLAB counter read
(~20 ns) and brackets **the seams 019 already brackets** — per phase, per widget, per addon, per pass — so this
is `p:frame().allocBytes` + an `allocBytes` column in the existing tables, not a new subsystem. Prerequisite for
any renderer work, and it pays the `haven/render/gl` coverage toll (`BGL`, `GLDrawList`, `BufPipe`) on the way.

## `dependencies` / `optional_dependencies` are parsed and then ignored — `Manifest`, `AddonRegistry`
Filed by area `docs` (001.6), which had to state the manifest field by field for `docs/addons/runtime.md`.
`Manifest.load` validates both arrays and keeps them on the object; **nothing reads them again**:
`AddonRegistry.loadAll` iterates `addonDir().listFiles()` in directory order, skips the disabled set, and
runs whatever it finds — no topological sort, no "required addon missing" refusal, no deferral. The docs
therefore describe them as recorded but neither ordered nor enforced, which is accurate and unsatisfying.
Two honest resolutions, and the choice is a design call: **drop the fields** (each addon has its own
`Globals`, so there is no import to sequence — a dependency can only mean "load me after that one", which
matters solely for cross-addon side effects through the client), or **implement them** (order the load,
refuse an addon whose hard dependency is missing or errored, and surface that as an error row + an
`:addons` status, the way a manifest error already is). Whichever wins, `runtime.md`'s manifest table and
`docs/addons/examples.md` need one line changed with it.
