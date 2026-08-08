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

## World-space text — opened by [043-vr-namespace](043-vr-namespace/)
`ov:text(s)` is **screen space**: a label drawn at the gob's projected point, at a constant size, however
far away you are. Nothing puts a label *in* the world — with perspective, shrinking as you walk off, and
occluded by what stands in front of it. It can be faked with a `hafen.vr():widget()` holding a single
label, but that is a whole widget tree, a texture and a draw pass to put one word in the air. A
`hafen.vr():text()` on the shared entity core would take the same anchors and the same `:facing` modes as
its siblings; the open question is whether it earns a collection or is better served by making the
widget path cheap enough that nobody notices.

## World-space shapes — opened by [043-vr-namespace](043-vr-namespace/)
Nothing draws a line, a path, an area outline or a radius **in the world**. A route, the border of a
claim you are planning, a waypoint trail and a range circle are all faked today with repeated sprites.
A `hafen.vr():shape()` — lines, polylines, filled polygons on the ground or upright — is the piece
missing from spatial visualisation, and the one that would make `planner`-style addons stop
approximating. `codebase/world-3d.md` already covers the `Model`/`Material` path a line list would use.

## A free world entity draws over ground that has unloaded — raised verifying [043.2](043-vr-namespace/)
Walk away from a `hafen.vr()` thing you placed at a point and the terrain cuts off while the prop keeps
drawing, hanging over the void until it leaves the render range; walk back and it is still there. Nothing is
broken — a client-only gob is in no `OCache` so nothing removes it (the premise the anchored entity's death
rests on), and `Gob.Placed.autotick` catches the `Loading` from the missing tile and keeps the previous
placement rather than dropping the gob (`learnings/ghosts.md`, `codebase/world-3d.md`). It is a *cosmetic*
gap: hiding a free entity while its tile is unloaded is a change to what is drawn, which 043 puts out of
scope. The shape it wants is the one 043.4 already builds for `:visible` — per-entity desired state, not a
flag read at the draw — driven by the tile's own `Waitable` rather than any sweep.

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
