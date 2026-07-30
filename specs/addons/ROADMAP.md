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

## Hook system round-out — [design/13-hooks-and-interception.md](design/13-hooks-and-interception.md)
Designed but unbuilt: **hook priority/ordering** (integer priority, first-preventDefault-wins —
D-021) and **post-hooks** (observe after the default ran). Also widget-*type* input-hook targets
(needs the factory seam from [design/08-widget-replacement.md](design/08-widget-replacement.md)).

## Lifecycle conveniences — [design/05-lifecycle-and-reload.md](design/05-lifecycle-and-reload.md)
Deferred from 1f-2/1f-3: **`:reload <id>`** (single-addon reload) and **live enable/disable**
(apply without a full addon-layer reload; today it's WoW apply-on-reload, D-006).

## Item handles & rich item data — [design/06-lua-api.md](design/06-lua-api.md)
Deferred from 1c-3: item **`quality`/`contents`** and per-item accessor functions (item handles
instead of snapshots). Also finer event granularity deferred from 1d-4: **per-slot
`EquipChanged`**, a **`SkillsChanged`** event.

## Overlay/render polish — [design/17-custom-rendering.md](design/17-custom-rendering.md)
Deferred from 2b: per-overlay **anchor/offset** and a per-gob match cache for the gob-overlay
sweep. Possible R-series follow-ons (animated glTF was explicitly out of the static-subset scope,
[design/18-custom-models-gltf.md](design/18-custom-models-gltf.md)).

## Finish the OOP migration (the exit path opened by 017-gob-oop) — [017-gob-oop/](017-gob-oop/)
`017` migrates **only** Gob and leaves the rest flat *on purpose*; that coexistence is debt with a
deadline, not a resting state. Two follow-ons close it: **Fight + Party to OOP**, which is what
restores the retired GobRef tokens as `hafen.target():gob()` and `hafen.party()[1]:gob()` (until
then the combat target's gob is unreachable — an accepted regression); and then the **final
demolition**, which migrates whatever namespaces remain and deletes the transitional markers 017
plants (the `design/06-lua-api.md` banner, the `(SUPERSEDED by D-044)` headers, and the
"Gob is OOP, the rest is flat" paragraph in `API-REFERENCE.md`).

## Minor client subsystems (the old audit's "A12" tier) — design on demand (`specs/codebase/`)
No design doc yet; each would get one when picked up: **Screenshooter** (programmatic screenshot),
**custom cursors** (`UI.Cursor`/`CursorQuery` — Lua widgets can't answer a `CursorQuery`),
**`GSettings`** (read/write client graphics settings), **`Polity`** (village/realm), **`NewsFeed`**,
**`Cal`** (calendar), **`DynresWindow`** (server-published custom windows — a special replacement
case), **`Partyview`** (the on-screen party HUD, distinct from the `Glob.party` roster we read).
