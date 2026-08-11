# 046-gob-scale — Plan

## Approach

**The size lives on the gob, as the engine's own kind of per-gob state.** A
`GobScale extends GAttrib implements Gob.SetupMod` in `src/io/brodgar/addon/`, keyed by its own `attrclass`
(a direct `GAttrib` subclass), so it shares a slot with nothing the server sets. `gobstate()` hands back a
**cached** `Location.scale(k)` — minted only when the number changes, `null` at `1`. This is `GobHealth`'s
shape exactly (`GobHealth.java:54`), and `LuaGobOverlay`'s precedent for an addon-owned attrib on a game gob.

**Propagation is the engine's, and costs no seam.** `Gob.ctick` calls the private `updstate()` every tick
(`Gob.java:464`), which rebuilds `GobState` and pushes `slot.ostate` **only** when `Utils.eq(mods)` says it
differs (`Gob.java:741`). A cached op is the same instance on every tick where nothing changed, so a scaled
gob costs no extra push; a change lands on the next tick. **This feature edits no `haven` file at all.**

**Where it composes.** `GobState` is the gob's own child render slot, under `Placed`'s `"gobx"` translate and
`"gob"` rotate, and `Location` composes multiplicatively down the tree ⇒ **T·R·S**: scaled in place around
the gob's origin, then rotated, then translated (`learnings/ghosts.md` V6). That is the same level and the
same math `GhostGob.obstate` already uses, so a native gob and a ghost scale identically — which is the whole
argument for the two carrying one verb.

**The verb.** `:scale` on `LuaGob.methods(owner)`: bare reads, one number writes and hands the **Gob** back —
the read/write pair every `hafen.vr()` entity answers (`api/vr/README.md`). The read resolves through
`gob(self, "scale")` and answers `nil` when the gob is gone; a write on a gone gob is inert and does not
throw, which is the Gob page's standing rule rather than an exception carved for the first write.
Validation: finite and `> 0` — `0` collapses the model and a negative flips triangle winding, so both are
refused naming the rule.

**Ownership and teardown.** The attrib records the `Addon` that last wrote it — a gob has ONE size, so last
write wins rather than layering or refusing. `UiApi.teardownGobScales(a)` is the twin of `teardownGobOverlays`
(`UiApi.java:1793`): one `AddonManager.allGobs()` walk under the UI monitor, `delattr` where the owner is the
departing addon, and one line in `AddonRegistry.teardown`'s ordered list beside it. D-100's shape — the state
is on the thing, and a single sweep is what that costs, once.

## Files to create / modify

- `src/io/brodgar/addon/GobScale.java` — **NEW**: value + owner + cached `Pipe.Op`; `gobstate()`; `on(Gob)` /
  `apply(Gob, Addon, float)` statics, following `LuaGobOverlay.on`.
- `src/io/brodgar/addon/LuaGob.java` — the `:scale` read/write pair; the handle's first write verb.
- `src/io/brodgar/addon/UiApi.java` — `teardownGobScales(Addon)`, the twin of `teardownGobOverlays`.
- `src/io/brodgar/addon/AddonRegistry.java` — one line in the ordered teardown list.
- `addons/046-gob-scale.1/` — the suite (`manifest.json` + `main.lua`, `:t046-1`).
- `docs/addons/api/gob.md` — *(/end)* a **Write** section: the verb, the in-place meaning, ungated on
  `gob:overlay()`'s footing, ends with the loaded object, and the `goback` caveat. **256 lines of a 300
  ceiling** — it is written tight or something older is tightened with it.
- `docs/addons/api/README.md` + `docs/addons/README.md` — *(/end)* the Gob rows, which today say read-only.
- `specs/codebase/world-3d.md` — *(/end)* extend with `Gob.SetupMod` / `GobState` / the per-tick `updstate`
  compare: coverage this feature had to read from source and no subsystem file holds.

## Risks & gotchas

- **`Location` does not override `equals`.** A fresh op each tick would push `slot.ostate` every tick on
  every scaled gob. The cache is not an optimisation, it is the mechanism.
- **A change lands on the next tick, never synchronously** (`updstate` is private and `ctick`-only). The
  suite may read back its own value immediately, but nothing may assert on the *drawn* size in the same frame.
- `ctick`'s `virtual && ols.isEmpty() && no Drawable ⇒ remove` (`Gob.java:465`) is untouched: native gobs are
  not virtual and this attrib is not a `Drawable`. Do not make it one.
- **`setattr` can throw `Loading`, but only for a `RenderTree.Node` attrib** — ours is not a node, so it
  cannot. Adding `RenderTree.Node` later would quietly buy that exception.
- **`goback("gobx")` sprites** (`resutil.CSprite`) reset past both facing and scale — carried verbatim from
  the ghost caveat (`learnings/ghosts.md` V6). A documented limit, not a bug to chase.
- A gob that unloads returns as a **new** `Gob`; the attrib went with the old one. That is the contract, and
  the suite says so with a `[manual]` walk rather than hiding it.
- Lua is marshalled onto the tick, so the write is already on the UI thread; the teardown sweep still takes
  `synchronized(ui)` like its twin, because teardown can run off it.
- The **player's own gob** is the most visible check and the one the suite should use — the camera follows
  the feet, so a scaled player does not move the view.

## Discarded alternatives

- `placestate()` rather than `gobstate()` — also composes after T·R, but `gobstate` is the level `GhostGob`
  scales at, and one level for both kinds is the point.
- A `Drawable.placer()` override returning a scaled `Matrix4f` — puts scale inside the *rotation* the engine
  re-reads every frame, mixing two meanings, and it is per-drawable rather than per-gob.
- A per-addon `Map<gobId, scale>` on the `Addon` — D-100 refuses it (state belongs on the thing) and it needs
  a prune the attrib gets for free when the gob dies.
- Remove-and-re-add to the scene, as `GhostGob` does for tint/alpha — unnecessary (`GobState` compares mods)
  and far too invasive on a gob the game owns.
- Multiplying two addons' scales, or refusing a second owner the way a hidden window does (D-069) — a gob has
  one size, and a scale is not a hold.
- Non-uniform `scale(x, y, z)` — one number, like every sibling (spec: out of scope).
